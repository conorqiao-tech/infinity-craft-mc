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
        self.assertFalse(receipt["client_rendering_verified"])

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
