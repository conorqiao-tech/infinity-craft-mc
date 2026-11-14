"""Synthetic journal substitutions must not redirect terrain recovery."""
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
import world_refresh as refresh


@unittest.skipUnless(os.name == 'posix', 'requires POSIX locks and FIFOs')
class RecoveryJournalReadIsolationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.terrain = self.root / 'backups/Terrain-Refresh-synthetic/terrain'
        for name in ('original-region', 'alternate-region'):
            (self.terrain / name).mkdir(parents=True)
            (self.terrain / name / 'old.bin').write_bytes(('synthetic ' + name).encode())
        common = {'format': 1, 'state': 'moving', 'world': 'world',
                  'rollback': 'Terrain-Refresh-synthetic', 'kept_files': {}}
        self.journal = self.root / refresh.JOURNAL
        self.original = json.dumps(dict(common, moved_paths=['original-region'])).encode()
        self.alternate = json.dumps(dict(common, moved_paths=['alternate-region'])).encode()
        self.journal.write_bytes(self.original)
        self.replacement = self.root / 'synthetic-alternate.json'
        self.replacement.write_bytes(self.alternate)
        self.retained = self.root / 'synthetic-original.json'
        self.lease = launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        os.write(self.lease, str(os.getpid()).encode())
        self.addCleanup(os.close, self.lease)

    def swap_in_another_process(self):
        code = ('import os,sys;os.replace(sys.argv[1],sys.argv[2]);'
                'os.replace(sys.argv[3],sys.argv[1])')
        subprocess.run([sys.executable, '-I', '-S', '-c', code, str(self.journal),
                        str(self.retained), str(self.replacement)], check=True, timeout=10)

    def assert_swapped_evidence_retained(self):
        self.assertEqual(self.retained.read_bytes(), self.original)
        self.assertEqual(self.journal.read_bytes(), self.alternate)
        for name in ('original-region', 'alternate-region'):
            self.assertEqual((self.terrain / name / 'old.bin').read_bytes(), ('synthetic ' + name).encode())
            self.assertFalse((self.world / name).exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_substitution_after_discovery_is_refused_before_terrain_changes(self):
        actual = refresh._terrain_entry
        swapped = []
        def observe(path):
            info = actual(path)
            if path == self.journal and not swapped:
                self.swap_in_another_process()
                swapped.append(True)
            return info
        with patch.object(refresh, '_terrain_entry', side_effect=observe):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(swapped, [True])
        self.assert_swapped_evidence_retained()

    def test_substitution_after_preflight_is_refused_before_first_move(self):
        actual = refresh._recovery_item
        observed = []
        def preflight(*args):
            result = actual(*args)
            if not observed:
                self.swap_in_another_process()
            observed.append(True)
            return result
        with patch.object(refresh, '_recovery_item', side_effect=preflight):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertTrue(observed)
        self.assert_swapped_evidence_retained()

    def test_in_place_journal_change_after_preflight_is_refused(self):
        actual = refresh._recovery_item
        observed = []
        def preflight(*args):
            result = actual(*args)
            if not observed:
                self.journal.write_bytes(self.alternate)
            observed.append(True)
            return result
        with patch.object(refresh, '_recovery_item', side_effect=preflight):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertTrue(observed)
        self.assertEqual(self.journal.read_bytes(), self.alternate)
        self.assertTrue((self.terrain / 'original-region/old.bin').exists())
        self.assertFalse((self.world / 'original-region').exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_journal_change_after_move_is_retained_for_review(self):
        actual = refresh.move
        def move_and_swap(source, target):
            actual(source, target)
            self.swap_in_another_process()
        with patch.object(refresh, 'move', side_effect=move_and_swap):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(self.retained.read_bytes(), self.original)
        self.assertEqual(self.journal.read_bytes(), self.alternate)
        self.assertEqual((self.world / 'original-region/old.bin').read_bytes(), b'synthetic original-region')
        self.assertTrue((self.terrain / 'alternate-region/old.bin').exists())
        self.assertFalse((self.root / 'backups/Terrain-Refresh-synthetic/MANIFEST.json').exists())
        self.assertTrue((self.root / 'launcher.lock').exists())

    def test_fifo_substitution_after_discovery_does_not_block_launcher(self):
        root = self.root / 'synthetic-fifo-root'
        root.mkdir()
        journal = root / refresh.JOURNAL
        journal.write_text('{}')
        code = r'''
import os, sys
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, sys.argv[1])
import server, world_refresh as r
root = Path(sys.argv[2]); path = root / r.JOURNAL
lease = server.acquire_launcher_lock(server.parse_args([]), root)
os.write(lease, str(os.getpid()).encode())
actual = r._terrain_entry
swapped = []
def observe(item):
    info = actual(item)
    if item == path and not swapped:
        os.replace(path, root / 'synthetic-original.json')
        os.mkfifo(path)
        swapped.append(True)
    return info
try:
    with patch.object(r, '_terrain_entry', side_effect=observe):
        try:
            r.recover(root, lease_fd=lease)
        except ValueError:
            sys.exit(0)
    sys.exit(7)
finally:
    os.close(lease)
'''
        try:
            result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                     str(Path(refresh.__file__).parent), str(root)],
                                    capture_output=True, timeout=4)
        except subprocess.TimeoutExpired:
            self.fail('synthetic FIFO caused the recovery process to block')
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
        self.assertTrue((root / 'synthetic-original.json').is_file())
        self.assertTrue((root / 'launcher.lock').exists())
        with self.assertRaisesRegex(RuntimeError, 'launcher.lock'):
            launcher.check_launcher_lock(launcher.parse_args([]), root)

    def test_failed_reader_with_ignored_sigchld_is_refused(self):
        code = ("import signal,sys;sys.path.insert(0,sys.argv[1]);import world_refresh as r;"
                "signal.signal(signal.SIGCHLD,signal.SIG_IGN);"
                "r._POLICY_READER='import sys;sys.exit(7)';\n"
                "try:r._read_journal_bytes(__import__('pathlib').Path(sys.argv[2]),"
                "__import__('pathlib').Path(sys.argv[2]).lstat())\n"
                "except ValueError:sys.exit(0)\nelse:sys.exit(9)")
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                 str(Path(refresh.__file__).parent), str(self.journal)],
                                capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors='replace'))
        self.assertEqual(self.journal.read_bytes(), self.original)

    def test_oversized_journal_requires_review(self):
        info = self.journal.lstat()
        with patch.object(refresh, '_JOURNAL_SIZE_LIMIT', info.st_size - 1):
            with self.assertRaisesRegex(ValueError, 'journal|review'):
                refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(self.journal.read_bytes(), self.original)

    def test_linked_journal_requires_review(self):
        link = self.root / 'synthetic-journal-hardlink'
        os.link(self.journal, link)
        with self.assertRaisesRegex(ValueError, 'journal|review'):
            refresh.recover(self.root, lease_fd=self.lease)
        self.assertEqual(self.journal.read_bytes(), self.original)
        self.assertEqual(link.read_bytes(), self.original)


if __name__ == '__main__':
    unittest.main()
