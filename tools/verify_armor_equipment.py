#!/usr/bin/env python3
"""Offline native Geyser armor-packet proof; never reads a live world or opens a listener.

Compiles a separate fixture against the exact pinned Geyser jar and initializes its
real local registries/bridge in a disposable directory. Does not replace or update
the adapter, production artifacts or existing global validation receipts.
"""
import argparse
import base64
import binascii
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PIN = "17a9e00f3be106c797066664679ca73ebf25af693a750f378a9669ca93b4c07e"
PROTOCOLS = [1001, 2168, 2193]
SUITS = (("helmet", "cuirass", "leggings", "boots"),) + tuple(
    tuple(prefix + "_" + piece for piece in ("helmet", "chestplate", "leggings", "boots"))
    for prefix in ("aurora", "ember", "elemental", "infinity"))
SLOTS = ("head", "chest", "legs", "feet")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def validate_receipt(receipt):
    expected = {"format_version": 1, "suits": 5, "armor_identities": 20,
                "canonical_and_alias_definition_cases": 120,
                "native_glider_definition_cases": 3,
                "native_login_component_packets": 3, "native_full_suit_packets": 30,
                "native_independent_slot_packets": 120, "native_clear_packets": 30,
                "native_intermediate_clear_packets": 120,
                "wire_roundtrips": 303}
    if not isinstance(receipt, dict) or receipt.get("protocols") != PROTOCOLS:
        raise ValueError("Armor proof must cover every pinned protocol")
    for key, value in expected.items():
        if type(receipt.get(key)) is not int or receipt[key] != value:
            raise ValueError("Incomplete native armor evidence: " + key)
    if (receipt.get("device_rendering_verified") is not False
            or receipt.get("network_listener_started") is not False):
        raise ValueError("Offline transport proof cannot claim device rendering or start a listener")
    gliders = receipt.get("native_glider_definition_observations")
    if not isinstance(gliders, list) or len(gliders) != len(PROTOCOLS):
        raise ValueError("Every codec needs an observed native Infinity glider definition")
    for protocol, entry in zip(PROTOCOLS, gliders):
        if (not isinstance(entry, dict) or entry.get("protocol") != protocol
                or entry.get("canonical_identifier") != "convergence:infinity_chestplate"
                or entry.get("native_wire_identifier") != "minecraft:elytra"
                or entry.get("creative_alias_identifier") != "infinity_catalog:infinity_chestplate"
                or entry.get("canonical_custom_definition_absent") is not True
                or any(type(entry.get(key)) is not int or entry[key] <= 0
                       for key in ("native_runtime_id", "creative_alias_runtime_id"))
                or entry["native_runtime_id"] == entry["creative_alias_runtime_id"]):
            raise ValueError("Native Infinity flight must retain the exact elytra definition and a separate Creative alias")
    observations = receipt.get("full_suit_observations")
    expected_cases = {(p, source, suit) for p in PROTOCOLS
                      for source in ("canonical_model_stack", "native_creative_reverse_translation")
                      for suit in SUITS}
    seen = set()
    if not isinstance(observations, list):
        raise ValueError("Missing actual full-suit packet observations")
    for entry in observations:
        if not isinstance(entry, dict) or not isinstance(entry.get("equipment"), dict):
            raise ValueError("Invalid native armor observation")
        equipment = entry["equipment"]
        payload = entry.get("encoded_armor_payload")
        if not isinstance(payload, str) or not 1 <= len(payload) <= 131072:
            raise ValueError("Missing bounded actual armor wire capture")
        try:
            if not base64.b64decode(payload, validate=True):
                raise ValueError("Empty actual armor wire capture")
        except (ValueError, binascii.Error) as error:
            raise ValueError("Invalid actual armor wire capture") from error
        if set(equipment) != set(SLOTS):
            raise ValueError("Observed armor packet omits a slot")
        canonical = entry.get("canonical_equipment")
        if not isinstance(canonical, dict) or set(canonical) != set(SLOTS):
            raise ValueError("Missing canonical identities from actual cached equipment")
        if entry.get("canonical_components_preserved") is not True:
            raise ValueError("The native translator must retain all canonical stack components")
        suit = tuple(canonical[s].removeprefix("convergence:")
                     if isinstance(canonical[s], str) and canonical[s].startswith("convergence:")
                     else None for s in SLOTS)
        for slot, path in zip(SLOTS, suit):
            expected_wire = "minecraft:elytra" if path == "infinity_chestplate" else "convergence:" + str(path)
            if equipment[slot] != expected_wire:
                raise ValueError("Native armor wire base differs from its canonical identity")
        case = (entry.get("protocol"), entry.get("source"), suit)
        if case not in expected_cases or case in seen:
            raise ValueError("Unexpected, missing or duplicated native armor identity")
        seen.add(case)
    if seen != expected_cases:
        raise ValueError("Native armor observations do not cover every suit/source/protocol")
    return expected


