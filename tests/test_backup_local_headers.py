"""Backups must reject damaged per-entry ZIP headers before publication."""
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native and launcher locks')
class BackupLocalHeaderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        (self.world / 'data/empty').mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(b'synthetic world data')

    def reject_local_change(self, entry_name, field_offset, encoding, mutate):
        actual_sync = community.sync_file

        def change_after_sync(stream):
            actual_sync(stream)
            with zipfile.ZipFile(stream, 'r') as archive:
                entry = archive.getinfo(entry_name)
                self.assertIsNone(archive.testzip())
            offset = entry.header_offset + field_offset
            length = struct.calcsize(encoding)
            original = struct.unpack(encoding, os.pread(stream.fileno(), length, offset))[0]
            os.pwrite(stream.fileno(), struct.pack(encoding, mutate(original)), offset)

        with patch.object(community, 'sync_file', side_effect=change_after_sync):
            with self.assertRaisesRegex(ValueError, 'archive|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual((self.world / 'level.dat').read_bytes(), b'synthetic world data')
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_file_local_crc_mismatch_is_rejected(self):
        self.reject_local_change('world/level.dat', 14, '<I', lambda n: n ^ 1)

    def test_file_local_uncompressed_size_mismatch_is_rejected(self):
        self.reject_local_change('world/level.dat', 22, '<I', lambda n: n + 1)

    def test_file_local_compressed_size_mismatch_is_rejected(self):
        self.reject_local_change('world/level.dat', 18, '<I', lambda n: n + 1)

    def test_file_local_method_mismatch_is_rejected(self):
        self.reject_local_change('world/level.dat', 8, '<H', lambda n: n ^ 8)

    def test_empty_directory_local_header_mismatch_is_rejected(self):
        self.reject_local_change('world/data/empty/', 14, '<I', lambda n: n ^ 1)

    def reject_central_change(self, field_offset, mutate):
        actual_sync = community.sync_file

        def change_after_sync(stream):
            actual_sync(stream)
            with zipfile.ZipFile(stream, 'r') as archive:
                offset = archive.start_dir
                for entry in archive.infolist():
                    fixed = os.pread(stream.fileno(), 46, offset)
                    self.assertEqual(fixed[:4], b'PK\x01\x02')
                    name_len, extra_len, comment_len = struct.unpack_from('<HHH', fixed, 28)
                    if entry.filename == 'world/level.dat':
                        original = struct.unpack_from('<H', fixed, field_offset)[0]
                        os.pwrite(stream.fileno(), struct.pack('<H', mutate(original)), offset + field_offset)
                        return
                    offset += 46 + name_len + extra_len + comment_len
            self.fail('synthetic central entry not found')

        with patch.object(community, 'sync_file', side_effect=change_after_sync):
            with self.assertRaisesRegex(ValueError, 'archive|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_central_timestamp_disagreeing_with_writer_is_rejected(self):
        self.reject_central_change(12, lambda n: n ^ 1)

    def test_central_flags_disagreeing_with_writer_are_rejected(self):
        self.reject_central_change(8, lambda n: n ^ 8)

    def test_unicode_and_zip64_headers_round_trip(self):
        data = b'synthetic data' * 200
        (self.world / 'data/\u4e16\u754c.dat').write_bytes(data)
        with patch.object(zipfile, 'ZIP64_LIMIT', 1024):
            name = community.backup(self.root, {'level-name': 'world'})
        path = self.root / 'backups' / name
        with zipfile.ZipFile(path) as archive:
            entry = archive.getinfo('world/data/\u4e16\u754c.dat')
            self.assertEqual(archive.read(entry), data)
            self.assertIsNone(archive.testzip())
        with path.open('rb') as stream:
            header = os.pread(stream.fileno(), 30, entry.header_offset)
        self.assertEqual(struct.unpack_from('<II', header, 18), (0xffffffff, 0xffffffff))


if __name__ == '__main__':
    unittest.main()
