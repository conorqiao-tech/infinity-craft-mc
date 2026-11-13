"""Community server settings and stopped-world backups."""
from datetime import datetime, timezone
from dataclasses import dataclass
from contextvars import ContextVar
import errno
import json
import hashlib
import math
import os
from pathlib import Path
import re
import selectors
import stat
import subprocess
import sys
import time
import uuid
import zipfile

DEFAULT_RULES = ["Respect other players.", "Do not grief, steal, or cheat.", "Follow staff instructions and report bugs."]


def configure_admin_code(root, enabled, code):
    if type(enabled) is not bool or not isinstance(code, str):
        raise ValueError("Choose the Admin code settings.")
    if code and (not 8 <= len(code) <= 128 or any(ord(c) < 32 for c in code)):
        raise ValueError("Use 8–128 characters without control characters for the private code.")
    path = root / "fabric/config/infinity-memberships.json"
    values = {"adminCodeEnabled": False, "adminCodeSha256": hashlib.sha256(b"").hexdigest()}
    if path.exists():
        try:
            values = json.loads(path.read_text())
            if not isinstance(values, dict) or not re.fullmatch(r"[a-f0-9]{64}", values.get("adminCodeSha256", "")):
                raise ValueError("Invalid private Admin configuration; original file kept.")
        except (json.JSONDecodeError, TypeError):
            raise ValueError("Invalid private Admin configuration; original file kept.") from None
    if code:
        values["adminCodeSha256"] = hashlib.sha256(code.encode()).hexdigest()
    if enabled and values["adminCodeSha256"] == hashlib.sha256(b"").hexdigest():
        raise ValueError("Enter a private Admin code before enabling redemption.")
    values["adminCodeEnabled"] = enabled
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(json.dumps(values, indent=2) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


AI_DEFAULTS = {"enabled": False, "provider": "openai", "codexExecutable": "", "model": "gpt-6-luna", "ownerOnly": True, "dailyRequestLimit": 50}


def ai_values(data):
    if not isinstance(data, dict):
        raise ValueError("Choose your AI chat settings.")
    values = {key: data.get(key, default) for key, default in AI_DEFAULTS.items()}
    if type(values["enabled"]) is not bool or type(values["ownerOnly"]) is not bool:
        raise ValueError("Choose who can use AI chat and whether it is enabled.")
    if not isinstance(values["provider"], str) or values["provider"] not in ("openai", "codex"):
        raise ValueError("Choose OpenAI API or Codex CLI as the AI provider.")
    executable = values["codexExecutable"]
    if not isinstance(executable, str) or len(executable) > 4096 or any(ord(c) < 32 or ord(c) == 127 for c in executable):
        raise ValueError("Enter one absolute Codex executable path without control characters or command arguments.")
    if executable and not Path(executable).is_absolute():
        raise ValueError("The Codex executable must be an absolute file path, without command arguments.")
    if values["provider"] == "codex" and not values["ownerOnly"]:
        raise ValueError("Codex uses the host's signed-in account and must be restricted to operators with level 4.")
    model = values["model"]
    if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", model):
        raise ValueError("Enter a valid AI model name.")
    limit = values["dailyRequestLimit"]
    if type(limit) is not int or not 0 <= limit <= 1000:
        raise ValueError("Daily AI requests must be a whole number from 0 to 1000.")
    return values


def ai_status(root):
    config = root / "fabric/config/infinity-ai.json"
    key = root / "fabric/config/infinity-ai-key.txt"
    try:
        values = ai_values(json.loads(config.read_text())) if config.exists() else dict(AI_DEFAULTS)
    except (OSError, ValueError, TypeError):
        values = dict(AI_DEFAULTS)
    return {**values, "hasKey": key.is_file() and key.stat().st_size > 0}


def private_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(content)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def configure_ai(root, data):
    values = ai_values(data)
    secret = data.get("apiKey", "")
    if not isinstance(secret, str) or (secret and (not 16 <= len(secret) <= 4096 or any(not 33 <= ord(c) <= 126 for c in secret))):
        raise ValueError("Enter a valid API key without spaces or control characters.")
    if values["provider"] == "codex" and secret:
        raise ValueError("Codex uses its existing CLI login. Leave the API key blank; your saved API key will be kept.")
    config = root / "fabric/config/infinity-ai.json"
    key = root / "fabric/config/infinity-ai-key.txt"
    existing = {}
    if config.exists():
        try:
            existing = json.loads(config.read_text())
            ai_values(existing)
        except (ValueError, TypeError):
            raise ValueError("Invalid private AI configuration; original file kept.") from None
    if values["enabled"]:
        if values["provider"] == "codex":
            executable = Path(values["codexExecutable"])
            if not values["codexExecutable"] or not executable.is_file() or not os.access(executable, os.X_OK):
                raise ValueError("Choose an existing executable Codex file by its absolute path before enabling Codex chat. Do not include command arguments.")
        elif not secret and not (key.is_file() and key.stat().st_size > 0):
            raise ValueError("Enter an API key before enabling OpenAI API chat.")
    if secret:
        private_write(key, secret)
    private_write(config, json.dumps({**existing, **values}, indent=2) + "\n")


def settings(data):
    name = data.get("server_name", "Infinity Armor")
    if not isinstance(name, str) or not name.strip() or len(name) > 60 or any(ord(c) < 32 or c == "\\" for c in name):
        raise ValueError("Server name must be 1–60 characters without control characters or backslashes.")
    limit = data.get("max_players", 32)
    if type(limit) is not int or not 2 <= limit <= 200:
        raise ValueError("Player slots must be between 2 and 200. Slots are not a performance guarantee.")
    whitelist = data.get("whitelist", False)
    if type(whitelist) is not bool:
        raise ValueError("Choose whether to use the allowlist.")
    difficulty = data.get("difficulty", "normal")
    if difficulty not in ("peaceful", "easy", "normal", "hard"):
        raise ValueError("Choose a valid difficulty.")
    rules = data.get("rules", DEFAULT_RULES)
    if isinstance(rules, str):
        rules = [line.strip() for line in rules.splitlines() if line.strip()]
    if not isinstance(rules, list) or not 1 <= len(rules) <= 8 or any(not isinstance(r, str) or not r.strip() or len(r) > 160 or any(ord(c) < 32 for c in r) for r in rules):
        raise ValueError("Enter 1–8 rules, up to 160 characters each, one per line.")
    return {"server_name": name.strip(), "max_players": limit, "whitelist": whitelist,
            "difficulty": difficulty, "rules": rules}


def properties_text(value):
    return json.dumps(value, ensure_ascii=True)[1:-1]


def configure(root, props, data):
    values = settings(data)
    # The launcher's properties writer escapes values, so store the text Java should read.
    props.update({"motd": values["server_name"] + " | Java + Bedrock | 5 Game Modes",
                  "max-players": str(values["max_players"]), "white-list": str(values["whitelist"]).lower(),
                  "enforce-whitelist": "true", "difficulty": values["difficulty"]})
    props.setdefault("spawn-protection", "32")
    props.setdefault("player-idle-timeout", "20")
    props.setdefault("entity-broadcast-range-percentage", "75")
    config = root / "fabric/config/infinity-community.json"
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(json.dumps({"name": values["server_name"], "rules": values["rules"]}, indent=2) + "\n")
    return values


def backups(root):
    folder = root / "backups"
    return [{"name": p.name, "bytes": p.stat().st_size} for p in sorted(folder.glob("Infinity-World-*.zip"), reverse=True)[:10]]


def world_folder(root, props):
    """Resolve the decoded level-name as Minecraft does, refusing folders outside fabric."""
    name = props.get("level-name", "world")
    if not isinstance(name, str) or any(ord(c) < 32 or ord(c) == 127 for c in name):
        raise ValueError("level-name in server.properties contains control characters. Choose a plain world folder name.")
    fabric = (root / "fabric").resolve()
    world = (fabric / name).resolve()
    if world == fabric or not world.is_relative_to(fabric):
        raise ValueError("The world folder must be inside this server's fabric folder.")
    return fabric, world


SESSION_LOCK_AVAILABLE = sys.platform in ("darwin", "linux") and hasattr(os, "O_NOFOLLOW")


_WORLD_SESSION_LOCK_OBSERVER = r'''
import ctypes, fcntl, os, stat, sys
try:
    if ctypes.sizeof(ctypes.c_void_p) != 8:
        sys.exit(30)
    machine = os.uname().machine
    if sys.platform == "darwin" and machine in ("arm64", "x86_64"):
        class Flock(ctypes.Structure):
            _fields_ = [("start", ctypes.c_int64), ("length", ctypes.c_int64),
                        ("pid", ctypes.c_int32), ("kind", ctypes.c_int16), ("whence", ctypes.c_int16)]
        expected_size = 24
    elif sys.platform == "linux" and machine in ("aarch64", "x86_64"):
        class Flock(ctypes.Structure):
            _fields_ = [("kind", ctypes.c_int16), ("whence", ctypes.c_int16),
                        ("start", ctypes.c_int64), ("length", ctypes.c_int64), ("pid", ctypes.c_int32)]
        expected_size = 32
    else:
        sys.exit(30)
    if ctypes.sizeof(Flock) != expected_size:
        sys.exit(30)
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                or (info.st_dev, info.st_ino) != (int(sys.argv[2]), int(sys.argv[3]))):
            sys.exit(20)
        query = Flock()
        query.kind, query.whence, query.start, query.length = fcntl.F_WRLCK, os.SEEK_SET, 0, 0
        result = Flock.from_buffer_copy(fcntl.fcntl(fd, fcntl.F_GETLK, bytes(query)))
        if (result.kind == fcntl.F_WRLCK and result.pid == int(sys.argv[4])
                and result.whence == os.SEEK_SET and result.start == 0 and result.length == 0):
            sys.exit(10)
        sys.exit(20)
    finally:
        os.close(fd)
except (OSError, ValueError):
    sys.exit(30)
'''


def _world_session_lock_identity(world, descriptor):
    """Check the descriptor and path without reopening the lock in this process."""
    try:
        held = os.fstat(descriptor)
        current = os.stat(world / "session.lock", follow_symlinks=False)
        if (not stat.S_ISREG(held.st_mode) or not stat.S_ISREG(current.st_mode)
                or held.st_nlink != 1 or current.st_nlink != 1
                or (held.st_dev, held.st_ino) != (current.st_dev, current.st_ino)):
            raise ValueError("The Minecraft world lock changed while preparing the backup; no archive was published.")
        return held
    except OSError as error:
        raise ValueError("The Minecraft world lock changed or became unreadable; no archive was published.") from error


def verify_world_session_lock(world, descriptor):
    """Observe the exclusive record lock; this does not prove continuous ownership."""
    held = _world_session_lock_identity(world, descriptor)
    try:
        # F_GETLK ignores locks owned by its own process. Inspect from a child;
        # opening and closing another descriptor in this process could release
        # this process's record lock even while the original descriptor is open.
        observer = subprocess.run([sys.executable, "-I", "-S", "-c", _WORLD_SESSION_LOCK_OBSERVER,
                                   str(world / "session.lock"), str(held.st_dev), str(held.st_ino), str(os.getpid())],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                  stderr=subprocess.DEVNULL, close_fds=True, timeout=5)
        if observer.returncode != 10:
            raise ValueError("The Minecraft world lock is not confirmed; keep the world stopped for host review.")
        _world_session_lock_identity(world, descriptor)
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("The Minecraft world lock could not be confirmed; keep the world stopped for host review.") from error


def acquire_world_session_lock(world):
    """Hold Minecraft's advisory lock for a stopped-world backup on supported POSIX hosts."""
    if not SESSION_LOCK_AVAILABLE:
        raise ValueError("This system cannot safely check the Minecraft world lock; use macOS or Linux to back up this world.")
    import fcntl
    try:
        descriptor = os.open(world / "session.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    except OSError as error:
        raise ValueError("The Minecraft world lock cannot be opened safely; no backup was made.") from error
    try:
        _world_session_lock_identity(world, descriptor)
        try:
            # Java's FileChannel.tryLock and POSIX lockf use the same advisory
            # record lock on the tested local macOS filesystem. Hold it for the
            # full archive operation; it cannot constrain unrelated writers.
            fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise ValueError("Another Minecraft server may be using this world; stop it before backing up.") from error
        verify_world_session_lock(world, descriptor)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


class DurabilityError(RuntimeError):
    """A backup could not be confirmed on stable storage, so it must not be relied upon."""


# macOS fsync can leave data in the drive cache; F_FULLFSYNC flushes it.
FULL_FSYNC = sys.platform == "darwin"
# Only POSIX lets Python open and fsync a directory. Elsewhere (Windows) a
# backup's contents are still fsynced and renamed into place, but nothing can
# confirm its folder entry reached stable storage, so it is saved but not a
# confirmed restore point: terrain refresh refuses to rely on it.
DIRECTORY_SYNC = os.name == "posix"
# Filesystems such as SMB or exFAT reject F_FULLFSYNC; plain fsync is then the
# strongest request available, as Go and SQLite also do on macOS.
FULLFSYNC_UNSUPPORTED = {getattr(errno, name) for name in ("EINVAL", "ENOTSUP", "EOPNOTSUPP", "ENOTTY") if hasattr(errno, name)}
UNSUPPORTED_DIRECTORY_SYNC = ("This system cannot confirm that a backup's folder entry reached stable storage, "
                              "so terrain refresh was not performed and the world was left unchanged. "
                              "Run the launcher on macOS or Linux to refresh terrain.")
UNCONFIRMED_BACKUP_NOTE = ("its file was flushed, but this system cannot confirm the backups folder entry "
                           "reached stable storage, so terrain refresh is unavailable here")


def full_fsync(descriptor):
    import fcntl
    fcntl.fcntl(descriptor, fcntl.F_FULLFSYNC)


def flush_descriptor(descriptor):
    if FULL_FSYNC:
        try:
            full_fsync(descriptor)
            return
        except OSError as error:
            if error.errno not in FULLFSYNC_UNSUPPORTED:
                raise
    os.fsync(descriptor)


def sync_file(stream):
    stream.flush()
    flush_descriptor(stream.fileno())


def sync_directory(path, *, directory_fd=None):
    if not DIRECTORY_SYNC:
        raise DurabilityError(UNSUPPORTED_DIRECTORY_SYNC)
    if directory_fd is not None:
        flush_descriptor(directory_fd)
        return
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        flush_descriptor(descriptor)
    finally:
        os.close(descriptor)


def confirm_backup_durable(root, name):
    """Require the named full-world backup and its folder entries on stable storage.

    Destructive operations call this; it fails rather than trusting a backup
    whose rename cannot be confirmed durable on this platform."""
    if not DIRECTORY_SYNC:
        raise DurabilityError(UNSUPPORTED_DIRECTORY_SYNC)
    if (not isinstance(name, str) or Path(name).name != name
            or not re.fullmatch(r"Infinity-World-[0-9_-]+-[0-9a-f]{6}\.zip", name)):
        raise ValueError("A fresh stopped-world backup is required before regeneration")
    folder = root / "backups"
    descriptor = os.open(folder / name, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0))
    try:
        flush_descriptor(descriptor)
    finally:
        os.close(descriptor)
    sync_directory(folder)
    sync_directory(root)


@dataclass(frozen=True)
class BackupFile:
    root: Path
    parts: tuple
    root_identity: tuple
    parent_identities: tuple
    snapshot: os.stat_result


def backup_directory_identity(info):
    if not stat.S_ISDIR(info.st_mode):
        raise ValueError("A backup directory changed; no archive was published.")
    return (info.st_dev, info.st_ino)


def backup_file_identity(info):
    if not stat.S_ISREG(info.st_mode):
        raise ValueError("A non-regular backup entry cannot be included safely; no archive was published.")
    if info.st_nlink != 1:
        raise ValueError("A hard-linked backup entry cannot be included safely; no archive was published.")
    return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink, info.st_size,
            info.st_mtime_ns, info.st_ctime_ns)


