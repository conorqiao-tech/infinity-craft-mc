#!/usr/bin/env python3
"""Check native Geyser winged-chest properties offline, without opening a listener.

The fixture exercises registered translators and real packet codecs. The pack
contract is checked separately; neither establishes iPad Molang or rendering.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

from combined_wing_resources import validate as validate_resources
from prepare_crossplay import ROOT, VERSION
from verify_armor_equipment import PIN, PROTOCOLS, digest

EXPECTED_CASES = 192
CASES_PER_PROTOCOL = 64
PROPERTIES = ["infinityhub:winged_chest", "infinityhub:infinity_winged_chest"]
CANONICAL_ITEM_IDS = ["convergence:chestplate", "convergence:infinity_chestplate"]


def validate_receipt(receipt):
    if (not isinstance(receipt, dict) or receipt.get("format") != 2
            or type(receipt.get("cases")) is not int or receipt["cases"] != EXPECTED_CASES
            or receipt.get("properties") != PROPERTIES
            or receipt.get("canonical_item_ids") != CANONICAL_ITEM_IDS
            or receipt.get("real_registered_java_paths") != 5
            or receipt.get("installed_bedrock_request_guard") is not True
            or receipt.get("schema_and_property_codec_roundtrips") is not True
            or receipt.get("property_consistency_on_every_flushed_packet") is not True
            or receipt.get("network_listener_started") is not False
            or receipt.get("client_rendering_or_molang_executed") is not False
            or receipt.get("live_world_access") is not False):
        raise ValueError("Incomplete or overstated winged-chest protocol evidence")
    observed = receipt.get("observations")
    if (not isinstance(observed, list) or len(observed) != len(PROTOCOLS)
            or [entry.get("protocol") for entry in observed] != PROTOCOLS
            or any(type(entry.get("cases")) is not int or entry["cases"] != CASES_PER_PROTOCOL
                   or any(entry.get(key) is not True for key in
                          ("self_and_observer", "both_canonical_suits",
                           "real_local_armor_moves_both_suits", "consistent_property_pairs"))
                   for entry in observed)):
        raise ValueError("Every pinned protocol needs native self and observer evidence")
    labels = receipt.get("case_labels")
    if (not isinstance(labels, list) or len(labels) != EXPECTED_CASES
            or any(not isinstance(label, str) or not 1 <= len(label) <= 256 for label in labels)
            or len(set(labels)) != EXPECTED_CASES
            or any(sum(label.startswith(str(protocol) + " ") for label in labels) != CASES_PER_PROTOCOL
                   for protocol in PROTOCOLS)):
        raise ValueError("Every native proof case needs a unique label in its pinned protocol")


def current_inputs():
    exports = ROOT / "research/integration/crossplay-export"
    sources = sorted((ROOT / "geyser-extension/src/main/java").rglob("*.java"))
    names = [ROOT / "tools/check_winged_chest.py", ROOT / "tools/combined_wing_resources.py",
             ROOT / "tools/creative-bridge-proof/WingedChestProof.java",
             ROOT / "geyser-extension/extension.yml", *sources,
             ROOT / "server/dependencies.lock.json", ROOT / "server/geyser/Geyser-Standalone.jar",
             ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar",
             ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack",
             *(exports / name for name in ("infinity-catalogue.json", "infinity-items.json", "infinity-blocks.json"))]
    return {path.relative_to(ROOT).as_posix(): digest(path) for path in names}


def verified(path):
    if not path.is_file() or not 0 < path.stat().st_size <= 1048576:
        raise ValueError("Missing or oversized winged-chest receipt")
    receipt = json.loads(path.read_text())
    validate_receipt(receipt)
    if receipt.get("version") != VERSION or receipt.get("input_sha256") != current_inputs():
        raise ValueError("Winged-chest evidence differs from current candidate inputs")
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit("Refusing to overwrite an existing winged-chest proof")
    java = args.java.resolve(); javac = java.with_name("javac")
    if not java.is_file() or not javac.is_file():
        raise SystemExit("Use the documented complete JDK25")
    before = current_inputs()
    geyser = ROOT / "server/geyser/Geyser-Standalone.jar"
    extension = ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar"
    pack = ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack"
    lock = json.loads((ROOT / "server/dependencies.lock.json").read_text())
    pins = {entry["path"]: entry["sha256"] for entry in lock["bundled"] + lock["downloads"]}
    if lock["version"] != VERSION or digest(geyser) != PIN or any(
            digest(ROOT / "server" / name) != pins[name] for name in
            ("geyser/extensions/InfinityCreativeBridge.jar", "geyser/packs/Infinity_Armor_Crossplay.mcpack")):
        raise SystemExit("Require the exact prepared candidate runtime")
    with zipfile.ZipFile(pack) as archive:
        records = archive.infolist()
        if (len(records) > 4096 or len(records) != len(set(archive.namelist()))
                or sum(entry.file_size for entry in records) > 128 * 1024 * 1024):
            raise SystemExit("Pack exceeds bounded unique-entry limits")
        contract = validate_resources({entry.filename: archive.read(entry) for entry in records})
    source = ROOT / "tools/creative-bridge-proof/WingedChestProof.java"
    exports = ROOT / "research/integration/crossplay-export"
    with tempfile.TemporaryDirectory(prefix="infinity-winged-chest-") as temporary:
        work = Path(temporary); classes = work / "classes"; classes.mkdir()
        (work / "extensions").mkdir(); shutil.copyfile(extension, work / "extensions/InfinityCreativeBridge.jar")
        (work / "custom_mappings").mkdir()
        for name in ("infinity-items.json", "infinity-blocks.json"):
            shutil.copyfile(exports / name, work / "custom_mappings" / name)
        shutil.copyfile(exports / "infinity-catalogue.json", work / "infinity-catalogue.json")
        (work / "config.yml").write_text("config-version: 8\njava:\n  auth-type: offline\ngameplay:\n  enable-custom-content: true\nadvanced:\n  bedrock:\n    validate-bedrock-login: false\nenable-metrics: false\nnotify-on-new-bedrock-update: false\n")
        compiled = subprocess.run([str(javac), "--release", "21", "-encoding", "UTF-8", "-cp", str(geyser),
                                   "-d", str(classes), str(source)], cwd=work, capture_output=True, text=True, timeout=90)
        if compiled.returncode:
            raise SystemExit("Winged-chest proof compilation failed:\n" + compiled.stdout + compiled.stderr)
        result = subprocess.run([str(java), "-Xmx512M", "-Djava.awt.headless=true", "-cp", str(classes) + ":" + str(geyser),
                                 "dev.infinityhub.proof.WingedChestProof", str(work), str(work / "proof.json")],
                                cwd=work, capture_output=True, text=True, timeout=120)
        if result.returncode or f"WINGED_CHEST_PROOF_PASSED {EXPECTED_CASES}" not in result.stdout:
            raise SystemExit("Winged-chest native proof failed:\n" + result.stdout[-16000:] + result.stderr[-12000:])
        receipt = json.loads((work / "proof.json").read_text()); validate_receipt(receipt)
        if before != current_inputs():
            raise SystemExit("Winged-chest proof inputs changed during execution")
        receipt.update(version=VERSION, input_sha256=before, prepared_pack_contract=contract,
                       native_stdout_sha256=hashlib.sha256(result.stdout.encode()).hexdigest(),
                       native_stderr_sha256=hashlib.sha256(result.stderr.encode()).hexdigest(),
                       compiled_fixture_sha256={p.relative_to(classes).as_posix(): digest(p) for p in sorted(classes.rglob("*.class"))})
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("x") as stream:
            json.dump(receipt, stream, indent=2); stream.write("\n")
    print(json.dumps({key: receipt[key] for key in ("version", "cases", "observations", "client_rendering_or_molang_executed")}))


if __name__ == "__main__":
    main()
