"""Mechanically pack original imagegen armor42 art into Minecraft resources.

This is image processing, not image painting: every visible source pixel comes
from source-sheet.png. Cropping, shared palette quantization, alpha cleanup and
nearest-neighbor UV packing are reproducible. No previous armor image/model is
loaded as an art source. The UV numbers are the equipment format contract.
Requires Pillow; gameplay and resource consumption do not require Pillow.
"""
from pathlib import Path
import binascii
import hashlib
import json
import struct
import zlib

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = Path(__file__).resolve().parent
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
SUITS = ("convergence", "aurora", "ember", "elemental", "infinity")
PIECES = ("helmet", "chestplate", "leggings", "boots")
ROWS = ((0, 197), (197, 392), (392, 585), (585, 777), (777, 977))
ICON_COLUMNS = ((25, 211), (216, 449), (451, 609), (612, 808))
PANEL_COLUMNS = ((827, 994), (1023, 1195), (1223, 1391), (1423, 1564))
PANEL_ROWS = ((21, 180), (212, 374), (409, 566), (600, 755), (789, 951))
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


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def save_png(image, path):
    """Use deterministic RGBA/filter-zero PNGs accepted by existing validators."""
    image = image.convert("RGBA")
    pixels = image.tobytes()
    stride = image.width * 4
    raw = b"".join(b"\0" + pixels[y * stride:(y + 1) * stride] for y in range(image.height))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">2I5B", image.width, image.height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def alpha_cleanup(image):
    result = image.convert("RGBA")
    raw = result.tobytes()
    result.putdata([tuple(raw[i:i + 3]) + (255,) if raw[i + 3] >= 128 else (0, 0, 0, 0)
                    for i in range(0, len(raw), 4)])
    return result


def solid_panel(image):
    """Repair alpha pinholes by nearest opaque pixel in the same generated tile."""
    result = image.convert("RGBA")
    pixels = result.load()
    opaque = [(x, y) for y in range(result.height) for x in range(result.width) if pixels[x, y][3] >= 128]
    assert opaque, "Generated material panel contains no artwork"
    for y in range(result.height):
        for x in range(result.width):
            rgba = pixels[x, y]
            if rgba[3] < 128:
                sx, sy = min(opaque, key=lambda p: (p[0] - x) ** 2 + (p[1] - y) ** 2)
                rgba = pixels[sx, sy]
            pixels[x, y] = (*rgba[:3], 255)
    return result


