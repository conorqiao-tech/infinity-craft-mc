import copy
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import combined_wing_resources as overlay


class CombinedWingPackageTests(unittest.TestCase):
    def setUp(self):
        resources = ROOT / "bedrock/resource_pack"
        self.files = {p.relative_to(resources).as_posix(): p.read_bytes()
                      for p in resources.rglob("*") if p.is_file()}
        # This previously rejected global override remains source-only.
        self.files.pop("attachables/elytra.json")

    def alter(self, name, mutation):
        value = json.loads(self.files[name])
        mutation(value)
        self.files[name] = json.dumps(value).encode()

    def test_valid_conditional_overlay_preserves_native_defaults(self):
        receipt = overlay.validate(self.files)
        self.assertTrue(receipt["ordinary_elytra_uses_native_renderer"])
        self.assertTrue(receipt["accepted_infinity_shell_unchanged"])
        self.assertEqual(receipt["infinity_property"], "infinityhub:infinity_winged_chest")
        self.assertEqual(receipt["equipment_slot_query_context"], "owning_entity")
        self.assertFalse(receipt["client_rendering_verified"])

    def test_rejects_equipment_lookup_on_attachable_instead_of_owning_entity(self):
        original = copy.copy(self.files)
        for index in (0, 1):
            with self.subTest(statement=index):
                self.files = copy.copy(original)
                def current_attachable(value):
                    scripts = value["minecraft:attachable"]["description"]["scripts"]
                    scripts["pre_animation"][index] = scripts["pre_animation"][index].replace(
                        "(context.owning_entity->query.item_slot_to_bone_name(context.item_slot))",
                        "query.item_slot_to_bone_name(context.item_slot)")
                self.alter(overlay.ATTACHABLE, current_attachable)
                with self.assertRaises(ValueError):
                    overlay.validate(self.files)

    def test_rejects_unguarded_or_wrong_owner_infinity_selector(self):
        original = copy.copy(self.files)
        for replacement in (
                "variable.infinityhub_infinity_winged_chest = query.property('infinityhub:infinity_winged_chest');",
                "variable.infinityhub_infinity_winged_chest = 1.0;"):
            with self.subTest(replacement=replacement):
                self.files = copy.copy(original)
                self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"]["scripts"][
                    "pre_animation"].__setitem__(1, replacement))
                with self.assertRaises(ValueError):
                    overlay.validate(self.files)

    def test_rejects_infinity_marker_selecting_foreign_armor_geometry_or_texture(self):
        original = copy.copy(self.files)
        for aliases, key in (("geometry", "infinity_armor"), ("textures", "infinity_armor")):
            with self.subTest(aliases=aliases):
                self.files = copy.copy(original)
                self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"][aliases].update(
                    {key: "geometry.convergence.cuirass_original" if aliases == "geometry"
                     else "textures/convergence/models/armor/armor42/convergence"}))
                with self.assertRaises(ValueError):
                    overlay.validate(self.files)

    def test_rejects_reversed_or_mismatched_set_arrays(self):
        original = copy.copy(self.files)
        for kind, array in (("geometries", "Array.combined_chest_geometry"),
                            ("textures", "Array.combined_chest_texture")):
            with self.subTest(kind=kind):
                self.files = copy.copy(original)
                self.alter(overlay.RENDERERS, lambda value: value["render_controllers"][
                    "controller.render.convergence.combined_armor"]["arrays"][kind][array].reverse())
                with self.assertRaises(ValueError):
                    overlay.validate(self.files)

    def test_rejects_infinity_texture_selector_that_ignores_set_marker(self):
        self.alter(overlay.RENDERERS, lambda value: value["render_controllers"][
            "controller.render.convergence.combined_armor"].update(textures=["Texture.convergence_armor", "Texture.enchanted"]))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_visually_unguarded_combined_body_even_if_flag_name_remains(self):
        self.alter(overlay.RENDERERS, lambda value: value["render_controllers"][
            "controller.render.convergence.combined_armor"]["part_visibility"].__setitem__(0,
                {"body": overlay.FLAG + " || 1.0"}))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_wing_renderer_rebound_to_a_duplicate_armor_shell(self):
        self.alter(overlay.RENDERERS, lambda value: value["render_controllers"][
            "controller.render.convergence.combined_wings"].update(geometry="Geometry.infinity_armor"))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_ordinary_elytra_texture_replacement(self):
        self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"]["textures"].update(
            default="textures/convergence/models/armor/armor_wings"))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_unconditional_armor_renderer(self):
        self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"].update(
            render_controllers=["controller.render.armor", "controller.render.convergence.combined_armor"]))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_stale_one_time_marker_instead_of_per_frame_guard(self):
        self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"]["scripts"].pop("pre_animation"))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_body_animation_that_would_change_stable_armor_fit(self):
        def body_channel(value):
            animation = next(iter(value["animations"].values()))
            animation["bones"]["body"] = {"scale": 1.067}
        self.alter(overlay.ANIMATIONS, body_channel)
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_rebinding_marked_controller_to_native_body_scaling(self):
        self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"]["animations"].update(
            convergence_controller="controller.animation.elytra.default"))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_rebinding_state_alias_to_native_gliding_animation(self):
        self.alter(overlay.ATTACHABLE, lambda value: value["minecraft:attachable"]["description"]["animations"].update(
            convergence_gliding="animation.elytra.gliding"))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_native_animation_alias_hidden_in_owned_state_graph(self):
        self.alter(overlay.CONTROLLER, lambda value: value["animation_controllers"][
            "controller.animation.convergence.combined_wings"]["states"]["folded"].update(animations=["default"]))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_missing_controller_definition_behind_valid_alias(self):
        self.alter(overlay.CONTROLLER, lambda value: value["animation_controllers"].clear())
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_unresolved_state_transition(self):
        self.alter(overlay.CONTROLLER, lambda value: value["animation_controllers"][
            "controller.animation.convergence.combined_wings"]["states"]["folded"].update(
                transitions=[{"missing_state": "query.is_gliding"}]))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_non_rotation_channels_in_owned_wing_animation(self):
        self.alter(overlay.ANIMATIONS, lambda value: next(iter(value["animations"].values()))["bones"][
            "convergence_left_wing"].update(scale=1.067))
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_duplicate_native_elytra_attachables(self):
        self.files["attachables/second_native_elytra.json"] = self.files[overlay.ATTACHABLE]
        with self.assertRaises(ValueError):
            overlay.validate(self.files)

    def test_rejects_changes_to_accepted_armor_and_original_art(self):
        original = copy.copy(self.files)
        for name in overlay.ACCEPTED:
            with self.subTest(name=name):
                self.files = copy.copy(original)
                self.files[name] += b"changed"
                with self.assertRaises(ValueError):
                    overlay.validate(self.files)


if __name__ == "__main__":
    unittest.main()
