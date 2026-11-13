"""Fail closed when the selected synthetic world's existence is uncertain."""
import errno
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SERVER = Path(__file__).resolve().parents[1] / 'server'
sys.path.insert(0, str(SERVER))
import community
import server as launcher


@unittest.skipUnless(os.name == 'posix', 'requires POSIX permission and lock behavior')
class BackupWorldDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        (self.root / 'fabric').mkdir()

    def restricted_world(self):
        world = self.root / 'fabric/restricted/world'
        world.mkdir(parents=True)
        (world / 'level.dat').write_bytes(b'synthetic level')
        (world / 'session.lock').write_bytes(b'synthetic lock')
        restricted = world.parent
        os.chmod(restricted, 0)
        self.addCleanup(os.chmod, restricted, 0o700)
        try:
            os.stat(world)
        except PermissionError as error:
            self.assertEqual(error.errno, errno.EACCES)
        else:
            self.skipTest('the current account bypasses directory mode restrictions')
        return world

    def test_unreadable_existing_world_is_not_reported_as_new(self):
        world = self.restricted_world()
        with patch.object(community, 'acquire_world_session_lock', wraps=community.acquire_world_session_lock) as acquire:
            with self.assertRaisesRegex(ValueError, 'world.*(check|read|access)'):
                community.backup(self.root, {'level-name': 'restricted/world'})
        acquire.assert_not_called()
        self.assertFalse((self.root / 'launcher.lock').exists())
        self.assertFalse((self.root / 'backups').exists())
        os.chmod(world.parent, 0o700)
        self.assertEqual((world / 'level.dat').read_bytes(), b'synthetic level')

    def test_unreadable_world_stops_launcher_before_java_setup(self):
        world = self.restricted_world()
        (self.root / 'fabric/server.properties').write_text('level-name=restricted/world\n')
        (self.root / 'fabric/eula.txt').write_text('eula=true\n')
        args = launcher.parse_args(['--bind', '127.0.0.1'])
        with patch.object(launcher, 'check_ports'), patch.object(launcher, 'emit'), \
             patch.object(launcher, 'java_command', side_effect=AssertionError('Java preparation reached')) as java, \
             patch.object(launcher, 'Service') as service:
            with self.assertRaisesRegex(ValueError, 'world.*(check|read|access)'):
                launcher.run_server(args, root=self.root, console=False)
        java.assert_not_called()
        service.assert_not_called()
        self.assertTrue((self.root / 'launcher.lock').is_file())
        with self.assertRaisesRegex(RuntimeError, 'launcher.lock'):
            launcher.check_launcher_lock(args, self.root)
        self.assertFalse((self.root / 'backups').exists())
        os.chmod(world.parent, 0o700)
        self.assertEqual((world / 'level.dat').read_bytes(), b'synthetic level')

    def test_io_error_checking_world_is_not_reported_as_new(self):
        world = self.root / 'fabric/world'
        world.mkdir()
        (world / 'level.dat').write_bytes(b'synthetic level')
        real_stat = Path.stat
        def fail_world_stat(path, *args, **kwargs):
            if path == world:
                raise OSError(errno.EIO, 'synthetic discovery I/O error')
            return real_stat(path, *args, **kwargs)
        with patch.object(community, 'world_folder', return_value=(self.root / 'fabric', world)), \
             patch.object(Path, 'stat', autospec=True, side_effect=fail_world_stat):
            with self.assertRaisesRegex(ValueError, 'world.*(check|read|access)'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertFalse((self.root / 'launcher.lock').exists())
        self.assertFalse((self.root / 'backups').exists())
        self.assertEqual((world / 'level.dat').read_bytes(), b'synthetic level')

    def test_missing_world_remains_a_new_world(self):
        self.assertIsNone(community.backup(self.root, {'level-name': 'world'}))
        self.assertFalse((self.root / 'launcher.lock').exists())
        self.assertFalse((self.root / 'backups').exists())

    def test_empty_world_with_only_session_lock_remains_new(self):
        world = self.root / 'fabric/world'
        world.mkdir()
        (world / 'session.lock').write_bytes(b'synthetic lock')
        self.assertIsNone(community.backup(self.root, {'level-name': 'world'}))
        self.assertEqual((world / 'session.lock').read_bytes(), b'synthetic lock')
        self.assertFalse((self.root / 'launcher.lock').exists())

    def test_selected_regular_file_is_refused(self):
        path = self.root / 'fabric/world'
        path.write_bytes(b'synthetic file')
        with self.assertRaisesRegex(ValueError, 'file, not a world'):
            community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(path.read_bytes(), b'synthetic file')
        self.assertFalse((self.root / 'launcher.lock').exists())

    def test_readable_saved_world_is_backed_up(self):
        world = self.root / 'fabric/world'
        world.mkdir()
        (world / 'level.dat').write_bytes(b'synthetic level')
        (world / 'session.lock').write_bytes(b'synthetic lock')
        name = community.backup(self.root, {'level-name': 'world'})
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')
        self.assertFalse((self.root / 'launcher.lock').exists())


if __name__ == '__main__':
    unittest.main()
