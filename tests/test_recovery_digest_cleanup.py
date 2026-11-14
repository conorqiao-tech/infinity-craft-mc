"""Synthetic recovery-reader failures must not become successful recovery."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import world_refresh as refresh


class RecoveryDigestCleanupTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name) / "kept.bin"
        self.path.write_bytes(b"synthetic kept file")

    def assert_chain_contains(self, error, kind):
        seen, pending = set(), [error]
        while pending:
            current = pending.pop()
            if current is None or id(current) in seen:
                continue
            if isinstance(current, kind):
                return
            seen.add(id(current))
            pending.extend((current.__cause__, current.__context__))
        self.fail("exception chain did not retain " + kind.__name__)

    def test_successful_reader_returns_the_observed_digest(self):
        value = refresh._recovery_digest(self.path, self.path.lstat())
        self.assertEqual(value, hashlib.sha256(b"synthetic kept file").hexdigest())

    def test_empty_file_returns_its_digest(self):
        self.path.write_bytes(b"")
        self.assertEqual(refresh._recovery_digest(self.path, self.path.lstat()),
                         hashlib.sha256(b"").hexdigest())

    def test_malformed_output_or_failed_status_is_refused(self):
        digest = hashlib.sha256(self.path.read_bytes()).hexdigest().encode("ascii")
        complete = digest + refresh._RECOVERY_DIGEST_TRAILER
        cases = [(digest, 0), (complete + b"extra", 0), (complete, 7),
                 (b"G" * 64 + refresh._RECOVERY_DIGEST_TRAILER, 0)]
        for output, status in cases:
            with self.subTest(output=output, status=status):
                with patch.object(refresh, "_run_digest_reader", return_value=(output, status)):
                    with self.assertRaisesRegex(ValueError, "host review"):
                        refresh._recovery_digest(self.path, self.path.lstat())

    @unittest.skipUnless(os.name == "posix", "requires POSIX SIGCHLD behavior")
    def test_failed_reader_output_is_rejected_with_ignored_sigchld(self):
        # Isolate SIGCHLD from the test runner and use a synthetic child that
        # prints a plausible digest but does not finish successfully.
        program = """
import hashlib, os, signal, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import world_refresh as r
path = Path(sys.argv[2])
value = hashlib.sha256(path.read_bytes()).hexdigest().encode() + b"\\n"
r._RECOVERY_DIGEST_READER = "import os;os.write(1," + repr(value) + ");os._exit(7)"
signal.signal(signal.SIGCHLD, signal.SIG_IGN)
try:
    r._recovery_digest(path, path.lstat())
except ValueError:
    sys.exit(0)
sys.exit(9)
"""
        result = subprocess.run([sys.executable, "-I", "-S", "-c", program,
                                 str(Path(refresh.__file__).parent), str(self.path)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))

    @unittest.skipUnless(os.name == "posix", "requires POSIX SIGCHLD behavior")
    def test_actual_reader_close_failure_is_rejected_with_ignored_sigchld(self):
        # Inject failure into the actual child after its real descriptor close.
        # On the old reader the digest was printed before that failure.
        program = """
import signal, sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import world_refresh as r
path = Path(sys.argv[2])
assert r._RECOVERY_DIGEST_READER.count('os.close(fd)') == 1
r._RECOVERY_DIGEST_READER = r._RECOVERY_DIGEST_READER.replace(
    'os.close(fd)', 'os.close(fd); raise OSError("synthetic close failure")')
signal.signal(signal.SIGCHLD, signal.SIG_IGN)
try:
    r._recovery_digest(path, path.lstat())
except ValueError:
    sys.exit(0)
sys.exit(9)
"""
        result = subprocess.run([sys.executable, "-I", "-S", "-c", program,
                                 str(Path(refresh.__file__).parent), str(self.path)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))

    def test_unconfirmed_timed_out_reader_has_bounded_cleanup(self):
        with patch.object(refresh.subprocess, "Popen") as popen:
            reader = popen.return_value
            reader.__enter__.return_value = reader
            reader.communicate.side_effect = subprocess.TimeoutExpired("reader", 120)
            reader.poll.return_value = None

            def wait(*, timeout=None):
                if timeout is None:
                    raise AssertionError("reader cleanup waited without a deadline")
                raise subprocess.TimeoutExpired("reader", timeout)

            reader.wait.side_effect = wait
            with self.assertRaisesRegex(ValueError, "could not be confirmed stopped"):
                refresh._recovery_digest(self.path, self.path.lstat())
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)

    def test_confirmed_timed_out_reader_is_refused(self):
        with patch.object(refresh.subprocess, "Popen") as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired("reader", 120)
            reader.poll.return_value = None
            with self.assertRaisesRegex(ValueError, "host review") as caught:
                refresh._recovery_digest(self.path, self.path.lstat())
            self.assert_chain_contains(caught.exception, subprocess.TimeoutExpired)
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)
            reader.stdout.close.assert_called_once_with()

    def test_interrupt_and_failed_cleanup_survive_pipe_close_failure(self):
        with patch.object(refresh.subprocess, "Popen") as popen:
            reader = popen.return_value
            reader.__enter__.return_value = reader
            reader.communicate.side_effect = KeyboardInterrupt()
            reader.poll.return_value = None
            reader.wait.side_effect = subprocess.TimeoutExpired("reader", 5)
            reader.stdout.close.side_effect = OSError("synthetic pipe close failure")
            with self.assertRaises(KeyboardInterrupt) as caught:
                refresh._recovery_digest(self.path, self.path.lstat())
            self.assert_chain_contains(caught.exception, subprocess.TimeoutExpired)
            self.assert_chain_contains(caught.exception, OSError)
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)

    def test_interrupt_during_close_wins_over_timeout(self):
        with patch.object(refresh.subprocess, "Popen") as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired("reader", 120)
            reader.poll.return_value = None
            reader.stdout.close.side_effect = KeyboardInterrupt()
            with self.assertRaises(KeyboardInterrupt) as caught:
                refresh._recovery_digest(self.path, self.path.lstat())
            self.assert_chain_contains(caught.exception, subprocess.TimeoutExpired)
            reader.wait.assert_called_once_with(timeout=5)


if __name__ == "__main__":
    unittest.main()
