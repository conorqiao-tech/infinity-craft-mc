"""A verifier checking small entries must distinguish progress from a stall."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX archive-reader isolation')
class BackupVerifierProgressTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'synthetic.zip'
        with zipfile.ZipFile(self.path, 'w') as archive:
            for i in range(8):
                archive.writestr('synthetic/empty-%d' % i, b'')
            checks = {entry.filename: ('literal', '') for entry in archive.infolist()}
            self.expected = community.backup_archive_index(archive, checks)

    def test_slow_empty_entries_keep_reader_active(self):
        original = community._BACKUP_ARCHIVE_VERIFIER
        needle = '                with archive.open(item, "r") as source:'
        instrumented = original.replace(needle, '                import time\n                time.sleep(0.3)\n' + needle, 1)
        self.assertNotEqual(original, instrumented)
        state = community._BackupReaderState()
        with self.path.open('rb') as stream, \
             patch.object(community, '_BACKUP_ARCHIVE_VERIFIER', instrumented), \
             patch.object(community, '_ARCHIVE_VERIFY_IDLE_TIMEOUT', 1.2), \
             patch.object(community, '_ARCHIVE_VERIFY_BASE_TIMEOUT', 10):
            community.verify_staged_backup_archive(stream, self.expected, state)
        self.assertEqual(state.unconfirmed, 0)

    def test_progress_followed_by_a_stall_is_still_stopped(self):
        script = "import os,sys,time;sys.stdin.buffer.read();os.write(1,b'.');time.sleep(30)"
        state = community._BackupReaderState()
        readers = []
        actual_popen = subprocess.Popen
        def capture(*args, **kwargs):
            child = actual_popen(*args, **kwargs)
            readers.append(child)
            return child
        started = time.monotonic()
        with self.path.open('rb') as stream, \
             patch.object(community, '_BACKUP_ARCHIVE_VERIFIER', script), \
             patch.object(community, '_ARCHIVE_VERIFY_IDLE_TIMEOUT', 0.2), \
             patch.object(community, '_ARCHIVE_VERIFY_BASE_TIMEOUT', 2), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaisesRegex(ValueError, 'progress|timed out'):
                community.verify_staged_backup_archive(stream, self.expected, state)
        self.assertLess(time.monotonic() - started, 5)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assertEqual(state.unconfirmed, 0)


if __name__ == '__main__':
    unittest.main()
