"""Source safety checks for the conditional native-elytra resource candidate.

These validate the authored resources and numeric wing clearance, not the iPad
engine's owner-property context, equipment timing, pixels or native glide input.
"""
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]
BED = ROOT / "bedrock/resource_pack"
MARKER = "infinityhub:winged_chest"
VARIABLE = "variable.infinityhub_winged_chest"
INFINITY_MARKER = "infinityhub:infinity_winged_chest"
INFINITY_VARIABLE = "variable.infinityhub_infinity_winged_chest"
WING_BONES = {"convergence_left_wing", "convergence_right_wing"}
NATIVE_ANIMATIONS = {"default_controller": "controller.animation.elytra.default",
                     **{state: "animation.elytra." + state for state in
                        ("default", "gliding", "sneaking", "sleeping", "swimming")}}


def read(relative):
    return json.loads((BED / relative).read_text())


def attachment():
    return read("attachables/combined_convergence_elytra.json")["minecraft:attachable"]["description"]


def marker_value(statement, properties, slot="torso", owner_exists=True, variable=VARIABLE):
    """Model the candidate's strict slot/Boolean-property input contract.

    The official equipment-filter domain includes torso; it does not establish
    this attachable's actual context.item_slot value. This parser is not a
    Molang interpreter. Invalid synthetic property types are refused because the
    native bridge schema is Boolean, not as a claim about engine coercion rules.
    Missing/unknown contexts refuse the custom pass rather than guessing a bone.
    """
    match = re.fullmatch(
        re.escape(variable) + r" = context\.item_slot == 'torso' \? "
        r"\(\(context\.owning_entity->query\.has_property\('([^']+)'\)\) \? "
        r"\(context\.owning_entity->query\.property\('([^']+)'\)\) : 0\.0\) : 0\.0;", statement)
    if match is None or match[1] != match[2]:
        raise ValueError("The marker must positively select torso and one matching, guarded owner property")
    if slot != "torso" or not owner_exists:
        return False
    value = properties.get(match[1])
    return type(value) is bool and value


def active(entries, marked):
    result = []
    for entry in entries:
        if len(entry) != 1:
            raise ValueError("Expected one conditional controller")
        name, expression = next(iter(entry.items()))
        if expression == VARIABLE:
            enabled = marked
        elif expression == "!" + VARIABLE:
            enabled = not marked
        else:
            raise ValueError("Unexpected marker gate")
        if enabled:
            result.append(name)
    return result


def vertices(cube):
    return itertools.product(*(tuple(origin + offset for offset in (0, size))
                               for origin, size in zip(cube["origin"], cube["size"])))


def rotate(vertex, pivot, angles, order):
    point = [a - b for a, b in zip(vertex, pivot)]
    for axis in order:
        angle = math.radians(angles[axis])
        cosine, sine = math.cos(angle), math.sin(angle)
        a, b = ((1, 2), (2, 0), (0, 1))[axis]
        point[a], point[b] = cosine * point[a] - sine * point[b], sine * point[a] + cosine * point[b]
    return tuple(a + b for a, b in zip(point, pivot))


