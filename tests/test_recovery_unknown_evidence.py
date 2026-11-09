"""Unrecognized synthetic recovery evidence must remain in place."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native and launcher locks')
class RecoveryUnknownEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.saved = self.root / 'backups/Terrain-Refresh-synthetic/terrain'
        self.ambiguous = self.world / 'data/ambiguous'
        self.ambiguous.mkdir(parents=True)
        self.kept = self.ambiguous / 'r.0.0.mca'
        self.kept.write_bytes(b'synthetic known kept bytes')
        for relative in ('data/ambiguous', 'data/safe'):
            folder = self.saved / relative
            folder.mkdir(parents=True)
            (folder / 'old.bin').write_bytes(('synthetic old ' + relative).encode())
        self.journal = self.root / refresh.JOURNAL
        self.journal.write_text(json.dumps({'format':1,'state':'moving','world':'world',
            'rollback':'Terrain-Refresh-synthetic','moved_paths':['data/ambiguous','data/safe'],
            'kept_files': {'data/ambiguous/r.0.0.mca': hashlib.sha256(self.kept.read_bytes()).hexdigest()}}))
        self.journal_before = self.journal.read_bytes()
        self.descriptor = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.descriptor, str(os.getpid()).encode())
        self.addCleanup(os.close, self.descriptor)

    def recover(self):
        return refresh.recover(self.root, lease_fd=self.descriptor)

    def assert_preflight_preserved_all(self):
        self.assertEqual(self.journal.read_bytes(), self.journal_before)
        self.assertEqual(self.kept.read_bytes(), b'synthetic known kept bytes')
        self.assertEqual((self.saved / 'data/safe/old.bin').read_bytes(), b'synthetic old data/safe')
        self.assertEqual((self.saved / 'data/ambiguous/old.bin').read_bytes(), b'synthetic old data/ambiguous')
        self.assertFalse((self.world / 'data/safe').exists())

    def test_unrecorded_refresh_temporary_is_preserved_before_any_move(self):
        temp = self.ambiguous / 'r.0.0.mca.refresh-tmp-unrecorded'
        temp.write_bytes(b'synthetic unrecorded evidence')
        with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
            self.recover()
        self.assertEqual(temp.read_bytes(), b'synthetic unrecorded evidence')
        self.assert_preflight_preserved_all()

    def test_unexpected_file_in_later_item_blocks_earlier_move(self):
        unknown = self.ambiguous / 'unrecognized.bin'
        unknown.write_bytes(b'synthetic unknown data')
        with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
            self.recover()
        self.assertEqual(unknown.read_bytes(), b'synthetic unknown data')
        self.assert_preflight_preserved_all()

    def test_fifo_blocks_recovery_before_known_file_is_removed(self):
        fifo = self.ambiguous / 'synthetic-pipe'
        os.mkfifo(fifo)
        with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
            self.recover()
        self.assertTrue(fifo.exists())
        self.assert_preflight_preserved_all()

    def test_unrecorded_empty_directory_blocks_recovery(self):
        extra = self.ambiguous / 'unrecorded-directory'
        extra.mkdir()
        with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
            self.recover()
        self.assertTrue(extra.is_dir())
        self.assert_preflight_preserved_all()

    def test_hardlinked_kept_file_blocks_recovery(self):
        alias = self.ambiguous / 'synthetic-alias'
        os.link(self.kept, alias)
        with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
            self.recover()
        self.assertEqual(self.kept.stat().st_ino, alias.stat().st_ino)
        self.assertEqual(alias.read_bytes(), b'synthetic known kept bytes')
        self.assert_preflight_preserved_all()

    def test_symlink_blocks_recovery_before_any_move(self):
        alias = self.ambiguous / 'synthetic-symlink'
        alias.symlink_to(self.kept)
        with self.assertRaisesRegex(ValueError, 'symbolic|Unexpected|review'):
            self.recover()
        self.assertTrue(alias.is_symlink())
        self.assert_preflight_preserved_all()

    def test_overlapping_journal_paths_are_rejected_before_any_move(self):
        record = json.loads(self.journal_before)
        record['moved_paths'] = ['data/safe', 'data/safe/old.bin']
        self.journal.write_text(json.dumps(record))
        self.journal_before = self.journal.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Overlapping.*review'):
            self.recover()
        self.assert_preflight_preserved_all()

    def test_rechecks_each_item_after_preflight(self):
        actual = refresh._recovery_item
        calls = []
        added = self.world / 'data/safe/unexpected.bin'
        def observe(*args):
            result = actual(*args)
            calls.append(args[2])
            if len(calls) == 2:
                code = ("from pathlib import Path;import sys;p=Path(sys.argv[1]);"
                        "p.parent.mkdir(parents=True);p.write_bytes(b'synthetic late file')")
                subprocess.run([sys.executable, '-c', code, str(added)], check=True, timeout=10)
            return result
        with patch.object(refresh, '_recovery_item', side_effect=observe):
            with self.assertRaisesRegex(ValueError, 'Unexpected|review'):
                self.recover()
        self.assertEqual(added.read_bytes(), b'synthetic late file')
        self.assertEqual(self.journal.read_bytes(), self.journal_before)
        self.assertEqual(self.kept.read_bytes(), b'synthetic known kept bytes')
        self.assertEqual((self.saved / 'data/safe/old.bin').read_bytes(), b'synthetic old data/safe')
        self.assertEqual((self.saved / 'data/ambiguous/old.bin').read_bytes(), b'synthetic old data/ambiguous')

    def test_known_kept_file_can_complete_a_clean_recovery(self):
        self.recover()
        self.assertFalse(self.journal.exists())
        self.assertFalse(self.kept.exists())
        self.assertEqual((self.world / 'data/ambiguous/old.bin').read_bytes(), b'synthetic old data/ambiguous')
        self.assertEqual((self.world / 'data/safe/old.bin').read_bytes(), b'synthetic old data/safe')


if __name__ == '__main__':
    unittest.main()
