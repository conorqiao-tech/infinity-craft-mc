#!/usr/bin/env python3
"""Build an additive, resource-only repair against the immutable explore.43 ZIP.

No server process is started and no installed file is replaced. The functional
API accepts a caller-pinned fixture baseline; the CLI always uses the published
explore.43 baseline hash and the complete 31-expression repair contract.
"""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import uuid
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BASELINE_SHA256 = "326683b341c63e93c605b3be9e4a6ef6a8c8b79bfdf0490d2f22b85544f937a4"
BASELINE_PREFIX = "Infinity_Armor_Lobbies_Server/"
REPAIR_PREFIX = "Infinity_Armor_Armor_Visibility_Repair_explore43-r1/"
PACK_PATH = "geyser/packs/Infinity_Armor_Crossplay.mcpack"
LOCK_PATH = "dependencies.lock.json"
NOTES_PATH = "ARMOR_VISIBILITY_REPAIR.md"
CONTROLLERS = ("render_controllers/original_static_armor.json", "render_controllers/elemental41.json")
VISIBILITY_COUNTS = dict(zip(CONTROLLERS, (19, 12)))
ALLOWED_PACK_CHANGES = {"manifest.json", *CONTROLLERS}
LEGACY_SUFFIX = " && !context.is_first_person"
REVISED_SUFFIX = " && (query.is_in_ui || !query.is_first_person)"
REPAIR_VERSION = (2, 13, 43001)
MAPPINGS = ("infinity-catalogue.json", "infinity-items.json", "infinity-blocks.json")
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_ENTRY = 32 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
MAX_JSON = 2 * 1024 * 1024
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def bounded_file(path, maximum):
    path = Path(path)
    if not path.is_file() or path.is_symlink() or path.stat().st_size > maximum:
        raise ValueError("Missing, linked, or oversized input: " + path.name)
    with path.open("rb") as stream:
        result = stream.read(maximum + 1)
    if len(result) > maximum:
        raise ValueError("Input grew beyond its size limit: " + path.name)
    return result


def safe_name(name):
    if (not isinstance(name, str) or not name or "\\" in name or ":" in name
            or any(ord(char) < 32 for char in name) or name.startswith("/")):
        raise ValueError("Unsafe archive or dependency path")
    parts = name.rstrip("/").split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError("Unsafe archive or dependency path")
    if any(part.casefold() in {"world", "world_nether", "world_the_end", "players", "backups", ".git",
                              ".dynu", ".remote", ".pinggy", ".runtime"} for part in parts):
        raise ValueError("Runtime state cannot be packaged")
    if parts[-1].casefold() in {"key.pem", "floodgate-key.pem", "infinity-ai-key.txt", "infinity-ai.json", "eula.txt",
                               "settings.json", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json"}:
        raise ValueError("Private runtime input cannot be packaged")
    return name


def safe_zip(data, maximum_entries=4096):
    """Read bounded members in memory; never extract an archive to disk."""
    if len(data) > MAX_ARCHIVE:
        raise ValueError("Archive exceeds its size limit")
    result = {}
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            if not infos or len(infos) > maximum_entries or sum(info.file_size for info in infos) > MAX_TOTAL:
                raise ValueError("Archive member count or expanded size exceeds its limit")
            seen = set()
            for info in infos:
                name = safe_name(info.filename)
                key = name.rstrip("/").casefold()
                mode = stat.S_IFMT(info.external_attr >> 16)
                if (key in seen or info.flag_bits & 1 or info.file_size > MAX_ENTRY
                        or info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                        or mode not in (0, stat.S_IFREG, stat.S_IFDIR)):
                    raise ValueError("Duplicate, linked, encrypted, unsupported, or oversized ZIP member")
                seen.add(key)
                result[name] = archive.read(info)
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError) as error:
        raise ValueError("Invalid or unreadable ZIP") from error
    return result


