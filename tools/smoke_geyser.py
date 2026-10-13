#!/usr/bin/env python3
"""Load real Geyser with exported mappings and ping its localhost Bedrock listener."""
import argparse
import json
import zipfile
import importlib.util
from pathlib import Path
import shutil
import socket
import struct
import time
import hashlib
from verify_owned_equipment import verify_owned_equipment

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    import sys
    sys.path.insert(0, str(ROOT / "server"))
    spec = importlib.util.spec_from_file_location("launcher", ROOT / "server/server.py")
    launcher = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(launcher)
    root = ROOT / "research/geyser-smoke"
    root.mkdir(parents=True, exist_ok=True)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    launcher.configure(launcher.parse_args(["--bind", "127.0.0.1", "--bedrock-port", str(port)]), root)
    geyser = root / "geyser"
    (geyser / "packs").mkdir(exist_ok=True)
    for name in ["infinity-items.json", "infinity-blocks.json"]:
        shutil.copy2(ROOT / "research/integration/crossplay-export" / name, geyser / "custom_mappings" / name)
    shutil.copy2(ROOT / "research/integration/config/floodgate/key.pem", geyser / "key.pem")
    shutil.copy2(ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack", geyser / "packs/Infinity_Armor_Crossplay.mcpack")
    shutil.copy2(ROOT / "server/geyser/Geyser-Standalone.jar", geyser / "Geyser-Standalone.jar")
    (geyser / "extensions").mkdir(exist_ok=True)
    shutil.copy2(ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar", geyser / "extensions/InfinityCreativeBridge.jar")
    shutil.copy2(ROOT / "research/integration/crossplay-export/infinity-catalogue.json", geyser / "infinity-catalogue.json")
    shutil.copy2(ROOT / "server/dependencies.lock.json", root / "dependencies.lock.json")
    launcher.clear_creative_receipt(root)
    service = launcher.Service("Bedrock", [args.java, "-Xmx512M", "-Dinfinity.creative.proof=true", "-jar", "Geyser-Standalone.jar", "--nogui"],
                              geyser, "Started Geyser on", stop_command="geyser stop")
    try:
        service.wait_ready(60)
        receipt = launcher.wait_creative_ready(service, root)
        equipment = verify_owned_equipment(receipt)
        proof = receipt["proof"]
        catalogue = json.loads((geyser / "infinity-catalogue.json").read_text())
        expected_selections = sum(1 if item["max_stack_size"] == 1 else 2 for item in catalogue["items"]) * len(receipt["protocols"])
        assert proof["actual_creative_hotbar_selections"] == expected_selections
        assert proof["cursor_and_drop_cases"] == expected_selections * 2
        assert proof["occupied_different_item_replacement_cases"] == receipt["items"] * len(receipt["protocols"])
        assert len(proof["encoded_hotbar_packets"]) == expected_selections
        assert len(proof["encoded_oversized_packets"]) == receipt["items"] * len(receipt["protocols"])
        guard = proof["stale_request_guard"]
        protocols = len(receipt["protocols"])
        stale = receipt["items"] * protocols * 3
        assert guard["stale_survival_alias_requests"] == stale
        assert guard["adventure_and_spectator_requests"] == protocols * 2
        assert guard["mixed_ordinary_survival_batches"] == protocols
        assert guard["request_metadata_preservation_cases"] == stale
        assert guard["native_response_and_resync_wire_packets"] == (stale + protocols * 3) * 9
        assert guard["complete_cached_components_counts_and_network_ids_preserved"] is True
        assert guard["native_error_cursor_inventory_armor_offhand_and_crafting_resync"] is True
        assert guard["ordinary_requests_and_unowned_creative_entries_unchanged"] is True
        proof_folder = ROOT / "research/integration/creative-bridge"
        proof_folder.mkdir(exist_ok=True)
        (proof_folder / "ready.json").write_text(json.dumps(receipt, indent=2) + "\n")
        (proof_folder / "startup.json").write_text(json.dumps({
            "extension_sha256": hashlib.sha256((geyser / "extensions/InfinityCreativeBridge.jar").read_bytes()).hexdigest(),
            "catalogue_sha256": receipt["catalogue_sha256"],
            "ready_sha256": hashlib.sha256((proof_folder / "ready.json").read_bytes()).hexdigest()}, indent=2) + "\n")
        magic = bytes.fromhex("00ffff00fefefefefdfdfdfd12345678")
        packet = b"\x01" + struct.pack(">q", int(time.time() * 1000)) + magic + struct.pack(">q", 12345678)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
            client.settimeout(5)
            client.sendto(packet, ("127.0.0.1", port))
            data, _ = client.recvfrom(4096)
        assert data[0] == 0x1c, "Expected Bedrock RakNet pong"
        size = struct.unpack(">H", data[33:35])[0]
        message = data[35:35 + size].decode()
        assert "Infinity Armor" in message
        (root / "ping.txt").write_text(message + "\n")
        print("Bedrock UDP ping passed: " + message)
    finally:
        service.stop()
        service.thread.join(timeout=5)
    log = (geyser / "launcher.log").read_text()
    exported = ROOT / "research/integration/crossplay-export"
    items = json.loads((exported / "infinity-items.json").read_text())
    blocks = json.loads((exported / "infinity-blocks.json").read_text())
    item_count = sum(len(variants) for variants in items["items"].values()) + 1 + receipt["items"]
    # Ordinary model mappings stay hidden: the separate primary aliases own
    # Creative reverse translation and never send synthetic ids to Java.
    assert all("creative_category" not in variant["bedrock_options"]
               for variants in items["items"].values() for variant in variants), \
        "Only the canonicalizing Creative aliases may enter the Bedrock catalogue"
    block_count = sum(len(b.get("state_overrides", {})) for b in blocks["blocks"].values()) + 199
    assert f"Registered {item_count} custom items" in log, "Exported Infinity items plus built-in mapping"
    assert f"Registered {block_count} custom block overrides" in log, "Exported Infinity states plus built-in mappings"
    with zipfile.ZipFile(ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
        terrain = json.loads(pack.read("textures/terrain_texture.json"))["texture_data"]
        item_atlas = json.loads(pack.read("textures/item_texture.json"))["texture_data"]
        for definition in blocks["blocks"].values():
            for override in definition["state_overrides"].values():
                for material in override["material_instances"].values():
                    path = terrain[material["texture"]]["textures"] + ".png"
                    assert path in pack.namelist(), "Missing Bedrock placed-block texture: " + path
        for variants in items["items"].values():
            for variant in variants:
                path = item_atlas[variant["bedrock_options"]["icon"]]["textures"]
                if path.startswith("textures/convergence/"):
                    assert path + ".png" in pack.namelist(), "Missing Bedrock custom item texture: " + path
    assert "ERROR" not in log, "Geyser reported an error; inspect its log"
    assert "Geyser shutdown successfully" in log, "The launcher must shut Geyser down cleanly"
    print("Geyser mappings, localhost listener, and launcher shutdown passed. Player login was not tested.")
    print(f"Creative bridge transport passed: {receipt['items']} items across {len(receipt['protocols'])} protocols; "
          f"{expected_selections} actual hotbar selections and {expected_selections * 2} cursor/drop requests. "
          "Via/Fabric acceptance and rendered device appearance are separate checks.")
    print(f"Stale Creative request guard passed: {stale} Survival, {protocols * 2} Adventure/Spectator "
          f"and {protocols} mixed ordinary batches; {guard['native_response_and_resync_wire_packets']} native codec response/resync packets.")
    print(f"Original held equipment transport passed: {equipment['items']} items across {equipment['protocols']} protocols; "
          f"{equipment['native_control_cases']} native ranged/shield/totem cases and {equipment['wire_packets']} Bedrock wire packets. "
          "Java ammunition mechanics and authenticated device rendering remain separate checks.")


if __name__ == "__main__":
    main()
