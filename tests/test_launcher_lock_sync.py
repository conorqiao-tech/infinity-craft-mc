"""A launcher lease must be flushed before the launcher changes a world."""
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import server as launcher


class LauncherLockSyncTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.args = launcher.parse_args(["--bind", "127.0.0.1"])

    def tearDown(self):
        self.temp.cleanup()

    def test_file_flush_failure_keeps_lock_and_does_not_begin_world_work(self):
        with patch.object(launcher.community, "flush_descriptor", side_effect=OSError("synthetic file flush failure")), \
             patch.object(launcher, "check_ports", side_effect=AssertionError("work started before lock flush")) as ports, \
             patch.object(launcher.world_refresh, "recover") as recover, \
             patch.object(launcher.community, "backup") as backup, \
             patch.object(launcher, "Service") as service:
            with self.assertRaisesRegex(OSError, "synthetic file flush failure"):
                launcher.run_server(self.args, root=self.root, console=False)
        ports.assert_not_called()
        recover.assert_not_called()
        backup.assert_not_called()
        service.assert_not_called()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    def test_pid_write_failure_keeps_the_incomplete_lock_for_review(self):
        with patch.object(launcher.os, "write", side_effect=OSError("synthetic PID write failure")), \
             patch.object(launcher, "check_ports") as ports, \
             patch.object(launcher.world_refresh, "recover") as recover:
            with self.assertRaisesRegex(OSError, "synthetic PID write failure"):
                launcher.run_server(self.args, root=self.root, console=False)
        ports.assert_not_called()
        recover.assert_not_called()
        self.assertEqual((self.root / "launcher.lock").read_bytes(), b"")

    @unittest.skipUnless(os.name == "posix", "POSIX directory synchronization")
    def test_directory_sync_failure_keeps_lock_and_does_not_begin_world_work(self):
        with patch.object(launcher.community, "DIRECTORY_SYNC", True), \
             patch.object(launcher.community, "flush_descriptor"), \
             patch.object(launcher.community, "sync_directory", side_effect=OSError("synthetic directory sync failure")), \
             patch.object(launcher, "check_ports", side_effect=AssertionError("work started before lock flush")) as ports, \
             patch.object(launcher.world_refresh, "recover") as recover, \
             patch.object(launcher, "Service") as service:
            with self.assertRaisesRegex(OSError, "synthetic directory sync failure"):
                launcher.run_server(self.args, root=self.root, console=False)
        ports.assert_not_called()
        recover.assert_not_called()
        service.assert_not_called()
        self.assertEqual((self.root / "launcher.lock").read_text(), str(os.getpid()))

    @unittest.skipUnless(os.name == "posix", "POSIX directory synchronization")
    def test_file_and_directory_flush_precede_world_recovery(self):
        events = []
        actual_flush = launcher.community.flush_descriptor
        actual_sync_dir = launcher.community.sync_directory

        def flush(fd):
            mode = os.fstat(fd).st_mode
            events.append("directory flush" if stat.S_ISDIR(mode) else "lock file flush")
            actual_flush(fd)

        def sync_directory(path):
            self.assertEqual(path, self.root)
            actual_sync_dir(path)
            events.append("directory flush completed")

        def check_ports(_):
            events.append("port check")

        def recover(*_):
            events.append("world recovery")
            raise InterruptedError()

        with patch.object(launcher.community, "DIRECTORY_SYNC", True), \
             patch.object(launcher.community, "flush_descriptor", side_effect=flush), \
             patch.object(launcher.community, "sync_directory", side_effect=sync_directory), \
             patch.object(launcher, "check_ports", side_effect=check_ports), \
             patch.object(launcher, "require_plain_properties"), \
             patch.object(launcher, "properties", return_value={}), \
             patch.object(launcher.community, "world_folder"), \
             patch.object(launcher.world_refresh, "recover", side_effect=recover), \
             patch.object(launcher, "emit"):
            with self.assertRaisesRegex(RuntimeError, "Interrupted during interrupted terrain recovery"):
                launcher.run_server(self.args, root=self.root, console=False)
        self.assertEqual(events, ["lock file flush", "directory flush", "directory flush completed",
                                  "port check", "world recovery"])
        self.assertTrue((self.root / "launcher.lock").exists())

    @unittest.skipUnless(os.name == "posix", "POSIX directory synchronization")
    def test_observed_lock_swap_during_sync_stops_before_world_work(self):
        lock = self.root / "launcher.lock"
        original = self.root / "original-lock"

        def swap(_):
            os.replace(lock, original)
            lock.write_bytes(b"synthetic replacement")

        with patch.object(launcher.community, "DIRECTORY_SYNC", True), \
             patch.object(launcher.community, "flush_descriptor"), \
             patch.object(launcher.community, "sync_directory", side_effect=swap), \
             patch.object(launcher, "check_ports", side_effect=AssertionError("work started after lock substitution")) as ports, \
             patch.object(launcher.world_refresh, "recover") as recover, \
             patch.object(launcher, "Service") as service:
            with self.assertRaisesRegex(RuntimeError, "launcher lock changed"):
                launcher.run_server(self.args, root=self.root, console=False)
        ports.assert_not_called()
        recover.assert_not_called()
        service.assert_not_called()
        self.assertEqual(original.read_text(), str(os.getpid()))
        self.assertEqual(lock.read_bytes(), b"synthetic replacement")

    @unittest.skipUnless(os.name == "posix", "POSIX hard-link test")
    def test_observed_extra_hardlink_stops_before_world_work(self):
        lock = self.root / "launcher.lock"
        alias = self.root / "synthetic-alias"
        with patch.object(launcher.community, "DIRECTORY_SYNC", True), \
             patch.object(launcher.community, "flush_descriptor"), \
             patch.object(launcher.community, "sync_directory", side_effect=lambda _: os.link(lock, alias)), \
             patch.object(launcher, "check_ports", side_effect=AssertionError("work started with linked lock")) as ports:
            with self.assertRaisesRegex(RuntimeError, "launcher lock changed"):
                launcher.run_server(self.args, root=self.root, console=False)
        ports.assert_not_called()
        self.assertEqual(lock.read_text(), str(os.getpid()))
        self.assertEqual(alias.stat().st_ino, lock.stat().st_ino)

    def test_without_directory_sync_file_flush_still_precedes_recovery(self):
        events = []
        with patch.object(launcher.community, "DIRECTORY_SYNC", False), \
             patch.object(launcher.community, "flush_descriptor", side_effect=lambda _: events.append("file flush")), \
             patch.object(launcher.community, "sync_directory") as directory, \
             patch.object(launcher, "check_ports", side_effect=lambda _: events.append("ports")), \
             patch.object(launcher, "require_plain_properties"), \
             patch.object(launcher, "properties", return_value={}), \
             patch.object(launcher.community, "world_folder"), \
             patch.object(launcher.world_refresh, "recover", side_effect=InterruptedError), \
             patch.object(launcher, "emit"):
            with self.assertRaisesRegex(RuntimeError, "Interrupted during interrupted terrain recovery"):
                launcher.run_server(self.args, root=self.root, console=False)
        directory.assert_not_called()
        self.assertEqual(events, ["file flush", "ports"])
        self.assertTrue((self.root / "launcher.lock").exists())


if __name__ == "__main__":
    unittest.main()
