"""Generate original held Infinity models without editing or importing artwork.

Run ``python3 artwork/refresh27/held_models.py`` from any directory, or call
``generate(resource_pack_path)`` for a disposable output. ``build_resources()``
returns the exact JSON objects keyed by resource-pack-relative paths. Only the
existing refresh27 sprites are read; no native model/texture is copied.

Engine contracts, rather than source geometry or animation values, come from:
https://learn.microsoft.com/en-us/minecraft/creator/documents/attachables
https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/geometry.v1.19.30
https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/render_controllers/bow.render_controllers.json
https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/render_controllers/crossbow.render_controllers.json
https://raw.githubusercontent.com/Mojang/bedrock-samples/main/resource_pack/attachables/shield.entity.json
https://github.com/GeyserMC/Geyser/blob/751d2aab177f614b9e99c9062959e4f2cb08a5c3/core/src/main/java/org/geysermc/geyser/session/GeyserSession.java#L1508-L1527
https://learn.microsoft.com/en-us/minecraft/creator/reference/content/molangreference/examples/molangconcepts/queryfunctions/query_is_item_name_any

Bow frames are standby/draw 0/draw 1/draw 2; crossbow adds loaded arrow/firework
frames 4/5. Their engine-selected frame, remaining use duration and blocking
flag drive these own poses. Frame declarations and pack references can be
checked here; actual client query values and rendered fit require game testing.
These are held-item resources, not replacements for launched arrow entities or
the Bedrock native totem activation animation. Owned shield poses follow
Geyser's mainhand blocking priority; ordinary offhand shield render predicates
remain native and require a mixed-hand device check.
"""
from __future__ import annotations

import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
BED = ROOT / "bedrock/resource_pack"
ITEMS = ("bow", "crossbow", "shield", "totem")
STAGES = {"bow": ("standby", "pulling_0", "pulling_1", "pulling_2"),
          "crossbow": ("standby", "pulling_0", "pulling_1", "pulling_2", "arrow", "rocket")}
BONE = "infinity_held_root"
BINDING = "query.item_slot_to_bone_name(context.item_slot)"
GRIP = [-6, 15, 1]
SIGN = "(context.item_slot == 'off_hand' ? -1.0 : 1.0)"


def item_names(slot: str, *paths: str) -> str:
    names = [namespace + ":" + path for path in paths for namespace in ("convergence", "infinity_catalog")]
    return "query.is_item_name_any('slot.weapon." + slot + "', " + ", ".join("'" + n + "'" for n in names) + ")"


def shielding(slot: str) -> str:
    # Pinned GeyserSession.attemptToBlock selects the main shield first.
    # Include native shields when deciding whether our offhand may block;
    # ordinary native shield resources themselves are never overridden here.
    # Creative aliases may appear during prediction before canonicalization.
    main = item_names("mainhand", "shield")
    any_main = main[:-1] + ", 'minecraft:shield')"
    off = item_names("offhand", "shield")
    return "query.blocking && " + ("(" + off + " && !(" + any_main + "))" if slot == "offhand" else main)


def sprite(texture: str = "default", stage: int = 0, *, position=None, scale=None, rotation=None):
    # Texture meshes extrude the original alpha silhouette. At 64px, 0.20
    # units per pixel gives a 12.8-unit tool rather than a 64-unit billboard.
    return {"texture": texture, "local_pivot": [32, 0, 32],
            "position": position if position is not None else list(GRIP),
            "rotation": rotation if rotation is not None else [90, 0, 0],
            "scale": scale if scale is not None else [round(0.20 + stage * 0.004, 3), 0.20, round(0.20 - stage * 0.012, 3)],
            "use_pixel_depth": True}


def capsule(origin, size):
    # A small ammunition shaft/capsule samples a bright opaque texel in our own
    # crossbow sprite. It does not reference the vanilla firework artwork.
    return {"origin": origin, "size": size,
            "uv": {face: {"uv": [42, 42], "uv_size": [1, 1]}
                   for face in ("north", "south", "east", "west", "up", "down")}}


def geometry(path: str, stage: str = "standby"):
    frames = STAGES.get(path, ("standby",))
    index = frames.index(stage)
    draw = index if index <= 3 else 3
    meshes = [sprite(stage=draw)]
    cubes = []
    if path == "crossbow" and stage == "arrow":
        cubes = [capsule([-6.25, 15.8, -5.2], [0.5, 0.5, 6.2]),
                 capsule([-6.5, 15.55, -5.8], [1, 1, 0.9]),
                 capsule([-7, 15.8, 0.3], [2, 0.5, 0.5])]
    elif path == "crossbow" and stage == "rocket":
        cubes = [capsule([-6.65, 15.4, -3.8], [1.3, 1.3, 3.7]),
                 capsule([-7.2, 15.65, -0.8], [2.4, 0.8, 0.8]),
                 capsule([-6.65, 15.4, -4.6], [1.3, 1.3, 0.8])]
    elif path == "shield":
        meshes = [sprite(scale=[0.235, 0.30, 0.255])]
    elif path == "totem":
        meshes = [sprite(scale=[0.145] * 3)]
    root = {"name": BONE, "binding": BINDING, "pivot": list(GRIP), "texture_meshes": meshes}
    if cubes:
        root["cubes"] = cubes
    return {"description": {"identifier": "geometry.convergence." + path + "_held_" + stage,
                            "texture_width": 64, "texture_height": 64,
                            "visible_bounds_width": 3, "visible_bounds_height": 3,
                            "visible_bounds_offset": [0, 1, 0]}, "bones": [root]}


