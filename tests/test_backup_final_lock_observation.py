"""Synthetic publication checks for the native lock and launcher lease."""
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX locks')
class BackupFinalLockObservationTests(unittest.TestCase):
    def setUp(self):
        import fcntl
        self.fcntl = fcntl
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')

    def hold_from_child(self, path, *, record):
        code = '''
import fcntl,sys
f=open(sys.argv[1],'r+b')
if sys.argv[2]=='record': fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
else: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
print('HELD',flush=True)
sys.stdin.read(1)
'''
        process = subprocess.Popen([sys.executable, '-I', '-S', '-c', code, str(path),
                                    'record' if record else 'flock'], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        def stop():
            if process.poll() is None:
                process.stdin.write('x')
                process.stdin.flush()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
            process.stdin.close()
            process.stdout.close()
            process.stderr.close()
        self.addCleanup(stop)
        readable, _, _ = select.select([process.stdout], [], [], 10)
        self.assertTrue(readable, 'synthetic contender did not report acquisition')
        self.assertEqual(process.stdout.readline().strip(), 'HELD')
        return process

    def assert_published_evidence(self):
        self.assertTrue((self.root / 'launcher.lock').exists())
        archive, = (self.root / 'backups').glob('*.zip')
        with zipfile.ZipFile(archive) as z:
            self.assertIsNone(z.testzip())
            self.assertEqual(z.read('world/level.dat'), b'synthetic level')

    def test_native_lock_lost_after_rename_retains_marker(self):
        actual_publish = community.publish_backup_no_replace
        contenders = []
        def publish_then_release(source, destination, **kwargs):
            value = actual_publish(source, destination, **kwargs)
            duplicate = os.open(self.world / 'session.lock', os.O_RDONLY | os.O_NOFOLLOW)
            os.close(duplicate)  # Deliberately release this process's record lock.
            contenders.append(self.hold_from_child(self.world / 'session.lock', record=True))
            return value
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish_then_release):
            with self.assertRaisesRegex(community.BackupArchiveUncertain, 'may already'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertIsNone(contenders[0].poll())
        self.assert_published_evidence()

    def test_owned_lease_lost_after_rename_retains_marker(self):
        actual_publish = community.publish_backup_no_replace
        actual_unlink = community.unlink_backup_lease_entry
        descriptors, contenders = [], []
        def capture_staged_lease(path, descriptor):
            if Path(path).name.startswith('.launcher-lock-'):
                descriptors.append(descriptor)
            return actual_unlink(path, descriptor)
        def publish_then_release(source, destination, **kwargs):
            value = actual_publish(source, destination, **kwargs)
            self.assertEqual(len(descriptors), 1)
            self.fcntl.flock(descriptors[0], self.fcntl.LOCK_UN)
            contenders.append(self.hold_from_child(self.root / 'launcher.lock', record=False))
            return value
        with patch.object(community, 'unlink_backup_lease_entry', side_effect=capture_staged_lease), \
             patch.object(community, 'publish_backup_no_replace', side_effect=publish_then_release):
            with self.assertRaisesRegex(community.BackupArchiveUncertain, 'may already'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertIsNone(contenders[0].poll())
        self.assert_published_evidence()

    def test_borrowed_lease_lost_after_rename_retains_marker(self):
        lease = os.open(self.root / 'launcher.lock', os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
        self.addCleanup(os.close, lease)
        self.fcntl.flock(lease, self.fcntl.LOCK_EX | self.fcntl.LOCK_NB)
        actual_publish = community.publish_backup_no_replace
        contenders = []
        def publish_then_release(source, destination, **kwargs):
            value = actual_publish(source, destination, **kwargs)
            self.fcntl.flock(lease, self.fcntl.LOCK_UN)
            contenders.append(self.hold_from_child(self.root / 'launcher.lock', record=False))
            return value
        with patch.object(community, 'publish_backup_no_replace', side_effect=publish_then_release):
            with self.assertRaisesRegex(community.BackupArchiveUncertain, 'may already'):
                community.backup(self.root, {'level-name': 'world'}, locked=True, lease_fd=lease)
        self.assertIsNone(contenders[0].poll())
        self.assert_published_evidence()
        os.fstat(lease)  # The backup never closes the borrowed descriptor.

    def test_interrupted_post_publication_observer_retains_marker(self):
        actual_verify = community.verify_world_session_lock
        def interrupt_final_observation(world, descriptor):
            if list((self.root / 'backups').glob('*.zip')):
                raise KeyboardInterrupt('synthetic observer interruption')
            return actual_verify(world, descriptor)
        with patch.object(community, 'verify_world_session_lock', side_effect=interrupt_final_observation):
            with self.assertRaises(KeyboardInterrupt):
                community.backup(self.root, {'level-name': 'world'})
        self.assert_published_evidence()

    def test_lock_failure_message_survives_archive_close_failure(self):
        actual_verify = community.verify_world_session_lock
        actual_fdopen = os.fdopen

        def fail_final_observation(world, descriptor):
            if list((self.root / 'backups').glob('*.zip')):
                raise ValueError('synthetic post-publication native lock loss')
            return actual_verify(world, descriptor)

        class CloseFailure:
            def __init__(self, stream):
                self.stream = stream

            def __getattr__(self, name):
                return getattr(self.stream, name)

            def __enter__(self):
                return self

            def __exit__(self, *args):
                self.stream.__exit__(*args)
                raise OSError('synthetic archive close failure')

        def failing_fdopen(descriptor, mode):
            return CloseFailure(actual_fdopen(descriptor, mode))

        with patch.object(community, 'verify_world_session_lock', side_effect=fail_final_observation), \
             patch.object(community.os, 'fdopen', side_effect=failing_fdopen):
            with self.assertRaisesRegex(community.BackupPublishedLockUncertain, 'may already') as caught:
                community.backup(self.root, {'level-name': 'world'})
        self.assertIsInstance(caught.exception.__cause__, OSError)
        self.assertIn('archive close failure', str(caught.exception.__cause__))
        self.assert_published_evidence()

    def test_parent_directory_close_failure_retains_published_evidence(self):
        actual_verify = community.verify_backup_parent_outside_world
        actual_close = os.close
        descriptors, failures = [], []
        def capture(root, descriptor, world, **kwargs):
            if not descriptors:
                descriptors.append(descriptor)
            return actual_verify(root, descriptor, world, **kwargs)
        def fail_close(descriptor):
            if descriptors and descriptor == descriptors[0] and not failures:
                actual_close(descriptor)
                failures.append(True)
                raise OSError('synthetic parent directory close uncertainty')
            return actual_close(descriptor)
        with patch.object(community, 'verify_backup_parent_outside_world', side_effect=capture), \
             patch.object(community.os, 'close', side_effect=fail_close):
            with self.assertRaisesRegex(OSError, 'parent directory close uncertainty'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(failures, [True])
        self.assert_published_evidence()

    def test_parent_open_of_native_lock_alias_does_not_release_lock(self):
        # Use a nested server root so the synthetic actor can replace its path.
        root = self.root / 'nested-server'
        world = root / 'fabric/world'
        world.mkdir(parents=True)
        (world / 'session.lock').write_bytes(b'synthetic lock')
        (world / 'level.dat').write_bytes(b'synthetic level')
        displaced = self.root / 'displaced-server'
        actual_acquire = community.acquire_world_session_lock
        actual_open = os.open
        observed = []
        def acquire_then_retarget(path):
            descriptor = actual_acquire(path)
            code = ("import os,sys;from pathlib import Path;"
                    "root=Path(sys.argv[1]);saved=Path(sys.argv[2]);"
                    "os.replace(root,saved);"
                    "os.symlink(saved/'fabric/world/session.lock',root)")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(root), str(displaced)],
                           check=True, timeout=10)
            return descriptor
        def open_root(path, flags, *args, **kwargs):
            try:
                return actual_open(path, flags, *args, **kwargs)
            except OSError:
                if Path(path) == root and flags & os.O_DIRECTORY:
                    code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                            "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                            "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
                    result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                             str(displaced / 'fabric/world/session.lock')], timeout=10)
                    observed.append(result.returncode)
                raise
        with patch.object(community, 'acquire_world_session_lock', side_effect=acquire_then_retarget), \
             patch.object(community.os, 'open', side_effect=open_root):
            with self.assertRaises(community.BackupArchiveUncertain):
                community.backup(root, {'level-name': 'world'})
        self.assertEqual(observed, [42])
        self.assertTrue((displaced / 'launcher.lock').exists())
        self.assertFalse((displaced / 'backups').exists())
        self.assertEqual((displaced / 'fabric/world/level.dat').read_bytes(), b'synthetic level')


