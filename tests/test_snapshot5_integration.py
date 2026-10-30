"""The save gate derives an exact helper migration from NBT, never receipt claims."""
import copy
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import verify_snapshot5_integration as gate


class HelperMigrationTests(unittest.TestCase):
    def fixture(self):
        identity = uuid.UUID("34e67df0-b40c-491d-a6ec-5d706e2cb598")
        native_uuid = list(struct.unpack(">iiii", identity.bytes))
        owner = "9a86ef9e-6060-4ca1-88dc-dfc8bd32113c"
        entity = {
            "id": "minecraft:iron_golem", "UUID": native_uuid,
            "Tags": ["infinity_agent", "infinity_owner_" + owner, "infinity_name_buddy",
                     "infinity_mode_follow", "infinity_profile_ultimate_finals"],
            "PlayerCreated": 1, "PersistenceRequired": 1, "Health": 73.0,
            "Pos": [12.5, 64.0, -9.5], "Motion": [0.0, 0.0, 0.0],
            "CustomName": {"text": "Owner's saved helper"}, "Invulnerable": 0,
            "equipment": {"mainhand": {"id": "convergence:spear", "count": 1,
                          "components": {"minecraft:custom_name": "Saved named spear"}}},
            "InfinityAdminStats": {"minecraft:max_absorption": {"original": 0.0, "expanded": 1}},
            "attributes": [
                {"id": "minecraft:max_health", "base": 100.0},
                {"id": "minecraft:attack_damage", "base": 15.0,
                 "modifiers": [{"id": "convergence:fixture", "amount": 2.0, "operation": "add_value"}]},
                {"id": "minecraft:armor", "base": 0.0},
                {"id": "minecraft:armor_toughness", "base": 0.0},
                {"id": "minecraft:knockback_resistance", "base": 1.0},
                {"id": "minecraft:movement_speed", "base": 0.33},
            ],
        }
        registration = {"format": 2, "agents": {str(identity): {
            "owner": owner, "name": "buddy", "mode": "FOLLOW", "profile": "ULTIMATE_FINALS",
            "dimension": "minecraft:overworld", "x": 12.5, "y": 64.0, "z": -9.5}}}
        return entity, registration

    @staticmethod
    def row(entity, name):
        return next(row for row in entity["attributes"] if row["id"] == "minecraft:" + name)

    def expected(self, original):
        # Explicit expected fixture values independent of the verifier's defaults.
        changed = copy.deepcopy(original)
        if gate.HELPER_POWER_MARKER not in original.get("InfinityAdminStats", {}):
            for name, old, new in (("max_health", 100.0, 1000.0), ("attack_damage", 15.0, 100.0),
                                   ("armor", 0.0, 30.0), ("armor_toughness", 0.0, 20.0),
                                   ("knockback_resistance", 1.0, 1.0)):
                row = self.row(changed, name)
                if "minecraft:" + name not in original.get("InfinityAdminStats", {}) and struct.pack(">d", row["base"]) == struct.pack(">d", old):
                    row["base"] = new
            changed.setdefault("InfinityAdminStats", {})[gate.HELPER_POWER_MARKER] = 1
        return changed

    def verify(self, original, converted, registration, version="2.13.0-explore.51", dimension="minecraft:overworld"):
        return gate.verify_saved_helper(original, converted, dimension, registration, version)

    def test_exact_five_default_policy_preserves_damage_equipment_modifiers_and_input(self):
        original, registration = self.fixture()
        saved = copy.deepcopy(original)
        converted = self.expected(original)
        converted["attributes"].reverse()  # Native list ordering is not an attribute change.
        converted["Pos"][0] += 0.25
        converted["Motion"] = [0.0, -0.1, 0.0]
        summary = self.verify(original, converted, registration)
        self.assertEqual(summary, {"migration_applied": True, "existing_marker_preserved": False,
            "changed_attribute_ids": ["minecraft:armor", "minecraft:armor_toughness", "minecraft:attack_damage", "minecraft:max_health"],
            "materialized_attribute_ids": [], "marker_added": True})
        self.assertEqual(original, saved)
        self.assertEqual(converted["Health"], 73.0)
        encoded = json.dumps(summary)
        for secret in (str(uuid.UUID(bytes=struct.pack(">iiii", *original["UUID"]))), "buddy", "Saved named spear", "12.5", "73.0"):
            self.assertNotIn(secret, encoded)

    def test_evidence_reader_rejects_duplicate_or_nonstandard_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'evidence.json'
            for text in ('{"format":3,"format":3}', '{format:3}', '{/*comment*/"format":3}', '{"value":NaN}', '{"value":Infinity}'):
                path.write_text(text)
                with self.subTest(text=text), self.assertRaises(ValueError):
                    gate.read_json(path)
                self.assertEqual(path.read_text(), text)

    def test_provenance_roster_acceptance_and_invalid_records_fail_closed(self):
        original, old = self.fixture()
        converted = self.expected(original)
        newer = copy.deepcopy(old)
        removed = str(uuid.uuid4())
        newer.update(format=3, dismissed=[removed], guardedRemovals=[removed], pendingCreations=[])
        self.assertEqual(self.verify(original, converted, newer), self.verify(original, converted, old))
        bad = [dict(newer, guardedRemovals=None), dict(newer, guardedRemovals=[removed, removed]),
               dict(newer, guardedRemovals=[str(uuid.uuid4())]), dict(newer, dismissed=[]),
               dict(newer, format=2), dict(newer, pendingCreations=None),
               dict(newer, pendingCreations=[str(uuid.uuid4())]),
               {key: value for key, value in newer.items() if key != 'guardedRemovals'}]
        for record in bad:
            with self.subTest(record=record), self.assertRaises(ValueError):
                self.verify(original, converted, record)
        noncanonical = copy.deepcopy(newer)
        identity, agent = next(iter(noncanonical['agents'].items()))
        noncanonical['agents'] = {identity.upper(): agent}
        noncanonical['dismissed'] = [identity]; noncanonical['guardedRemovals'] = [identity]
        with self.assertRaisesRegex(ValueError, 'noncanonical registered helper identity'):
            self.verify(original, converted, noncanonical)
        noncanonical['agents'] = {'1-1-1-1-1': agent}
        with self.assertRaisesRegex(ValueError, 'noncanonical registered helper identity'):
            self.verify(original, converted, noncanonical)

    def test_saved_override_even_at_vanilla_base_is_never_upgraded(self):
        original, registration = self.fixture()
        original["InfinityAdminStats"]["minecraft:attack_damage"] = {"original": 15.0, "expanded": 1}
        self.row(original, "armor")["base"] = 9000.0
        converted = self.expected(original)
        summary = self.verify(original, converted, registration)
        self.assertEqual(self.row(converted, "attack_damage")["base"], 15.0)
        self.assertEqual(self.row(converted, "armor")["base"], 9000.0)
        self.assertNotIn("minecraft:attack_damage", summary["changed_attribute_ids"])
        self.assertNotIn("minecraft:armor", summary["changed_attribute_ids"])
        self.row(converted, "attack_damage")["base"] = 100.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_new51_registered_helper_must_receive_exact_marker_and_defaults(self):
        original, registration = self.fixture()
        for converted in (copy.deepcopy(original), self.expected(original)):
            if gate.HELPER_POWER_MARKER in converted["InfinityAdminStats"]:
                converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 2
            with self.subTest(marker=converted["InfinityAdminStats"].get(gate.HELPER_POWER_MARKER)):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_existing_or_unknown_marker_preserves_every_value_without_reapplying(self):
        for marker in (0, 1, 2, "future", {"unrecognized": 1}):
            with self.subTest(marker=marker):
                original, registration = self.fixture()
                original["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = marker
                summary = self.verify(original, copy.deepcopy(original), registration)
                self.assertFalse(summary["migration_applied"])
                self.assertTrue(summary["existing_marker_preserved"])
                self.assertEqual(summary["changed_attribute_ids"], [])
                converted = copy.deepcopy(original)
                self.row(converted, "attack_damage")["base"] = 100.0
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)
                converted = copy.deepcopy(original)
                converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 99
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_existing_damage_cannot_be_healed_by_declared_migration(self):
        original, registration = self.fixture()
        for health in (74.0, 1000.0, 0.0, float("nan")):
            converted = self.expected(original)
            converted["Health"] = health
            with self.subTest(health=health):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_equipment_name_identity_flags_and_other_native_fields_are_strict(self):
        for field, value in (("equipment", {}), ("CustomName", {"text": "Replaced"}),
                             ("Invulnerable", 1), ("PlayerCreated", 0), ("new_field", 1)):
            original, registration = self.fixture()
            converted = self.expected(original)
            converted[field] = value
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)
        original, registration = self.fixture()
        converted = self.expected(original)
        converted["UUID"][0] += 1
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_custom_attribute_modifiers_and_undo_history_cannot_change(self):
        original, registration = self.fixture()
        for alteration in ("custom_base", "modifier", "undo", "drop", "extra", "duplicate"):
            converted = self.expected(original)
            if alteration == "custom_base":
                self.row(converted, "movement_speed")["base"] = 0.5
            elif alteration == "modifier":
                self.row(converted, "attack_damage")["modifiers"][0]["amount"] = 3.0
            elif alteration == "undo":
                converted["InfinityAdminStats"]["minecraft:max_absorption"]["original"] = 5.0
            elif alteration == "drop":
                converted["attributes"].pop()
            elif alteration == "extra":
                converted["attributes"].append({"id": "minecraft:luck", "base": 1.0})
            else:
                converted["attributes"].append(copy.deepcopy(converted["attributes"][0]))
            with self.subTest(alteration=alteration):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_unregistered_helper_and_wrong_owner_or_profile_tags_are_rejected(self):
        for alteration in ("unregistered", "owner", "name", "mode", "profile", "duplicate_owner", "not_helper"):
            original, registration = self.fixture()
            converted = self.expected(original)
            if alteration == "unregistered":
                registration["agents"].clear()
            elif alteration == "not_helper":
                original["Tags"].remove("infinity_agent")
            elif alteration == "duplicate_owner":
                original["Tags"].append("infinity_owner_00000000-0000-0000-0000-000000000000")
            else:
                prefix = "infinity_" + alteration + "_"
                original["Tags"] = [prefix + "wrong" if tag.startswith(prefix) else tag for tag in original["Tags"]]
            with self.subTest(alteration=alteration):
                with self.assertRaises(ValueError):
                    self.verify(original, converted, registration)

    def test_actual_native_level_and_valid_registration_are_required(self):
        original, registration = self.fixture()
        with self.assertRaises(ValueError):
            self.verify(original, self.expected(original), registration, dimension="minecraft:the_nether")
        for field, value in (("owner", "not-a-uuid"), ("mode", "UNKNOWN"), ("profile", "UNKNOWN"),
                             ("name", "invalid name"), ("x", float("inf")), ("y", 3000.0)):
            altered = copy.deepcopy(registration)
            next(iter(altered["agents"].values()))[field] = value
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    self.verify(original, self.expected(original), altered)

    def test_dimension_comes_from_native_region_layout_including_legacy_and_nested_paths(self):
        for path, dimension in (("entities/r.0.0.mca", "minecraft:overworld"),
                                ("DIM-1/entities/r.0.0.mca", "minecraft:the_nether"),
                                ("DIM1/entities/r.0.0.mca", "minecraft:the_end"),
                                ("dimensions/convergence/survival/entities/r.0.0.mca", "convergence:survival"),
                                ("dimensions/example/nested/world/entities/r.0.0.mca", "example:nested/world")):
            self.assertEqual(gate.native_entity_dimension(path), dimension)
        for path in ("unknown/entities/r.0.0.mca", "dimensions/Bad/World/entities/r.0.0.mca"):
            with self.assertRaises(ValueError):
                gate.native_entity_dimension(path)

    def test_historical_pre51_requires_unchanged_entity_and_rejects_new_migration(self):
        original, registration = self.fixture()
        summary = self.verify(original, copy.deepcopy(original), registration, version="2.13.0-explore.50")
        self.assertFalse(summary["migration_applied"])
        self.assertFalse(summary["marker_added"])
        with self.assertRaises(ValueError):
            self.verify(original, self.expected(original), registration, version="2.13.0-explore.50")

    def test_signed_zero_is_not_an_untouched_java_double_default(self):
        original, registration = self.fixture()
        self.row(original, "armor")["base"] = -0.0
        converted = self.expected(original)
        summary = self.verify(original, converted, registration)
        self.assertNotIn("minecraft:armor", summary["changed_attribute_ids"])
        self.assertEqual(struct.pack(">d", self.row(converted, "armor")["base"]), struct.pack(">d", -0.0))
        self.row(converted, "armor")["base"] = 0.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_only_five_native_supported_missing_attributes_materialize_and_other_data_stays(self):
        original, registration = self.fixture()
        original["attributes"] = [self.row(original, "movement_speed")]
        original["InfinityAdminStats"]["future_private_metadata"] = {"saved": [1, 2, 3]}
        converted = copy.deepcopy(original)
        for name, value in (("max_health", 1000.0), ("attack_damage", 100.0), ("armor", 30.0),
                            ("armor_toughness", 20.0), ("knockback_resistance", 1.0)):
            converted["attributes"].append({"id": "minecraft:" + name, "base": value})
        converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 1
        summary = self.verify(original, converted, registration)
        self.assertEqual(summary["materialized_attribute_ids"], sorted(gate.HELPER_POWER_DEFAULTS))
        incomplete = copy.deepcopy(converted)
        incomplete["attributes"] = [row for row in incomplete["attributes"] if row["id"] != "minecraft:armor"]
        with self.assertRaises(ValueError):
            self.verify(original, incomplete, registration)
        converted["InfinityAdminStats"].pop("future_private_metadata")
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_missing_native_attribute_with_admin_history_materializes_without_upgrading(self):
        original, registration = self.fixture()
        original["attributes"] = [row for row in original["attributes"] if row["id"] != "minecraft:armor"]
        original["InfinityAdminStats"]["minecraft:armor"] = {"original": 0.0, "expanded": 1}
        converted = copy.deepcopy(original)
        self.row(converted, "max_health")["base"] = 1000.0
        self.row(converted, "attack_damage")["base"] = 100.0
        self.row(converted, "armor_toughness")["base"] = 20.0
        converted["attributes"].append({"id": "minecraft:armor", "base": 0.0})
        converted["InfinityAdminStats"][gate.HELPER_POWER_MARKER] = 1
        self.verify(original, converted, registration)
        self.row(converted, "armor")["base"] = 30.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)

    def test_nonfinite_bases_duplicate_uuid_parts_and_large_displacement_are_rejected(self):
        original, registration = self.fixture()
        for invalid in (float("inf"), float("nan"), True):
            changed = copy.deepcopy(original)
            self.row(changed, "attack_damage")["base"] = invalid
            with self.assertRaises(ValueError):
                self.verify(changed, copy.deepcopy(changed), registration)
        for raw in ([1, 2, 3], [True, 2, 3, 4], [2 ** 31, 2, 3, 4]):
            changed = copy.deepcopy(original)
            changed["UUID"] = raw
            with self.assertRaises(ValueError):
                self.verify(changed, copy.deepcopy(changed), registration)
        converted = self.expected(original)
        converted["Pos"][0] += 2.0
        with self.assertRaises(ValueError):
            self.verify(original, converted, registration)


if __name__ == "__main__":
    unittest.main()
