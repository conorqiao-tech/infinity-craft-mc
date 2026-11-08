"""Bounded observations of Minecraft save failures retain launcher evidence."""

from contextlib import ExitStack, redirect_stdout
import io
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import server as launcher


ENTITY_FAILURE = "[08:31:28] [IO-Worker-7/ERROR]: Failed to store entity chunk [256, 256]"
STORE_FAILURE = "[08:31:28] [IO-Worker-7/ERROR]: Failed to store chunk [256, 256]"
CHUNK_FAILURE = "[08:31:28] [IO-Worker-7/ERROR]: Failed to save chunk 256,256"


class SaveFailureTests(unittest.TestCase):
    def service(self, root, name, output, before_stop=False):
        script = (
            "import sys\n"
            + ("print(" + repr(output) + ", flush=True)\n" if before_stop else "")
            + "print('READY', flush=True)\nsys.stdin.readline()\n"
            + ("" if before_stop else "print(" + repr(output) + ", flush=True)\n")
        )
        return launcher.Service(name, [sys.executable, "-u", "-c", script], root, "READY")

    def test_native_save_failure_before_or_during_stop_refuses_clean_exit(self):
        for line in (ENTITY_FAILURE, STORE_FAILURE, CHUNK_FAILURE,
                     ENTITY_FAILURE.replace(']: ', '] (Minecraft) '),
                     CHUNK_FAILURE.replace('/ERROR]', '/FATAL]')):
            for early in (False, True):
                with self.subTest(line=line, early=early), tempfile.TemporaryDirectory() as folder, \
                     redirect_stdout(io.StringIO()):
                    service = self.service(Path(folder), "Java", line, early)
                    service.wait_ready(5)
                    self.assertFalse(service.stop())
                    self.assertEqual(service.process.returncode, 0)
                    self.assertFalse(service.thread.is_alive())

    def test_normal_output_and_unrelated_or_bedrock_errors_do_not_match(self):
        cases = [
            ("Java", "[08:31:28] [Server thread/INFO]: Saved the game"),
            ("Java", "[08:31:28] [Server thread/INFO]: <player> " + ENTITY_FAILURE),
            ("Java", "[08:31:28] [IO-Worker-7/INFO]: Failed to save chunk 256,256"),
            ("Bedrock", ENTITY_FAILURE),
        ]
        for name, line in cases:
            with self.subTest(name=name, line=line), tempfile.TemporaryDirectory() as folder, \
                 redirect_stdout(io.StringIO()):
                service = self.service(Path(folder), name, line)
                service.wait_ready(5)
                self.assertTrue(service.stop())

    def test_unfinished_java_output_reader_refuses_clean_exit(self):
        entered, release = threading.Event(), threading.Event()
        with tempfile.TemporaryDirectory() as folder, redirect_stdout(io.StringIO()):
            service = self.service(Path(folder), "Java", "blocked final line")
            service.wait_ready(5)
            original_publish = service._publish_output
            original_join = service.thread.join
            def publish(message, log_line=None):
                if "blocked final line" in message:
                    entered.set()
                    if not release.wait(5):
                        raise TimeoutError("synthetic output sink was not released")
                return original_publish(message, log_line)
            def bounded_join(timeout=None):
                self.assertTrue(entered.wait(3))
            try:
                with patch.object(service, "_publish_output", side_effect=publish), \
                     patch.object(service.thread, "join", side_effect=bounded_join):
                    self.assertFalse(service.stop())
                self.assertEqual(service.process.returncode, 0)
            finally:
                release.set()
                original_join(5)
            self.assertFalse(service.thread.is_alive())

    @unittest.skipUnless(os.name == "posix", "requires POSIX launcher lease")
    def test_clean_child_exit_after_error_retains_launcher_lock(self):
        with tempfile.TemporaryDirectory() as folder, redirect_stdout(io.StringIO()):
            root = Path(folder)
            (root / "fabric").mkdir()
            stop = threading.Event()
            bedrock = Mock()
            bedrock.process.poll.return_value = 0
            bedrock.stop.return_value = True
            bedrock.wait_ready.side_effect = lambda *args, **kwargs: stop.set()
            real_service = launcher.Service
            java_children = []
            def make_service(name, *args, **kwargs):
                if name == "Bedrock":
                    return bedrock
                script = "import sys\nprint('READY',flush=True)\nsys.stdin.readline()\nprint(" + repr(ENTITY_FAILURE) + ",flush=True)\n"
                child = real_service("Java", [sys.executable, "-u", "-c", script], root / "fabric", "READY", lease_fd=kwargs["lease_fd"])
                java_children.append(child)
                return child
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
                stack.enter_context(patch.object(launcher, "Service", side_effect=make_service))
                with self.assertRaisesRegex(RuntimeError, "retain launcher.lock"):
                    launcher.run_server(launcher.parse_args([]), stop_requested=stop, root=root, console=False)
            self.assertEqual(java_children[0].process.returncode, 0)
            self.assertEqual((root / "launcher.lock").read_text(), str(os.getpid()))


if __name__ == "__main__":
    unittest.main()
