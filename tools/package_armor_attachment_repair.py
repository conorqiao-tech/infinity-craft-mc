#!/usr/bin/env python3
"""Build a resource-only native-joint armor repair against immutable explore.45.

The contract changes only the 21 active armor/backpack skeletons, their two
controllers' bone-name keys, and the cache revision. Artwork, authored cuboids,
visibility expressions, item identities, gameplay and terrain remain unchanged.
This packages a device-comparison candidate; it does not prove client rendering.
"""
import argparse
import copy
import json
from pathlib import Path

import package_armor_visibility_repair as BASE


ROOT = Path(__file__).resolve().parents[1]
BASELINE_SHA256 = "4f6c7e9e1fd8cc6c9409de3bb6343a94efac928d43d506a412751ec7da852eb8"
RELEASE_VERSION = "2.13.0-explore.45"
REPAIR_VERSION = (2, 13, 45001)
REPAIR_NAME = "armor-attachment-explore45-r1"
REPAIR_PREFIX = "Infinity_Armor_Armor_Attachment_Repair_explore45-r1/"
NOTES_PATH = "ARMOR_ATTACHMENT_REPAIR.md"
NATIVE_NAMES = {"head": "head", "body": "body", "rightarm": "rightArm", "leftarm": "leftArm",
                "rightleg": "rightLeg", "leftleg": "leftLeg"}
PIECE_JOINTS = {"helmet": ("head",), "chestplate": ("body", "rightarm", "leftarm"),
                "leggings": ("rightleg", "leftleg"), "boots": ("rightleg", "leftleg"), "backpack": ("body",)}
GEOMETRY_JOINTS, CONTROLLER_BONES = {}, {}
CONTROLLERS = ("render_controllers/original_static_armor.json", "render_controllers/elemental41.json")


def _register(folder, stem, piece, prefix, controller_suffix, controller_file):
    path = f"models/entity/{folder}/{stem}.geo.json"
    joints = PIECE_JOINTS[piece]
    # Historical authoring names are deliberately part of this narrow contract.
    old_names = {"head": prefix + "head", "body": prefix + ("pack" if piece == "backpack" else "body"),
                 "rightarm": prefix + "right_arm", "leftarm": prefix + "left_arm",
                 "rightleg": prefix + "right_leg", "leftleg": prefix + "left_leg"}
    GEOMETRY_JOINTS[path] = tuple((old_names[joint], joint) for joint in joints)
    CONTROLLER_BONES.setdefault(controller_file, {})[f"controller.render.convergence.{stem}_{controller_suffix}"] = {
        old_names[joint]: NATIVE_NAMES[joint] for joint in joints}


for family in ("", "aurora_", "ember_"):
    for piece in ("helmet", "chestplate", "leggings", "boots"):
        stem = "cuirass" if not family and piece == "chestplate" else family + piece
        _register("original_armor", stem, piece, "infinity_", "original", CONTROLLERS[0])
_register("original_armor", "backpack", "backpack", "infinity_", "original", CONTROLLERS[0])
for family in ("elemental", "infinity"):
    for piece in ("helmet", "chestplate", "leggings", "boots"):
        _register("elemental41", family + "_" + piece, piece, family + "_", "elemental41", CONTROLLERS[1])

GEOMETRY_COUNTS = {name: len(joints) for name, joints in GEOMETRY_JOINTS.items()}
CONTROLLER_COUNTS = {name: sum(len(bones) for bones in controllers.values())
                     for name, controllers in CONTROLLER_BONES.items()}
ALLOWED_PACK_CHANGES = {"manifest.json", *GEOMETRY_JOINTS, *CONTROLLERS}


def validate_geometry(old, new, expected_joints):
    """Construct the flat native armor skeleton; compare every other field."""
    expected = copy.deepcopy(old)
    try:
        geometries = expected["minecraft:geometry"]
        if not isinstance(geometries, list) or len(geometries) != 1:
            raise ValueError("An active armor geometry must contain exactly one model")
        bones = geometries[0]["bones"]
        if len(bones) != len(expected_joints):
            raise ValueError("Armor bone coverage changed")
        for bone, (name, joint) in zip(bones, expected_joints):
            if (bone["name"] != name or bone.get("binding") != "'" + joint + "'"
                    or "parent" in bone or not isinstance(bone.get("cubes"), list) or not bone["cubes"]):
                raise ValueError("Unexpected baseline armor binding or hierarchy")
            bone["name"] = NATIVE_NAMES[joint]
            del bone["binding"]
    except (KeyError, TypeError, IndexError, AttributeError) as error:
        raise ValueError("Malformed active armor geometry") from error
    if expected != new:
        raise ValueError("Geometry changed beyond the exact flat native-joint repair")
    return {"cube_bearing_bones": len(bones), "empty_ancestors": 0}


