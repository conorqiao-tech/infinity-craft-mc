"""Author original gear JSON without reading or changing any bitmap/native model.

Bedrock binding/per-face UV schema:
https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/geometry.v1.19.30
https://learn.microsoft.com/en-us/minecraft/creator/documents/attachables
Render-controller material/glint and visibility schema:
https://learn.microsoft.com/en-us/minecraft/creator/documents/custom_projectiles
https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/render_controller.v1.8.0

Player joint names are an engine attachment contract, not imported armor bones.
The cuboids, silhouettes, controllers and Java poses below are authored here.
Java state property names come from the pinned 26.3 client resource definitions;
all models and texture references remain in the convergence namespace.
"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
WEARABLES = {"helmet": ("convergence", "helmet", "head", 0),
             "cuirass": ("convergence", "chestplate", "chest", 1),
             "leggings": ("convergence", "leggings", "legs", 2),
             "boots": ("convergence", "boots", "feet", 3),
             "backpack": ("backpack", "backpack", "chest", 1)}
for outfit in ("aurora", "ember"):
    for piece, slot, index in (("helmet", "head", 0), ("chestplate", "chest", 1),
                               ("leggings", "legs", 2), ("boots", "feet", 3)):
        WEARABLES[outfit + "_" + piece] = (outfit, piece, slot, index)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def face_uv(uv, part):
    result = {}
    for direction, face in (("up", "top"), ("down", "bottom"), ("west", "right"),
                            ("north", "front"), ("east", "left"), ("south", "back")):
        x0, y0, x1, y1 = uv[part + "_" + face]
        result[direction] = {"uv": [x0 * 2, y0 * 2], "uv_size": [(x1 - x0) * 2, (y1 - y0) * 2]}
    return result


def cube(uv, part, origin, size, rotation=None, pivot=None):
    value = {"origin": origin, "size": size, "uv": face_uv(uv, part)}
    if rotation is not None:
        value.update({"rotation": rotation, "pivot": pivot})
    return value


def bone(name, joint, pivot, cubes):
    # A literal Molang string binds this independent bone to the animated player
    # joint. Legs/arms bind separately: a torso attachment cannot animate them.
    return {"name": "infinity_" + name, "binding": "'" + joint + "'", "pivot": pivot, "cubes": cubes}


def armor_geometry(path, collection, piece, uv):
    """Small original plate assemblies; no inherited/imported armor geometry."""
    # Skin clothing is still rendered below an attachable: hats inflate by .5,
    # sleeves/jackets/pants by .25. Clearing only the base skin clips that layer.
    plate = {"convergence": 0.40, "aurora": 0.38, "ember": 0.44, "backpack": 0.3}[collection]
    if piece == "helmet":
        head_plate = max(0.60, plate + 0.20)
        shell = cube(uv, "head", [-4 - head_plate, 24 - head_plate, -4 - head_plate],
                     [8 + head_plate * 2, 8 + head_plate * 2, 8 + head_plate * 2])
        details = []
        if collection == "aurora":
            # Two jade crown fins follow the helmet rather than a shoulder joint.
            details = [cube(uv, "head", [x, 31, -0.8], [1.2, 3.2, 1.6], [0, 0, tilt], [x + 0.6, 31, 0])
                       for x, tilt in ((-4.3, 18), (3.1, -18))]
        elif collection == "ember":
            details = [cube(uv, "head", [x, 30, -0.7], [1.3, 2.8, 1.4], [0, 0, tilt], [x + 0.65, 30, 0])
                       for x, tilt in ((-5.1, 20), (3.8, -20))]
        else:
            details = [cube(uv, "head", [-2.2, 32 + head_plate, -1.3], [4.4, 0.65, 2.6])]
        bones = [bone("head", "head", [0, 24, 0], [shell] + details)]
    elif piece == "chestplate":
        bones = [bone("body", "body", [0, 24, 0], [
            cube(uv, "body", [-4 - plate, 12 - plate, -2 - plate], [8 + plate * 2, 12 + plate * 2, 4 + plate * 2]),
            # A raised breastplate emblem and two articulated shoulder guards.
            cube(uv, "body", [-2.7, 17, -2 - plate - 0.65], [5.4, 4.5, 0.6])])]
        for side, joint, x, pivot in (("right_arm", "rightArm", -8, [-5, 22, 0]),
                                      ("left_arm", "leftArm", 4, [5, 22, 0])):
            # The torso and arm shells meet at the shoulder. Distinct depths
            # prevent two positive-area faces from occupying the same plane.
            arm_plate = plate + 0.08
            bones.append(bone(side, joint, pivot, [
                # Slim sleeves reach y=11.25; wide sleeves reach y=24.25.
                # Enclose their union without changing either joint binding.
                cube(uv, "arm", [x - arm_plate, 11.15, -2 - arm_plate], [4 + arm_plate * 2, 24 + arm_plate - 11.15, 4 + arm_plate * 2]),
                cube(uv, "arm", [x - 0.6, 21.3, -2.6], [5.2, 2.6, 5.2])]))
    elif piece in ("leggings", "boots"):
        bones = []
        for side, joint, x, pivot in (("right_leg", "rightLeg", -3.9, [-1.9, 12, 0]),
                                      ("left_leg", "leftLeg", -0.1, [1.9, 12, 0])):
            # Full-width shells enclose each leg's pants overlay, including the
            # inner thigh. A tiny left-side depth/Y stagger separates shared
            # centre faces without cutting a gap through the visible skin.
            stagger = 0.012 if side == "left_leg" else 0.0
            layer = plate + (-0.02 if piece == "leggings" else 0.12) + stagger
            outer_x = x - layer
            width = 4 + layer * 2
            if piece == "leggings":
                # The boot overlaps this inner layer at a different depth;
                # neither a skin gap nor a pair of coplanar seam faces remains.
                cubes = [cube(uv, "leg", [outer_x, 3.75 - stagger, -2 - layer], [width, 8.6 + stagger * 2, 4 + layer * 2]),
                         cube(uv, "leg", [outer_x + 0.12, 6.8 - stagger, -2 - layer - 0.35], [width - 0.24, 2.4 + stagger * 2, 0.3])]
            else:
                cubes = [cube(uv, "leg", [outer_x, -layer, -2 - layer], [width, 4.1 + stagger + layer, 4 + layer * 2]),
                         cube(uv, "leg", [outer_x, -layer, -2 - layer - 0.8], [width, 1.5 + stagger * 2, 0.75])]
            bones.append(bone(side, joint, pivot, cubes))
    elif piece == "backpack":
        # A real pack sits behind the torso, not a second recolored chest shell.
        pack = cube(uv, "body", [-3.3, 13, 2.4], [6.6, 9.8, 4.2])
        pack["uv"]["south"] = face_uv(uv, "body")["north"]
        bones = [bone("pack", "body", [0, 24, 0], [pack,
            cube(uv, "body", [-2.8, 20.2, 6.65], [5.6, 2.2, 0.6]),
            cube(uv, "arm", [-4.35, 15, 2.9], [0.95, 5.3, 2.7]),
            cube(uv, "arm", [3.4, 15, 2.9], [0.95, 5.3, 2.7])])]
    else:
        raise ValueError(piece)
    # Molang string literals preserve case. Follow the lowercase limb targets
    # used by Microsoft's custom wearable example; head/body already agree.
    # Keep this separate from the inactive legacy flight-shell generator.
    for target in bones:
        target["binding"] = target["binding"].lower()
    return {"format_version": "1.19.30", "minecraft:geometry": [{
        "description": {"identifier": "geometry.convergence." + path + "_original", "texture_width": 128,
                        "texture_height": 64, "visible_bounds_width": 2.5, "visible_bounds_height": 3,
                        "visible_bounds_offset": [0, 1, 0]}, "bones": bones}]}


def flight_shell_geometry(uv):
    """Original open flight harness; neither a native nor static armor model.

    Segmented front plates, side ribs and a small rear mount leave space for
    the engine's moving wings. Short shoulder guards follow each player arm;
    this layer never defines or animates the actual elytra bones.
    """
    bones = [bone("flight_body", "body", [0, 24, 0], [
        cube(uv, "body", [-4.4, 22.2, -2.45], [8.8, 1.65, 0.7]),
        cube(uv, "body", [-3.45, 15.2, -2.7], [6.9, 6.6, 0.85]),
        cube(uv, "body", [-4.1, 12.1, -2.55], [8.2, 2.4, 0.7]),
        cube(uv, "body", [-4.4, 13, -1.8], [0.9, 8.9, 3.2]),
        cube(uv, "body", [3.5, 13, -1.8], [0.9, 8.9, 3.2]),
        cube(uv, "body", [-1.4, 20.2, 2.2], [2.8, 2.8, 0.55])])]
    for side, joint, x, pivot in (("right_arm", "rightArm", -8.5, [-5, 22, 0]),
                                  ("left_arm", "leftArm", 3.5, [5, 22, 0])):
        bones.append(bone("flight_" + side, joint, pivot, [
            cube(uv, "arm", [x, 20.7, -2.45], [5, 3.4, 4.9]),
            cube(uv, "arm", [x + 0.65, 19.5, -2.6], [3.7, 1, 0.55])]))
    return {"format_version": "1.19.30", "minecraft:geometry": [{
        "description": {"identifier": "geometry.convergence.flight_shell_original", "texture_width": 128,
                        "texture_height": 64, "visible_bounds_width": 2.5, "visible_bounds_height": 3,
                        "visible_bounds_offset": [0, 1, 0]}, "bones": bones}]}


def bedrock_models():
    uv = json.loads((ROOT / "artwork/refresh27/uv-manifest.json").read_text())["armor"]["convergence"]["uv_rectangles"]
    controllers = {}
    for path, (collection, piece, slot, index) in WEARABLES.items():
        geometry = armor_geometry(path, collection, piece, uv)
        write_json(BED / f"models/entity/original_armor/{path}.geo.json", geometry)
        controller = "controller.render.convergence." + path + "_original"
        # Limit rendering to an equipped instance, not the same item held in a
        # hand. Explicit bindings then inherit head, arm and leg motion.
        equipped = f"query.is_item_name_any('slot.armor.{slot}', 0, 'convergence:{path}', 'infinity_catalog:{path}')"
        visibility = []
        for b in geometry["minecraft:geometry"][0]["bones"]:
            third_person = b["name"] not in ("infinity_right_arm", "infinity_left_arm")
            # Use the current entity render pass, with an explicit inventory
            # allowance. A held attachable context is not our worn camera test.
            visibility.append({b["name"]: equipped + (" && (query.is_in_ui || !query.is_first_person)" if third_person else "")})
        controllers[controller] = {"geometry": "Geometry.default",
            "materials": [{"*": "variable.is_enchanted ? Material.enchanted : Material.default"}],
            "textures": ["Texture.default", "Texture.enchanted"], "part_visibility": visibility}
        description = {"identifier": "convergence:" + path,
            "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {"default": (f"textures/convergence/models/armor/refresh27/{collection}_refresh27" if collection == "backpack"
                                     else f"textures/convergence/models/armor/armor42/{collection}"),
                         "enchanted": "textures/misc/enchanted_actor_glint"},
            "geometry": {"default": geometry["minecraft:geometry"][0]["description"]["identifier"]},
            "scripts": {"parent_setup": "variable." + {"head":"helmet", "chest":"chest", "legs":"leg", "feet":"boot"}[slot] + "_layer_visible = 0.0;"},
            "render_controllers": [controller]}
        # Keep the same engine tint query previously selected by armor rendering.
        # No dye/gameplay component or saved backpack color is changed here.
        if collection == "backpack":
            controllers[controller]["color"] = {c: f"query.armor_color_slot({index}, {i})" for i,c in enumerate(("r","g","b","a"))}
        write_json(BED / f"attachables/refresh27/{path}.json", {"format_version": "1.10.0", "minecraft:attachable": {"description": description}})
        # Creative prediction may momentarily use the public catalog ID. It has
        # no separate worn model or texture and becomes the canonical real stack.
        alias = dict(description, identifier="infinity_catalog:" + path)
        write_json(BED / f"attachables/refresh27/catalog_{path}.json", {"format_version": "1.10.0", "minecraft:attachable": {"description": alias}})
    write_json(BED / "render_controllers/original_static_armor.json", {"format_version": "1.8.0", "render_controllers": controllers})
    flight = flight_shell_geometry(uv)
    write_json(BED / "models/entity/original_armor/flight_shell.geo.json", flight)
    wings_path = BED / "attachables/elytra.json"
    wings = json.loads(wings_path.read_text())
    # Only the extra chest layer changes. Native wing geometry, materials,
    # animation controllers and engine scripts remain exactly as supplied.
    wings["minecraft:attachable"]["description"]["geometry"]["infinity_armor"] = flight["minecraft:geometry"][0]["description"]["identifier"]
    write_json(wings_path, wings)


def model(name):
    return {"type": "minecraft:model", "model": "convergence:item/" + name}


def pose(rotation, translation, scale):
    return {"rotation": rotation, "translation": translation, "scale": scale}


def ranged_display(kind, stage=0):
    """Original held poses; frame movement uses the existing original sprite."""
    bow = kind == "bow"
    rotation = [0, -85, 24] if bow else [-85, 0, -50]
    translation = [1.15, 3.1, 1.0]
    scale = [round(0.70 + stage * 0.015, 3), round(0.70 - stage * 0.035, 3), 0.70]
    displays = {"firstperson_righthand": pose(rotation, translation, scale),
                "firstperson_lefthand": pose([-rotation[0], -rotation[1], -rotation[2]], translation, scale)}
    right = [-78, 255, -38] if bow else [-88, 0, -56]
    left = [-78, -275, 38] if bow else [-88, 0, 32]
    for context, r in (("thirdperson_righthand", right), ("thirdperson_lefthand", left)):
        displays[context] = pose(r, [-0.8, -1.8, 2.3] if bow else [1.8, 0.2, -2.8],
                                [round(0.92 + stage * 0.02,3), round(0.92 - stage * 0.04,3), 0.92])
    return displays


def java_models():
    for kind in ("bow", "crossbow"):
        texture = "convergence:item/refresh27/" + kind
        write_json(JAVA / f"models/item/{kind}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": texture}, "display": ranged_display(kind)})
        for i in range(3):
            write_json(JAVA / f"models/item/{kind}_pulling_{i}.json", {"parent": "convergence:item/" + kind, "display": ranged_display(kind, i + 1)})
    bow = {"type": "minecraft:condition", "property": "minecraft:using_item", "on_false": model("bow"),
           "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:use_duration", "scale": 0.05,
                       "fallback": model("bow_pulling_0"), "entries": [{"threshold": 0.65, "model": model("bow_pulling_1")}, {"threshold": 0.9, "model": model("bow_pulling_2")}]}}
    write_json(JAVA / "items/bow.json", {"model": bow})
    for kind, stage in (("arrow", 3), ("rocket", 4)):
        write_json(JAVA / f"models/item/crossbow_{kind}.json", {"parent": "convergence:item/crossbow", "display": ranged_display("crossbow", stage)})
    crossbow = {"type": "minecraft:select", "property": "minecraft:charge_type",
        "cases": [{"when": "arrow", "model": model("crossbow_arrow")}, {"when": "rocket", "model": model("crossbow_rocket")}],
        "fallback": {"type": "minecraft:condition", "property": "minecraft:using_item", "on_false": model("crossbow"),
            "on_true": {"type": "minecraft:range_dispatch", "property": "minecraft:crossbow/pull", "fallback": model("crossbow_pulling_0"),
                        "entries": [{"threshold": 0.58, "model": model("crossbow_pulling_1")}, {"threshold": 1.0, "model": model("crossbow_pulling_2")}]}}}
    write_json(JAVA / "items/crossbow.json", {"model": crossbow})
    shield_texture = "convergence:item/refresh27/shield"
    write_json(JAVA / "models/item/shield_icon.json", {"parent": "minecraft:item/generated", "textures": {"layer0": shield_texture}})
    shield_display = {"firstperson_righthand": pose([0,175,5],[-8.5,1.8,-9],[1.15]*3),
                      "firstperson_lefthand": pose([0,175,-5],[8.5,1.8,-9],[1.15]*3),
                      "thirdperson_righthand": pose([0,90,0],[8.5,5,-3.5],[1]*3),
                      "thirdperson_lefthand": pose([0,90,0],[8.5,5,11],[1]*3)}
    write_json(JAVA / "models/item/shield.json", {"textures": {"layer0": shield_texture, "particle": shield_texture}, "gui_light": "front",
        "elements": [{"from": [2.5,1.5,7.5], "to": [13.5,14.5,8.5], "faces": {face:{"uv":[0,0,16,16],"texture":"#layer0"} for face in ("north","south","east","west","up","down")}}],
        "display": shield_display})
    block_display = dict(shield_display)
    block_display["firstperson_righthand"] = pose([0,165,-8],[-3.5,2.5,-7.5],[1.25]*3)
    block_display["firstperson_lefthand"] = pose([0,195,8],[3.5,2.5,-7.5],[1.25]*3)
    block_display["thirdperson_righthand"] = pose([0,105,-15],[6.5,5,-4],[1]*3)
    block_display["thirdperson_lefthand"] = pose([0,75,15],[6.5,5,10],[1]*3)
    write_json(JAVA / "models/item/shield_blocking.json", {"parent":"convergence:item/shield", "display":block_display})
    inventory = ["gui", "ground", "fixed", "on_shelf"]
    shield = {"type":"minecraft:select", "property":"minecraft:display_context", "cases":[{"when":inventory,"model":model("shield_icon")}],
              "fallback":{"type":"minecraft:condition","property":"minecraft:using_item","on_false":model("shield"),"on_true":model("shield_blocking")}}
    write_json(JAVA / "items/shield.json", {"model":shield})
    write_json(JAVA / "models/item/spear_in_hand.json", {"parent":"minecraft:item/generated", "textures":{"layer0":"convergence:item/refresh27/spear"},
        "display":{"firstperson_righthand":pose([-18,90,-32],[2.8,2.2,0.2],[1.4,1.4,0.7]),
                   "firstperson_lefthand":pose([-18,-90,32],[2.8,2.2,0.2],[1.4,1.4,0.7]),
                   "thirdperson_righthand":pose([5,265,-38],[0,2.2,2],[1.75,1.75,0.88]),
                   "thirdperson_lefthand":pose([5,-265,38],[0,2.2,2],[1.75,1.75,0.88])}})
    write_json(JAVA / "items/spear.json", {"model":{"type":"minecraft:select","property":"minecraft:display_context",
        "cases":[{"when":inventory,"model":model("spear")}],"fallback":model("spear_in_hand")},"swap_animation_scale":1.95})


def generate():
    bedrock_models()
    java_models()
    print("Authored 13 independent Bedrock armor assemblies, an original flight chest shell, 13 catalog attachable aliases and original Java ranged/shield/spear state models; PNGs and native wing geometry/animations retained.")


if __name__ == "__main__":
    generate()
