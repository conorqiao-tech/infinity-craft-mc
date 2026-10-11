"""Mechanically crop generated artwork into item sprites, cube tiles and armor UVs.

No vanilla PNG is used as a source. Unused UV cells stay transparent; opaque face
holes sample the closest opaque pixel from that same generated face.
"""
from pathlib import Path
import hashlib
import json
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = Path(__file__).resolve().parent
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
UV = {
    "head_top": (8, 0, 16, 8), "head_bottom": (16, 0, 24, 8),
    "head_right": (0, 8, 8, 16), "head_front": (8, 8, 16, 16),
    "head_left": (16, 8, 24, 16), "head_back": (24, 8, 32, 16),
    "leg_top": (4, 16, 8, 20), "leg_bottom": (8, 16, 12, 20),
    "leg_right": (0, 20, 4, 32), "leg_front": (4, 20, 8, 32),
    "leg_left": (8, 20, 12, 32), "leg_back": (12, 20, 16, 32),
    "body_top": (20, 16, 28, 20), "body_bottom": (28, 16, 36, 20),
    "body_right": (16, 20, 20, 32), "body_front": (20, 20, 28, 32),
    "body_left": (28, 20, 32, 32), "body_back": (32, 20, 40, 32),
    "arm_top": (44, 16, 48, 20), "arm_bottom": (48, 16, 52, 20),
    "arm_right": (40, 20, 44, 32), "arm_front": (44, 20, 48, 32),
    "arm_left": (48, 20, 52, 32), "arm_back": (52, 20, 56, 32),
}
SOURCE_FACES = {
    "head_top": (11, 0, 21, 4), "head_bottom": (11, 9, 21, 12),
    "head_right": (1, 4, 10, 12), "head_front": (10, 2, 22, 12),
    "head_left": (22, 4, 31, 12), "head_back": (10, 2, 22, 12),
    "leg_top": (1, 13, 7, 17), "leg_bottom": (1, 28, 7, 31),
    "leg_right": (0, 15, 4, 31), "leg_front": (2, 13, 7, 31),
    "leg_left": (7, 15, 10, 31), "leg_back": (10, 13, 15, 31),
    "body_top": (26, 13, 38, 17), "body_bottom": (26, 28, 38, 31),
    "body_right": (18, 14, 24, 31), "body_front": (24, 14, 40, 31),
    "body_left": (40, 14, 44, 31), "body_back": (24, 14, 40, 31),
    "arm_top": (47, 13, 53, 17), "arm_bottom": (47, 28, 53, 31),
    "arm_right": (44, 14, 48, 31), "arm_front": (48, 14, 54, 31),
    "arm_left": (54, 14, 58, 31), "arm_back": (58, 14, 63, 31),
}
ICON_ROWS = [
    ["helmet", "cuirass", "chestplate", "leggings", "boots", "sword", "mace"],
    ["aurora_helmet", "aurora_chestplate", "aurora_leggings", "aurora_boots", "spear", "shield", "pickaxe"],
    ["axe", "shovel", "hoe", "ingot", "totem", "bow", "crossbow"],
    ["ember_helmet", "ember_chestplate", "ember_leggings", "ember_boots", "backpack", "infinity_arrow", "void_arrow"],
    ["starfire_arrow", "builder_wand", "sculptor_wand", "blink_wand", "party_wand", "plane_remote", "blueprint_wand"],
    ["storm_staff", "gust_glove", "anchor_charm", "tidewarden_helmet", "tidewarden_chestplate", "tidewarden_leggings", "tidewarden_boots"],
]
BLOCKS = ["infinity_block", "infinity_bricks", "polished_infinity", "radiant_infinity", "aurora_tiles",
          "obsidian_lattice", "copper_circuit", "moonstone", "sunstone_lamp", "verdant_mosaic"]


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def save(image, *paths):
    for path in paths:
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path)


def cell(image, col, row, cols, rows):
    return image.crop((round(col * image.width / cols), round(row * image.height / rows),
                       round((col + 1) * image.width / cols), round((row + 1) * image.height / rows)))


def opaque_face(tile):
    pixels = tile.load()
    opaque = [(x, y) for y in range(tile.height) for x in range(tile.width) if pixels[x, y][3] >= 128]
    assert opaque, "Generated UV face contains no artwork"
    for y in range(tile.height):
        for x in range(tile.width):
            rgba = pixels[x, y]
            if rgba[3] < 128:
                sx, sy = min(opaque, key=lambda p: (p[0] - x) ** 2 + (p[1] - y) ** 2)
                rgba = pixels[sx, sy]
            pixels[x, y] = (*rgba[:3], 255)
    return tile


