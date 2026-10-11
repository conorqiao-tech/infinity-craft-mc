"""Owner opt-in terrain regeneration, performed only during locked, stopped startup."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import uuid
import zipfile
import tempfile
import sys
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


def sync_directory(path):
    if os.name == "posix":
        descriptor = os.open(path, os.O_RDONLY)
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


def inside(parent, relative):
    if parent.is_symlink():
        raise ValueError("Terrain refresh refuses symbolic links")
    parent = parent.resolve()
    value = PurePosixPath(relative)
    if value.is_absolute() or ".." in value.parts or not value.parts:
        raise ValueError("Unsafe terrain refresh path")
    path = parent.joinpath(*value.parts)
    cursor = parent
    for part in value.parts:
        cursor = cursor / part
        if cursor.is_symlink():
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
    if not path.exists():
        return None
    if path.is_symlink():
        raise ValueError("Terrain policy cannot be a symbolic link")
    value = json.loads(path.read_text())
    if (not isinstance(value, dict) or value.get("format") != 1 or type(value.get("enabled")) is not bool
            or type(value.get("refresh_next_start", False)) is not bool
            or not isinstance(value.get("last_release", ""), str)):
        raise ValueError("Invalid private terrain refresh policy")
    return value


def recover(root):
    path = root / JOURNAL
    if not path.exists():
        return
    if path.is_symlink():
        raise ValueError("Terrain journal cannot be a symbolic link")
    record = json.loads(path.read_text())
    if record.get("format") != 1 or record.get("state") not in ("moving", "committed"):
        raise ValueError("Invalid terrain refresh recovery journal")
    world = inside(root / "fabric", record["world"])
    rollback = inside(root / "backups", record["rollback"])
    if not rollback.name.startswith("Terrain-Refresh-"):
        raise ValueError("Invalid terrain refresh recovery folder")
    if record["state"] == "moving":
        for relative in reversed(record["moved_paths"]):
            source = inside(world, relative)
            saved = inside(rollback / "terrain", relative)
            if saved.exists():
                if source.exists():
                    # Only the exact protected records written by this stopped
                    # transaction may be removed during rollback.
                    existing = [p for p in source.rglob("*") if p.is_file()] if source.is_dir() else [source]
                    for file in existing:
                        relative_file = file.relative_to(world).as_posix()
                        inside(world, relative_file)
                        kept_files = record.get("kept_files", {})
                        temporary_for_kept = any(file.parent == inside(world, name).parent
                            and file.name.startswith(Path(name).name + ".refresh-tmp-") for name in kept_files)
                        if not temporary_for_kept and kept_files.get(relative_file) != digest(file):
                            raise ValueError("Unexpected new terrain exists; restore the verified backup manually")
                    for file in existing:
                        file.unlink()
                    if source.is_dir():
                        for folder in sorted((p for p in source.rglob("*") if p.is_dir()), reverse=True):
                            folder.rmdir()
                        source.rmdir()
                source.parent.mkdir(parents=True, exist_ok=True)
                move(saved, source)
            elif not source.exists():
                raise ValueError("Old terrain is missing; restore the verified full backup manually")
        # The moving transaction has never written its new policy. Preserve
        # the owner's edits, especially a decision to disable refresh.
        record["state"] = "rolled_back"
    else:
        current = read_policy(root)
        if current is not None:
            atomic_json(root / POLICY, dict(current,
                        last_release=record["new_policy"]["last_release"], refresh_next_start=False))
    atomic_json(rollback / "MANIFEST.json", record)
    path.unlink()
    sync_directory(root)


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


def maybe_refresh(root, level_name, backup_name, emit=print):
    root = root.resolve()
    # The caller holds the launcher's exclusive lease and has not started Java.
    if not (root / "launcher.lock").is_file():
        raise ValueError("Terrain refresh requires a locked, stopped launcher")
    recover(root)
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
        recover(root)
        raise
    emit("Terrain regenerated for installed mod update. Verified backup: " + archive.name)
    emit("Refreshed dimension folders: " + ", ".join(p.relative_to(world / "dimensions").as_posix() for p in dimensions))
    emit("Retained protected chunk areas around saved players, helpers/pets, homes and the End arena.")
    emit("Player saves, ranks, backpacks, helper/pet entities and boss progress retained. "
         "Old terrain/chests archived: backups/" + rollback.name)
    return rollback
