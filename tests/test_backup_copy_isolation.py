"""Synthetic backup reads must not release their process's native world lock."""
from dataclasses import replace
import io
import os
from pathlib import Path
import signal
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
class BackupCopyIsolationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.world = self.root / 'fabric/world'
        self.world.mkdir(parents=True)
        self.lock = self.world / 'session.lock'
        self.lock.write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic level')
        self.target = self.world / 'region/r.0.0.mca'
        self.target.parent.mkdir()
        self.data = b'synthetic region bytes' * 100
        self.target.write_bytes(self.data)
        self.props = {'level-name': 'world'}

    def contender(self):
        code = ("import fcntl,sys;f=open(sys.argv[1],'r+b');\n"
                "try:fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)\n"
                "except BlockingIOError:sys.exit(42)\nelse:sys.exit(0)")
        return subprocess.run([sys.executable, '-c', code, str(self.lock)], timeout=10).returncode

    def exercise_swap(self, actor_code):
        real_write = community.write_backup_file
        observations = []
        original = self.target.with_name('synthetic-original')

        def raced_write(output, entry, fabric):
            if entry.root == self.world and entry.parts == ('region', 'r.0.0.mca'):
                observations.append(self.contender())
                subprocess.run([sys.executable, '-c', actor_code, str(self.target), str(self.lock)],
                               check=True, timeout=10)
                try:
                    with self.assertRaises(ValueError):
                        real_write(output, entry, fabric)
                finally:
                    self.target.unlink()
                    original.rename(self.target)
                observations.append(self.contender())
                raise ValueError('synthetic substitution refused')
            return real_write(output, entry, fabric)

        with patch.object(community, 'write_backup_file', side_effect=raced_write):
            with self.assertRaisesRegex(ValueError, 'substitution refused'):
                community.backup(self.root, self.props)
        self.assertEqual(observations, [42, 42])
        self.assertEqual(self.target.read_bytes(), self.data)
        self.assertEqual(self.lock.stat().st_nlink, 1)
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_native_lock_alias_during_backup_does_not_release_parent_lock(self):
        actor = ("import os,sys;from pathlib import Path;p=Path(sys.argv[1]);"
                 "p.rename(p.with_name('synthetic-original'));os.link(sys.argv[2],p)")
        self.exercise_swap(actor)

    def test_native_lock_symlink_during_backup_is_rejected(self):
        actor = ("import os,sys;from pathlib import Path;p=Path(sys.argv[1]);"
                 "p.rename(p.with_name('synthetic-original'));os.symlink(sys.argv[2],p)")
        self.exercise_swap(actor)

    def test_fifo_substituted_after_inventory_is_rejected_without_hanging(self):
        actor = ("import os,sys;from pathlib import Path;p=Path(sys.argv[1]);"
                 "p.rename(p.with_name('synthetic-original'));os.mkfifo(p)")
        self.exercise_swap(actor)

    def test_clean_backup_preserves_all_world_content(self):
        self.target.write_bytes(self.data * 1024)
        name = community.backup(self.root, self.props)
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/region/r.0.0.mca'), self.data * 1024)
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')
            self.assertNotIn('world/session.lock', archive.namelist())

    def test_short_successful_reader_is_not_published(self):
        with patch.object(community, '_BACKUP_FILE_READER', "import os;os.write(1,b'x')"):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, self.props)
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertEqual(len(list((self.root / 'backups').glob('*.partial'))), 1)
        self.assertEqual(self.target.read_bytes(), self.data)

    def test_reader_error_after_complete_output_is_not_published(self):
        script = "import os,sys;os.write(1,b'synthetic level');sys.exit(22)"
        with patch.object(community, '_BACKUP_FILE_READER', script):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, self.props)
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        self.assertEqual(self.target.read_bytes(), self.data)

    def test_ignored_sigchld_cannot_turn_a_failed_reader_into_success(self):
        entry = next(item for item in community.backup_world_files(self.world) if item.parts == ('level.dat',))
        script = "import os,sys;os.write(1,b'synthetic level');sys.exit(22)"
        previous = signal.signal(signal.SIGCHLD, signal.SIG_IGN)
        try:
            with patch.object(community, '_BACKUP_FILE_READER', script), zipfile.ZipFile(io.BytesIO(), 'w') as output:
                with self.assertRaisesRegex(ValueError, 'changed'):
                    community.write_backup_file(output, entry, self.world.parent)
        finally:
            signal.signal(signal.SIGCHLD, previous)

    def test_ignored_sigchld_allows_a_reader_with_a_valid_trailer(self):
        entry = next(item for item in community.backup_world_files(self.world) if item.parts == ('level.dat',))
        buffer = io.BytesIO()
        previous = signal.signal(signal.SIGCHLD, signal.SIG_IGN)
        try:
            with zipfile.ZipFile(buffer, 'w') as output:
                community.write_backup_file(output, entry, self.world.parent)
        finally:
            signal.signal(signal.SIGCHLD, previous)
        with zipfile.ZipFile(buffer) as archive:
            self.assertEqual(archive.read('world/level.dat'), b'synthetic level')

    def test_reader_extra_bytes_are_not_published(self):
        script = "import os;os.write(1,b'x'*1024)"
        with patch.object(community, '_BACKUP_FILE_READER', script):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, self.props)
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_reader_with_extra_bytes_after_trailer_is_not_published(self):
        script = "import os;os.write(1,b'synthetic levelINFINITY_BACKUP_FILE_OK_V1extra')"
        with patch.object(community, '_BACKUP_FILE_READER', script):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, self.props)
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_empty_file_round_trips_without_including_reader_trailer(self):
        (self.world / 'empty.bin').write_bytes(b'')
        name = community.backup(self.root, self.props)
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertEqual(archive.read('world/empty.bin'), b'')

    def test_reader_killed_by_signal_is_not_published(self):
        script = 'import os,signal;os.kill(os.getpid(),signal.SIGKILL)'
        with patch.object(community, '_BACKUP_FILE_READER', script):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, self.props)
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_streamed_entry_uses_zip64_when_required(self):
        data = self.data * 1024
        self.target.write_bytes(data)
        with patch.object(zipfile, 'ZIP64_LIMIT', 1024 * 1024):
            name = community.backup(self.root, self.props)
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertGreaterEqual(archive.getinfo('world/region/r.0.0.mca').extract_version, 45)
            self.assertEqual(archive.read('world/region/r.0.0.mca'), data)

    def test_stalled_reader_is_killed_and_reaped_before_backup_returns(self):
        script = 'import time;time.sleep(30)'
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        with patch.object(community, '_BACKUP_FILE_READER', script), \
             patch.object(community, '_BACKUP_READER_IDLE_TIMEOUT', 0.1), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaisesRegex(ValueError, 'progress'):
                community.backup(self.root, self.props)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertEqual(len(list((self.root / 'backups').glob('*.partial'))), 1)

    def test_trickling_reader_is_bounded_by_total_deadline(self):
        script = "import os,time\nfor _ in range(15):\n os.write(1,b'x');time.sleep(0.04)"
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        with patch.object(community, '_BACKUP_FILE_READER', script), \
             patch.object(community, '_BACKUP_READER_IDLE_TIMEOUT', 0.15), \
             patch.object(community, '_BACKUP_READER_BASE_TIMEOUT', 0.2), \
             patch.object(community, '_BACKUP_READER_MIN_BYTES_PER_SECOND', 10**9), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaisesRegex(ValueError, 'progress'):
                community.backup(self.root, self.props)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_reader_hanging_after_stdout_eof_is_bounded_by_total_deadline(self):
        script = 'import os,time;os.close(1);time.sleep(30)'
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        start = time.monotonic()
        with patch.object(community, '_BACKUP_FILE_READER', script), \
             patch.object(community, '_BACKUP_READER_IDLE_TIMEOUT', 5), \
             patch.object(community, '_BACKUP_READER_BASE_TIMEOUT', 0.2), \
             patch.object(community, '_BACKUP_READER_MIN_BYTES_PER_SECOND', 10**9), \
             patch.object(community.subprocess, 'Popen', side_effect=capture):
            with self.assertRaises(ValueError):
                community.backup(self.root, self.props)
        self.assertLess(time.monotonic() - start, 2)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_archive_write_failure_kills_and_reaps_reader(self):
        script = "import os,time;os.write(1,b'x');time.sleep(30)"
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        with patch.object(community, '_BACKUP_FILE_READER', script), \
             patch.object(community.subprocess, 'Popen', side_effect=capture), \
             patch.object(zipfile._ZipWriteFile, 'write', side_effect=OSError('synthetic disk failure')):
            with self.assertRaisesRegex(ValueError, 'could not be read'):
                community.backup(self.root, self.props)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_interrupted_archive_write_reaps_reader_and_keeps_launcher_marker(self):
        script = "import os,time;os.write(1,b'x');time.sleep(30)"
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
            return process
        with patch.object(community, '_BACKUP_FILE_READER', script), \
             patch.object(community.subprocess, 'Popen', side_effect=capture), \
             patch.object(zipfile._ZipWriteFile, 'write', side_effect=KeyboardInterrupt()):
            with self.assertRaises(KeyboardInterrupt):
                community.backup(self.root, self.props)
        self.assertEqual(len(readers), 1)
        self.assertIsNotNone(readers[0].poll())
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_unconfirmed_reader_cleanup_retains_manual_launcher_marker(self):
        script = 'import time;time.sleep(30)'
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                real_wait = process.wait
                readers.append((process, real_wait))
                def unconfirmed(timeout=None):
                    raise subprocess.TimeoutExpired(command, timeout)
                process.wait = unconfirmed
            return process
        try:
            with patch.object(community, '_BACKUP_FILE_READER', script), \
                 patch.object(community, '_BACKUP_READER_IDLE_TIMEOUT', 0.1), \
                 patch.object(community.subprocess, 'Popen', side_effect=capture):
                with self.assertRaises(community.BackupReaderUncertain):
                    community.backup(self.root, self.props)
            self.assertTrue((self.root / 'launcher.lock').exists())
            self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        finally:
            for process, real_wait in readers:
                if process.poll() is None:
                    process.kill()
                real_wait(timeout=5)

    def test_archive_close_cannot_mask_uncertain_reader_cleanup(self):
        real_stop = community._stop_backup_reader
        real_close = zipfile.ZipFile.close
        close_errors = []
        def uncertain_after_reap(reader):
            real_stop(reader)
            raise community.BackupReaderUncertain('synthetic uncertain cleanup')
        def fail_close_once(archive):
            real_close(archive)
            if not close_errors:
                close_errors.append(True)
                raise OSError('synthetic archive close failure')
        with patch.object(community, '_stop_backup_reader', side_effect=uncertain_after_reap), \
             patch.object(zipfile.ZipFile, 'close', fail_close_once):
            with self.assertRaisesRegex(OSError, 'archive close failure'):
                community.backup(self.root, self.props)
        self.assertEqual(close_errors, [True])
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_archive_close_cannot_mask_interruption(self):
        real_close = zipfile.ZipFile.close
        close_errors = []
        def fail_close_once(archive):
            real_close(archive)
            if not close_errors:
                close_errors.append(True)
                raise OSError('synthetic archive close failure')
        with patch.object(zipfile._ZipWriteFile, 'write', side_effect=KeyboardInterrupt()), \
             patch.object(zipfile.ZipFile, 'close', fail_close_once):
            with self.assertRaisesRegex(OSError, 'archive close failure'):
                community.backup(self.root, self.props)
        self.assertEqual(close_errors, [True])
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_parent_close_cannot_mask_uncertain_reader_cleanup(self):
        real_stop = community._stop_backup_reader
        real_open = community.open_backup_parent
        real_close = os.close
        opened = set()
        def uncertain_after_reap(reader):
            real_stop(reader)
            raise community.BackupReaderUncertain('synthetic uncertain cleanup')
        def record_parent(entry):
            descriptor = real_open(entry)
            opened.add(descriptor)
            return descriptor
        def fail_parent_close(descriptor):
            if descriptor in opened:
                opened.remove(descriptor)
                real_close(descriptor)
                raise OSError('synthetic parent close failure')
            return real_close(descriptor)
        with patch.object(community, '_stop_backup_reader', side_effect=uncertain_after_reap), \
             patch.object(community, 'open_backup_parent', side_effect=record_parent), \
             patch.object(community.os, 'close', side_effect=fail_parent_close):
            with self.assertRaisesRegex(ValueError, 'could not be read'):
                community.backup(self.root, self.props)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_failed_reader_spawn_retains_manual_launcher_marker(self):
        real_popen = subprocess.Popen
        def fail_reader(command, *args, **kwargs):
            if len(command) > 4 and command[4] == community._BACKUP_FILE_READER:
                raise OSError('synthetic spawn failure')
            return real_popen(command, *args, **kwargs)
        with patch.object(community.subprocess, 'Popen', side_effect=fail_reader):
            with self.assertRaisesRegex(ValueError, 'could not be read'):
                community.backup(self.root, self.props)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_interrupt_during_reader_cleanup_retains_manual_launcher_marker(self):
        script = 'import time;time.sleep(30)'
        real_popen = subprocess.Popen
        readers = []
        def capture(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                real_wait = process.wait
                readers.append((process, real_wait))
                def interrupted(timeout=None):
                    raise KeyboardInterrupt()
                process.wait = interrupted
            return process
        try:
            with patch.object(community, '_BACKUP_FILE_READER', script), \
                 patch.object(community, '_BACKUP_READER_IDLE_TIMEOUT', 0.1), \
                 patch.object(community.subprocess, 'Popen', side_effect=capture):
                with self.assertRaises(community.BackupReaderUncertain):
                    community.backup(self.root, self.props)
            self.assertTrue((self.root / 'launcher.lock').exists())
            self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        finally:
            for process, real_wait in readers:
                if process.poll() is None:
                    process.kill()
                real_wait(timeout=5)

    def test_interrupted_spawn_retains_marker_when_child_handle_is_unavailable(self):
        script = 'import time;time.sleep(30)'
        real_popen = subprocess.Popen
        readers = []
        def interrupt_after_spawn(command, *args, **kwargs):
            process = real_popen(command, *args, **kwargs)
            if len(command) > 4 and command[4] == script:
                readers.append(process)
                raise KeyboardInterrupt()
            return process
        try:
            with patch.object(community, '_BACKUP_FILE_READER', script), \
                 patch.object(community.subprocess, 'Popen', side_effect=interrupt_after_spawn):
                with self.assertRaises(KeyboardInterrupt):
                    community.backup(self.root, self.props)
            self.assertEqual(len(readers), 1)
            self.assertTrue((self.root / 'launcher.lock').exists())
            self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))
        finally:
            for process in readers:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
                process.stdout.close()

    def test_reader_does_not_inherit_native_lock_descriptor(self):
        original_acquire = community.acquire_world_session_lock
        original_script = community._BACKUP_FILE_READER
        def acquire(world):
            descriptor = original_acquire(world)
            held = os.fstat(descriptor)
            check = ("import os,sys\n"
                     f"try: info=os.fstat({descriptor})\n"
                     "except OSError: pass\n"
                     f"else:\n if (info.st_dev,info.st_ino)==({held.st_dev},{held.st_ino}): sys.exit(25)\n")
            community._BACKUP_FILE_READER = check + original_script
            return descriptor
        try:
            with patch.object(community, 'acquire_world_session_lock', side_effect=acquire):
                name = community.backup(self.root, self.props)
            with zipfile.ZipFile(self.root / 'backups' / name) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read('world/region/r.0.0.mca'), self.data)
        finally:
            community._BACKUP_FILE_READER = original_script

    def test_reader_rejects_a_different_parent_directory_descriptor(self):
        outside = self.root / 'synthetic-outside'
        outside.mkdir()
        (outside / 'r.0.0.mca').write_bytes(b'unrelated synthetic bytes')
        original_open = community.open_backup_parent
        def replace_parent(entry):
            if entry.parts == ('region', 'r.0.0.mca'):
                return community.open_backup_directory(outside)
            return original_open(entry)
        with patch.object(community, 'open_backup_parent', side_effect=replace_parent):
            with self.assertRaisesRegex(ValueError, 'changed'):
                community.backup(self.root, self.props)
        self.assertEqual((outside / 'r.0.0.mca').read_bytes(), b'unrelated synthetic bytes')
        self.assertFalse(list((self.root / 'backups').glob('Infinity-World-*.zip')))

    def test_invalid_leaf_names_are_refused_before_starting_reader(self):
        entry = community.backup_world_files(self.world)[0]
        for leaf in ('', '.', '..', 'nested/name', 'nul\0name'):
            with self.subTest(leaf=leaf), zipfile.ZipFile(io.BytesIO(), 'w') as output:
                with self.assertRaisesRegex(ValueError, 'invalid backup filename'):
                    community.write_backup_file(output, replace(entry, parts=(leaf,)), self.world.parent)


if __name__ == '__main__':
    unittest.main()