def sprite_bounds(image, col, row):
    """Find the row's connected sprite, excluding scraps from neighboring rows.

    A small vertical search overlap recovers tall sprites such as the anchor's
    ring. Separate boots/legs are retained when they occupy the same row.
    """
    x0, x1 = round(col * image.width / 7), round((col + 1) * image.width / 7)
    y0, y1 = round(row * image.height / 6), round((row + 1) * image.height / 6)
    region = image.crop((x0, max(0, y0 - 40), x1, min(image.height, y1 + 40)))
    origin = max(0, y0 - 40)
    alpha = region.getchannel("A")
    occupied = {(x, y) for y in range(region.height) for x in range(region.width) if alpha.getpixel((x, y)) >= 32}
    groups = []
    while occupied:
        start = min(occupied, key=lambda p: (p[1], p[0]))
        occupied.remove(start); pending = [start]; points = []
        while pending:
            x, y = pending.pop(); points.append((x, y))
            for neighbor in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if neighbor in occupied:
                    occupied.remove(neighbor); pending.append(neighbor)
        xs, ys = zip(*points)
        groups.append((len(points), (min(xs), min(ys), max(xs) + 1, max(ys) + 1)))
    candidates = [group for group in groups if y0 + 20 <= origin + (group[1][1] + group[1][3]) / 2 <= y1 - 20]
    primary = max(candidates, key=lambda group: group[0])[1]
    same_row = [box for count, box in groups if count >= 8 and primary[1] <= (box[1] + box[3]) / 2 <= primary[3]]
    box = (min(b[0] for b in same_row), min(b[1] for b in same_row), max(b[2] for b in same_row), max(b[3] for b in same_row))
    return (x0 + max(0, box[0] - 2), origin + max(0, box[1] - 2),
            x0 + min(region.width, box[2] + 2), origin + min(region.height, box[3] + 2))


