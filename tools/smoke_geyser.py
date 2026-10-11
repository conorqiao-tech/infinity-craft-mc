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
    service = launcher.Service("Bedrock", [args.java, "-Xmx512M", "-jar", "Geyser-Standalone.jar", "--nogui"],
                              geyser, "Started Geyser on", stop_command="geyser stop")
    try:
        service.wait_ready(60)
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
    item_count = sum(len(variants) for variants in items["items"].values()) + 1
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


if __name__ == "__main__":
    main()
