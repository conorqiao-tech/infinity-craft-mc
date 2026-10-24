"""Regression gates for complete armor transport evidence; not a rendering test."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("armor_equipment_verifier", ROOT / "tools/verify_armor_equipment.py")
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


def evidence():
    suits = [("helmet", "cuirass", "leggings", "boots")]
    for family in ("aurora", "ember", "elemental", "infinity"):
        suits.append(tuple(family + "_" + part for part in ("helmet", "chestplate", "leggings", "boots")))
    return {"format_version": 1, "protocols": [1001, 2168, 2193], "suits": 5,
            "armor_identities": 20, "canonical_and_alias_definition_cases": 120,
            "native_glider_definition_cases": 3,
            "native_glider_definition_observations": [
                {"protocol": protocol, "canonical_identifier": "convergence:infinity_chestplate",
                 "native_wire_identifier": "minecraft:elytra", "native_runtime_id": 500,
                 "creative_alias_identifier": "infinity_catalog:infinity_chestplate",
                 "creative_alias_runtime_id": 10000, "canonical_custom_definition_absent": True}
                for protocol in (1001, 2168, 2193)],
            "native_login_component_packets": 3, "native_full_suit_packets": 30,
            "native_independent_slot_packets": 120, "native_clear_packets": 30,
            "native_intermediate_clear_packets": 120, "wire_roundtrips": 303,
            "device_rendering_verified": False, "network_listener_started": False,
            "full_suit_observations": [
                {"protocol": protocol, "source": source, "encoded_armor_payload": "AA==",
                 "canonical_equipment": dict(zip(("head", "chest", "legs", "feet"),
                                                 ("convergence:" + name for name in suit))),
                 "canonical_components_preserved": True,
                 "equipment": dict(zip(("head", "chest", "legs", "feet"),
                                       ("minecraft:elytra" if name == "infinity_chestplate"
                                        else "convergence:" + name for name in suit)))}
                for protocol in (1001, 2168, 2193)
                for source in ("canonical_model_stack", "native_creative_reverse_translation")
                for suit in suits]}


class ArmorPacketEvidenceTests(unittest.TestCase):
    def test_packager_binds_resource_validator_and_sanitizes_complete_inputs(self):
        sys.path.insert(0, str(ROOT / "tools"))
        import package_crossplay as package
        import verify_armor_equipment as armor
        names = ["tools/verify_armor_equipment.py", "tools/creative-bridge-proof/ArmorEquipmentProof.java",
                 "tools/combined_wing_resources.py", "server/geyser/Geyser-Standalone.jar",
                 "server/geyser/extensions/InfinityCreativeBridge.jar",
                 "server/geyser/packs/Infinity_Armor_Crossplay.mcpack",
                 *("research/integration/crossplay-export/" + name for name in
                   ("infinity-catalogue.json", "infinity-items.json", "infinity-blocks.json"))]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in names:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)
            receipt = evidence()
            receipt["input_sha256"] = {str((root / name).resolve()): armor.digest(root / name) for name in names}
            receipt["prepared_pack_identity_check"] = {"fixture": "pack validation already covered separately"}
            proof = root / "research/armor-equipment" / (package.VERSION + ".json")
            proof.parent.mkdir(parents=True)
            proof.write_text(json.dumps(receipt))
            with patch.object(armor, "validate_pack", return_value=receipt["prepared_pack_identity_check"]) as validate:
                entries = package.verified_armor_packets(root)
                validate.assert_called_once_with(root / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack")
            public = json.loads(entries["checks/armor-equipment-native.json"])
            self.assertEqual(set(public["input_sha256"]), set(names))
            self.assertNotIn(str(root), json.dumps(public))
            validator = root / "tools/combined_wing_resources.py"
            validator.write_text("changed validator")
            with self.assertRaisesRegex(RuntimeError, "inputs changed"):
                package.verified_armor_packets(root)
            validator.write_text("tools/combined_wing_resources.py")
            for variant in ("missing", "extra"):
                with self.subTest(variant=variant):
                    changed = copy.deepcopy(receipt)
                    if variant == "missing":
                        changed["input_sha256"].pop(str(validator.resolve()))
                    else:
                        changed["input_sha256"][str(root / "unbound.py")] = "0" * 64
                    proof.write_text(json.dumps(changed))
                    with self.assertRaisesRegex(RuntimeError, "inputs changed"):
                        package.verified_armor_packets(root)

    def test_complete_slot_and_protocol_coverage_is_accepted(self):
        self.assertEqual(VERIFIER.validate_receipt(evidence())["armor_identities"], 20)

    def test_chestplate_only_packet_cannot_count_as_full_suit(self):
        data = evidence()
        data["full_suit_observations"][0]["equipment"] = {"chest": "convergence:cuirass"}
        with self.assertRaises(ValueError):
            VERIFIER.validate_receipt(data)

    def test_missing_or_wrong_helmet_leggings_or_boots_is_rejected(self):
        for slot in ("head", "legs", "feet"):
            with self.subTest(slot=slot):
                data = evidence()
                data["full_suit_observations"][0]["equipment"][slot] = "minecraft:air"
                with self.assertRaises(ValueError):
                    VERIFIER.validate_receipt(data)

    def test_infinity_chest_requires_native_elytra_on_wire(self):
        data = evidence()
        row = next(r for r in data["full_suit_observations"]
                   if r["canonical_equipment"]["chest"] == "convergence:infinity_chestplate")
        row["equipment"]["chest"] = "convergence:infinity_chestplate"
        with self.assertRaisesRegex(ValueError, "wire base"):
            VERIFIER.validate_receipt(data)

    def test_all_other_nineteen_armor_identities_keep_custom_wire_definitions(self):
        reference = evidence()
        seen = set()
        for index, row in enumerate(reference["full_suit_observations"]):
            for slot, identity in row["canonical_equipment"].items():
                if identity == "convergence:infinity_chestplate" or identity in seen:
                    continue
                seen.add(identity)
                with self.subTest(identity=identity):
                    data = evidence()
                    data["full_suit_observations"][index]["equipment"][slot] = "minecraft:elytra"
                    with self.assertRaisesRegex(ValueError, "wire base"):
                        VERIFIER.validate_receipt(data)
        self.assertEqual(len(seen), 19)

    def test_wire_fallback_does_not_replace_or_hide_the_cached_canonical_marker(self):
        for replacement in (None, "minecraft:elytra", "convergence:chestplate"):
            with self.subTest(replacement=replacement):
                data = evidence()
                row = next(r for r in data["full_suit_observations"]
                           if r["equipment"]["chest"] == "minecraft:elytra")
                if replacement is None:
                    del row["canonical_equipment"]["chest"]
                else:
                    row["canonical_equipment"]["chest"] = replacement
                with self.assertRaises(ValueError):
                    VERIFIER.validate_receipt(data)

    def test_native_equipment_cannot_claim_component_mutation_as_success(self):
        data = evidence()
        data["full_suit_observations"][0]["canonical_components_preserved"] = False
        with self.assertRaisesRegex(ValueError, "canonical stack components"):
            VERIFIER.validate_receipt(data)

    def test_each_native_glider_definition_keeps_a_distinct_creative_alias(self):
        for change in ("missing", "wrong_base", "alias_collision", "custom_canonical"):
            with self.subTest(change=change):
                data = evidence()
                row = data["native_glider_definition_observations"][0]
                if change == "missing":
                    data["native_glider_definition_observations"].pop()
                elif change == "wrong_base":
                    row["native_wire_identifier"] = "convergence:infinity_chestplate"
                elif change == "alias_collision":
                    row["creative_alias_runtime_id"] = row["native_runtime_id"]
                else:
                    row["canonical_custom_definition_absent"] = False
                with self.assertRaises(ValueError):
                    VERIFIER.validate_receipt(data)

    def test_duplicate_observation_cannot_hide_missing_suit(self):
        data = evidence()
        data["full_suit_observations"][-1] = copy.deepcopy(data["full_suit_observations"][0])
        with self.assertRaises(ValueError):
            VERIFIER.validate_receipt(data)

    def test_missing_protocol_or_creative_path_is_rejected(self):
        for field in ("protocol", "source"):
            data = evidence()
            data["full_suit_observations"] = [r for r in data["full_suit_observations"]
                                               if r[field] != (2193 if field == "protocol" else "native_creative_reverse_translation")]
            with self.assertRaises(ValueError):
                VERIFIER.validate_receipt(data)

    def test_reduced_counts_or_boolean_counts_are_rejected(self):
        for key in ("native_independent_slot_packets", "canonical_and_alias_definition_cases", "native_glider_definition_cases", "wire_roundtrips"):
            for value in (0, True):
                data = evidence()
                data[key] = value
                with self.assertRaises(ValueError):
                    VERIFIER.validate_receipt(data)

    def test_transport_cannot_claim_device_rendering_or_network_start(self):
        for key in ("device_rendering_verified", "network_listener_started"):
            data = evidence()
            data[key] = True
            with self.assertRaises(ValueError):
                VERIFIER.validate_receipt(data)

    def test_missing_or_corrupt_wire_capture_is_rejected(self):
        for payload in (None, "", "not base64"):
            data = evidence()
            data["full_suit_observations"][0]["encoded_armor_payload"] = payload
            with self.assertRaises(ValueError):
                VERIFIER.validate_receipt(data)

    def test_pack_requires_all_non_chest_attachables_for_both_identities(self):
        with tempfile.TemporaryDirectory() as directory:
            for missing in ("convergence:helmet", "infinity_catalog:elemental_leggings", "convergence:infinity_boots"):
                path = Path(directory) / "pack.mcpack"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr("manifest.json", "{}")
                    for index, identity in enumerate((prefix + name for suit in VERIFIER.SUITS for name in suit
                                                       for prefix in ("convergence:", "infinity_catalog:"))):
                        if identity != missing:
                            archive.writestr("attachables/" + str(index) + ".json", json.dumps({
                                "minecraft:attachable": {"description": {"identifier": identity}}}))
                with self.assertRaises(ValueError):
                    VERIFIER.validate_pack(path)

    def test_custom_armor_files_cannot_replace_the_required_native_flight_renderer(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pack.mcpack"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("manifest.json", "{}")
                for index, identity in enumerate(prefix + name for suit in VERIFIER.SUITS for name in suit
                                                 for prefix in ("convergence:", "infinity_catalog:")):
                    archive.writestr("attachables/" + str(index) + ".json", json.dumps({
                        "minecraft:attachable": {"description": {"identifier": identity}}}))
            with self.assertRaisesRegex(ValueError, "conditional native-glider"):
                VERIFIER.validate_pack(path)


if __name__ == "__main__":
    unittest.main()