def validate_pack(path):
    """Bind packet IDs to actual pack attachables; this is not Molang evaluation."""
    with zipfile.ZipFile(path) as archive:
        if (len(archive.namelist()) > 4096 or len(archive.namelist()) != len(set(archive.namelist()))
                or sum(entry.file_size for entry in archive.infolist()) > 128 * 1024 * 1024):
            raise ValueError("Pack exceeds bounded unique-entry limits")
        attachables = {}
        for name in archive.namelist():
            if name.startswith("attachables/") and name.endswith(".json"):
                entry = json.loads(archive.read(name))["minecraft:attachable"]["description"]
                identifier = entry["identifier"]
                if identifier in attachables:
                    raise ValueError("Duplicate armor attachable identity")
                attachables[identifier] = name
        for suit in SUITS:
            for item in suit:
                for prefix in ("convergence:", "infinity_catalog:"):
                    if prefix + item not in attachables:
                        raise ValueError("Native armor packet has no matching pack attachable: " + prefix + item)
        spec = importlib.util.spec_from_file_location("_armor_combined_wing_contract", ROOT / "tools/combined_wing_resources.py")
        combined = importlib.util.module_from_spec(spec); spec.loader.exec_module(combined)
        try:
            contract = combined.validate({name: archive.read(name) for name in archive.namelist()})
        except (KeyError, TypeError) as error:
            raise ValueError("Missing conditional native-glider pack resources") from error
        return {"sha256": digest(path), "manifest": json.loads(archive.read("manifest.json")),
                "matching_armor_attachable_identities": 40,
                "unchanged_custom_armor_identities": 19,
                "native_glider_canonical_identifier": "convergence:infinity_chestplate",
                "native_glider_wire_identifier": "minecraft:elytra",
                "conditional_native_glider_contract": contract,
                "engine_molang_evaluated": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path)
    parser.add_argument("--geyser", type=Path, default=ROOT / "server/geyser/Geyser-Standalone.jar")
    parser.add_argument("--bridge", type=Path, default=ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar")
    parser.add_argument("--exports", type=Path, default=ROOT / "research/integration/crossplay-export")
    parser.add_argument("--pack", type=Path, default=ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack")
    parser.add_argument("--output", type=Path, help="Optional new receipt; refuses to overwrite an existing file")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    java = args.java.resolve(); javac = java.with_name("javac")
    if not java.is_file() or not javac.is_file():
        raise SystemExit("Select the documented complete JDK25 with --java")
    if not 10 <= args.timeout <= 600:
        raise SystemExit("Timeout must be between10 and600seconds")
    if digest(args.geyser) != PIN:
        raise SystemExit("Require the exact pinned Geyser artifact")
    lock = json.loads((ROOT / "server/dependencies.lock.json").read_text())
    bridge_pin = next(e["sha256"] for e in lock["bundled"]
                      if e["path"] == "geyser/extensions/InfinityCreativeBridge.jar")
    if digest(args.bridge) != bridge_pin:
        raise SystemExit("Bridge differs from current pinned release lock")
    if args.output and args.output.exists():
        raise SystemExit("Refusing to overwrite an existing proof receipt")
    source = ROOT / "tools/creative-bridge-proof/ArmorEquipmentProof.java"
    inputs = [Path(__file__), source, ROOT / "tools/combined_wing_resources.py", args.geyser, args.bridge, args.pack,
              *(args.exports / n for n in ("infinity-catalogue.json", "infinity-items.json", "infinity-blocks.json"))]
    before = {str(p.resolve()): digest(p) for p in inputs}
    pack = validate_pack(args.pack)
    catalogue = json.loads((args.exports / "infinity-catalogue.json").read_text())
    catalogue_items = {i["path"]: i for i in catalogue["items"]}
    for suit in SUITS:
        for item, slot in zip(suit, SLOTS):
            if catalogue_items.get(item, {}).get("slot") != slot:
                raise SystemExit("Canonical catalogue is missing an expected armor slot")
    with tempfile.TemporaryDirectory(prefix="infinity-armor-equipment-") as temporary:
        work = Path(temporary); classes = work / "classes"; classes.mkdir()
        extensions = work / "extensions"; extensions.mkdir()
        shutil.copyfile(args.bridge, extensions / "InfinityCreativeBridge.jar")
        (work / "custom_mappings").mkdir()
        for name in ("infinity-items.json", "infinity-blocks.json"):
            shutil.copyfile(args.exports / name, work / "custom_mappings" / name)
        shutil.copyfile(args.exports / "infinity-catalogue.json", work / "infinity-catalogue.json")
        (work / "config.yml").write_text("config-version: 8\njava:\n  auth-type: offline\ngameplay:\n  enable-custom-content: true\n  force-resource-packs: true\nadvanced:\n  bedrock:\n    validate-bedrock-login: false\nenable-metrics: false\nnotify-on-new-bedrock-update: false\n")
        compiled = subprocess.run([str(javac), "--release", "21", "-encoding", "UTF-8", "-classpath",
                                   str(args.geyser.resolve()), "-d", str(classes), str(source)],
                                  cwd=work, capture_output=True, text=True, timeout=args.timeout)
        if compiled.returncode:
            raise SystemExit("Armor proof compilation failed:\n" + compiled.stdout + compiled.stderr)
        result = subprocess.run([str(java), "-Xmx512M", "-Djava.awt.headless=true", "-classpath",
                                 str(classes) + ":" + str(args.geyser.resolve()),
                                 "dev.infinityhub.proof.ArmorEquipmentProof", str(work), str(work / "proof.json")],
                                cwd=work, capture_output=True, text=True, timeout=args.timeout)
        if result.returncode or "ARMOR_EQUIPMENT_PROOF_PASSED" not in result.stdout:
            raise SystemExit("Native armor proof failed:\n" + result.stdout[-20000:] + result.stderr[-12000:])
        receipt = json.loads((work / "proof.json").read_text()); validate_receipt(receipt)
        if any(digest(p) != before[str(p.resolve())] for p in inputs):
            raise SystemExit("Armor verification inputs changed while the fixture was running")
        receipt["input_sha256"] = before
        receipt["compiled_fixture_sha256"] = {p.relative_to(classes).as_posix(): digest(p)
                                                for p in sorted(classes.rglob("*.class"))}
        receipt["native_stdout_sha256"] = hashlib.sha256(result.stdout.encode()).hexdigest()
        receipt["native_stderr_sha256"] = hashlib.sha256(result.stderr.encode()).hexdigest()
        receipt["prepared_pack_identity_check"] = pack
        receipt["tool_scope"] = "Disposable offline native registry/translator/codec check; no live server or global proof receipt changed"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x") as stream:
                json.dump(receipt, stream, indent=2); stream.write("\n")
        print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
