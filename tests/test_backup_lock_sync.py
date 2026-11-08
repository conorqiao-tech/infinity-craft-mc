"""Manual backup publishes and flushes its lease before reading a world."""
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community


@unittest.skipUnless(os.name == "posix", "POSIX manual-backup lease publication")
class BackupLockSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        (self.world / "level.dat").write_bytes(b"synthetic world")

    def tearDown(self):
        self.temp.cleanup()

    def test_lease_file_flush_failure_prevents_world_read(self):
        with patch.object(community, "flush_descriptor", side_effect=OSError("synthetic lease flush failure")), \
             patch.object(community, "world_folder", side_effect=AssertionError("world read before lease flush")) as world:
            with self.assertRaisesRegex(OSError, "synthetic lease flush failure"):
                community.backup(self.root, {"level-name": "world"})
        world.assert_not_called()
        self.assertFalse((self.root / "launcher.lock").exists())
        self.assertEqual(list(self.root.glob(".launcher-lock-*.tmp")), [])
        self.assertEqual((self.world / "level.dat").read_bytes(), b"synthetic world")

    def test_lease_directory_sync_failure_keeps_lock_before_world_read(self):
        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=OSError("synthetic lease directory failure")), \
             patch.object(community, "world_folder", side_effect=AssertionError("world read before directory sync")) as world:
            with self.assertRaisesRegex(OSError, "synthetic lease directory failure"):
                community.backup(self.root, {"level-name": "world"})
        world.assert_not_called()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))
        self.assertFalse((self.root / "backups").exists())
        self.assertEqual((self.world / "level.dat").read_bytes(), b"synthetic world")

    def test_lease_directory_sync_precedes_world_lookup(self):
        events = []
        real_sync = community.sync_directory

        def sync(path):
            self.assertEqual(path, self.root)
            real_sync(path)
            events.append("directory sync")

        def world(*_):
            events.append("world lookup")
            raise InterruptedError("synthetic stop after lookup")

        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=sync), \
             patch.object(community, "world_folder", side_effect=world):
            with self.assertRaisesRegex(InterruptedError, "synthetic stop"):
                community.backup(self.root, {"level-name": "world"})
        self.assertEqual(events, ["directory sync", "world lookup"])
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_detected_lease_swap_during_directory_sync_stops_backup(self):
        lock, original = self.root / "launcher.lock", self.root / "original-lock"

        def swap(_):
            os.replace(lock, original)
            lock.write_bytes(b"synthetic replacement")

        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=swap), \
             patch.object(community, "world_folder", side_effect=AssertionError("world read after lease swap")) as world:
            with self.assertRaisesRegex(ValueError, "launcher lock changed"):
                community.backup(self.root, {"level-name": "world"})
        world.assert_not_called()
        self.assertEqual(original.read_text(), str(os.getpid()))
        self.assertEqual(lock.read_bytes(), b"synthetic replacement")
        self.assertFalse((self.root / "backups").exists())


if __name__ == "__main__":
    unittest.main()
