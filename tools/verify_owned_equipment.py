"""Reject missing or partial native evidence for original Bedrock held equipment."""

PROTOCOLS = [1001, 2168, 2193]


def verify_owned_equipment(ready):
    """Called by the real Geyser smoke check and the package evidence gate."""
    support = ready.get("owned_equipment")
    proof = ready.get("owned_equipment_proof")
    if not isinstance(support, dict) or not isinstance(proof, dict):
        raise ValueError("Missing owned equipment native evidence; rerun Geyser checks")
    protocols = ready.get("protocols")
    support_protocols = support.get("protocols")
    if (not isinstance(protocols, dict) or set(protocols) != {str(p) for p in PROTOCOLS}
            or any(type(count) is not int or count != 46 for count in protocols.values())
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