def open_backup_directory(path, dir_fd=None):
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=dir_fd)


def backup_world_files(world):
    """List files through directory descriptors, retaining identities for the copy.

    These checks detect some substitutions but cannot exclude another writer
    or turn a stopped-world backup into a filesystem snapshot.
    """
    files = []

    def walk(directory, parts, root_identity, parents):
        for name in sorted(os.listdir(directory)):
            if not parts and name == "session.lock":
                continue  # Do not reopen the native record lock in this process.
            info = os.stat(name, dir_fd=directory, follow_symlinks=False)
            if stat.S_ISLNK(info.st_mode):
                raise ValueError("A symbolic link was found in the world. Back it up manually before continuing.")
            if stat.S_ISDIR(info.st_mode):
                identity = backup_directory_identity(info)
                child = open_backup_directory(name, dir_fd=directory)
                try:
                    if backup_directory_identity(os.fstat(child)) != identity:
                        raise ValueError("A world directory changed while preparing the backup; no archive was published.")
                    walk(child, parts + (name,), root_identity, parents + (identity,))
                finally:
                    os.close(child)
            else:
                backup_file_identity(info)
                files.append(BackupFile(world, parts + (name,), root_identity, parents, info))

    try:
        directory = open_backup_directory(world)
        try:
            root_identity = backup_directory_identity(os.fstat(directory))
            walk(directory, (), root_identity, ())
        finally:
            os.close(directory)
    except OSError as error:
        raise ValueError("Cannot read every world directory safely; no backup was published.") from error
    return files


