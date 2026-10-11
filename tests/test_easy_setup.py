import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
import server as launcher
import dashboard
import runtime


class EasySetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.args = launcher.parse_args(["--bind", "127.0.0.1"])

    def tearDown(self):
        self.temp.cleanup()

    def panel(self):
        return dashboard.Panel(launcher, self.args, self.root)

    def test_panel_requires_explicit_eula_and_does_not_create_files(self):
        panel = self.panel()
        with patch.object(launcher, "run_server") as start:
            with self.assertRaisesRegex(ValueError, "EULA"):
                panel.start({"accept_eula": False})
            start.assert_not_called()
        self.assertFalse((self.root / "fabric/eula.txt").exists())
        self.assertFalse((self.root / "settings.json").exists())

    def test_panel_lifecycle_persists_settings_and_rejects_duplicate_start(self):
        panel = self.panel()
        began = threading.Event()
        def fake_run(args, stop, ready, root, console, on_console_ready):
            self.assertEqual(args.memory, "4G")
            self.assertFalse(console)
            on_console_ready(Mock())
            ready(launcher.join_addresses(args))
            began.set()
            stop.wait(5)
        with patch.object(launcher, "run_server", side_effect=fake_run):
            panel.start({"accept_eula": True, "settings": {"memory": "4G", "bind": "127.0.0.1", "java_port": 25599}})
            try:
                self.assertTrue(began.wait(2))
                self.assertEqual(panel.snapshot()["state"], "running")
                self.assertEqual(panel.snapshot()["addresses"]["java"], "127.0.0.1:25599")
                with self.assertRaisesRegex(ValueError, "already"):
                    panel.start({"accept_eula": True})
            finally:
                panel.close()
        self.assertEqual(panel.snapshot()["state"], "stopped")
        self.assertEqual(self.panel().settings["memory"], "4G")
        self.assertFalse((self.root / "fabric/eula.txt").exists())

    def test_panel_console_requires_running_server_and_single_command(self):
        panel = self.panel()
        with self.assertRaisesRegex(ValueError, "Start the world"):
            panel.command("op TestPlayer")
        panel.state = "running"
        panel.send_command = Mock()
        with self.assertRaises(ValueError):
            panel.command("say hello\nstop")
        panel.command("/op TestPlayer")
        panel.send_command.assert_called_once_with("op TestPlayer")

    def test_admin_code_is_private_hashed_and_requires_stopped_world(self):
        import hashlib
        panel = self.panel()
        panel.admin_code({"enabled": True, "code": "test-only-secret"})
        path = self.root / "fabric/config/infinity-memberships.json"
        config = json.loads(path.read_text())
        self.assertTrue(config["adminCodeEnabled"])
        self.assertEqual(config["adminCodeSha256"], hashlib.sha256(b"test-only-secret").hexdigest())
        self.assertNotIn("test-only-secret", json.dumps(panel.snapshot()))
        self.assertNotIn("test-only-secret", path.read_text())
        panel.admin_code({"enabled": False, "code": ""})
        self.assertFalse(json.loads(path.read_text())["adminCodeEnabled"])
        (self.root / "launcher.lock").write_text("running")
        with self.assertRaisesRegex(ValueError, "Stop"):
            panel.admin_code({"enabled": True, "code": "new-test-secret"})

    def test_admin_code_validation_preserves_existing_configuration(self):
        panel = self.panel()
        with self.assertRaises(ValueError):
            panel.admin_code({"enabled": True, "code": ""})
        for code in ["short", "bad\nsecret", "x" * 129]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                panel.admin_code({"enabled": True, "code": code})
        path = self.root / "fabric/config/infinity-memberships.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("broken private configuration")
        with self.assertRaises(ValueError):
            panel.admin_code({"enabled": True, "code": "test-only-secret"})
        self.assertEqual(path.read_text(), "broken private configuration")

    def test_ai_key_is_private_and_never_returned_in_status_or_logs(self):
        panel = self.panel()
        panel.ai_config({"enabled": True, "apiKey": "test-only-ai-key-123"})
        config = self.root / "fabric/config/infinity-ai.json"
        key = self.root / "fabric/config/infinity-ai-key.txt"
        self.assertEqual(key.read_text(), "test-only-ai-key-123")
        self.assertEqual(key.stat().st_mode & 0o777, 0o600)
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertNotIn("test-only-ai-key-123", config.read_text())
        snapshot = panel.snapshot()
        self.assertEqual(snapshot["ai"], {"enabled": True, "provider": "openai", "codexExecutable": "", "model": "gpt-6-luna", "ownerOnly": True, "dailyRequestLimit": 50, "hasKey": True})
        self.assertNotIn("test-only-ai-key-123", json.dumps(snapshot))
        self.assertNotIn("apiKey", json.dumps(snapshot))
        self.assertFalse(list(config.parent.glob("*.tmp")))
        panel.ai_config({"enabled": True, "model": "x" * 100, "apiKey": "x" * 4096})
        self.assertEqual(key.stat().st_size, 4096)
        self.assertEqual(len(panel.snapshot()["ai"]["model"]), 100)

    def test_ai_validation_keeps_existing_configuration_and_key(self):
        panel = self.panel()
        panel.ai_config({"enabled": True, "apiKey": "test-only-ai-key-123"})
        config = self.root / "fabric/config/infinity-ai.json"
        key = self.root / "fabric/config/infinity-ai-key.txt"
        original = config.read_text()
        for invalid in [{"enabled": "yes"}, {"ownerOnly": 1}, {"model": "bad model"}, {"model": "x" * 101}, {"dailyRequestLimit": True}, {"dailyRequestLimit": -1}, {"apiKey": "secret\ninvalid"}]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                panel.ai_config({"enabled": True, **invalid})
            self.assertEqual(config.read_text(), original)
            self.assertEqual(key.read_text(), "test-only-ai-key-123")
        config.write_text("broken private configuration")
        with self.assertRaises(ValueError):
            panel.ai_config({"enabled": True, "apiKey": "replacement-test-key"})
        self.assertEqual(config.read_text(), "broken private configuration")
        self.assertEqual(key.read_text(), "test-only-ai-key-123")

    def test_ai_configuration_requires_stopped_world_and_blank_preserves_key(self):
        panel = self.panel()
        with self.assertRaisesRegex(ValueError, "API key"):
            panel.ai_config({"enabled": True})
        panel.ai_config({"enabled": False})
        self.assertFalse((self.root / "fabric/config/infinity-ai-key.txt").exists())
        panel.ai_config({"enabled": True, "apiKey": "test-only-ai-key-123"})
        panel.ai_config({"enabled": False, "apiKey": "", "model": "gpt-6-luna", "ownerOnly": False, "dailyRequestLimit": 0})
        self.assertEqual(panel.snapshot()["ai"]["dailyRequestLimit"], 0)
        key = self.root / "fabric/config/infinity-ai-key.txt"
        self.assertEqual(key.read_text(), "test-only-ai-key-123")
        self.assertFalse(panel.snapshot()["ai"]["enabled"])
        panel.state = "running"
        with self.assertRaisesRegex(ValueError, "Stop"):
            panel.ai_config({"enabled": True})
        panel.state = "stopped"
        (self.root / "launcher.lock").write_text("running")
        with self.assertRaisesRegex(ValueError, "Stop"):
            panel.ai_config({"enabled": True})
        self.assertEqual(key.read_text(), "test-only-ai-key-123")

    def test_codex_configuration_needs_no_api_key_and_preserves_unknown_fields(self):
        panel = self.panel()
        config = self.root / "fabric/config/infinity-ai.json"
        config.parent.mkdir(parents=True)
        config.write_text(json.dumps({"timeoutSeconds": 73, "future": {"enabled": True}}))
        executable = str(Path(sys.executable).resolve())
        with patch("subprocess.run") as run:
            panel.ai_config({"enabled": True, "provider": "codex", "codexExecutable": executable})
            run.assert_not_called()
        saved = json.loads(config.read_text())
        self.assertEqual(saved["provider"], "codex")
        self.assertEqual(saved["codexExecutable"], executable)
        self.assertEqual(saved["future"], {"enabled": True})
        self.assertEqual(saved["timeoutSeconds"], 73)
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertFalse((self.root / "fabric/config/infinity-ai-key.txt").exists())
        self.assertFalse(panel.snapshot()["ai"]["hasKey"])
        self.assertTrue(panel.snapshot()["ai"]["ownerOnly"])
        self.assertNotIn("future", panel.snapshot()["ai"])

    def test_codex_invalid_provider_path_or_public_access_preserves_saved_secrets(self):
        panel = self.panel()
        panel.ai_config({"enabled": True, "apiKey": "test-only-ai-key-123"})
        config = self.root / "fabric/config/infinity-ai.json"
        key = self.root / "fabric/config/infinity-ai-key.txt"
        original = config.read_text()
        executable = str(Path(sys.executable).resolve())
        plain_file = self.root / "not-executable"
        plain_file.write_text("not a program")
        plain_file.chmod(0o600)
        base = {"enabled": True, "provider": "codex", "codexExecutable": executable}
        for invalid in [{"provider": "unknown"}, {"provider": []}, {"ownerOnly": False},
                        {"codexExecutable": ""}, {"codexExecutable": "codex"},
                        {"codexExecutable": executable + " --help"},
                        {"codexExecutable": str(self.root / "missing")},
                        {"codexExecutable": str(self.root)}, {"codexExecutable": str(plain_file)},
                        {"codexExecutable": executable + "\n"}, {"codexExecutable": None}]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                panel.ai_config({**base, **invalid})
            self.assertEqual(config.read_text(), original)
            self.assertEqual(key.read_text(), "test-only-ai-key-123")
        with self.assertRaisesRegex(ValueError, "existing CLI login"):
            panel.ai_config({**base, "apiKey": "replacement-test-only-key"})
        self.assertEqual(config.read_text(), original)
        self.assertEqual(key.read_text(), "test-only-ai-key-123")

    def test_codex_switch_keeps_api_key_and_missing_executable_can_be_disabled(self):
        panel = self.panel()
        panel.ai_config({"enabled": True, "apiKey": "test-only-ai-key-123"})
        key = self.root / "fabric/config/infinity-ai-key.txt"
        executable = self.root / "codex with spaces"
        executable.write_text("#!/bin/sh\nexit 99\n")
        executable.chmod(0o700)
        options = {"enabled": True, "provider": "codex", "codexExecutable": str(executable)}
        panel.ai_config(options)
        self.assertEqual(key.read_text(), "test-only-ai-key-123")
        executable.unlink()
        self.assertEqual(panel.snapshot()["ai"]["provider"], "codex")
        with self.assertRaisesRegex(ValueError, "existing executable"):
            panel.ai_config(options)
        panel.ai_config({**options, "enabled": False})
        self.assertFalse(panel.snapshot()["ai"]["enabled"])
        self.assertEqual(key.read_text(), "test-only-ai-key-123")
        panel.ai_config({"enabled": True, "provider": "openai"})
        self.assertEqual(panel.snapshot()["ai"]["provider"], "openai")
        self.assertEqual(key.read_text(), "test-only-ai-key-123")

    def test_codex_configuration_is_blocked_while_world_or_backup_is_active(self):
        panel = self.panel()
        options = {"enabled": True, "provider": "codex", "codexExecutable": str(Path(sys.executable).resolve())}
        for state in ("starting", "running", "stopping", "backing_up"):
            panel.state = state
            with self.subTest(state=state), self.assertRaisesRegex(ValueError, "Stop"):
                panel.ai_config(options)
        panel.state = "stopped"
        (self.root / "launcher.lock").write_text("running")
        with self.assertRaisesRegex(ValueError, "Stop"):
            panel.ai_config(options)
        self.assertFalse((self.root / "fabric/config/infinity-ai.json").exists())

    def test_panel_reports_worker_failure(self):
        panel = self.panel()
        with patch.object(launcher, "run_server", side_effect=RuntimeError("Java port is already in use")):
            panel.start({"accept_eula": True})
            panel.worker.join(2)
        self.assertEqual(panel.snapshot()["state"], "error")
        self.assertIn("already in use", panel.snapshot()["error"])

    def test_invalid_settings_and_existing_world_lock_do_not_start(self):
        panel = self.panel()
        for settings in [{"memory": "0G"}, {"java_port": True}, {"bedrock_port": 65536}, {"bind": "1.2.3.4"}]:
            with self.subTest(settings=settings), self.assertRaises(ValueError):
                panel.start({"accept_eula": True, "settings": settings})
        (self.root / "launcher.lock").write_text("test")
        with self.assertRaisesRegex(ValueError, "Another server"):
            panel.start({"accept_eula": True})
        self.assertIsNone(panel.worker)

    def test_http_rejects_missing_token_and_foreign_origin(self):
        panel = self.panel()
        httpd = dashboard.PanelServer(("127.0.0.1", 0), dashboard.handler_for(panel, "test-token", b"<html>panel</html>"))
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        origin = "http://127.0.0.1:" + str(httpd.server_port)
        def request(path, token=None, origin_header=None, data=None):
            headers = {}
            if token:
                headers["X-Session-Token"] = token
            if origin_header:
                headers["Origin"] = origin_header
            return urllib.request.urlopen(urllib.request.Request(origin + path, headers=headers, data=data), timeout=3)
        try:
            with self.assertRaises(urllib.error.HTTPError) as denied:
                request("/api/status")
            self.assertEqual(denied.exception.code, 403)
            with self.assertRaises(urllib.error.HTTPError) as denied:
                request("/api/start", "test-token", "https://untrusted.invalid", b'{"accept_eula":true}')
            self.assertEqual(denied.exception.code, 403)
            with self.assertRaises(urllib.error.HTTPError) as denied:
                request("/api/ai-config", "test-token", "https://untrusted.invalid", b'{"enabled":false}')
            self.assertEqual(denied.exception.code, 403)
            with self.assertRaises(urllib.error.HTTPError) as denied:
                request("/api/ai-config", origin_header=origin, data=b'{"enabled":false}')
            self.assertEqual(denied.exception.code, 403)
            with request("/api/ai-config", "test-token", origin, b'{"enabled":false}') as response:
                self.assertFalse(json.load(response)["ai"]["enabled"])
            with request("/api/status", "test-token") as response:
                self.assertEqual(json.load(response)["state"], "stopped")
            with self.assertRaises(urllib.error.HTTPError) as denied:
                request("/api/start", "test-token", origin, b'{}')
            self.assertEqual(denied.exception.code, 400)
            with request("/api/stop", "test-token", origin, b'{}') as response:
                self.assertEqual(json.load(response)["state"], "stopped")
            self.assertFalse((self.root / "fabric/eula.txt").exists())
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(2)

    def test_remote_status_exposes_only_sanitized_game_addresses(self):
        panel = self.panel()
        public = [
            {"edition": "Java", "state": "running", "ready": True,
             "address": {"host": "java.example.test", "port": 43297}, "verified": False},
            {"edition": "Bedrock", "state": "running", "ready": True,
             "address": {"host": "bedrock.example.test", "port": 60912}, "verified": False},
        ]
        with patch.object(dashboard.pinggy_joining, "tunnels", return_value=[{"privateToken": "never-return-this"}]), \
             patch.object(dashboard.pinggy_joining, "public_status", return_value=public):
            status = panel.snapshot()
        self.assertEqual(status["remote_joining"]["tunnels"], public)
        self.assertTrue(status["remote_joining"]["available"])
        self.assertFalse(status["friend_guide"]["available"])
        self.assertNotIn("never-return-this", json.dumps(status))
        panel.remote_checked = 0
        with patch.object(dashboard.pinggy_joining, "tunnels", side_effect=RuntimeError("private agent diagnostic")):
            unavailable = panel.snapshot()
        self.assertFalse(unavailable["remote_joining"]["available"])
        self.assertEqual(unavailable["remote_joining"]["tunnels"], [])
        self.assertNotIn("private agent diagnostic", json.dumps(unavailable))

    def test_friend_guide_requires_authentication_and_current_both_editions(self):
        panel = self.panel()
        pdf = self.root / dashboard.FRIEND_GUIDE
        markdown = pdf.with_suffix(".md")
        pdf.write_bytes(b"%PDF-1.4\nexample guide")
        markdown.write_text("Java Server Address: java.example.test:43297.\n"
                            "Bedrock Address: bedrock.example.test, Port: 60912.\n")
        public = [
            {"edition": "Java", "state": "running", "ready": True,
             "address": {"host": "java.example.test", "port": 43297}, "verified": False},
            {"edition": "Bedrock", "state": "running", "ready": True,
             "address": {"host": "bedrock.example.test", "port": 60912}, "verified": False},
        ]
        httpd = dashboard.PanelServer(("127.0.0.1", 0), dashboard.handler_for(panel, "test-token", b"<html>panel</html>"))
        thread = threading.Thread(target=httpd.serve_forever, daemon=True)
        thread.start()
        origin = "http://127.0.0.1:" + str(httpd.server_port)
        try:
            with patch.object(dashboard.pinggy_joining, "tunnels", return_value=[]), \
                 patch.object(dashboard.pinggy_joining, "public_status", return_value=public) as sanitize:
                with self.assertRaises(urllib.error.HTTPError) as denied:
                    urllib.request.urlopen(origin + "/api/friend-guide", timeout=3)
                self.assertEqual(denied.exception.code, 403)
                request = urllib.request.Request(origin + "/api/friend-guide", headers={"X-Session-Token": "test-token"})
                with urllib.request.urlopen(request, timeout=3) as response:
                    self.assertEqual(response.headers["Content-Type"], "application/pdf")
                    self.assertEqual(response.read(), pdf.read_bytes())
                with urllib.request.urlopen(urllib.request.Request(origin + "/api/status", headers={"X-Session-Token": "test-token"}), timeout=3) as response:
                    self.assertTrue(json.load(response)["friend_guide"]["available"])
                sanitize.return_value = [public[0], {**public[1], "address": {"host": "new.example.test", "port": 60913}}]
                panel.remote_checked = 0
                with self.assertRaises(urllib.error.HTTPError) as stale:
                    urllib.request.urlopen(request, timeout=3)
                self.assertEqual(stale.exception.code, 404)
        finally:
            httpd.shutdown()
            httpd.server_close()
            thread.join(2)

    def test_panel_lock_is_reusable_only_after_owner_exits(self):
        lease = dashboard.acquire_panel_lock(self.root)
        self.assertIsNotNone(lease)
        try:
            self.assertIsNone(dashboard.acquire_panel_lock(self.root))
        finally:
            lease.close()
        second = dashboard.acquire_panel_lock(self.root)
        self.assertIsNotNone(second)
        second.close()

    def test_tcp_and_udp_conflicts_are_explained(self):
        for kind, setting in [(socket.SOCK_STREAM, "java_port"), (socket.SOCK_DGRAM, "bedrock_port")]:
            with socket.socket(socket.AF_INET, kind) as listener:
                listener.bind(("127.0.0.1", 0))
                args = launcher.parse_args(["--bind", "127.0.0.1"])
                setattr(args, setting, listener.getsockname()[1])
                with self.assertRaisesRegex(RuntimeError, "already in use"):
                    launcher.check_ports(args)

    def test_mac_prefers_physical_lan_over_virtual_default_route(self):
        results = [subprocess.CompletedProcess([], 0, "Hardware Port: Wi-Fi\nDevice: en0\n", ""),
                   subprocess.CompletedProcess([], 0, "192.168.10.20\n", "")]
        with patch.object(launcher.sys, "platform", "darwin"), patch.object(launcher.subprocess, "run", side_effect=results), \
             patch.object(launcher.socket, "gethostbyname_ex", return_value=("host", [], ["198.18.0.1"])), \
             patch.object(launcher.socket, "socket") as sock:
            sock.return_value.__enter__.return_value.getsockname.return_value = ("198.18.0.1", 10)
            self.assertEqual(launcher.lan_address(), "192.168.10.20")

    def test_local_only_join_does_not_probe_network(self):
        with patch.object(launcher, "lan_address") as probe:
            self.assertEqual(launcher.join_addresses(self.args)["host"], "127.0.0.1")
            probe.assert_not_called()

    def test_runtime_extraction_rejects_path_escape(self):
        archive = self.root / "bad.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            member = tarfile.TarInfo("../escape")
            member.size = 3
            bundle.addfile(member, io.BytesIO(b"bad"))
        destination = self.root / "extract"
        destination.mkdir()
        with self.assertRaisesRegex(RuntimeError, "unsafe"):
            runtime.extract_archive(archive, destination)
        self.assertFalse((self.root / "escape").exists())

    def test_runtime_extraction_rejects_escaping_symlink(self):
        archive = self.root / "bad.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            member = tarfile.TarInfo("link")
            member.type = tarfile.SYMTYPE
            member.linkname = "../outside"
            bundle.addfile(member)
        destination = self.root / "extract"
        destination.mkdir()
        with self.assertRaisesRegex(RuntimeError, "unsafe"):
            runtime.extract_archive(archive, destination)

    def test_cancel_startup_wait_stops_a_real_child_cleanly(self):
        service = launcher.Service("test", [sys.executable, "-u", "-c", "import sys; print('booting'); sys.stdin.readline(); print('saved')"], self.root, "never ready")
        cancel = threading.Event()
        cancel.set()
        try:
            with self.assertRaises(InterruptedError):
                service.wait_ready(timeout=2, cancel=cancel)
        finally:
            service.stop()
            service.thread.join(2)
        self.assertEqual(service.process.returncode, 0)
        self.assertIn("saved", (self.root / "launcher.log").read_text())

    def test_world_lock_prevents_all_setup_mutations(self):
        (self.root / "launcher.lock").write_text("another launcher")
        with patch.object(launcher, "setup") as setup:
            with self.assertRaisesRegex(RuntimeError, "launcher.lock"):
                launcher.run_server(self.args, root=self.root, console=False)
            setup.assert_not_called()
        self.assertEqual((self.root / "launcher.lock").read_text(), "another launcher")

    def test_failed_bridge_start_stops_both_services_and_releases_lock(self):
        java, geyser = Mock(), Mock()
        geyser.wait_ready.side_effect = RuntimeError("Bedrock failed")
        with patch.object(launcher, "check_ports"), patch.object(launcher, "java_command", return_value="java"), \
             patch.object(launcher, "setup"), patch.object(launcher, "accept_eula"), patch.object(launcher, "configure"), \
             patch.object(launcher, "copy_bridge_data"), patch.object(launcher, "Service", side_effect=[java, geyser]):
            with self.assertRaisesRegex(RuntimeError, "Bedrock failed"):
                launcher.run_server(self.args, root=self.root, console=False)
        java.stop.assert_called_once()
        geyser.stop.assert_called_once()
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_bootstrap_pins_match_runtime_manifest(self):
        lock = json.loads((ROOT / "server/runtime.lock.json").read_text())
        for key, entries in lock["platforms"].items():
            lines = (ROOT / "server/setup" / (key + ".txt")).read_text().splitlines()
            self.assertEqual(lines, [entries["python"]["url"], entries["python"]["sha256"]])
            for entry in entries.values():
                self.assertRegex(entry["sha256"], r"^[a-f0-9]{64}$")
                self.assertTrue(entry["url"].startswith("https://github.com/"))


if __name__ == "__main__":
    unittest.main()
