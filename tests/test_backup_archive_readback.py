"""A staged synthetic backup must be readable before publication."""
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native and launcher locks')
class BackupArchiveReadbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        (self.world / 'data/empty').mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        (self.root / 'fabric/server.properties').write_text('level-name=world\n')

    def test_corrupt_staged_header_is_not_published(self):
        actual = community.sync_file
        def corrupt(stream):
            actual(stream)
            os.pwrite(stream.fileno(), b'BAD!', 0)
        with patch.object(community, 'sync_file', side_effect=corrupt):
            with self.assertRaisesRegex(ValueError, 'archive|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_healthy_archive_still_contains_file_and_empty_directory(self):
        name = community.backup(self.root, {'level-name': 'world'})
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')
            self.assertTrue(archive.getinfo('world/data/empty/').is_dir())

    def assert_unpublished_evidence(self):
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_corrupt_staged_payload_is_not_published(self):
        actual = community.sync_file
        def corrupt(stream):
            actual(stream)
            with zipfile.ZipFile(stream, 'r') as archive:
                info = archive.getinfo('world/level.dat')
            header = os.pread(stream.fileno(), 30, info.header_offset)
            name_length, extra_length = struct.unpack_from('<HH', header, 26)
            offset = info.header_offset + 30 + name_length + extra_length
            original = os.pread(stream.fileno(), info.compress_size, offset)
            self.assertGreater(len(original), 0)
            os.pwrite(stream.fileno(), b'\xff' * len(original), offset)
        with patch.object(community, 'sync_file', side_effect=corrupt):
            with self.assertRaisesRegex(ValueError, 'archive|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assert_unpublished_evidence()
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic level')

    def test_valid_but_different_staged_zip_does_not_match_writer_index(self):
        actual = community.sync_file
        def replace(stream):
            actual(stream)
            stream.seek(0)
            stream.truncate()
            with zipfile.ZipFile(stream, 'w') as archive:
                archive.writestr('world/level.dat', b'different synthetic content')
            stream.flush()
        with patch.object(community, 'sync_file', side_effect=replace):
            with self.assertRaisesRegex(ValueError, 'archive|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assert_unpublished_evidence()

    def test_large_file_readback_sends_progress_and_passes(self):
        data = b'synthetic-' * (512 * 1024)
        (self.world / 'data/large.bin').write_bytes(data)
        name = community.backup(self.root, {'level-name': 'world'})
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/data/large.bin'), data)

    def test_large_archive_index_is_streamed_to_verifier(self):
        archive_path = self.root / 'synthetic-many-entries.zip'
        with zipfile.ZipFile(archive_path, 'w') as archive:
            for index in range(1500):
                archive.writestr('synthetic/%04d-' % index + 'x' * 60, b'')
            expected = community.backup_archive_index(archive, {entry.filename: ('literal', '') for entry in archive.infolist()})
        self.assertGreater(len(json.dumps(expected).encode()), 65536)
        state = community._BackupReaderState()
        with archive_path.open('rb') as stream:
            community.verify_staged_backup_archive(stream, expected, state)
        self.assertEqual(state.unconfirmed, 0)

    def test_verifier_failure_without_trailer_is_not_success(self):
        with patch.object(community, '_BACKUP_ARCHIVE_VERIFIER', 'import sys;sys.exit(7)'):
            with self.assertRaisesRegex(ValueError, 'verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assert_unpublished_evidence()

    def test_direct_verifier_requires_trailer_even_with_ignored_sigchld(self):
        archive_path = self.root / 'synthetic-input.zip'
        with zipfile.ZipFile(archive_path, 'w') as archive:
            archive.writestr('synthetic', b'data')
            expected = community.backup_archive_index(archive, {'synthetic': ('literal', b'data'.hex())})
        state = community._BackupReaderState()
        with archive_path.open('rb') as stream:
            previous = signal.signal(signal.SIGCHLD, signal.SIG_IGN)
            try:
                with patch.object(community, '_BACKUP_ARCHIVE_VERIFIER', 'import sys;sys.exit(7)'):
                    with self.assertRaisesRegex(ValueError, 'verification'):
                        community.verify_staged_backup_archive(stream, expected, state)
            finally:
                signal.signal(signal.SIGCHLD, previous)
        self.assertEqual(state.unconfirmed, 0)

    def test_stalled_verifier_is_killed_and_reaped(self):
        script = 'import time;time.sleep(30)'
        actual_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = actual_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        start = time.monotonic()
        with patch.object(community, '_BACKUP_ARCHIVE_VERIFIER', script), \
             patch.object(community, '_ARCHIVE_VERIFY_IDLE_TIMEOUT', 0.2), \
             patch.object(community, '_ARCHIVE_VERIFY_BASE_TIMEOUT', 1), \
             patch.object(community, '_ARCHIVE_VERIFY_MIN_BYTES_PER_SECOND', 10**9), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaisesRegex(ValueError, 'verification.*progress|verification.*timed out'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertLess(time.monotonic() - start, 5)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assert_unpublished_evidence()

    def test_excess_verifier_output_is_bounded_and_rejected(self):
        script = "import os,time;os.write(1,b'x'*65536);time.sleep(30)"
        actual_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = actual_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        with patch.object(community, '_BACKUP_ARCHIVE_VERIFIER', script), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaisesRegex(ValueError, 'unexpected data|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assert_unpublished_evidence()

    def test_archive_reader_does_not_release_the_parent_native_lock(self):
        descriptor = community.acquire_world_session_lock(self.world)
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        def contender():
            return subprocess.run([sys.executable, '-I', '-S', '-c', code,
                                   str(self.world / 'session.lock')], timeout=10).returncode
        class Borrowed:
            def fileno(self):
                return descriptor.fileno()
        state = community._BackupReaderState()
        try:
            self.assertEqual(contender(), 42)
            with self.assertRaisesRegex(ValueError, 'verification'):
                community.verify_staged_backup_archive(Borrowed(), [], state)
            self.assertEqual(state.unconfirmed, 0)
            self.assertEqual(contender(), 42)
        finally:
            descriptor.close()
        self.assertEqual(contender(), 0)

    def test_unconfirmed_verifier_cleanup_retains_the_marker(self):
        actual_stop = community._stop_backup_reader
        verifier = community._BACKUP_ARCHIVE_VERIFIER
        def unconfirmed(reader):
            actual_stop(reader)
            if len(reader.args) > 4 and reader.args[4] == verifier:
                raise community.BackupReaderUncertain('synthetic unconfirmed verification cleanup')
        with patch.object(community, '_stop_backup_reader', side_effect=unconfirmed):
            with self.assertRaises(community.BackupReaderUncertain):
                community.backup(self.root, {'level-name': 'world'})
        self.assert_unpublished_evidence()

    def test_interrupting_verifier_stops_observed_child_and_retains_evidence(self):
        actual_verify = community.verify_staged_backup_archive
        actual_popen = subprocess.Popen
        verifier = community._BACKUP_ARCHIVE_VERIFIER
        readers = []
        def capture(command, *args, **kwargs):
            process = actual_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == verifier:
                readers.append(process)
            return process
        def interrupt(stream, expected, state):
            with patch.object(community.os, 'set_blocking', side_effect=KeyboardInterrupt()):
                return actual_verify(stream, expected, state)
        with patch.object(community, 'verify_staged_backup_archive', side_effect=interrupt), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaises(KeyboardInterrupt):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assert_unpublished_evidence()

    def test_interrupted_verifier_spawn_keeps_evidence_until_host_review(self):
        actual_popen = subprocess.Popen
        verifier = community._BACKUP_ARCHIVE_VERIFIER
        untracked = []
        def interrupt_after_spawn(command, *args, **kwargs):
            process = actual_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == verifier:
                untracked.append(process)
                raise KeyboardInterrupt()
            return process
        try:
            with patch.object(community.subprocess, 'Popen', side_effect=interrupt_after_spawn):
                with self.assertRaises(KeyboardInterrupt):
                    community.backup(self.root, {'level-name': 'world'})
            self.assertEqual(len(untracked), 1)
            self.assert_unpublished_evidence()
        finally:
            for process in untracked:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=5)  # Only this test's synthetic child.


if __name__ == '__main__':
    unittest.main()
