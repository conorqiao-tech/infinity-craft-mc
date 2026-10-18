"""Pack the original Astral Wilds raster sheet; no previous artwork is read.

Only mechanical alpha cleanup, bounded cropping, palette reduction and nearest-
neighbor scaling are applied. Requires Pillow for authoring, not for gameplay.
All paths are relative to this staged/source root, so the same packer works when
the complete reviewed payload is subsequently placed in a source checkout.
"""
import binascii
import hashlib
import json
from pathlib import Path
import struct
import zlib

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = Path(__file__).resolve().parent
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
ITEMS = (("starshard_lantern", "Starshard Lantern"), ("riftspool", "Riftspool"),
         ("keepers_trowel", "Keeper's Trowel"))


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def save_png(image, path):
    image = image.convert("RGBA"); raw = image.tobytes(); stride = image.width * 4
    filtered = b"".join(b"\0" + raw[y * stride:(y + 1) * stride] for y in range(image.height))
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", binascii.crc32(kind + payload) & 0xffffffff)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">2I5B", image.width, image.height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(filtered, 9)) + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)


def sprite(image):
    result = image.convert("RGBA"); raw = result.tobytes()
    result.putdata([tuple(raw[i:i + 3]) + (255,) if raw[i + 3] >= 128 else (0, 0, 0, 0)
                    for i in range(0, len(raw), 4)])
    # Suppress tiny detached antialias scraps while retaining the generated
    # ribbon and substantial star motes as separate transparent components.
    pixels = result.load()
    remaining = {(x, y) for y in range(result.height) for x in range(result.width) if pixels[x, y][3]}
    while remaining:
        start = remaining.pop(); todo = [start]; group = []
        while todo:
            x, y = todo.pop(); group.append((x, y))
            for point in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if point in remaining:
                    remaining.remove(point); todo.append(point)
        if len(group) < 8:
            for point in group: pixels[point] = (0, 0, 0, 0)
    bounds = result.getchannel("A").getbbox(); assert bounds
    result = result.crop(bounds)
    result = result.quantize(colors=64, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE).convert("RGBA")
    scale = min(58 / result.width, 58 / result.height)
    result = result.resize((max(1, round(result.width * scale)), max(1, round(result.height * scale))), Image.Resampling.NEAREST)
    icon = Image.new("RGBA", (64, 64))
    icon.paste(result, ((64 - result.width) // 2, (64 - result.height) // 2))
    return icon, bounds


def main():
    source_path = ART / "source-sheet.png"
    source = Image.open(source_path).convert("RGBA")
    assert source.size == (2172, 724), "Source cells were measured against this exact native imagegen output."
    manifest = {"format_version": 1, "generation_tool": "image_gen.imagegen",
                "generation_options": {"transparent_background": True},
                "source": "source-sheet.png", "source_size": list(source.size),
                "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                "prompt": "prompt.txt", "prompt_sha256": hashlib.sha256((ART / "prompt.txt").read_bytes()).hexdigest(),
                "packer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                "processing": "Measured cell cropping; alpha threshold128; remove detached scraps<8source pixels; adaptive64-color palette; nearest-neighbor fit58inside64",
                "items": {}, "sha256": {}, "preview_is_client_capture": False}
    preview = Image.new("RGBA", (384, 128), (12, 19, 32, 255))
    outputs = []; atlas = {}
    for index, (name, label) in enumerate(ITEMS):
        cell = (index * 724, 0, (index + 1) * 724, 724)
        icon, bounds = sprite(source.crop(cell))
        texture = "convergence:item/astral43/" + name
        for path in (JAVA / f"textures/item/astral43/{name}.png", BED / f"textures/convergence/items/astral43/{name}.png"):
            save_png(icon, path); outputs.append(path)
        model = JAVA / f"models/item/{name}.json"
        definition = JAVA / f"items/{name}.json"
        write_json(model, {"parent": "minecraft:item/generated", "textures": {"layer0": texture}})
        write_json(definition, {"model": {"type": "minecraft:model", "model": "convergence:item/" + name}})
        outputs.extend((model, definition))
        atlas["convergence_" + name] = {"textures": "textures/convergence/items/astral43/" + name}
        manifest["items"][name] = {"label": label, "size": [64, 64], "cell": list(cell),
                                   "source_bounds_in_cell": list(bounds), "java_texture": texture,
                                   "bedrock_texture": atlas["convergence_" + name]["textures"]}
        preview.alpha_composite(icon.resize((128, 128), Image.Resampling.NEAREST), (index * 128, 0))
    path = ART / "preview.png"; save_png(preview, path); outputs.append(path)
    path = ART / "bedrock-atlas-entries.json"; write_json(path, atlas); outputs.append(path)
    for path in outputs: manifest["sha256"][str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    write_json(ART / "manifest.json", manifest)
    print("Packed three original64×64AstralWilds icons with matching Java/Bedrock pixels, item/model definitions and separate atlas entries.")


if __name__ == "__main__":
    main()
