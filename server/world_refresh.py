"""Startup world preservation and recovery of earlier terrain refresh transactions.

Ordinary starts never regenerate terrain. A legacy private policy is read only
to report that it is no longer acted on; it is never rewritten by startup. An
interrupted transaction left by an earlier release is still recovered before
the startup backup, but recovery does not authorize a new refresh.
"""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import stat
import uuid
import zipfile
import tempfile
import sys
import subprocess
import community
import terrain_storage

POLICY = ".world-refresh.json"
JOURNAL = ".world-refresh-transaction.json"
MARKERS = ("infinity-built-in-lobbies.json", "infinity-built-in-maps.json",
           "infinity-course-selector-signs.json")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


_RECOVERY_DIGEST_READER = r'''
import hashlib, os, stat, sys
try:
    expected = tuple(int(value) for value in sys.argv[2:])
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        def identity(info):
            return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                    info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or identity(before) != expected:
            sys.exit(21)
        value = hashlib.sha256()
        remaining = before.st_size
        while remaining:
            block = os.read(fd, min(1024 * 1024, remaining))
            if not block:
                sys.exit(22)
            value.update(block)
            remaining -= len(block)
        if os.read(fd, 1) or identity(os.fstat(fd)) != expected:
            sys.exit(22)
    finally:
        os.close(fd)
    # Emit success only after the identity checks and file close succeed.
    sys.stdout.buffer.write(value.hexdigest().encode("ascii") + b"\n\x00INFINITY_RECOVERY_DIGEST_OK\n")
    sys.stdout.buffer.flush()
except (OSError, ValueError):
    sys.exit(23)
'''
_RECOVERY_DIGEST_TRAILER = b"\n\x00INFINITY_RECOVERY_DIGEST_OK\n"


def _run_recovery_reader(program, path, expected, label):
    """Read in a direct child and bound cleanup after a failed read."""
    reader = subprocess.Popen([sys.executable, "-I", "-S", "-c", program,
                               str(path), *(str(value) for value in expected)],
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, close_fds=True)
    try:
        try:
            output, _ = reader.communicate(timeout=120)
            return output, reader.returncode
        except BaseException as original:
            try:
                if reader.poll() is None:
                    try:
                        reader.kill()
                    except ProcessLookupError:
                        pass  # It exited between poll and kill; wait still confirms it.
                reader.wait(timeout=5)
            except BaseException as cleanup_error:
                if isinstance(original, (KeyboardInterrupt, SystemExit)):
                    raise original from cleanup_error
                if isinstance(cleanup_error, (KeyboardInterrupt, SystemExit)):
                    raise cleanup_error from original
                raise ValueError("Terrain " + label + " reader could not be confirmed stopped; "
                                 "keep the launcher marker for host review") from cleanup_error
            raise
    finally:
        pending = sys.exc_info()[1]
        try:
            if reader.stdout is not None:
                reader.stdout.close()
        except BaseException as close_error:
            if pending is not None:
                if (pending.__cause__ is not None and close_error.__cause__ is None
                        and close_error is not pending.__cause__):
                    close_error.__cause__ = pending.__cause__
                if (isinstance(close_error, (KeyboardInterrupt, SystemExit))
                        and not isinstance(pending, (KeyboardInterrupt, SystemExit))):
                    raise close_error from pending
                raise pending from close_error
            raise


def _run_digest_reader(path, expected):
    return _run_recovery_reader(_RECOVERY_DIGEST_READER, path, expected, "file digest")


