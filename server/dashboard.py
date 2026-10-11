"""A loopback-only, session-protected control panel for the shared world."""
from collections import deque
import copy
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time
import urllib.request
import webbrowser
import community
import pinggy_joining


FRIEND_GUIDE = "Infinity_Armor_Friend_Connection_Guide.pdf"


class Panel:
    def __init__(self, launcher, args, root):
        self.launcher, self.args, self.root = launcher, args, root
        self.guard = threading.RLock()
        self.logs = deque(maxlen=240)
        self.state, self.error = "stopped", ""
        self.worker = None
        self.send_command = None
        self.stop_event = threading.Event()
        self.settings = self.valid_settings({"memory": args.memory, "java_port": args.java_port,
                         "bedrock_port": args.bedrock_port, "bind": args.bind})
        saved = root / "settings.json"
        if saved.exists():
            try:
                self.settings = self.valid_settings(json.loads(saved.read_text()))
            except (ValueError, TypeError, OSError):
                self.log("Saved settings could not be read; using the defaults.")
        self.addresses = launcher.join_addresses(self.arguments())
        self.remote_checked = 0.0
        self.remote_state = {"checked_at": None, "tunnels": [], "available": False}

    @staticmethod
    def valid_settings(data):
        if not isinstance(data, dict):
            raise ValueError("Choose your server settings first.")
        memory = data.get("memory", "2G")
        if memory not in ("2G", "3G", "4G", "6G", "8G"):
            raise ValueError("Choose a memory size from the list.")
        ports = [data.get("java_port", 25565), data.get("bedrock_port", 19132)]
        if any(type(p) is not int or not 1 <= p <= 65535 for p in ports):
            raise ValueError("Ports must be whole numbers between 1 and 65535.")
        bind = data.get("bind", "0.0.0.0")
        if bind not in ("0.0.0.0", "127.0.0.1"):
            raise ValueError("Choose a valid network setting.")
        return {"memory": memory, "java_port": ports[0], "bedrock_port": ports[1], "bind": bind,
                **community.settings(data)}

    def arguments(self):
        args = copy.copy(self.args)
        for key, value in self.settings.items():
            setattr(args, key, value)
        args.community = community.settings(self.settings)
        return args

    def accepted(self):
        return self.launcher.properties(self.root / "fabric/eula.txt").get("eula", "").lower() == "true" or self.args.accept_eula

    def log(self, line):
        with self.guard:
            self.logs.append(re.sub(r"\x1b\[[0-9;]*m", "", str(line)))

    def snapshot(self):
        with self.guard:
            remote = self.remote_joining()
            return {"state": self.state, "error": self.error, "settings": dict(self.settings),
                    "addresses": self.addresses, "eula_accepted": self.accepted(), "logs": list(self.logs),
                    "ai": community.ai_status(self.root), "roster": community.roster(self.root, self.state == "running"), "backups": community.backups(self.root),
                    "remote_joining": remote, "friend_guide": {"available": self.friend_guide_path(remote) is not None}}

    def remote_joining(self):
        """Read the owned local agent only; never return its config, logs, or credentials."""
        now = time.monotonic()
        if now - self.remote_checked >= 5 or not self.remote_checked:
            try:
                tunnels = pinggy_joining.public_status(self.root, pinggy_joining.tunnels(self.root))
                self.remote_state = {"checked_at": int(time.time()), "tunnels": tunnels, "available": True}
            except (OSError, RuntimeError, ValueError, TypeError, KeyError):
                self.remote_state = {"checked_at": int(time.time()), "tunnels": [], "available": False}
            self.remote_checked = now
        return copy.deepcopy(self.remote_state)

    def friend_guide_path(self, remote=None):
        """Offer the generated guide only while it names both current public endpoints."""
        remote = remote if remote is not None else self.remote_joining()
        tunnels = {item.get("edition"): item for item in remote.get("tunnels", []) if isinstance(item, dict)}
        if not all(tunnels.get(edition, {}).get("ready") for edition in ("Java", "Bedrock")):
            return None
        java = tunnels["Java"].get("address")
        bedrock = tunnels["Bedrock"].get("address")
        if not isinstance(java, dict) or not isinstance(bedrock, dict):
            return None
        candidates = (self.root / FRIEND_GUIDE, self.root.parent / FRIEND_GUIDE,
                      self.root.parent / "output/pdf" / FRIEND_GUIDE)
        for pdf in candidates:
            markdown = pdf.with_suffix(".md")
            if pdf.is_symlink() or markdown.is_symlink() or not pdf.is_file() or not markdown.is_file():
                continue
            try:
                pdf_info, markdown_info = pdf.stat(), markdown.stat()
                if (not 10 <= pdf_info.st_size <= 2_000_000 or
                    not 10 <= markdown_info.st_size <= 100_000 or
                    abs(pdf_info.st_mtime - markdown_info.st_mtime) > 10):
                    continue
                content = markdown.read_text(encoding="utf-8")
                with pdf.open("rb") as stream:
                    header = stream.read(5)
                if (re.search(r"Server Address: " + re.escape(f"{java['host']}:{java['port']}") + r"(?=\s|[*,.]|$)", content) and
                    re.search(r"Address: " + re.escape(str(bedrock['host'])) + r"(?=\s|[*,.]|$)", content) and
                    re.search(r"Port: " + re.escape(str(bedrock['port'])) + r"(?=\s|[*,.]|$)", content) and
                    header == b"%PDF-"):
                    return pdf
            except (OSError, UnicodeError, KeyError, TypeError):
                continue
        return None

    def start(self, data):
        with self.guard:
            if self.worker and self.worker.is_alive():
                raise ValueError("The world is already starting, running, or saving.")
            settings = self.valid_settings(data.get("settings", {}))
            if not self.accepted() and data.get("accept_eula") is not True:
                raise ValueError("Read and accept the Minecraft EULA before starting your world.")
            if (self.root / "launcher.lock").exists():
                raise ValueError("Another server launcher is open. Close it before starting here. See README.md if it crashed.")
            self.settings = settings
            temporary = self.root / "settings.json.tmp"
            temporary.write_text(json.dumps(settings, indent=2) + "\n")
            temporary.replace(self.root / "settings.json")
            args = self.arguments()
            args.accept_eula = True  # The checked form or existing file supplies explicit acceptance.
            self.addresses = self.launcher.join_addresses(args)
            self.error = ""
            self.logs.clear()
            self.state = "starting"
            self.stop_event.clear()

            def ready(addresses):
                with self.guard:
                    self.addresses = addresses
                    if not self.stop_event.is_set():
                        self.state = "running"

            def work():
                try:
                    self.launcher.run_server(args, self.stop_event, ready, self.root, console=False,
                                             on_console_ready=lambda send: setattr(self, "send_command", send))
                except Exception as error:
                    with self.guard:
                        self.error = str(error)
                        self.state = "error"
                    self.log(str(error))
                else:
                    with self.guard:
                        self.state = "stopped"
                finally:
                    self.send_command = None

            self.worker = threading.Thread(target=work, name="infinity-world")
            self.worker.start()

    def stop(self):
        with self.guard:
            if self.worker and self.worker.is_alive():
                self.state = "stopping"
                self.stop_event.set()
                self.log("Saving and stopping. If a download is in progress, it will finish first.")

    def command(self, value):
        with self.guard:
            if self.state != "running" or not self.send_command:
                raise ValueError("Start the world before sending a command.")
            if not isinstance(value, str) or not value.strip() or len(value) > 512 or any(ord(c) < 32 for c in value):
                raise ValueError("Enter one server command on a single line.")
            command = value.strip().lstrip("/")
            if command.lower() == "stop":
                self.stop()
            else:
                self.send_command(command)
                self.log("> " + command)

    def backup(self):
        with self.guard:
            if self.worker and self.worker.is_alive():
                raise ValueError("Save & Stop the world before making a backup.")
            self.state, self.error = "backing_up", ""
            def work():
                try:
                    name = community.backup(self.root, self.launcher.properties(self.root / "fabric/server.properties"))
                    if name is None:
                        raise ValueError("No saved world yet. Start your world once before backing it up.")
                    self.log("Backup saved: backups/" + name)
                    with self.guard:
                        self.state = "stopped"
                except Exception as error:
                    with self.guard:
                        self.state, self.error = "error", str(error)
            self.worker = threading.Thread(target=work, name="infinity-backup")
            self.worker.start()

    def admin_code(self, data):
        with self.guard:
            if (self.worker and self.worker.is_alive()) or (self.root / "launcher.lock").exists():
                raise ValueError("Save & Stop the world before changing the Admin code.")
            community.configure_admin_code(self.root, data.get("enabled"), data.get("code", ""))
            self.log("Private Admin code settings saved. Changes apply next start.")

    def ai_config(self, data):
        with self.guard:
            if self.state in ("starting", "running", "stopping", "backing_up") or (self.worker and self.worker.is_alive()) or (self.root / "launcher.lock").exists():
                raise ValueError("Save & Stop the world before changing AI chat settings.")
            community.configure_ai(self.root, data)
            self.log("Private AI chat settings saved. Changes apply next start.")

    def close(self):
        self.stop()
        if self.worker:
            self.worker.join()


class PanelServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False


def handler_for(panel, token, page):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def respond(self, code, data, content_type="application/json"):
            body = data if isinstance(data, bytes) else json.dumps(data).encode()
            self.send_response(code)
            self.send_header("Content-Type", content_type if content_type == "application/pdf" else content_type + "; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self, mutation=False):
            host = "127.0.0.1:" + str(self.server.server_port)
            origin = self.headers.get("Origin")
            return (self.headers.get("Host") == host and
                    secrets.compare_digest(self.headers.get("X-Session-Token", ""), token) and
                    (origin == "http://" + host if mutation else origin in (None, "http://" + host)))

        def do_GET(self):
            if self.path == "/":
                self.respond(200, page, "text/html")
            elif self.path == "/api/status" and self.authorized():
                self.respond(200, panel.snapshot())
            elif self.path == "/api/friend-guide" and self.authorized():
                guide = panel.friend_guide_path()
                if guide is None:
                    self.respond(404, {"error": "No current friend guide matches both public addresses."})
                else:
                    try:
                        self.respond(200, guide.read_bytes(), "application/pdf")
                    except OSError:
                        self.respond(404, {"error": "The friend guide is unavailable."})
            else:
                self.respond(403, {"error": "Reopen the launcher to access this control panel."})

        def do_POST(self):
            if not self.authorized(mutation=True):
                self.respond(403, {"error": "This request did not come from your control panel."})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    raise ValueError("Invalid request size")
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError("Invalid form")
                if self.path == "/api/start":
                    panel.start(data)
                elif self.path == "/api/stop":
                    panel.stop()
                elif self.path == "/api/command":
                    panel.command(data.get("command"))
                elif self.path == "/api/moderate":
                    panel.command(community.moderation_command(data.get("action"), data.get("player")))
                elif self.path == "/api/membership":
                    panel.command(community.membership_command(data.get("action"), data.get("player"), data.get("tier"), data.get("days")))
                elif self.path == "/api/admin-code":
                    panel.admin_code(data)
                elif self.path == "/api/ai-config":
                    panel.ai_config(data)
                elif self.path == "/api/backup":
                    panel.backup()
                elif self.path == "/api/quit":
                    if panel.snapshot()["state"] in ("starting", "running", "stopping", "backing_up"):
                        raise ValueError("Save & Stop your world before closing the launcher.")
                    threading.Thread(target=self.server.shutdown, daemon=True).start()
                else:
                    raise ValueError("Unknown action")
                self.respond(200, panel.snapshot())
            except (ValueError, OSError) as error:
                self.respond(400, {"error": str(error)})
    return Handler


def acquire_panel_lock(root):
    lock = (root / ".dashboard.lock").open("a+b")
    try:
        if os.name == "nt":
            import msvcrt
            if lock.seek(0, 2) == 0:
                lock.write(b"0")
                lock.flush()
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        lock.close()
        return None
    return lock


def serve(launcher, args):
    root = launcher.ROOT
    record = root / ".dashboard.json"
    lease = acquire_panel_lock(root)
    if lease is None:
        # A repeated double-click reopens the same control panel, never a second world.
        for attempt in range(8):
            try:
                existing = json.loads(record.read_text())
                url, token = existing["url"], existing["token"]
                if not re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}", url):
                    raise ValueError("Unexpected control panel address")
                request = urllib.request.Request(url + "/api/status", headers={"X-Session-Token": token})
                with urllib.request.urlopen(request, timeout=2) as response:
                    if response.status == 200:
                        address = url + "/#" + token
                        print("Your control panel is already open: " + address)
                        if not args.no_browser:
                            webbrowser.open(address)
                        return
            except (OSError, ValueError, KeyError):
                lease = acquire_panel_lock(root)
                if lease is not None:
                    break
                time.sleep(0.25)
        if lease is None:
            raise RuntimeError("The control panel is already opening. Use its existing Terminal window.")
    panel = None
    httpd = None
    try:
        panel = Panel(launcher, args, root)
        launcher.OUTPUT = panel.log
        token = secrets.token_urlsafe(32)
        httpd = PanelServer(("127.0.0.1", 0), handler_for(panel, token, (root / "dashboard.html").read_bytes()))
        url = "http://127.0.0.1:" + str(httpd.server_port)
        fd = os.open(record, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as file:
            json.dump({"url": url, "token": token}, file)
        address = url + "/#" + token
        print("\nInfinity Armor control panel: " + address, flush=True)
        print("Keep this window open. Use Save & Stop in the panel before closing it.", flush=True)
        if not args.no_browser:
            webbrowser.open(address)
        httpd.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        print("\nSaving and closing the launcher...")
    finally:
        if panel:
            panel.close()
        launcher.OUTPUT = None
        if httpd:
            httpd.server_close()
        record.unlink(missing_ok=True)
        lease.close()
