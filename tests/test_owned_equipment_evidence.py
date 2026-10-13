"""Packaging must reject partial or misleading original held-equipment proofs."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("owned_equipment_gate", ROOT / "tools/verify_owned_equipment.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class OwnedEquipmentEvidenceTests(unittest.TestCase):
    def setUp(self):
        # Use a receipt from the real pinned Geyser process, never an invented
        # successful fixture that could conceal a missing native check.
        self.ready = json.loads((ROOT / "research/integration/creative-bridge/ready.json").read_text())

    def test_real_native_receipt_is_required(self):
        self.assertEqual(GATE.verify_owned_equipment(self.ready),
                         {"items": 4, "protocols": 3, "native_control_cases": 57, "wire_packets": 69})
        for field in ("owned_equipment", "owned_equipment_proof"):
            candidate = copy.deepcopy(self.ready)
            del candidate[field]
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "Missing owned equipment"):
                GATE.verify_owned_equipment(candidate)

    def test_incomplete_or_inflated_native_cases_are_rejected(self):
        for value in (0, 68, 70, True, "69"):
            candidate = copy.deepcopy(self.ready)
            candidate["owned_equipment_proof"]["bedrock_wire_packets"] = value
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "Incomplete owned equipment"):
                GATE.verify_owned_equipment(candidate)
        for protocols in ([2193], [1001.0, 2168.0, 2193.0], ["1001", "2168", "2193"]):
            candidate = copy.deepcopy(self.ready)
            candidate["owned_equipment"]["protocols"] = protocols
            with self.subTest(protocols=protocols), self.assertRaisesRegex(ValueError, "unexpected protocol"):
                GATE.verify_owned_equipment(candidate)

    def test_ordinary_item_or_ammunition_regressions_cannot_package(self):
        for section, field in (
            ("owned_equipment", "ordinary_vanilla_components_unchanged"),
            ("owned_equipment", "complete_unowned_wire_components_match_native_baseline"),
            ("owned_equipment_proof", "complete_unowned_wire_components_match_native_baseline"),
            ("owned_equipment_proof", "complete_cached_components_and_network_ids_preserved"),
            ("owned_equipment_proof", "native_arrow_identity_preserved"),
            ("owned_equipment_proof", "charged_projectile_retention"),
            ("owned_equipment_proof", "creative_arrow_ammunition_component"),
            ("owned_equipment_proof", "crossbow_offhand_rocket_ammunition_component"),
        ):
            candidate = copy.deepcopy(self.ready)
            candidate[section][field] = False
            with self.subTest(field=field), self.assertRaises(ValueError):
                GATE.verify_owned_equipment(candidate)

    def test_transport_cannot_claim_an_authenticated_client_or_java_mechanics(self):
        for section, field in (
            ("owned_equipment", "device_rendering_verified"),
            ("owned_equipment_proof", "java_ammo_rules_proven_here"),
            ("owned_equipment_proof", "device_input_or_rendering_proven_here"),
        ):
            candidate = copy.deepcopy(self.ready)
            candidate[section][field] = True
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "cannot claim"):
                GATE.verify_owned_equipment(candidate)


if __name__ == "__main__":
    unittest.main()