def _recovery_digest(path, info):
    """Read a kept file outside the process holding the POSIX world lock.

    Closing a second descriptor to that lock's inode in the lock-owning
    process would release its record lock. A substituted hardlink must not
    make the recovery reader do that. This does not exclude a later writer.
    """
    expected = (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    try:
        output, returncode = _run_digest_reader(path, expected)
        current = path.lstat()
        current_identity = (current.st_dev, current.st_ino, current.st_mode, current.st_nlink,
                            current.st_size, current.st_mtime_ns, current.st_ctime_ns)
        value = output[:64]
        if (returncode != 0 or current_identity != expected
                or len(output) != 64 + len(_RECOVERY_DIGEST_TRAILER)
                or output[64:] != _RECOVERY_DIGEST_TRAILER
                or any(byte not in b"0123456789abcdef" for byte in value)):
            raise ValueError("Terrain recovery file changed; keep it for host review")
        return value.decode("ascii")
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("Terrain recovery file could not be checked; keep it for host review") from error


_POLICY_READER = r'''
import os, stat, sys
try:
    expected = tuple(int(value) for value in sys.argv[2:])
    fd = os.open(sys.argv[1], os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_NONBLOCK", 0))
    try:
        def identity(info):
            return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                    info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or identity(before) != expected:
            sys.exit(21)
        remaining = before.st_size
        data = bytearray()
        while remaining:
            block = os.read(fd, min(65536, remaining))
            if not block:
                sys.exit(22)
            data.extend(block)
            remaining -= len(block)
        if identity(os.fstat(fd)) != expected:
            sys.exit(23)
    finally:
        os.close(fd)
    sys.stdout.buffer.write(data + b"\x00INFINITY_POLICY_READ_OK\n")
    sys.stdout.buffer.flush()
except (OSError, ValueError):
    sys.exit(24)
'''
_POLICY_READER_TRAILER = b"\x00INFINITY_POLICY_READ_OK\n"
_POLICY_SIZE_LIMIT = 1024 * 1024
_JOURNAL_SIZE_LIMIT = 16 * 1024 * 1024


def _run_policy_reader(path, expected):
    """Read in a direct child and bound cleanup after a failed read."""
    return _run_recovery_reader(_POLICY_READER, path, expected, "policy")


def _read_policy_bytes(path, info):
    """Read the policy without opening its inode in the native-lock owner."""
    if info.st_nlink != 1 or info.st_size > _POLICY_SIZE_LIMIT:
        raise ValueError("Terrain policy is linked or too large; keep it for host review")
    expected = (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    try:
        output, returncode = _run_policy_reader(path, expected)
        current = _terrain_entry(path)
        current_identity = None if current is None else (
            current.st_dev, current.st_ino, current.st_mode, current.st_nlink,
            current.st_size, current.st_mtime_ns, current.st_ctime_ns)
        if (returncode != 0 or current_identity != expected
                or len(output) != info.st_size + len(_POLICY_READER_TRAILER)
                or not output.endswith(_POLICY_READER_TRAILER)):
            raise ValueError("Terrain policy changed or its reader failed; keep it for host review")
        return output[:info.st_size]
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("Terrain policy could not be checked; keep it for host review") from error


def _journal_identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
            info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def _check_journal(path, expected):
    current = _terrain_entry(path)
    if current is None or _journal_identity(current) != expected:
        raise ValueError("Terrain journal changed; keep its evidence for host review")


def _run_journal_reader(path, expected):
    # The policy reader also performs the required bounded regular-file read
    # and emits its trailer only after identity checks and descriptor close.
    return _run_recovery_reader(_POLICY_READER, path, expected, "journal")


def _read_journal_bytes(path, info):
    if info.st_nlink != 1 or info.st_size > _JOURNAL_SIZE_LIMIT:
        raise ValueError("Terrain journal is linked or too large; keep it for host review")
    expected = _journal_identity(info)
    try:
        output, returncode = _run_journal_reader(path, expected)
        _check_journal(path, expected)
        if (returncode != 0 or len(output) != info.st_size + len(_POLICY_READER_TRAILER)
                or not output.endswith(_POLICY_READER_TRAILER)):
            raise ValueError("Terrain journal changed or its reader failed; keep it for host review")
        return output[:-len(_POLICY_READER_TRAILER)]
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("Terrain journal could not be checked; keep it for host review") from error


def sync_directory(path):
    if os.name == "posix":
        directory_flag = getattr(os, "O_DIRECTORY", None)
        if not directory_flag:
            raise OSError("Directory synchronization requires directory-only open support")
        # Opening and closing a substituted session.lock in this process can
        # release its POSIX record lock. Require a directory at the open itself.
        descriptor = os.open(path, os.O_RDONLY | directory_flag | getattr(os, "O_NONBLOCK", 0))
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)


def move(source, target):
    os.replace(source, target)
    sync_directory(source.parent)
    sync_directory(target.parent)


def atomic_bytes(path, value):
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", dir=path.parent,
                                         prefix=path.name + ".refresh-tmp-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
            if sys.platform == "darwin":
                import fcntl
                fcntl.fcntl(stream.fileno(), fcntl.F_FULLFSYNC)
        temporary.chmod(0o600)
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def atomic_json(path, value):
    atomic_bytes(path, (json.dumps(value, indent=2) + "\n").encode())


def _terrain_entry(path):
    """Return an observed entry, treating only a missing path as absent."""
    try:
        return path.lstat()
    except FileNotFoundError:
        return None
    except OSError as error:
        raise ValueError("A terrain path could not be checked; keep its evidence for host review") from error


def inside(parent, relative):
    parent_info = _terrain_entry(parent)
    if parent_info is not None and stat.S_ISLNK(parent_info.st_mode):
        raise ValueError("Terrain refresh refuses symbolic links")
    parent = parent.resolve()
    value = PurePosixPath(relative)
    if value.is_absolute() or ".." in value.parts or not value.parts:
        raise ValueError("Unsafe terrain refresh path")
    path = parent.joinpath(*value.parts)
    cursor = parent
    for part in value.parts:
        cursor = cursor / part
        info = _terrain_entry(cursor)
        if info is not None and stat.S_ISLNK(info.st_mode):
            raise ValueError("Terrain refresh refuses symbolic links")
    if not path.resolve().is_relative_to(parent.resolve()):
        raise ValueError("Terrain refresh path escapes its folder")
    return path


def release_id(root):
    lock = json.loads((root / "dependencies.lock.json").read_text())
    mods = [x for x in lock["bundled"]
            if x["path"].startswith("fabric/mods/Infinity-Armor-")]
    if len(mods) != 1 or digest(inside(root, mods[0]["path"])) != mods[0]["sha256"]:
        raise ValueError("Verify the installed Infinity mod before refreshing terrain")
    with zipfile.ZipFile(inside(root, mods[0]["path"])) as archive:
        version = json.loads(archive.read("fabric.mod.json"))["version"]
    if not isinstance(version, str) or not version:
        raise ValueError("Installed Infinity mod has no valid version")
    return version + ":" + mods[0]["sha256"]


def read_policy(root):
    path = root / POLICY
    info = _terrain_entry(path)
    if info is None:
        return None
    if stat.S_ISLNK(info.st_mode):
        raise ValueError("Terrain policy cannot be a symbolic link")
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("Terrain policy must be a regular file; keep it for host review")
    value = json.loads(_read_policy_bytes(path, info))
    if (not isinstance(value, dict) or value.get("format") != 1 or type(value.get("enabled")) is not bool
            or type(value.get("refresh_next_start", False)) is not bool
            or not isinstance(value.get("last_release", ""), str)):
        raise ValueError("Invalid private terrain refresh policy")
    return value


def recover(root, emit=None, lease_fd=None):
    path = root / JOURNAL
    journal_info = _terrain_entry(path)
    if journal_info is None:
        return
    # Recovery can change saved terrain. Do not accept an unbacked claim that
    # a launcher owns the outer lease. This check cannot exclude a late swap
    # or a noncooperating writer with the same filesystem authority.
    community.verify_borrowed_launcher_lease(root, lease_fd)
    if stat.S_ISLNK(journal_info.st_mode):
        raise ValueError("Terrain journal cannot be a symbolic link")
    if not stat.S_ISREG(journal_info.st_mode):
        raise ValueError("Terrain journal must be a regular file; keep it for host review")
    journal_identity = _journal_identity(journal_info)
    record = json.loads(_read_journal_bytes(path, journal_info).decode("utf-8"))
    if record.get("format") != 1 or record.get("state") not in ("moving", "committed"):
        raise ValueError("Invalid terrain refresh recovery journal")
    world = inside(root / "fabric", record["world"])
    rollback = inside(root / "backups", record["rollback"])
    if not rollback.name.startswith("Terrain-Refresh-"):
        raise ValueError("Invalid terrain refresh recovery folder")
    # Recovery can move or delete old terrain before the startup backup is
    # created. The launcher lease alone does not stop another Minecraft server
    # started outside this launcher. Lock the journal's world for the entire
    # recovery; an advisory lock does not exclude noncooperating writers.
    session_lock = community.acquire_world_session_lock(world)
    try:
        return _recover_with_world_lock(root, emit, path, record, world, rollback,
                                        session_lock, lease_fd, journal_identity)
    finally:
        os.close(session_lock)


def _recovery_item(world, rollback, relative, kept_files):
    """Inspect one recovery item without deleting or moving any entry."""
    source = inside(world, relative)
    saved = inside(rollback / "terrain", relative)
    saved_info = _terrain_entry(saved)
    source_info = _terrain_entry(source)
    if saved_info is None:
        if source_info is None:
            raise ValueError("Old terrain is missing; restore the verified full backup manually")
        if not (stat.S_ISREG(source_info.st_mode) or stat.S_ISDIR(source_info.st_mode)):
            raise ValueError("Unexpected restored terrain entry; keep it for host review")
        return source, saved, [], []
    if not (stat.S_ISREG(saved_info.st_mode) or stat.S_ISDIR(saved_info.st_mode)):
        raise ValueError("Unexpected archived terrain entry; keep it for host review")
    if source_info is None:
        return source, saved, [], []

    files = []
    directories = []
    pending = [source]
    while pending:
        item = pending.pop()
        relative_item = item.relative_to(world).as_posix()
        inside(world, relative_item)
        info = item.lstat()
        if stat.S_ISDIR(info.st_mode):
            directories.append(item)
            # scandir propagates traversal errors and does not follow links.
            with os.scandir(item) as entries:
                pending.extend(item / entry.name for entry in entries)
        elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
            expected_hash = kept_files.get(relative_item)
            if expected_hash is None or expected_hash != _recovery_digest(item, info):
                raise ValueError("Unexpected new terrain exists; keep it for host review")
            files.append(item)
        else:
            raise ValueError("Unexpected terrain recovery entry; keep it for host review")

    # Empty or unrelated nested directories have no recorded kept-file owner.
    # Retain them for review rather than deleting them as incidental cleanup.
    known_directories = {source}
    for file in files:
        parent = file.parent
        while parent != source and parent.is_relative_to(source):
            known_directories.add(parent)
            parent = parent.parent
    if any(directory not in known_directories for directory in directories):
        raise ValueError("Unexpected terrain recovery directory; keep it for host review")
    return source, saved, files, sorted(directories, key=lambda item: len(item.parts), reverse=True)


def _recover_with_world_lock(root, emit, path, record, world, rollback, session_lock, lease_fd,
                             journal_identity):
    _check_journal(path, journal_identity)
    if record["state"] == "moving":
        moved_paths = record.get("moved_paths")
        if not isinstance(moved_paths, list) or any(not isinstance(relative, str) for relative in moved_paths):
            raise ValueError("Invalid terrain refresh recovery paths")
        kept_files = record.get("kept_files", {})
        if not isinstance(kept_files, dict):
            raise ValueError("Invalid protected terrain recovery records")
        # Validate all entries before any move: an old or damaged journal must
        # never be allowed to replace the inode whose lock guards recovery.
        normalized = []
        for relative in moved_paths:
            parts = PurePosixPath(relative).parts
            if parts and parts[0].casefold() == "session.lock":
                raise ValueError("Terrain recovery cannot move the Minecraft world lock")
            inside(world, relative)
            inside(rollback / "terrain", relative)
            normalized.append(tuple(part.casefold() for part in parts))
        ordered = sorted(normalized)
        if any(child[:len(parent)] == parent for parent, child in zip(ordered, ordered[1:])):
            raise ValueError("Overlapping terrain recovery paths require host review")
        # Check all items before the first mutation. Repeat the check at each
        # move because a prior check does not exclude a concurrent writer.
        for relative in reversed(moved_paths):
            _recovery_item(world, rollback, relative, kept_files)
        for relative in reversed(moved_paths):
            community.verify_borrowed_launcher_lease(root, lease_fd)
            community.verify_world_session_lock(world, session_lock)
            source, saved, existing, directories = _recovery_item(world, rollback, relative, kept_files)
            saved_info = _terrain_entry(saved)
            source_info = _terrain_entry(source)
            _check_journal(path, journal_identity)
            if saved_info is None:
                if source_info is None:
                    raise ValueError("Old terrain is missing; restore the verified full backup manually")
                if not (stat.S_ISREG(source_info.st_mode) or stat.S_ISDIR(source_info.st_mode)):
                    raise ValueError("Unexpected restored terrain entry; keep it for host review")
            else:
                if not (stat.S_ISREG(saved_info.st_mode) or stat.S_ISDIR(saved_info.st_mode)):
                    raise ValueError("Unexpected archived terrain entry; keep it for host review")
                if source_info is not None:
                    if not (stat.S_ISREG(source_info.st_mode) or stat.S_ISDIR(source_info.st_mode)):
                        raise ValueError("Unexpected terrain recovery entry; keep it for host review")
                    for file in existing:
                        file.unlink()
                    for folder in directories:
                        folder.rmdir()
                source.parent.mkdir(parents=True, exist_ok=True)
                move(saved, source)
        # The moving transaction has never written its new policy. Preserve
        # the owner's edits, especially a decision to disable refresh.
        community.verify_borrowed_launcher_lease(root, lease_fd)
        community.verify_world_session_lock(world, session_lock)
        record["state"] = "rolled_back"
        message = ("Rolled back an interrupted terrain refresh from an earlier start; original terrain restored. "
                   "Recovery record: backups/" + rollback.name)
    else:
        # This finishes the earlier, already-committed transaction only; it
        # records that transaction's result and never requests another refresh.
        community.verify_borrowed_launcher_lease(root, lease_fd)
        community.verify_world_session_lock(world, session_lock)
        current = read_policy(root)
        _check_journal(path, journal_identity)
        if current is not None:
            atomic_json(root / POLICY, dict(current,
                        last_release=record["new_policy"]["last_release"], refresh_next_start=False))
        message = ("Finished recording a terrain refresh completed by an earlier start; "
                   + (POLICY + " was updated to clear its pending request. " if current is not None else "")
                   + "Recovery record: backups/" + rollback.name)
    community.verify_borrowed_launcher_lease(root, lease_fd)
    community.verify_world_session_lock(world, session_lock)
    _check_journal(path, journal_identity)
    atomic_json(rollback / "MANIFEST.json", record)
    community.verify_borrowed_launcher_lease(root, lease_fd)
    community.verify_world_session_lock(world, session_lock)
    _check_journal(path, journal_identity)
    path.unlink()
    sync_directory(root)
    if emit:
        emit(message)


def startup_check(root, emit=print):
    """Keep every saved world as-is; report, but never act on, a legacy refresh policy.

    Runs after recovery and the startup backup, before Java opens. It writes nothing.
    """
    if _terrain_entry(root / JOURNAL) is not None:
        raise ValueError("An earlier terrain refresh is unrecovered; startup stopped before opening the world")
    try:
        policy = read_policy(root)
        if policy is None:
            return
    except (OSError, ValueError):
        emit("Existing worlds kept. " + POLICY + " is unreadable and was left unchanged; "
             "this launcher does not regenerate terrain.")
        return
    if policy["enabled"] or policy.get("refresh_next_start"):
        emit("Existing worlds kept. This launcher does not regenerate terrain; the legacy "
             + POLICY + " request was left unchanged and is not acted on.")


def verify_backup(root, world, backup_name):
    if not backup_name or Path(backup_name).name != backup_name:
        raise ValueError("A fresh stopped-world backup is required before regeneration")
    archive = inside(root / "backups", backup_name)
    prefix = world.relative_to(root / "fabric").as_posix() + "/"
    with zipfile.ZipFile(archive) as output:
        if output.testzip() is not None:
            raise ValueError("Terrain regeneration backup is corrupt")
        files = {p.relative_to(world).as_posix(): p for p in world.rglob("*")
                 if p.is_file() and p.name != "session.lock"}
        names = [n[len(prefix):] for n in output.namelist() if n.startswith(prefix)]
        if len(names) != len(set(names)) or set(names) != set(files):
            raise ValueError("Terrain regeneration backup does not match this saved world")
        for name, file in files.items():
            inside(world, name)
            if hashlib.sha256(output.read(prefix + name)).hexdigest() != digest(file):
                raise ValueError("Terrain regeneration backup differs from the stopped world")
    return archive, files


def regenerate(root, level_name, backup_name, emit=print, lease_fd=None):
    """The earlier destructive transaction, retained so its journal/recovery format stays defined.

    The launcher does not call this. Any future use needs a new, separately
    approved design; an old policy flag is not approval for it.
    """
    root = root.resolve()
    # The caller holds the launcher's exclusive lease and has not started Java.
    if not (root / "launcher.lock").is_file():
        raise ValueError("Terrain refresh requires a locked, stopped launcher")
    community.verify_borrowed_launcher_lease(root, lease_fd)
    recover(root, lease_fd=lease_fd)
    policy = read_policy(root)
    if policy is None or not policy["enabled"]:
        return None
    release = release_id(root)
    world = inside(root / "fabric", level_name)
    if not (world / "level.dat").is_file():
        policy.update(last_release=release, refresh_next_start=False)
        atomic_json(root / POLICY, policy)
        return None
    if not policy.get("last_release") and not policy.get("refresh_next_start"):
        policy["last_release"] = release
        atomic_json(root / POLICY, policy)
        emit("Terrain refresh enabled for future mod updates; current terrain retained.")
        return None
    if policy.get("last_release") == release and not policy.get("refresh_next_start"):
        return None
    # Only 26.3's separate player/entity layout is supported; never guess at a legacy save.
    if not (world / "players/data").is_dir() or not (world / "dimensions").is_dir():
        raise ValueError("Terrain refresh requires the converted Minecraft 26.3 world layout")
    archive, files = verify_backup(root, world, backup_name)
    if policy.get("safety_strategy", "protected_areas") != "protected_areas":
        raise ValueError("Choose the supported protected_areas terrain refresh strategy")
    # Regeneration is destructive: the full-world backup and its folder entries
    # must be confirmed on stable storage before any terrain is prepared or moved.
    community.confirm_backup_durable(root, archive.name)
    kept, protected = terrain_storage.prepare(world)
    paths = []
    dimensions = sorted({p.parent.parent for p in (world / "dimensions").rglob("r.*.*.mca")
                         if p.parent.name == "region"})
    for dimension in dimensions:
        for child in ("region", "poi", "entities", "data/minecraft/chunk_tickets.dat", "data/minecraft/raids.dat"):
            path = dimension / child
            if path.exists():
                paths.append(path.relative_to(world).as_posix())
    # Keep generation provenance alongside protected lobby/course fragments.
    # The native builders refuse to replace occupied unmarked areas; known
    # course markers permit safe reconciliation of missing generated blocks.
    if not paths:
        raise ValueError("No saved terrain found; regeneration was not performed")
    preserved = {name: digest(file) for name, file in files.items()
                 if not any(name == p or name.startswith(p + "/") for p in paths)}
    rollback = root / "backups" / ("Terrain-Refresh-" + uuid.uuid4().hex)
    rollback.mkdir(mode=0o700)
    sync_directory(rollback.parent)
    new_policy = dict(policy, last_release=release, refresh_next_start=False)
    record = {"format": 1, "state": "moving", "world": level_name,
              "rollback": rollback.name, "backup": archive.name,
              "backup_sha256": digest(archive), "old_policy": policy,
              "new_policy": new_policy, "moved_paths": paths,
              "preserved_files": preserved,
              "kept_files": {name: hashlib.sha256(data).hexdigest() for name, data in kept.items()},
              "protected_chunks": protected}
    for name, data in kept.items():
        target = inside(rollback / "kept", name)
        target.parent.mkdir(parents=True, exist_ok=True)
        atomic_bytes(target, data)
    atomic_json(rollback / "MANIFEST.json", record)
    (rollback / "RECOVERY.md").write_text(
        "Save and stop the server before restoring. Keep any newly generated terrain in a separate folder, "
        "then restore the paths under terrain/ to the matching paths in fabric/" + level_name + "/. "
        "Set enabled to false in the private refresh policy before restarting, so the restored terrain is retained. "
        "The full verified world ZIP and checksum are recorded in that manifest. Player files, ranks, "
        "backpacks, protected helper/pet chunks, global metadata and boss progress were retained.\n")
    (rollback / "RECOVERY.md").chmod(0o600)
    atomic_json(root / JOURNAL, record)
    try:
        for relative in paths:
            source = inside(world, relative)
            saved = inside(rollback / "terrain", relative)
            saved.parent.mkdir(parents=True, exist_ok=True)
            # Newly created archive ancestors must survive before the source
            # entry is moved. The journal is already durable at this point.
            for parent in reversed(saved.parents):
                if parent == rollback or rollback in parent.parents:
                    sync_directory(parent)
            move(source, saved)
        for name, data in kept.items():
            target = inside(world, name)
            target.parent.mkdir(parents=True, exist_ok=True)
            for parent in reversed(target.parents):
                if parent == world or world in parent.parents:
                    sync_directory(parent)
            atomic_bytes(target, data)
        if any(digest(inside(world, name)) != value for name, value in preserved.items()):
            raise ValueError("Personal or entity data changed during terrain refresh")
        if any(digest(inside(world, name)) != value for name, value in record["kept_files"].items()):
            raise ValueError("Protected terrain changed during terrain refresh")
        record["state"] = "committed"
        atomic_json(root / JOURNAL, record)
        atomic_json(root / POLICY, new_policy)
        atomic_json(rollback / "MANIFEST.json", record)
        (root / JOURNAL).unlink()
        sync_directory(root)
    except Exception:
        recover(root, lease_fd=lease_fd)
        raise
    emit("Terrain regenerated for installed mod update. Verified backup: " + archive.name)
    emit("Refreshed dimension folders: " + ", ".join(p.relative_to(world / "dimensions").as_posix() for p in dimensions))
    emit("Retained protected chunk areas around saved players, helpers/pets, homes and the End arena.")
    emit("Player saves, ranks, backpacks, helper/pet entities and boss progress retained. "
         "Old terrain/chests archived: backups/" + rollback.name)
    return rollback