class CombinedConvergenceElytraTests(unittest.TestCase):
    def test_slot_is_positive_attachable_context_and_properties_remain_on_owner(self):
        description = attachment()
        # The context string is an explicit candidate, not a claimed result of
        # executing Molang on an iPad. No entity-to-bone mapping is modeled.
        for statement, variable, marker in zip(description["scripts"]["pre_animation"],
                (VARIABLE, INFINITY_VARIABLE), (MARKER, INFINITY_MARKER)):
            with self.subTest(variable=variable):
                self.assertIn(" = context.item_slot == 'torso' ? ", statement)
                self.assertNotIn("item_slot_to_bone_name", statement)
                self.assertTrue(marker_value(statement, {marker: True}, variable=variable))
                for slot in ("chest", "body", "main_hand", "off_hand", None):
                    self.assertFalse(marker_value(statement, {marker: True}, slot, variable=variable))
                self.assertFalse(marker_value(statement, {marker: True}, owner_exists=False,
                    variable=variable))

    def test_unmarked_item_keeps_exact_native_resources_and_animation_aliases(self):
        # Pinned official source: Mojang/bedrock-samples commit
        # 46ba6ea985fb5a92d79a9419198f10dda14c199d, attachables/elytra.json.
        description = attachment()
        self.assertEqual(description["identifier"], "minecraft:elytra")
        self.assertEqual({key: description["materials"][key] for key in ("default", "enchanted")},
                         {"default": "elytra", "enchanted": "elytra_glint"})
        self.assertEqual({key: description["textures"][key] for key in ("default", "enchanted")},
                         {"default": "textures/models/armor/elytra", "enchanted": "textures/misc/enchanted_actor_glint"})
        self.assertEqual(description["geometry"]["default"], "geometry.elytra")
        self.assertEqual({key: description["animations"][key] for key in NATIVE_ANIMATIONS}, NATIVE_ANIMATIONS)
        self.assertEqual(description["scripts"]["parent_setup"], "variable.chest_layer_visible = 0.0;")
        self.assertEqual(active(description["scripts"]["animate"], False), ["default_controller"])
        # Keep the engine's complete native controller, including its trim and
        # enchanted behavior; a simplified substitute would fail this check.
        self.assertEqual(active(description["render_controllers"], False), ["controller.render.armor"])

    def test_marker_is_guarded_refreshed_each_frame_and_switches_both_passes(self):
        description = attachment()
        statements = description["scripts"]["pre_animation"]
        self.assertEqual(len(statements), 2)
        for properties, expected, infinity in (({}, False, False), ({MARKER: False}, False, False),
                ({MARKER: True}, True, False), ({MARKER: True, INFINITY_MARKER: True}, True, True),
                ({MARKER: True, INFINITY_MARKER: False}, True, False),
                ({MARKER: False, INFINITY_MARKER: True}, False, True), ({}, False, False)):
            marked = marker_value(statements[0], properties)
            self.assertEqual(marked, expected)
            self.assertEqual(marker_value(statements[1], properties, variable=INFINITY_VARIABLE), infinity)
            self.assertEqual(active(description["scripts"]["animate"], marked),
                             ["convergence_controller"] if expected else ["default_controller"])
            self.assertEqual(active(description["render_controllers"], marked),
                             ["controller.render.convergence.combined_armor", "controller.render.convergence.combined_wings"]
                             if expected else ["controller.render.armor"])
        with self.assertRaises(ValueError):
            marker_value(statements[0].replace("has_property", "property"), {})

    def test_held_and_unknown_slots_never_select_custom_pass_even_with_marked_owner(self):
        description = attachment()
        # This table is a contract test with injected context, not evidence of
        # what an iPad supplies for an equipped chest item or absent owner.
        for statement, variable, marker in zip(description["scripts"]["pre_animation"],
                (VARIABLE, INFINITY_VARIABLE), (MARKER, INFINITY_MARKER)):
            for properties in ({}, {marker: False}, {MARKER: True, INFINITY_MARKER: True}):
                for slot in ("main_hand", "off_hand", "head", "legs", "feet", "chest", "body", "", "unknown", None):
                    with self.subTest(variable=variable, properties=properties, slot=slot):
                        marked = marker_value(statement, properties, slot, variable=variable)
                        self.assertFalse(marked)
                        self.assertEqual(active(description["scripts"]["animate"], marked), ["default_controller"])
                        self.assertEqual(active(description["render_controllers"], marked), ["controller.render.armor"])
            self.assertFalse(marker_value(statement, {marker: True}, "torso", owner_exists=False, variable=variable))
            self.assertTrue(marker_value(statement, {marker: True}, "torso", variable=variable))
            with self.assertRaises(ValueError):
                marker_value(statement.replace("context.owning_entity->", ""), {marker: True}, variable=variable)

    def test_custom_armor_reuses_only_accepted_same_set_original_art_and_joints(self):
        description = attachment()
        self.assertEqual(description["geometry"]["convergence_armor"], "geometry.convergence.cuirass_original")
        self.assertEqual(description["textures"]["convergence_armor"], "textures/convergence/models/armor/armor42/convergence")
        self.assertEqual(description["geometry"]["infinity_armor"], "geometry.convergence.infinity_chestplate_elemental41")
        self.assertEqual(description["textures"]["infinity_armor"], "textures/convergence/models/armor/armor42/infinity")
        preserved = {
            "models/entity/original_armor/cuirass.geo.json": "4218cd173cfb5159210a9ddf4fc8b117e6faa2fe56f5e29202d9697e9cee849d",
            "textures/convergence/models/armor/armor42/convergence.png": "3287b00a45f292e73367ded77c31374f384d4e81c552dfae158383ef90deeacc",
            "textures/convergence/models/armor/armor_wings.png": "c1d221fe3a87f52f20b42ed507bd2b5872faf21bc412dab0d598c790e7574941",
            "models/entity/elemental41/infinity_chestplate.geo.json": "660e6467c83cc07eb690e1b2bc02f66e9467377b22fb9b6c6ac21e85ca2d0c13",
            "textures/convergence/models/armor/armor42/infinity.png": "e7dd7843aaf8e9046a9e0f6c36a6d9728787a61175a8096a56ffeeda5b77d0f1",
        }
        for path, digest in preserved.items():
            self.assertEqual(hashlib.sha256((BED / path).read_bytes()).hexdigest(), digest, path)
        for path in ("models/entity/original_armor/cuirass.geo.json", "models/entity/elemental41/infinity_chestplate.geo.json"):
            geometry = read(path)["minecraft:geometry"][0]
            self.assertEqual({bone["name"]: bone["pivot"] for bone in geometry["bones"]},
                             {"body": [0, 24, 0], "rightArm": [-5, 22, 0], "leftArm": [5, 22, 0]})
            for bone in geometry["bones"]:
                self.assertTrue(bone["cubes"])
                self.assertFalse({"binding", "parent", "position", "rotation", "scale"} & bone.keys())
        controllers = read("render_controllers/combined_convergence_elytra.json")["render_controllers"]
        armor = controllers["controller.render.convergence.combined_armor"]
        self.assertEqual(armor["geometry"], "Array.combined_chest_geometry[" + INFINITY_VARIABLE + " ? 1 : 0]")
        self.assertEqual(armor["textures"], ["Array.combined_chest_texture[" + INFINITY_VARIABLE + " ? 1 : 0]", "Texture.enchanted"])
        # Both indices select a matching geometry/texture pair from the actual
        # invoked arrays. These are source-resolution checks, not Molang pixels.
        for infinity, expected in ((False, "convergence_armor"), (True, "infinity_armor")):
            index = int(infinity)
            self.assertEqual(armor["arrays"]["geometries"]["Array.combined_chest_geometry"][index], "Geometry." + expected)
            self.assertEqual(armor["arrays"]["textures"]["Array.combined_chest_texture"][index], "Texture." + expected)
        visibility = {key: value for item in armor["part_visibility"] for key, value in item.items()}
        self.assertEqual(visibility, {"body": VARIABLE + " && (query.is_in_ui || !query.is_first_person)",
                                     "rightArm": VARIABLE, "leftArm": VARIABLE})

    def test_custom_animations_touch_only_original_wing_hinges(self):
        animations = read("animations/combined_convergence_elytra.animation.json")["animations"]
        self.assertEqual(len(animations), 5)
        for animation in animations.values():
            self.assertTrue(animation["loop"])
            self.assertEqual(set(animation["bones"]), WING_BONES)
            for channel in animation["bones"].values():
                self.assertEqual(set(channel), {"rotation"})
                self.assertEqual(len(channel["rotation"]), 3)
                self.assertTrue(all(isinstance(angle, (int, float)) and math.isfinite(angle)
                                    for angle in channel["rotation"]))
        controller = read("animation_controllers/combined_convergence_elytra.animation_controllers.json")["animation_controllers"]
        state_machine = controller["controller.animation.convergence.combined_wings"]
        self.assertEqual(state_machine["initial_state"], "folded")
        self.assertEqual(set(state_machine["states"]), {"folded", "gliding", "sneaking", "sleeping", "swimming"})
        aliases = attachment()["animations"]
        for name, state in state_machine["states"].items():
            self.assertEqual(state["animations"], ["convergence_" + name])
            self.assertIn(aliases[state["animations"][0]], animations)
            self.assertGreater(state["blend_transition"], 0)
            for transition in state["transitions"]:
                self.assertTrue(set(transition) <= state_machine["states"].keys())

    def test_wings_stay_behind_torso_through_authored_poses_and_blends(self):
        geometry = read("models/entity/original_armor/combined_convergence_wings.geo.json")["minecraft:geometry"][0]
        self.assertEqual(geometry["bones"][0], {"name": "body", "pivot": [0, 24, 0]})
        self.assertEqual({bone["name"] for bone in geometry["bones"][1:]}, WING_BONES)
        animations = read("animations/combined_convergence_elytra.animation.json")["animations"]
        # The wings and accepted torso receive the same owner body transform.
        # Separation from its back plane therefore survives any common walk,
        # crouch or gliding transform, without applying that transform twice.
        torsos = [read(path)["minecraft:geometry"][0]["bones"][0]["cubes"][0] for path in
                  ("models/entity/original_armor/cuirass.geo.json", "models/entity/elemental41/infinity_chestplate.geo.json")]
        torso_back = max(torso["origin"][2] + torso["size"][2] for torso in torsos)
        for bone in geometry["bones"][1:]:
            self.assertEqual(bone["parent"], "body")
            self.assertNotIn("binding", bone)
            for cube in bone["cubes"]:
                self.assertTrue(all(size > 0 for size in cube["size"]))
            for start, end in itertools.product(animations.values(), repeat=2):
                first, last = start["bones"][bone["name"]]["rotation"], end["bones"][bone["name"]]["rotation"]
                for step in range(21):
                    angles = tuple(a + (b - a) * step / 20 for a, b in zip(first, last))
                    for cube in bone["cubes"]:
                        for vertex in vertices(cube):
                            # Both Euler orderings cover this X/Z-only design;
                            # neither may move a wing through the torso shell.
                            for order in ((0, 1, 2), (2, 1, 0)):
                                transformed = rotate(vertex, bone["pivot"], angles, order)
                                self.assertGreaterEqual(transformed[2], torso_back + 0.1)

    def test_gliding_spreads_wings_beyond_folded_silhouette(self):
        geometry = read("models/entity/original_armor/combined_convergence_wings.geo.json")["minecraft:geometry"][0]
        animations = read("animations/combined_convergence_elytra.animation.json")["animations"]
        spans = {}
        for state in ("folded", "gliding"):
            animation = animations["animation.convergence.combined_wings." + state]
            for order in ((0, 1, 2), (2, 1, 0)):
                xs = [rotate(vertex, bone["pivot"], animation["bones"][bone["name"]]["rotation"], order)[0]
                      for bone in geometry["bones"][1:] for cube in bone["cubes"] for vertex in vertices(cube)]
                spans[state, order] = max(xs) - min(xs)
        for order in ((0, 1, 2), (2, 1, 0)):
            self.assertGreater(spans["gliding", order], spans["folded", order] * 1.75)

    def test_java_infinity_asset_keeps_matching_armor_and_adds_existing_wings(self):
        asset = json.loads((ROOT / "java/src/main/resources/assets/convergence/equipment/infinity_armor.json").read_text())
        self.assertEqual(asset, {"layers": {
            "humanoid": [{"texture": "convergence:armor42/infinity"}],
            "humanoid_leggings": [{"texture": "convergence:armor42/infinity"}],
            "wings": [{"texture": "convergence:armor"}]}})


if __name__ == "__main__":
    unittest.main()
