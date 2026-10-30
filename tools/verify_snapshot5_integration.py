#!/usr/bin/env python3
"""Validate private integration evidence and export a sanitized release receipt.

Reads disposable research fixtures only. No server controls, network requests,
world writes, identities, host paths or raw NBT enter the public receipt.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

from prepare_crossplay import VERSION, expected_native_tests
from snapshot5_dependencies import MINECRAFT, PROTOCOL, digest, snapshot_inputs, verify_lock

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = Path("research/integration/snapshot5-integration.json")
PRIVATE = Path("research/snapshot5-compatibility")
SOURCE_FILES = ("tools/verify_snapshot5_integration.py", "tools/package_crossplay.py", "server/terrain_storage.py")
HELPER_POWER_MARKER = "infinity_helper_power_defaults"
# Exact Snapshot5 IronGolem native bases and explore.51's five finite defaults.
# This independent expectation is deliberately not imported from production code
# or taken from a conversion receipt's declared changes.
HELPER_POWER_DEFAULTS = {
    "minecraft:max_health": (100.0, 1000.0),
    "minecraft:attack_damage": (15.0, 100.0),
    "minecraft:armor": (0.0, 30.0),
    "minecraft:armor_toughness": (0.0, 20.0),
    "minecraft:knockback_resistance": (1.0, 1.0),
}


def require(condition, message):
    if not condition:
        raise ValueError("Snapshot integration: " + message)


def read_json(path):
    require(path.is_file() and 0 < path.stat().st_size <= 8 * 1024 * 1024, "missing or oversized evidence")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate evidence key")
            result[key] = value
        return result
    with path.open("rb") as stream:
        body = stream.read(8 * 1024 * 1024 + 1)
    require(len(body) <= 8 * 1024 * 1024, "evidence exceeded bounded read")
    data = json.loads(body, object_pairs_hook=unique)
    require(isinstance(data, dict), "evidence must be an object")
    return data


def private_path(root, value):
    path = Path(value)
    path = (path if path.is_absolute() else root / path).resolve()
    require((root / PRIVATE).resolve() in path.parents, "evidence escaped disposable fixture directory")
    return path


def cases(path, expected):
    require(path.is_file() and path.stat().st_size <= 8 * 1024 * 1024, "missing or oversized native report")
    tree = ET.parse(path)
    rows = list(tree.getroot().iter("testcase"))
    names = [row.get("name") for row in rows]
    require(len(rows) == expected and len(set(names)) == expected, "incomplete/duplicate native test cases")
    require(not any(row.find(kind) is not None for row in rows for kind in ("failure", "error", "skipped")), "native tests failed or skipped")
    return tree, names


def native_entity_dimension(relative):
    """Derive the saved native level from its entity-region path, not a receipt."""
    parts = Path(relative).parts
    if len(parts) == 2 and parts[0] == "entities":
        return "minecraft:overworld"
    if len(parts) == 3 and parts[1] == "entities" and parts[0] in ("DIM-1", "DIM1"):
        return "minecraft:the_nether" if parts[0] == "DIM-1" else "minecraft:the_end"
    require(len(parts) >= 5 and parts[0] == "dimensions" and parts[-2] == "entities",
            "unsupported saved helper native level path")
    dimension = parts[1] + ":" + "/".join(parts[2:-2])
    require(re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", dimension) is not None,
            "invalid saved helper native level")
    return dimension


def native_helper_uuid(entity):
    raw = entity.get("UUID")
    require(isinstance(raw, list) and len(raw) == 4
            and all(type(value) is int and -(2 ** 31) <= value < 2 ** 31 for value in raw),
            "invalid native helper UUID")
    return str(uuid.UUID(bytes=b"".join(struct.pack(">I", value & 0xffffffff) for value in raw)))


def same_native_value(first, second):
    """Keep every decoded field, including signed zero and numeric value kinds."""
    if type(first) is not type(second):
        return False
    if isinstance(first, dict):
        return first.keys() == second.keys() and all(same_native_value(first[key], second[key]) for key in first)
    if isinstance(first, list):
        return len(first) == len(second) and all(same_native_value(a, b) for a, b in zip(first, second))
    if isinstance(first, float):
        return math.isfinite(first) and math.isfinite(second) and struct.pack(">d", first) == struct.pack(">d", second)
    return first == second


def validate_helper_registrations(registrations):
    """Accept old rosters or the provenance-aware format without weakening helper checks."""
    require(isinstance(registrations, dict) and type(registrations.get("format")) is int
            and registrations["format"] in (2, 3) and isinstance(registrations.get("agents"), dict),
            "invalid saved helper registration document")
    if registrations["format"] == 2:
        require("guardedRemovals" not in registrations, "old helper roster cannot assert new removal provenance")
        return
    dismissed = registrations.get("dismissed")
    guarded = registrations.get("guardedRemovals")
    require(isinstance(dismissed, list) and isinstance(guarded, list)
            and len(dismissed) <= 256 and len(guarded) <= 256, "invalid removal provenance lists")
    def identities(values):
        result = set()
        for value in values:
            try:
                valid = isinstance(value, str) and str(uuid.UUID(value)) == value
            except (ValueError, AttributeError):
                valid = False
            require(valid and value not in result and value not in registrations["agents"], "invalid removal provenance identity")
            result.add(value)
        return result
    reserved, proven = identities(dismissed), identities(guarded)
    require(proven <= reserved, "unreserved guarded removal provenance")
    pending = registrations.get("pendingCreations")
    require(isinstance(pending, list) and len(pending) <= 50, "invalid pending helper identities")
    seen = set()
    for value in pending:
        try:
            valid = isinstance(value, str) and str(uuid.UUID(value)) == value
        except (ValueError, AttributeError):
            valid = False
        require(valid and value not in seen and value in registrations["agents"], "invalid pending helper identity")
        seen.add(value)


def verify_saved_helper(original, converted, dimension, registrations, version=VERSION):
    """Derive exactly one authorized migration from native NBT and registration.

    Every other decoded field remains strict, except the existing bounded
    position/velocity allowance and irrelevant attribute-list ordering. The
    returned observation contains no identity, name, position, world or values.
    """
    require(isinstance(original, dict) and isinstance(converted, dict), "invalid helper NBT")
    identity = native_helper_uuid(original)
    require(native_helper_uuid(converted) == identity, "copied helper UUID changed")
    validate_helper_registrations(registrations)
    agent = registrations["agents"].get(identity)
    require(isinstance(agent, dict), "native helper is not registered")
    owner = agent.get("owner")
    try:
        require(isinstance(owner, str) and str(uuid.UUID(owner)) == owner, "invalid registered helper owner")
    except (ValueError, AttributeError):
        require(False, "invalid registered helper owner")
    require(isinstance(agent.get("name"), str) and re.fullmatch(r"[a-z0-9_-]{1,24}", agent["name"]) is not None
            and agent.get("mode") in ("FOLLOW", "GUARD", "STAY")
            and agent.get("profile") in ("PRIMITIVE", "REGULAR", "ULTIMATE_FINALS", "DEBUG", "CLI", "API"),
            "invalid saved helper name, mode or profile")
    require(agent.get("dimension") == dimension and re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_./-]+", dimension) is not None,
            "registered helper native level differs")
    require(all(type(agent.get(axis)) in (int, float) and math.isfinite(agent[axis]) for axis in ("x", "y", "z"))
            and abs(agent["x"]) <= 30_000_000 and abs(agent["z"]) <= 30_000_000 and -2048 <= agent["y"] <= 2048,
            "invalid registered helper anchor")
    tags = original.get("Tags")
    require(original.get("id") == "minecraft:iron_golem" and type(original.get("PlayerCreated")) is int
            and original["PlayerCreated"] == 1 and type(original.get("PersistenceRequired")) is int
            and original["PersistenceRequired"] == 1 and isinstance(tags, list)
            and all(isinstance(tag, str) for tag in tags) and len(set(tags)) == len(tags)
            and "infinity_agent" in tags, "original entity is not the registered native helper")
    for prefix, value in (("infinity_owner_", owner), ("infinity_name_", agent["name"]),
                          ("infinity_mode_", agent["mode"].lower()), ("infinity_profile_", agent["profile"].lower())):
        require([tag for tag in tags if tag.startswith(prefix)] == [prefix + value], "saved helper tags differ from registration")
    require(type(original.get("Health")) in (int, float) and math.isfinite(original["Health"]) and original["Health"] > 0,
            "original registered helper is not living")
    expected = copy.deepcopy(original)
    attrs = expected.get("attributes")
    require(isinstance(attrs, list) and all(isinstance(row, dict) and isinstance(row.get("id"), str)
            and type(row.get("base")) in (int, float) and math.isfinite(row["base"]) for row in attrs)
            and len({row["id"] for row in attrs}) == len(attrs), "invalid or duplicate original helper attributes")
    originals = expected.get("InfinityAdminStats", {})
    require(isinstance(originals, dict), "invalid original helper admin undo history")
    marker_present = HELPER_POWER_MARKER in originals
    migrated = version == "2.13.0-explore.51" and not marker_present
    changed = []; materialized = []
    if migrated:
        by_id = {row["id"]: row for row in attrs}
        for attribute, (native_base, target_base) in HELPER_POWER_DEFAULTS.items():
            row = by_id.get(attribute)
            if row is None:
                # Native IronGolem supports these five instances even when an
                # older save omitted a default row. getAttribute materializes
                # them before the production override/history check.
                row = {"id": attribute, "base": native_base}
                attrs.append(row); by_id[attribute] = row
                materialized.append(attribute); changed.append(attribute)
            # Java Double.compare distinguishes -0.0 from the native +0.0 base.
            if attribute not in originals and struct.pack(">d", float(row["base"])) == struct.pack(">d", native_base):
                if row["base"] != target_base and attribute not in changed:
                    changed.append(attribute)
                row["base"] = target_base
        originals[HELPER_POWER_MARKER] = 1
        expected["InfinityAdminStats"] = originals
    actual = copy.deepcopy(converted)
    old_pos, new_pos = expected.pop("Pos", None), actual.pop("Pos", None)
    require(isinstance(old_pos, list) and isinstance(new_pos, list) and len(old_pos) == len(new_pos) == 3
            and all(type(value) in (int, float) and math.isfinite(value) for value in [*old_pos, *new_pos])
            and sum((a - b) ** 2 for a, b in zip(old_pos, new_pos)) <= 1, "unexplained copied helper displacement")
    expected.pop("Motion", None); actual.pop("Motion", None)
    for entity in (expected, actual):
        rows = entity.get("attributes")
        require(isinstance(rows, list) and all(isinstance(row, dict) and isinstance(row.get("id"), str) for row in rows)
                and len({row["id"] for row in rows}) == len(rows), "invalid or duplicate copied helper attributes")
        entity["attributes"] = {row["id"]: row for row in rows}
    require(same_native_value(expected, actual), "helper differs from exact expected defaults migration or preserved stable fields")
    return {"migration_applied": migrated, "existing_marker_preserved": marker_present,
            "changed_attribute_ids": sorted(changed), "materialized_attribute_ids": sorted(materialized), "marker_added": migrated}


def native_world(root, base, label, evidence):
    """Recheck copied save bytes, JSON and helper semantics without exporting them."""
    sys.path.insert(0, str(root / "server"))
    import terrain_storage as storage
    result = read_json(base / "snapshot5-world-conversion-lab/conversion-result.json")
    manifest = read_json(base / "snapshot5-world-conversion-lab/world-copy-manifest.json")
    semantics = read_json(base / "world-semantics-comparison.json")
    before = base / "world-upgrade-fixture/world"
    after = base / "snapshot5-world-conversion-lab/world"
    require(private_path(root, manifest["source_world"]) == before.resolve()
            and private_path(root, manifest["converted_world"]) == after.resolve(), "unexpected private conversion world")
    require(result["exit_code"] == 0 and result["clean_stop"] is True and result["no_player_logins"] is True
            and result["input_fixture_unchanged"] is True and result["terrain_refresh"] is False
            and result["force_load_removed"] is True and result["passed_preservation_checks"] is True
            and semantics["passed"] is True and semantics["live_world_used"] is False
            and semantics["original_fixture_unchanged"] is True and manifest["live_world_used"] is False
            and manifest["terrain_refresh"] is False and manifest["copied_world_files_match"] is True,
            "conversion must preserve its original private copy without admitting a player or refreshing terrain")
    require(result["mods"] == label and manifest["mods"] == label, "conversion used different release mods")
    originals = manifest["original_fixture_files"]
    require(len(originals) == manifest["input_file_count"] == result["input_file_count"] and len(originals) > 0,
            "conversion input file count changed")
    for relative, expected in originals.items():
        path = (before / relative).resolve()
        require(before.resolve() in path.parents and digest(path) == expected, "original conversion file changed")
    players = {name: value for name, value in originals.items() if name.startswith("players/")}
    require(players and len(players) == result["offline_player_file_count"]
            and result["all_offline_player_files_byte_identical"] is True, "missing offline player preservation")
    for name, expected in players.items():
        require(digest(after / name) == expected, "offline player data/stat/advancement bytes changed")
    mods = semantics["mod_json_equal"]
    require(mods and set(mods) == set(result["private_mod_json"]) and all(value is True for value in mods.values()),
            "mod state preservation comparison failed")
    for name in mods:
        require(Path(name).name == name and read_json(before / name) == read_json(after / name), "private mod state changed")
    require("infinity-agents.json" in mods and "infinity-agents.json" in originals,
            "missing actual saved helper registrations")
    registrations = read_json(before / "infinity-agents.json")
    validate_helper_registrations(registrations)
    old_level = storage.saved_nbt(before / "level.dat")["Data"]
    new_level = storage.saved_nbt(after / "level.dat")["Data"]
    require(result["source_data_version"] == old_level["DataVersion"]
            and new_level["DataVersion"] == result["converted_level_data_version"] == semantics["converted_data_version"] == 5125,
            "conversion data versions do not match actual saved NBT")
    difficulty = {key for key in ("difficulty_settings", "difficulty", "Difficulty", "hardcore", "DifficultyLocked") if key in old_level}
    require(difficulty and all(old_level[key] == new_level.get(key) for key in difficulty), "difficulty changed in copied NBT")
    helpers = result["helpers"]
    wanted = {helper["uuid"] for helper in helpers}
    require(wanted and len(wanted) == len(helpers) == len(semantics["helper_comparison"]), "missing copied helper comparison")
    require(wanted == set(registrations["agents"]), "copied helper comparison omitted or invented a registered helper")
    def entities(world):
        found = {}
        for file in world.rglob("entities/r.*.mca"):
            for record in storage.region_records(file):
                nbt = storage.NBT(storage.unpack(record[4], record[5])).parse()
                for entity in nbt.get("Entities", []):
                    key = storage.identity(entity)
                    require("infinity_agent" not in entity.get("Tags", []) or key in wanted,
                            "unregistered tagged helper in copied world")
                    if key in wanted:
                        require(key not in found, "duplicate helper UUID in copied world")
                        found[key] = (entity, native_entity_dimension(file.relative_to(world)))
        require(set(found) == wanted, "saved helper missing from copied entity regions")
        return found
    old_entities, new_entities = entities(before), entities(after)
    migration_observations = []
    for key in wanted:
        original, old_dimension = old_entities[key]; converted, new_dimension = new_entities[key]
        require(old_dimension == new_dimension, "copied helper changed native levels")
        require(native_helper_uuid(original) == key, "saved helper native UUID differs from registration")
        migration_observations.append(verify_saved_helper(original, converted, old_dimension, registrations))
    for name in ("snapshot5-world-conversion-lab/conversion-result.json", "snapshot5-world-conversion-lab/world-copy-manifest.json", "world-semantics-comparison.json"):
        evidence["world_" + Path(name).stem] = digest(base / name)
    evidence["world_conversion_log"] = digest(private_path(root, result["log"]))
    require(evidence["world_conversion_log"] == result["log_sha256"], "conversion log changed")
    return {"source_data_version": old_level["DataVersion"], "target_data_version": 5125,
            "input_files": len(originals), "offline_player_files": len(players), "mod_state_files": len(mods),
            "helpers": len(wanted), "offline_player_bytes_preserved": True, "mod_state_preserved": True,
            "helper_stable_fields_preserved": True, "difficulty_preserved": True,
            "original_copy_unchanged": True, "no_player_logins": True, "terrain_refreshed": False,
            "helper_default_migration": {
                "migrated_helpers": sum(row["migration_applied"] for row in migration_observations),
                "existing_markers_preserved": sum(row["existing_marker_preserved"] for row in migration_observations),
                "markers_added": sum(row["marker_added"] for row in migration_observations),
                "marker_version": 1,
                "changed_attribute_ids": sorted({attribute for row in migration_observations for attribute in row["changed_attribute_ids"]}),
                "changed_attributes": sum(len(row["changed_attribute_ids"]) for row in migration_observations),
                "materialized_attribute_ids": sorted({attribute for row in migration_observations for attribute in row["materialized_attribute_ids"]}),
                "materialized_attributes": sum(len(row["materialized_attribute_ids"]) for row in migration_observations)}}


def collect(root=ROOT):
    root = root.resolve(); base = root / PRIVATE; evidence = {}
    lock = read_json(root / "server/dependencies.lock.json"); verify_lock(lock, root)
    pinned = {entry["path"]: entry["sha256"] for entry in lock["downloads"] + lock["bundled"]}
    mods = {Path(name).name: sha for name, sha in pinned.items() if name.startswith("fabric/mods/")}
    require(len(mods) == 6, "unexpected release mod set")
    require(all(digest(root / "server/fabric/mods" / name) == sha for name, sha in mods.items()), "release mod bytes changed")
    inputs = snapshot_inputs(root)
    native = read_json(root / "research/integration/provenance.json")
    require(native.get("snapshot5_inputs") == inputs and native["mod_sha256"] == mods[f"Infinity-Armor-{VERSION}.jar"], "native provenance differs")
    _, native_names = cases(root / "research/integration/results.xml", expected_native_tests())
    require(native["report_sha256"] == digest(root / "research/integration/results.xml"), "native aggregate report changed")
    codec = read_json(base / "upstream-bridge/artifact-lock-snapshot5.2.json")
    bridge = read_json(base / "upstream-bridge/integration-proof.json")
    require(codec["target"] == bridge["target"] == MINECRAFT and codec["protocol"] == bridge["target_protocol"] == PROTOCOL,
            "wrong translated protocol target")
    for entry in bridge["evidence"]:
        path = private_path(root, entry["path"])
        require(digest(path) == entry["sha256"], "private bridge evidence changed")
    codec_xml = base / "upstream-bridge/ViaBackwards/common/build/test-results/test/TEST-dev.infinity.bridge.Snapshot5BridgeTest.xml"
    tree, names = cases(codec_xml, 21)
    require(dict(tree.getroot().attrib) == codec["test_result"] == bridge["unit_codec_packet_tests"]
            and set(names) == set(codec["test_names"]), "codec test manifest/report differ")
    source_manifest = read_json(root / "compatibility/snapshot5/sources.json")
    for label, project in (("ViaVersion", "viaversion"), ("ViaBackwards", "viabackwards")):
        source = next(p for p in source_manifest["projects"] if p["name"] == project)
        seal = codec["sources"][label]
        require(seal["upstream_commit"] == source["commit"] and
                {item["path"]: item["sha256"] for item in seal["changed_files"]} == source["changed_inputs"], "codec source seal differs from public patches")
    with zipfile.ZipFile(root / "server/fabric/mods/ViaFabric-0.4.22-infinity-snapshot5.1.jar") as jar:
        nested = hashlib.sha256(jar.read("META-INF/jars/viaversion-common-5.13.0-infinity-snapshot5.2.jar")).hexdigest()
    artifacts = {entry["kind"]: entry["sha256"] for entry in codec["artifacts"]}
    require(artifacts["viaversion-common"] == nested == bridge["nested_viaversion"]["sha256"]
            and artifacts["viabackwards-fabric"] == mods["ViaBackwards-5.13.0-infinity-snapshot5.2.jar"], "codec tests used different Via artifacts")
    require(bridge["tested_mod_hashes"] == mods, "translated TCP Play used different app/dependencies")
    app = read_json(base / "snapshot5-crossplay-lab/final-app-run.json")
    require(app["mods"] == mods and app["stopped_cleanly"] is True and app["live_world_used"] is False
            and app["fresh_generated_floodgate_key_only"] is True and app["online_mode"] is True
            and app["whitelist_enforced"] is True and app["native_test_jar_shipped"] is False,
            "full-app security/runtime boundary changed")
    require(bridge["lab_security"] == {"online_mode": True, "whitelist": True, "enforce_whitelist": True,
            "fresh_keys_only": True, "live_keys_or_world_copied": False}, "bridge authentication boundary weakened")
    positive_path = base / "upstream-bridge/probe/allow-app-clean.json"
    play = read_json(positive_path)
    require(digest(positive_path) == app["positive"]["sha256"], "full-app positive probe changed")
    require(all(play[key] == value for key, value in bridge["full_app_synthetic_probe"].items()), "bridge copied probe does not match raw result")
    require(play["backend_protocol_expected"] == PROTOCOL and play["client_protocol"] == 776
            and play["expected_outcome_passed"] is True and play["play_reached"] is True
            and play["elapsed_seconds"] >= 90 and play["chunks_decoded"] >= 49 and play["errors"] == []
            and play["floodgate_identity_matches"] is True and play["fresh_lab_key_only"] is True
            and play["native_respawn_requested"] is True and play["teleports_acknowledged"] >= 2,
            "sustained translated Play/identity/chunk/respawn requirements failed")
    packets = ("ClientboundRegistryDataPacket", "ClientboundLoginPacket", "ClientboundUpdateAdvancementsPacket",
               "ClientboundLevelChunkWithLightPacket", "ClientboundContainerSetContentPacket", "ClientboundRespawnPacket")
    require(all(play["packets"].get(name, 0) > 0 for name in packets), "required translated packet family missing")
    negatives = {}
    for mode, reason in (("denied", "not_whitelisted"), ("wrongkey", "official Geyser"), ("tamper", "official Geyser"), ("plain", "requires Minecraft Java")):
        path = base / ("upstream-bridge/probe/" + mode + "-clean.json"); result = read_json(path)
        require(all(result[key] == value for key, value in bridge["negative_probes"][mode].items()), "negative raw/copy probe differs")
        require(result["expected_outcome_passed"] is True and result["server_closed_before_timeout"] is True
                and result["play_reached"] is False and result["errors"] == [] and reason in result["disconnect_reason"], "negative authentication probe failed")
        negatives[mode] = True; evidence["negative_" + mode] = digest(path)
    geyser = bridge["actual_geyser_runtime_smoke"]
    require(geyser["jar_sha256"] == pinned["geyser/Geyser-Standalone.jar"] and geyser["success"] is True
            and geyser["fresh_key_only"] is True and geyser["validate_bedrock_login"] is True
            and geyser["actual_geyser_runtime"] is True and geyser["ipad_authenticated_login_tested"] is False,
            "actual Geyser startup evidence differs")
    ack = read_json(base / "viafabric-ack-runtime/result-final.json")
    ack_xml = private_path(root, ack["report"]); _, ack_names = cases(ack_xml, 5)
    require(ack_names.count("minecraft:always_pass") == 1, "ACK proof must contain four targeted tests and one framework baseline")
    require(ack["minecraft"] == MINECRAFT and ack["exit_code"] == 0 and ack["passed"] is True
            and ack["cases"] == 5 and set(ack_names) == set(ack["case_names"])
            and digest(ack_xml) == ack["report_sha256"] and all(ack["mods"].get(name) == sha for name, sha in mods.items() if not name.startswith("Infinity-Armor-")), "native ACK proof differs")
    evidence.update({"codec_report": digest(codec_xml), "codec_artifacts": digest(base / "upstream-bridge/artifact-lock-snapshot5.2.json"),
        "bridge_integration": digest(base / "upstream-bridge/integration-proof.json"), "translated_play": digest(positive_path),
        "app_run": digest(base / "snapshot5-crossplay-lab/final-app-run.json"), "ack_result": digest(base / "viafabric-ack-runtime/result-final.json"), "ack_report": digest(ack_xml)})
    world = native_world(root, base, mods, evidence)
    return {"format_version": 1, "version": VERSION, "minecraft": MINECRAFT, "protocol": PROTOCOL, "experimental": True,
            "snapshot5_inputs": inputs, "source_hashes": {name: digest(root / name) for name in SOURCE_FILES},
            "dependency_lock_sha256": digest(root / "server/dependencies.lock.json"), "runtime_mod_sha256": mods,
            "native_tests": len(native_names), "codec_tests": len(names),
            "ack_tests": {"total": len(ack_names), "targeted": len([n for n in ack_names if n != "minecraft:always_pass"]), "framework_baseline": ack_names.count("minecraft:always_pass")},
            "translated_play": {"client_protocol": 776, "seconds": play["elapsed_seconds"], "chunks": play["chunks_decoded"],
                                "teleports_acknowledged": play["teleports_acknowledged"], "native_respawn": True,
                                "verified_floodgate_identity": True, "decoded_packet_families": list(packets)},
            "negative_authentication_probes": negatives, "online_mode_and_whitelist_retained": True,
            "actual_geyser_runtime_started": True, "world_conversion": world, "evidence_sha256": evidence,
            "boundaries": {"actual_ipad_or_xbox_login_tested": False, "device_rendering_verified": False,
                           "synthetic_fresh_key_probe": True, "live_world_modified": False}}


def verified(root=ROOT):
    receipt = read_json(root / PUBLIC)
    require(receipt == collect(root), "public receipt is stale, changed or contains unapproved/private fields")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / PUBLIC)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    receipt = collect()
    if not args.check_only:
        output = args.output.resolve()
        require((ROOT / "research").resolve() in output.parents, "receipt must stay in research")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Snapshot integration evidence verified; sanitized receipt contains no identities, hosts or raw saves.")


if __name__ == "__main__":
    main()
