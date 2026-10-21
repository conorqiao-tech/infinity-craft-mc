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
import sys
import xml.etree.ElementTree as ET
import zipfile

from prepare_crossplay import VERSION, expected_native_tests
from snapshot5_dependencies import MINECRAFT, PROTOCOL, digest, snapshot_inputs, verify_lock

ROOT = Path(__file__).resolve().parents[1]
PUBLIC = Path("research/integration/snapshot5-integration.json")
PRIVATE = Path("research/snapshot5-compatibility")
SOURCE_FILES = ("tools/verify_snapshot5_integration.py", "tools/package_crossplay.py", "server/terrain_storage.py")


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
    def entities(world):
        found = {}
        for file in world.rglob("entities/r.*.mca"):
            for record in storage.region_records(file):
                nbt = storage.NBT(storage.unpack(record[4], record[5])).parse()
                for entity in nbt.get("Entities", []):
                    key = storage.identity(entity)
                    if key in wanted:
                        require(key not in found, "duplicate helper UUID in copied world")
                        found[key] = entity
        require(set(found) == wanted, "saved helper missing from copied entity regions")
        return found
    old_entities, new_entities = entities(before), entities(after)
    for key in wanted:
        original, converted = copy.deepcopy(old_entities[key]), copy.deepcopy(new_entities[key])
        old_pos, new_pos = original.pop("Pos"), converted.pop("Pos")
        original.pop("Motion", None); converted.pop("Motion", None)
        require(all(math.isfinite(x) for x in [*old_pos, *new_pos])
                and sum((a - b) ** 2 for a, b in zip(old_pos, new_pos)) <= 1, "unexplained copied helper displacement")
        for entity in (original, converted):
            attrs = entity.get("attributes", [])
            require(len({entry["id"] for entry in attrs}) == len(attrs), "duplicate helper attributes")
            entity["attributes"] = {entry["id"]: entry for entry in attrs}
        require(original == converted, "helper identity/equipment/stats or other stable fields changed")
    for name in ("snapshot5-world-conversion-lab/conversion-result.json", "snapshot5-world-conversion-lab/world-copy-manifest.json", "world-semantics-comparison.json"):
        evidence["world_" + Path(name).stem] = digest(base / name)
    evidence["world_conversion_log"] = digest(private_path(root, result["log"]))
    require(evidence["world_conversion_log"] == result["log_sha256"], "conversion log changed")
    return {"source_data_version": old_level["DataVersion"], "target_data_version": 5125,
            "input_files": len(originals), "offline_player_files": len(players), "mod_state_files": len(mods),
            "helpers": len(wanted), "offline_player_bytes_preserved": True, "mod_state_preserved": True,
            "helper_stable_fields_preserved": True, "difficulty_preserved": True,
            "original_copy_unchanged": True, "no_player_logins": True, "terrain_refreshed": False}


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
