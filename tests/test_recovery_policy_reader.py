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

    def test_normal_policy_is_read(self):
        self.assertEqual(refresh.read_policy(self.root), self.value)

    def test_swapped_hardlink_does_not_release_world_lock(self):
        held = community.acquire_world_session_lock(self.world)
        self.assertEqual(self.contender(), 42)
        real_run = subprocess.run
        observed = []
        def substitute(command, *args, **kwargs):
            if refresh._POLICY_READER in command and not observed:
                displaced = self.root / 'saved-synthetic-policy'
                code = ("import os,sys;from pathlib import Path;p,q,lock=map(Path,sys.argv[1:]);"
                        "os.replace(p,q);os.link(lock,p)")
                real_run([sys.executable, '-I', '-S', '-c', code, str(self.policy), str(displaced),
                          str(self.world / 'session.lock')], check=True, timeout=10)
                result = real_run(command, *args, **kwargs)
                observed.append(self.contender())
                return result
            return real_run(command, *args, **kwargs)
        try:
            with patch.object(refresh.subprocess, 'run', side_effect=substitute):
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
        with patch.object(refresh.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'linked|review'):
                refresh.read_policy(self.root)
        run.assert_not_called()
        (self.root / 'extra-policy-link').unlink()
        self.policy.write_bytes(b' ' * (refresh._POLICY_SIZE_LIMIT + 1))
        with patch.object(refresh.subprocess, 'run') as run:
            with self.assertRaisesRegex(ValueError, 'large|review'):
                refresh.read_policy(self.root)
        run.assert_not_called()

    def test_policy_changed_after_reader_is_refused(self):
        real_run = subprocess.run
        def change(command, *args, **kwargs):
            result = real_run(command, *args, **kwargs)
            if refresh._POLICY_READER in command:
                self.policy.write_text(json.dumps(dict(self.value, last_release='changed')))
            return result
        with patch.object(refresh.subprocess, 'run', side_effect=change):
            with self.assertRaisesRegex(ValueError, 'changed|review'):
                refresh.read_policy(self.root)

    def test_reader_timeout_is_refused(self):
        with patch.object(refresh.subprocess, 'run', side_effect=subprocess.TimeoutExpired('reader', 120)):
            with self.assertRaisesRegex(ValueError, 'checked|review'):
                refresh.read_policy(self.root)

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
