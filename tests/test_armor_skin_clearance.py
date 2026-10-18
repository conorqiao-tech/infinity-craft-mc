"""Armor must cover opaque player clothing, not just clear other armor.

Bounds are the union of Mojang's wide/Slim skin contracts, not copied armor:
https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/mobs.json
Inspected source SHA256: 0279492e5f6a7b5bbafb4fbaa80290146acb79332d06ac39619376a4831ff464.
Rigid pose checks validate model-space containment, not Bedrock engine rendering.
"""
import itertools
import json
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BED = ROOT / "bedrock/resource_pack/models/entity"
SKIN = {
    "head": ((-4.5, 23.5, -4.5), (4.5, 32.5, 4.5)),
    "body": ((-4.25, 11.75, -2.25), (4.25, 24.25, 2.25)),
    "rightarm": ((-8.25, 11.25, -2.25), (-3.75, 24.25, 2.25)),
    "leftarm": ((3.75, 11.25, -2.25), (8.25, 24.25, 2.25)),
    "rightleg": ((-4.15, -.25, -2.25), (.35, 12.25, 2.25)),
    "leftleg": ((-.35, -.25, -2.25), (4.15, 12.25, 2.25)),
}
PIVOTS = {"head": (0, 24, 0), "body": (0, 24, 0),
          "rightarm": (-5, 22, 0), "leftarm": (5, 22, 0),
          "rightleg": (-1.9, 12, 0), "leftleg": (1.9, 12, 0)}


def bounds(cube):
    return tuple(cube["origin"]), tuple(a + b for a, b in zip(cube["origin"], cube["size"]))


def margin(outer, inner):
    return min(*(inner[0][i] - outer[0][i] for i in range(3)),
               *(outer[1][i] - inner[1][i] for i in range(3)))


def shells():
    for suit, prefix in (("convergence", ""), ("aurora", "aurora_"), ("ember", "ember_"),
                         ("elemental", "elemental_"), ("infinity", "infinity_")):
        folder = "elemental41" if suit in ("elemental", "infinity") else "original_armor"
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            name = "cuirass" if suit == "convergence" and piece == "chestplate" else prefix + piece
            geo = json.loads((BED / folder / (name + ".geo.json")).read_text())["minecraft:geometry"][0]
            for bone in geo["bones"]:
                joint = bone["binding"].strip("'")
                lo, hi = map(list, SKIN[joint])
                if piece in ("leggings", "boots"):
                    seam = 3.4 if folder == "elemental41" else 4.0
                    if piece == "leggings": lo[1] = seam
                    else: hi[1] = seam
                yield suit, piece, bone, (lo, hi)


def rotate(point, pivot, angles, inverse=False):
    """Reversible joint transform for representative turn/walk/crouch poses."""
    value = [point[i] - pivot[i] for i in range(3)]
    axes = list(enumerate(angles))
    for axis, angle in (reversed(axes) if inverse else axes):
        angle = math.radians(-angle if inverse else angle)
        a, b = ((1, 2), (2, 0), (0, 1))[axis]
        x, y = value[a], value[b]
        value[a], value[b] = x * math.cos(angle) - y * math.sin(angle), x * math.sin(angle) + y * math.cos(angle)
    return tuple(value[i] + pivot[i] for i in range(3))


class ArmorSkinClearanceTests(unittest.TestCase):
    def test_41_fixtures_reproduce_hidden_hat_pants_and_sleeves(self):
        old = [({"origin": [-4.28, 23.72, -4.28], "size": [8.56] * 3}, SKIN["head"]),
               ({"origin": [-4, 3.65, -2.1], "size": [3.95, 8.5, 4.2]},
                ((-4.15, 4, -2.25), (.35, 12.25, 2.25))),
               ({"origin": [-8.2, 11.8, -2.2], "size": [4.4, 12.4, 4.4]}, SKIN["rightarm"])]
        for cube, skin in old:
            self.assertLess(margin(bounds(cube), skin), 0, "The old shell is occluded by opaque skin clothing")

    def test_all_five_suits_cover_wide_and_slim_skin_layers(self):
        count = 0
        for suit, piece, bone, skin in shells():
            with self.subTest(suit=suit, piece=piece, bone=bone["name"]):
                self.assertGreaterEqual(margin(bounds(bone["cubes"][0]), skin), .1 - 1e-8)
                self.assertTrue(all(0 < n <= 14 for cube in bone["cubes"] for n in cube["size"]))
                self.assertLessEqual(bone["cubes"][0]["size"][0], 10, "Keep the wearable silhouette compact")
                count += 1
        self.assertEqual(count, 40, "Each suit covers head, torso, two arms, two thighs and two feet")

    def test_rigid_same_joint_fixture_preserves_static_clearance(self):
        # Conditional on successful engine binding: this reversible fixture
        # checks shell containment, not actual Bedrock pose inheritance.
        poses = ((0, 0, 0), (35, 25, 0), (-28, -40, 12))
        for suit, piece, bone, skin in shells():
            outer = bounds(bone["cubes"][0]); joint = bone["binding"].strip("'")
            self.assertEqual(tuple(bone["pivot"]), PIVOTS[joint])
            for pose in poses:
                for corner in itertools.product(*zip(*skin)):
                    moved = rotate(corner, PIVOTS[joint], pose)
                    local = rotate(moved, PIVOTS[joint], pose, inverse=True)
                    for i in range(3):
                        self.assertGreaterEqual(local[i], outer[0][i] + .1 - 1e-8, (suit, piece, joint, pose))
                        self.assertLessEqual(local[i], outer[1][i] - .1 + 1e-8, (suit, piece, joint, pose))

    def test_thigh_and_boot_layers_overlap_without_exposing_ankles(self):
        pairs = {}
        for suit, piece, bone, skin in shells():
            if piece in ("leggings", "boots"):
                pairs.setdefault((suit, bone["binding"]), {})[piece] = bounds(bone["cubes"][0])
        self.assertEqual(len(pairs), 10)
        for name, pieces in pairs.items():
            legs, boots = pieces["leggings"], pieces["boots"]
            self.assertGreater(boots[1][1], legs[0][1], name)
            self.assertGreater(boots[1][2], legs[1][2], name)
            self.assertLess(boots[0][2], legs[0][2], name)


if __name__ == "__main__":
    unittest.main()