def animation_name(path: str, pose: str) -> str:
    return "animation.convergence." + path + "_held_" + pose


def animation(position, rotation, scale=1):
    return {"loop": True, "bones": {BONE: {"position": position, "rotation": rotation, "scale": [scale] * 3}}}


def animations(path: str):
    if path == "shield":
        # Binding follows the selected hand. Mirrored first-person offsets
        # keep a normal shield at the screen edge and a blocking one in view.
        return {animation_name(path, "first"): animation([SIGN + " * 1.8", -0.8, -3.1], [-12, SIGN + " * 18", SIGN + " * -8"], 0.86),
                animation_name(path, "block"): animation([SIGN + " * -0.9", 1.1, -4.1], [-5, SIGN + " * 7", SIGN + " * -3"], 0.92),
                animation_name(path, "third"): animation([0, 0.1, "variable.infinity_blocking ? -1.1 : 0.15"],
                    ["variable.infinity_blocking ? -25 : -7", SIGN + " * 6", SIGN + " * (variable.infinity_blocking ? -12 : -4)"])}
    if path in STAGES:
        bow = path == "bow"
        return {animation_name(path, "first"): animation([SIGN + " * 1.4", -1.0, -2.8],
                    [-16 if bow else -8, SIGN + " * 14", SIGN + (" * -18" if bow else " * -9")], 0.82),
                animation_name(path, "draw"): animation([SIGN + " * 0.5", "0.7 + variable.infinity_charge * 0.25", "-3.7 - variable.infinity_charge * 0.4"],
                    [-28 if bow else -14, SIGN + " * 5", SIGN + (" * -10" if bow else " * -4")], 0.88),
                animation_name(path, "third"): animation([0, 0, "variable.infinity_using ? -0.35 : 0"],
                    ["variable.infinity_using ? -12 : -3", SIGN + " * 8", SIGN + " * -7"])}
    return {animation_name(path, "first"): animation([SIGN + " * 1.2", -0.7, -2.5], [-8, SIGN + " * 12", SIGN + " * -12"], 0.88),
            animation_name(path, "third"): animation([0, 0, 0], [-5, SIGN + " * 6", SIGN + " * -8"])}


def attachable(path: str, namespace: str):
    frames = STAGES.get(path, ("standby",))
    geometries = {"default" if i == 0 else stage: "geometry.convergence." + path + "_held_" + stage
                  for i, stage in enumerate(frames)}
    textures = {"default": "textures/convergence/items/refresh27/" + path,
                "enchanted": "textures/misc/enchanted_item_glint"}
    poses = animations(path)
    names = {name.rsplit("_held_", 1)[1]: name for name in poses}
    scripts = {"animate": [{"first": "context.is_first_person"}, {"third": "!context.is_first_person"}]}
    if path in STAGES:
        own_hand = "context.item_slot == 'main_hand' && " + item_names("mainhand", path)
        scripts["pre_animation"] = [
            "variable.infinity_using = " + own_hand + " && query.main_hand_item_use_duration > 0.0;",
            "variable.infinity_charge = variable.infinity_using ? math.clamp((query.main_hand_item_max_duration - (query.main_hand_item_use_duration - query.frame_alpha + 1.0)) / 20.0, 0.0, 1.0) : 0.0;"
        ]
        scripts["animate"] = [{"first": "context.is_first_person && !variable.infinity_using"},
                              {"draw": "context.is_first_person && variable.infinity_using"},
                              {"third": "!context.is_first_person"}]
    elif path == "shield":
        scripts["pre_animation"] = [
            "variable.infinity_blocking_main = " + shielding("mainhand") + ";",
            "variable.infinity_blocking_off = " + shielding("offhand") + ";",
            "variable.infinity_blocking = context.item_slot == 'off_hand' ? variable.infinity_blocking_off : variable.infinity_blocking_main;"
        ]
        scripts["animate"] = [{"first": "context.is_first_person && !variable.infinity_blocking"},
                              {"block": "context.is_first_person && variable.infinity_blocking"},
                              {"third": "!context.is_first_person"}]
    return {"format_version": "1.10.0", "minecraft:attachable": {"description": {
        "identifier": namespace + ":" + path,
        "materials": {"default": "entity_alphatest", "enchanted": "entity_alphatest_glint"},
        "textures": textures, "geometry": geometries, "animations": names,
        "scripts": scripts, "render_controllers": ["controller.render.convergence." + path + "_held_original"]}}}


