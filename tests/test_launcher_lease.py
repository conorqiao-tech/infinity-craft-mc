import importlib.util
from contextlib import ExitStack
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

SERVER = Path(__file__).resolve().parents[1] / "server"
sys.path.insert(0, str(SERVER))
spec = importlib.util.spec_from_file_location("launcher_lease_test", SERVER / "server.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


@unittest.skipUnless(os.name == "posix", "POSIX advisory lock inheritance")
class LauncherLeaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.args = launcher.parse_args(["--bind", "127.0.0.1"])

    def tearDown(self):
        self.temp.cleanup()

    def competing_recovery(self):
        program = """
import os, sys
sys.path.insert(0, sys.argv[1])
import server
server.os.kill = lambda *_: (_ for _ in ()).throw(ProcessLookupError())
server.check_ports = lambda *_: None
try:
    descriptor = server.acquire_launcher_lock(server.parse_args([]), __import__('pathlib').Path(sys.argv[2]))
except RuntimeError:
    print('refused')
else:
    os.close(descriptor)
    print('reclaimed')
"""
        result = subprocess.run([sys.executable, "-c", program, str(SERVER), str(self.root)],
                                check=True, capture_output=True, text=True, timeout=10)
        return result.stdout.strip()

    def test_new_lease_blocks_recovery_even_if_recorded_pid_appears_dead(self):
        descriptor = launcher.acquire_launcher_lock(self.args, self.root)
        try:
            os.write(descriptor, b"999999999")
            self.assertEqual(self.competing_recovery(), "refused")
        finally:
            os.close(descriptor)

    def test_service_inherits_lease_until_child_exits(self):
        descriptor = launcher.acquire_launcher_lock(self.args, self.root)
        service = None
        try:
            os.write(descriptor, b"999999999")
            ready = self.root / "child-ready"
            program = "import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('ready'); sys.stdin.readline()"
            service = launcher.Service("test", [sys.executable, "-u", "-c", program, str(ready)],
                                       self.root, "unused", lease_fd=descriptor)
            deadline = time.monotonic() + 10
            while not ready.exists() and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), "child did not start")
            os.close(descriptor)
            descriptor = None
            self.assertIsNone(service.process.poll())
            self.assertEqual(self.competing_recovery(), "refused")
            service.stop()
            self.assertEqual(self.competing_recovery(), "reclaimed")
        finally:
            if service is not None:
                service.stop()
            if descriptor is not None:
                os.close(descriptor)

    def test_lease_survives_until_both_services_exit(self):
        descriptor = launcher.acquire_launcher_lock(self.args, self.root)
        services = []
        try:
            os.write(descriptor, b"999999999")
            program = "import pathlib,sys; pathlib.Path(sys.argv[1]).write_text('ready'); sys.stdin.readline()"
            for index in range(2):
                ready = self.root / ("child-ready-" + str(index))
                services.append(launcher.Service("test", [sys.executable, "-u", "-c", program, str(ready)],
                                                 self.root, "unused", lease_fd=descriptor))
                deadline = time.monotonic() + 10
                while not ready.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(ready.exists(), "child did not start")
            os.close(descriptor)
            descriptor = None
            services[0].stop()
            self.assertIsNone(services[1].process.poll())
            self.assertEqual(self.competing_recovery(), "refused")
            services[1].stop()
            self.assertEqual(self.competing_recovery(), "reclaimed")
        finally:
            for service in services:
                service.stop()
            if descriptor is not None:
                os.close(descriptor)

    def test_run_server_holds_and_passes_one_lease_to_both_services(self):
        stop = threading.Event()
        descriptors = []

        class FakeService:
            def __init__(self, name, command, cwd, ready_text, **kwargs):
                descriptor = kwargs["lease_fd"]
                os.fstat(descriptor)
                descriptors.append(descriptor)
                self.name = name
            def wait_ready(self, *args, **kwargs):
                if len(descriptors) == 2:
                    stop.set()
            def stop(self):
                pass

        with ExitStack() as stack:
            for obj, name, value in [
                (launcher, "check_ports", None), (launcher, "require_plain_properties", None),
                (launcher, "properties", {}), (launcher.community, "world_folder", None),
                (launcher.world_refresh, "recover", None), (launcher.community, "backup", None),
                (launcher, "java_command", "java"), (launcher, "setup", None),
                (launcher, "accept_eula", None), (launcher, "configure", None),
                (launcher.owner_rules, "load", {}), (launcher.world_refresh, "startup_check", None),
                (launcher, "copy_bridge_data", None), (launcher, "clear_creative_receipt", None),
                (launcher, "wait_creative_ready", None), (launcher, "emit", None),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", FakeService))
            launcher.run_server(self.args, stop_requested=stop, root=self.root, console=False)
        self.assertEqual(len(descriptors), 2)
        self.assertEqual(descriptors[0], descriptors[1])
        with self.assertRaises(OSError):
            os.fstat(descriptors[0])
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_changed_lock_entry_is_preserved_on_cleanup(self):
        replacement = self.root / "replacement"
        replacement.write_bytes(b"other lock evidence")

        def replace_before_exit(*args, **kwargs):
            os.replace(replacement, self.root / "launcher.lock")
            raise InterruptedError()

        with ExitStack() as stack:
            for obj, name, value in [
                (launcher, "check_ports", None), (launcher, "require_plain_properties", None),
                (launcher, "properties", {}), (launcher.community, "world_folder", None),
                (launcher.world_refresh, "recover", None), (launcher, "emit", None),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher.community, "backup", side_effect=replace_before_exit))
            launcher.run_server(self.args, root=self.root, console=False)
        self.assertEqual((self.root / "launcher.lock").read_bytes(), b"other lock evidence")

    def test_failed_lease_creation_leaves_entry_for_review(self):
        import fcntl
        with patch.object(fcntl, "flock", side_effect=OSError("lock unavailable")):
            with self.assertRaisesRegex(OSError, "lock unavailable"):
                launcher.acquire_launcher_lock(self.args, self.root)
        self.assertTrue((self.root / "launcher.lock").exists())