def backup_server_files(fabric):
    """Capture the optional, explicitly allowed accompanying server files."""
    files = []
    try:
        directory = open_backup_directory(fabric)
        try:
            identity = backup_directory_identity(os.fstat(directory))
            for name in ("server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json"):
                try:
                    info = os.stat(name, dir_fd=directory, follow_symlinks=False)
                except FileNotFoundError:
                    continue
                if stat.S_ISLNK(info.st_mode):
                    raise ValueError("A symbolic link was found in the server backup entries; no archive was published.")
                backup_file_identity(info)
                files.append(BackupFile(fabric, (name,), identity, (), info))
        finally:
            os.close(directory)
    except OSError as error:
        raise ValueError("Cannot read a server backup entry safely; no backup was published.") from error
    return files


def backup_inventory(files):
    return [(entry.root, entry.parts, entry.root_identity, entry.parent_identities,
             backup_file_identity(entry.snapshot)) for entry in files]


def open_backup_parent(entry):
    """Reopen each recorded parent without following links and compare identities."""
    directory = open_backup_directory(entry.root)
    try:
        if backup_directory_identity(os.fstat(directory)) != entry.root_identity:
            raise ValueError("A backup directory changed; no archive was published.")
        for name, expected in zip(entry.parts[:-1], entry.parent_identities):
            child = open_backup_directory(name, dir_fd=directory)
            os.close(directory)
            directory = child
            if backup_directory_identity(os.fstat(directory)) != expected:
                raise ValueError("A backup directory changed; no archive was published.")
        return directory
    except BaseException:
        os.close(directory)
        raise


