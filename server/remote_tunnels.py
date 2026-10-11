"""Provision only the two free Minecraft Playit presets for this server."""
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
import uuid

API = "https://api.playit.gg"


class SetupRequired(RuntimeError):
    """Contains only our own fixed, safe instructions for the owner."""


def api(secret, route, body):
    request = urllib.request.Request(API + route, data=json.dumps(body).encode(), headers={
        "Authorization": "Agent-Key " + secret, "Content-Type": "application/json",
        "User-Agent": "InfinityArmor-RemoteJoining/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 401 and route == "/tunnels/create":
            raise SetupRequired("Playit requires tunnel creation in its signed-in panel. Open https://playit.gg/account/tunnels and create Minecraft Java and Minecraft Bedrock tunnels using the local ports in REMOTE_JOINING.md. Then rerun this launcher.") from None
        raise RuntimeError("Playit did not authorize this request.") from None
    except (OSError, ValueError):
        raise RuntimeError("Playit could not be reached. Check your Internet connection.") from None
    if result.get("status") != "success":
        # Never print an arbitrary server response: it may contain account data.
        raise RuntimeError("Playit did not approve this request. Check account verification and free tunnel limits at https://playit.gg/account/tunnels.")
    return result["data"]


def secret_in(root):
    directory = Path(root) / ".remote"
    if directory.is_symlink() or not directory.is_dir():
        raise RuntimeError("Playit needs its own private local .remote directory.")
    directory.chmod(0o700)
    path = directory / "playit.toml"
    if path.is_symlink():
        raise RuntimeError("The Playit credential must be a local file.")
    if not path.is_file():
        raise RuntimeError("Connect the Playit agent to your account first.")
    path.chmod(0o600)
    # This is the only field read; no dependency on Python 3.11's TOML module.
    match = re.search(r'^\s*secret_key\s*=\s*"([a-fA-F0-9]+)"\s*$', path.read_text(), re.M)
    if not match:
        raise RuntimeError("Playit has not saved an agent key yet.")
    return match.group(1)


def targets(root):
    path = Path(root) / "settings.json"
    settings = json.loads(path.read_text()) if path.is_file() else {}
    result = []
    for label, kind, protocol, key, default in [
        ("Java", "minecraft-java", "tcp", "java_port", 25565),
        ("Bedrock", "minecraft-bedrock", "udp", "bedrock_port", 19132),
    ]:
        port = settings.get(key, default)
        if isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535:
            raise RuntimeError("Invalid Minecraft port in settings.json.")
        result.append((label, kind, protocol, port))
    return result


def matching(tunnel, agent, kind, protocol, port):
    origin = tunnel.get("origin") or {}
    data = origin.get("data") or {}
    return (tunnel.get("tunnel_type") == kind and tunnel.get("port_type") == protocol
            and tunnel.get("port_count") == 1 and tunnel.get("proxy_protocol") is None
            and origin.get("type") == "agent" and data.get("agent_id") == agent
            and data.get("local_ip") == "127.0.0.1" and data.get("local_port") == port)


def configure(root, create=False):
    game_targets = targets(root)
    secret = secret_in(root)
    agent_data = api(secret, "/agents/rundata", {})
    if create and agent_data.get("account_status") == "email-not-verified":
        raise SetupRequired("Verify your Playit email at https://playit.gg/account/details/security/verify, then reopen this launcher. The agent is already linked to your account.")
    agent = agent_data["agent_id"]
    listing = {"tunnel_id": None, "agent_id": agent}
    tunnels = api(secret, "/tunnels/list", listing)["tunnels"]
    results = []
    for label, kind, protocol, port in game_targets:
        existing = next((t for t in tunnels if matching(t, agent, kind, protocol, port)), None)
        if existing is None and create:
            api(secret, "/tunnels/create", {
                "name": "Infinity Armor " + label, "tunnel_type": kind,
                "port_type": protocol, "port_count": 1,
                "origin": {"type": "agent", "data": {"agent_id": agent,
                    "local_ip": "127.0.0.1", "local_port": port}},
                "enabled": True, "alloc": {"type": "region", "details": {"region": "global"}},
                "firewall_id": None, "proxy_protocol": None,
            })
            # Reload authoritative state; never infer an assigned address.
            tunnels = api(secret, "/tunnels/list", listing)["tunnels"]
            existing = next((t for t in tunnels if matching(t, agent, kind, protocol, port)), None)
        if existing:
            alloc = existing.get("alloc") or {}
            data = alloc.get("data") or {}
            host, public_port = data.get("ip_hostname"), data.get("port_start")
            if alloc.get("status") == "allocated" and host and public_port:
                results.append({"edition": label, "host": host, "port": public_port,
                    "active": bool(existing.get("active")), "verified": False})
    # No key, account identifier, or private dashboard URL goes in this shareable record.
    destination = Path(root) / ".remote/addresses.json"
    if destination.is_symlink():
        raise RuntimeError("The address record must be a local file.")
    temporary = destination.with_name("addresses-" + uuid.uuid4().hex + ".tmp")
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as output:
            json.dump({"addresses": results}, output, indent=2)
            output.write("\n")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return results


def display(addresses):
    if not addresses:
        print("No public address assigned yet. Finish Playit setup first.")
    for address in addresses:
        print(f"{address['edition']}: {address['host']}:{address['port']}"
              + (" (allocated; connection check still needed)" if address['active'] else " (inactive)"))
