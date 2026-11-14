"""Preserve synthetic recovery evidence when path metadata is unavailable."""
from contextlib import contextmanager
import errno
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX locks')
class RecoveryPathDiscoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / 'fabric/world'
        self.current = self.world / 'region'
        self.current.mkdir(parents=True)
        self.unrecorded = self.current / 'unrecorded.bin'
        self.unrecorded.write_bytes(b'synthetic unrecorded current terrain')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        self.saved = self.root / 'backups/Terrain-Refresh-synthetic/terrain/region'
        self.saved.mkdir(parents=True)
        self.old = self.saved / 'old.bin'
        self.old.write_bytes(b'synthetic archived terrain')
        self.journal = self.root / refresh.JOURNAL
        self.journal.write_text(json.dumps({'format': 1, 'state': 'moving', 'world': 'world',
            'rollback': 'Terrain-Refresh-synthetic', 'moved_paths': ['region'], 'kept_files': {}}))
        self.original_journal = self.journal.read_bytes()
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    @contextmanager
    def fail_metadata(self, target, enabled=lambda: True):
        real_stat, real_lstat, real_path_lstat = os.stat, os.lstat, Path.lstat
        def matches(path):
            return not isinstance(path, int) and os.fspath(path) == os.fspath(target) and enabled()
        def stat(path, *args, **kwargs):
            if matches(path):
                raise OSError(errno.EIO, 'synthetic metadata error')
            return real_stat(path, *args, **kwargs)
        def lstat(path, *args, **kwargs):
            if matches(path):
                raise OSError(errno.EIO, 'synthetic metadata error')
            return real_lstat(path, *args, **kwargs)
        def path_lstat(path, *args, **kwargs):
            if matches(path):
                raise OSError(errno.EIO, 'synthetic metadata error')
            return real_path_lstat(path, *args, **kwargs)
        with patch.object(os, 'stat', side_effect=stat), patch.object(os, 'lstat', side_effect=lstat), \
             patch.object(Path, 'lstat', autospec=True, side_effect=path_lstat):
            yield

    def assert_evidence_retained(self):
        self.assertEqual(self.journal.read_bytes(), self.original_journal)
        self.assertEqual(self.old.read_bytes(), b'synthetic archived terrain')
        self.assertEqual(self.unrecorded.read_bytes(), b'synthetic unrecorded current terrain')
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_unreadable_journal_stops_recovery(self):
        with self.fail_metadata(self.journal):
            with self.assertRaisesRegex(ValueError, 'check|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assert_evidence_retained()

    def test_unreadable_journal_stops_startup_check(self):
        with self.fail_metadata(self.journal):
            with self.assertRaisesRegex(ValueError, 'check|review'):
                refresh.startup_check(self.root, emit=lambda message: None)
        self.assert_evidence_retained()

    def test_unreadable_archived_entry_stops_before_any_move(self):
        with self.fail_metadata(self.saved), patch.object(refresh, 'move', wraps=refresh.move) as move:
            with self.assertRaisesRegex(ValueError, 'check|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        move.assert_not_called()
        self.assert_evidence_retained()

    def test_stat_failure_does_not_hide_archived_entry_from_validation(self):
        real_stat = os.stat
        def stat(path, *args, **kwargs):
            if not isinstance(path, int) and os.fspath(path) == os.fspath(self.saved):
                raise OSError(errno.EIO, 'synthetic archived-entry stat error')
            return real_stat(path, *args, **kwargs)
        with patch.object(os, 'stat', side_effect=stat), patch.object(refresh, 'move', wraps=refresh.move) as move:
            with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        move.assert_not_called()
        self.assert_evidence_retained()

    def test_unreadable_current_entry_stops_before_any_move(self):
        self.unrecorded.unlink()
        with self.fail_metadata(self.current), patch.object(refresh, 'move', wraps=refresh.move) as move:
            with self.assertRaisesRegex(ValueError, 'check|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        move.assert_not_called()
        self.assertEqual(self.journal.read_bytes(), self.original_journal)
        self.assertEqual(self.old.read_bytes(), b'synthetic archived terrain')
        self.assertTrue(self.current.is_dir())

    def test_error_after_preflight_still_stops_before_move(self):
        self.unrecorded.unlink()
        original = refresh._recovery_item
        completed = []
        def preflight(*args):
            result = original(*args)
            completed.append(True)
            return result
        with self.fail_metadata(self.saved, enabled=lambda: bool(completed)), \
             patch.object(refresh, '_recovery_item', side_effect=preflight), \
             patch.object(refresh, 'move', wraps=refresh.move) as move:
            with self.assertRaisesRegex(ValueError, 'check|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(completed, [True])
        move.assert_not_called()
        self.assertEqual(self.journal.read_bytes(), self.original_journal)
        self.assertEqual(self.old.read_bytes(), b'synthetic archived terrain')
        self.assertTrue(self.current.is_dir())

    def test_observed_source_symlink_after_recheck_keeps_journal(self):
        retained_saved = self.root / 'synthetic-retained-archive'
        self.saved.rename(retained_saved)
        displaced = self.root / 'synthetic-displaced-current'
        original = refresh._recovery_item
        calls = []
        def substitute_after_recheck(*args):
            result = original(*args)
            calls.append(True)
            if len(calls) == 2:
                self.current.rename(displaced)
                self.current.symlink_to(displaced, target_is_directory=True)
            return result
        with patch.object(refresh, '_recovery_item', side_effect=substitute_after_recheck):
            with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(len(calls), 2)
        self.assertTrue(self.current.is_symlink())
        self.assertEqual((displaced / 'unrecorded.bin').read_bytes(), b'synthetic unrecorded current terrain')
        self.assertEqual((retained_saved / 'old.bin').read_bytes(), b'synthetic archived terrain')
        self.assertEqual(self.journal.read_bytes(), self.original_journal)

    def test_dangling_journal_link_is_refused_by_recovery(self):
        self.journal.unlink()
        self.journal.symlink_to(self.root / 'missing-synthetic-journal')
        with self.assertRaisesRegex(ValueError, 'symbolic|review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertTrue(self.journal.is_symlink())
        self.assertEqual(self.old.read_bytes(), b'synthetic archived terrain')

    def test_fifo_journal_is_refused_without_reading(self):
        self.journal.unlink()
        os.mkfifo(self.journal)
        original = Path.read_text
        def forbid_journal(path, *args, **kwargs):
            if path == self.journal:
                raise AssertionError('synthetic FIFO read attempted')
            return original(path, *args, **kwargs)
        with patch.object(Path, 'read_text', autospec=True, side_effect=forbid_journal):
            with self.assertRaisesRegex(ValueError, 'regular|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertTrue(self.journal.exists())
        self.assertEqual(self.old.read_bytes(), b'synthetic archived terrain')

    def test_uncheckable_policy_keeps_committed_recovery_journal(self):
        record = json.loads(self.original_journal)
        record['state'] = 'committed'
        record['new_policy'] = {'last_release': 'synthetic-release'}
        self.journal.write_text(json.dumps(record))
        self.original_journal = self.journal.read_bytes()
        policy = self.root / refresh.POLICY
        policy.write_text(json.dumps({'format': 1, 'enabled': False, 'refresh_next_start': False,
                                      'last_release': 'synthetic-previous'}))
        before = policy.read_bytes()
        with self.fail_metadata(policy):
            with self.assertRaisesRegex(ValueError, 'check|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(policy.read_bytes(), before)
        self.assert_evidence_retained()

    def test_uncheckable_policy_is_reported_at_startup(self):
        self.journal.unlink()
        policy = self.root / refresh.POLICY
        policy.write_text(json.dumps({'format': 1, 'enabled': False}))
        before = policy.read_bytes()
        messages = []
        with self.fail_metadata(policy):
            refresh.startup_check(self.root, emit=messages.append)
        self.assertEqual(len(messages), 1)
        self.assertIn('unreadable', messages[0])
        self.assertEqual(policy.read_bytes(), before)

    def test_fifo_policy_is_refused_without_reading(self):
        policy = self.root / refresh.POLICY
        os.mkfifo(policy)
        original = Path.read_text
        def forbid_policy(path, *args, **kwargs):
            if path == policy:
                raise AssertionError('synthetic FIFO read attempted')
            return original(path, *args, **kwargs)
        with patch.object(Path, 'read_text', autospec=True, side_effect=forbid_policy):
            with self.assertRaisesRegex(ValueError, 'regular|review'):
                refresh.read_policy(self.root)
        self.assertTrue(policy.exists())

    def test_absent_journal_is_allowed(self):
        self.journal.unlink()
        self.assertIsNone(refresh.recover(self.root, lease_fd=self.lease))
        self.assertIsNone(refresh.startup_check(self.root, emit=lambda message: None))
        self.assertEqual(self.old.read_bytes(), b'synthetic archived terrain')

    def test_clean_recovery_restores_archived_entry(self):
        self.unrecorded.unlink()
        refresh.recover(self.root, lease_fd=self.lease)
        self.assertFalse(self.journal.exists())
        self.assertEqual((self.current / 'old.bin').read_bytes(), b'synthetic archived terrain')


if __name__ == '__main__':
    unittest.main()
