#!/usr/bin/env python3
"""Package only validated crossplay artifacts; exclude all runtime state and credentials."""
import hashlib
import argparse
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
from generate_odyssey_assets import verify as verify_odyssey
from prepare_crossplay import ROOT, VERSION, expected_native_tests, expected_launcher_tests
from verify_owned_equipment import creative_capture_counts, verify_owned_equipment


def verified_test_hash(provenance, test_mod):
    actual = hashlib.sha256(test_mod.read_bytes()).hexdigest()
    if provenance.get("test_mod_sha256") != actual:
        raise RuntimeError("GameTest jar differs from the native validation evidence; rerun native checks")
    return actual


def verified_creative_bridge(root, lock):
    from build_creative_bridge import PIN, digest
    metadata = lock["creative_bridge"]
    sources = [root / "geyser-extension/extension.yml", root / "tools/build_creative_bridge.py",
               *sorted((root / "geyser-extension/src/main/java").rglob("*.java"))]
    expected = {p.relative_to(root).as_posix(): digest(p) for p in sources}
    if metadata["source_hashes"] != expected:
        raise RuntimeError("Creative bridge source differs from the prepared jar; rebuild, prepare and rerun checks")
    server = root / "server"
    if (metadata["geyser_sha256"] != PIN or digest(server / "geyser/Geyser-Standalone.jar") != PIN
            or digest(server / "geyser/extensions/InfinityCreativeBridge.jar") != metadata["extension_sha256"]):
        raise RuntimeError("Creative bridge artifacts differ from the pinned build")
    return metadata


def verified_creative_wire(root, lock, provenance):
    """Require actual captured Geyser bytes, Via conversion and native refusal evidence."""
    from smoke_creative_wire import bounded_json, digest, NESTED, VIAFABRIC, VIABACKWARDS
    checks = root / "research/integration/creative-bridge"
    receipt = bounded_json(checks / "wire-proof.json")
    sources = [root / "tools/smoke_creative_wire.py",
               root / "tools/native_checks/CreativeBridgeWireGameTests.java"]
    if receipt["source_hashes"] != {p.relative_to(root).as_posix(): digest(p) for p in sources}:
        raise RuntimeError("Creative wire source differs from its proof; rerun the supplemental native check")
    if (receipt["java_sources"] != provenance["java_sources"]
            or receipt["mod_sha256"] != provenance["mod_sha256"]
            or receipt["original_test_mod_sha256"] != provenance["test_mod_sha256"]):
        raise RuntimeError("Creative wire proof differs from the current native-tested source/jars")
    relative = Path(receipt["runtime"])
    if relative.is_absolute() or ".." in relative.parts or relative.parts[:2] != ("research", "creative-wire"):
        raise RuntimeError("Creative wire runtime must be an isolated research world")
    runtime = root / relative
    report = runtime / "results.xml"
    native_file = runtime / "native-wire.json"
    native = bounded_json(native_file)
    ready_file = checks / "ready.json"
    ready = bounded_json(ready_file)
    verify_owned_equipment(ready)
    startup_file = checks / "startup.json"
    catalogue = root / "research/integration/crossplay-export/infinity-catalogue.json"
    expected_counts = creative_capture_counts(bounded_json(catalogue, 131_072), ready)
    actual_hashes = {
        "report_sha256": digest(report), "native_proof_sha256": digest(native_file),
        "log_sha256": digest(runtime / "wire.log"), "capture_sha256": digest(ready_file),
        "geyser_startup_sha256": digest(startup_file), "captured_catalogue_sha256": digest(catalogue),
        "native_catalogue_sha256": digest(runtime / "crossplay-export/infinity-catalogue.json"),
        "wire_test_mod_sha256": digest(runtime / "mods" / f"Infinity-Armor-{VERSION}-gametest.jar"),
        "extension_sha256": digest(root / "server/geyser/extensions/InfinityCreativeBridge.jar"),
        "mod_sha256": digest(runtime / "mods" / f"Infinity-Armor-{VERSION}.jar"),
    }
    if any(receipt[field] != value for field, value in actual_hashes.items()) or receipt["proof"] != native:
        raise RuntimeError("Creative wire proof evidence changed; rerun the supplemental native check")
    cases = list(ET.parse(report).getroot().iter("testcase"))
    if (receipt["native_tests"] != 2 or len(cases) != 2
            or any(case.find(kind) is not None for case in cases for kind in ("failure", "error", "skipped"))):
        raise RuntimeError("Creative wire native tests must both pass without skips")
    hotbar = expected_counts["hotbar_cases"]
    overflow = len(ready["proof"]["encoded_oversized_packets"])
    if (hotbar <= 0 or overflow <= 0 or native["hotbar_cases"] != hotbar
            or native["survival_cases"] != hotbar or native["via_survival_cases"] != hotbar
            or native["overflow_cases"] != overflow or native["catalogue_equivalent"] is not True
            or native["capture_sha256"] != actual_hashes["capture_sha256"]
            or native["catalogue_sha256"] != actual_hashes["captured_catalogue_sha256"]
            or native["native_catalogue_sha256"] != actual_hashes["native_catalogue_sha256"]):
        raise RuntimeError("Creative wire proof does not cover its captured packets and catalogue")
    pinned = {entry["path"]: entry["sha256"] for entry in lock["downloads"] + lock["bundled"]}
    for name, field in ((VIAFABRIC, "viafabric_sha256"), (VIABACKWARDS, "viabackwards_sha256")):
        if (native[field] != pinned["fabric/mods/" + name]
                or native[field] != digest(root / "server/fabric/mods" / name)
                or native[field] != digest(runtime / "mods" / name)):
            raise RuntimeError("Creative wire proof uses a different Via dependency")
    with zipfile.ZipFile(root / "server/fabric/mods" / VIAFABRIC) as archive:
        if native["viaversion_nested_sha256"] != hashlib.sha256(archive.read(NESTED)).hexdigest():
            raise RuntimeError("Creative wire proof uses a different nested ViaVersion dependency")
    return {"checks/creative-bridge-wire-proof.json": (checks / "wire-proof.json").read_bytes(),
            "checks/creative-bridge-native-wire.json": native_file.read_bytes(),
            "checks/creative-bridge-wire-results.xml": report.read_bytes(),
            "checks/creative-bridge-captured-catalogue.json": catalogue.read_bytes(),
            "checks/creative-bridge-native-catalogue.json": (runtime / "crossplay-export/infinity-catalogue.json").read_bytes(),
            "checks/creative-bridge-wire-test.jar": (runtime / "mods" / f"Infinity-Armor-{VERSION}-gametest.jar").read_bytes(),
            "checks/creative-bridge-wire-log.txt": (runtime / "wire.log").read_bytes()}


