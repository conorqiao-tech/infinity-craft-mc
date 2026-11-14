"""Do not open a substituted regular file as a recovery directory."""
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


@unittest.skipUnless(os.name == 'posix' and hasattr(os, 'O_DIRECTORY'), 'requires POSIX directory descriptors')
class RecoveryDirectorySyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')

    def contender(self):
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        return subprocess.run([sys.executable, '-I', '-S', '-c', code, str(self.world / 'session.lock')],
                              timeout=10).returncode

    def test_substituted_native_lock_is_not_opened_by_directory_sync(self):
        folder = self.root / 'directory-to-sync'
        folder.mkdir()
        displaced = self.root / 'displaced-directory'
        held = community.acquire_world_session_lock(self.world)
        self.assertEqual(self.contender(), 42)
        real_open = os.open
        changed = []
        def retarget(path, flags, *args, **kwargs):
            if not changed and not isinstance(path, int) and os.fspath(path) == os.fspath(folder):
                code = ("import os,sys;from pathlib import Path;"
                        "p,q,lock=map(Path,sys.argv[1:]);os.replace(p,q);os.symlink(lock,p)")
                subprocess.run([sys.executable, '-I', '-S', '-c', code, str(folder), str(displaced),
                                str(self.world / 'session.lock')], check=True, timeout=10)
                changed.append(True)
            return real_open(path, flags, *args, **kwargs)
        try:
            with patch.object(refresh.os, 'open', side_effect=retarget):
                with self.assertRaises(OSError):
                    refresh.sync_directory(folder)
            self.assertEqual(changed, [True])
            self.assertEqual(self.contender(), 42)
            self.assertTrue(folder.is_symlink())
            self.assertTrue(displaced.is_dir())
        finally:
            os.close(held)
        self.assertEqual(self.contender(), 0)
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')

    def test_directory_and_symlinked_directory_can_be_synced(self):
        folder = self.root / 'directory-to-sync'
        folder.mkdir()
        link = self.root / 'directory-link'
        link.symlink_to(folder, target_is_directory=True)
        refresh.sync_directory(folder)
        refresh.sync_directory(link)

    def test_regular_file_is_not_a_directory_to_sync(self):
        path = self.root / 'synthetic-file'
        path.write_bytes(b'synthetic')
        with self.assertRaises(OSError):
            refresh.sync_directory(path)
        self.assertEqual(path.read_bytes(), b'synthetic')

    def test_missing_directory_only_open_fails_before_open(self):
        with patch.object(refresh.os, 'O_DIRECTORY', None):
            with patch.object(refresh.os, 'open') as open_file:
                with self.assertRaisesRegex(OSError, 'directory-only open support'):
                    refresh.sync_directory(self.root)
        open_file.assert_not_called()

    def test_hardlink_to_native_lock_does_not_release_it(self):
        held = community.acquire_world_session_lock(self.world)
        try:
            path = self.root / 'linked-lock'
            os.link(self.world / 'session.lock', path)
            with self.assertRaises(OSError):
                refresh.sync_directory(path)
            self.assertEqual(self.contender(), 42)
        finally:
            os.close(held)
        self.assertEqual(self.contender(), 0)

    def test_fifo_is_refused_without_waiting_for_a_writer(self):
        path = self.root / 'fifo'
        os.mkfifo(path)
        code = ("import sys;sys.path.insert(0,sys.argv[1]);import world_refresh;"
                "world_refresh.sync_directory(sys.argv[2])")
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                 str(Path(refresh.__file__).parent), str(path)],
                                capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)

    def test_fsync_error_closes_directory_descriptor(self):
        real_close = os.close
        with patch.object(refresh.os, 'fsync', side_effect=OSError('synthetic sync failure')):
            with patch.object(refresh.os, 'close', wraps=real_close) as close_file:
                with self.assertRaisesRegex(OSError, 'synthetic sync failure'):
                    refresh.sync_directory(self.root)
        self.assertEqual(close_file.call_count, 1)


if __name__ == '__main__':
    unittest.main()