@unittest.skipUnless(os.name == 'posix', 'requires POSIX directory descriptors')
class BackupOutputOverlapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name).resolve()

    def test_root_inside_world_refused_before_creating_backup_directory(self):
        container = self.base / 'container'
        world = container / 'world'
        root = world / 'synthetic-server'
        root.mkdir(parents=True)
        (world / 'session.lock').write_bytes(b'synthetic lock')
        (world / 'level.dat').write_bytes(b'synthetic level')
        (root / 'fabric').symlink_to(container, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'inside the world'):
            community.backup(root, {'level-name': 'world'})
        self.assertFalse((root / 'backups').exists())
        self.assertFalse((root / 'launcher.lock').exists())
        self.assertEqual((world / 'level.dat').read_bytes(), b'synthetic level')
        self.assertTrue((root / 'fabric').is_symlink())

    def test_root_equal_to_world_refused_before_creating_backup_directory(self):
        root = self.base / 'world-server'
        root.mkdir()
        (root / 'session.lock').write_bytes(b'synthetic lock')
        (root / 'level.dat').write_bytes(b'synthetic level')
        (root / 'fabric').symlink_to(self.base, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'inside the world'):
            community.backup(root, {'level-name': 'world-server'})
        self.assertFalse((root / 'backups').exists())
        self.assertFalse((root / 'launcher.lock').exists())
        self.assertEqual((root / 'level.dat').read_bytes(), b'synthetic level')

    def test_world_inside_output_parent_is_allowed(self):
        root = self.base / 'server'
        world = root / 'backups/world'
        world.mkdir(parents=True)
        (world / 'session.lock').write_bytes(b'synthetic lock')
        (world / 'level.dat').write_bytes(b'synthetic level')
        (root / 'fabric').symlink_to(root / 'backups', target_is_directory=True)
        name = community.backup(root, {'level-name': 'world'})
        with zipfile.ZipFile(root / 'backups' / name) as z:
            self.assertIsNone(z.testzip())
            self.assertEqual(z.read('world/level.dat'), b'synthetic level')
        self.assertFalse((root / 'launcher.lock').exists())

    def test_symlinked_server_root_is_allowed(self):
        actual = self.base / 'physical-server'
        world = actual / 'fabric/world'
        world.mkdir(parents=True)
        (world / 'session.lock').write_bytes(b'synthetic lock')
        (world / 'level.dat').write_bytes(b'synthetic level')
        root = self.base / 'server-link'
        root.symlink_to(actual, target_is_directory=True)
        name = community.backup(root, {'level-name': 'world'})
        with zipfile.ZipFile(actual / 'backups' / name) as z:
            self.assertIsNone(z.testzip())
            self.assertEqual(z.read('world/level.dat'), b'synthetic level')
        self.assertTrue(root.is_symlink())

    def test_root_path_retarget_after_preflight_does_not_create_in_decoy(self):
        root = self.base / 'server'
        world = root / 'fabric/world'
        world.mkdir(parents=True)
        (world / 'session.lock').write_bytes(b'synthetic lock')
        (world / 'level.dat').write_bytes(b'synthetic level')
        displaced = self.base / 'displaced-server'
        actual_verify = community.verify_backup_parent_outside_world
        swapped = []
        def swap_after_preflight(path, descriptor, checked_world, **kwargs):
            result = actual_verify(path, descriptor, checked_world, **kwargs)
            if not swapped:
                code = ("import os,sys;from pathlib import Path;"
                        "root=Path(sys.argv[1]);saved=Path(sys.argv[2]);"
                        "os.replace(root,saved);"
                        "os.symlink(saved/'fabric/world',root,target_is_directory=True)")
                subprocess.run([sys.executable, '-I', '-S', '-c', code, str(root), str(displaced)],
                               check=True, timeout=10)
                swapped.append(True)
            return result
        with patch.object(community, 'verify_backup_parent_outside_world', side_effect=swap_after_preflight):
            with self.assertRaises(community.BackupArchiveUncertain):
                community.backup(root, {'level-name': 'world'})
        self.assertEqual(swapped, [True])
        self.assertTrue((displaced / 'launcher.lock').exists())
        self.assertTrue((displaced / 'backups').is_dir())
        self.assertFalse((displaced / 'fabric/world/backups').exists())
        self.assertEqual((displaced / 'fabric/world/level.dat').read_bytes(), b'synthetic level')
        self.assertFalse(list((displaced / 'backups').glob('*.zip')))


if __name__ == '__main__':
    unittest.main()
