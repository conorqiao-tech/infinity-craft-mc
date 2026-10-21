"""Catalogue-coverage arithmetic, not invented native success evidence.

These fixtures contain only schema/count tuples. They cannot pass the package
gate, which still requires the exact genuine packet, native and runtime proofs.
"""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("creative_coverage_gate", ROOT / "tools/verify_owned_equipment.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


def catalogue(unstackable=32, stackable=14):
    return {"format_version": 1, "minecraft": "26.4-snapshot-5", "items": [
        *({"path": "armor_" + str(i), "max_stack_size": 1} for i in range(unstackable)),
        *({"path": "material_" + str(i), "max_stack_size": 64} for i in range(stackable)),
    ]}


def count_capture(canonical):
    # Deliberately no payloads, JVM result, proof flags, source or artifact hashes.
    # This fixture exercises completeness only and must never stand in for a run.
    samples = []
    for protocol in (1001, 2168, 2193):
        for item in canonical["items"]:
            for count in sorted({1, item["max_stack_size"]}):
                samples.append({"path": item["path"], "count": count, "bedrock_protocol": protocol})
    return {"items": len(canonical["items"]),
            "protocols": {str(protocol): len(canonical["items"]) for protocol in (1001, 2168, 2193)},
            "proof": {"encoded_hotbar_packets": samples, "actual_creative_hotbar_selections": len(samples)}}


class CreativeCaptureCountTests(unittest.TestCase):
    def test_existing_genuine_capture_keeps_its_exact_requirements(self):
        canonical = json.loads((ROOT / "research/integration/crossplay-export/infinity-catalogue.json").read_text())
        captured = json.loads((ROOT / "research/integration/creative-bridge/ready.json").read_text())
        counts = GATE.creative_capture_counts(canonical, captured)
        expected_pairs = sum(1 if item["max_stack_size"] == 1 else 2 for item in canonical["items"])
        self.assertEqual(counts["canonical_identities"], len(canonical["items"]))
        self.assertEqual(counts["disk_cases"], expected_pairs)
        self.assertEqual(counts["hotbar_cases"], expected_pairs * 3)
        self.assertEqual(counts["framed_inbound_packets"], expected_pairs * 4 + 5)

    def test_baseline_coverage_arithmetic_remains_exact(self):
        canonical = catalogue()
        self.assertEqual(GATE.creative_capture_counts(canonical, count_capture(canonical)), {
            "hotbar_cases": 180, "distinct_item_count_cases": 60, "disk_cases": 60,
            "rejoin_cases": 60, "canonical_identities": 46, "native_save_batches": 2,
            "genuine_reconnects": 2, "variant_inputs": 5, "framed_inbound_packets": 245,
            "backpack_styles_preserved": 3})

    def test_one_extra_armor_case_requires_an_additional_partial_batch_and_reconnect(self):
        canonical = catalogue(unstackable=33)
        counts = GATE.creative_capture_counts(canonical, count_capture(canonical))
        self.assertEqual(counts["canonical_identities"], 47)
        self.assertEqual(counts["hotbar_cases"], 183)
        self.assertEqual(counts["disk_cases"], 61)
        self.assertEqual(counts["rejoin_cases"], 61)
        self.assertEqual(counts["native_save_batches"], 3)
        self.assertEqual(counts["genuine_reconnects"], 3)
        self.assertEqual(counts["framed_inbound_packets"], 249)

    def test_multiple_additional_full_and_partial_batches_are_required(self):
        for unstackable, distinct, batches in ((62, 90, 3), (64, 92, 4), (100, 128, 5)):
            with self.subTest(unstackable=unstackable):
                canonical = catalogue(unstackable=unstackable)
                counts = GATE.creative_capture_counts(canonical, count_capture(canonical))
                self.assertEqual(counts["distinct_item_count_cases"], distinct)
                self.assertEqual(counts["disk_cases"], distinct)
                self.assertEqual(counts["rejoin_cases"], distinct)
                self.assertEqual(counts["native_save_batches"], batches)
                self.assertEqual(counts["genuine_reconnects"], batches)
                self.assertEqual(counts["framed_inbound_packets"], distinct * 4 + 5)

    def test_upper_catalogue_bound_requires_every_stack_count_and_final_partial_batch(self):
        canonical = catalogue(unstackable=0, stackable=128)
        counts = GATE.creative_capture_counts(canonical, count_capture(canonical))
        self.assertEqual(counts["distinct_item_count_cases"], 256)
        self.assertEqual(counts["hotbar_cases"], 768)
        self.assertEqual(counts["native_save_batches"], 9)
        self.assertEqual(counts["genuine_reconnects"], 9)

    def test_omitting_new_armor_from_every_protocol_cannot_reuse_the_old_counts(self):
        canonical = catalogue(unstackable=33)
        captured = count_capture(canonical)
        captured["proof"]["encoded_hotbar_packets"] = [sample for sample in
            captured["proof"]["encoded_hotbar_packets"] if sample["path"] != "armor_32"]
        captured["proof"]["actual_creative_hotbar_selections"] = 180
        with self.assertRaisesRegex(ValueError, "omits canonical"):
            GATE.creative_capture_counts(canonical, captured)

    def test_omitting_only_one_protocol_or_max_stack_case_is_refused(self):
        canonical = catalogue()
        for missing in ({"path": "armor_0", "count": 1, "bedrock_protocol": 2193},
                        {"path": "material_0", "count": 64, "bedrock_protocol": 1001}):
            with self.subTest(missing=missing):
                captured = count_capture(canonical)
                captured["proof"]["encoded_hotbar_packets"].remove(missing)
                captured["proof"]["actual_creative_hotbar_selections"] -= 1
                with self.assertRaisesRegex(ValueError, "omits canonical"):
                    GATE.creative_capture_counts(canonical, captured)

    def test_duplicate_replacement_cannot_hide_omission_behind_the_same_length(self):
        canonical = catalogue()
        captured = count_capture(canonical)
        samples = captured["proof"]["encoded_hotbar_packets"]
        samples[-1] = copy.deepcopy(samples[0])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            GATE.creative_capture_counts(canonical, captured)

    def test_unknown_protocol_item_or_noncanonical_count_is_refused(self):
        canonical = catalogue()
        for key, value in (("path", "not_registered"), ("count", 2), ("bedrock_protocol", 9999)):
            with self.subTest(key=key):
                captured = count_capture(canonical)
                captured["proof"]["encoded_hotbar_packets"][0][key] = value
                with self.assertRaisesRegex(ValueError, "unexpected"):
                    GATE.creative_capture_counts(canonical, captured)

    def test_protocol_registry_count_must_match_the_full_catalogue(self):
        canonical = catalogue(unstackable=33)
        for field, value in (("items", 46), ("items", True), ("items", 47.0),
                             ("protocols", {"1001": 47, "2168": 47}),
                             ("protocols", {"1001": 47, "2168": 47, "2193": 46}),
                             ("protocols", {"1001": 47.0, "2168": 47, "2193": 47})):
            with self.subTest(field=field, value=value):
                captured = count_capture(canonical)
                captured[field] = value
                with self.assertRaisesRegex(ValueError, "registries"):
                    GATE.creative_capture_counts(canonical, captured)

    def test_counts_and_protocol_numbers_must_be_actual_integers(self):
        canonical = catalogue()
        for field, value in (("count", True), ("count", 1.0), ("count", "1"),
                             ("bedrock_protocol", 1001.0), ("bedrock_protocol", "1001")):
            with self.subTest(field=field, value=value):
                captured = count_capture(canonical)
                captured["proof"]["encoded_hotbar_packets"][0][field] = value
                with self.assertRaisesRegex(ValueError, "invalid protocol/item/count"):
                    GATE.creative_capture_counts(canonical, captured)

    def test_selection_counter_cannot_understate_or_inflate_complete_packets(self):
        canonical = catalogue()
        for value in (179, 181, True, 180.0, "180"):
            with self.subTest(value=value):
                captured = count_capture(canonical)
                captured["proof"]["actual_creative_hotbar_selections"] = value
                with self.assertRaisesRegex(ValueError, "selection count"):
                    GATE.creative_capture_counts(canonical, captured)

    def test_invalid_catalogue_cannot_define_its_own_smaller_requirements(self):
        for changed in (catalogue(unstackable=0, stackable=0), catalogue(unstackable=129, stackable=0)):
            with self.subTest(items=len(changed["items"])), self.assertRaises(ValueError):
                GATE.catalogue_item_count_pairs(changed)
        for key, value in (("max_stack_size", True), ("max_stack_size", 1.0),
                           ("max_stack_size", 0), ("max_stack_size", 65), ("path", "invalid:path")):
            with self.subTest(key=key, value=value):
                canonical = catalogue()
                canonical["items"][0][key] = value
                with self.assertRaises(ValueError):
                    GATE.catalogue_item_count_pairs(canonical)
        canonical = catalogue()
        canonical["items"][1] = copy.deepcopy(canonical["items"][0])
        with self.assertRaises(ValueError):
            GATE.catalogue_item_count_pairs(canonical)


if __name__ == "__main__":
    unittest.main()
