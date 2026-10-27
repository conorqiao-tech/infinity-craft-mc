"""R4 selector contract and mutation checks; these do not execute Bedrock Molang.

The positive torso value is inferred from the official equipment-filter domain,
not established as this client's attachable context. The property matrix models
the bridge's Boolean schema and refuses invalid synthetic inputs; it does not
claim the engine rejects or coerces those values in the same way.
"""
import copy
import hashlib
import itertools
import json
from pathlib import Path
import sys
import unittest

from tests.test_combined_convergence_elytra import marker_value


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "bedrock/resource_pack"
sys.path.insert(0, str(ROOT / "tools"))
import combined_wing_resources as overlay


FLAGS = ("variable.infinityhub_winged_chest", "variable.infinityhub_infinity_winged_chest")
PROPERTIES = ("infinityhub:winged_chest", "infinityhub:infinity_winged_chest")
MISSING = object()
VALUES = (MISSING, False, True, None, 0, 1, "true", "false", [], {})
SLOTS = ("torso", "main_hand", "off_hand", "hand", "head", "leg", "legs", "feet",
         "body", "chest", "slot.armor.chest", "Torso", "torso ", "", "unknown", None, 0, 1)


class WingSlotSelectorTests(unittest.TestCase):
    def setUp(self):
        # Source resources only. Prepared packs and live files are outside this
        # test; the source-only historical global override is deliberately absent.
        self.files = {p.relative_to(RESOURCES).as_posix(): p.read_bytes()
                      for p in RESOURCES.rglob("*") if p.is_file()}
        self.files.pop("attachables/elytra.json")
        self.attachable = json.loads(self.files[overlay.ATTACHABLE])
        self.description = self.attachable["minecraft:attachable"]["description"]
        self.statements = self.description["scripts"]["pre_animation"]

    def altered(self, index, replacement):
        value = copy.deepcopy(self.attachable)
        value["minecraft:attachable"]["description"]["scripts"]["pre_animation"][index] = replacement
        return {**self.files, overlay.ATTACHABLE: json.dumps(value).encode()}

    def test_source_delta_preserves_all_other_native_and_custom_paths(self):
        # Canonical structural hash of the accepted r3 attachable with ONLY its
        # two pre_animation statements removed. This covers all native aliases,
        # animation/rendering branches and the stable accepted body/wing paths.
        preserved = copy.deepcopy(self.attachable)
        preserved["minecraft:attachable"]["description"]["scripts"].pop("pre_animation")
        canonical = json.dumps(preserved, sort_keys=True, separators=(",", ":")).encode()
        self.assertEqual(hashlib.sha256(canonical).hexdigest(),
                         "ac2422f3d77516027be3104db3ddf7c61834d0595d03babb9d58a2ee8920f54a")
        self.assertEqual(self.statements, [
            flag + " = context.item_slot == 'torso' ? "
            "((context.owning_entity->query.has_property('" + prop + "')) ? "
            "(context.owning_entity->query.property('" + prop + "')) : 0.0) : 0.0;"
            for flag, prop in zip(FLAGS, PROPERTIES)
        ])

    def test_context_and_two_properties_are_independent_boolean_contract_inputs(self):
        for slot, owner_exists, primary, infinity in itertools.product(SLOTS, (False, True), VALUES, VALUES):
            properties = {name: value for name, value in zip(PROPERTIES, (primary, infinity))
                          if value is not MISSING}
            with self.subTest(slot=slot, owner=owner_exists, primary=primary, infinity=infinity):
                actual = tuple(marker_value(statement, properties, slot, owner_exists, variable=flag)
                               for statement, flag in zip(self.statements, FLAGS))
                valid_context = owner_exists and type(slot) is str and slot == "torso"
                # The expected table comes from the explicit safety contract,
                # independently of a slot-to-bone map or Python truthiness.
                self.assertEqual(actual, (valid_context and primary is True,
                                          valid_context and infinity is True))

    def test_ordinary_and_held_items_keep_native_branch_even_with_infinity_owner(self):
        scenarios = (("torso", {}, False), ("torso", {PROPERTIES[0]: False}, False),
                     ("torso", dict.fromkeys(PROPERTIES, True), True),
                     ("main_hand", dict.fromkeys(PROPERTIES, True), False),
                     ("off_hand", dict.fromkeys(PROPERTIES, True), False),
                     (None, dict.fromkeys(PROPERTIES, True), False))
        for slot, properties, combined in scenarios:
            with self.subTest(slot=slot, properties=properties):
                selected = marker_value(self.statements[0], properties, slot)
                self.assertEqual(selected, combined)
                # These are authored branch selectors; resolving them here does
                # not assert native animation or renderer execution on a device.
                for entries, native, marked in (
                        (self.description["scripts"]["animate"], "default_controller", ["convergence_controller"]),
                        (self.description["render_controllers"], "controller.render.armor",
                         ["controller.render.convergence.combined_armor", "controller.render.convergence.combined_wings"])):
                    resolved = []
                    for entry in entries:
                        name, expression = next(iter(entry.items()))
                        self.assertIn(expression, (FLAGS[0], "!" + FLAGS[0]))
                        enabled = selected if expression == FLAGS[0] else not selected
                        if enabled:
                            resolved.append(name)
                    self.assertEqual(resolved, marked if combined else [native])

    def test_each_frame_model_does_not_keep_a_stale_true_value_after_context_changes(self):
        for index, (statement, flag, prop) in enumerate(zip(self.statements, FLAGS, PROPERTIES)):
            sequence = (("torso", {prop: True}), ("main_hand", {prop: True}),
                        ("off_hand", {prop: True}), (None, {prop: True}),
                        ("torso", {}), ("torso", {prop: False}), ("torso", {prop: True}))
            with self.subTest(statement=index):
                self.assertEqual([marker_value(statement, properties, slot, variable=flag)
                                  for slot, properties in sequence], [True, False, False, False, False, False, True])
        missing_refresh = copy.deepcopy(self.attachable)
        missing_refresh["minecraft:attachable"]["description"]["scripts"].pop("pre_animation")
        with self.assertRaises(ValueError):
            overlay.validate({**self.files, overlay.ATTACHABLE: json.dumps(missing_refresh).encode()})

    def test_validator_rejects_negative_hand_filters_or_slot_alternatives(self):
        predicates = ("1.0", "context.item_slot != 'main_hand'",
                      "context.item_slot != 'main_hand' && context.item_slot != 'off_hand'",
                      "context.item_slot == 'torso' || context.item_slot == 'body'",
                      "context.item_slot == 'torso' || 1.0", "context.item_slot == 'chest'",
                      "context.item_slot == 'body'", "context.item_slot == 'Torso'",
                      "(context.owning_entity->query.item_slot_to_bone_name(context.item_slot)) == 'body'",
                      "query.item_slot_to_bone_name(context.item_slot) == 'body'")
        for index, statement in enumerate(self.statements):
            for predicate in predicates:
                with self.subTest(statement=index, predicate=predicate):
                    changed = statement.replace("context.item_slot == 'torso'", predicate)
                    with self.assertRaises(ValueError):
                        overlay.validate(self.altered(index, changed))

    def test_validator_rejects_property_only_missing_or_crossed_owner_guards(self):
        for index, (statement, flag, prop) in enumerate(zip(self.statements, FLAGS, PROPERTIES)):
            prefix = flag + " = context.item_slot == 'torso' ? "
            mutations = (
                flag + " = " + statement.removeprefix(prefix).removesuffix(" : 0.0;") + ";",
                statement.replace("query.has_property", "query.property"),
                statement.replace("context.owning_entity->", ""),
                statement.replace("query.has_property('" + prop + "')", "query.has_property('" + PROPERTIES[1-index] + "')"),
                statement.replace("query.property('" + prop + "')", "query.property('" + PROPERTIES[1-index] + "')"),
                statement.replace(flag + " = ", FLAGS[1-index] + " = "),
                flag + " = context.item_slot == 'torso' ? 1.0 : 0.0;",
                statement.replace(" : 0.0;", " : 1.0;"),
            )
            for changed in mutations:
                with self.subTest(statement=index, replacement=changed):
                    with self.assertRaises(ValueError):
                        overlay.validate(self.altered(index, changed))

    def test_validator_rejects_extra_or_reordered_per_frame_statements(self):
        for statements in (list(reversed(self.statements)), self.statements + ["variable.infinityhub_winged_chest = 1.0;"],
                           self.statements[:1], [self.statements[0], self.statements[0]]):
            with self.subTest(statements=statements):
                altered = copy.deepcopy(self.attachable)
                altered["minecraft:attachable"]["description"]["scripts"]["pre_animation"] = statements
                with self.assertRaises(ValueError):
                    overlay.validate({**self.files, overlay.ATTACHABLE: json.dumps(altered).encode()})

    def test_receipt_preserves_inference_and_device_evidence_boundary(self):
        receipt = overlay.validate(self.files)
        self.assertEqual(receipt["equipment_slot_query_context"], "attachable.context.item_slot")
        self.assertEqual(receipt["equipment_slot_value"], "torso")
        self.assertTrue(receipt["equipment_slot_value_inferred"])
        self.assertFalse(receipt["equipment_slot_value_device_verified"])
        self.assertFalse(receipt["client_rendering_verified"])
        self.assertEqual(receipt["equipment_slot_value_source"],
                         "https://learn.microsoft.com/en-us/minecraft/creator/reference/content/"
                         "entityreference/examples/filters/has_equipment?view=minecraft-bedrock-stable")


if __name__ == "__main__":
    unittest.main()
