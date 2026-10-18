"""Mechanically pack four independently generated transparent relic sprites.

Requires Pillow for authoring. The game and the stdlib validator do not.
Only the four new texture pairs, previews/provenance, four existing model layer0
values and four existing item-atlas texture values are written by main().
"""
import binascii
import copy
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
ITEMS = {"windstep": "Windstep", "firebrand": "Firebrand", "tidecaller": "Tidecaller",
         "terra_gauntlet": "Terra Gauntlet"}
PROMPTS = ART / "exact-prompts.json"
BACKGROUND = (12, 19, 32, 255)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path):
    return path.relative_to(ROOT).as_posix()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def save_png(image, path):
    """Deterministic RGBA/filter-zero writer shared with the earlier art payloads."""
    image = image.convert("RGBA")
    pixels = image.tobytes()
    stride = image.width * 4
    raw = b"".join(b"\0" + pixels[y * stride:(y + 1) * stride] for y in range(image.height))

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", binascii.crc32(kind + payload) & 0xffffffff))

    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">2I5B", image.width, image.height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def json_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def remainder(value, kind):
    """Fingerprint every JSON field except the explicitly authorized texture values."""
    value = copy.deepcopy(value)
    if kind == "model":
        del value["textures"]["layer0"]
    else:
        for name in ITEMS:
            del value["texture_data"]["convergence_" + name]["textures"]
    return json_digest(value)


def preserved_paths():
    paths = [ROOT / "java/src/main/java/dev/convergence" / (name + ".java")
             for name in ("ElementalRelics", "ElementalForgeMenu", "GearNames", "CrossplaySupport")]
    paths.append(JAVA / "lang/en_us.json")
    for name in ITEMS:
        paths.extend((JAVA / f"items/{name}.json",
                      ROOT / f"java/src/main/resources/data/convergence/recipe/{name}.json",
                      ROOT / f"java/src/main/resources/data/convergence/advancement/recipes/elemental41/{name}.json",
                      JAVA / f"textures/item/elemental41/{name}.png",
                      BED / f"textures/convergence/items/elemental41/{name}.png"))
    return paths


