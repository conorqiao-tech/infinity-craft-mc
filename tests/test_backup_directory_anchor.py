"""Synthetic output-directory swaps while a backup owns both locks."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX descriptors and locks')
class BackupDirectoryAnchorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fixture = Path(self.temp.name).resolve()
        self.root = self.fixture / 'server'
        self.root.mkdir()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.displaced = self.root / 'synthetic-displaced-backups'
        self.observations = []

    def swap(self, partial, *, move_archive):
        actor = '''
import fcntl,json,os,sys
from pathlib import Path
folder,displaced,world,partial,lock,lease=map(Path,sys.argv[1:7]);move=sys.argv[7]=='yes'
blocked=[]
for path,method in ((lock,'record'),(lease,'flock')):
    with path.open('r+b') as f:
        try:
            if method=='record': fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
            else: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: blocked.append(True)
        else: blocked.append(False)
os.replace(folder,displaced)
if move: os.replace(displaced/partial.name,world/partial.name)
else: os.replace(world/'synthetic-victim',world/partial.name)
os.symlink(world,folder,target_is_directory=True)
print(json.dumps(blocked))
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', actor, str(self.root / 'backups'),
                                 str(self.displaced), str(self.world), str(partial),
                                 str(self.world / 'session.lock'), str(self.root / 'launcher.lock'),
                                 'yes' if move_archive else 'no'],
                                capture_output=True, text=True, timeout=10, check=True)
        self.observations.append(json.loads(result.stdout))

    def test_directory_retarget_after_sync_is_refused(self):
        actual_sync = community.sync_file
        def swap_after_sync(stream):
            actual_sync(stream)
            partial, = (self.root / 'backups').glob('*.partial')
            self.swap(partial, move_archive=True)
        with patch.object(community, 'sync_file', side_effect=swap_after_sync):
            with self.assertRaises(community.BackupArchiveUncertain):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(self.observations, [[True, True]])
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue((self.root / 'backups').is_symlink())
        partial, = self.world.glob('*.partial')
        self.assertFalse(list(self.world.glob('*.zip')))
        with zipfile.ZipFile(partial) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')

    def test_directory_retarget_at_publish_does_not_move_decoy(self):
        (self.world / 'synthetic-victim').write_bytes(b'preserve synthetic victim')
        actual_publish = community.publish_backup_no_replace
        def swap_at_publish(source, destination, *args, **kwargs):
            self.swap(source, move_archive=False)
            return actual_publish(source, destination, *args, **kwargs)
        with patch.object(community, 'publish_backup_no_replace', side_effect=swap_at_publish):
            with self.assertRaises(community.BackupArchiveUncertain):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(self.observations, [[True, True]])
        self.assertTrue((self.root / 'launcher.lock').exists())
        decoy, = self.world.glob('*.partial')
        self.assertEqual(decoy.read_bytes(), b'preserve synthetic victim')
        self.assertFalse(list(self.world.glob('*.zip')))
        published, = self.displaced.glob('*.zip')
        with zipfile.ZipFile(published) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/synthetic-victim'), b'preserve synthetic victim')

    def test_existing_symlink_backup_directory_is_refused_before_archive_creation(self):
        (self.root / 'backups').symlink_to(self.world, target_is_directory=True)
        with self.assertRaises((ValueError, OSError)):
            community.backup(self.root, {'level-name': 'world'})
        self.assertFalse(list(self.world.glob('*.partial')))
        self.assertFalse(list(self.world.glob('*.zip')))
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')

    def test_ancestor_relocation_after_sync_is_refused(self):
        moved_world = self.fixture / 'synthetic-moved-world'
        moved_root = moved_world / 'synthetic-relocated-server'
        actual_sync = community.sync_file
        observed = []
        def relocate_after_sync(stream):
            actual_sync(stream)
            actor = '''
import fcntl,json,os,sys
from pathlib import Path
root,world,outside,relocated,lock,lease=map(Path,sys.argv[1:]);blocked=[]
for path,method in ((lock,'record'),(lease,'flock')):
    with path.open('r+b') as f:
        try:
            if method=='record': fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
            else: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: blocked.append(True)
        else: blocked.append(False)
os.replace(world,outside)
os.symlink(outside,root/'fabric/world',target_is_directory=True)
os.replace(root,relocated)
os.symlink(relocated,root,target_is_directory=True)
print(json.dumps(blocked))
'''
            result = subprocess.run([sys.executable, '-I', '-S', '-c', actor, str(self.root), str(self.world),
                                     str(moved_world), str(moved_root), str(self.world / 'session.lock'),
                                     str(self.root / 'launcher.lock')], capture_output=True, text=True,
                                    timeout=10, check=True)
            observed.append(json.loads(result.stdout))
        with patch.object(community, 'sync_file', side_effect=relocate_after_sync):
            with self.assertRaises(community.BackupArchiveUncertain):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(observed, [[True, True]])
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertEqual((moved_world / 'level.dat').read_bytes(), b'synthetic level')
        partial, = (moved_root / 'backups').glob('*.partial')
        self.assertFalse(list((moved_root / 'backups').glob('*.zip')))
        with zipfile.ZipFile(partial) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')

    def test_directory_symlink_to_native_lock_does_not_release_record_lock(self):
        lock = self.world / 'session.lock'
        (self.root / 'backups').symlink_to(lock)
        actual_open = community.open_backup_directory
        observed = []
        def open_directory(path, *args, **kwargs):
            try:
                return actual_open(path, *args, **kwargs)
            except OSError:
                code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                        "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                        "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
                result = subprocess.run([sys.executable, '-c', code, str(lock)], timeout=10)
                observed.append(result.returncode)
                raise
        with patch.object(community, 'open_backup_directory', side_effect=open_directory):
            with self.assertRaisesRegex(ValueError, 'backups directory'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(observed, [42])
        self.assertTrue((self.root / 'backups').is_symlink())
        self.assertEqual(lock.read_bytes(), b'synthetic lock')

    def test_output_directory_close_failure_retains_published_archive_and_marker(self):
        folder = self.root / 'backups'
        folder.mkdir()
        actual_close = os.close
        actual_open = community.open_backup_directory
        output_descriptors = []
        failures = []
        def record_output_descriptor(path, *args, **kwargs):
            descriptor = actual_open(path, *args, **kwargs)
            if Path(path) == folder:
                output_descriptors.append(descriptor)
            return descriptor
        def fail_after_closing(descriptor):
            if output_descriptors and descriptor == output_descriptors[0] and not failures:
                actual_close(descriptor)
                failures.append(True)
                raise OSError('synthetic backup directory close uncertainty')
            return actual_close(descriptor)
        with patch.object(community, 'open_backup_directory', side_effect=record_output_descriptor):
            with patch.object(community.os, 'close', side_effect=fail_after_closing):
                with self.assertRaisesRegex(OSError, 'directory close uncertainty'):
                    community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(len(output_descriptors), 1)
        self.assertEqual(failures, [True])
        self.assertTrue((self.root / 'launcher.lock').exists())
        published, = folder.glob('*.zip')
        with zipfile.ZipFile(published) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')

    def test_ancestry_probe_close_failure_retains_marker_before_staging(self):
        folder = self.root / 'backups'
        folder.mkdir()
        expected = folder.stat()
        actual_close = os.close
        failures = []
        def fail_probe_close(descriptor):
            info = os.fstat(descriptor)
            if not failures and (info.st_dev, info.st_ino) == (expected.st_dev, expected.st_ino):
                actual_close(descriptor)
                failures.append(True)
                raise OSError('synthetic ancestry probe close uncertainty')
            return actual_close(descriptor)
        with patch.object(community.os, 'close', side_effect=fail_probe_close):
            with self.assertRaisesRegex(community.BackupArchiveUncertain, 'could not close safely'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(failures, [True])
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertEqual(list(folder.iterdir()), [])
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')


if __name__ == '__main__':
    unittest.main()