_BACKUP_READER_IDLE_TIMEOUT = 120
_BACKUP_READER_BASE_TIMEOUT = 120
_BACKUP_READER_MIN_BYTES_PER_SECOND = 64 * 1024
_BACKUP_READER_TRAILER = b"INFINITY_BACKUP_FILE_OK_V1"

_BACKUP_FILE_READER = r'''
import os, stat, sys
try:
    def emit(block):
        remaining = memoryview(block)
        while remaining:
            written = os.write(1, remaining)
            if written <= 0:
                sys.exit(23)
            remaining = remaining[written:]
    parent = int(sys.argv[1])
    name = sys.argv[2]
    parent_expected = (int(sys.argv[3]), int(sys.argv[4]))
    expected = tuple(int(value) for value in sys.argv[5:])
    directory = os.fstat(parent)
    if not stat.S_ISDIR(directory.st_mode) or (directory.st_dev, directory.st_ino) != parent_expected:
        sys.exit(24)
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    try:
        def identity(info):
            return (info.st_dev, info.st_ino, info.st_mode, info.st_nlink,
                    info.st_size, info.st_mtime_ns, info.st_ctime_ns)
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or identity(before) != expected:
            sys.exit(21)
        copied = 0
        while True:
            block = os.read(descriptor, 1024 * 1024)
            if not block:
                break
            copied += len(block)
            if copied > expected[4]:
                sys.exit(22)
            emit(block)
        if copied != expected[4] or identity(os.fstat(descriptor)) != expected:
            sys.exit(22)
    finally:
        os.close(descriptor)
    # This is emitted only after the complete read, final fstat and close.
    # The parent treats it as a fixed trailer after exactly the expected bytes.
    emit(b"INFINITY_BACKUP_FILE_OK_V1")
except (OSError, ValueError):
    sys.exit(23)
'''


class BackupReaderUncertain(RuntimeError):
    """A backup reader could not be confirmed stopped; retain the launcher marker."""


class BackupArchiveUncertain(RuntimeError):
    """An archive entry no longer matches the held descriptor; retain evidence."""


def backup_output_ancestry(descriptor):
    """Observe directory identities up to the filesystem root, without opening files."""
    current = None
    parent = None

    def close_probe(fd):
        try:
            os.close(fd)
        except OSError as error:
            raise BackupArchiveUncertain("A backups-directory check could not close safely; keep its evidence for review.") from error

    try:
        current = os.dup(descriptor)
        chain = []
        seen = set()
        for _ in range(256):
            info = os.fstat(current)
            if not stat.S_ISDIR(info.st_mode):
                raise BackupArchiveUncertain("The backups-directory ancestry is not a directory; keep its evidence for review.")
            identity = (info.st_dev, info.st_ino)
            if identity in seen:
                raise BackupArchiveUncertain("The backups-directory ancestry is ambiguous; keep its evidence for review.")
            seen.add(identity)
            chain.append(identity)
            parent = open_backup_directory("..", dir_fd=current)
            parent_info = os.fstat(parent)
            if not stat.S_ISDIR(parent_info.st_mode):
                raise BackupArchiveUncertain("The backups-directory parent is not a directory; keep its evidence for review.")
            if (parent_info.st_dev, parent_info.st_ino) == identity:
                return tuple(chain)
            previous = current
            current = parent
            parent = None
            close_probe(previous)
        raise BackupArchiveUncertain("The backups-directory ancestry is too deep; keep its evidence for review.")
    except OSError as error:
        raise BackupArchiveUncertain("The backups-directory ancestry could not be confirmed; keep its evidence for review.") from error
    finally:
        try:
            if parent is not None:
                close_probe(parent)
        finally:
            if current is not None:
                close_probe(current)


