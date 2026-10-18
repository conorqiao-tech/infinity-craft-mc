"""Stdlib verification of the four original relic sprites; not a client render."""
import binascii
import copy
import hashlib
import json
from pathlib import Path
import struct
import zlib

ART = Path(__file__).resolve().parent
ROOT = ART.parents[1]
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
ITEMS = {"windstep": "Windstep", "firebrand": "Firebrand", "tidecaller": "Tidecaller",
         "terra_gauntlet": "Terra Gauntlet"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def png(path):
    """Decode bounded 8-bit RGBA PNGs, including generated-source filter types."""
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n" and len(data) <= 64 * 1024 * 1024, path
    at = 8
    packed = bytearray()
    width = height = None
    while at < len(data):
        assert at + 12 <= len(data), path
        length = struct.unpack(">I", data[at:at + 4])[0]
        assert at + 12 + length <= len(data), path
        kind = data[at + 4:at + 8]
        payload = data[at + 8:at + 8 + length]
        assert binascii.crc32(kind + payload) & 0xffffffff == struct.unpack(">I", data[at + 8 + length:at + 12 + length])[0], path
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">2I5B", payload)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0), path
            assert 0 < width <= 4096 and 0 < height <= 4096, path
        elif kind == b"IDAT":
            packed.extend(payload)
        at += length + 12
        if kind == b"IEND":
            break
    assert at == len(data) and width and height, path
    stride = width * 4
    decoder = zlib.decompressobj()
    raw = decoder.decompress(packed, height * (stride + 1) + 1)
    assert decoder.eof and not decoder.unconsumed_tail and not decoder.unused_data, path
    assert len(raw) == height * (stride + 1), path
    rows = []
    previous = bytearray(stride)
    for y in range(height):
        offset = y * (stride + 1)
        filter_type = raw[offset]
        assert filter_type in range(5), path
        row = bytearray(raw[offset + 1:offset + 1 + stride])
        for x in range(stride):
            a = row[x - 4] if x >= 4 else 0
            b = previous[x]
            c = previous[x - 4] if x >= 4 else 0
            if filter_type == 1:
                prediction = a
            elif filter_type == 2:
                prediction = b
            elif filter_type == 3:
                prediction = (a + b) // 2
            elif filter_type == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                prediction = a if pa <= pb and pa <= pc else b if pb <= pc else c
            else:
                prediction = 0
            row[x] = (row[x] + prediction) % 256
        rows.append(row)
        previous = row
    pixels = [tuple(row[x:x + 4]) for row in rows for x in range(0, stride, 4)]
    return width, height, pixels