def render_controller(path: str):
    value = {"geometry": "Geometry.default",
             "materials": [{"*": "variable.is_enchanted ? Material.enchanted : Material.default"}],
             "textures": ["Texture.default", "Texture.enchanted"]}
    if path in STAGES:
        frames = STAGES[path]
        array = "Array.infinity_" + path + "_held"
        value["arrays"] = {"geometries": {array: ["Geometry.default" if i == 0 else "Geometry." + stage
                                                 for i, stage in enumerate(frames)]}}
        # Mojang's native samples distinguish crossbow arrow/rocket at 4/5.
        # Actual custom-shooter client frame selection remains unproven; an
        # out-of-contract value must not masquerade as a loaded projectile.
        last = str(len(frames) - 1)
        value["geometry"] = array + "[query.get_animation_frame >= 0 && query.get_animation_frame <= " + last + " ? query.get_animation_frame : 0]"
    return value


def build_resources():
    """Pure, deterministic resource generation; does not read native assets."""
    resources = {}
    anim = {}
    controllers = {}
    for path in ITEMS:
        frames = STAGES.get(path, ("standby",))
        resources["models/entity/original_tools/" + path + ".geo.json"] = {
            "format_version": "1.19.30", "minecraft:geometry": [geometry(path, stage) for stage in frames]}
        for namespace, prefix in (("convergence", ""), ("infinity_catalog", "catalog_")):
            resources["attachables/held/" + prefix + path + ".json"] = attachable(path, namespace)
        anim.update(animations(path))
        controllers["controller.render.convergence." + path + "_held_original"] = render_controller(path)
    resources["animations/original_tools.json"] = {"format_version": "1.8.0", "animations": anim}
    resources["render_controllers/original_tools.json"] = {"format_version": "1.8.0", "render_controllers": controllers}
    return resources


def encoded_resources():
    return {path: (json.dumps(body, indent=2) + "\n").encode() for path, body in build_resources().items()}


def validate(resources=None, texture_root=BED):
    """Reject unresolved references, oversized sprites and native replacements.

    This is structural resource validation, not a rendered gameplay claim.
    """
    resources = build_resources() if resources is None else resources
    geometry_ids = set()
    for name, body in resources.items():
        if name.startswith("models/"):
            for geo in body["minecraft:geometry"]:
                desc = geo["description"]
                assert desc["identifier"].startswith("geometry.convergence.")
                assert desc["identifier"] not in geometry_ids
                geometry_ids.add(desc["identifier"])
                assert (desc["texture_width"], desc["texture_height"]) == (64, 64)
                assert len(geo["bones"]) == 1 and geo["bones"][0]["binding"] == BINDING
                assert geo["bones"][0]["name"] == BONE
    available_animations = resources["animations/original_tools.json"]["animations"]
    available_controllers = resources["render_controllers/original_tools.json"]["render_controllers"]
    identifiers = set()
    for name, body in resources.items():
        if not name.startswith("attachables/"):
            continue
        desc = body["minecraft:attachable"]["description"]
        assert desc["identifier"].split(":")[0] in ("convergence", "infinity_catalog")
        assert desc["identifier"] not in identifiers
        identifiers.add(desc["identifier"])
        assert set(desc["geometry"].values()) <= geometry_ids
        assert set(desc["animations"].values()) <= set(available_animations)
        assert set(desc["render_controllers"]) <= set(available_controllers)
        for key, texture in desc["textures"].items():
            if key == "enchanted":
                assert texture == "textures/misc/enchanted_item_glint"
                continue
            assert texture.startswith("textures/convergence/items/refresh27/")
            data = (Path(texture_root) / (texture + ".png")).read_bytes()
            assert data[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack(">II", data[16:24]) == (64, 64)
        for ref in desc["geometry"].values():
            geo = next(g for filename, value in resources.items() if filename.startswith("models/")
                       for g in value["minecraft:geometry"] if g["description"]["identifier"] == ref)
            assert all(mesh["texture"] in desc["textures"] for b in geo["bones"] for mesh in b.get("texture_meshes", []))
        for ref in desc["animations"].values():
            assert set(available_animations[ref]["bones"]) == {BONE}
        controller = available_controllers[desc["render_controllers"][0]]
        if "arrays" in controller:
            refs = next(iter(controller["arrays"]["geometries"].values()))
            assert all(ref.split(".", 1)[1] in desc["geometry"] for ref in refs)
    assert len(identifiers) == len(ITEMS) * 2
    return {"files": len(resources), "attachables": len(identifiers), "geometries": len(geometry_ids),
            "animations": len(available_animations), "render_controllers": len(available_controllers)}


def generate(destination=BED):
    resources = build_resources()
    validate(resources)
    destination = Path(destination)
    for name, body in encoded_resources().items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
    return tuple(destination / name for name in resources)


if __name__ == "__main__":
    generate()
    print(json.dumps(validate(), sort_keys=True))
