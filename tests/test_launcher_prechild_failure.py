"""Preserve launcher evidence after interrupted synthetic pre-child world work."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import zipfile

SERVER = Path(__file__).resolve().parents[1] / "server"
sys.path.insert(0, str(SERVER))
import community
import server as launcher
import world_refresh as refresh


@unittest.skipUnless(os.name == "posix", "requires native POSIX lock semantics")
class PreChildWorldFailureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        (self.world / "session.lock").write_bytes(b"synthetic native lock")
        (self.world / "level.dat").write_bytes(b"synthetic level")
        (self.root / "fabric/server.properties").write_text("level-name=world\n")
        (self.root / "fabric/eula.txt").write_text("eula=true\n")
        self.args = launcher.parse_args(["--bind", "127.0.0.1"])

    def journal(self):
        paths = ["data/synthetic-a.bin", "data/synthetic-b.bin"]
        saved = self.root / "backups/Terrain-Refresh-synthetic/terrain"
        for relative in paths:
            item = saved / relative
            item.parent.mkdir(parents=True, exist_ok=True)
            item.write_bytes(("old " + relative).encode())
        journal = self.root / refresh.JOURNAL
        journal.write_text(json.dumps({"format": 1, "state": "moving", "world": "world",
            "rollback": "Terrain-Refresh-synthetic", "moved_paths": paths, "kept_files": {}}))
        return paths, saved, journal

    def assert_recovery_blocked_by_retained_lock(self):
        lock = self.root / "launcher.lock"
        self.assertTrue(lock.is_file())
        self.assertEqual(lock.read_text(), str(os.getpid()))
        code = ("import pathlib,sys; sys.path.insert(0,sys.argv[1]); import server; "
                "root=pathlib.Path(sys.argv[2]);\n"
                "try: server.check_launcher_lock(server.parse_args([]),root)\n"
                "except RuntimeError: sys.exit(0)\n"
                "sys.exit(42)")
        result = subprocess.run([sys.executable, "-c", code, str(SERVER), str(self.root)],
                                timeout=15, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def recover_then_fail(self, error):
        paths, saved, journal = self.journal()
        before = journal.read_bytes()
        real_move = refresh.move
        def interrupted(source, target):
            real_move(source, target)
            raise error
        with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
             patch.object(refresh, "move", side_effect=interrupted), \
             patch.object(community, "backup", wraps=community.backup) as backup, \
             patch.object(launcher, "Service") as service:
            with self.assertRaises((OSError, RuntimeError)):
                launcher.run_server(self.args, root=self.root, console=False)
        backup.assert_not_called()
        service.assert_not_called()
        self.assertEqual(journal.read_bytes(), before)
        self.assertEqual((self.world / paths[1]).read_bytes(), ("old " + paths[1]).encode())
        self.assertEqual((saved / paths[0]).read_bytes(), ("old " + paths[0]).encode())
        self.assert_recovery_blocked_by_retained_lock()

    def test_failed_recovery_after_one_move_retains_launcher_evidence(self):
        self.recover_then_fail(OSError("synthetic recovery move failure"))

    def test_interrupted_recovery_after_one_move_retains_launcher_evidence(self):
        self.recover_then_fail(InterruptedError("synthetic cancellation"))

    def test_keyboard_interrupt_during_recovery_retains_launcher_evidence(self):
        self.recover_then_fail(KeyboardInterrupt())

    def test_failed_startup_backup_after_publication_retains_launcher_evidence(self):
        backups = self.root / "backups"
        backups.mkdir()
        real_sync = community.sync_directory
        def sync(path):
            if Path(path) == backups and list(backups.glob("*.zip")):
                raise OSError("synthetic archive directory sync failure")
            return real_sync(path)
        with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
             patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=sync), \
             patch.object(launcher, "Service") as service:
            with self.assertRaisesRegex(OSError, "synthetic archive directory sync failure"):
                launcher.run_server(self.args, root=self.root, console=False)
        service.assert_not_called()
        archives = list(backups.glob("*.zip"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("world/level.dat"), b"synthetic level")
        self.assert_recovery_blocked_by_retained_lock()

    def test_interrupted_startup_backup_after_publication_retains_evidence(self):
        backups = self.root / "backups"
        backups.mkdir()
        real_sync = community.sync_directory
        def sync(path):
            if Path(path) == backups and list(backups.glob("*.zip")):
                raise InterruptedError("synthetic backup interruption")
            return real_sync(path)
        with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
             patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=sync), \
             patch.object(launcher, "Service") as service:
            with self.assertRaisesRegex(RuntimeError, "Interrupted during startup backup"):
                launcher.run_server(self.args, root=self.root, console=False)
        service.assert_not_called()
        self.assertEqual(len(list(backups.glob("*.zip"))), 1)
        self.assert_recovery_blocked_by_retained_lock()

    def test_preflight_failure_before_world_work_releases_launcher_marker(self):
        with patch.object(launcher, "check_ports", side_effect=RuntimeError("synthetic port conflict")), \
             patch.object(refresh, "recover") as recover:
            with self.assertRaisesRegex(RuntimeError, "synthetic port conflict"):
                launcher.run_server(self.args, root=self.root, console=False)
        recover.assert_not_called()
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_clean_stop_after_completed_startup_backup_releases_marker(self):
        stop = threading.Event()
        stop.set()
        with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
             patch.object(launcher, "Service") as service:
            launcher.run_server(self.args, stop_requested=stop, root=self.root, console=False)
        service.assert_not_called()
        self.assertFalse((self.root / "launcher.lock").exists())
        archives = list((self.root / "backups").glob("*.zip"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("world/level.dat"), b"synthetic level")


if __name__ == "__main__":
    unittest.main()
