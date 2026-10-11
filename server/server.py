#!/usr/bin/env python3
"""Run the Infinity Fabric world and its Geyser bridge together, with automatic Java setup."""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
import socket
import stat

from runtime import install_java, java_in
import community

ROOT = Path(__file__).resolve().parent
EULA_URL = "https://www.minecraft.net/eula"
OUTPUT = None


def emit(message):
    print(message, flush=True)
    if OUTPUT:
        OUTPUT(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(entry, root=ROOT):
    destination = (root / entry["path"]).resolve()
    if not destination.is_relative_to(root.resolve()):
        raise RuntimeError("Dependency path escapes the server folder")
    if destination.is_file() and sha256(destination) == entry["sha256"]:
        return
    if not entry["url"].startswith("https://"):
        raise RuntimeError("Downloads must use HTTPS")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".download")
    emit("Downloading " + destination.name)
    try:
        request = urllib.request.Request(entry["url"], headers={"User-Agent": "InfinityArmor-Crossplay/2.4.0"})
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        if sha256(temporary) != entry["sha256"]:
            raise RuntimeError("Checksum mismatch: " + destination.name)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def setup(root=ROOT):
    lock = json.loads((root / "dependencies.lock.json").read_text())
    for entry in lock["downloads"]:
        fetch(entry, root)
    for entry in lock["bundled"]:
        file = root / entry["path"]
        if not file.is_file() or sha256(file) != entry["sha256"]:
            raise RuntimeError("Bundled file is missing or changed: " + entry["path"])


def properties(path):
    result = {}
    if path.exists():
        for line in path.read_text().splitlines():
            if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
                key, value = line.split("=", 1)
                result[key.strip()] = value.strip()
    return result


def configure(args, root=ROOT):
    fabric = root / "fabric"
    geyser = root / "geyser"
    (fabric / "config").mkdir(parents=True, exist_ok=True)
    (geyser / "custom_mappings").mkdir(parents=True, exist_ok=True)
    propfile = fabric / "server.properties"
    props = properties(propfile)
    for key, value in {
        "motd": "Infinity Armor - Java + Bedrock", "gamemode": "survival",
        "difficulty": "normal", "view-distance": "8", "simulation-distance": "6",
        "max-players": "10", "level-name": "world", "enable-rcon": "false",
    }.items():
        props.setdefault(key, value)
    props.update({"server-port": str(args.java_port), "server-ip": args.bind,
                  "online-mode": "true", "enforce-secure-profile": "false"})
    community_settings = getattr(args, "community", None)
    if community_settings is not None:
        community.configure(root, props, community_settings)
    propfile.write_text("\n".join(k + "=" + v for k, v in props.items()) + "\n")
    autohost = fabric / "config/polymer/auto-host.json"
    autohost.parent.mkdir(parents=True, exist_ok=True)
    if not autohost.exists():
        autohost.write_text(json.dumps({"enabled": True, "required": False, "mod_override": False,
            "type": "polymer:automatic", "settings": {}, "resource_pack_status_dialog": False,
            "message": "Download Infinity Armor's textures for this shared world."}, indent=2) + "\n")
    # This config is launcher-managed; extra server gameplay settings live in server.properties.
    (geyser / "config.yml").write_text(
        "config-version: 8\n"
        "bedrock:\n  address: " + json.dumps(args.bind) + "\n  port: " + str(args.bedrock_port) + "\n"
        "java:\n  address: 127.0.0.1\n  port: " + str(args.java_port) + "\n  auth-type: floodgate\n"
        "gameplay:\n  enable-custom-content: true\n  force-resource-packs: true\n  server-name: "
        + json.dumps(community_settings["server_name"] if community_settings else "Infinity Armor") + "\n"
        "advanced:\n  floodgate-key-file: key.pem\n"
        "motd:\n  primary-motd: " + json.dumps(community_settings["server_name"] if community_settings else "Infinity Armor")
        + "\n  secondary-motd: Shared Java + Bedrock world\n  passthrough-player-counts: true\n  max-players: " + str(int(props["max-players"])) + "\n"
    )


def accept_eula(flag, root=ROOT):
    file = root / "fabric/eula.txt"
    if properties(file).get("eula", "").lower() == "true":
        return
    if not flag:
        if not sys.stdin.isatty():
            raise RuntimeError("Read " + EULA_URL + " and rerun with --accept-eula if you agree.")
        print("Minecraft requires you to accept its EULA: " + EULA_URL)
        if input('Type "agree" to accept and start, or anything else to cancel: ').strip().lower() != "agree":
            raise RuntimeError("Cancelled; the Minecraft EULA was not accepted.")
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text("# Accepted by the person running this launcher.\neula=true\n")