def verified_creative_persistence(root, lock, provenance):
    """Bind real receive hooks, disk writes and saved reconnects to this package."""
    from smoke_creative_wire import bounded_json, digest, VIAFABRIC, VIABACKWARDS, NESTED
    from benchmark_helpers import runtime_inputs
    checks = root / "research/integration/creative-bridge"
    receipt_file = checks / "persistence-proof.json"
    receipt = bounded_json(receipt_file)
    if type(receipt.get("format_version")) is not int or receipt["format_version"] != 1:
        raise RuntimeError("Unsupported Creative persistence proof format")
    if set(receipt.get("executed_via_dependencies", {})) != {VIAFABRIC, VIABACKWARDS}:
        raise RuntimeError("Creative persistence must record both executed Via dependencies")
    sources = ["tools/smoke_creative_persistence.py", "tools/native_checks/CreativeBridgePersistenceGameTests.java",
               "tools/benchmark_helpers.py", "tools/prepare_crossplay.py", "tools/package_crossplay.py",
               "tools/smoke_creative_wire.py", "tools/verify_owned_equipment.py", "tools/crossplay_resources.py"]
    if receipt["source_hashes"] != {name: digest(root / name) for name in sources}:
        raise RuntimeError("Creative persistence source differs from its proof; rerun the isolated check")
    if (receipt["version"] != VERSION or receipt["java_sources"] != provenance["java_sources"]
            or receipt["mod_sha256"] != provenance["mod_sha256"]
            or receipt["original_test_mod_sha256"] != provenance["test_mod_sha256"]
            or receipt["native_provenance_sha256"] != digest(root / "research/integration/provenance.json")):
        raise RuntimeError("Creative persistence proof differs from the native-tested source/jars")
    wire = verified_creative_wire(root, lock, provenance)
    if (receipt["wire_receipt_sha256"] != digest(checks / "wire-proof.json")
            or receipt["wire_evidence_sha256"] != {name: hashlib.sha256(data).hexdigest() for name, data in wire.items()}):
        raise RuntimeError("Creative persistence proof uses a different wire capture")
    relative = Path(receipt["runtime"])
    if relative.is_absolute() or ".." in relative.parts or relative.parts[:2] != ("research", "creative-persistence"):
        raise RuntimeError("Creative persistence runtime must be an isolated research world")
    runtime = root / relative
    report = runtime / "results.xml"; native_file = runtime / "native-persistence.json"
    native = bounded_json(native_file)
    actual = {
        "report_sha256": digest(report), "native_proof_sha256": digest(native_file),
        "log_sha256": digest(runtime / "persistence.log"), "capture_sha256": digest(checks / "ready.json"),
        "geyser_startup_sha256": digest(checks / "startup.json"),
        "captured_catalogue_sha256": digest(root / "research/integration/crossplay-export/infinity-catalogue.json"),
        "native_catalogue_sha256": digest(runtime / "crossplay-export/infinity-catalogue.json"),
        "persistence_test_mod_sha256": digest(runtime / "mods" / f"Infinity-Armor-{VERSION}-gametest.jar"),
        "mod_sha256": digest(runtime / "mods" / f"Infinity-Armor-{VERSION}.jar"),
        "extension_sha256": digest(root / "server/geyser/extensions/InfinityCreativeBridge.jar"),
    }
    if any(receipt[key] != value for key, value in actual.items()) or receipt["proof"] != native:
        raise RuntimeError("Creative persistence evidence changed; rerun the isolated check")
    cases = list(ET.parse(report).getroot().iter("testcase"))
    if (receipt["native_tests"] != 2 or len(cases) != 2
            or any(case.find(kind) is not None for case in cases for kind in ("failure", "error", "skipped"))):
        raise RuntimeError("Creative persistence native tests must both pass without skips")
    expected_counts = creative_capture_counts(
        bounded_json(root / "research/integration/crossplay-export/infinity-catalogue.json", 131_072),
        bounded_json(checks / "ready.json"))
    flags = ("catalogue_equivalent", "receive_hook_canonical_before_listener",
             "renamed_damaged_weapon_preserved", "personal_backpack_preserved", "observer_file_unchanged")
    if (any(type(native.get(key)) is not int or native[key] != value for key, value in expected_counts.items())
            or any(native.get(key) is not True for key in flags)
            or native["capture_sha256"] != actual["capture_sha256"]
            or native["catalogue_sha256"] != actual["captured_catalogue_sha256"]
            or native["native_catalogue_sha256"] != actual["native_catalogue_sha256"]
            or native["observer_check_scope"] != "before fixture cleanup and ordinary server shutdown saves"
            or not re.fullmatch(r"[0-9a-f]{64}", native["observer_file_sha256"])):
        raise RuntimeError("Creative persistence proof does not cover receive, disk, reconnect and observer boundaries")
    if (digest(runtime / "captured-ready.json") != actual["capture_sha256"]
            or digest(runtime / "captured-catalogue.json") != actual["captured_catalogue_sha256"]):
        raise RuntimeError("Creative persistence executed capture differs from its bound inputs")
    expected_runtime = dict(receipt["runtime_inputs"])
    expected_runtime[f"mods/Infinity-Armor-{VERSION}-gametest.jar"] = actual["persistence_test_mod_sha256"]
    if runtime_inputs(runtime) != expected_runtime:
        raise RuntimeError("Creative persistence executed runtime differs from the recorded inputs")
    pinned = {entry["path"]:entry["sha256"] for entry in lock["downloads"] + lock["bundled"]}
    for name, value in receipt["executed_via_dependencies"].items():
        if value != pinned["fabric/mods/" + name] or value != digest(runtime / "mods" / name):
            raise RuntimeError("Creative persistence uses a different executed Via dependency")
    if (native["viafabric_sha256"] != receipt["executed_via_dependencies"][VIAFABRIC]
            or native["viabackwards_sha256"] != receipt["executed_via_dependencies"][VIABACKWARDS]):
        raise RuntimeError("Creative persistence native Via bindings differ from the executed jars")
    with zipfile.ZipFile(runtime / "mods" / VIAFABRIC) as via:
        nested_hash = hashlib.sha256(via.read(NESTED)).hexdigest()
    if (native["viaversion_nested_sha256"] != nested_hash
            or digest(runtime / "viaversion-pinned.jar") != nested_hash):
        raise RuntimeError("Creative persistence uses a different nested ViaVersion dependency")
    return {"checks/creative-persistence-proof.json":receipt_file.read_bytes(),
            "checks/creative-persistence-native.json":native_file.read_bytes(),
            "checks/creative-persistence-native-catalogue.json":(runtime / "crossplay-export/infinity-catalogue.json").read_bytes(),
            "checks/creative-persistence-results.xml":report.read_bytes(),
            "checks/creative-persistence-test.jar":(runtime / "mods" / f"Infinity-Armor-{VERSION}-gametest.jar").read_bytes(),
            "checks/creative-persistence-log.txt":(runtime / "persistence.log").read_bytes()}