def validate_controller(old, new, expected_controllers):
    """Rename bone keys only; retain item guards, camera rules and materials."""
    expected = copy.deepcopy(old)
    renamed = 0
    try:
        controllers = expected["render_controllers"]
        if controllers.keys() != expected_controllers.keys():
            raise ValueError("Armor controller coverage changed")
        for name, renames in expected_controllers.items():
            controller = controllers[name]
            parts = controller["part_visibility"]
            if (not isinstance(parts, list) or len(parts) != len(renames)
                    or any(not isinstance(part, dict) or len(part) != 1 for part in parts)
                    or [next(iter(part)) for part in parts] != list(renames)):
                raise ValueError("Unexpected baseline armor visibility guards")
            controller["part_visibility"] = [{renames[bone]: value for bone, value in part.items()} for part in parts]
            renamed += len(renames)
    except (KeyError, TypeError, IndexError, AttributeError) as error:
        raise ValueError("Malformed armor render controller") from error
    if expected != new:
        raise ValueError("Controller changed beyond its exact native bone-name keys")
    return renamed


def validate_pack(old_data, new_data, expected_counts=None):
    old, new = BASE.safe_zip(old_data), BASE.safe_zip(new_data)
    if old.keys() != new.keys():
        raise ValueError("Attachment repair cannot add or delete pack entries")
    changed = {name for name in old if old[name] != new[name]}
    if changed != ALLOWED_PACK_CHANGES:
        raise ValueError("Repair must change exactly 21 active geometries, two controllers and the manifest")
    BASE.validate_manifest(BASE.json_object(old["manifest.json"]), BASE.json_object(new["manifest.json"]), REPAIR_VERSION)
    geometry = {name: validate_geometry(BASE.json_object(old[name]), BASE.json_object(new[name]), joints)
                for name, joints in GEOMETRY_JOINTS.items()}
    counts = {name: value["cube_bearing_bones"] for name, value in geometry.items()}
    controllers = {name: validate_controller(BASE.json_object(old[name]), BASE.json_object(new[name]), mapping)
                   for name, mapping in CONTROLLER_BONES.items()}
    if (counts != GEOMETRY_COUNTS or controllers != CONTROLLER_COUNTS
            or (expected_counts is not None and expected_counts != GEOMETRY_COUNTS)):
        raise ValueError("All 41 active armor/backpack bones and guards must be repaired")
    return {"changed_entries": {name: {"old_sha256": BASE.digest(old[name]), "new_sha256": BASE.digest(new[name])}
                                for name in sorted(changed)},
            "unchanged_entry_count": len(old) - len(changed), "added_entries": [], "deleted_entries": [],
            "geometry_native_joints": counts, "controller_bone_renames": controllers,
            "empty_ancestors": sum(value["empty_ancestors"] for value in geometry.values()),
            "version": list(REPAIR_VERSION), "runtime_native_joint_rendering_verified": False}


def build_repair(baseline_zip, server_root, mapping_root, notes_path, output,
                 *, expected_baseline_sha256=BASELINE_SHA256):
    """Fixtures may pin a baseline; production retains the immutable45 default."""
    return BASE.build_repair(
        baseline_zip, server_root, mapping_root, notes_path, output,
        expected_baseline_sha256=expected_baseline_sha256, expected_counts=GEOMETRY_COUNTS,
        pack_validator=validate_pack, repair_name=REPAIR_NAME, repair_prefix=REPAIR_PREFIX,
        notes_name=NOTES_PATH, packager_path=__file__, expected_release_version=RELEASE_VERSION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=ROOT / "dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.45.zip")
    parser.add_argument("--server", type=Path, default=ROOT / "server")
    parser.add_argument("--mappings", type=Path, default=ROOT / "research/integration/crossplay-export")
    parser.add_argument("--notes", type=Path, default=ROOT / "server" / NOTES_PATH)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/armor-attachment-explore45-r1/Infinity_Armor_Armor_Attachment_Repair_explore45-r1.zip")
    args = parser.parse_args()
    try:
        report = build_repair(args.baseline, args.server, args.mappings, args.notes, args.output)
    except (ValueError, OSError) as error:
        parser.exit(1, "Attachment repair packaging refused: " + str(error) + "\n")
    print(json.dumps({"output": str(args.output), "sha256": BASE.file_digest(args.output),
                      "native_joint_bones": sum(report["pack_changes"]["geometry_native_joints"].values()),
                      "device_rendering_verified": False}, indent=2))


if __name__ == "__main__":
    main()