def java_command(explicit, root=ROOT, install=False):
    choices = [explicit, os.environ.get("INFINITY_JAVA")]
    if os.environ.get("JAVA_HOME"):
        choices.append(str(Path(os.environ["JAVA_HOME"]) / "bin/java"))
    if sys.platform == "darwin":
        result = subprocess.run(["/usr/libexec/java_home", "-v", "21+"], capture_output=True, text=True)
        if result.returncode == 0:
            choices.append(str(Path(result.stdout.strip()) / "bin/java"))
    managed = java_in(root / ".runtime/java")
    if managed:
        choices.append(str(managed))
    choices.append(shutil.which("java"))
    for java in filter(None, choices):
        try:
            result = subprocess.run([java, "-version"], capture_output=True, text=True, timeout=15)
            match = re.search(r'version "(\d+)', result.stderr + result.stdout)
            if result.returncode == 0 and match and int(match[1]) >= 21:
                return java
        except (OSError, subprocess.SubprocessError):
            pass
        if explicit:
            break
    if install and not explicit:
        return java_command(install_java(root, fetch, emit), root)
    raise RuntimeError("Java 21 or newer is required. Open Start-Mac.command/start.bat for automatic setup, or use --java /path/to/java.")


class Service:
    def __init__(self, name, command, cwd, ready_text, stop_command="stop"):
        self.name = name
        self.stop_command = stop_command
        self.ready = threading.Event()
        self.process = subprocess.Popen(command, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1,
            start_new_session=(os.name != "nt"))
        self.log = (cwd / "launcher.log").open("w", encoding="utf-8")
        self.thread = threading.Thread(target=self.read_output, args=(ready_text,), daemon=True)
        self.thread.start()

    def read_output(self, ready_text):
        try:
            for line in self.process.stdout:
                self.log.write(line)
                self.log.flush()
                emit("[" + self.name + "] " + line.rstrip())
                if ready_text in line:
                    self.ready.set()
        finally:
            self.log.close()

    def wait_ready(self, timeout=300, cancel=None):
        deadline = time.monotonic() + timeout
        while not self.ready.wait(0.2):
            if cancel is not None and cancel.is_set():
                raise InterruptedError("Startup cancelled")
            if self.process.poll() is not None:
                raise RuntimeError(self.name + " stopped before startup completed. See its launcher.log.")
            if time.monotonic() >= deadline:
                raise RuntimeError(self.name + " startup timed out. See its launcher.log.")

    def send(self, command):
        if self.process.poll() is None:
            self.process.stdin.write(command + "\n")
            self.process.stdin.flush()

    def stop(self):
        if self.process.poll() is not None:
            return
        try:
            self.send(self.stop_command)
            self.process.wait(timeout=45)
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait()


def copy_bridge_data(root=ROOT):
    source = root / "fabric/crossplay-export"
    for name in ["infinity-items.json", "infinity-blocks.json"]:
        file = source / name
        if not file.is_file():
            raise RuntimeError("Fabric did not export " + name + "; check that the crossplay mod loaded.")
        shutil.copy2(file, root / "geyser/custom_mappings" / name)
    key = root / "fabric/config/floodgate/key.pem"
    if not key.is_file():
        raise RuntimeError("Floodgate did not generate its key. See fabric/launcher.log.")
    destination = root / "geyser/key.pem"
    shutil.copy2(key, destination)
    destination.chmod(0o600)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--setup", action="store_true", help="Download pinned dependencies without starting or accepting the EULA")
    parser.add_argument("--check", action="store_true", help="Check Java and installed file hashes without starting")
    parser.add_argument("--dashboard", action="store_true", help="Open the local Start/Stop control panel")
    parser.add_argument("--console", action="store_true", help="Use the terminal instead of the control panel")
    parser.add_argument("--no-browser", action="store_true", help="Print the control panel URL without opening a browser")
    parser.add_argument("--accept-eula", action="store_true", help="Accept https://www.minecraft.net/eula")
    parser.add_argument("--java", help="Java 21+ executable")
    parser.add_argument("--memory", default="2G", help="Java world heap, e.g. 2G or 4G; Geyser uses up to 512M")
    parser.add_argument("--java-port", type=int, default=25565)
    parser.add_argument("--bedrock-port", type=int, default=19132)
    parser.add_argument("--bind", default="0.0.0.0", choices=["0.0.0.0", "127.0.0.1"], help="All interfaces, or local testing only")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[1-9][0-9]*[MG]", args.memory, re.I):
        parser.error("--memory must look like 2G or 2048M")
    if not all(1 <= port <= 65535 for port in [args.java_port, args.bedrock_port]):
        parser.error("Ports must be between 1 and 65535")
    return args