def archive(path, entries, prefix):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            assert not name.endswith(("key.pem", "eula.txt"))
            info = zipfile.ZipInfo(prefix + "/" + name, (2026, 9, 24, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if name.startswith(".githooks/") or name.endswith((".sh", ".command", "/gradlew")) else 0o644) << 16
            output.writestr(info, data)
    with zipfile.ZipFile(path) as output:
        assert output.testzip() is None


def verified_armor_packets(root):
    """Require fresh native armor transport evidence without claiming rendering."""
    from verify_armor_equipment import digest, validate_receipt, validate_pack
    receipt_path = root / "research/armor42/native-armor-packets.json"
    receipt = json.loads(receipt_path.read_text())
    validate_receipt(receipt)
    expected = ["tools/verify_armor_equipment.py", "tools/creative-bridge-proof/ArmorEquipmentProof.java",
                "server/geyser/Geyser-Standalone.jar", "server/geyser/extensions/InfinityCreativeBridge.jar",
                "server/geyser/packs/Infinity_Armor_Crossplay.mcpack",
                *("research/integration/crossplay-export/" + name for name in
                  ("infinity-catalogue.json", "infinity-items.json", "infinity-blocks.json"))]
    actual = {str((root / name).resolve()): digest(root / name) for name in expected}
    if receipt.get("input_sha256") != actual:
        raise RuntimeError("Armor transport proof inputs changed; rerun the native armor check")
    if receipt.get("prepared_pack_identity_check") != validate_pack(root / expected[4]):
        raise RuntimeError("Armor transport proof belongs to a different prepared pack")
    # Public packages keep reproducible repository paths, never local home paths.
    receipt["input_sha256"] = {name: actual[str((root / name).resolve())] for name in expected}
    return {"checks/armor-equipment-native.json": (json.dumps(receipt, indent=2) + "\n").encode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--launcher-patch", action="store_true",
                        help="Build in a separate folder without overwriting the original mod-release packages")
    options = parser.parse_args()
    verify_odyssey()
    report = ROOT / "research/integration/results.xml"
    tests = list(ET.parse(report).getroot().iter("testcase"))
    assert len(tests) == expected_native_tests() and all(t.find("failure") is None and t.find("error") is None and t.find("skipped") is None for t in tests)
    assert report.stat().st_mtime >= max(p.stat().st_mtime for p in (ROOT / "java/src").rglob("*") if p.is_file())
    testlog_file = ROOT / "research/launcher-tests.log"
    testlog = testlog_file.read_text()
    assert f"Ran {expected_launcher_tests()} tests" in testlog and re.search(r"^OK$", testlog, re.MULTILINE)
    assert testlog_file.stat().st_mtime >= max(p.stat().st_mtime for p in [
        *(ROOT / "server").glob("*.py"), *(ROOT / "tests").glob("test_*.py")])
    mod = ROOT / f"server/fabric/mods/Infinity-Armor-{VERSION}.jar"
    # Bind every package to the exact tested source, mod and report, including ordinary releases.
    provenance = json.loads((ROOT / "research/integration/provenance.json").read_text())
    assert provenance["java_sources"] == {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                                           for p in (ROOT / "java/src").rglob("*") if p.is_file()}
    assert provenance["mod_sha256"] == hashlib.sha256(mod.read_bytes()).hexdigest()
    assert provenance["report_sha256"] == hashlib.sha256(report.read_bytes()).hexdigest()
    assert provenance["native_tests"] == len(tests)
    tested_test_sha = verified_test_hash(provenance, ROOT / f"java/build/libs/Infinity-Armor-{VERSION}-gametest.jar")
    if not options.launcher_patch:
        assert mod.stat().st_mtime >= max(p.stat().st_mtime for p in (ROOT / "java/src/main").rglob("*") if p.is_file())
    assert report.stat().st_mtime >= mod.stat().st_mtime
    geyser_log_file = ROOT / "research/geyser-smoke.log"
    smokelog = geyser_log_file.read_text()
    assert "Geyser mappings, localhost listener, and launcher shutdown passed." in smokelog and "ERROR" not in smokelog
    assert geyser_log_file.stat().st_mtime >= mod.stat().st_mtime
    textures = json.loads((ROOT / "TEXTURE_SHA256.json").read_text())
    for name, expected in textures.items():
        if (ROOT / name).exists():
            assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected
    server = ROOT / "server"
    lock = json.loads((server / "dependencies.lock.json").read_text())
    assert lock["version"] == VERSION
    bridge = verified_creative_bridge(ROOT, lock)
    creative_checks = ROOT / "research/integration/creative-bridge"
    startup = json.loads((creative_checks / "startup.json").read_text())
    ready_file = creative_checks / "ready.json"
    ready = json.loads(ready_file.read_text())
    assert startup["extension_sha256"] == bridge["extension_sha256"]
    assert startup["ready_sha256"] == hashlib.sha256(ready_file.read_bytes()).hexdigest()
    assert ready["geyser_sha256"] == bridge["geyser_sha256"]
    assert startup["catalogue_sha256"] == ready["catalogue_sha256"] == hashlib.sha256(
        (ROOT / "research/integration/crossplay-export/infinity-catalogue.json").read_bytes()).hexdigest()
    assert ready_file.stat().st_mtime >= mod.stat().st_mtime
    assert ready["unowned_primary_and_java_items_unchanged"] is True
    creative_capture_counts(json.loads(
        (ROOT / "research/integration/crossplay-export/infinity-catalogue.json").read_text()), ready)
    verify_owned_equipment(ready)
    guard = ready["proof"]["stale_request_guard"]
    protocols = len(ready["protocols"])
    stale = ready["items"] * protocols * 3
    assert guard["stale_survival_alias_requests"] == stale
    assert guard["adventure_and_spectator_requests"] == protocols * 2
    assert guard["mixed_ordinary_survival_batches"] == protocols
    assert guard["request_metadata_preservation_cases"] == stale
    assert guard["native_response_and_resync_wire_packets"] == (stale + protocols * 3) * 9
    assert all(guard[name] is True for name in (
        "complete_cached_components_counts_and_network_ids_preserved",
        "native_error_cursor_inventory_armor_offhand_and_crafting_resync",
        "ordinary_requests_and_unowned_creative_entries_unchanged"))
    wire_entries = verified_creative_wire(ROOT, lock, provenance)
    persistence_entries = verified_creative_persistence(ROOT, lock, provenance)
    entries = {name: (server / name).read_bytes() for name in ["README.md", "VALIDATION.md", "THIRD_PARTY.md",
        "TEXTURE_CREDITS.md", "server.py", "dependencies.lock.json", "Start-Mac.command", "start.sh", "start.bat",
        "runtime.py", "runtime.lock.json", "dashboard.py", "dashboard.html", "bootstrap.sh", "bootstrap.ps1", "START_HERE.txt",
        "community.py", "world_refresh.py", "terrain_storage.py", "owner_rules.py", "WORLD_REFRESH.md", "MODES.md", "LOBBIES.md", "MEMBERSHIPS.md", "SUBSCRIPTIONS.md", "REWARDS.md", "TRADING.md", "AGENTS_GUIDE.md", "EXPANSION.md", "ELEMENTAL_FORGE.md", "CREATIVE_STUDIO.md", "CREATIVE_INVENTORY.md", "COMMONS.md", "EXPERIENCE_POLISH.md", "ORIGINAL_ARTWORK.md", "RELIABILITY.md", "EXPLORATION.md", "ODYSSEY.md", "PLAYER_TRADING.md", "CONTRIBUTORS.md", "HOLIDAY_UPDATES.md", "HOSTING.md", "infinity.service.example",
        "Start-Pinggy-Mac.command", "Stop-Pinggy-Mac.command", "PINGGY_JOINING.md", "pinggy_install.py", "pinggy_joining.py", "remote_joining.py", "remote_tunnels.py",
        "Start-Dynu-Mac.command", "DYNU_JOINING.md", "dynu_joining.py"]}
    for pin in sorted((server / "setup").glob("*.txt")):
        entries["setup/" + pin.name] = pin.read_bytes()
    for file in lock["bundled"]:
        data = (server / file["path"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == file["sha256"]
        entries[file["path"]] = data
    entries["checks/java-gametest-results.xml"] = report.read_bytes()
    entries["checks/native-provenance.json"] = (ROOT / "research/integration/provenance.json").read_bytes()
    entries["checks/launcher-tests.txt"] = testlog.encode()
    for name in ("build.json", "startup.json", "ready.json"):
        entries["checks/creative-bridge-" + name] = (creative_checks / name).read_bytes()
    entries.update(wire_entries)
    entries.update(persistence_entries)
    entries.update(verified_armor_packets(ROOT))
    entries["ARMOR_REPAIR.md"] = (server / "ARMOR_REPAIR.md").read_bytes()
    capacity_checks = ROOT / "research/capacity-performance" / VERSION
    if capacity_checks.exists():
        from benchmark_capacity import capacity_labels, evidence_hashes, validate_measurement
        measured = json.loads((capacity_checks / "provenance.json").read_text())
        samples = json.loads((capacity_checks / "summary.json").read_text())
        assert measured["inputs"]["optimized"] == provenance["mod_sha256"]
        assert measured["inputs"]["tests"] == tested_test_sha
        assert measured["java_sources"] == provenance["java_sources"]
        labels = capacity_labels(measured["repeats"])
        assert set(samples) == labels
        assert measured["artifacts"] == evidence_hashes(capacity_checks, labels)
        for label, sample in samples.items():
            validate_measurement(sample)
            assert json.loads((capacity_checks / (label + ".json")).read_text()) == sample
            cases = list(ET.parse(capacity_checks / (label + ".xml")).getroot().iter("testcase"))
            assert len(cases) == 2 and all(t.find(kind) is None for t in cases for kind in ("failure", "error", "skipped"))
        for name in ("summary.json", "provenance.json"):
            entries["checks/capacity-performance-" + name] = (capacity_checks / name).read_bytes()
    fullstack_log_file = ROOT / "research/fullstack-smoke.log"
    fullstack = fullstack_log_file.read_text()
    assert "Full-stack lifecycle passed:" in fullstack and "ERROR" not in fullstack
    assert f"convergence {VERSION}" in fullstack
    assert fullstack_log_file.stat().st_mtime >= mod.stat().st_mtime
    entries["checks/fullstack-smoke.txt"] = ("\n".join(line for line in fullstack.splitlines() if line.startswith("Full-stack")) + "\n").encode()
    if options.launcher_patch:
        terrain_log = ROOT / "research/terrain-protected-fullstack-final.log"
        result = terrain_log.read_text()
        assert "Terrain refresh passed:" in result and "ERROR" not in result
        assert terrain_log.stat().st_mtime >= max(p.stat().st_mtime for p in (server).glob("*.py"))
        entries["checks/terrain-refresh-smoke.txt"] = ("\n".join(
            line for line in result.splitlines() if line.startswith(("Full-stack", "Terrain refresh passed:"))) + "\n").encode()
    entries["checks/geyser-smoke.txt"] = re.sub(r"\x1b\[[0-9;]*m", "", smokelog).encode()
    dist = ROOT / "dist/lobbies"
    if options.launcher_patch:
        dist = dist / "launcher-terrain-refresh"
    dist.mkdir(exist_ok=True, parents=True)
    server_archive = dist / f"Infinity_Armor_Lobbies_Server_v{VERSION}.zip"
    source_archive = dist / f"Infinity_Armor_Lobbies_Source_v{VERSION}.zip"
    archive(server_archive, entries, "Infinity_Armor_Lobbies_Server")
    source = {"server/" + k: v for k, v in entries.items()}
    for directory in ["java/src", "java/gradle", "bedrock/resource_pack", "artwork/refresh19", "artwork/refresh27", "artwork/elemental41", "artwork/armor42", "artwork/odyssey", "geyser-extension/src", "vendor"]:
        for file in (ROOT / directory).rglob("*"):
            if file.is_file():
                source[file.relative_to(ROOT).as_posix()] = file.read_bytes()
    for name in ["java/build.gradle", "java/settings.gradle", "java/gradle.properties", "java/gradlew", "java/gradlew.bat",
        "TEXTURE_CREDITS.md", "TEXTURE_SHA256.json", "docs/CROSSPLAY_SOURCE.md", "tools/check_crossplay.py", "tools/prepare_crossplay.py", "tools/crossplay_resources.py", "tools/build_creative_bridge.py", "geyser-extension/extension.yml",
        "tools/smoke_geyser.py", "tools/verify_owned_equipment.py", "tools/verify_armor_equipment.py", "tools/creative-bridge-proof/ArmorEquipmentProof.java", "tools/smoke_creative_wire.py", "tools/native_checks/CreativeBridgeWireGameTests.java", "tools/smoke_creative_persistence.py", "tools/native_checks/CreativeBridgePersistenceGameTests.java", "tools/check_git_identity.py", ".claude/settings.json", "CLAUDE.md", ".githooks/commit-msg", ".githooks/pre-merge-commit", ".githooks/pre-push", "tools/generate_creative_assets.py", "tools/generate_odyssey_assets.py", "tools/package_crossplay.py", "tools/gametest-framework.sha256", "tools/smoke_fullstack.py", "tools/benchmark_helpers.py", "tools/benchmark_capacity.py",
        *(p.relative_to(ROOT).as_posix() for p in sorted((ROOT / "tests").glob("test_*.py")))]:
        source[name] = (ROOT / name).read_bytes()
    source["README.md"] = (ROOT / "docs/CROSSPLAY_SOURCE.md").read_bytes()
    source["ORIGINAL_GUIDE_2.3.0.md"] = (ROOT / "README.md").read_bytes()
    archive(source_archive, source, "Infinity_Armor_Lobbies_Source")
    shutil.copy2(server / "README.md", dist / "Infinity_Armor_Lobbies_Guide.md")
    shutil.copy2(server / "HOSTING.md", dist / "Infinity_Armor_Public_Hosting.md")
    shutil.copy2(server / "MODES.md", dist / "Infinity_Armor_Game_Modes.md")
    shutil.copy2(server / "LOBBIES.md", dist / "Infinity_Armor_Lobbies.md")
    shutil.copy2(server / "MEMBERSHIPS.md", dist / "Infinity_Armor_Memberships.md")
    shutil.copy2(server / "SUBSCRIPTIONS.md", dist / "Infinity_Armor_Subscriptions.md")
    shutil.copy2(server / "REWARDS.md", dist / "Infinity_Armor_Rewards.md")
    shutil.copy2(server / "TRADING.md", dist / "Infinity_Armor_Trading.md")
    shutil.copy2(server / "AGENTS_GUIDE.md", dist / "Infinity_Armor_Agents.md")
    shutil.copy2(server / "EXPANSION.md", dist / "Infinity_Armor_Expansion.md")
    shutil.copy2(server / "ELEMENTAL_FORGE.md", dist / "Infinity_Armor_Elemental_Forge.md")
    shutil.copy2(server / "EXPLORATION.md", dist / "Infinity_Armor_Exploration.md")
    shutil.copy2(server / "ODYSSEY.md", dist / "Infinity_Armor_Odyssey.md")
    shutil.copy2(server / "PINGGY_JOINING.md", dist / "Infinity_Armor_Pinggy_Joining.md")
    shutil.copy2(server / "DYNU_JOINING.md", dist / "Infinity_Armor_Dynu_Joining.md")
    shutil.copy2(server / "PLAYER_TRADING.md", dist / "Infinity_Armor_Player_Trading.md")
    files = sorted([server_archive, source_archive, *(dist / name for name in [
        "Infinity_Armor_Lobbies_Guide.md", "Infinity_Armor_Public_Hosting.md",
        "Infinity_Armor_Game_Modes.md", "Infinity_Armor_Lobbies.md", "Infinity_Armor_Memberships.md", "Infinity_Armor_Subscriptions.md", "Infinity_Armor_Rewards.md", "Infinity_Armor_Trading.md", "Infinity_Armor_Agents.md", "Infinity_Armor_Expansion.md", "Infinity_Armor_Elemental_Forge.md", "Infinity_Armor_Exploration.md", "Infinity_Armor_Odyssey.md", "Infinity_Armor_Pinggy_Joining.md", "Infinity_Armor_Dynu_Joining.md", "Infinity_Armor_Player_Trading.md"])])
    (dist / "SHA256SUMS.txt").write_text("\n".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name for p in files) + "\n")
    for file in files:
        print(file.name, file.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
