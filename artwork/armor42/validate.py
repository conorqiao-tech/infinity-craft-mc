"""Validate armor42 provenance/pixels/references; this is not an engine render."""
from pathlib import Path
import hashlib
import json
import struct
import zlib

ART = Path(__file__).resolve().parent
ROOT = ART.parents[1]
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"


def png(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    packed = bytearray(); position = 8; width = height = None
    while position < len(data):
        size = struct.unpack(">I", data[position:position + 4])[0]
        kind = data[position + 4:position + 8]
        payload = data[position + 8:position + 8 + size]
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">2I5B", payload)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0), path
            assert 0 < width <= 1024 and 0 < height <= 1024, path
        elif kind == b"IDAT":
            packed.extend(payload)
        position += size + 12
    raw = zlib.decompress(packed); stride = width * 4
    assert len(raw) == height * (stride + 1), path
    assert all(raw[y * (stride + 1)] == 0 for y in range(height)), path
    pixels = [tuple(raw[y * (stride + 1) + 1 + x * 4:y * (stride + 1) + 5 + x * 4])
              for y in range(height) for x in range(width)]
    return width, height, pixels


def validate():
    manifest = json.loads((ART / "manifest.json").read_text())
    assert manifest["generation_tool"] == "image_gen.imagegen"
    for file, key in ((manifest["source"], "source_sha256"), (manifest["prompt"], "prompt_sha256"), ("pack_art.py", "packer_sha256")):
        assert hashlib.sha256((ART / file).read_bytes()).hexdigest() == manifest[key], file
    for file, expected in manifest["sha256"].items():
        assert hashlib.sha256((ROOT / file).read_bytes()).hexdigest() == expected, file
        png(ROOT / file)
    used = {(x, y) for rect in manifest["uv_rectangles"].values()
            for y in range(rect[1] * 2, rect[3] * 2) for x in range(rect[0] * 2, rect[2] * 2)}
    atlas = json.loads((BED / "textures/item_texture.json").read_text())["texture_data"]
    count = 0
    for suit, definition in manifest["suits"].items():
        worn_path = BED / f"textures/convergence/models/armor/armor42/{suit}.png"
        width, height, pixels = png(worn_path)
        assert (width, height) == (128, 64)
        for y in range(height):
            for x in range(width):
                assert pixels[y * width + x][3] == (255 if (x, y) in used else 0), (suit, x, y)
        for layer in ("humanoid", "humanoid_leggings"):
            assert (JAVA / f"textures/entity/equipment/{layer}/armor42/{suit}.png").read_bytes() == worn_path.read_bytes()
        equipment_names = ("armor", "cuirass") if suit == "convergence" else (suit + "_armor",)
        for name in equipment_names:
            equipment = json.loads((JAVA / f"equipment/{name}.json").read_text())
            for layer in ("humanoid", "humanoid_leggings"):
                assert equipment["layers"][layer][0]["texture"] == "convergence:armor42/" + suit
        folder = "refresh27" if suit in ("convergence", "aurora", "ember") else "elemental41"
        for name in definition["icons"]:
            icon_path = JAVA / f"textures/item/armor42/{name}.png"
            w, h, colors = png(icon_path)
            assert (w, h) == (64, 64)
            assert set(p[3] for p in colors) == {0, 255}, name
            assert 200 < sum(p[3] == 255 for p in colors) < 3600, name
            assert len(set(p for p in colors if p[3])) >= 12, name
            assert icon_path.read_bytes() == (BED / f"textures/convergence/items/armor42/{name}.png").read_bytes()
            model = json.loads((JAVA / f"models/item/{name}.json").read_text())
            assert model["textures"]["layer0"] == "convergence:item/armor42/" + name
            assert atlas["convergence_" + name]["textures"] == "textures/convergence/items/armor42/" + name
            for prefix in ("", "catalog_"):
                description = json.loads((BED / f"attachables/{folder}/{prefix}{name}.json").read_text())["minecraft:attachable"]["description"]
                assert description["textures"]["default"] == "textures/convergence/models/armor/armor42/" + suit
            count += 1
    assert count == 20
    assert len(manifest["sha256"]) == 57
    print("Validated five original armor42 atlases, twenty complete icons, all Java/Bedrock texture references and source/output checksums. Engine rendering remains a separate client check.")


if __name__ == "__main__":
    validate()
