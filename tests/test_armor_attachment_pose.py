"""Native attachment source contract and independent owner-pose math.

The pinned Mojang fixture is independent of our generators. Its selected armor
uses flat, exact-name joints, unlike the owner's animated root/waist hierarchy.
We compare posed skin against actual armor face planes, using separate inputs;
we never rotate skin then undo that same rotation against an unposed shell.
This models the documented same-name owner transform. It does not execute the
Bedrock renderer or establish the old explicit-binding runtime matrix formula.
"""
import copy
import itertools
import json
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "bedrock/resource_pack/models/entity"
FIXTURE = json.loads((ROOT / "tests/fixtures/armor_native_pose_contract.json").read_text())
NATIVE = FIXTURE["native_armor"]
EPS = 1e-8


def models():
    """Enumerate the active 20 suit pieces and backpack, not inactive flight."""
    for prefix in ("", "aurora_", "ember_"):
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            stem = "cuirass" if not prefix and piece == "chestplate" else prefix + piece
            path = MODELS / "original_armor" / (stem + ".geo.json")
            yield path, piece, json.loads(path.read_text())["minecraft:geometry"][0]
    path = MODELS / "original_armor/backpack.geo.json"
    yield path, "backpack", json.loads(path.read_text())["minecraft:geometry"][0]
    for suit in ("elemental", "infinity"):
        for piece in ("helmet", "chestplate", "leggings", "boots"):
            path = MODELS / "elemental41" / (suit + "_" + piece + ".geo.json")
            yield path, piece, json.loads(path.read_text())["minecraft:geometry"][0]


def native_contract(geometry, piece):
    bones = geometry["bones"]
    assert len(bones) == len(NATIVE["piece_joints"][piece]), "Unexpected attachment bones"
    assert {b["name"] for b in bones} == set(NATIVE["piece_joints"][piece]), "Exact native joint names required"
    for bone in bones:
        assert "binding" not in bone, "Explicit item binding is a different coordinate contract"
        assert "parent" not in bone, "Selected vanilla armor primary joints are independent roots"
        assert bone["pivot"] == NATIVE["pivots"][bone["name"]], "Native armor pivot changed"
        assert not any(key in bone for key in ("rotation", "position", "scale")), "Unexpected local bone transform"
        assert bone.get("cubes"), "A primary armor bone must retain its original cubes"


def identity():
    return tuple(tuple(float(i == j) for j in range(4)) for i in range(4))


def multiply(left, right):
    return tuple(tuple(sum(left[i][k] * right[k][j] for k in range(4))
                       for j in range(4)) for i in range(4))


def translate(vector):
    return tuple(tuple(float(vector[i]) if j == 3 and i < 3 else float(i == j)
                       for j in range(4)) for i in range(4))


def rotation(angles):
    result = identity()
    for axis, degrees in enumerate(angles):
        c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
        a, b = ((1, 2), (2, 0), (0, 1))[axis]
        part = [list(row) for row in identity()]
        part[a][a] = part[b][b] = c
        part[a][b], part[b][a] = -s, s
        result = multiply(part, result)
    return result


def point(matrix, vertex):
    return tuple(sum(matrix[i][j] * (*vertex, 1)[j] for j in range(4)) for i in range(3))


def owner_frames(slim, pose):
    """Compose full owner deltas using independently pinned absolute pivots.

    Each delta is parent_delta * T(pivot) * T(position) * R * T(-pivot).
    A flat native-name armor joint receives this complete owner delta once;
    it does not add the owner's bind-pose pivot as another translation.
    """
    nodes = copy.deepcopy(FIXTURE["owner"]["nodes"])
    if slim:
        for name, pivot in FIXTURE["owner"]["slim_pivot_overrides"].items():
            nodes[name]["pivot"] = pivot
    frames = {}

    def visit(name):
        if name not in frames:
            node, channel = nodes[name], pose.get(name, {})
            parent = visit(node["parent"]) if "parent" in node else identity()
            pivot = node["pivot"]
            local = multiply(translate(pivot), translate(channel.get("position", (0, 0, 0))))
            local = multiply(local, rotation(channel.get("rotation", (0, 0, 0))))
            local = multiply(local, translate([-n for n in pivot]))
            frames[name] = multiply(parent, local)
        return frames[name]

    for name in nodes:
        visit(name)
    return frames


def subtract(a, b):
    return tuple(x - y for x, y in zip(a, b))


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def corners(bounds):
    return itertools.product(*zip(*bounds))


def posed_shell(cube, matrix):
    """Return an actual posed shell origin, three unit face normals and widths."""
    origin = point(matrix, cube["origin"])
    axes = []
    for axis, width in enumerate(cube["size"]):
        end = list(cube["origin"])
        end[axis] += width
        vector = subtract(point(matrix, end), origin)
        length = math.sqrt(dot(vector, vector))
        axes.append((tuple(v / length for v in vector), length))
    return origin, axes


def containment_margin(shell, vertices):
    origin, axes = shell
    return min(min(dot(subtract(vertex, origin), axis), width - dot(subtract(vertex, origin), axis))
               for vertex in vertices for axis, width in axes)


def skin_bounds(slim, joint, piece, elemental):
    source = FIXTURE["owner"]["opaque_bounds_wide"]
    bounds = copy.deepcopy(FIXTURE["owner"]["slim_bound_overrides"].get(joint, source[joint])
                           if slim else source[joint])
    if piece in ("leggings", "boots"):
        seam = 3.4 if elemental else 4.0
        bounds[0 if piece == "leggings" else 1][1] = seam
    return bounds


