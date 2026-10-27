#!/usr/bin/env python3
"""Build a resource-only wing appearance candidate against immutable explore.51.

Only the combined elytra attachable's two slot selectors and the client cache
revision change. The native fallback, property guards, artwork, geometry, mod,
runtime and mappings remain unchanged. A static contract cannot prove that the
iPad evaluates the revised Molang context; this is a device-comparison candidate.
"""
import argparse
import copy
import json
from pathlib import Path

import package_armor_visibility_repair as BASE


ROOT = Path(__file__).resolve().parents[1]
BASELINE_SHA256 = "9ea69bb0114dc6bdbf236790c1105190ec0fb4ab350eee0eeb0b4c39c2865eab"
RELEASE_VERSION = "2.13.0-explore.51"
BASELINE_PACK_VERSION = (2, 13, 51000)
REPAIR_VERSION = (2, 13, 51001)
REPAIR_NAME = "wing-appearance-explore51-r1"
REPAIR_PREFIX = "Infinity_Armor_Wing_Appearance_Repair_explore51-r1/"
NOTES_PATH = "WING_APPEARANCE_REPAIR.md"
ATTACHABLE = "attachables/combined_convergence_elytra.json"
ALLOWED_PACK_CHANGES = {"manifest.json", ATTACHABLE}
SELECTOR_COUNTS = {ATTACHABLE: 2}
OLD_SLOT_QUERY = "query.item_slot_to_bone_name(context.item_slot)"
OWNER_SLOT_QUERY = "(context.owning_entity->query.item_slot_to_bone_name(context.item_slot))"
SELECTORS = (
    ("variable.infinityhub_winged_chest", "infinityhub:winged_chest"),
    ("variable.infinityhub_infinity_winged_chest", "infinityhub:infinity_winged_chest"),
)


def _baseline_selector(variable, property_name):
    return (f"{variable} = {OLD_SLOT_QUERY} == 'body' ? "
            f"((context.owning_entity->query.has_property('{property_name}')) ? "
            f"(context.owning_entity->query.property('{property_name}')) : 0.0) : 0.0;")


def validate_attachable(old, new):
    """Require both known selectors; change only the entity used by the slot query."""
    expected = copy.deepcopy(old)
    try:
        scripts = expected["minecraft:attachable"]["description"]["scripts"]
        previous = scripts["pre_animation"]
        required = [_baseline_selector(variable, name) for variable, name in SELECTORS]
        if previous != required:
            raise ValueError("Baseline must contain exactly both known guarded slot selectors in order")
        scripts["pre_animation"] = [expression.replace(OLD_SLOT_QUERY, OWNER_SLOT_QUERY)
                                    for expression in previous]
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("Malformed combined elytra attachable") from error
    if expected != new:
        raise ValueError("Attachable changed beyond the two owning-entity slot-query replacements")
    return len(SELECTORS)


def validate_pack(old_data, new_data, expected_counts=None):
    old, new = BASE.safe_zip(old_data), BASE.safe_zip(new_data)
    if old.keys() != new.keys():
        raise ValueError("Wing appearance repair cannot add or delete pack entries")
    changed = {name for name in old if old[name] != new[name]}
    if changed != ALLOWED_PACK_CHANGES:
        raise ValueError("Repair must change exactly the manifest and combined elytra attachable")
    old_manifest = BASE.json_object(old["manifest.json"])
    new_manifest = BASE.json_object(new["manifest.json"])
    try:
        versions = [old_manifest["header"]["version"],
                    *(module["version"] for module in old_manifest["modules"])]
        if not old_manifest["modules"] or any(version != list(BASELINE_PACK_VERSION) for version in versions):
            raise ValueError("Baseline pack must use the explore.51 cache revision")
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError("Malformed baseline pack manifest") from error
    BASE.validate_manifest(old_manifest, new_manifest, REPAIR_VERSION)
    counts = {ATTACHABLE: validate_attachable(BASE.json_object(old[ATTACHABLE]),
                                            BASE.json_object(new[ATTACHABLE]))}
    if counts != SELECTOR_COUNTS or (expected_counts is not None and expected_counts != SELECTOR_COUNTS):
        raise ValueError("Both winged-chest selectors must receive the context repair")
    return {
        "changed_entries": {name: {"old_sha256": BASE.digest(old[name]), "new_sha256": BASE.digest(new[name])}
                            for name in sorted(changed)},
        "unchanged_entry_count": len(old) - len(changed),
        "added_entries": [], "deleted_entries": [],
        "selector_owner_context_replacements": counts,
        "version": list(REPAIR_VERSION),
        "runtime_molang_context_verified": False,
    }


def build_repair(baseline_zip, server_root, mapping_root, notes_path, output,
                 *, expected_baseline_sha256=BASELINE_SHA256):
    """Fixtures may pin a tiny baseline; production retains the immutable51 hash."""
    return BASE.build_repair(
        baseline_zip, server_root, mapping_root, notes_path, output,
        expected_baseline_sha256=expected_baseline_sha256, expected_counts=SELECTOR_COUNTS,
        pack_validator=validate_pack, repair_name=REPAIR_NAME, repair_prefix=REPAIR_PREFIX,
        notes_name=NOTES_PATH, packager_path=__file__, expected_release_version=RELEASE_VERSION)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=ROOT / "dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.51.zip")
    parser.add_argument("--server", type=Path, default=ROOT / "server")
    parser.add_argument("--mappings", type=Path, default=ROOT / "research/integration/crossplay-export")
    parser.add_argument("--notes", type=Path, default=ROOT / "server" / NOTES_PATH)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/wing-appearance-explore51-r1/Infinity_Armor_Wing_Appearance_Repair_explore51-r1.zip")
    args = parser.parse_args()
    try:
        report = build_repair(args.baseline, args.server, args.mappings, args.notes, args.output)
    except (ValueError, OSError) as error:
        parser.exit(1, "Wing appearance repair packaging refused: " + str(error) + "\n")
    print(json.dumps({"output": str(args.output), "sha256": BASE.file_digest(args.output),
                      "slot_query_replacements": report["pack_changes"]["selector_owner_context_replacements"],
                      "device_rendering_verified": False, "runtime_molang_context_verified": False}, indent=2))


if __name__ == "__main__":
    main()
