#!/usr/bin/env python3
"""Package the resource-only explore.44 r1 limb-binding diagnostic candidate.

The CLI pins the immutable tested explore.44 server ZIP. Only its manifest
revision and thirty quoted binding literals in fifteen active limb geometries
may change. This validates authored resources, not Bedrock's motion/rendering.
It neither starts a server nor changes installed artifacts or terrain.
"""
import argparse
import copy
import json
from pathlib import Path

import package_armor_visibility_repair as BASE


ROOT = Path(__file__).resolve().parents[1]
BASELINE_SHA256 = "6229c9d6715891e2fbba06d52ebae6778994d7d02ef6e00b9dd85292ae3695e6"
RELEASE_VERSION = "2.13.0-explore.44"
REPAIR_VERSION = (2, 13, 44001)
REPAIR_NAME = "armor-motion-explore44-r1"
REPAIR_PREFIX = "Infinity_Armor_Armor_Motion_Repair_explore44-r1/"
NOTES_PATH = "ARMOR_MOTION_REPAIR.md"
GEOMETRY_BINDINGS = {}
for stem in ("cuirass", "aurora_chestplate", "ember_chestplate"):
    GEOMETRY_BINDINGS[f"models/entity/original_armor/{stem}.geo.json"] = ("'rightArm'", "'leftArm'")
for stem in ("leggings", "boots", "aurora_leggings", "aurora_boots", "ember_leggings", "ember_boots"):
    GEOMETRY_BINDINGS[f"models/entity/original_armor/{stem}.geo.json"] = ("'rightLeg'", "'leftLeg'")
for family in ("elemental", "infinity"):
    for piece in ("chestplate", "leggings", "boots"):
        GEOMETRY_BINDINGS[f"models/entity/elemental41/{family}_{piece}.geo.json"] = (
            ("'rightArm'", "'leftArm'") if piece == "chestplate" else ("'rightLeg'", "'leftLeg'"))
BINDING_COUNTS = dict.fromkeys(GEOMETRY_BINDINGS, 2)
ALLOWED_PACK_CHANGES = {"manifest.json", *GEOMETRY_BINDINGS}


def validate_geometry(old, new, expected_bindings):
    """Reverse the exact literal case changes, then compare every JSON field."""
    normalized = copy.deepcopy(new)
    replacements = []
    try:
        previous_geometries = old["minecraft:geometry"]
        current_geometries = normalized["minecraft:geometry"]
        if not isinstance(previous_geometries, list) or not previous_geometries:
            raise ValueError("Geometry must contain an authored model")
        for index, previous in enumerate(previous_geometries):
            for bone_index, bone in enumerate(previous["bones"]):
                literal = bone.get("binding")
                if literal not in expected_bindings:
                    continue
                current = current_geometries[index]["bones"][bone_index]
                if current["binding"] != literal.lower():
                    raise ValueError("Limb binding must use the exact lowercase quoted literal")
                current["binding"] = literal
                replacements.append(literal)
    except (KeyError, IndexError, TypeError, AttributeError) as error:
        raise ValueError("Malformed limb geometry") from error
    if sorted(replacements) != sorted(expected_bindings) or normalized != old:
        raise ValueError("Geometry changed beyond the two approved limb-binding literals")
    return len(replacements)


def validate_pack(old_data, new_data, expected_counts=None):
    old, new = BASE.safe_zip(old_data), BASE.safe_zip(new_data)
    if old.keys() != new.keys():
        raise ValueError("Motion repair cannot add or delete pack entries")
    changed = {name for name in old if old[name] != new[name]}
    if changed != ALLOWED_PACK_CHANGES:
        raise ValueError("Motion repair must change exactly the manifest and fifteen active limb geometries")
    BASE.validate_manifest(BASE.json_object(old["manifest.json"]), BASE.json_object(new["manifest.json"]), REPAIR_VERSION)
    counts = {name: validate_geometry(BASE.json_object(old[name]), BASE.json_object(new[name]), bindings)
              for name, bindings in GEOMETRY_BINDINGS.items()}
    # The production contract cannot be reduced to a subset, including through
    # the shared builder's optional counts argument.
    if counts != BINDING_COUNTS or (expected_counts is not None and expected_counts != BINDING_COUNTS):
        raise ValueError("Motion repair must cover all thirty active limb bindings")
    return {"changed_entries": {name: {"old_sha256": BASE.digest(old[name]), "new_sha256": BASE.digest(new[name])}
                                for name in sorted(changed)},
            "unchanged_entry_count": len(old) - len(changed),
            "added_entries": [], "deleted_entries": [], "binding_replacements": counts,
            "version": list(REPAIR_VERSION), "runtime_binding_case_hypothesis_verified": False}


def build_repair(baseline_zip, server_root, mapping_root, notes_path, output,
                 *, expected_baseline_sha256=BASELINE_SHA256):
    """Fixture callers may pin a tiny baseline; the CLI retains the exact44 pin.

    Even fixtures must contain all fifteen geometry names and thirty binding
    literals. All locked artifacts/runtime/mappings remain byte-identical.
    """
    return BASE.build_repair(
        baseline_zip, server_root, mapping_root, notes_path, output,
        expected_baseline_sha256=expected_baseline_sha256, expected_counts=BINDING_COUNTS,
        pack_validator=validate_pack, repair_name=REPAIR_NAME, repair_prefix=REPAIR_PREFIX,
        notes_name=NOTES_PATH, packager_path=__file__, expected_release_version=RELEASE_VERSION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=ROOT / "dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.44.zip")
    parser.add_argument("--server", type=Path, default=ROOT / "server")
    parser.add_argument("--mappings", type=Path, default=ROOT / "research/integration/crossplay-export")
    parser.add_argument("--notes", type=Path, default=ROOT / "server" / NOTES_PATH)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/armor-motion-explore44-r1/Infinity_Armor_Armor_Motion_Repair_explore44-r1.zip")
    args = parser.parse_args()
    try:
        patch = build_repair(args.baseline, args.server, args.mappings, args.notes, args.output)
    except (ValueError, OSError) as error:
        parser.exit(1, "Motion repair packaging refused: " + str(error) + "\n")
    print(json.dumps({"output": str(args.output), "sha256": BASE.file_digest(args.output),
                      "binding_replacements": sum(patch["pack_changes"]["binding_replacements"].values()),
                      "device_rendering_verified": False}, indent=2))


if __name__ == "__main__":
    main()
