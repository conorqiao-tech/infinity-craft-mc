"""Validate original assets and wearable coverage without starting Minecraft.

This checks resource contracts, not actual iPad/Java visual correctness.
"""
from pathlib import Path
import hashlib
import json
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]
ART = Path(__file__).resolve().parent
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"


def png(path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    at = 8; packed = bytearray(); width = height = None
    while at < len(data):
        length = struct.unpack(">I", data[at:at + 4])[0]
        kind = data[at + 4:at + 8]; payload = data[at + 8:at + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">2I5B", payload)
            assert (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0), path
            assert 0 < width <= 1024 and 0 < height <= 1024, path
        if kind == b"IDAT":
            packed.extend(payload)
        at += length + 12
    raw = zlib.decompress(packed)
    stride = width * 4
    assert len(raw) == height * (stride + 1), path
    assert all(raw[y * (stride + 1)] == 0 for y in range(height)), path
    rows = [raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)] for y in range(height)]
    return width, height, rows


def validate():
    manifest = json.loads((ART / "manifest.json").read_text())
    for name, expected in manifest["sha256"].items():
        path = ROOT / name
        assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
        png(path)
    controllers = json.loads((BED / "render_controllers/elemental41.json").read_text())["render_controllers"]
    assert len(controllers) == 8
    expected_joints = {"helmet": {"head"}, "chestplate": {"body", "rightArm", "leftArm"},
                       "leggings": {"rightLeg", "leftLeg"}, "boots": {"rightLeg", "leftLeg"}}
    for suit in ("elemental", "infinity"):
        cubes = []
        equipment = json.loads((JAVA / f"equipment/{suit}_armor.json").read_text())
        for layer in ("humanoid", "humanoid_leggings"):
            assert equipment["layers"][layer] == [{"texture": f"convergence:armor42/{suit}"}]
            assert (JAVA / f"textures/entity/equipment/{layer}/armor42/{suit}.png").read_bytes() == (BED / f"textures/convergence/models/armor/armor42/{suit}.png").read_bytes()
        width, height, rows = png(BED / f"textures/convergence/models/armor/armor42/{suit}.png")
        assert (width, height) == (128, 64)
        used = {(x, y) for rect in manifest["armor_uv"].values()
                for y in range(rect[1] * 2, rect[3] * 2) for x in range(rect[0] * 2, rect[2] * 2)}
        for y in range(height):
            for x in range(width):
                assert rows[y][x * 4 + 3] == (255 if (x, y) in used else 0), (suit, x, y)
        for piece, joints in expected_joints.items():
            name = suit + "_" + piece
            desc = json.loads((BED / f"attachables/elemental41/{name}.json").read_text())["minecraft:attachable"]["description"]
            alias = json.loads((BED / f"attachables/elemental41/catalog_{name}.json").read_text())["minecraft:attachable"]["description"]
            assert desc["identifier"] == "convergence:" + name
            assert alias == dict(desc, identifier="infinity_catalog:" + name)
            geo = json.loads((BED / f"models/entity/elemental41/{name}.geo.json").read_text())["minecraft:geometry"][0]
            assert geo["description"]["identifier"] == desc["geometry"]["default"]
            assert {bone["name"] for bone in geo["bones"]} == joints
            assert all("binding" not in bone and "parent" not in bone for bone in geo["bones"])
            assert len(geo["bones"]) == len(joints)
            controller = controllers[desc["render_controllers"][0]]
            assert len(controller["part_visibility"]) == len(joints)
            for item in controller["part_visibility"]:
                expression = next(iter(item.values()))
                assert "query.is_item_name_any('slot.armor." in expression
                assert "'convergence:" + name + "'" in expression
                assert "'infinity_catalog:" + name + "'" in expression
            for bone in geo["bones"]:
                for cube in bone["cubes"]:
                    cubes.append((name + "/" + bone["name"], cube))
                    # Slim sleeves extend below the wide-skin shoulder model.
                    # The independent skin-envelope regression bounds coverage.
                    assert len(cube["size"]) == 3 and all(0 < value <= 14 for value in cube["size"])
                    assert set(cube["uv"]) == {"up", "down", "north", "south", "east", "west"}
                    for face in cube["uv"].values():
                        x, y = face["uv"]; w, h = face["uv_size"]
                        assert 0 <= x < x + w <= width and 0 <= y < y + h <= height
                        assert all(rows[py][px * 4 + 3] == 255 for py in range(y, y + h) for px in range(x, x + w))
        # In the real generated upright bind pose, any two coplanar positive-
        # area surfaces can flicker. Check all axes, including piece boundaries
        # and the pair of legs, rather than a handpicked screenshot case.
        for index, (left_name, left) in enumerate(cubes):
            for right_name, right in cubes[index + 1:]:
                for axis in range(3):
                    other = [a for a in range(3) if a != axis]
                    if not all(min(left["origin"][a] + left["size"][a], right["origin"][a] + right["size"][a])
                               - max(left["origin"][a], right["origin"][a]) > 1e-6 for a in other):
                        continue
                    for ls in (0, 1):
                        for rs in (0, 1):
                            lp = left["origin"][axis] + ls * left["size"][axis]
                            rp = right["origin"][axis] + rs * right["size"][axis]
                            assert abs(lp - rp) > 1e-6, ("Coplanar armor faces", left_name, right_name, axis, lp)
    items = json.loads((BED / "textures/item_texture.json").read_text())["texture_data"]
    terrain = json.loads((BED / "textures/terrain_texture.json").read_text())["texture_data"]
    for name in manifest["icons"] + manifest["blocks"]:
        assert (JAVA / f"items/{name}.json").is_file()
        assert (JAVA / f"models/item/{name}.json").is_file()
        entry = items["convergence_" + name]
        folder = ("armor42" if name.startswith(("elemental_", "infinity_")) and name not in manifest["blocks"]
                  else "relic44" if name in ("windstep", "firebrand", "tidecaller", "terra_gauntlet") else "elemental41")
        assert f"/{folder}/" in entry["textures"]
        assert (BED / (entry["textures"] + ".png")).is_file()
        if name in manifest["blocks"]:
            assert terrain["convergence_" + name] == entry
            assert (JAVA / f"blockstates/{name}.json").is_file()
    # A private task baseline independently protects all older PNG bytes.
    baseline = ROOT / "research/elemental41/baseline.json"
    protected = 0
    if baseline.exists():
        for name, expected in json.loads(baseline.read_text())["existing_artwork"].items():
            assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected, name
            protected += 1
    print(f"Validated 8 wearable definitions,16 attachables,12 icons,5 block tiles; {protected} prior PNGs unchanged. Actual client rendering remains unverified.")


if __name__ == "__main__":
    validate()
