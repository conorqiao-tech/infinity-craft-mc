"""Staged backup content must match bytes read from the synthetic source."""
import hashlib
import io
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
import community

FIRST = bytes.fromhex('fc89b8960a31bff0')
SECOND = bytes.fromhex('dbdbff47d3678eec')


def raw_deflate(data):
    compressor = zlib.compressobj(1, zlib.DEFLATED, -15)
    return compressor.compress(data) + compressor.flush()


@unittest.skipUnless(os.name == 'posix', 'requires POSIX native and launcher locks')
class BackupContentDigestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / 'fabric/world'
        (self.world / 'data/empty').mkdir(parents=True)
        (self.world / 'session.lock').write_bytes(b'synthetic native lock')
        (self.world / 'level.dat').write_bytes(FIRST)

    def reject_equal_crc_staged_change(self, archive_name):
        self.assertNotEqual(FIRST, SECOND)
        self.assertEqual(zlib.crc32(FIRST), zlib.crc32(SECOND))
        self.assertNotEqual(hashlib.sha256(FIRST).digest(), hashlib.sha256(SECOND).digest())
        self.assertEqual(len(raw_deflate(FIRST)), len(raw_deflate(SECOND)))
        actual_sync = community.sync_file

        def replace_payload(stream):
            actual_sync(stream)
            with zipfile.ZipFile(stream, 'r') as archive:
                info = archive.getinfo(archive_name)
            header = os.pread(stream.fileno(), 30, info.header_offset)
            name_length, extra_length = struct.unpack_from('<HH', header, 26)
            offset = info.header_offset + 30 + name_length + extra_length
            self.assertEqual(os.pread(stream.fileno(), info.compress_size, offset), raw_deflate(FIRST))
            os.pwrite(stream.fileno(), raw_deflate(SECOND), offset)
            with zipfile.ZipFile(stream, 'r') as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read(archive_name), SECOND)

        with patch.object(community, 'sync_file', side_effect=replace_payload):
            with self.assertRaisesRegex(ValueError, 'archive|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual((self.world / 'level.dat').read_bytes(), FIRST)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_equal_crc_world_content_change_is_not_published(self):
        self.reject_equal_crc_staged_change('world/level.dat')

    def test_equal_crc_server_content_change_is_not_published(self):
        (self.root / 'fabric/whitelist.json').write_bytes(FIRST)
        self.reject_equal_crc_staged_change('whitelist.json')
        self.assertEqual((self.root / 'fabric/whitelist.json').read_bytes(), FIRST)

    def test_healthy_empty_and_multiblock_files_round_trip(self):
        (self.world / 'data/empty.bin').write_bytes(b'')
        payload = b'synthetic multiblock data' * 100000
        (self.world / 'data/big.bin').write_bytes(payload)
        name = community.backup(self.root, {'level-name': 'world'})
        with zipfile.ZipFile(self.root / 'backups' / name) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read('world/level.dat'), FIRST)
            self.assertEqual(archive.read('world/data/empty.bin'), b'')
            self.assertEqual(archive.read('world/data/big.bin'), payload)
            self.assertTrue(archive.getinfo('world/data/empty/').is_dir())

    def assert_failed_reader(self, suffix):
        program = ("import os;os.write(1,bytes.fromhex('" + FIRST.hex() + "')"
                   "+b'INFINITY_BACKUP_FILE_OK_V1'+" + repr(suffix) + ")")
        with patch.object(community, '_BACKUP_FILE_READER', program):
            with self.assertRaisesRegex(ValueError, 'changed|verification'):
                community.backup(self.root, {'level-name': 'world'})
        self.assertEqual((self.world / 'level.dat').read_bytes(), FIRST)
        self.assertTrue((self.root / 'launcher.lock').exists())
        self.assertTrue(list((self.root / 'backups').glob('*.partial')))
        self.assertFalse(list((self.root / 'backups').glob('*.zip')))

    def test_missing_digest_trailer_is_not_success(self):
        self.assert_failed_reader(b'')

    def test_malformed_digest_trailer_is_not_success(self):
        self.assert_failed_reader(b'g' * 64)

    def test_wrong_but_well_formed_digest_is_not_published(self):
        self.assert_failed_reader(b'0' * 64)

    def test_generated_info_bytes_are_compared_without_a_parent_file_read(self):
        path = self.root / 'synthetic-info.zip'
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            archive.writestr('BACKUP_INFO.json', FIRST)
            expected = community.backup_archive_index(archive, {'BACKUP_INFO.json': ('literal', FIRST.hex())})
        with path.open('r+b') as stream:
            with zipfile.ZipFile(stream) as archive:
                item = archive.getinfo('BACKUP_INFO.json')
            fixed = os.pread(stream.fileno(), 30, item.header_offset)
            name_length, extra_length = struct.unpack_from('<HH', fixed, 26)
            offset = item.header_offset + 30 + name_length + extra_length
            self.assertEqual(os.pread(stream.fileno(), item.compress_size, offset), raw_deflate(FIRST))
            os.pwrite(stream.fileno(), raw_deflate(SECOND), offset)
            with zipfile.ZipFile(stream) as archive:
                self.assertIsNone(archive.testzip())
            state = community._BackupReaderState()
            with self.assertRaisesRegex(ValueError, 'verification'):
                community.verify_staged_backup_archive(stream, expected, state)
            self.assertEqual(state.unconfirmed, 0)

    def test_incomplete_content_record_is_refused(self):
        with zipfile.ZipFile(io.BytesIO(), 'w') as archive:
            archive.writestr('synthetic', b'data')
            with self.assertRaisesRegex(ValueError, 'content record'):
                community.backup_archive_index(archive, {})


if __name__ == '__main__':
    unittest.main()