def verify_backup_output_directory(path, descriptor, *, ancestry=None):
    """Compare the output path and observed parents with the held directory."""
    try:
        held = os.fstat(descriptor)
        entry = path.lstat()
    except OSError as error:
        raise BackupArchiveUncertain("The backups directory could not be confirmed; keep its evidence for review.") from error
    if (not stat.S_ISDIR(held.st_mode) or not stat.S_ISDIR(entry.st_mode)
            or (held.st_dev, held.st_ino) != (entry.st_dev, entry.st_ino)):
        raise BackupArchiveUncertain("The backups directory changed; keep its evidence for review.")
    if ancestry is not None and backup_output_ancestry(descriptor) != ancestry:
        raise BackupArchiveUncertain("The backups-directory ancestry changed; keep its evidence for review.")


def verify_backup_archive_entry(path, descriptor, *, directory_fd):
    """Compare an archive entry without opening a possible native-lock alias."""
    try:
        held = os.fstat(descriptor)
        entry = os.stat(path.name, dir_fd=directory_fd, follow_symlinks=False)
    except OSError as error:
        raise BackupArchiveUncertain("The backup archive could not be confirmed; keep its evidence for review.") from error
    if (not stat.S_ISREG(held.st_mode) or not stat.S_ISREG(entry.st_mode)
            or held.st_nlink != 1 or entry.st_nlink != 1
            or (held.st_dev, held.st_ino) != (entry.st_dev, entry.st_ino)):
        raise BackupArchiveUncertain("The backup archive changed; keep its evidence for review.")


def publish_backup_no_replace(source, destination, *, directory_fd):
    """Rename inside the held output directory without replacing an entry."""
    import ctypes
    library = ctypes.CDLL(None, use_errno=True)
    try:
        if sys.platform == "darwin":
            rename = library.renameatx_np
            rename.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
            args = (directory_fd, os.fsencode(source.name), directory_fd,
                    os.fsencode(destination.name), 0x00000004)  # RENAME_EXCL
        elif sys.platform == "linux":
            rename = library.renameat2
            rename.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
            args = (directory_fd, os.fsencode(source.name), directory_fd,
                    os.fsencode(destination.name), 1)  # RENAME_NOREPLACE
        else:
            raise AttributeError("exclusive rename is unsupported")
    except AttributeError as error:
        raise OSError(errno.ENOTSUP, "Exclusive backup publication is unavailable") from error
    rename.restype = ctypes.c_int
    if rename(*args) != 0:
        number = ctypes.get_errno()
        raise OSError(number, os.strerror(number), str(source), None, str(destination))


class _BackupReaderState:
    def __init__(self):
        self.unconfirmed = 0


_ACTIVE_BACKUP_READERS = ContextVar("active_backup_readers", default=None)


def _backup_error_requires_review(error):
    """Retain evidence when cleanup masks an interruption or uncertain reader."""
    pending = [error]
    seen = set()
    while pending:
        current = pending.pop()
        if current is None or id(current) in seen:
            continue
        seen.add(id(current))
        if isinstance(current, (BackupReaderUncertain, BackupArchiveUncertain, KeyboardInterrupt, SystemExit)):
            return True
        pending.extend((current.__cause__, current.__context__))
    return False


def _stop_backup_reader(reader):
    try:
        if reader.poll() is None:
            reader.kill()
        reader.wait(timeout=5)
    except BaseException as error:
        raise BackupReaderUncertain("A backup reader could not be confirmed stopped; keep the launcher lock for host review.") from error


