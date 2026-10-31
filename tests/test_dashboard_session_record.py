"""Private panel session records, tested only with disposable local fixtures."""
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
import dashboard


class DashboardSessionRecordTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.record = self.root / ".dashboard.json"

    def tearDown(self):
        self.temp.cleanup()

    def write(self):
        dashboard.write_panel_record(self.record, "http://127.0.0.1:12345", "synthetic-token")

    def assert_no_temporary_record(self):
        self.assertEqual(list(self.root.glob(".dashboard-*.tmp")), [])

    @unittest.skipUnless(os.name == "posix", "POSIX mode bits do not establish Windows ACLs")
    def test_existing_permissive_record_is_replaced_by_private_file(self):
        self.record.write_text("old record")
        self.record.chmod(0o644)
        self.write()
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
        self.assertEqual(json.loads(self.record.read_text()), {"url": "http://127.0.0.1:12345", "token": "synthetic-token"})
        self.assert_no_temporary_record()

    def test_existing_symlink_is_replaced_without_touching_its_target(self):
        sentinel = self.root / "unrelated-file"
        sentinel.write_bytes(b"keep this content")
        try:
            self.record.symlink_to(sentinel)
        except (OSError, NotImplementedError) as error:
            self.skipTest("Symbolic links unavailable: " + str(error))
        self.write()
        self.assertEqual(sentinel.read_bytes(), b"keep this content")
        self.assertFalse(self.record.is_symlink())
        self.assertEqual(json.loads(self.record.read_text())["token"], "synthetic-token")
        self.assert_no_temporary_record()

    def test_partial_write_failure_preserves_old_record_and_removes_temporary(self):
        self.record.write_text("old record")
        def partial(value, stream):
            stream.write('{"url":')
            raise OSError("simulated write failure")
        with patch.object(dashboard.json, "dump", side_effect=partial):
            with self.assertRaisesRegex(OSError, "simulated write failure"):
                self.write()
        self.assertEqual(self.record.read_text(), "old record")
        self.assert_no_temporary_record()

    def test_replace_failure_preserves_old_record_and_removes_temporary(self):
        self.record.write_text("old record")
        with patch.object(dashboard.os, "replace", side_effect=OSError("simulated replace failure")):
            with self.assertRaisesRegex(OSError, "simulated replace failure"):
                self.write()
        self.assertEqual(self.record.read_text(), "old record")
        self.assert_no_temporary_record()

    def test_serve_failure_keeps_prior_record_and_releases_panel_lock(self):
        self.record.write_text("old record")
        (self.root / "dashboard.html").write_text("synthetic panel")
        launcher = SimpleNamespace(ROOT=self.root, OUTPUT=None)
        panel = Mock()
        args = SimpleNamespace(no_browser=True)
        with patch.object(dashboard, "Panel", return_value=panel), \
             patch.object(dashboard, "write_panel_record", side_effect=OSError("cannot write record")):
            with self.assertRaisesRegex(OSError, "cannot write record"):
                dashboard.serve(launcher, args)
        self.assertEqual(self.record.read_text(), "old record")
        panel.close.assert_called_once()
        lease = dashboard.acquire_panel_lock(self.root)
        self.assertIsNotNone(lease)
        lease.close()

    def test_repeated_launch_reopens_the_same_panel_and_releases_lock(self):
        (self.root / "dashboard.html").write_text("synthetic panel")
        launcher = SimpleNamespace(ROOT=self.root, OUTPUT=None)
        panel = Mock()
        panel.snapshot.return_value = {"state": "stopped"}
        args = SimpleNamespace(no_browser=True)
        listening = threading.Event()
        servers = []
        original_server = dashboard.PanelServer
        original_write = dashboard.write_panel_record
        def make_server(*args, **kwargs):
            server = original_server(*args, **kwargs)
            servers.append(server)
            return server
        def write_record(*args):
            original_write(*args)
            listening.set()
        with patch.object(dashboard, "Panel", return_value=panel), \
             patch.object(dashboard, "PanelServer", side_effect=make_server), \
             patch.object(dashboard, "write_panel_record", side_effect=write_record), \
             patch("builtins.print") as printed:
            worker = threading.Thread(target=dashboard.serve, args=(launcher, args), daemon=True)
            worker.start()
            try:
                self.assertTrue(listening.wait(5), "first panel did not publish its record")
                first = self.record.read_bytes()
                dashboard.serve(launcher, args)
                self.assertEqual(self.record.read_bytes(), first)
                self.assertEqual(len(servers), 1)
                self.assertTrue(any("already open" in str(call) for call in printed.call_args_list))
            finally:
                if servers:
                    servers[0].shutdown()
                worker.join(5)
            self.assertFalse(worker.is_alive())
        self.assertFalse(self.record.exists())
        lease = dashboard.acquire_panel_lock(self.root)
        self.assertIsNotNone(lease)
        lease.close()


if __name__ == "__main__":
    unittest.main()
