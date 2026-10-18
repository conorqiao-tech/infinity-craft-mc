"""Stdlib validation of original Astral Wilds resource payload, not gameplay."""
import hashlib
import json
from pathlib import Path
import struct
import zlib

ART = Path(__file__).resolve().parent
ROOT = ART.parents[1]
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
ITEMS = {"starshard_lantern", "riftspool", "keepers_trowel"}


def png(path):
    data = path.read_bytes(); assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    at = 8; packed = bytearray(); width = height = None
    while at < len(data):
        size = struct.unpack(">I", data[at:at + 4])[0]; kind = data[at + 4:at + 8]; payload = data[at + 8:at + 8 + size]
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">2I5B", payload)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0)
            assert 0 < width <= 512 and 0 < height <= 512
        elif kind == b"IDAT": packed.extend(payload)
        at += size + 12
    raw = zlib.decompress(packed); stride = width * 4
    assert len(raw) == height * (stride + 1)
    assert all(raw[y * (stride + 1)] == 0 for y in range(height))
    pixels = [tuple(raw[y * (stride + 1) + 1 + x * 4:y * (stride + 1) + 5 + x * 4])
              for y in range(height) for x in range(width)]
    return width, height, pixels


def validate():
    data = json.loads((ART / "manifest.json").read_text())
    assert data["generation_tool"] == "image_gen.imagegen"
    assert set(data["items"]) == ITEMS
    for path, field in (("source-sheet.png", "source_sha256"), ("prompt.txt", "prompt_sha256"), ("pack_art.py", "packer_sha256")):
        assert hashlib.sha256((ART / path).read_bytes()).hexdigest() == data[field], path
    for name, expected in data["sha256"].items(): assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected, name
    atlas = json.loads((ART / "bedrock-atlas-entries.json").read_text())
    assert set(atlas) == {"convergence_" + name for name in ITEMS}
    for name in ITEMS:
        java = JAVA / f"textures/item/astral43/{name}.png"
        bedrock = BED / f"textures/convergence/items/astral43/{name}.png"
        assert java.read_bytes() == bedrock.read_bytes()
        width, height, pixels = png(java); assert (width, height) == (64, 64)
        assert set(p[3] for p in pixels) == {0, 255}
        assert 500 < sum(p[3] == 255 for p in pixels) < 3500, name
        assert 12 <= len({p for p in pixels if p[3]}) <= 64, name
        assert atlas["convergence_" + name]["textures"] == "textures/convergence/items/astral43/" + name
        model = json.loads((JAVA / f"models/item/{name}.json").read_text())
        assert model == {"parent": "minecraft:item/generated", "textures": {"layer0": "convergence:item/astral43/" + name}}
        definition = json.loads((JAVA / f"items/{name}.json").read_text())
        assert definition == {"model": {"type": "minecraft:model", "model": "convergence:item/" + name}}
    png(ART / "preview.png")
    print("Validated three original Astral Wilds icons, matching Java/Bedrock pixels, resource definitions, separate atlas entries and provenance hashes. Gameplay is not established by this resource check.")


if __name__ == "__main__":
    validate()