def json_object(data):
    if len(data) > MAX_JSON:
        raise ValueError("JSON exceeds its size limit")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate JSON key")
            result[key] = value
        return result

    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=unique)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("Invalid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    return value


def encoded_json(value):
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def validate_manifest(old, new, expected_version=REPAIR_VERSION):
    normalized = copy.deepcopy(new)
    try:
        pairs = [(old["header"], normalized["header"])]
        if not old["modules"] or len(old["modules"]) != len(normalized["modules"]):
            raise ValueError("Pack modules changed")
        pairs.extend(zip(old["modules"], normalized["modules"]))
        for previous, current in pairs:
            uuid.UUID(previous["uuid"])
            before, after = previous["version"], current["version"]
            if (any(not isinstance(version, list) or len(version) != 3
                    or any(type(number) is not int or number < 0 for number in version)
                    for version in (before, after))
                    or tuple(after) <= tuple(before) or tuple(after) != tuple(expected_version)):
                raise ValueError("Pack header and module versions must increase to the repair version")
            current["version"] = before
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("Malformed pack manifest") from error
    if normalized != old:
        raise ValueError("Only pack header and module version fields may change")


def validate_controller(old, new):
    """Reverse only the exact approved suffix, then compare the entire JSON."""
    normalized = copy.deepcopy(new)
    changed = 0
    try:
        for name, controller in old["render_controllers"].items():
            previous = controller.get("part_visibility", [])
            current = normalized["render_controllers"][name].get("part_visibility", [])
            if len(previous) != len(current):
                raise ValueError("Controller visibility structure changed")
            for index, parts in enumerate(previous):
                for bone, expression in parts.items():
                    replacement = current[index][bone]
                    if isinstance(expression, str) and expression.endswith(LEGACY_SUFFIX):
                        expected = expression[:-len(LEGACY_SUFFIX)] + REVISED_SUFFIX
                        if replacement != expected:
                            raise ValueError("Visibility must preserve its equipped-item guard and use the exact repair suffix")
                        current[index][bone] = expression
                        changed += 1
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("Malformed render controller") from error
    if changed == 0 or normalized != old or "!context.is_first_person" in json.dumps(new):
        raise ValueError("Controller changed beyond the approved visibility suffixes")
    return changed


def validate_pack(old_data, new_data, expected_counts=None):
    old, new = safe_zip(old_data), safe_zip(new_data)
    if old.keys() != new.keys():
        raise ValueError("Repair pack cannot add or delete entries")
    changed = {name for name in old if old[name] != new[name]}
    if changed != ALLOWED_PACK_CHANGES:
        raise ValueError("Pack changes must be exactly the manifest and two armor controllers")
    validate_manifest(json_object(old["manifest.json"]), json_object(new["manifest.json"]))
    counts = {name: validate_controller(json_object(old[name]), json_object(new[name])) for name in CONTROLLERS}
    if expected_counts is not None and counts != expected_counts:
        raise ValueError("Incomplete armor visibility repair coverage")
    return {"changed_entries": {name: {"old_sha256": digest(old[name]), "new_sha256": digest(new[name])}
                                for name in sorted(changed)},
            "unchanged_entry_count": len(old) - len(changed),
            "added_entries": [], "deleted_entries": [], "visibility_replacements": counts,
            "version": list(REPAIR_VERSION)}


def lock_entries(lock):
    result = {}
    try:
        for group in ("downloads", "bundled"):
            if not isinstance(lock[group], list):
                raise ValueError("Dependency lock groups must be lists")
            for entry in lock[group]:
                name, checksum = safe_name(entry["path"]), entry["sha256"]
                if name.endswith("/") or name in result or not isinstance(checksum, str) or not SHA256.fullmatch(checksum):
                    raise ValueError("Invalid or duplicate locked dependency")
                result[name] = checksum
    except (KeyError, TypeError) as error:
        raise ValueError("Malformed dependency lock") from error
    return result


def validate_lock(old, new, old_pack_hash, new_pack_hash):
    old_entries, new_entries = lock_entries(old), lock_entries(new)
    if old_entries.get(PACK_PATH) != old_pack_hash or new_entries.get(PACK_PATH) != new_pack_hash or old_pack_hash == new_pack_hash:
        raise ValueError("Dependency lock must bind the exact old and new pack bytes")
    normalized = copy.deepcopy(new)
    for group in ("downloads", "bundled"):
        for entry in normalized[group]:
            if entry["path"] == PACK_PATH:
                entry["sha256"] = old_pack_hash
    if normalized != old:
        raise ValueError("Only the resource-pack checksum may change in the dependency lock")
    return old_entries


def contained_file(root, relative):
    relative = safe_name(relative)
    root = Path(root).resolve()
    path = root / relative
    if root not in path.resolve().parents or not path.is_file() or path.is_symlink():
        raise ValueError("Required artifact is missing or outside its declared input folder: " + relative)
    return path


def build_repair(baseline_zip, server_root, mapping_root, notes_path, output,
                 *, expected_baseline_sha256=BASELINE_SHA256, expected_counts=None,
                 pack_validator=None, repair_name="armor-visibility-explore43-r1",
                 repair_prefix=REPAIR_PREFIX, notes_name=NOTES_PATH,
                 packager_path=None, expected_release_version=None):
    """Validate all inputs before writing a new four-file repair ZIP.

    Tests may pin a tiny fixture ZIP with expected_baseline_sha256. Production
    callers should retain the immutable default and pass VISIBILITY_COUNTS.
    A separately pinned repair may supply its own narrow pack contract while
    reusing the bounded archive, dependency, runtime, mapping and output checks.
    """
    if not repair_prefix.endswith("/"):
        raise ValueError("Repair archive root must be a folder")
    safe_name(repair_prefix)
    safe_name(notes_name)
    if notes_name in (LOCK_PATH, PACK_PATH, "PATCH.json") or notes_name.endswith("/"):
        raise ValueError("Repair notes must have a separate safe payload name")
    provenance_path = Path(packager_path or __file__).resolve()
    try:
        provenance_name = provenance_path.relative_to(ROOT).as_posix()
    except ValueError as error:
        raise ValueError("Repair packager must belong to the source tree") from error
    baseline_data = bounded_file(baseline_zip, MAX_ARCHIVE)
    if not SHA256.fullmatch(expected_baseline_sha256) or digest(baseline_data) != expected_baseline_sha256:
        raise ValueError("Baseline ZIP is not the exact expected immutable package")
    baseline = safe_zip(baseline_data, maximum_entries=512)
    if any(not name.startswith(BASELINE_PREFIX) for name in baseline):
        raise ValueError("Unexpected baseline package root")
    baseline = {name[len(BASELINE_PREFIX):]: data for name, data in baseline.items()}
    try:
        old_pack, old_lock_data = baseline[PACK_PATH], baseline[LOCK_PATH]
        runtime_data = baseline["runtime.lock.json"]
        baseline_proof = json_object(baseline["checks/armor-equipment-native.json"])
    except KeyError as error:
        raise ValueError("Baseline lacks required pack, lock, runtime, or mapping provenance") from error
    new_pack = bounded_file(contained_file(server_root, PACK_PATH), MAX_ARCHIVE)
    new_lock_data = bounded_file(contained_file(server_root, LOCK_PATH), MAX_JSON)
    notes = bounded_file(notes_path, 256 * 1024)
    if not notes.strip():
        raise ValueError("Repair notes cannot be empty")
    pack_report = (pack_validator or validate_pack)(old_pack, new_pack, expected_counts)
    old_lock, new_lock = json_object(old_lock_data), json_object(new_lock_data)
    if expected_release_version is not None and old_lock.get("version") != expected_release_version:
        raise ValueError("Baseline release identity differs from the repair contract")
    locked = validate_lock(old_lock, new_lock, digest(old_pack), digest(new_pack))
    if any(entry["path"] not in baseline for entry in old_lock["bundled"]):
        raise ValueError("Baseline is missing a bundled dependency")
    unchanged = {}
    for name, checksum in locked.items():
        if name in baseline and digest(baseline[name]) != checksum:
            raise ValueError("Baseline artifact differs from its dependency lock: " + name)
        if name != PACK_PATH:
            if file_digest(contained_file(server_root, name)) != checksum:
                raise ValueError("Current artifact differs from the baseline: " + name)
            unchanged[name] = checksum
    if bounded_file(contained_file(server_root, "runtime.lock.json"), MAX_JSON) != runtime_data:
        raise ValueError("Java runtime lock differs from the baseline")
    unchanged["runtime.lock.json"] = digest(runtime_data)
    mapping_hashes = {}
    inputs = baseline_proof.get("input_sha256")
    if not isinstance(inputs, dict):
        raise ValueError("Baseline lacks mapping hashes")
    for name in MAPPINGS:
        matches = [checksum for path, checksum in inputs.items() if PurePosixPath(path).name == name]
        if len(matches) != 1 or not isinstance(matches[0], str) or not SHA256.fullmatch(matches[0]):
            raise ValueError("Missing or ambiguous baseline mapping provenance: " + name)
        actual = digest(bounded_file(contained_file(mapping_root, name), MAX_JSON))
        if actual != matches[0]:
            raise ValueError("Current mapping differs from the baseline: " + name)
        mapping_hashes[name] = actual
    payloads = {LOCK_PATH: new_lock_data, PACK_PATH: new_pack, notes_name: notes}
    patch = {"format_version": 1, "repair": repair_name,
             "baseline": {"file": Path(baseline_zip).name, "sha256": expected_baseline_sha256,
                          "version": old_lock.get("version")},
             "expected_old_sha256": {LOCK_PATH: digest(old_lock_data), PACK_PATH: digest(old_pack)},
             "expected_new_sha256": {LOCK_PATH: digest(new_lock_data), PACK_PATH: digest(new_pack)},
             "pack_changes": pack_report, "unchanged_locked_artifacts_sha256": unchanged,
             "unchanged_mapping_sha256": mapping_hashes,
             "payload_sha256": {name: digest(data) for name, data in sorted(payloads.items())},
             "bundle_files": sorted([*payloads, "PATCH.json"]),
             "provenance": {"packager": provenance_name,
                            "packager_sha256": file_digest(provenance_path)},
             "device_rendering_verified": False, "live_installation_performed": False}
    if provenance_path != Path(__file__).resolve():
        patch["provenance"]["shared_validator"] = "tools/package_armor_visibility_repair.py"
        patch["provenance"]["shared_validator_sha256"] = file_digest(__file__)
    payloads["PATCH.json"] = encoded_json(patch)
    output = Path(output)
    if output.suffix.lower() != ".zip" or output.resolve() == Path(baseline_zip).resolve():
        raise ValueError("Repair output must be a separate ZIP")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation preserves every existing release and previous repair ZIP.
    with output.open("xb") as stream:
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(payloads.items()):
                info = zipfile.ZipInfo(repair_prefix + name, (2026, 10, 18, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                archive.writestr(info, data)
    return patch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=ROOT / "dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.43.zip")
    parser.add_argument("--server", type=Path, default=ROOT / "server")
    parser.add_argument("--mappings", type=Path, default=ROOT / "research/integration/crossplay-export")
    parser.add_argument("--notes", type=Path, default=ROOT / "server" / NOTES_PATH)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/armor-visibility-explore43-r1/Infinity_Armor_Armor_Visibility_Repair_explore43-r1.zip")
    args = parser.parse_args()
    try:
        patch = build_repair(args.baseline, args.server, args.mappings, args.notes, args.output,
                             expected_counts=VISIBILITY_COUNTS)
    except (ValueError, OSError) as error:
        parser.exit(1, "Repair packaging refused: " + str(error) + "\n")
    print(json.dumps({"output": str(args.output), "sha256": file_digest(args.output),
                      "visibility_replacements": patch["pack_changes"]["visibility_replacements"]}, indent=2))


if __name__ == "__main__":
    main()
