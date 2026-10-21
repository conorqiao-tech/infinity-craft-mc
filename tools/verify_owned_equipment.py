"""Require complete catalogue coverage and native held-equipment evidence."""

import re
from snapshot5_dependencies import MINECRAFT

PROTOCOLS = [1001, 2168, 2193]
# These slots exclude the scratch slot, navigator, picker and component controls.
# The native fixture independently constructs and checks this same capacity.
PERSISTENCE_COLLECTION_SLOTS = tuple(slot for slot in range(1, 36)
                                     if slot not in (6, 7, 8, 34, 35))


def catalogue_item_count_pairs(catalogue):
    """Derive requirements from the full exported catalogue, never the capture.

    Native checks independently compare this document with the booted canonical
    registry. This validator is only the coverage/count gate, not native proof.
    """
    if (not isinstance(catalogue, dict)
            or type(catalogue.get("format_version")) is not int
            or catalogue["format_version"] != 1 or catalogue.get("minecraft") != MINECRAFT
            or not isinstance(catalogue.get("items"), list)
            or not 1 <= len(catalogue["items"]) <= 128):
        raise ValueError("Require a complete supported canonical catalogue")
    paths = set()
    pairs = set()
    for entry in catalogue["items"]:
        if not isinstance(entry, dict):
            raise ValueError("Canonical catalogue entry must be an object")
        path = entry.get("path")
        maximum = entry.get("max_stack_size")
        if (not isinstance(path, str) or not re.fullmatch(r"[a-z0-9_]{1,64}", path)
                or path in paths or type(maximum) is not int or not 1 <= maximum <= 64):
            raise ValueError("Canonical catalogue has a duplicate/invalid identity or stack size")
        paths.add(path)
        pairs.update(((path, 1), (path, maximum)))
    return pairs


def creative_capture_counts(catalogue, captured):
    """Reject omissions/duplicates before deriving exact native proof counts.

    The expected cross product comes from every canonical item and its applicable
    counts across all pinned protocols; a shortened capture cannot lower it.
    Successful schema validation does not establish that any packet was executed.
    """
    pairs = catalogue_item_count_pairs(catalogue)
    identities = len(catalogue["items"])
    if not isinstance(captured, dict):
        raise ValueError("Require the complete actual Creative capture")
    registries = captured.get("protocols")
    if (type(captured.get("items")) is not int or captured["items"] != identities
            or not isinstance(registries, dict)
            or set(registries) != {str(protocol) for protocol in PROTOCOLS}
            or any(type(value) is not int or value != identities for value in registries.values())):
        raise ValueError("Creative capture registries do not cover the full canonical catalogue")
    proof = captured.get("proof")
    samples = proof.get("encoded_hotbar_packets") if isinstance(proof, dict) else None
    if not isinstance(samples, list):
        raise ValueError("Missing complete actual Creative hotbar capture")
    expected = {(protocol, path, count) for protocol in PROTOCOLS for path, count in pairs}
    observed = set()
    for sample in samples:
        if (not isinstance(sample, dict) or not isinstance(sample.get("path"), str)
                or type(sample.get("count")) is not int
                or type(sample.get("bedrock_protocol")) is not int):
            raise ValueError("Creative capture has an invalid protocol/item/count case")
        case = (sample["bedrock_protocol"], sample["path"], sample["count"])
        if case not in expected or case in observed:
            raise ValueError("Creative capture has an unexpected or duplicate protocol/item/count case")
        observed.add(case)
    if observed != expected:
        raise ValueError("Creative capture omits canonical protocol/item/count cases")
    hotbar = len(expected)
    if (type(proof.get("actual_creative_hotbar_selections")) is not int
            or proof["actual_creative_hotbar_selections"] != hotbar):
        raise ValueError("Creative capture selection count differs from the complete actual capture")
    distinct = len(pairs)
    batches = (distinct + len(PERSISTENCE_COLLECTION_SLOTS) - 1) // len(PERSISTENCE_COLLECTION_SLOTS)
    return {"hotbar_cases": hotbar, "distinct_item_count_cases": distinct,
            "disk_cases": distinct, "rejoin_cases": distinct,
            "canonical_identities": identities, "native_save_batches": batches,
            "genuine_reconnects": batches, "variant_inputs": 5,
            "framed_inbound_packets": hotbar + distinct + 5,
            "backpack_styles_preserved": 3}


def verify_owned_equipment(ready):
    """Called by the real Geyser smoke check and the package evidence gate."""
    support = ready.get("owned_equipment")
    proof = ready.get("owned_equipment_proof")
    if not isinstance(support, dict) or not isinstance(proof, dict):
        raise ValueError("Missing owned equipment native evidence; rerun Geyser checks")
    protocols = ready.get("protocols")
    support_protocols = support.get("protocols")
    items = ready.get("items")
    if (type(items) is not int or not 1 <= items <= 128
            or not isinstance(protocols, dict) or set(protocols) != {str(p) for p in PROTOCOLS}
            or any(type(count) is not int or count != items for count in protocols.values())
            or not isinstance(support_protocols, list)
            or any(type(protocol) is not int for protocol in support_protocols)
            or support_protocols != PROTOCOLS):
        raise ValueError("Owned equipment evidence uses unexpected protocol registries")
    counts = {
        "items": 4, "custom_mapping_records": 12, "owned_login_definitions": 12,
        "mandatory_component_packet_roundtrips": 3, "mandatory_owned_model_cases": 12,
        "mandatory_untouched_baseline_component_packet_roundtrips": 3,
    }
    for field, expected in counts.items():
        if type(support.get(field)) is not int or support[field] != expected:
            raise ValueError("Incomplete owned equipment startup evidence: " + field)
    for field in ("unowned_definitions_and_primary_mappings_unchanged",
                  "native_arrow_fallbacks_preserved", "ordinary_vanilla_components_unchanged",
                  "owned_default_display_names_are_catalogue_literals",
                  "complete_unowned_wire_components_match_native_baseline"):
        if support.get(field) is not True:
            raise ValueError("Owned equipment changed unowned/native data: " + field)
    counts = {
        "component_packet_roundtrips": 3, "owned_model_cases": 12,
        "untouched_baseline_component_packet_roundtrips": 3,
        "native_inventory_slot_cases": 30, "native_arrow_fallback_cases": 9,
        "charged_projectile_and_empty_cases": 18, "native_ranged_use_release_cases": 24,
        "native_shield_main_and_offhand_cases": 21, "native_mixed_shield_cases": 9,
        "native_totem_main_and_offhand_cases": 12,
        "bedrock_wire_packets": 69,
    }
    for field, expected in counts.items():
        if type(proof.get(field)) is not int or proof[field] != expected:
            raise ValueError("Incomplete owned equipment native proof: " + field)
    for field in ("ordinary_vanilla_definitions_unchanged",
                  "complete_unowned_wire_components_match_native_baseline",
                  "complete_cached_components_and_network_ids_preserved",
                  "native_arrow_identity_preserved", "charged_projectile_retention",
                  "creative_arrow_ammunition_component", "crossbow_offhand_rocket_ammunition_component"):
        if proof.get(field) is not True:
            raise ValueError("Owned equipment native proof failed: " + field)
    if (support.get("device_rendering_verified") is not False
            or proof.get("java_ammo_rules_proven_here") is not False
            or proof.get("device_input_or_rendering_proven_here") is not False):
        raise ValueError("Equipment transport evidence cannot claim Java mechanics or device rendering")
    return {"items": 4, "protocols": 3, "native_control_cases": 57, "wire_packets": 69}
