"""Recovery must not replace an existing synthetic world entry."""
import hashlib
import errno
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import server as launcher
import community
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX locks and directory descriptors')
class RecoveryPreserveTargetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        self.rollback = self.root / 'backups/Terrain-Refresh-synthetic'
        (self.rollback / 'terrain/region').mkdir(parents=True)
        self.journal = self.root / refresh.JOURNAL
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode('ascii'))
        self.addCleanup(os.close, self.lease)

    def write_journal(self, paths, kept=None):
        self.journal.write_text(json.dumps({'format': 1, 'state': 'moving', 'world': 'world',
            'rollback': self.rollback.name, 'moved_paths': paths, 'kept_files': kept or {}}))
        return self.journal.read_bytes()

    def test_known_current_directory_is_kept_for_review_before_any_move(self):
        current = self.world / 'region'
        current.mkdir()
        kept = current / 'kept.mca'
        kept.write_bytes(b'synthetic kept data')
        (self.rollback / 'terrain/region/old.mca').write_bytes(b'synthetic archive')
        before = self.write_journal(['region'], {'region/kept.mca': hashlib.sha256(kept.read_bytes()).hexdigest()})
        with self.assertRaisesRegex(ValueError, 'review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(kept.read_bytes(), b'synthetic kept data')
        self.assertEqual((self.rollback / 'terrain/region/old.mca').read_bytes(), b'synthetic archive')
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_target_appearing_after_recheck_is_never_overwritten(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        target = self.world / 'region/r.0.0.mca'
        before = self.write_journal(['region/r.0.0.mca'])
        actual = refresh._recovery_item
        calls = []

        def after_recheck(*args):
            result = actual(*args)
            calls.append(True)
            if len(calls) == 2:
                code = "from pathlib import Path;import sys;Path(sys.argv[1]).write_bytes(b'synthetic late data')"
                subprocess.run([sys.executable, '-I', '-S', '-c', code, str(target)], check=True, timeout=10)
            return result

        with patch.object(refresh, '_recovery_item', side_effect=after_recheck):
            with self.assertRaisesRegex(ValueError, 'review|changed|exists'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(target.read_bytes(), b'synthetic late data')
        self.assertEqual(saved.read_bytes(), b'synthetic archive')
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_absent_target_restores_the_archived_file(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        self.write_journal(['region/r.0.0.mca'])
        refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual((self.world / 'region/r.0.0.mca').read_bytes(), b'synthetic archive')
        self.assertFalse(saved.exists())
        self.assertFalse(self.journal.exists())

    def test_conflict_is_found_before_any_other_path_moves(self):
        region = self.world / 'region'
        region.mkdir()
        current = region / 'conflict.mca'
        current.write_bytes(b'synthetic kept data')
        saved = self.rollback / 'terrain/region'
        (saved / 'conflict.mca').write_bytes(b'synthetic archived conflict')
        (saved / 'safe.mca').write_bytes(b'synthetic archived safe')
        before = self.write_journal(['region/conflict.mca', 'region/safe.mca'],
            {'region/conflict.mca': hashlib.sha256(current.read_bytes()).hexdigest()})
        with self.assertRaisesRegex(ValueError, 'review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(current.read_bytes(), b'synthetic kept data')
        self.assertEqual((saved / 'safe.mca').read_bytes(), b'synthetic archived safe')
        self.assertFalse((region / 'safe.mca').exists())
        self.assertEqual(self.journal.read_bytes(), before)

    def test_target_created_at_the_exclusive_rename_is_not_replaced(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        target = self.world / 'region/r.0.0.mca'
        before = self.write_journal(['region/r.0.0.mca'])
        original = community.restore_terrain_no_replace

        def collide(source, destination, **kwargs):
            code = "from pathlib import Path;import sys;Path(sys.argv[1]).write_bytes(b'synthetic late data')"
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(target)], check=True, timeout=10)
            return original(source, destination, **kwargs)

        with patch.object(community, 'restore_terrain_no_replace', side_effect=collide):
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(target.read_bytes(), b'synthetic late data')
        self.assertEqual(saved.read_bytes(), b'synthetic archive')
        self.assertEqual(self.journal.read_bytes(), before)

    def test_unsupported_or_cross_device_rename_does_not_fall_back(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        before = self.write_journal(['region/r.0.0.mca'])
        for number in (errno.ENOTSUP, errno.EXDEV):
            with self.subTest(number=number), patch.object(community, 'restore_terrain_no_replace',
                                                           side_effect=OSError(number, 'synthetic failure')):
                with self.assertRaisesRegex(ValueError, 'review'):
                    refresh.recover(self.root, lease_fd=self.lease)
            self.assertEqual(saved.read_bytes(), b'synthetic archive')
            self.assertFalse((self.world / 'region/r.0.0.mca').exists())
            self.assertEqual(self.journal.read_bytes(), before)

    def test_missing_target_parent_requires_review_without_creating_it(self):
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        before = self.write_journal(['region/r.0.0.mca'])
        with self.assertRaisesRegex(ValueError, 'parent.*review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertFalse((self.world / 'region').exists())
        self.assertEqual(saved.read_bytes(), b'synthetic archive')
        self.assertEqual(self.journal.read_bytes(), before)

    def test_current_entry_with_no_archive_requires_review(self):
        region = self.world / 'region'
        region.mkdir()
        target = region / 'r.0.0.mca'
        target.write_bytes(b'synthetic original')
        before = self.write_journal(['region/r.0.0.mca'])
        with patch.object(community, 'restore_terrain_no_replace', wraps=community.restore_terrain_no_replace) as move:
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        move.assert_not_called()
        self.assertEqual(target.read_bytes(), b'synthetic original')
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_archived_source_substitution_after_check_keeps_available_evidence(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        retained = saved.with_name('original-synthetic.mca')
        target = self.world / 'region/r.0.0.mca'
        before = self.write_journal(['region/r.0.0.mca'])
        actual = community.restore_terrain_no_replace

        def replace_before_rename(source, destination, **kwargs):
            code = ("import os,sys;os.rename(sys.argv[1],sys.argv[2]);"
                    "open(sys.argv[1],'wb').write(b'synthetic substitute')")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(saved), str(retained)], check=True, timeout=10)
            return actual(source, destination, **kwargs)

        with patch.object(community, 'restore_terrain_no_replace', side_effect=replace_before_rename):
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(retained.read_bytes(), b'synthetic archive')
        self.assertEqual(target.read_bytes(), b'synthetic substitute')
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_parent_substitution_is_detected_after_descriptor_relative_move(self):
        region = self.world / 'region'
        region.mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        displaced = self.world / 'synthetic-displaced-region'
        before = self.write_journal(['region/r.0.0.mca'])
        actual = community.restore_terrain_no_replace

        def move_parent(source, destination, **kwargs):
            code = ("import os,sys;os.rename(sys.argv[1],sys.argv[2]);"
                    "os.symlink(sys.argv[2],sys.argv[1])")
            subprocess.run([sys.executable, '-I', '-S', '-c', code, str(region), str(displaced)], check=True, timeout=10)
            return actual(source, destination, **kwargs)

        with patch.object(community, 'restore_terrain_no_replace', side_effect=move_parent):
            with self.assertRaisesRegex(ValueError, 'review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertTrue(region.is_symlink())
        self.assertEqual((displaced / 'r.0.0.mca').read_bytes(), b'synthetic archive')
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_interruption_after_rename_keeps_journal_and_available_entry(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        before = self.write_journal(['region/r.0.0.mca'])
        actual = community.restore_terrain_no_replace

        def interrupt(source, destination, **kwargs):
            actual(source, destination, **kwargs)
            raise KeyboardInterrupt()

        with patch.object(community, 'restore_terrain_no_replace', side_effect=interrupt):
            with self.assertRaises(KeyboardInterrupt):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual((self.world / 'region/r.0.0.mca').read_bytes(), b'synthetic archive')
        self.assertFalse(saved.exists())
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_interrupted_error_after_rename_propagates_with_evidence(self):
        (self.world / 'region').mkdir()
        saved = self.rollback / 'terrain/region/r.0.0.mca'
        saved.write_bytes(b'synthetic archive')
        before = self.write_journal(['region/r.0.0.mca'])
        actual = community.restore_terrain_no_replace
        interruption = InterruptedError('synthetic interruption')

        def interrupt(source, destination, **kwargs):
            actual(source, destination, **kwargs)
            raise interruption

        with patch.object(community, 'restore_terrain_no_replace', side_effect=interrupt):
            with self.assertRaises(InterruptedError) as observed:
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertIs(observed.exception, interruption)
        self.assertEqual((self.world / 'region/r.0.0.mca').read_bytes(), b'synthetic archive')
        self.assertFalse(saved.exists())
        self.assertEqual(self.journal.read_bytes(), before)
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_directory_cleanup_does_not_mask_an_interruption(self):
        descriptors = [community.open_backup_directory(self.world), community.open_backup_directory(self.rollback)]
        close = os.close
        closed = []

        def fail_after_close(descriptor):
            close(descriptor)
            closed.append(descriptor)
            if descriptor == descriptors[1]:
                raise OSError('synthetic close failure')
            raise KeyboardInterrupt()

        with patch.object(refresh.os, 'close', side_effect=fail_after_close):
            with self.assertRaises(KeyboardInterrupt):
                refresh._close_recovery_directories(descriptors)
        self.assertCountEqual(closed, descriptors)

    def test_archived_nested_symlink_or_fifo_requires_review(self):
        saved = self.rollback / 'terrain/region'
        original = saved / 'old.mca'
        original.write_bytes(b'synthetic archive')
        before = self.write_journal(['region'])
        unrelated = self.root / 'synthetic-unrelated'
        unrelated.write_bytes(b'synthetic untouched')
        unusual = saved / 'nested'
        for kind in ('symlink', 'fifo'):
            with self.subTest(kind=kind):
                if kind == 'symlink':
                    unusual.symlink_to(unrelated)
                else:
                    os.mkfifo(unusual)
                with self.assertRaisesRegex(ValueError, 'link|special|review'):
                    refresh.recover(self.root, lease_fd=self.lease)
                self.assertEqual(unrelated.read_bytes(), b'synthetic untouched')
                self.assertEqual(original.read_bytes(), b'synthetic archive')
                self.assertFalse((self.world / 'region').exists())
                self.assertEqual(self.journal.read_bytes(), before)
                self.assertTrue((self.root / 'launcher.lock').exists())
                unusual.unlink()

    def test_archived_hardlink_is_refused_without_releasing_native_lock(self):
        saved = self.rollback / 'terrain/region'
        (saved / 'old.mca').write_bytes(b'synthetic archive')
        before = self.write_journal(['region'])
        alias = saved / 'alias.mca'
        actual = refresh._check_recovery_archive
        observed = []
        lock = self.world / 'session.lock'
        code = ("import fcntl,sys; f=open(sys.argv[1],'r+b');\n"
                "try: fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError: sys.exit(42)\nelse: sys.exit(0)")

        def check(path, info):
            subprocess.run([sys.executable, '-I', '-S', '-c',
                            'import os,sys;os.link(sys.argv[1],sys.argv[2])',
                            str(lock), str(alias)], check=True, timeout=10)
            observed.append(subprocess.run([sys.executable, '-I', '-S', '-c', code, str(lock)], timeout=10).returncode)
            try:
                return actual(path, info)
            finally:
                observed.append(subprocess.run([sys.executable, '-I', '-S', '-c', code, str(lock)], timeout=10).returncode)

        try:
            with patch.object(refresh, '_check_recovery_archive', side_effect=check):
                with self.assertRaisesRegex(ValueError, 'link|review'):
                    refresh.recover(self.root, lease_fd=self.lease)
            self.assertEqual(observed, [42, 42])
            self.assertEqual(subprocess.run([sys.executable, '-I', '-S', '-c', code, str(lock)], timeout=10).returncode, 0)
            self.assertTrue(alias.exists())
            self.assertEqual((saved / 'old.mca').read_bytes(), b'synthetic archive')
            self.assertFalse((self.world / 'region').exists())
            self.assertEqual(self.journal.read_bytes(), before)
        finally:
            alias.unlink(missing_ok=True)  # Remove only this test's synthetic alias.


if __name__ == '__main__':
    unittest.main()