def check_ports(args):
    for kind, port, edition in [(socket.SOCK_STREAM, args.java_port, "Java"), (socket.SOCK_DGRAM, args.bedrock_port, "Bedrock")]:
        try:
            with socket.socket(socket.AF_INET, kind) as listener:
                if kind == socket.SOCK_STREAM:
                    # A recently saved world can leave closed TCP connections in
                    # TIME_WAIT. Unix reuse permits restarting, while Windows
                    # needs exclusive binding to avoid sharing a live port.
                    option = getattr(socket, "SO_EXCLUSIVEADDRUSE", socket.SO_REUSEADDR)
                    listener.setsockopt(socket.SOL_SOCKET, option, 1)
                listener.bind((args.bind, port))
                if kind == socket.SOCK_STREAM:
                    listener.listen(1)
        except OSError as error:
            raise RuntimeError(edition + " port " + str(port) + " is already in use or unavailable. Stop the other server or choose a different port in Settings.") from error


def lan_address():
    candidates = []
    # Prefer a physical interface over a VPN's default route.
    try:
        if sys.platform == "darwin":
            interfaces = subprocess.run(["/usr/sbin/networksetup", "-listallhardwareports"], capture_output=True, text=True, timeout=4)
            for interface in re.findall(r"Device: (en[0-9]+)", interfaces.stdout):
                result = subprocess.run(["/usr/sbin/ipconfig", "getifaddr", interface], capture_output=True, text=True, timeout=2)
                if result.returncode == 0:
                    candidates.append(result.stdout.strip())
        elif sys.platform.startswith("linux") and shutil.which("ip"):
            result = subprocess.run(["ip", "-j", "-4", "addr"], capture_output=True, text=True, timeout=4)
            for interface in json.loads(result.stdout):
                if interface.get("operstate") == "UP" and not re.match(r"(?:lo|tun|tap|wg|docker|br|veth|virbr|tailscale)", interface["ifname"]):
                    candidates.extend(info["local"] for info in interface.get("addr_info", []) if info.get("scope") == "global")
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    try:
        candidates.extend(socket.gethostbyname_ex(socket.gethostname())[2])
    except OSError:
        pass
    try:
        # UDP connect selects a route but sends no packet.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            candidates.append(probe.getsockname()[0])
    except OSError:
        pass
    networks = [ipaddress.ip_network(n) for n in ("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")]
    for candidate in candidates:
        try:
            if any(ipaddress.ip_address(candidate) in network for network in networks):
                return candidate
        except ValueError:
            pass
    return ""


def join_addresses(args):
    address = "127.0.0.1" if args.bind == "127.0.0.1" else lan_address()
    return {"host": address, "java": address + ":" + str(args.java_port) if address else "",
            "local_java": "localhost:" + str(args.java_port), "bedrock_port": args.bedrock_port,
            "local_only": args.bind == "127.0.0.1"}


def acquire_launcher_lock(args, root):
    """Recover a crashed POSIX launcher only when its PID is gone and ports are free."""
    path = root / "launcher.lock"
    try:
        return os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except FileExistsError:
        return inspect_stale_launcher_lock(args, root, replace=True)


def check_launcher_lock(args, root):
    """Validate dashboard preflight without removing a lock or changing settings."""
    path = root / "launcher.lock"
    if path.exists() or path.is_symlink():
        inspect_stale_launcher_lock(args, root, replace=False)


def inspect_stale_launcher_lock(args, root, *, replace):
    path = root / "launcher.lock"
    if os.name != "posix":
        raise RuntimeError("launcher.lock exists. Close the other launcher before starting this world.")

    import fcntl
    previous = None
    try:
        previous = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        fcntl.flock(previous, fcntl.LOCK_EX | fcntl.LOCK_NB)
        info = os.fstat(previous)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or not 0 < info.st_size <= 32:
            raise RuntimeError("launcher.lock is not a valid lock owned by this user.")
        value = os.read(previous, 33).decode("ascii").strip()
        if not value.isdecimal() or int(value) < 1:
            raise RuntimeError("launcher.lock does not contain a valid process ID.")
        try:
            os.kill(int(value), 0)
        except ProcessLookupError:
            pass
        else:
            raise RuntimeError("launcher.lock belongs to a process that may still be running.")
        check_ports(args)
        current = path.lstat()
        if (current.st_dev, current.st_ino) != (info.st_dev, info.st_ino):
            raise RuntimeError("launcher.lock changed during recovery.")
        if not replace:
            return None  # The real start repeats these checks before acquiring its lease.
        path.unlink()
        return os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    except (OSError, UnicodeError, ValueError) as error:
        raise RuntimeError("launcher.lock could not be safely recovered. Close the other launcher and check the game ports.") from error
    finally:
        if previous is not None:
            os.close(previous)