def write_backup_file(output, entry, fabric):
    """Copy through a child so file closes cannot release our native record lock."""
    expected = backup_file_identity(entry.snapshot)
    if (not entry.parts or not isinstance(entry.parts[-1], str) or entry.parts[-1] in ("", ".", "..")
            or "/" in entry.parts[-1] or "\0" in entry.parts[-1]):
        raise ValueError("An invalid backup filename cannot be copied; no archive was published.")
    parent_expected = entry.parent_identities[-1] if entry.parent_identities else entry.root_identity
    reader_state = _ACTIVE_BACKUP_READERS.get()
    try:
        parent = open_backup_parent(entry)
        try:
            reader = None
            try:
                if reader_state is not None:
                    # A failed or interrupted spawn may have created a child
                    # even when no usable process handle was returned.
                    reader_state.unconfirmed += 1
                reader = subprocess.Popen([sys.executable, "-I", "-S", "-c", _BACKUP_FILE_READER,
                                           str(parent), entry.parts[-1], *(str(value) for value in parent_expected),
                                           *(str(value) for value in expected)],
                                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                          stderr=subprocess.DEVNULL, close_fds=True, pass_fds=(parent,))
                archive_name = entry.root.joinpath(*entry.parts).relative_to(fabric).as_posix()
                info = zipfile.ZipInfo(archive_name, time.localtime(entry.snapshot.st_mtime)[:6])
                info.external_attr = (entry.snapshot.st_mode & 0xFFFF) << 16
                info.compress_type = output.compression
                info._compresslevel = output.compresslevel
                info.file_size = entry.snapshot.st_size
                copied = 0
                trailer = bytearray()
                deadline = (time.monotonic() + _BACKUP_READER_BASE_TIMEOUT
                            + entry.snapshot.st_size / _BACKUP_READER_MIN_BYTES_PER_SECOND)
                with selectors.DefaultSelector() as selector, output.open(info, "w") as target:
                    selector.register(reader.stdout, selectors.EVENT_READ)
                    while True:
                        remaining = deadline - time.monotonic()
                        if remaining <= 0 or not selector.select(min(_BACKUP_READER_IDLE_TIMEOUT, remaining)):
                            raise ValueError("A backup reader stopped making progress; no archive was published.")
                        chunk = os.read(reader.stdout.fileno(), 1024 * 1024)
                        if not chunk:
                            break
                        remaining_file = entry.snapshot.st_size - copied
                        data = chunk[:remaining_file]
                        copied += len(data)
                        if data:
                            target.write(data)
                        trailer.extend(chunk[len(data):])
                        if len(trailer) > len(_BACKUP_READER_TRAILER):
                            raise ValueError("A backup file changed while it was copied; no archive was published.")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise ValueError("A backup reader stopped making progress; no archive was published.")
                status = reader.wait(timeout=min(_BACKUP_READER_IDLE_TIMEOUT, remaining))
                if status != 0 or copied != entry.snapshot.st_size or bytes(trailer) != _BACKUP_READER_TRAILER:
                    raise ValueError("A backup file changed while it was copied; no archive was published.")
                if backup_file_identity(os.stat(entry.parts[-1], dir_fd=parent, follow_symlinks=False)) != expected:
                    raise ValueError("A backup file changed while it was copied; no archive was published.")
            finally:
                if reader is not None:
                    try:
                        reader.stdout.close()
                    finally:
                        _stop_backup_reader(reader)
                        if reader_state is not None:
                            reader_state.unconfirmed -= 1
        finally:
            os.close(parent)
        # Reopen the path after copying, including parents, to detect an observed
        # move of a directory that was held open during the read.
        parent = open_backup_parent(entry)
        try:
            if backup_file_identity(os.stat(entry.parts[-1], dir_fd=parent, follow_symlinks=False)) != expected:
                raise ValueError("A backup file changed while it was copied; no archive was published.")
        finally:
            os.close(parent)
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("A backup path changed or could not be read; no archive was published.") from error


def unlink_backup_lease_entry(path, descriptor):
    """Remove only an observed entry matching our lease; this is not writer exclusion."""
    try:
        current = path.lstat()
    except FileNotFoundError:
        return False
    held = os.fstat(descriptor)
    if (not stat.S_ISREG(current.st_mode) or not stat.S_ISREG(held.st_mode)
            or (current.st_dev, current.st_ino) != (held.st_dev, held.st_ino)):
        return False
    path.unlink()
    return True


_LAUNCHER_LEASE_OBSERVER = r'''
import fcntl, os, stat, sys
try:
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1
                or (info.st_dev, info.st_ino) != (int(sys.argv[2]), int(sys.argv[3]))):
            sys.exit(20)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError:
            sys.exit(10)  # The expected entry has an exclusive flock.
        else:
            sys.exit(0)  # No exclusive lease; do not silently reacquire it.
    finally:
        os.close(fd)
except OSError:
    sys.exit(30)
'''


def verify_borrowed_launcher_lease(root, descriptor):
    """Check a caller's matching cooperative lease without closing it.

    An independent observer catches a missing or downgraded exclusive lock at
    its check point; it cannot prove uninterrupted ownership or exclude a
    noncooperating writer.
    """
    if not isinstance(descriptor, int) or isinstance(descriptor, bool):
        raise ValueError("A matching launcher lease is required before world work.")

    def check_entry():
        held = os.fstat(descriptor)
        current = os.stat(root / "launcher.lock", follow_symlinks=False)
        if (not stat.S_ISREG(held.st_mode) or not stat.S_ISREG(current.st_mode)
                or held.st_nlink != 1 or current.st_nlink != 1
                or (held.st_dev, held.st_ino) != (current.st_dev, current.st_ino)):
            raise ValueError("The borrowed launcher lease changed; keep the world stopped for review.")
        return held

    try:
        held = check_entry()
        if os.name == "posix":
            import fcntl
            # Open the path in a separate process. Closing another descriptor
            # for Minecraft's session.lock in this process can release its
            # POSIX record lock if an actor substitutes that inode here.
            observer = subprocess.run([sys.executable, "-I", "-S", "-c", _LAUNCHER_LEASE_OBSERVER,
                                       str(root / "launcher.lock"), str(held.st_dev), str(held.st_ino)],
                                      stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                      stderr=subprocess.DEVNULL, close_fds=True, timeout=5)
            if observer.returncode != 10:
                raise ValueError("The borrowed launcher lease is not confirmed; keep the world stopped for review.")
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # On Windows this only compares the descriptor to the entry. Backups
        # of existing worlds still refuse without a native session-lock check.
        check_entry()
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError("The borrowed launcher lease is unavailable or changed; keep the world stopped for review.") from error


def backup(root, props, locked=False, lease_fd=None):
    """Archive the stopped world; returns its name, or None for a genuinely new world.

    Where DIRECTORY_SYNC is false the archive is fsynced and renamed but its
    folder entry is unconfirmed; confirm_backup_durable reports that."""
    reader_state = _BackupReaderState()
    token = _ACTIVE_BACKUP_READERS.set(reader_state)
    try:
        return _backup_with_reader_state(root, props, locked, lease_fd, reader_state)
    finally:
        _ACTIVE_BACKUP_READERS.reset(token)


