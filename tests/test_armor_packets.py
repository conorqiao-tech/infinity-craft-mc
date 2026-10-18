"""Regression gates for complete armor transport evidence; not a rendering test."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
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
            "native_login_component_packets": 3, "native_full_suit_packets": 30,
            "native_independent_slot_packets": 120, "native_clear_packets": 30,
            "native_intermediate_clear_packets": 120, "wire_roundtrips": 303,
            "device_rendering_verified": False, "network_listener_started": False,
            "full_suit_observations": [
                {"protocol": protocol, "source": source, "encoded_armor_payload": "AA==",
                 "equipment": dict(zip(("head", "chest", "legs", "feet"),
                                       ("convergence:" + name for name in suit)))}
                for protocol in (1001, 2168, 2193)
                for source in ("canonical_model_stack", "native_creative_reverse_translation")
                for suit in suits]}


class ArmorPacketEvidenceTests(unittest.TestCase):
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
        for key in ("native_independent_slot_packets", "canonical_and_alias_definition_cases", "wire_roundtrips"):
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


if __name__ == "__main__":
    unittest.main()
