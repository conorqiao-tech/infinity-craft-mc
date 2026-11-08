"""Launcher recovery must preserve evidence after an uncertain process exit."""

from contextlib import ExitStack
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

if os.name == "posix":
    import fcntl

SERVER = Path(__file__).resolve().parents[1] / "server"
sys.path.insert(0, str(SERVER))
import server as launcher


@unittest.skipUnless(os.name == "posix", "requires POSIX lease semantics")
class LauncherUncertainExitTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.args = launcher.parse_args(["--bind", "127.0.0.1"])

    def tearDown(self):
        self.temporary.cleanup()

    def test_orphan_with_unlocked_lease_does_not_enable_recovery(self):
        # Deliberately model a child with the inherited lease capability. This
        # is a synthetic failure injection, not normal Fabric behavior.
        child = (
            "import fcntl,os,pathlib,sys,time; "
            "fcntl.flock(int(sys.argv[1]),fcntl.LOCK_UN); "
            "pathlib.Path(sys.argv[2]).write_text(str(os.getpid())); "
            "time.sleep(60)"
        )
        parent = """
import os, pathlib, sys, time
sys.path.insert(0, sys.argv[1])
import server
root = pathlib.Path(sys.argv[2])
fd = server.acquire_launcher_lock(server.parse_args([]), root)
os.write(fd, str(os.getpid()).encode())
service = server.Service('synthetic', [sys.executable, '-u', '-c', sys.argv[3],
                         str(fd), str(root / 'child.pid')], root, 'unused', lease_fd=fd)
while True:
    time.sleep(1)
"""
        process = subprocess.Popen([sys.executable, "-c", parent, str(SERVER), str(self.root), child],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        child_pid = None
        try:
            deadline = time.monotonic() + 15
            ready = self.root / "child.pid"
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), "synthetic child did not unlock the lease")
            child_pid = int(ready.read_text())
            process.kill()
            process.wait(timeout=10)
            os.kill(child_pid, 0)
            lock = self.root / "launcher.lock"
            original = lock.read_bytes()
            # The raw lease is available and this fixture binds no game port.
            with lock.open("rb") as stream:
                fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                fcntl.flock(stream, fcntl.LOCK_UN)
            with patch.object(launcher, "check_ports"):
                with self.assertRaises(RuntimeError):
                    launcher.acquire_launcher_lock(self.args, self.root)
            self.assertEqual(lock.read_bytes(), original)
            os.kill(child_pid, 0)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            if process.stderr is not None:
                process.stderr.close()
            if child_pid is not None:
                try:
                    os.kill(child_pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_failed_service_construction_preserves_launcher_evidence(self):
        # A constructor can fail after spawning a child, even if it tries to
        # terminate that child. The caller cannot prove its state in that case.
        with ExitStack() as stack:
            for obj, name, value in [
                (launcher, "check_ports", None), (launcher, "require_plain_properties", None),
                (launcher, "properties", {}), (launcher.community, "world_folder", None),
                (launcher.world_refresh, "recover", None), (launcher.community, "backup", None),
                (launcher, "java_command", "java"), (launcher, "setup", None),
                (launcher, "accept_eula", None), (launcher, "configure", None),
                (launcher.owner_rules, "load", {}), (launcher.world_refresh, "startup_check", None),
                (launcher, "emit", None),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", side_effect=OSError("synthetic spawn failure")))
            with self.assertRaisesRegex(OSError, "synthetic spawn failure"):
                launcher.run_server(self.args, root=self.root, console=False)
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    def test_unexpected_child_failure_preserves_launcher_evidence(self):
        java = Mock()
        java.wait_ready.side_effect = RuntimeError("synthetic child failed")
        java.process.poll.return_value = 0
        with ExitStack() as stack:
            for obj, name, value in [
                (launcher, "check_ports", None), (launcher, "require_plain_properties", None),
                (launcher, "properties", {}), (launcher.community, "world_folder", None),
                (launcher.world_refresh, "recover", None), (launcher.community, "backup", None),
                (launcher, "java_command", "java"), (launcher, "setup", None),
                (launcher, "accept_eula", None), (launcher, "configure", None),
                (launcher.owner_rules, "load", {}), (launcher.world_refresh, "startup_check", None),
                (launcher, "emit", None),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", return_value=java))
            with self.assertRaisesRegex(RuntimeError, "synthetic child failed"):
                launcher.run_server(self.args, root=self.root, console=False)
        java.stop.assert_called_once()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    def test_cancellation_after_child_launch_preserves_launcher_evidence(self):
        java = Mock()
        java.wait_ready.side_effect = InterruptedError()
        java.stop.return_value = True
        java.process.poll.return_value = 0
        with ExitStack() as stack:
            for obj, name, value in [
                (launcher, "check_ports", None), (launcher, "require_plain_properties", None),
                (launcher, "properties", {}), (launcher.community, "world_folder", None),
                (launcher.world_refresh, "recover", None), (launcher.community, "backup", None),
                (launcher, "java_command", "java"), (launcher, "setup", None),
                (launcher, "accept_eula", None), (launcher, "configure", None),
                (launcher.owner_rules, "load", {}), (launcher.world_refresh, "startup_check", None),
                (launcher, "emit", None),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", return_value=java))
            with self.assertRaisesRegex(RuntimeError, "cancelled after a server process started"):
                launcher.run_server(self.args, root=self.root, console=False)
        java.stop.assert_called_once()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    def test_setup_refuses_a_dangling_launcher_lock_link(self):
        lock = self.root / "launcher.lock"
        lock.symlink_to(self.root / "missing-target")
        with patch.object(launcher, "ROOT", self.root), patch.object(launcher, "setup") as setup:
            with self.assertRaisesRegex(RuntimeError, "launcher.lock"):
                launcher.main(["--setup"])
            setup.assert_not_called()
        self.assertTrue(lock.is_symlink())
        self.assertFalse((self.root / "missing-target").exists())

    def test_preflight_refuses_when_lock_entry_cannot_be_inspected(self):
        with patch.object(Path, "lstat", side_effect=PermissionError("synthetic permission failure")):
            with self.assertRaisesRegex(RuntimeError, "could not be checked"):
                launcher.check_launcher_lock(self.args, self.root)

    def test_forced_child_stop_preserves_launcher_evidence(self):
        stop = threading.Event()
        java, bedrock = Mock(), Mock()
        java.process.poll.return_value = bedrock.process.poll.return_value = 0
        java.stop.return_value = False
        bedrock.wait_ready.side_effect = lambda *args, **kwargs: stop.set()
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
                (launcher, "join_addresses", {"java": "", "local_java": "loopback", "host": "", "bedrock_port": 1}),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", side_effect=[java, bedrock]))
            with self.assertRaisesRegex(RuntimeError, "did not stop normally"):
                launcher.run_server(self.args, stop_requested=stop, root=self.root, console=False)
        java.stop.assert_called_once()
        bedrock.stop.assert_called_once()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    def test_stop_failure_still_attempts_other_child_and_keeps_lock(self):
        java, bedrock = Mock(), Mock()
        java.process.poll.return_value = 0
        bedrock.wait_ready.side_effect = RuntimeError("synthetic startup failure")
        bedrock.stop.side_effect = OSError("synthetic stop failure")
        with ExitStack() as stack:
            for obj, name, value in [
                (launcher, "check_ports", None), (launcher, "require_plain_properties", None),
                (launcher, "properties", {}), (launcher.community, "world_folder", None),
                (launcher.world_refresh, "recover", None), (launcher.community, "backup", None),
                (launcher, "java_command", "java"), (launcher, "setup", None),
                (launcher, "accept_eula", None), (launcher, "configure", None),
                (launcher.owner_rules, "load", {}), (launcher.world_refresh, "startup_check", None),
                (launcher, "copy_bridge_data", None), (launcher, "clear_creative_receipt", None),
                (launcher, "emit", None),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", side_effect=[java, bedrock]))
            with self.assertRaisesRegex(OSError, "synthetic stop failure"):
                launcher.run_server(self.args, root=self.root, console=False)
        bedrock.stop.assert_called_once()
        java.stop.assert_called_once()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    def test_child_still_running_after_stop_keeps_lock_and_reports_failure(self):
        stop = threading.Event()
        java, bedrock = Mock(), Mock()
        java.process.poll.return_value = 0
        bedrock.process.poll.return_value = None
        bedrock.wait_ready.side_effect = lambda *args, **kwargs: stop.set()
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
                (launcher, "join_addresses", {"java": "", "local_java": "loopback", "host": "", "bedrock_port": 1}),
            ]:
                stack.enter_context(patch.object(obj, name, return_value=value))
            stack.enter_context(patch.object(launcher, "Service", side_effect=[java, bedrock]))
            with self.assertRaisesRegex(RuntimeError, "did not stop"):
                launcher.run_server(self.args, stop_requested=stop, root=self.root, console=False)
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))


if __name__ == "__main__":
    unittest.main()