def sprite(source, box):
    image = alpha_cleanup(source.crop(box))
    # The generated sheet includes isolated subpixel scraps outside a few
    # silhouettes. Retain every substantive component (including paired boots).
    pixels = image.load()
    remaining = {(x, y) for y in range(image.height) for x in range(image.width) if pixels[x, y][3]}
    groups = []
    while remaining:
        start = remaining.pop(); pending = [start]; points = []
        while pending:
            x, y = pending.pop(); points.append((x, y))
            for neighbor in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if neighbor in remaining:
                    remaining.remove(neighbor); pending.append(neighbor)
        groups.append(points)
    primary = max(groups, key=len)
    top, bottom = min(y for x, y in primary), max(y for x, y in primary)
    for points in groups:
        center = (min(y for x, y in points) + max(y for x, y in points)) / 2
        if len(points) < 8 or not top <= center <= bottom:
            for point in points:
                pixels[point] = (0, 0, 0, 0)
    bounds = image.getchannel("A").getbbox()
    assert bounds, box
    image = image.crop(bounds)
    ratio = min(58 / image.width, 58 / image.height)
    size = (max(1, round(image.width * ratio)), max(1, round(image.height * ratio)))
    image = image.resize(size, Image.Resampling.NEAREST)
    out = Image.new("RGBA", (64, 64))
    out.paste(image, ((64 - image.width) // 2, (64 - image.height) // 2))
    return out


def panel_face(panel, face):
    """Use matching generated face areas; do not synthesize decorative pixels."""
    w, h = panel.size
    if face.endswith("_top"):
        box = (0, 0, w, max(1, h // 5))
    elif face.endswith("_bottom"):
        box = (0, h * 4 // 5, w, h)
    elif face.endswith("_right"):
        box = (0, 0, max(1, w // 3), h)
    elif face.endswith("_left"):
        box = (w * 2 // 3, 0, w, h)
    else:
        box = (0, 0, w, h)
    return panel.crop(box)


def active_references():
    """Change only the five sets' texture references, retaining all other data."""
    equipment_names = {"convergence": ("armor", "cuirass"), "aurora": ("aurora_armor",),
                       "ember": ("ember_armor",), "elemental": ("elemental_armor",), "infinity": ("infinity_armor",)}
    changed = []
    atlas_path = BED / "textures/item_texture.json"
    atlas = json.loads(atlas_path.read_text())
    for suit in SUITS:
        texture = "convergence:armor42/" + suit
        for name in equipment_names[suit]:
            path = JAVA / f"equipment/{name}.json"
            data = json.loads(path.read_text())
            for layer in ("humanoid", "humanoid_leggings"):
                data["layers"][layer][0]["texture"] = texture
            write_json(path, data); changed.append(str(path.relative_to(ROOT)))
        for piece in PIECES:
            name = ("cuirass" if piece == "chestplate" else piece) if suit == "convergence" else suit + "_" + piece
            model_path = JAVA / f"models/item/{name}.json"
            model = json.loads(model_path.read_text())
            model["textures"]["layer0"] = "convergence:item/armor42/" + name
            write_json(model_path, model); changed.append(str(model_path.relative_to(ROOT)))
            atlas["texture_data"]["convergence_" + name]["textures"] = "textures/convergence/items/armor42/" + name
            folder = "refresh27" if suit in ("convergence", "aurora", "ember") else "elemental41"
            for prefix in ("", "catalog_"):
                path = BED / f"attachables/{folder}/{prefix}{name}.json"
                data = json.loads(path.read_text())
                data["minecraft:attachable"]["description"]["textures"]["default"] = "textures/convergence/models/armor/armor42/" + suit
                write_json(path, data); changed.append(str(path.relative_to(ROOT)))
    write_json(atlas_path, atlas); changed.append(str(atlas_path.relative_to(ROOT)))
    return changed


def main():
    source_path = ART / "source-sheet.png"
    source = Image.open(source_path).convert("RGBA")
    assert source.size == (1586, 992), "Layout was measured against this exact original imagegen source."
    manifest = {"format_version": 1, "mode": "built-in imagegen raster source; mechanical crop/palette/nearest-neighbor UV packing",
                "source": "source-sheet.png", "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                "prompt": "prompt.txt", "prompt_sha256": hashlib.sha256((ART / "prompt.txt").read_bytes()).hexdigest(),
                "packer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "generation_tool": "image_gen.imagegen", "generation_options": {"transparent_background": True},
                "source_size": list(source.size), "suits": {}, "sha256": {},
                "uv_rectangles": UV, "ordinary_armor_and_elytra_changed": False,
                "preview_is_client_capture": False}
    outputs = []
    preview = Image.new("RGBA", (1024, 960), (15, 22, 34, 255))
    uv_preview = Image.new("RGBA", (768, 320), (15, 22, 34, 255))
    for row, suit in enumerate(SUITS):
        y0, y1 = ROWS[row]
        # A narrow vertical search margin recovers tall crown tips; component
        # selection in sprite() excludes the neighboring row's fragments.
        sy0, sy1 = max(0, y0 - 10), min(source.height, y1 + 10)
        # One shared adaptive palette per row keeps icons and worn panels coherent.
        colors = alpha_cleanup(source.crop((0, sy0, source.width, sy1))).quantize(
            colors=64, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).convert("RGBA")
        row_image = Image.new("RGBA", source.size)
        row_image.paste(colors, (0, sy0))
        icons = {}
        icon_boxes = {}
        for column, piece in enumerate(PIECES):
            x0, x1 = ICON_COLUMNS[column]
            box = (x0, sy0, x1, sy1)
            icon = sprite(row_image, box)
            name = ("cuirass" if piece == "chestplate" else piece) if suit == "convergence" else suit + "_" + piece
            for path in (JAVA / f"textures/item/armor42/{name}.png", BED / f"textures/convergence/items/armor42/{name}.png"):
                save_png(icon, path); outputs.append(path)
            preview.alpha_composite(icon.resize((128, 128), Image.Resampling.NEAREST), (column * 160 + 12, row * 192 + 32))
            icons[name] = list(icon.size); icon_boxes[name] = list(box)
        panels = {}
        panel_boxes = {}
        for column, part in enumerate(("head", "body", "arm", "leg")):
            x0, x1 = PANEL_COLUMNS[column]; py0, py1 = PANEL_ROWS[row]
            # Sleeve/greave tiles each contain paired motifs; use one articulated plate.
            if part in ("arm", "leg"):
                x1 = x0 + (x1 - x0) // 2 - 2
            box = (x0, py0, x1, py1)
            panels[part] = solid_panel(row_image.crop(box)); panel_boxes[part] = list(box)
        worn = Image.new("RGBA", (128, 64))
        for face, rect in UV.items():
            target = tuple(value * 2 for value in rect)
            tile = panel_face(panels[face.split("_")[0]], face).resize(
                (target[2] - target[0], target[3] - target[1]), Image.Resampling.NEAREST)
            worn.paste(tile, target[:2])
        for layer in ("humanoid", "humanoid_leggings"):
            path = JAVA / f"textures/entity/equipment/{layer}/armor42/{suit}.png"
            save_png(worn, path); outputs.append(path)
        path = BED / f"textures/convergence/models/armor/armor42/{suit}.png"
        save_png(worn, path); outputs.append(path)
        preview.alpha_composite(worn.resize((384, 192), Image.Resampling.NEAREST), (640, row * 192))
        uv_preview.alpha_composite(worn.resize((256, 128), Image.Resampling.NEAREST), ((row % 3) * 256, (row // 3) * 160))
        manifest["suits"][suit] = {"icons": icons, "icon_source_rectangles": icon_boxes,
                                  "panel_source_rectangles": panel_boxes, "texture_size": [128, 64],
                                  "palette_colors": 64, "texture": "armor42/" + suit}
    for name, image in (("inventory-and-uv-preview.png", preview), ("uv-preview.png", uv_preview)):
        path = ART / name; save_png(image, path); outputs.append(path)
    manifest["texture_reference_files"] = active_references()
    for path in outputs:
        manifest["sha256"][str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    write_json(ART / "manifest.json", manifest)
    print(f"Packed 20 original 64×64 icons, five 128×64 worn atlases and Java/Bedrock reference updates; {len(outputs)} PNG outputs.")


if __name__ == "__main__":
    main()
