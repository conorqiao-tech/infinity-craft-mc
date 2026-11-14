"""Legacy policy reads must not release a recovery owner's native lock."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native record locks')
class RecoveryPolicyReaderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.policy = self.root / refresh.POLICY
        self.value = {'format': 1, 'enabled': False, 'last_release': 'synthetic'}
        self.policy.write_text(json.dumps(self.value))

    def contender(self):
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        return subprocess.run([sys.executable, '-I', '-S', '-c', code,
                               str(self.world / 'session.lock')], timeout=10).returncode

    def assert_chain_contains(self, error, expected_type):
        seen, pending = set(), [error]
        while pending:
            current = pending.pop()
            if current is None or id(current) in seen:
                continue
            if isinstance(current, expected_type):
                return
            seen.add(id(current))
            pending.extend((current.__cause__, current.__context__))
        self.fail('exception chain did not retain ' + expected_type.__name__)

    def test_normal_policy_is_read(self):
        self.assertEqual(refresh.read_policy(self.root), self.value)

    def test_swapped_hardlink_does_not_release_world_lock(self):
        held = community.acquire_world_session_lock(self.world)
        self.assertEqual(self.contender(), 42)
        real_read = refresh._run_policy_reader
        observed = []
        def substitute(path, expected):
            displaced = self.root / 'saved-synthetic-policy'
            code = ("import os,sys;from pathlib import Path;p,q,lock=map(Path,sys.argv[1:]);"
                    "os.replace(p,q);os.link(lock,p)")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(self.policy), str(displaced),
                            str(self.world / 'session.lock')], check=True, timeout=10)
            result = real_read(path, expected)
            observed.append(self.contender())
            return result
        try:
            with patch.object(refresh, '_run_policy_reader', side_effect=substitute):
                with self.assertRaisesRegex(ValueError, 'changed|reader|review'):
                    refresh.read_policy(self.root)
            self.assertEqual(observed, [42])
            self.assertEqual(self.contender(), 42)
            self.assertEqual(json.loads((self.root / 'saved-synthetic-policy').read_text()), self.value)
        finally:
            os.close(held)
        self.assertEqual(self.contender(), 0)
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')

    def test_linked_or_oversized_policy_is_refused_before_reader(self):
        os.link(self.policy, self.root / 'extra-policy-link')
        with patch.object(refresh, '_run_policy_reader') as run:
            with self.assertRaisesRegex(ValueError, 'linked|review'):
                refresh.read_policy(self.root)
        run.assert_not_called()
        (self.root / 'extra-policy-link').unlink()
        self.policy.write_bytes(b' ' * (refresh._POLICY_SIZE_LIMIT + 1))
        with patch.object(refresh, '_run_policy_reader') as run:
            with self.assertRaisesRegex(ValueError, 'large|review'):
                refresh.read_policy(self.root)
        run.assert_not_called()

    def test_policy_changed_after_reader_is_refused(self):
        real_read = refresh._run_policy_reader
        def change(path, expected):
            result = real_read(path, expected)
            self.policy.write_text(json.dumps(dict(self.value, last_release='changed')))
            return result
        with patch.object(refresh, '_run_policy_reader', side_effect=change):
            with self.assertRaisesRegex(ValueError, 'changed|review'):
                refresh.read_policy(self.root)

    def test_reader_timeout_is_refused(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired('reader', 120)
            reader.poll.return_value = None
            with self.assertRaisesRegex(ValueError, 'checked|review'):
                refresh.read_policy(self.root)
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)
            reader.stdout.close.assert_called_once_with()

    def test_unconfirmed_timed_out_reader_has_bounded_cleanup(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired('reader', 120)
            reader.poll.return_value = None
            reader.wait.side_effect = subprocess.TimeoutExpired('reader', 5)
            with self.assertRaisesRegex(ValueError, 'could not be confirmed stopped'):
                refresh.read_policy(self.root)
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)
            reader.stdout.close.assert_called_once_with()

    def test_interrupt_survives_unconfirmed_reader_and_close_failure(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = KeyboardInterrupt()
            reader.poll.return_value = None
            reader.wait.side_effect = subprocess.TimeoutExpired('reader', 5)
            reader.stdout.close.side_effect = OSError('synthetic close failure')
            with self.assertRaises(KeyboardInterrupt) as caught:
                refresh.read_policy(self.root)
            self.assertIsInstance(caught.exception.__cause__, OSError)
            self.assert_chain_contains(caught.exception, subprocess.TimeoutExpired)
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)
            reader.stdout.close.assert_called_once_with()

    def test_unconfirmed_reader_warning_survives_close_failure(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired('reader', 120)
            reader.poll.return_value = None
            reader.wait.side_effect = subprocess.TimeoutExpired('reader', 5)
            reader.stdout.close.side_effect = OSError('synthetic close failure')
            with self.assertRaisesRegex(ValueError, 'could not be confirmed stopped') as caught:
                refresh.read_policy(self.root)
            self.assertIsInstance(caught.exception.__cause__, OSError)
            self.assert_chain_contains(caught.exception, subprocess.TimeoutExpired)
            reader.wait.assert_called_once_with(timeout=5)

    def test_system_exit_is_preserved_after_reader_cleanup(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = SystemExit(3)
            reader.poll.return_value = None
            with self.assertRaises(SystemExit) as caught:
                refresh.read_policy(self.root)
            self.assertEqual(caught.exception.code, 3)
            reader.kill.assert_called_once_with()
            reader.wait.assert_called_once_with(timeout=5)

    def test_interrupt_during_timeout_cleanup_is_not_masked(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired('reader', 120)
            reader.poll.return_value = None
            reader.kill.side_effect = KeyboardInterrupt()
            with self.assertRaises(KeyboardInterrupt) as caught:
                refresh.read_policy(self.root)
            self.assertIsInstance(caught.exception.__cause__, subprocess.TimeoutExpired)
            reader.stdout.close.assert_called_once_with()

    def test_interrupt_during_close_is_not_masked_by_timeout(self):
        with patch.object(refresh.subprocess, 'Popen') as popen:
            reader = popen.return_value
            reader.communicate.side_effect = subprocess.TimeoutExpired('reader', 120)
            reader.poll.return_value = None
            reader.stdout.close.side_effect = KeyboardInterrupt()
            with self.assertRaises(KeyboardInterrupt) as caught:
                refresh.read_policy(self.root)
            self.assertIsInstance(caught.exception.__cause__, subprocess.TimeoutExpired)
            reader.wait.assert_called_once_with(timeout=5)

    def test_failed_reader_with_ignored_sigchld_is_refused(self):
        code = ("import signal,sys;sys.path.insert(0,sys.argv[1]);import world_refresh as r;"
                "signal.signal(signal.SIGCHLD,signal.SIG_IGN);"
                "r._POLICY_READER='import sys; sys.exit(7)';\n"
                "try:r.read_policy(__import__('pathlib').Path(sys.argv[2]))\n"
                "except ValueError:sys.exit(0)\nelse:sys.exit(9)")
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                 str(Path(refresh.__file__).parent), str(self.root)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))


if __name__ == '__main__':
    unittest.main()