def main():
    manifest = {"format": 1, "mode": "imagegen source; mechanical nearest-neighbor crop/UV packing",
                "armor": {}, "icons": {}, "blocks": {}}
    armor = Image.open(ART / "armor-source.png").convert("RGBA")
    armor_preview = Image.new("RGBA", (512, 256), "#202734")
    for i, (collection, asset) in enumerate([("convergence", "armor"), ("aurora", "aurora_armor"),
                                           ("ember", "ember_armor"), ("backpack", "backpack")]):
        source = cell(armor, i % 2, i // 2, 2, 2)
        out = Image.new("RGBA", (128, 64))
        for face, destination in UV.items():
            box = SOURCE_FACES[face]
            pixels = tuple(round(v * (source.width / 64 if n % 2 == 0 else source.height / 32)) for n, v in enumerate(box))
            target = tuple(v * 2 for v in destination)
            tile = source.crop(pixels).resize((target[2] - target[0], target[3] - target[1]), Image.Resampling.NEAREST)
            out.paste(opaque_face(tile), target[:2])
        name = collection + "_refresh27"
        for layer in ("humanoid", "humanoid_leggings"):
            save(out, JAVA / f"textures/entity/equipment/{layer}/{name}.png")
        save(out, BED / f"textures/convergence/models/armor/refresh27/{name}.png")
        equipment_path = JAVA / f"equipment/{asset}.json"
        equipment = json.loads(equipment_path.read_text())
        for layer in ("humanoid", "humanoid_leggings"):
            equipment["layers"][layer] = [{"texture": "convergence:" + name}]
        write_json(equipment_path, equipment)
        armor_preview.alpha_composite(out.resize((256, 128), Image.Resampling.NEAREST), (i % 2 * 256, i // 2 * 128))
        manifest["armor"][collection] = {"size": [128, 64], "uv_rectangles": UV, "source_rectangles": SOURCE_FACES}
    save(armor_preview, ART / "armor-uv-preview.png")

    # Model/equipment identifiers are custom, so native leather and netherite stay untouched.
    wearables = {"helmet": ("convergence", "helmet"), "cuirass": ("convergence", "chestplate"),
                 "leggings": ("convergence", "leggings"), "boots": ("convergence", "boots"), "backpack": ("backpack", "chestplate")}
    for outfit in ("aurora", "ember"):
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            wearables[outfit + "_" + piece] = (outfit, piece)
    for path, (collection, piece) in wearables.items():
        variable = {"helmet": "helmet", "chestplate": "chest", "leggings": "leg", "boots": "boot"}[piece]
        description = {"identifier": "convergence:" + path,
                       "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                       "textures": {"default": f"textures/convergence/models/armor/refresh27/{collection}_refresh27",
                                    "enchanted": "textures/misc/enchanted_actor_glint"},
                       "geometry": {"default": "geometry.humanoid.armor." + piece},
                       "scripts": {"parent_setup": f"variable.{variable}_layer_visible = 0.0;"},
                       "render_controllers": ["controller.render.armor"]}
        write_json(BED / f"attachables/refresh27/{path}.json", {"format_version": "1.10.0", "minecraft:attachable": {"description": description}})
    # Keep the established native flight animations and wing geometry intact.
    wings_path = BED / "attachables/elytra.json"
    wings = json.loads(wings_path.read_text())
    wings["minecraft:attachable"]["description"]["textures"]["infinity_armor"] = "textures/convergence/models/armor/refresh27/convergence_refresh27"
    write_json(wings_path, wings)

    atlas = json.loads((BED / "textures/item_texture.json").read_text())
    icons = Image.open(ART / "icons-source.png").convert("RGBA")
    icon_preview = Image.new("RGBA", (7 * 128, 6 * 128), "#202734")
    for row, names in enumerate(ICON_ROWS):
        for col, name in enumerate(names):
            bounds = sprite_bounds(icons, col, row)
            sprite = icons.crop(bounds)
            sprite.thumbnail((56, 56), Image.Resampling.NEAREST)
            icon = Image.new("RGBA", (64, 64))
            icon.paste(sprite, ((64 - sprite.width) // 2, (64 - sprite.height) // 2))
            save(icon, JAVA / f"textures/item/refresh27/{name}.png", BED / f"textures/convergence/items/refresh27/{name}.png")
            model_path = JAVA / f"models/item/{name}.json"
            model = json.loads(model_path.read_text()) if model_path.exists() else {"parent": "minecraft:item/generated", "textures": {}}
            model["textures"]["layer0"] = "convergence:item/refresh27/" + name
            write_json(model_path, model)
            write_json(JAVA / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": "convergence:item/" + name}})
            atlas["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/items/refresh27/" + name}
            icon_preview.alpha_composite(icon.resize((128, 128), Image.Resampling.NEAREST), (col * 128, row * 128))
            manifest["icons"][name] = {"cell": [col, row], "source_bounds": bounds, "size": [64, 64]}
    # Native wings are shared with ordinary elytra on Bedrock; retained flight takes priority.
    wing_icon = Image.open(JAVA / "textures/item/refresh27/chestplate.png")
    save(wing_icon, BED / "textures/items/elytra.png", BED / "textures/items/broken_elytra.png")
    save(icon_preview, ART / "inventory-preview.png")

    terrain = json.loads((BED / "textures/terrain_texture.json").read_text())
    blocks = Image.open(ART / "blocks-source.png").convert("RGBA")
    block_preview = Image.new("RGBA", (5 * 192, 2 * 192))
    for index, name in enumerate(BLOCKS):
        tile = cell(blocks, index % 5, index // 5, 5, 2).resize((32, 32), Image.Resampling.NEAREST)
        save(tile, JAVA / f"textures/block/refresh27/{name}.png", BED / f"textures/convergence/blocks/refresh27/{name}.png")
        write_json(JAVA / f"models/block/{name}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "convergence:block/refresh27/" + name}})
        atlas["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/blocks/refresh27/" + name}
        terrain["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/blocks/refresh27/" + name}
        for y in range(3):
            for x in range(3):
                block_preview.paste(tile.resize((64, 64), Image.Resampling.NEAREST), ((index % 5 * 3 + x) * 64, (index // 5 * 3 + y) * 64))
        manifest["blocks"][name] = {"cell": [index % 5, index // 5], "size": [32, 32]}
    write_json(BED / "textures/item_texture.json", atlas)
    write_json(BED / "textures/terrain_texture.json", terrain)
    save(block_preview, ART / "block-tiling-preview.png")
    manifest["sha256"] = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                          for directory in (JAVA / "textures", BED / "textures") for p in directory.rglob("*.png") if "refresh27" in p.parts}
    manifest["source_sha256"] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in ART.glob("*-source.png")}
    write_json(ART / "uv-manifest.json", manifest)
    print("Packed 4 armor UV atlases, 42 distinct icons, 10 block tiles and 13 custom static attachables; originals retained.")


if __name__ == "__main__":
    main()
