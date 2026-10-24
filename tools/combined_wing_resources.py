"""Fail closed if the native-elytra cosmetic overlay changes ordinary equipment.

The bridge supplies namespaced, per-player booleans. Missing/false properties
retain Minecraft's own elytra renderer and animations; only the combined marker
enables an armor shell and wings, and the set marker selects the accepted
Convergence or Infinity chest shell. This checks package
structure, not a Bedrock client's Molang evaluation or rendered pixels.
"""
import hashlib
import json
import math

ATTACHABLE = "attachables/combined_convergence_elytra.json"
RENDERERS = "render_controllers/combined_convergence_elytra.json"
ANIMATIONS = "animations/combined_convergence_elytra.animation.json"
CONTROLLER = "animation_controllers/combined_convergence_elytra.animation_controllers.json"
WINGS = "models/entity/original_armor/combined_convergence_wings.geo.json"
FLAG = "variable.infinityhub_winged_chest"
PROPERTY = "infinityhub:winged_chest"
INFINITY_FLAG = "variable.infinityhub_infinity_winged_chest"
INFINITY_PROPERTY = "infinityhub:infinity_winged_chest"
ACCEPTED = {
    "models/entity/original_armor/cuirass.geo.json": "4218cd173cfb5159210a9ddf4fc8b117e6faa2fe56f5e29202d9697e9cee849d",
    "textures/convergence/models/armor/armor42/convergence.png": "3287b00a45f292e73367ded77c31374f384d4e81c552dfae158383ef90deeacc",
    "textures/convergence/models/armor/armor_wings.png": "c1d221fe3a87f52f20b42ed507bd2b5872faf21bc412dab0d598c790e7574941",
    "models/entity/elemental41/infinity_chestplate.geo.json": "660e6467c83cc07eb690e1b2bc02f66e9467377b22fb9b6c6ac21e85ca2d0c13",
    "textures/convergence/models/armor/armor42/infinity.png": "e7dd7843aaf8e9046a9e0f6c36a6d9728787a61175a8096a56ffeeda5b77d0f1",
}