def sprite(source):
    """Threshold, crop, quantize and rescale generated pixels; never redraw them."""
    source = source.convert("RGBA")
    assert 0 < source.width <= 4096 and 0 < source.height <= 4096
    clean = Image.new("RGBA", source.size)
    clean.putdata([(*pixel[:3], 255) if pixel[3] >= 128 else (0, 0, 0, 0)
                   for pixel in source.getdata()])
    bounds = clean.getchannel("A").getbbox()
    assert bounds, "Generated source has no opaque silhouette after alpha threshold128"
    cropped = clean.crop(bounds)
    # Train one RGB palette for this image. Restore the binary source mask after
    # palette reduction so alpha cannot be softened or promoted by quantization.
    reduced = cropped.convert("RGB").quantize(colors=32, method=Image.Quantize.MEDIANCUT,
                                               dither=Image.Dither.NONE).convert("RGBA")
    reduced.putalpha(cropped.getchannel("A"))
    ratio = min(56 / reduced.width, 56 / reduced.height)
    size = (max(1, min(56, round(reduced.width * ratio))),
            max(1, min(56, round(reduced.height * ratio))))
    reduced = reduced.resize(size, Image.Resampling.NEAREST)
    icon = Image.new("RGBA", (64, 64))
    icon.paste(reduced, ((64 - size[0]) // 2, (64 - size[1]) // 2))
    icon.putdata([pixel if pixel[3] else (0, 0, 0, 0) for pixel in icon.getdata()])
    assert icon.getchannel("A").getbbox(), "Generated silhouette vanished during nearest-neighbor scaling"
    return icon, list(bounds), list(size)


def main():
    prompts = json.loads(PROMPTS.read_text())
    prompt_values = prompts.get("prompts", prompts)
    assert all(isinstance(prompt_values.get(name), str) and prompt_values[name].strip() for name in ITEMS), \
        "Retain each exact imagegen prompt under its item ID"
    if "generation_tool" in prompts:
        assert prompts["generation_tool"] == "image_gen.imagegen"
    if "transparent_background" in prompts:
        assert prompts["transparent_background"] is True
    preserved = {relative(path): digest(path) for path in preserved_paths()}
    atlas_path = BED / "textures/item_texture.json"
    atlas = json.loads(atlas_path.read_text())
    model_paths = {name: JAVA / f"models/item/{name}.json" for name in ITEMS}
    models = {name: json.loads(path.read_text()) for name, path in model_paths.items()}
    remainders = {relative(atlas_path): remainder(atlas, "atlas")}
    remainders.update({relative(model_paths[name]): remainder(model, "model") for name, model in models.items()})
    manifest = {"format_version": 1, "generation_tool": "image_gen.imagegen",
                "generation_options": {"transparent_background": True},
                "processing": {"alpha_threshold": 128, "palette_max_colors": 32,
                               "palette_method": "RGB MEDIANCUT, no dithering",
                               "crop": "opaque alpha bounding box; no component removal",
                               "fit_box": [56, 56], "canvas": [64, 64], "resampling": "NEAREST"},
                "items": {}, "sha256": {}, "preserved_sha256": preserved,
                "reference_remainders_sha256": remainders,
                "preview_order": list(ITEMS), "preview_background": list(BACKGROUND),
                "preview_sizes": [64, 128, 16], "preview_is_client_capture": False,
                "gameplay_or_native_equipment_changed": False}
    outputs = [PROMPTS, Path(__file__), ART / "validate.py", ART / "README.md"]
    icons = []
    # Complete all image processing before writing shared resource references.
    for name, label in ITEMS.items():
        source_path = ART / f"sources/{name}.png"
        with Image.open(source_path) as image:
            source_size = list(image.size)
            assert "A" in image.getbands(), "Imagegen source must retain transparency"
            icon, bounds, fitted_size = sprite(image)
        icons.append(icon)
        pixels = list(icon.getdata())
        manifest["items"][name] = {"label": label, "source": relative(source_path),
                                   "source_size": source_size, "source_bounds": bounds,
                                   "fitted_size": fitted_size, "size": [64, 64],
                                   "pixel_bounds": list(icon.getchannel("A").getbbox()),
                                   "opaque_pixels": sum(pixel[3] == 255 for pixel in pixels),
                                   "opaque_colors": len({pixel[:3] for pixel in pixels if pixel[3]}),
                                   "java_texture": f"convergence:item/relic44/{name}",
                                   "bedrock_texture": f"textures/convergence/items/relic44/{name}"}
        outputs.append(source_path)
    for (name, _label), icon in zip(ITEMS.items(), icons):
        for path in (JAVA / f"textures/item/relic44/{name}.png", BED / f"textures/convergence/items/relic44/{name}.png"):
            save_png(icon, path)
            outputs.append(path)
        models[name]["textures"]["layer0"] = manifest["items"][name]["java_texture"]
        atlas["texture_data"]["convergence_" + name]["textures"] = manifest["items"][name]["bedrock_texture"]
    for size in manifest["preview_sizes"]:
        preview = Image.new("RGBA", (len(ITEMS) * size, size), BACKGROUND)
        for index, icon in enumerate(icons):
            preview.alpha_composite(icon.resize((size, size), Image.Resampling.NEAREST), (index * size, 0))
        path = ART / f"preview-{size}.png"
        save_png(preview, path)
        outputs.append(path)
    for name, model in models.items():
        assert remainder(model, "model") == remainders[relative(model_paths[name])]
        write_json(model_paths[name], model)
        outputs.append(model_paths[name])
    assert remainder(atlas, "atlas") == remainders[relative(atlas_path)]
    write_json(atlas_path, atlas)
    outputs.append(atlas_path)
    assert {relative(path): digest(path) for path in preserved_paths()} == preserved, \
        "Historical sprites, native item names/definitions, recipes and gameplay must remain byte-identical"
    manifest["sha256"] = {relative(path): digest(path) for path in outputs}
    write_json(ART / "manifest.json", manifest)
    print("Packed four original relic icons: matching Java/Bedrock64×64RGBA, binary alpha, <=32colors; actualpixelpreviews64/128/16. No gameplay/native equipment changed.")


if __name__ == "__main__":
    main()
