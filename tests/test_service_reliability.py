import contextlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import server as launcher


class FailingLog(io.StringIO):
    def __init__(self, operation):
        super().__init__()
        self.operation = operation
        self.write_attempts = self.flush_attempts = self.close_attempts = 0

    def write(self, content):
        self.write_attempts += 1
        if self.operation == "write":
            raise OSError("private log failure detail")
        return super().write(content)

    def flush(self):
        self.flush_attempts += 1
        if self.operation == "flush":
            raise OSError("private log failure detail")
        return super().flush()

    def close(self):
        self.close_attempts += 1
        super().close()
        if self.operation == "close":
            raise OSError("private log failure detail")


class TrackingPipe(io.StringIO):
    def __init__(self, content):
        super().__init__(content)
        self.drained = False

    def close(self):
        if not self.closed:
            self.drained = self.tell() == len(self.getvalue())
        super().close()


class ServiceReliabilityTests(unittest.TestCase):
    def fixture(self, log=None, join_rules=None):
        service = launcher.Service.__new__(launcher.Service)
        service.name = "Test"
        service.join_rules = join_rules
        service.ready = threading.Event()
        service.failed_sinks = set()
        service.log = log if log is not None else io.StringIO()
        service.process = Mock(stdout=TrackingPipe("first\nREADY\nlast\n"))
        return service

    def child(self):
        return Mock(stdin=io.StringIO(), stdout=io.StringIO(), poll=Mock(return_value=None))

    def test_log_open_failure_does_not_launch_a_child(self):
        with patch.object(Path, "open", side_effect=OSError("log unavailable")), \
             patch.object(launcher.subprocess, "Popen") as spawn:
            with self.assertRaisesRegex(OSError, "log unavailable"):
                launcher.Service("Test", ["unused"], Path("unused"), "READY")
        spawn.assert_not_called()

    def test_spawn_failure_closes_the_preallocated_log(self):
        log = io.StringIO()
        with patch.object(Path, "open", return_value=log), \
             patch.object(launcher.subprocess, "Popen", side_effect=OSError("spawn failed")):
            with self.assertRaisesRegex(OSError, "spawn failed"):
                launcher.Service("Test", ["unused"], Path("unused"), "READY")
        self.assertTrue(log.closed)

    def test_thread_start_failure_reaps_child_and_closes_pipes(self):
        child, log = self.child(), io.StringIO()
        reader = Mock(start=Mock(side_effect=RuntimeError("reader unavailable")))
        with patch.object(Path, "open", return_value=log), \
             patch.object(launcher.subprocess, "Popen", return_value=child), \
             patch.object(launcher.threading, "Thread", return_value=reader):
            with self.assertRaisesRegex(RuntimeError, "reader unavailable"):
                launcher.Service("Test", ["unused"], Path("unused"), "READY")
        child.terminate.assert_called_once()
        child.wait.assert_called_once_with(timeout=10)
        child.kill.assert_not_called()
        self.assertTrue(child.stdin.closed)
        self.assertTrue(child.stdout.closed)
        self.assertTrue(log.closed)

    def test_thread_start_failure_escalates_when_child_ignores_termination(self):
        child, log = self.child(), io.StringIO()
        child.wait.side_effect = [subprocess.TimeoutExpired("unused", 10), 0]
        reader = Mock(start=Mock(side_effect=RuntimeError("reader unavailable")))
        with patch.object(Path, "open", return_value=log), \
             patch.object(launcher.subprocess, "Popen", return_value=child), \
             patch.object(launcher.threading, "Thread", return_value=reader):
            with self.assertRaisesRegex(RuntimeError, "reader unavailable"):
                launcher.Service("Test", ["unused"], Path("unused"), "READY")
        child.terminate.assert_called_once()
        child.kill.assert_called_once()
        self.assertEqual(child.wait.call_count, 2)
        self.assertTrue(all(call.kwargs == {"timeout": 10} for call in child.wait.call_args_list))
        self.assertTrue(child.stdin.closed and child.stdout.closed and log.closed)

    def test_thread_start_failure_reaps_a_real_child(self):
        spawned = []
        real_spawn = subprocess.Popen
        def spawn(*args, **kwargs):
            process = real_spawn(*args, **kwargs)
            spawned.append(process)
            return process
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(launcher.subprocess, "Popen", side_effect=spawn), \
             patch.object(launcher.threading.Thread, "start", side_effect=RuntimeError("reader unavailable")):
            try:
                with self.assertRaisesRegex(RuntimeError, "reader unavailable"):
                    launcher.Service("Test", [sys.executable, "-c", "import time; time.sleep(30)"], Path(folder), "READY")
                self.assertEqual(len(spawned), 1)
                self.assertIsNotNone(spawned[0].poll())
                self.assertTrue(spawned[0].stdin.closed and spawned[0].stdout.closed)
            finally:
                for process in spawned:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
                    process.stdin.close()
                    process.stdout.close()

    def test_file_write_and_flush_failures_keep_draining_and_report_once(self):
        for operation in ("write", "flush"):
            with self.subTest(operation=operation):
                log, messages = FailingLog(operation), []
                service = self.fixture(log)
                with contextlib.redirect_stdout(io.StringIO()), patch.object(launcher, "OUTPUT", messages.append):
                    service.read_output("READY")
                self.assertTrue(service.ready.is_set())
                self.assertTrue(service.process.stdout.drained and service.process.stdout.closed)
                self.assertIn("[Test] last", messages)
                self.assertEqual(sum("File logging failed" in value for value in messages), 1)
                self.assertNotIn("private log failure detail", "\n".join(messages))
                self.assertEqual(log.write_attempts, 1)
                self.assertEqual(log.close_attempts, 1)

    def test_terminal_failure_keeps_panel_and_file_output_working(self):
        messages, service = [], self.fixture()
        recorded = []
        service.log = Mock(write=recorded.append)
        with patch("builtins.print", side_effect=BrokenPipeError("terminal closed")) as terminal, \
             patch.object(launcher, "OUTPUT", messages.append):
            service.read_output("READY")
        self.assertTrue(service.ready.is_set())
        self.assertIn("last\n", recorded)
        self.assertIn("[Test] last", messages)
        self.assertEqual(sum("Terminal logging failed" in value for value in messages), 1)
        terminal.assert_called_once()

    def test_panel_failure_keeps_terminal_and_file_output_working(self):
        service, terminal = self.fixture(), io.StringIO()
        recorded = []
        service.log = Mock(write=recorded.append)
        panel = Mock(side_effect=RuntimeError("private callback failure"))
        with contextlib.redirect_stdout(terminal), patch.object(launcher, "OUTPUT", panel):
            service.read_output("READY")
        self.assertTrue(service.ready.is_set())
        self.assertIn("last\n", recorded)
        self.assertIn("[Test] last", terminal.getvalue())
        self.assertEqual(terminal.getvalue().count("Panel logging failed"), 1)
        self.assertNotIn("private callback failure", terminal.getvalue())
        panel.assert_called_once()

    def test_all_sink_failures_do_not_recurse_or_abandon_remaining_lines(self):
        log, panel = FailingLog("write"), Mock(side_effect=RuntimeError("panel failed"))
        service = self.fixture(log)
        with patch("builtins.print", side_effect=BrokenPipeError("terminal closed")) as terminal, \
             patch.object(launcher, "OUTPUT", panel):
            service.read_output("READY")
        self.assertTrue(service.ready.is_set())
        self.assertTrue(service.process.stdout.drained and service.process.stdout.closed)
        self.assertEqual(service.failed_sinks, {"file", "terminal", "panel"})
        self.assertEqual(log.write_attempts, 1)
        terminal.assert_called_once()
        panel.assert_called_once()

    def test_diagnostic_sink_failure_is_reported_without_recursion(self):
        log, messages = FailingLog("write"), []
        service = self.fixture(log)
        def terminal(message, **kwargs):
            if "File logging failed" in message:
                raise BrokenPipeError("diagnostic terminal closed")
        with patch("builtins.print", side_effect=terminal), patch.object(launcher, "OUTPUT", messages.append):
            service.read_output("READY")
        self.assertTrue(service.ready.is_set())
        self.assertEqual(sum("File logging failed" in value for value in messages), 1)
        self.assertEqual(sum("Terminal logging failed" in value for value in messages), 1)
        self.assertIn("[Test] last", messages)

    def test_log_close_failure_reports_once_without_thread_failure(self):
        log, messages = FailingLog("close"), []
        service = self.fixture(log)
        with contextlib.redirect_stdout(io.StringIO()), patch.object(launcher, "OUTPUT", messages.append):
            service.read_output("READY")
        self.assertTrue(service.ready.is_set())
        self.assertEqual(sum("File logging failed" in value for value in messages), 1)
        self.assertEqual(log.close_attempts, 1)

    def test_failed_owner_rule_and_logging_sinks_still_consume_all_output(self):
        service = self.fixture(FailingLog("write"), Mock(command_for=Mock(side_effect=ValueError("rule failed"))))
        with patch("builtins.print", side_effect=BrokenPipeError("terminal closed")), \
             patch.object(launcher, "OUTPUT", Mock(side_effect=RuntimeError("panel failed"))):
            service.read_output("READY")
        self.assertTrue(service.ready.is_set())
        self.assertTrue(service.process.stdout.drained and service.process.stdout.closed)
        self.assertEqual(service.join_rules.command_for.call_count, 3)

    def test_stop_drains_final_child_output_and_closes_both_pipes(self):
        script = "import sys\nprint('READY', flush=True)\nsys.stdin.readline()\nprint('SAVED', flush=True)\n"
        with tempfile.TemporaryDirectory() as folder, contextlib.redirect_stdout(io.StringIO()):
            service = launcher.Service("Test", [sys.executable, "-u", "-c", script], Path(folder), "READY")
            try:
                service.wait_ready(timeout=5)
            finally:
                service.stop()
            self.assertEqual(service.process.returncode, 0)
            self.assertFalse(service.thread.is_alive())
            self.assertTrue(service.process.stdin.closed and service.process.stdout.closed)
            self.assertIn("SAVED", (Path(folder) / "launcher.log").read_text())
            service.stop()  # Already stopped services still keep cleanup idempotent.

    def test_stop_does_not_close_stdin_during_an_inflight_send(self):
        entered, release, closed = threading.Event(), threading.Event(), threading.Event()
        class SlowInput(io.StringIO):
            def write(self, content):
                entered.set()
                if not release.wait(2):
                    raise TimeoutError("test send was not released")
                return super().write(content)
            def close(self):
                closed.set()
                super().close()
        service = self.fixture()
        service.send_lock = threading.Lock()
        service.thread = Mock()
        service.process.stdin = SlowInput()
        service.process.poll.side_effect = lambda: 0 if entered.is_set() else None
        sender = threading.Thread(target=service.send, args=("list",))
        stopper = threading.Thread(target=service.stop)
        sender.start()
        try:
            self.assertTrue(entered.wait(1))
            stopper.start()
            self.assertFalse(closed.wait(.05), "Shutdown closed stdin while send still owned it")
        finally:
            release.set()
            sender.join(2)
            if stopper.ident is not None:
                stopper.join(2)
        self.assertFalse(sender.is_alive() or stopper.is_alive())
        self.assertTrue(service.process.stdin.closed)
        service.thread.join.assert_called_once_with(timeout=5)

    def test_real_child_pipe_does_not_block_when_all_logging_sinks_fail(self):
        # Output exceeds an OS pipe's capacity before READY, proving that the
        # reader keeps consuming after all three optional sinks fail.
        script = "import sys\nfor _ in range(8192): print('x' * 128)\nprint('READY', flush=True)\nsys.stdin.readline()\n"
        log, panel = FailingLog("write"), Mock(side_effect=RuntimeError("panel failed"))
        with tempfile.TemporaryDirectory() as folder, \
             patch.object(Path, "open", return_value=log), \
             patch("builtins.print", side_effect=BrokenPipeError("terminal closed")), \
             patch.object(launcher, "OUTPUT", panel):
            service = launcher.Service("Test", [sys.executable, "-u", "-c", script], Path(folder), "READY")
            try:
                service.wait_ready(timeout=5)
            finally:
                service.stop()
                service.thread.join(5)
                service.process.stdin.close()
                service.process.stdout.close()
        self.assertFalse(service.thread.is_alive())
        self.assertEqual(service.process.returncode, 0)
        self.assertEqual(service.failed_sinks, {"file", "terminal", "panel"})
        self.assertEqual(log.write_attempts, 1)
        panel.assert_called_once()


if __name__ == "__main__":
    unittest.main()