def validate(files):
    def read(name):
        raw = files[name]
        if not 0 < len(raw) <= 131072:
            raise ValueError("Missing or oversized combined-wing resource: " + name)
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError("Duplicate combined-wing resource key")
                result[key] = value
            return result
        return json.loads(raw, object_pairs_hook=unique)

    def require(condition, reason):
        if not condition:
            raise ValueError(reason)

    for name, expected in ACCEPTED.items():
        require(hashlib.sha256(files[name]).hexdigest() == expected,
                "Accepted combined armor/artwork changed: " + name)
    description = read(ATTACHABLE)["minecraft:attachable"]["description"]
    require(description["identifier"] == "minecraft:elytra", "Flight must retain the native elytra identity")
    for name, expected in (("materials", {"default": "elytra", "enchanted": "elytra_glint"}),
                           ("textures", {"default": "textures/models/armor/elytra", "enchanted": "textures/misc/enchanted_actor_glint"}),
                           ("geometry", {"default": "geometry.elytra"})):
        require(all(description[name].get(key) == value for key, value in expected.items()),
                "Ordinary elytra defaults changed: " + name)
    native = {"default_controller": "controller.animation.elytra.default",
              **{state: "animation.elytra." + state for state in ("default", "gliding", "sneaking", "sleeping", "swimming")}}
    require(all(description["animations"].get(key) == value for key, value in native.items()),
            "Ordinary elytra animation references changed")
    scripts = description["scripts"]
    require(scripts.get("parent_setup") == "variable.chest_layer_visible = 0.0;", "Native parent setup changed")
    require(scripts.get("pre_animation") == [
        flag + " = query.item_slot_to_bone_name(context.item_slot) == 'body' ? "
        "((context.owning_entity->query.has_property('" + prop + "')) ? "
        "(context.owning_entity->query.property('" + prop + "')) : 0.0) : 0.0;"
        for flag, prop in ((FLAG, PROPERTY), (INFINITY_FLAG, INFINITY_PROPERTY))
    ], "Only the chest attachment may read both guarded owner properties each frame")
    require(scripts.get("animate") == [{"default_controller": "!" + FLAG}, {"convergence_controller": FLAG}],
            "Native and combined animations must be mutually exclusive")
    require(description["render_controllers"] == [
        {"controller.render.armor": "!" + FLAG},
        {"controller.render.convergence.combined_armor": FLAG},
        {"controller.render.convergence.combined_wings": FLAG}],
        "Ordinary elytra must use its native renderer, with no armor overlay")
    require(description["geometry"]["convergence_armor"] == "geometry.convergence.cuirass_original"
            and description["textures"]["convergence_armor"] == "textures/convergence/models/armor/armor42/convergence",
            "Combined armor must use its own accepted Convergence shell")
    require(description["geometry"].get("infinity_armor") == "geometry.convergence.infinity_chestplate_elemental41"
            and description["textures"].get("infinity_armor") == "textures/convergence/models/armor/armor42/infinity",
            "Infinity's combined armor must retain its own accepted shell and artwork")
    require(description["geometry"].get("convergence_wing") == "geometry.convergence.combined_wings_original"
            and description["textures"].get("convergence_wing") == "textures/convergence/models/armor/armor_wings",
            "Combined wings must retain their own original model and artwork")
    renderers = read(RENDERERS)["render_controllers"]
    require(set(renderers) == {"controller.render.convergence.combined_armor", "controller.render.convergence.combined_wings"},
            "Unexpected combined renderer")
    armor = renderers["controller.render.convergence.combined_armor"]
    selector = INFINITY_FLAG + " ? 1 : 0"
    require(armor.get("geometry") == "Array.combined_chest_geometry[" + selector + "]"
            and armor.get("textures") == ["Array.combined_chest_texture[" + selector + "]", "Texture.enchanted"]
            and armor.get("arrays") == {
                "geometries": {"Array.combined_chest_geometry": ["Geometry.convergence_armor", "Geometry.infinity_armor"]},
                "textures": {"Array.combined_chest_texture": ["Texture.convergence_armor", "Texture.infinity_armor"]}},
            "The guarded Infinity marker must select matching armor geometry and texture together")
    body_visible = FLAG + " && (query.is_in_ui || !query.is_first_person)"
    require(set(armor) == {"geometry", "materials", "textures", "arrays", "part_visibility"}
            and armor.get("materials") == [{"*": "variable.is_enchanted ? Material.convergence_armor_enchanted : Material.convergence_armor"}]
            and armor.get("part_visibility") == [{"body": body_visible}, {"rightArm": FLAG}, {"leftArm": FLAG}],
            "Combined armor must preserve guarded accepted joint visibility and materials")
    wings = renderers["controller.render.convergence.combined_wings"]
    require(set(wings) == {"geometry", "materials", "textures", "part_visibility"}
            and wings.get("geometry") == "Geometry.convergence_wing"
            and wings.get("textures") == ["Texture.convergence_wing", "Texture.enchanted"]
            and wings.get("materials") == [{"*": "variable.is_enchanted ? Material.convergence_wing_enchanted : Material.convergence_wing"}]
            and wings.get("part_visibility") == [{"body": body_visible},
                {"convergence_left_wing": body_visible}, {"convergence_right_wing": body_visible}],
            "Combined wings must retain their guarded renderer, visibility and original aliases")
    animations = read(ANIMATIONS)["animations"]
    states = {"folded", "gliding", "sneaking", "sleeping", "swimming"}
    expected_animations = {"animation.convergence.combined_wings." + state for state in states}
    require(set(animations) == expected_animations,
            "Unexpected combined-wing animation")
    for animation in animations.values():
        require(set(animation) == {"loop", "bones"} and animation.get("loop") is True,
                "Combined wings require looping, bone-only animations")
        require(set(animation.get("bones", {})) == {"convergence_left_wing", "convergence_right_wing"},
                "Wing animations must never move or scale accepted armor joints")
        for channel in animation["bones"].values():
            angles = channel.get("rotation")
            require(set(channel) == {"rotation"} and isinstance(angles, list) and len(angles) == 3
                    and all(type(angle) in (int, float) and math.isfinite(angle) for angle in angles),
                    "Combined wing channels may only rotate their own hinges")
    # Resolve the actually invoked alias and all of its state animations. Merely
    # shipping safe, unused wing definitions cannot protect the accepted body
    # from a mistakenly rebound native elytra controller or animation.
    controller_name = "controller.animation.convergence.combined_wings"
    aliases = description["animations"]
    require(aliases.get("convergence_controller") == controller_name,
            "The marked path must invoke only the owned wing controller")
    controllers = read(CONTROLLER).get("animation_controllers", {})
    require(set(controllers) == {controller_name}, "Missing or unexpected combined-wing controller")
    controller = controllers[controller_name]
    require(set(controller) == {"initial_state", "states"} and controller.get("initial_state") == "folded"
            and set(controller.get("states", {})) == states, "Incomplete combined-wing state graph")
    reached_animations = set()
    for state_name, state in controller["states"].items():
        require(set(state) == {"animations", "transitions", "blend_transition"}
                and state.get("animations") == ["convergence_" + state_name],
                "Every combined-wing state must invoke its own explicit animation alias")
        resolved = aliases.get(state["animations"][0])
        require(resolved == "animation.convergence.combined_wings." + state_name and resolved in animations,
                "Combined-wing state resolves to a missing or foreign animation")
        reached_animations.add(resolved)
        transitions = state.get("transitions")
        require(isinstance(transitions, list) and transitions, "Combined-wing state has no exit transitions")
        for transition in transitions:
            require(isinstance(transition, dict) and len(transition) == 1
                    and set(transition) <= states
                    and all(isinstance(expression, str) and expression for expression in transition.values()),
                    "Combined-wing transition references an unknown state")
        blend = state.get("blend_transition")
        require(type(blend) in (int, float) and math.isfinite(blend) and 0 < blend <= 1,
                "Combined-wing blending must remain finite and bounded")
    require(reached_animations == expected_animations, "Combined-wing animations must all be reached by the owned states")
    read(WINGS)
    native_attachables = [name for name in files if name.startswith("attachables/") and name.endswith(".json")
                          and read(name).get("minecraft:attachable", {}).get("description", {}).get("identifier") == "minecraft:elytra"]
    require(native_attachables == [ATTACHABLE], "Only the guarded native-elytra attachable may be active")
    return {"property": PROPERTY, "infinity_property": INFINITY_PROPERTY,
            "ordinary_elytra_uses_native_renderer": True,
            "accepted_convergence_shell_unchanged": True, "accepted_infinity_shell_unchanged": True,
            "client_rendering_verified": False}
