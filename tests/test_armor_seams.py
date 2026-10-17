"""Reject measurable armor z-fighting; this does not replace an iPad render check."""
import itertools
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


def overlapping_faces(cubes):
    conflicts = []
    for a, b in itertools.combinations(cubes, 2):
        # A single bound assembly can intentionally contain inset details.
        if a[:2] == b[:2]:
            continue
        for axis in range(3):
            for high in (False, True):
                pa = a[2]["origin"][axis] + (a[2]["size"][axis] if high else 0)
                pb = b[2]["origin"][axis] + (b[2]["size"][axis] if high else 0)
                if abs(pa - pb) > 1e-6:
                    continue
                extents = [min(a[2]["origin"][i] + a[2]["size"][i], b[2]["origin"][i] + b[2]["size"][i])
                           - max(a[2]["origin"][i], b[2]["origin"][i]) for i in range(3) if i != axis]
                if all(length > 1e-6 for length in extents):
                    conflicts.append((a[:2], b[:2], axis, high, extents))
    return conflicts


class ArmorSeamTests(unittest.TestCase):
    def test_detector_reproduces_old_boot_and_leggings_overlap(self):
        # explore.40 put both outer faces at z=-2.35, with .4 units of
        # vertical overlap: distinct pieces competed for the same pixels.
        old = [("leggings", "right_leg", {"origin": [-4.25, 3.6, -2.35], "size": [4.7, 8.65, 4.7]}),
               ("boots", "right_leg", {"origin": [-4.25, -.35, -2.35], "size": [4.7, 4.35, 4.7]})]
        self.assertTrue(overlapping_faces(old))

    def test_every_static_suit_has_separate_surface_planes(self):
        bed = ROOT / "bedrock/resource_pack/models/entity"
        groups = [("original_armor", p, ("helmet", "cuirass" if not p else "chestplate", "leggings", "boots"))
                  for p in ("", "aurora_", "ember_")]
        groups += [("elemental41", p + "_", ("helmet", "chestplate", "leggings", "boots"))
                   for p in ("elemental", "infinity")]
        for folder, prefix, pieces in groups:
            cubes = []
            for piece in pieces:
                path = prefix + piece
                data = json.loads((bed / folder / (path + ".geo.json")).read_text())["minecraft:geometry"][0]
                for bone in data["bones"]:
                    for cube in bone.get("cubes", []):
                        # Rotated ornament faces require transformed render QA.
                        if not cube.get("rotation"):
                            cubes.append((path, bone["name"], cube))
            self.assertFalse(overlapping_faces(cubes), (prefix or "convergence", overlapping_faces(cubes)))


if __name__ == "__main__":
    unittest.main()
