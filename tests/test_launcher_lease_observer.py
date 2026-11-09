"""Observe a lost borrowed flock without reopening native locks in this process."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

if os.name == 'posix':
    import fcntl

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import server as launcher


@unittest.skipUnless(os.name == 'posix', 'requires POSIX advisory locks')
class LeaseObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    def unlock_in_child(self):
        code = 'import fcntl,sys; fcntl.flock(int(sys.argv[1]),fcntl.LOCK_UN)'
        subprocess.run([sys.executable, '-c', code, str(self.lease)], pass_fds=(self.lease,), check=True, timeout=10)

    def record_lock_blocked(self, path):
        code = ("import fcntl,sys; f=open(sys.argv[1],'r+b'); "
                "\ntry: fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)"
                "\nexcept BlockingIOError: sys.exit(42)\nelse: sys.exit(0)")
        return subprocess.run([sys.executable, '-c', code, str(path)], timeout=10).returncode == 42

    def test_previously_unlocked_lease_stops_backup_and_preserves_marker(self):
        self.unlock_in_child()
        with self.assertRaisesRegex(ValueError, 'launcher.*lease|lease.*launcher'):
            community.backup(self.root, {'level-name': 'world'}, locked=True, lease_fd=self.lease)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse((self.root / 'backups').exists())
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')

    def test_downgraded_shared_lease_stops_backup(self):
        code = 'import fcntl,sys; fcntl.flock(int(sys.argv[1]),fcntl.LOCK_SH)'
        subprocess.run([sys.executable, '-c', code, str(self.lease)], pass_fds=(self.lease,), check=True, timeout=10)
        with self.assertRaisesRegex(ValueError, 'launcher.*lease|lease.*launcher'):
            community.backup(self.root, {'level-name': 'world'}, locked=True, lease_fd=self.lease)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse((self.root / 'backups').exists())

    def test_held_lease_remains_held_and_native_lock_survives_observation(self):
        native_fd = os.open(self.world / 'session.lock', os.O_RDWR)
        try:
            fcntl.lockf(native_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            community.verify_borrowed_launcher_lease(self.root, self.lease)
            self.assertTrue(self.record_lock_blocked(self.world / 'session.lock'))
        finally:
            os.close(native_fd)

    def test_probe_opening_substituted_native_inode_does_not_release_native_lock(self):
        native_path = self.world / 'session.lock'
        lock_path = self.root / 'launcher.lock'
        saved = self.root / 'original-launcher-lock'
        native_fd = os.open(native_path, os.O_RDWR)
        fcntl.lockf(native_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        real_run = subprocess.run
        invoked = []
        def swap_before_probe(*args, **kwargs):
            if not invoked:
                code = ("from pathlib import Path;import os,sys;r=Path(sys.argv[1]);"
                        "os.rename(r/'launcher.lock',r/'original-launcher-lock');"
                        "os.rename(r/'fabric/world/session.lock',r/'launcher.lock')")
                real_run([sys.executable, '-c', code, str(self.root)], check=True, timeout=10)
                invoked.append(True)
            return real_run(*args, **kwargs)
        try:
            with patch('subprocess.run', side_effect=swap_before_probe):
                with self.assertRaisesRegex(ValueError, 'launcher.*lease|lease.*launcher'):
                    community.verify_borrowed_launcher_lease(self.root, self.lease)
            self.assertEqual(invoked, [True])
            self.assertTrue(self.record_lock_blocked(lock_path))
        finally:
            if saved.exists():
                lock_path.rename(native_path)
                saved.rename(lock_path)
            os.close(native_fd)

    def test_unavailable_observer_refuses_before_world_work(self):
        with patch('subprocess.run', side_effect=subprocess.TimeoutExpired('synthetic observer', 5)):
            with self.assertRaisesRegex(ValueError, 'launcher.*lease|lease.*launcher'):
                community.backup(self.root, {'level-name': 'world'}, locked=True, lease_fd=self.lease)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse((self.root / 'backups').exists())


if __name__ == '__main__':
    unittest.main()