class ArmorAttachmentPoseTests(unittest.TestCase):
    def test_all_21_active_models_follow_selected_native_armor_contract(self):
        count = 0
        for path, piece, geometry in models():
            with self.subTest(model=path.name):
                native_contract(geometry, piece)
                count += 1
        self.assertEqual(count, 21)

    def test_wide_and_slim_clothing_stays_inside_independently_posed_shells(self):
        checked = 0
        for path, piece, geometry in models():
            native_contract(geometry, piece)
            if piece == "backpack":
                continue  # The pack intentionally sits behind, rather than enclosing, the body.
            for slim, (pose_name, pose) in itertools.product((False, True), FIXTURE["poses"].items()):
                frames = owner_frames(slim, pose)
                for bone in geometry["bones"]:
                    joint = bone["name"]
                    with self.subTest(model=path.name, joint=joint, slim=slim, pose=pose_name):
                        skin = skin_bounds(slim, joint, piece, path.parent.name == "elemental41")
                        skin_vertices = [point(frames[joint], p) for p in corners(skin)]
                        armor_shell = posed_shell(bone["cubes"][0], frames[bone["name"]])
                        self.assertGreaterEqual(containment_margin(armor_shell, skin_vertices), .1 - EPS)
                        checked += 1
        self.assertEqual(checked, 400)

    def test_math_composition_has_independent_analytic_landmarks(self):
        self.assertAlmostEqual(point(rotation((90, 0, 0)), (0, 1, 0))[2], 1)
        combined = multiply(translate((1, 2, 3)), rotation((0, 0, 90)))
        for actual, expected in zip(point(combined, (1, 0, 0)), (1, 3, 3)):
            self.assertAlmostEqual(actual, expected)
        # Crouch root has translation [0,1.25,9] and rotation 28 degrees.
        frames = owner_frames(False, FIXTURE["poses"]["crouch"])
        reference = (0, 12 * math.cos(math.radians(28)) + 1.25,
                     12 * math.sin(math.radians(28)) + 9)
        for actual, expected in zip(point(frames["root"], (0, 12, 0)), reference):
            self.assertAlmostEqual(actual, expected)
        # Body's -2 translation belongs to upper limbs, not root-parented legs.
        self.assertNotEqual(frames["body"], frames["root"])

    def test_backpack_receives_complete_body_pose_once(self):
        path, piece, geometry = next(item for item in models() if item[1] == "backpack")
        native_contract(geometry, piece)
        cube = geometry["bones"][0]["cubes"][0]
        frames = owner_frames(False, FIXTURE["poses"]["crouch"])
        vertex = cube["origin"]
        expected = point(frames["body"], vertex)
        self.assertGreater(math.dist(expected, vertex), 1)
        self.assertGreater(math.dist(expected, point(frames["root"], vertex)), 1)
        self.assertGreater(math.dist(expected, point(multiply(frames["root"], frames["body"]), vertex)), 1)

    def test_old_binding_prefix_and_other_attachment_mutants_are_rejected(self):
        _, piece, geometry = next(item for item in models() if item[1] == "chestplate")
        mutations = {
            "old_prefixed_binding": lambda b: b.update(name="infinity_right_arm", binding="'rightarm'"),
            "extra_binding": lambda b: b.update(binding="'rightarm'"),
            "wrong_parent": lambda b: b.update(parent="body"),
            "wrong_pivot": lambda b: b.update(pivot=[-10, 22, 0]),
            "local_bone_offset": lambda b: b.update(position=[-5, 0, 0]),
        }
        for name, mutate in mutations.items():
            changed = copy.deepcopy(geometry)
            bone = next(b for b in changed["bones"] if b["name"] == "rightArm")
            mutate(bone)
            with self.subTest(mutation=name), self.assertRaises(AssertionError):
                native_contract(changed, piece)

    def test_world_space_check_rejects_offsets_missing_root_and_double_root(self):
        _, piece, geometry = next(item for item in models() if item[1] == "chestplate")
        bone = next(b for b in geometry["bones"] if b["name"] == "rightArm")
        pose = FIXTURE["poses"]["crouch"]
        frames = owner_frames(False, pose)
        skin = skin_bounds(False, "rightArm", piece, False)
        vertices = [point(frames["rightArm"], p) for p in corners(skin)]
        altered = copy.deepcopy(bone["cubes"][0])
        altered["origin"][0] -= 5  # Observed outward drift, independently of binding causality.
        missing_root_pose = {name: values for name, values in pose.items() if name != "root"}
        mutants = {
            "global_cube_shifted_as_if_local": posed_shell(altered, frames["rightArm"]),
            "missing_owner_root": posed_shell(bone["cubes"][0], owner_frames(False, missing_root_pose)["rightArm"]),
            "owner_root_applied_twice": posed_shell(bone["cubes"][0], multiply(frames["root"], frames["rightArm"])),
            "bind_pivot_added_twice": posed_shell(bone["cubes"][0], multiply(frames["rightArm"], translate(bone["pivot"]))),
        }
        self.assertGreaterEqual(containment_margin(posed_shell(bone["cubes"][0], frames["rightArm"]), vertices), .1 - EPS)
        for name, shell in mutants.items():
            with self.subTest(mutation=name):
                self.assertLess(containment_margin(shell, vertices), 0)


if __name__ == "__main__":
    unittest.main()
