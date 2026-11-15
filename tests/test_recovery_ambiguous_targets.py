"""An old journal cannot prove why an archive entry is absent."""
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native and launcher locks')
class RecoveryAmbiguousTargetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        (self.world / 'data').mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.rollback = self.root / 'backups/Terrain-Refresh-synthetic'
        (self.rollback / 'terrain/data').mkdir(parents=True)
        self.journal = self.root / refresh.JOURNAL
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    def write_journal(self, paths):
        self.journal.write_text(json.dumps({'format': 1, 'state': 'moving', 'world': 'world',
            'rollback': self.rollback.name, 'moved_paths': paths, 'kept_files': {}}))
        return self.journal.read_bytes()

    def assert_pending(self, original):
        self.assertEqual(self.journal.read_bytes(), original)
        self.assertTrue((self.root / 'launcher.lock').is_file())
        self.assertEqual(list(self.rollback.glob('RECOVERY-*.json')), [])
        self.assertEqual(list(self.root.glob(refresh.JOURNAL + '.retired-*')), [])

    def test_existing_file_without_archive_requires_review(self):
        current = self.world / 'data/current.bin'
        current.write_bytes(b'synthetic current')
        original = self.write_journal(['data/current.bin'])
        with patch.object(community, 'restore_terrain_no_replace', wraps=community.restore_terrain_no_replace) as move:
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        move.assert_not_called()
        self.assertEqual(current.read_bytes(), b'synthetic current')
        self.assert_pending(original)

    def test_existing_directory_without_archive_blocks_other_moves(self):
        current = self.world / 'data/current'
        current.mkdir()
        (current / 'protected.bin').write_bytes(b'synthetic current')
        saved = self.rollback / 'terrain/data/other.bin'
        saved.write_bytes(b'synthetic archived')
        original = self.write_journal(['data/current', 'data/other.bin'])
        with self.assertRaisesRegex(ValueError, 'review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual((current / 'protected.bin').read_bytes(), b'synthetic current')
        self.assertEqual(saved.read_bytes(), b'synthetic archived')
        self.assertFalse((self.world / 'data/other.bin').exists())
        self.assert_pending(original)

    def test_missing_archive_observed_after_preflight_requires_review(self):
        saved = self.rollback / 'terrain/data/old.bin'
        saved.write_bytes(b'synthetic archived')
        displaced = self.rollback / 'terrain/data/displaced.bin'
        target = self.world / 'data/old.bin'
        original = self.write_journal(['data/old.bin'])
        real = refresh._recovery_item
        calls = []

        def after_preflight(*args):
            result = real(*args)
            calls.append(True)
            if len(calls) == 1:
                saved.rename(displaced)
                target.write_bytes(b'synthetic current')
            return result

        with patch.object(refresh, '_recovery_item', side_effect=after_preflight):
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(target.read_bytes(), b'synthetic current')
        self.assertEqual(displaced.read_bytes(), b'synthetic archived')
        self.assert_pending(original)

    def test_missing_archive_observed_after_action_recheck_requires_review(self):
        saved = self.rollback / 'terrain/data/old.bin'
        saved.write_bytes(b'synthetic archived')
        displaced = self.rollback / 'terrain/data/displaced.bin'
        target = self.world / 'data/old.bin'
        original = self.write_journal(['data/old.bin'])
        real = refresh._recovery_item
        calls = []

        def after_recheck(*args):
            result = real(*args)
            calls.append(True)
            if len(calls) == 2:
                saved.rename(displaced)
                target.write_bytes(b'synthetic current')
            return result

        with patch.object(refresh, '_recovery_item', side_effect=after_recheck), \
             patch.object(community, 'restore_terrain_no_replace', wraps=community.restore_terrain_no_replace) as move:
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(len(calls), 2)
        move.assert_not_called()
        self.assertEqual(target.read_bytes(), b'synthetic current')
        self.assertEqual(displaced.read_bytes(), b'synthetic archived')
        self.assert_pending(original)

    def test_empty_moving_journal_requires_review(self):
        original = self.write_journal([])
        with self.assertRaisesRegex(ValueError, 'journal|review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assert_pending(original)

    def test_present_archive_and_absent_target_still_restore(self):
        saved = self.rollback / 'terrain/data/old.bin'
        saved.write_bytes(b'synthetic archived')
        self.write_journal(['data/old.bin'])
        refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual((self.world / 'data/old.bin').read_bytes(), b'synthetic archived')
        self.assertFalse(saved.exists())
        self.assertFalse(self.journal.exists())


if __name__ == '__main__':
    unittest.main()
