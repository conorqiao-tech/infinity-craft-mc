"""Stopped-world backups must retain empty synthetic directories."""
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX world and launcher locks')
class BackupEmptyDirectoriesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        self.empty = self.world / 'data/empty/nested'
        self.empty.mkdir(parents=True)
        (self.world / 'region').mkdir()
        (self.world / 'region/r.0.0.mca').write_bytes(b'synthetic terrain')
        (self.root / 'fabric/server.properties').write_text('level-name=world\n')

    def test_empty_nested_directory_survives_separate_extraction(self):
        self.empty.chmod(0o750)
        name = community.backup(self.root, {'level-name': 'world'})
        restore = self.root / 'separate-extraction'
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertIsNone(archive.testzip())
            info = archive.getinfo('world/data/empty/nested/')
            self.assertTrue(info.is_dir())
            self.assertTrue(stat.S_ISDIR(info.external_attr >> 16))
            self.assertEqual(stat.S_IMODE(info.external_attr >> 16), 0o750)
            archive.extractall(restore)
        self.assertTrue((restore / 'world/data/empty/nested').is_dir())
        self.assertEqual(list((restore / 'world/data/empty/nested').iterdir()), [])
        self.assertEqual((restore / 'world/region/r.0.0.mca').read_bytes(), b'synthetic terrain')

    def test_legacy_verifier_checks_empty_directories_and_nested_lock_files(self):
        nested_lock = self.empty.parent / 'session.lock'
        nested_lock.write_bytes(b'synthetic nested data')
        name = community.backup(self.root, {'level-name': 'world'})
        _, files = refresh.verify_backup(self.root, self.world, name)
        self.assertEqual(files['data/empty/session.lock'], nested_lock)
        legacy = self.root / 'backups/legacy-synthetic.zip'
        with zipfile.ZipFile(self.root / 'backups' / name) as source, zipfile.ZipFile(legacy, 'w') as target:
            for info in source.infolist():
                if not info.is_dir():
                    target.writestr(info, source.read(info))
        with self.assertRaisesRegex(ValueError, 'does not match'):
            refresh.verify_backup(self.root, self.world, legacy.name)
        self.empty.rmdir()
        with self.assertRaisesRegex(ValueError, 'does not match'):
            refresh.verify_backup(self.root, self.world, name)

    def test_empty_directory_changed_while_writing_is_not_published(self):
        original = zipfile.ZipFile.writestr
        child = self.empty / 'synthetic-late-file'
        def insert(archive, name, data, *args, **kwargs):
            if isinstance(name, zipfile.ZipInfo) and name.filename == 'world/data/empty/nested/':
                child.write_bytes(b'synthetic data')
            return original(archive, name, data, *args, **kwargs)
        with patch.object(zipfile.ZipFile, 'writestr', insert):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(child.read_bytes(), b'synthetic data')
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_substituted_lock_file_is_never_opened_as_a_directory(self):
        actual_writer = community.write_backup_empty_directory
        real_open = community.open_backup_directory
        displaced = self.root / 'retained-synthetic-directory'
        observed = []
        lock = self.world / 'session.lock'
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        def contender():
            return subprocess.run([sys.executable, '-I', '-S', '-c', code, str(lock)], timeout=10).returncode
        def swap_on_open(path, dir_fd=None):
            if path == 'nested' and dir_fd is not None and not observed:
                self.empty.rename(displaced)
                os.link(lock, self.empty)
                observed.append(contender())
            return real_open(path, dir_fd=dir_fd)
        def check(output, entry, fabric):
            with patch.object(community, 'open_backup_directory', side_effect=swap_on_open):
                try:
                    return actual_writer(output, entry, fabric)
                finally:
                    observed.append(contender())
        try:
            with patch.object(community, 'write_backup_empty_directory', side_effect=check):
                with self.assertRaisesRegex(ValueError, 'directory.*changed|directory.*read'):
                    community.backup(self.root, {'level-name': 'world'})
            self.assertEqual(observed, [42, 42])
            self.assertEqual(contender(), 0)
            self.assertTrue(displaced.is_dir())
            self.assertEqual(self.empty.stat().st_ino, lock.stat().st_ino)
            self.assertFalse(list((self.root / 'backups').glob('*.zip')))
            self.assertTrue((self.root / 'launcher.lock').exists())
        finally:
            if self.empty.exists() and not self.empty.is_dir():
                self.empty.unlink()  # Only the synthetic alias made by this test.

    def test_new_empty_directory_before_final_inventory_is_not_silently_omitted(self):
        added = self.world / 'synthetic-added-empty'
        original = zipfile.ZipFile.writestr
        def insert(archive, name, data, *args, **kwargs):
            if name == 'BACKUP_INFO.json':
                added.mkdir()
            return original(archive, name, data, *args, **kwargs)
        with patch.object(zipfile.ZipFile, 'writestr', insert):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertTrue(added.is_dir())
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))

    def test_removed_empty_directory_before_final_inventory_stops_publication(self):
        original = zipfile.ZipFile.writestr
        def remove(archive, name, data, *args, **kwargs):
            if name == 'BACKUP_INFO.json':
                self.empty.rmdir()
            return original(archive, name, data, *args, **kwargs)
        with patch.object(zipfile.ZipFile, 'writestr', remove):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertFalse(self.empty.exists())
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))

    def test_replaced_empty_directory_before_final_inventory_stops_publication(self):
        original = zipfile.ZipFile.writestr
        displaced = self.root / 'synthetic-retained-empty'
        def replace(archive, name, data, *args, **kwargs):
            if name == 'BACKUP_INFO.json':
                self.empty.rename(displaced)
                self.empty.mkdir()
            return original(archive, name, data, *args, **kwargs)
        with patch.object(zipfile.ZipFile, 'writestr', replace):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertTrue(self.empty.is_dir())
        self.assertTrue(displaced.is_dir())
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_changed_empty_directory_mode_before_final_inventory_stops_publication(self):
        original = zipfile.ZipFile.writestr
        def chmod(archive, name, data, *args, **kwargs):
            if name == 'BACKUP_INFO.json':
                self.empty.chmod(0o700)
            return original(archive, name, data, *args, **kwargs)
        self.empty.chmod(0o755)
        with patch.object(zipfile.ZipFile, 'writestr', chmod):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(stat.S_IMODE(self.empty.stat().st_mode), 0o700)
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())


if __name__ == '__main__':
    unittest.main()
