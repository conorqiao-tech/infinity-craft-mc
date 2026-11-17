"""A synthetic backup retains source digests for a later read-only check."""
import hashlib
import json
import os
from pathlib import Path
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native and launcher locks')
class BackupRestoreVerifierTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        (self.world / 'data/empty').mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic world data')
        (self.root / 'fabric/ops.json').write_text('[]')

    def backup(self):
        return self.root / 'backups' / community.backup(self.root, {'level-name': 'world'})

    def check(self, path):
        script = Path(__file__).resolve().parents[1] / 'server/verify_backup.py'
        return subprocess.run([sys.executable, '-I', '-S', str(script), str(path)],
                              capture_output=True, text=True, timeout=30)

    def rewrite(self, path, *, replacement=None, omit=None):
        target = path.with_name('synthetic-rewrite.zip')
        with zipfile.ZipFile(path) as original, zipfile.ZipFile(target, 'w') as output:
            for entry in original.infolist():
                if entry.filename == omit:
                    continue
                data = original.read(entry)
                if replacement and entry.filename in replacement:
                    data = replacement[entry.filename]
                output.writestr(entry, data)
        os.replace(target, path)

    def test_backup_retains_file_digests_and_empty_directories(self):
        path = self.backup()
        with zipfile.ZipFile(path) as archive:
            info = json.loads(archive.read('BACKUP_INFO.json'))
            manifest = info['digest_manifest']
            expected = {row['path']: row['sha256'] for row in manifest['files']}
            self.assertEqual(expected['world/level.dat'], hashlib.sha256(b'synthetic world data').hexdigest())
            self.assertEqual(expected['ops.json'], hashlib.sha256(b'[]').hexdigest())
            self.assertIn('world/data/empty/', manifest['empty_directories'])
            self.assertEqual(info['world'], 'world')
            self.assertNotIn('world/session.lock', expected)
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        result = self.check(path)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), {'status': 'digest_check_passed', 'files': 2, 'empty_directories': 1})
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)

    def test_equal_crc_corruption_after_backup_is_detected(self):
        first = bytes.fromhex('fc89b8960a31bff0')
        second = bytes.fromhex('dbdbff47d3678eec')
        self.assertEqual(zlib.crc32(first), zlib.crc32(second))
        self.assertNotEqual(first, second)
        (self.world / 'level.dat').write_bytes(first)
        path = self.backup()
        def raw(data):
            compressor = zlib.compressobj(1, zlib.DEFLATED, -15)
            return compressor.compress(data) + compressor.flush()
        with path.open('r+b') as stream:
            with zipfile.ZipFile(stream) as archive:
                item = archive.getinfo('world/level.dat')
            fixed = os.pread(stream.fileno(), 30, item.header_offset)
            name_length, extra_length = struct.unpack_from('<HH', fixed, 26)
            offset = item.header_offset + 30 + name_length + extra_length
            self.assertEqual(os.pread(stream.fileno(), item.compress_size, offset), raw(first))
            self.assertEqual(len(raw(first)), len(raw(second)))
            os.pwrite(stream.fileno(), raw(second), offset)
        with zipfile.ZipFile(path) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), second)
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        result = self.check(path)
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn('digest', result.stderr)
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)
        self.assertEqual((self.world / 'level.dat').read_bytes(), first)

    def test_legacy_archive_is_retained_and_reported_separately(self):
        path = self.root / 'synthetic-legacy.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('world/level.dat', b'older synthetic data')
            archive.writestr('BACKUP_INFO.json', json.dumps({'format': 1, 'created_utc': 'old', 'world': 'world'}))
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        result = self.check(path)
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertIn('no digest manifest', result.stderr)
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)

    def test_unicode_nested_world_is_verified(self):
        world = self.root / 'fabric/\u4e16\u754c/nested'
        (world / 'empty').mkdir(parents=True)
        (world / 'session.lock').write_bytes(b'synthetic native lock')
        (world / 'level.dat').write_bytes(b'unicode world')
        name = community.backup(self.root, {'level-name': '\u4e16\u754c/nested'})
        result = self.check(self.root / 'backups' / name)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_forced_zip64_backup_is_verified(self):
        (self.world / 'level.dat').write_bytes(b'synthetic zip64 data' * 200)
        with patch.object(zipfile, 'ZIP64_LIMIT', 1024):
            path = self.backup()
        with zipfile.ZipFile(path) as archive:
            item = archive.getinfo('world/level.dat')
        with path.open('rb') as stream:
            fixed = os.pread(stream.fileno(), 30, item.header_offset)
        self.assertEqual(struct.unpack_from('<II', fixed, 18), (0xffffffff, 0xffffffff))
        result = self.check(path)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_local_header_crc_mismatch_after_backup_is_refused(self):
        path = self.backup()
        with path.open('r+b') as stream:
            with zipfile.ZipFile(stream) as archive:
                item = archive.getinfo('world/level.dat')
            offset = item.header_offset + 14
            crc = struct.unpack('<I', os.pread(stream.fileno(), 4, offset))[0]
            os.pwrite(stream.fileno(), struct.pack('<I', crc ^ 1), offset)
        with zipfile.ZipFile(path) as archive:
            self.assertIsNone(archive.testzip())
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('local header', result.stderr)

    def test_world_path_overlapping_backup_info_is_refused(self):
        other = self.root / 'fabric/BACKUP_INFO.json/nested'
        other.mkdir(parents=True)
        (other / 'level.dat').write_bytes(b'retained synthetic world')
        with self.assertRaisesRegex(ValueError, 'conflicts with backup metadata'):
            community.backup(self.root, {'level-name': 'BACKUP_INFO.json/nested'})
        self.assertEqual((other / 'level.dat').read_bytes(), b'retained synthetic world')
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_unlisted_entry_is_refused(self):
        path = self.backup()
        with zipfile.ZipFile(path, 'a') as archive:
            archive.writestr('synthetic-extra', b'data')
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('do not match', result.stderr)

    def test_recorded_empty_directory_with_a_child_is_refused(self):
        path = self.backup()
        with zipfile.ZipFile(path) as archive:
            info = json.loads(archive.read('BACKUP_INFO.json'))
        child = 'world/data/empty/synthetic'
        info['digest_manifest']['files'].append({'path': child, 'sha256': hashlib.sha256(b'data').hexdigest()})
        self.rewrite(path, replacement={'BACKUP_INFO.json': json.dumps(info).encode('utf-8')})
        with zipfile.ZipFile(path, 'a') as archive:
            archive.writestr(child, b'data')
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('empty directory', result.stderr)

    def test_missing_recorded_file_is_refused(self):
        path = self.backup()
        self.rewrite(path, omit='world/level.dat')
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('do not match', result.stderr)

    def test_duplicate_zip_entry_is_refused(self):
        path = self.backup()
        with warnings.catch_warnings(), zipfile.ZipFile(path, 'a') as archive:
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr('world/level.dat', b'synthetic world data')
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('duplicate', result.stderr)

    def test_duplicate_manifest_key_is_refused(self):
        path = self.backup()
        with zipfile.ZipFile(path) as archive:
            old = archive.read('BACKUP_INFO.json')
        self.assertTrue(old.startswith(b'{'))
        self.rewrite(path, replacement={'BACKUP_INFO.json': b'{"format":1,' + old[1:]})
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('duplicate JSON', result.stderr)

    def test_invalid_or_duplicate_digest_record_is_refused(self):
        for change in ('invalid', 'duplicate'):
            with self.subTest(change=change):
                path = self.backup()
                with zipfile.ZipFile(path) as archive:
                    info = json.loads(archive.read('BACKUP_INFO.json'))
                files = info['digest_manifest']['files']
                if change == 'invalid':
                    files[0]['sha256'] = 'not-a-digest'
                else:
                    files.append(dict(files[0]))
                self.rewrite(path, replacement={'BACKUP_INFO.json': json.dumps(info).encode('utf-8')})
                result = self.check(path)
                self.assertEqual(result.returncode, 1)
                self.assertIn('invalid file record' if change == 'invalid' else 'duplicate', result.stderr)

    def test_nonregular_zip_entry_is_refused(self):
        path = self.backup()
        target = path.with_name('synthetic-rewrite.zip')
        with zipfile.ZipFile(path) as original, zipfile.ZipFile(target, 'w') as output:
            for entry in original.infolist():
                data = original.read(entry)
                if entry.filename == 'world/level.dat':
                    entry.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(entry, data)
        os.replace(target, path)
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('not a regular file', result.stderr)

    def test_oversized_info_is_not_published(self):
        with patch.object(community, 'BACKUP_INFO_MAX_BYTES', 1):
            with self.assertRaisesRegex(ValueError, 'manifest exceeds'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic world data')
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_archive_change_during_check_is_refused(self):
        path = self.backup()
        script = Path(__file__).resolve().parents[1] / 'server/verify_backup.py'
        program = r'''
import runpy,sys,zipfile
script,path=sys.argv[1:]
original=zipfile.ZipFile.open
changed=False
def hooked(self,name,*args,**kwargs):
    global changed
    if getattr(name,'filename',name)=='world/level.dat' and not changed:
        changed=True
        with open(path,'ab') as output:output.write(b'synthetic late change')
    return original(self,name,*args,**kwargs)
zipfile.ZipFile.open=hooked
sys.argv=[script,path]
runpy.run_path(script,run_name='__main__')
'''
        result = subprocess.run([sys.executable, '-I', '-S', '-c', program, str(script), str(path)],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 1)
        self.assertIn('changed', result.stderr)

    def test_changed_backup_info_is_refused(self):
        path = self.backup()
        with zipfile.ZipFile(path) as archive:
            info = json.loads(archive.read('BACKUP_INFO.json'))
        info['world'] = 'different'
        self.rewrite(path, replacement={'BACKUP_INFO.json': json.dumps(info).encode('utf-8')})
        result = self.check(path)
        self.assertEqual(result.returncode, 1)
        self.assertIn('outside the recorded world', result.stderr)

    def test_linked_and_special_archive_paths_are_refused(self):
        path = self.backup()
        link = self.root / 'synthetic-symlink.zip'
        link.symlink_to(path)
        self.assertEqual(self.check(link).returncode, 1)
        hard = self.root / 'synthetic-hardlink.zip'
        os.link(path, hard)
        self.assertEqual(self.check(hard).returncode, 1)
        hard.unlink()
        fifo = self.root / 'synthetic-fifo.zip'
        os.mkfifo(fifo)
        self.assertEqual(self.check(fifo).returncode, 1)
        self.assertEqual(self.check(path).returncode, 0)

    def test_module_refuses_import_into_the_lock_holder(self):
        code = "import sys;sys.path.insert(0,sys.argv[1]);import verify_backup"
        server = Path(__file__).resolve().parents[1] / 'server'
        result = subprocess.run([sys.executable, '-I', '-S', '-c', code, str(server)],
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('separate process', result.stderr)


if __name__ == '__main__':
    unittest.main()
