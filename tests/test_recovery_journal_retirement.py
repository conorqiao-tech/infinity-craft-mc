"""Do not discard an unverified synthetic recovery journal on retirement."""
import json
import errno
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX locks')
class RecoveryJournalRetirementTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root = self.base / 'synthetic-server'
        self.root.mkdir()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.saved = self.root / 'backups/Terrain-Refresh-synthetic/terrain/region'
        self.saved.mkdir(parents=True)
        (self.saved / 'old.bin').write_bytes(b'synthetic old terrain')
        record = {'format': 1, 'state': 'moving', 'world': 'world',
                  'rollback': 'Terrain-Refresh-synthetic', 'moved_paths': ['region'], 'kept_files': {}}
        self.journal = self.root / refresh.JOURNAL
        self.original = json.dumps(record).encode()
        self.journal.write_bytes(self.original)
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    def retired(self):
        return list(self.root.glob(refresh.JOURNAL + '.retired-*'))

    def assert_marker(self):
        self.assertTrue((self.root / 'launcher.lock').is_file())

    def replace_journal(self, payload=b'synthetic replacement journal'):
        alternate = self.root / 'synthetic-replacement'
        alternate.write_bytes(payload)
        code = ('import os,sys;os.replace(sys.argv[1],sys.argv[2]);'
                'os.replace(sys.argv[3],sys.argv[1])')
        subprocess.run([sys.executable, '-I', '-S', '-c', code, str(self.journal),
                        str(self.root / 'synthetic-original'), str(alternate)], check=True, timeout=10)

    def test_clean_recovery_preserves_retired_journal(self):
        messages = []
        refresh.recover(self.root, emit=messages.append, lease_fd=self.lease)
        self.assertFalse(self.journal.exists())
        retired = self.retired()
        self.assertEqual(len(retired), 1)
        self.assertEqual(retired[0].read_bytes(), self.original)
        self.assertEqual((self.world / 'region/old.bin').read_bytes(), b'synthetic old terrain')
        self.assertEqual(len(messages), 1)
        self.assertIn(retired[0].name, messages[0])

    def test_stable_symlinked_server_root_is_allowed(self):
        real_root = self.base / 'synthetic-real-server'
        os.rename(self.root, real_root)
        self.root.symlink_to(real_root, target_is_directory=True)
        refresh.recover(self.root, lease_fd=self.lease)
        self.assertFalse(self.journal.exists())
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)

    def test_swap_at_retirement_is_preserved_and_refused(self):
        actual_unlink, actual_publish = Path.unlink, community.publish_backup_no_replace
        swapped = []
        def unlink(path, *args, **kwargs):
            if path == self.journal and not swapped:
                self.replace_journal()
                swapped.append(True)
            return actual_unlink(path, *args, **kwargs)
        def publish(source, destination, **kwargs):
            if source == self.journal and not swapped:
                self.replace_journal()
                swapped.append(True)
            return actual_publish(source, destination, **kwargs)
        with patch.object(Path, 'unlink', autospec=True, side_effect=unlink), \
             patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(swapped, [True])
        self.assertEqual((self.root / 'synthetic-original').read_bytes(), self.original)
        retired = self.retired()
        self.assertEqual(len(retired), 1)
        self.assertEqual(retired[0].read_bytes(), b'synthetic replacement journal')
        self.assertFalse(self.journal.exists())
        self.assert_marker()

    def test_in_place_change_before_retirement_is_refused_and_retained(self):
        actual = community.publish_backup_no_replace
        changed = []
        def publish(source, destination, **kwargs):
            before = self.journal.stat()
            code = ("import os,sys;from pathlib import Path;p=Path(sys.argv[1]);"
                    "data=p.read_bytes();p.write_bytes(b'X'+data[1:]);"
                    "os.utime(p,ns=(int(sys.argv[2]),int(sys.argv[3])))")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(self.journal),
                            str(before.st_atime_ns), str(before.st_mtime_ns)], check=True, timeout=10)
            changed.append(True)
            return actual(source, destination, **kwargs)
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(changed, [True])
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), b'X' + self.original[1:])
        self.assert_marker()

    def test_no_overwrite_when_retired_destination_exists(self):
        suffix = '0' * 32
        collision = self.root / (refresh.JOURNAL + '.retired-' + suffix)
        collision.write_bytes(b'synthetic existing evidence')
        with patch.object(refresh.uuid, 'uuid4', return_value=SimpleNamespace(hex=suffix)):
            with self.assertRaises((ValueError, OSError)):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(collision.read_bytes(), b'synthetic existing evidence')
        self.assertEqual(self.journal.read_bytes(), self.original)
        self.assert_marker()

    def test_uncertain_rename_retains_available_evidence(self):
        actual = community.publish_backup_no_replace
        def rename_then_fail(source, destination, **kwargs):
            actual(source, destination, **kwargs)
            raise OSError('synthetic post-rename failure')
        with patch.object(community, 'publish_backup_no_replace', side_effect=rename_then_fail):
            with self.assertRaises((ValueError, OSError)):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertFalse(self.journal.exists())
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)
        self.assert_marker()

    def test_recreated_journal_after_rename_is_retained_and_refused(self):
        actual = community.publish_backup_no_replace
        def rename_then_create(source, destination, **kwargs):
            actual(source, destination, **kwargs)
            self.journal.write_bytes(b'synthetic new journal')
        with patch.object(community, 'publish_backup_no_replace', side_effect=rename_then_create):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(self.journal.read_bytes(), b'synthetic new journal')
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)
        self.assert_marker()

    def test_retired_hardlink_swap_does_not_release_native_lock(self):
        actual = refresh._run_retired_journal_reader
        observed = []
        contender = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                     "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                     "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        lock = self.world / 'session.lock'
        def reader(name, expected, directory_fd):
            path = self.root / name
            code = ("import os,sys;os.replace(sys.argv[1],sys.argv[2]);"
                    "os.link(sys.argv[3],sys.argv[1])")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(path),
                            str(self.root / 'synthetic-retired-original'), str(lock)],
                           check=True, timeout=10)
            result = actual(name, expected, directory_fd)
            observed.append(subprocess.run([sys.executable, '-I', '-S', '-c', contender,
                                            str(lock)], timeout=10).returncode)
            return result
        with patch.object(refresh, '_run_retired_journal_reader', side_effect=reader):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(observed, [42])
        self.assertEqual((self.root / 'synthetic-retired-original').read_bytes(), self.original)
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(os.stat(self.retired()[0]).st_ino, os.stat(lock).st_ino)
        self.assert_marker()
        self.assertEqual(subprocess.run([sys.executable, '-I', '-S', '-c', contender,
                                         str(lock)], timeout=10).returncode, 0)

    def test_interrupt_survives_directory_close_failure_after_rename(self):
        actual_verify, actual_publish, actual_close = (refresh._verify_retirement_directory,
                                                       community.publish_backup_no_replace, os.close)
        held = []
        def verify(root, descriptor):
            if not held:
                held.append(descriptor)
            return actual_verify(root, descriptor)
        def rename_then_interrupt(source, destination, **kwargs):
            actual_publish(source, destination, **kwargs)
            raise KeyboardInterrupt()
        def close(descriptor):
            actual_close(descriptor)
            if held and descriptor == held[0]:
                raise OSError('synthetic directory close failure')
        with patch.object(refresh, '_verify_retirement_directory', side_effect=verify), \
             patch.object(community, 'publish_backup_no_replace', side_effect=rename_then_interrupt), \
             patch.object(os, 'close', side_effect=close):
            with self.assertRaises(KeyboardInterrupt) as caught:
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertIsInstance(caught.exception.__cause__, OSError)
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)
        self.assert_marker()

    def test_retired_reader_failure_keeps_journal_and_marker(self):
        with patch.object(refresh, '_run_retired_journal_reader',
                          return_value=(b'incomplete synthetic reader output', 0)):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)
        self.assert_marker()

    def test_equal_bytes_at_a_different_retired_inode_are_refused(self):
        actual = refresh._run_retired_journal_reader
        def reader(name, expected, directory_fd):
            path = self.root / name
            code = ("import os,sys;from pathlib import Path;"
                    "p=Path(sys.argv[1]);data=p.read_bytes();"
                    "os.replace(p,sys.argv[2]);p.write_bytes(data)")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(path),
                            str(self.root / 'synthetic-retired-original')], check=True, timeout=10)
            return actual(name, expected, directory_fd)
        with patch.object(refresh, '_run_retired_journal_reader', side_effect=reader):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)
        self.assertEqual((self.root / 'synthetic-retired-original').read_bytes(), self.original)
        self.assertNotEqual(self.retired()[0].stat().st_ino,
                            (self.root / 'synthetic-retired-original').stat().st_ino)
        self.assert_marker()

    def test_directory_sync_failure_keeps_retired_journal(self):
        actual_verify, actual_sync = refresh._verify_retirement_directory, os.fsync
        held = []
        def verify(root, descriptor):
            if not held:
                held.append(descriptor)
            return actual_verify(root, descriptor)
        def sync(descriptor):
            if held and descriptor == held[0]:
                raise OSError('synthetic directory sync failure')
            return actual_sync(descriptor)
        with patch.object(refresh, '_verify_retirement_directory', side_effect=verify), \
             patch.object(os, 'fsync', side_effect=sync):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)
        self.assert_marker()

    def test_unsupported_rename_keeps_original_journal(self):
        with patch.object(community, 'publish_backup_no_replace',
                          side_effect=OSError(errno.ENOTSUP, 'synthetic unsupported rename')):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(self.journal.read_bytes(), self.original)
        self.assertEqual(self.retired(), [])
        self.assert_marker()

    def test_missing_native_rename_dependency_keeps_original_journal(self):
        with patch.object(community, '_ctypes', None):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(self.journal.read_bytes(), self.original)
        self.assertEqual(self.retired(), [])
        self.assert_marker()

    def test_missing_native_rename_is_refused_before_world_lock_is_opened(self):
        with patch.object(community, '_EXCLUSIVE_RENAME', None), \
             patch.object(community.os, 'open', wraps=os.open) as opened:
            with self.assertRaisesRegex(ValueError, 'journal|backup|review'):
                community.acquire_world_session_lock(self.world)
        opened.assert_not_called()
        self.assertEqual(self.journal.read_bytes(), self.original)
        self.assert_marker()

    def test_stripped_python_keeps_other_settings_available_and_refuses_lock(self):
        code = r'''
import sys
from pathlib import Path
sys.modules['ctypes'] = None
sys.path.insert(0, sys.argv[1])
import community
assert community.ai_values({})['enabled'] is False
assert community._EXCLUSIVE_RENAME is None
try:
    community.acquire_world_session_lock(Path(sys.argv[2]))
except ValueError:
    assert not (Path(sys.argv[2]) / 'session.lock').exists()
else:
    raise AssertionError('world lock unexpectedly opened')
'''
        empty_world = self.base / 'synthetic-empty-world'
        empty_world.mkdir()
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                 str(Path(community.__file__).parent), str(empty_world)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))

    def test_publication_does_not_reimport_native_library_while_locked(self):
        # A substituted import could open/close the world-lock inode in this
        # process. Evict ctypes and put a synthetic lock alias on the import
        # path exactly at publication; the retained module must be used.
        actual = community.publish_backup_no_replace
        module_dir = self.base / 'synthetic-imports'
        module_dir.mkdir()
        alias = module_dir / 'ctypes.py'
        observed = []
        contender = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                     "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                     "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        def publish(source, destination, **kwargs):
            saved = sys.modules.pop('ctypes', None)
            sys.path.insert(0, str(module_dir))
            os.link(self.world / 'session.lock', alias)
            try:
                result = actual(source, destination, **kwargs)
            finally:
                alias.unlink()
                sys.path.remove(str(module_dir))
                if saved is not None:
                    sys.modules['ctypes'] = saved
                else:
                    sys.modules.pop('ctypes', None)
            observed.append(subprocess.run([sys.executable, '-I', '-S', '-c', contender,
                                            str(self.world / 'session.lock')], timeout=10).returncode)
            return result
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(observed, [42])
        self.assertEqual(len(self.retired()), 1)
        self.assertEqual(self.retired()[0].read_bytes(), self.original)

    def test_directory_replaced_during_path_absence_check_is_refused(self):
        actual = refresh._terrain_entry
        displaced = self.base / 'synthetic-moved-server'
        swapped = []
        def observe(path):
            if path == self.journal and self.retired() and not swapped:
                os.rename(self.root, displaced)
                self.root.mkdir()
                swapped.append(True)
            return actual(path)
        with patch.object(refresh, '_terrain_entry', side_effect=observe):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(swapped, [True])
        preserved = list(displaced.glob(refresh.JOURNAL + '.retired-*'))
        self.assertEqual(len(preserved), 1)
        self.assertEqual(preserved[0].read_bytes(), self.original)
        self.assertTrue((displaced / 'launcher.lock').is_file())

    def test_directory_moved_and_symlinked_back_is_refused(self):
        actual = community.publish_backup_no_replace
        new_parent = self.base / 'synthetic-new-parent'
        new_parent.mkdir()
        displaced = new_parent / 'synthetic-moved-server'
        moved = []
        def publish(source, destination, **kwargs):
            actual(source, destination, **kwargs)
            os.rename(self.root, displaced)
            self.root.symlink_to(displaced, target_is_directory=True)
            moved.append(True)
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(moved, [True])
        preserved = list(displaced.glob(refresh.JOURNAL + '.retired-*'))
        self.assertEqual(len(preserved), 1)
        self.assertEqual(preserved[0].read_bytes(), self.original)
        self.assertTrue((displaced / 'launcher.lock').is_file())

    def test_same_parent_directory_rename_with_new_symlink_is_refused(self):
        actual = community.publish_backup_no_replace
        displaced = self.base / 'synthetic-moved-server'
        moved = []
        def publish(source, destination, **kwargs):
            actual(source, destination, **kwargs)
            os.rename(self.root, displaced)
            self.root.symlink_to(displaced, target_is_directory=True)
            moved.append(True)
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(moved, [True])
        preserved = list(displaced.glob(refresh.JOURNAL + '.retired-*'))
        self.assertEqual(len(preserved), 1)
        self.assertEqual(preserved[0].read_bytes(), self.original)
        self.assertTrue((displaced / 'launcher.lock').is_file())

    def test_retired_reader_uses_held_directory_after_path_moves(self):
        retired = self.root / (refresh.JOURNAL + '.retired-synthetic')
        retired.write_bytes(self.original)
        info = retired.lstat()
        expected = refresh._journal_identity(info)
        descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        native_lock = community.acquire_world_session_lock(self.world)
        displaced = self.base / 'synthetic-moved-server'
        os.rename(self.root, displaced)
        try:
            output, status = refresh._run_retired_journal_reader(retired.name, expected, descriptor)
            self.assertEqual(status, 0)
            self.assertEqual(output, self.original + community._BACKUP_READER_TRAILER)
            contender = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                         "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                         "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
            self.assertEqual(subprocess.run([sys.executable, '-I', '-S', '-c', contender,
                                             str(displaced / 'fabric/world/session.lock')],
                                            timeout=10).returncode, 42)
        finally:
            os.close(native_lock)
            os.close(descriptor)

    def test_retired_reader_rejects_names_outside_held_directory(self):
        descriptor = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        self.addCleanup(os.close, descriptor)
        with patch.object(refresh.subprocess, 'Popen') as popen:
            for name in ('', '.', '..', '../session.lock', '/tmp/synthetic'):
                with self.subTest(name=name):
                    with self.assertRaisesRegex(ValueError, 'journal|review'):
                        refresh._run_retired_journal_reader(name, (), descriptor)
        popen.assert_not_called()

    def test_failed_retired_reader_with_ignored_sigchld_is_refused(self):
        code = r'''
import os, signal, sys
from pathlib import Path
sys.path.insert(0,sys.argv[1])
import community, world_refresh as r
root=Path(sys.argv[2]); path=root/r.JOURNAL
fd=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
try:
    signal.signal(signal.SIGCHLD,signal.SIG_IGN)
    community._BACKUP_FILE_READER='import os;os.write(1,b"plausible");os._exit(7)'
    try:r._read_retired_journal_bytes(path.name,path.lstat(),fd)
    except ValueError:sys.exit(0)
    sys.exit(9)
finally:
    os.close(fd)
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                 str(Path(refresh.__file__).parent), str(self.root)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
        self.assertEqual(self.journal.read_bytes(), self.original)


if __name__ == '__main__':
    unittest.main()
