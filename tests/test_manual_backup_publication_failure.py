"""Retain synthetic backup evidence if archive publication becomes uncertain."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community
import server as launcher


@unittest.skipUnless(os.name == "posix", "requires POSIX native and launcher locks")
class ManualBackupPublicationFailureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        (self.world / "session.lock").write_bytes(b"synthetic native lock")
        (self.world / "level.dat").write_bytes(b"synthetic level")
        (self.root / "fabric/server.properties").write_text("level-name=world\n")
        self.backups = self.root / "backups"
        self.backups.mkdir()

    def assert_retained_marker(self):
        self.assertTrue((self.root / "launcher.lock").is_file())
        with self.assertRaisesRegex(RuntimeError, "launcher.lock"):
            launcher.check_launcher_lock(launcher.parse_args([]), self.root)

    def test_directory_sync_failure_after_publication_keeps_marker(self):
        actual_sync = community.sync_directory
        def sync(path):
            if Path(path) == self.backups and list(self.backups.glob("*.zip")):
                raise OSError("synthetic publication directory sync failure")
            return actual_sync(path)
        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=sync):
            with self.assertRaisesRegex(OSError, "synthetic publication directory sync failure"):
                community.backup(self.root, {"level-name": "world"})
        archives = list(self.backups.glob("*.zip"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("world/level.dat"), b"synthetic level")
        self.assert_retained_marker()

    def test_rename_failure_after_move_keeps_published_archive_and_marker(self):
        real_replace = community.os.replace
        def move_then_fail(source, destination):
            self.assertEqual(Path(source).suffix, ".partial")
            real_replace(source, destination)
            raise OSError("synthetic uncertain rename failure")
        with patch.object(community.os, "replace", side_effect=move_then_fail):
            with self.assertRaisesRegex(OSError, "synthetic uncertain rename failure"):
                community.backup(self.root, {"level-name": "world"})
        archives = list(self.backups.glob("*.zip"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertIsNone(archive.testzip())
        self.assert_retained_marker()

    def test_rename_failure_before_move_keeps_partial_and_marker(self):
        with patch.object(community.os, "replace", side_effect=OSError("synthetic rename refusal")):
            with self.assertRaisesRegex(OSError, "synthetic rename refusal"):
                community.backup(self.root, {"level-name": "world"})
        self.assertEqual(list(self.backups.glob("*.zip")), [])
        partials = list(self.backups.glob("*.partial"))
        self.assertEqual(len(partials), 1)
        with zipfile.ZipFile(partials[0]) as archive:
            self.assertIsNone(archive.testzip())
        self.assert_retained_marker()

    def test_partial_cleanup_failure_after_publication_keeps_marker(self):
        real_unlink = Path.unlink
        def unlink(path, *args, **kwargs):
            if path.suffix == ".partial":
                raise PermissionError("synthetic partial cleanup failure")
            return real_unlink(path, *args, **kwargs)
        with patch.object(Path, "unlink", unlink):
            with self.assertRaisesRegex(PermissionError, "synthetic partial cleanup failure"):
                community.backup(self.root, {"level-name": "world"})
        self.assertEqual(len(list(self.backups.glob("*.zip"))), 1)
        self.assert_retained_marker()

    def test_native_lock_close_failure_after_publication_keeps_marker(self):
        native = (self.world / "session.lock").stat()
        real_close = community.os.close
        observed = []
        def close(fd):
            info = os.fstat(fd)
            if (info.st_dev, info.st_ino) == (native.st_dev, native.st_ino) and not observed:
                observed.append(True)
                real_close(fd)
                raise OSError("synthetic native lock close failure")
            return real_close(fd)
        with patch.object(community.os, "close", side_effect=close):
            with self.assertRaisesRegex(OSError, "synthetic native lock close failure"):
                community.backup(self.root, {"level-name": "world"})
        self.assertEqual(observed, [True])
        self.assertEqual(len(list(self.backups.glob("*.zip"))), 1)
        self.assert_retained_marker()

    def test_completed_manual_backup_releases_marker(self):
        name = community.backup(self.root, {"level-name": "world"})
        self.assertTrue((self.backups / name).is_file())
        self.assertFalse((self.root / "launcher.lock").exists())


if __name__ == "__main__":
    unittest.main()