def bounds(pixels, width, threshold=128):
    points = [(i % width, i // width) for i, pixel in enumerate(pixels) if pixel[3] >= threshold]
    assert points, "Sprite has no visible silhouette"
    xs, ys = zip(*points)
    return [min(xs), min(ys), max(xs) + 1, max(ys) + 1]


def remainder(value, kind):
    value = copy.deepcopy(value)
    if kind == "model":
        del value["textures"]["layer0"]
    else:
        for name in ITEMS:
            del value["texture_data"]["convergence_" + name]["textures"]
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def nearest_indices(source_size, target_size):
    """Match Pillow's incremental pixel-centre nearest-neighbor sampler.

    Incremental addition matters at exact boundaries: multiplication can round
    31.999999999999996 to32 and falsely report a changed source silhouette.
    """
    scale = source_size / target_size
    position = scale * .5
    indices = []
    for _ in range(target_size):
        indices.append(min(source_size - 1, int(position)))
        position += scale
    return indices


def validate():
    manifest = json.loads((ART / "manifest.json").read_text())
    assert manifest["format_version"] == 1
    assert manifest["generation_tool"] == "image_gen.imagegen"
    assert manifest["generation_options"] == {"transparent_background": True}
    assert manifest["preview_is_client_capture"] is False
    assert manifest["gameplay_or_native_equipment_changed"] is False
    assert set(manifest["items"]) == set(ITEMS)
    assert manifest["processing"]["alpha_threshold"] == 128
    assert manifest["processing"]["palette_max_colors"] == 32
    assert manifest["processing"]["fit_box"] == [56, 56]
    assert manifest["processing"]["canvas"] == [64, 64]
    assert manifest["processing"]["resampling"] == "NEAREST"
    expected_hashes = {f"artwork/relic44/{name}" for name in ("exact-prompts.json", "pack_art.py", "validate.py", "README.md")}
    expected_hashes |= {f"artwork/relic44/preview-{size}.png" for size in (64, 128, 16)}
    expected_hashes.add("bedrock/resource_pack/textures/item_texture.json")
    preserved = {"java/src/main/java/dev/convergence/" + name + ".java"
                 for name in ("ElementalRelics", "ElementalForgeMenu", "GearNames", "CrossplaySupport")}
    preserved.add("java/src/main/resources/assets/convergence/lang/en_us.json")
    for name in ITEMS:
        expected_hashes.update((f"artwork/relic44/sources/{name}.png",
                                f"java/src/main/resources/assets/convergence/textures/item/relic44/{name}.png",
                                f"bedrock/resource_pack/textures/convergence/items/relic44/{name}.png",
                                f"java/src/main/resources/assets/convergence/models/item/{name}.json"))
        preserved.update((f"java/src/main/resources/assets/convergence/items/{name}.json",
                          f"java/src/main/resources/data/convergence/recipe/{name}.json",
                          f"java/src/main/resources/data/convergence/advancement/recipes/elemental41/{name}.json",
                          f"java/src/main/resources/assets/convergence/textures/item/elemental41/{name}.png",
                          f"bedrock/resource_pack/textures/convergence/items/elemental41/{name}.png"))
    assert set(manifest["sha256"]) == expected_hashes
    assert set(manifest["preserved_sha256"]) == preserved
    for field in ("sha256", "preserved_sha256"):
        for name, expected in manifest[field].items():
            assert digest(ROOT / name) == expected, (field, name)
    prompts = json.loads((ART / "exact-prompts.json").read_text())
    prompt_values = prompts.get("prompts", prompts)
    assert all(isinstance(prompt_values.get(name), str) and prompt_values[name].strip() for name in ITEMS)
    atlas_path = BED / "textures/item_texture.json"
    atlas = json.loads(atlas_path.read_text())
    assert remainder(atlas, "atlas") == manifest["reference_remainders_sha256"][atlas_path.relative_to(ROOT).as_posix()]
    language = json.loads((JAVA / "lang/en_us.json").read_text())
    icons = {}
    for name, label in ITEMS.items():
        entry = manifest["items"][name]
        assert entry["label"] == label
        assert language["item.convergence." + name] == label
        source = ROOT / entry["source"]
        assert source == ART / f"sources/{name}.png"
        sw, sh, source_pixels = png(source)
        assert [sw, sh] == entry["source_size"]
        assert any(pixel[3] < 128 for pixel in source_pixels), (name, "Generated source must contain transparency")
        assert bounds(source_pixels, sw) == entry["source_bounds"]
        java = JAVA / f"textures/item/relic44/{name}.png"
        bedrock = BED / f"textures/convergence/items/relic44/{name}.png"
        assert java.read_bytes() == bedrock.read_bytes(), name
        width, height, pixels = png(java)
        assert (width, height) == (64, 64) and entry["size"] == [64, 64], name
        assert {pixel[3] for pixel in pixels} == {0, 255}, name
        assert all(pixel == (0, 0, 0, 0) for pixel in pixels if not pixel[3]), name
        actual_bounds = bounds(pixels, width)
        assert actual_bounds == entry["pixel_bounds"], name
        left, top, right, bottom = actual_bounds
        assert 4 <= left < right <= 60 and 4 <= top < bottom <= 60, (name, actual_bounds)
        assert sum(pixel[3] == 255 for pixel in pixels) == entry["opaque_pixels"], name
        colors = len({pixel[:3] for pixel in pixels if pixel[3]})
        assert 1 <= colors <= 32 and colors == entry["opaque_colors"], (name, colors)
        # Prove every output alpha pixel is the thresholded source sampled by
        # Pillow's pixel-centre nearest-neighbor mapping, with no
        # painted additions or clipped transparency margins.
        x0, y0, x1, y1 = entry["source_bounds"]
        fw, fh = entry["fitted_size"]
        ratio = min(56 / (x1 - x0), 56 / (y1 - y0))
        assert [fw, fh] == [max(1, min(56, round((x1 - x0) * ratio))),
                            max(1, min(56, round((y1 - y0) * ratio)))], name
        ox, oy = (64 - fw) // 2, (64 - fh) // 2
        source_x = nearest_indices(x1 - x0, fw)
        source_y = nearest_indices(y1 - y0, fh)
        for y in range(64):
            for x in range(64):
                alpha = 0
                if ox <= x < ox + fw and oy <= y < oy + fh:
                    sx = x0 + source_x[x - ox]
                    sy = y0 + source_y[y - oy]
                    alpha = 255 if source_pixels[sy * sw + sx][3] >= 128 else 0
                assert pixels[y * 64 + x][3] == alpha, (name, "Source silhouette differs", x, y)
        assert entry["java_texture"] == f"convergence:item/relic44/{name}"
        assert entry["bedrock_texture"] == f"textures/convergence/items/relic44/{name}"
        model_path = JAVA / f"models/item/{name}.json"
        model = json.loads(model_path.read_text())
        assert model["textures"]["layer0"] == entry["java_texture"]
        assert remainder(model, "model") == manifest["reference_remainders_sha256"][model_path.relative_to(ROOT).as_posix()]
        assert atlas["texture_data"]["convergence_" + name]["textures"] == entry["bedrock_texture"]
        definition = json.loads((JAVA / f"items/{name}.json").read_text())
        assert definition == {"model": {"type": "minecraft:model", "model": "convergence:item/" + name}}
        icons[name] = pixels
    assert manifest["preview_order"] == list(ITEMS)
    assert manifest["preview_sizes"] == [64, 128, 16]
    assert manifest["preview_background"] == [12, 19, 32, 255]
    for size in manifest["preview_sizes"]:
        width, height, pixels = png(ART / f"preview-{size}.png")
        assert (width, height) == (len(ITEMS) * size, size)
        source_indices = nearest_indices(64, size)
        for index, name in enumerate(ITEMS):
            for y in range(size):
                for x in range(size):
                    sx, sy = source_indices[x], source_indices[y]
                    expected = icons[name][sy * 64 + sx]
                    if not expected[3]:
                        expected = (12, 19, 32, 255)
                    assert pixels[y * width + index * size + x] == expected, (name, "Preview pixels", size, x, y)
    print("Validated four original relic sprites, source silhouettes, binary alpha, transparent margins, <=32colors, matching edition bytes,64/128/16actualpixelpreviews, references and unchanged native names/gameplay/historical sprites. This is not a client render.")


if __name__ == "__main__":
    validate()