def run_server(args, stop_requested=None, on_ready=None, root=ROOT, console=True, on_console_ready=None):
    stop_requested = stop_requested or threading.Event()
    # An exclusive lock prevents two launchers from changing files under a running world.
    lock_path = root / "launcher.lock"
    lock_fd = acquire_launcher_lock(args, root)
    services = []
    try:
        with os.fdopen(lock_fd, "w") as file:
            file.write(str(os.getpid()))
        check_ports(args)
        backup_name = community.backup(root, properties(root / "fabric/server.properties"), locked=True)
        if backup_name:
            emit("World backed up before startup: backups/" + backup_name)
        if stop_requested.is_set():
            return
        emit("Preparing your shared world. First setup can take a few minutes.")
        java = java_command(args.java, root, install=True)
        if stop_requested.is_set():
            return
        setup(root)
        if stop_requested.is_set():
            return
        accept_eula(args.accept_eula, root)
        configure(args, root)
        for name in ["infinity-items.json", "infinity-blocks.json"]:
            (root / "fabric/crossplay-export" / name).unlink(missing_ok=True)
        fabric = Service("Java", [java, "-Xms512M", "-Xmx" + args.memory,
            "-jar", "fabric-server-launch.jar", "nogui"], root / "fabric", "[Infinity] Community and crossplay ready.")
        services.append(fabric)
        fabric.wait_ready(cancel=stop_requested)
        copy_bridge_data(root)
        geyser = Service("Bedrock", [java, "-Xms128M", "-Xmx512M", "-jar", "Geyser-Standalone.jar", "--nogui"],
            root / "geyser", "Started Geyser on", stop_command="geyser stop")
        services.append(geyser)
        geyser.wait_ready(120, cancel=stop_requested)
        if on_console_ready:
            on_console_ready(fabric.send)
        addresses = join_addresses(args)
        emit("\nShared world ready. Java: " + (addresses["java"] or addresses["local_java"]) + "; Bedrock: " + (addresses["host"] or "check your LAN IP") + ", port " + str(args.bedrock_port))
        emit("Keep the launcher open. Save & Stop in the panel, or Ctrl+C here, saves the world.")
        if on_ready:
            on_ready(addresses)

        def read_console():
            for line in sys.stdin:
                command = line.strip()
                if command.lower() == "stop":
                    stop_requested.set()
                    return
                if command:
                    try:
                        fabric.send(command)
                    except (BrokenPipeError, OSError):
                        return

        if console:
            threading.Thread(target=read_console, daemon=True).start()
        while not stop_requested.wait(0.5):
            for service in services:
                if service.process.poll() is not None:
                    raise RuntimeError(service.name + " stopped; shutting down the other service.")
    except KeyboardInterrupt:
        emit("\nSaving and stopping...")
    except InterruptedError:
        emit("Startup cancelled; stopping any running services...")
    finally:
        for service in reversed(services):
            service.stop()
        lock_path.unlink(missing_ok=True)


def main(argv=None):
    args = parse_args(argv)
    if args.check:
        java = java_command(args.java)
        lock = json.loads((ROOT / "dependencies.lock.json").read_text())
        missing = [entry["path"] for entry in lock["downloads"] + lock["bundled"]
                   if not (ROOT / entry["path"]).is_file() or sha256(ROOT / entry["path"]) != entry["sha256"]]
        if missing:
            raise RuntimeError("Missing/changed files; run --setup: " + ", ".join(missing))
        emit("Java OK: " + java + "\nAll pinned files verified. No server was started.")
        return
    if args.setup:
        if (ROOT / "launcher.lock").exists():
            raise RuntimeError("Stop the running world before downloading or repairing server files.")
        setup()
        java_command(args.java, install=True)
        emit("Setup complete. Open the launcher to start. No EULA was accepted.")
        return
    if args.dashboard and not args.console:
        from dashboard import serve
        serve(sys.modules[__name__], args)
    else:
        run_server(args)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        print("Error: " + str(error), file=sys.stderr)
        sys.exit(1)
