"""Inspect existing worn geometry after a seam repair; never author new art.

The PNG below is a software asset preview, not an actual Minecraft render. It
reads original PNGs only for inspection and never modifies them or derives new
armor from them. generate.py creates the new sets independently from blank.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import struct
import zlib

ART = Path(__file__).resolve().parent
ROOT = ART.parents[1]
BED = ROOT / "bedrock/resource_pack"
spec = importlib.util.spec_from_file_location("elemental41_art", ART / "generate.py")
art = importlib.util.module_from_spec(spec)
spec.loader.exec_module(art)


def decode(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    at = 8; compressed = bytearray(); width = height = None
    while at < len(data):
        length = struct.unpack(">I", data[at:at + 4])[0]
        kind = data[at + 4:at + 8]; payload = data[at + 8:at + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">2I5B", payload)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0)
            assert width <= 256 and height <= 256
        elif kind == b"IDAT":
            compressed.extend(payload)
        at += length + 12
    raw = zlib.decompress(compressed); stride = width * 4
    assert len(raw) == height * (stride + 1)
    previous = bytearray(stride); canvas = art.Canvas(width, height)
    for y in range(height):
        kind = raw[y * (stride + 1)]; assert kind in range(5)
        row = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for x in range(stride):
            a = row[x - 4] if x >= 4 else 0; b = previous[x]; c = previous[x - 4] if x >= 4 else 0
            if kind == 1:
                prediction = a
            elif kind == 2:
                prediction = b
            elif kind == 3:
                prediction = (a + b) // 2
            elif kind == 4:
                p = a + b - c; da, db, dc = abs(p - a), abs(p - b), abs(p - c)
                prediction = a if da <= db and da <= dc else b if db <= dc else c
            else:
                prediction = 0
            row[x] = (row[x] + prediction) % 256
        for x in range(width):
            canvas.put(x, y, tuple(row[x * 4:x * 4 + 4]))
        previous = row
    return canvas


def main():
    protected = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in (BED / "textures").rglob("*.png")}
    preview = art.Canvas(960, 240, "111a26")
    for index, collection in enumerate(("convergence", "aurora", "ember")):
        names = ["helmet", "cuirass", "leggings", "boots"] if collection == "convergence" else [collection + "_" + p for p in art.PIECES]
        actual = [json.loads((BED / f"models/entity/original_armor/{name}.geo.json").read_text()) for name in names]
        atlas = decode(BED / f"textures/convergence/models/armor/refresh27/{collection}_refresh27.png")
        for pose in range(2):
            preview.copy(art.geometry_view(collection, atlas, bool(pose), actual), (index * 2 + pose) * 160, 0)
    preview.save(ART / "existing-armor-seams-preview.png")
    assert protected == {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in protected}
    print("Inspected actual Convergence/Aurora/Ember cuboids and UVs in standing/pivot poses; PNG inputs unchanged. This is not a client rendering test.")


if __name__ == "__main__":
    main()