def _backup_with_reader_state(root, props, locked, lease_fd, reader_state):
    lockfile = root / "launcher.lock"
    fd = None
    lease_temporary = None
    owns_launcher_lock = False
    lease_prepared = False
    temporary = None
    archive_creation_attempted = False
    session_lock = None
    backup_directory_fd = None
    publication_complete = False
    backup_directory_closed = False
    session_lock_closed = False
    reader_uncertain = False
    try:
        if locked:
            verify_borrowed_launcher_lease(root, lease_fd)
        else:
            if lease_fd is not None:
                raise ValueError("A borrowed launcher lease requires a locked backup.")
            if os.name == "posix":
                import fcntl
                # Do not publish an empty launcher.lock: a process exit between
                # creation and the PID write would leave an unrecoverable lock.
                lease_temporary = root / (".launcher-lock-" + uuid.uuid4().hex + ".tmp")
                fd = os.open(lease_temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            else:
                try:
                    fd = os.open(lockfile, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                    owns_launcher_lock = True
                except FileExistsError as error:
                    raise ValueError("Save & Stop the server before backing up the world.") from error
            pid = str(os.getpid()).encode("ascii")
            if os.write(fd, pid) != len(pid):
                raise OSError("Could not write the complete launcher lock owner")
            flush_descriptor(fd)
            if lease_temporary is not None:
                try:
                    os.link(lease_temporary, lockfile, follow_symlinks=False)
                    owns_launcher_lock = True
                except FileExistsError as error:
                    raise ValueError("Save & Stop the server before backing up the world.") from error
                held = os.fstat(fd)
                current = lockfile.lstat()
                if (not stat.S_ISREG(held.st_mode) or not stat.S_ISREG(current.st_mode)
                        or held.st_nlink != 2 or current.st_nlink != 2
                        or (held.st_dev, held.st_ino) != (current.st_dev, current.st_ino)):
                    raise ValueError("The launcher lock changed before backup; no archive was published.")
                if not unlink_backup_lease_entry(lease_temporary, fd):
                    raise ValueError("The launcher lock staging entry changed; no archive was published.")
                lease_temporary = None
                held = os.fstat(fd)
                current = lockfile.lstat()
                if (not stat.S_ISREG(current.st_mode) or held.st_nlink != 1 or current.st_nlink != 1
                        or (held.st_dev, held.st_ino) != (current.st_dev, current.st_ino)):
                    raise ValueError("The launcher lock changed before backup; no archive was published.")
            # Make the published entry as durable as this platform can request
            # before looking at world files. A later same-authority swap can
            # still bypass this bounded check.
            if DIRECTORY_SYNC:
                sync_directory(root)
            held = os.fstat(fd)
            current = lockfile.lstat()
            if (not stat.S_ISREG(held.st_mode) or not stat.S_ISREG(current.st_mode)
                    or held.st_nlink != 1 or current.st_nlink != 1
                    or (held.st_dev, held.st_ino) != (current.st_dev, current.st_ino)):
                raise ValueError("The launcher lock changed before backup; no archive was published.")
            lease_prepared = True
        fabric, world = world_folder(root, props)
        if not world.exists():
            return None  # A genuinely new world; Minecraft creates it on first start.
        if not world.is_dir():
            raise ValueError("level-name in server.properties names a file, not a world folder.")
        # Minecraft can still load a folder without level.dat (for example from
        # level.dat_old), so only an empty folder counts as a new world.
        if not (world / "level.dat").is_file() and all(p.name == "session.lock" for p in world.iterdir()):
            return None
        # launcher.lock only coordinates this launcher. A separately started
        # Minecraft server also holds the world's session.lock, including when
        # the caller already owns launcher.lock (locked=True).
        session_lock = acquire_world_session_lock(world)
        folder = root / "backups"
        try:
            folder.mkdir()
        except FileExistsError:
            pass
        else:
            if DIRECTORY_SYNC:
                sync_directory(folder.parent)
        try:
            backup_directory_fd = open_backup_directory(folder)
        except OSError as error:
            raise ValueError("The backups directory cannot be opened safely; no archive was published.") from error
        verify_backup_output_directory(folder, backup_directory_fd)
        backup_directory_ancestry = backup_output_ancestry(backup_directory_fd)
        verify_backup_output_directory(folder, backup_directory_fd, ancestry=backup_directory_ancestry)
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
        destination = folder / ("Infinity-World-" + stamp + "-" + uuid.uuid4().hex[:6] + ".zip")
        temporary = destination.with_suffix(".partial")
        files = backup_world_files(world) + backup_server_files(fabric)
        inventory = backup_inventory(files)
        verify_backup_output_directory(folder, backup_directory_fd, ancestry=backup_directory_ancestry)
        archive_creation_attempted = True
        descriptor = os.open(temporary.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0),
                             0o600, dir_fd=backup_directory_fd)
        with os.fdopen(descriptor, "wb") as stream:
            with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as output:
                for file in files:
                    write_backup_file(output, file, fabric)
                output.writestr("BACKUP_INFO.json", json.dumps({"format": 1, "created_utc": stamp, "world": str(world.relative_to(fabric))}))
            verify_world_session_lock(world, session_lock)
            if backup_inventory(backup_world_files(world) + backup_server_files(fabric)) != inventory:
                raise ValueError("The world or server files changed during backup; no archive was published.")
            # Closing the archive wrote its central directory; persist the
            # complete ZIP before its final name becomes visible.
            os.fchmod(stream.fileno(), 0o600)
            sync_file(stream)
            verify_backup_output_directory(folder, backup_directory_fd, ancestry=backup_directory_ancestry)
            verify_backup_archive_entry(temporary, stream.fileno(), directory_fd=backup_directory_fd)
            verify_world_session_lock(world, session_lock)
            if locked:
                verify_borrowed_launcher_lease(root, lease_fd)
            verify_backup_output_directory(folder, backup_directory_fd, ancestry=backup_directory_ancestry)
            verify_backup_archive_entry(temporary, stream.fileno(), directory_fd=backup_directory_fd)
            # Rename without replacing an existing backup. If this call or a
            # later check is uncertain, retain the marker and available paths.
            publish_backup_no_replace(temporary, destination, directory_fd=backup_directory_fd)
            verify_backup_archive_entry(destination, stream.fileno(), directory_fd=backup_directory_fd)
            verify_backup_output_directory(folder, backup_directory_fd, ancestry=backup_directory_ancestry)
            if DIRECTORY_SYNC:
                sync_directory(folder, directory_fd=backup_directory_fd)
            verify_backup_archive_entry(destination, stream.fileno(), directory_fd=backup_directory_fd)
            verify_backup_output_directory(folder, backup_directory_fd, ancestry=backup_directory_ancestry)
        publication_complete = True
        return destination.name
    except BaseException as error:
        reader_uncertain = _backup_error_requires_review(error)
        raise
    finally:
        try:
            if backup_directory_fd is not None:
                os.close(backup_directory_fd)
            backup_directory_closed = True
        finally:
            try:
                if session_lock is not None:
                    os.close(session_lock)
                session_lock_closed = True
            finally:
                if fd is not None:
                    try:
                        # A pathname can be replaced after any check, so retain
                        # available evidence whenever staging did not complete.
                        safe_to_retire = (not reader_uncertain and reader_state.unconfirmed == 0
                                          and backup_directory_closed and session_lock_closed
                                          and (not archive_creation_attempted or publication_complete))
                        if owns_launcher_lock and lease_prepared and safe_to_retire:
                            unlink_backup_lease_entry(lockfile, fd)
                    finally:
                        try:
                            if lease_temporary is not None:
                                unlink_backup_lease_entry(lease_temporary, fd)
                        finally:
                            os.close(fd)


ROSTER_MAX_BYTES = 256 * 1024
ROSTER_MAX_PLAYERS = 200


def roster(root, running):
    if not running:
        return {"players": [], "tick_ms": None, "available": False}
    try:
        import time
        with (root / "fabric/community-status.json").open("rb") as stream:
            content = stream.read(ROSTER_MAX_BYTES + 1)
        if len(content) > ROSTER_MAX_BYTES:
            raise ValueError("Oversized status")
        def invalid_constant(value):
            raise ValueError("Invalid status number")
        data = json.loads(content, parse_constant=invalid_constant)
        if not isinstance(data, dict):
            raise ValueError("Invalid status")
        updated, tick_ms, players = data["updated"], data["tick_ms"], data["players"]
        if (type(updated) not in (int, float) or not math.isfinite(updated) or
            type(tick_ms) not in (int, float) or not math.isfinite(tick_ms) or tick_ms < 0 or
            not isinstance(players, list) or len(players) > ROSTER_MAX_PLAYERS):
            raise ValueError("Invalid status")
        for player in players:
            if not isinstance(player, dict) or "name" not in player:
                raise ValueError("Invalid player status")
            for key, limit in (("name", 64), ("uuid", 64), ("mode", 64), ("rank", 160)):
                if key in player and (not isinstance(player[key], str) or
                    not 1 <= len(player[key]) <= limit or any(ord(c) < 32 for c in player[key])):
                    raise ValueError("Invalid player status")
        if abs(time.time() * 1000 - updated) > 30_000:
            raise ValueError("Stale status")
        # Only the native public roster fields belong in a dashboard response.
        public_players=[{key:player[key] for key in ("name","uuid","mode","rank") if key in player} for player in players]
        return {"players": public_players, "tick_ms": tick_ms, "available": True}
    except (OSError, ValueError, KeyError, OverflowError, RecursionError):
        return {"players": [], "tick_ms": None, "available": False}


def moderation_command(action, name):
    if not isinstance(name, str) or not re.fullmatch(r"\.?[A-Za-z0-9_]{1,32}", name):
        raise ValueError("Enter the exact player name, including the leading dot for Bedrock.")
    templates = {"kick": "kick {} Removed by staff", "ban": "ban {} Banned by staff", "unban": "pardon {}",
                 "mute": "community mute {} 10", "unmute": "community unmute {}", "allow": "whitelist add {}",
                 "remove_allow": "whitelist remove {}"}
    if action not in templates:
        raise ValueError("Choose a valid moderation action.")
    return templates[action].format(name)


def membership_command(action, name, tier=None, days=None):
    if not isinstance(name, str) or not re.fullmatch(r"\.?[A-Za-z0-9_]{1,32}", name):
        raise ValueError("Choose an online player's exact name, including the dot for Bedrock.")
    if action in ("revoke", "revokeadmin"):
        return "membership " + action + " " + name
    if action != "grant" or tier not in ("go", "plus", "pro", "ultra"):
        raise ValueError("Choose Go, Plus, Pro, or Ultra. Admin uses the private code.")
    if type(days) is not int or not 1 <= days <= 3660:
        raise ValueError("Membership duration must be 1–3660 whole days.")
    return "membership grant " + name + " " + tier + " " + str(days)
