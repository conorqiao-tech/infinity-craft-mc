"""Exact experimental runtime pins and bounded local-port provenance checks.

Ports are rebuilt from the reviewed public source recipe. They are bundled in
the package after checksum verification, never represented by upstream URLs.
"""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MINECRAFT = "26.4-snapshot-5"
PROTOCOL = 1073742167
LOADER = "0.20.1"
FABRIC_API = "fabric-api-0.164.1+26.4.jar"
FABRIC_API_SHA256 = "773f55b45e056e8e31bef6a029b9bc2b270ca63268f32f2e3a8bd1623594fcd1"
FRAMEWORK_VERSION = "4.0.40+24b2efa99f"
FRAMEWORK = "fabric-gametest-api-v1-" + FRAMEWORK_VERSION + ".jar"
FRAMEWORK_SHA256 = "2297ec98e755bfd5172ba97055113ed9e21cf8b519e3d9c0d1f5c72c261d2dd1"


def digest(path, limit=256 * 1024 * 1024):
    if not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ValueError("Missing or oversized snapshot dependency input: " + str(path))
    result = hashlib.sha256()
    with path.open("rb") as stream:
        total = 0
        for block in iter(lambda: stream.read(65536), b""):
            total += len(block)
            if total > limit:
                raise ValueError("Snapshot input exceeded bounded read")
            result.update(block)
    return result.hexdigest()


def bounded_json(path):
    if not path.is_file() or not 0 < path.stat().st_size <= 2 * 1024 * 1024:
        raise ValueError("Missing or oversized snapshot manifest")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate snapshot manifest key: " + key)
            result[key] = value
        return result
    with path.open("rb") as stream:
        raw = stream.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError("Snapshot manifest exceeded bounded read")
    value = json.loads(raw, object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ValueError("Snapshot manifest must be an object")
    return value


def fork_artifacts(root=ROOT):
    manifest = bounded_json(root / "compatibility/snapshot5/artifacts.json")
    if (manifest.get("format_version") != 1 or manifest.get("minecraft") != MINECRAFT
            or manifest.get("experimental") is not True or not isinstance(manifest.get("sha256"), dict)):
        raise ValueError("Unsupported experimental artifact manifest")
    pins = manifest["sha256"]
    patterns = (r"polymer-bundled-0\.18\.5-infinity-snapshot5\+26\.4-snapshot-5\.jar",
                r"floodgate-fabric-2\.2\.7-infinity-snapshot5\.jar",
                r"ViaFabric-0\.4\.22-infinity-snapshot5\.[1-9][0-9]*\.jar",
                r"ViaBackwards-5\.13\.0-infinity-snapshot5\.[1-9][0-9]*\.jar")
    if len(pins) != len(patterns) or any(sum(bool(re.fullmatch(pattern, name)) for name in pins) != 1 for pattern in patterns):
        raise ValueError("Require exactly the four reviewed local-port artifacts")
    if any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value) for value in pins.values()):
        raise ValueError("Invalid local-port artifact checksum")
    return dict(pins)


def snapshot_inputs(root=ROOT):
    """Bind proofs to exact source pins, patches, licenses and rebuild/check tools."""
    base = root / "compatibility/snapshot5"
    manifest = bounded_json(base / "sources.json")
    projects = manifest.get("projects")
    if (manifest.get("format_version") != 1 or manifest.get("minecraft") != MINECRAFT
            or manifest.get("protocol") != PROTOCOL or manifest.get("experimental") is not True):
        raise ValueError("Unsupported snapshot source manifest")
    if not isinstance(projects, list) or any(not isinstance(p, dict) for p in projects) or {p.get("name") for p in projects} != {
            "polymer", "floodgate", "viaversion", "viabackwards", "viafabric"} or len(projects) != 5:
        raise ValueError("Require five pinned public source projects")
    files = [base / "sources.json", base / "artifacts.json",
             root / "tools/build_snapshot5_dependencies.py", root / "tools/snapshot5_dependencies.py"]
    fork_artifacts(root)
    for project in projects:
        if not re.fullmatch(r"[0-9a-f]{40}", project.get("commit", "")):
            raise ValueError("A full upstream source commit is required")
        for name in ("patch", "license"):
            relative = Path(project[name])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("Port source input escapes its manifest directory")
            path = base / relative
            if digest(path, 16 * 1024 * 1024) != project[name + "_sha256"]:
                raise ValueError("Changed local-port " + name + ": " + project["name"])
            files.append(path)
    return {path.relative_to(root).as_posix(): digest(path, 16 * 1024 * 1024) for path in files}


def staged_forks(root=ROOT):
    pins = fork_artifacts(root)
    for name, expected in pins.items():
        if digest(root / "vendor/snapshot5" / name) != expected:
            raise ValueError("Rebuild the reviewed snapshot port before packaging: " + name)
    return pins


def verify_lock(lock, root=ROOT):
    if lock.get("minecraft") != MINECRAFT:
        raise ValueError("Dependency lock is for a different native Minecraft version")
    if lock.get("snapshot5") != {"experimental": True, "protocol": PROTOCOL, "source_inputs": snapshot_inputs(root)}:
        raise ValueError("Snapshot source/recipe inputs differ from the prepared lock")
    pins = {entry["path"]: entry["sha256"] for entry in lock["downloads"] + lock["bundled"]}
    if any(pins.get("fabric/mods/" + name) != sha for name, sha in fork_artifacts(root).items()):
        raise ValueError("Dependency lock does not bundle the exact reviewed local ports")
