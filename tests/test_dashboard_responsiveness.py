import json
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import dashboard
import server as launcher


PUBLIC = [{"edition": "Java", "state": "running", "ready": True,
           "address": {"host": "java.example.test", "port": 43297}, "verified": False},
          {"edition": "Bedrock", "state": "running", "ready": True,
           "address": {"host": "bedrock.example.test", "port": 60912}, "verified": False}]


class DashboardResponsivenessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.panel = dashboard.Panel(launcher, launcher.parse_args(["--bind", "127.0.0.1"]), self.root)
        self.panel.state = "running"
        self.panel.worker = Mock()
        self.panel.worker.is_alive.return_value = True
        self.panel.send_command = Mock()
        self.release = threading.Event()

    def tearDown(self):
        self.release.set()
        if self.panel.status_worker:
            self.panel.status_worker.join(2)
        self.panel.close()
        self.temp.cleanup()

    def responsive(self, action):
        done = threading.Event()
        result, failures = [], []

        def work():
            try:
                result.append(action())
            except BaseException as error:
                failures.append(error)
            finally:
                done.set()

        thread = threading.Thread(target=work, daemon=True)
        thread.start()
        self.assertTrue(done.wait(1), "World controls waited for an ancillary status read")
        thread.join(1)
        if failures:
            raise failures[0]
        return result[0]

    def finish_refresh(self):
        worker = self.panel.status_worker
        self.assertIsNotNone(worker)
        self.release.set()
        worker.join(2)
        self.assertFalse(worker.is_alive(), "The released status refresh did not complete")

    def test_blocked_remote_refresh_is_single_flight_and_controls_stay_responsive(self):
        entered = threading.Event()

        def blocked(root):
            entered.set()
            self.release.wait(5)
            return [{"privateToken": "not-for-the-panel"}]

        with patch.object(dashboard.pinggy_joining, "tunnels", side_effect=blocked) as tunnels, \
             patch.object(dashboard.pinggy_joining, "public_status", return_value=PUBLIC):
            first = self.responsive(self.panel.snapshot)
            self.assertFalse(first["remote_joining"]["available"])
            self.assertIsNone(first["remote_joining"]["checked_at"])
            self.assertTrue(entered.wait(1))
            worker = self.panel.status_worker
            for _ in range(20):
                self.responsive(self.panel.snapshot)
                self.assertIs(self.panel.status_worker, worker)
            self.responsive(lambda: self.panel.command("say still responsive"))
            self.panel.send_command.assert_called_once_with("say still responsive")
            self.responsive(lambda: self.panel.log("console output continues"))
            self.assertIn("console output continues", self.responsive(self.panel.snapshot)["logs"])
            self.responsive(self.panel.stop)
            self.assertTrue(self.panel.stop_event.is_set())
            self.assertEqual(tunnels.call_count, 1)
            self.finish_refresh()
            # The lifecycle change invalidates observations of the previous world.
            self.assertIsNone(self.panel.remote_state["checked_at"])

    def test_every_file_status_read_runs_outside_the_control_lock(self):
        for target, attribute in ((self.panel, "accepted"), (dashboard.community, "ai_status"),
                                  (dashboard.community, "roster"), (dashboard.community, "backups"),
                                  (self.panel, "find_friend_guide")):
            with self.subTest(reader=attribute):
                self.panel.state = "running"
                self.panel.status_checked = 0
                self.panel.remote_checked = time.monotonic()
                self.release.clear()
                entered = threading.Event()
                original = getattr(target, attribute)

                def blocked(*args, **kwargs):
                    entered.set()
                    self.release.wait(5)
                    return original(*args, **kwargs)

                with patch.object(target, attribute, side_effect=blocked):
                    self.responsive(self.panel.snapshot)
                    self.assertTrue(entered.wait(1))
                    self.responsive(lambda: self.panel.command("list"))
                    self.responsive(lambda: self.panel.log("file read is pending"))
                    snapshot = self.responsive(self.panel.snapshot)
                    self.assertEqual(snapshot["state"], "running")
                    self.responsive(self.panel.stop)
                    self.finish_refresh()

    def test_refresh_timestamps_describe_completion_and_returns_detached_caches(self):
        entered = threading.Event()
        monotonic, wall = [10.0], [100.0]

        def blocked(root):
            entered.set()
            self.release.wait(5)
            return [{"privateToken": "never-return-this"}]

        with patch.object(dashboard, "time", Mock(monotonic=lambda: monotonic[0], time=lambda: wall[0])), \
             patch.object(dashboard.pinggy_joining, "tunnels", side_effect=blocked), \
             patch.object(dashboard.pinggy_joining, "public_status", return_value=PUBLIC):
            self.responsive(self.panel.snapshot)
            self.assertTrue(entered.wait(1))
            self.assertEqual(self.panel.remote_checked, 0)
            monotonic[0], wall[0] = 20.0, 120.0
            self.finish_refresh()
            status = self.panel.snapshot()
            self.assertEqual(status["remote_joining"]["checked_at"], 120)
            self.assertEqual(self.panel.remote_checked, 20)
            self.assertEqual(self.panel.status_checked, 10)
            self.assertEqual(status["remote_joining"]["tunnels"], PUBLIC)
            self.assertNotIn("never-return-this", json.dumps(status))
            status["remote_joining"]["tunnels"][0]["address"]["host"] = "changed-by-caller"
            self.assertEqual(self.panel.snapshot()["remote_joining"]["tunnels"][0]["address"]["host"],
                             "java.example.test")

    def test_failed_refresh_is_unavailable_without_private_diagnostics_and_retries(self):
        with patch.object(dashboard.pinggy_joining, "tunnels", side_effect=RuntimeError("private agent diagnostic")), \
             patch.object(dashboard.community, "ai_status", side_effect=OSError("secret config content")), \
             patch.object(dashboard.community, "roster", side_effect=ValueError("private player record")), \
             patch.object(dashboard.community, "backups", side_effect=OSError("private disk path")):
            self.panel.snapshot()
            self.finish_refresh()
            status = self.panel.snapshot()
        self.assertFalse(status["remote_joining"]["available"])
        self.assertEqual(status["remote_joining"]["tunnels"], [])
        self.assertIsInstance(status["remote_joining"]["checked_at"], int)
        self.assertFalse(status["roster"]["available"])
        self.assertFalse(status["ai"]["hasKey"])
        self.assertEqual(status["backups"], [])
        for private in ("private agent diagnostic", "secret config content", "private player record", "private disk path"):
            self.assertNotIn(private, json.dumps(status))
        self.panel.remote_checked = 0
        with patch.object(dashboard.pinggy_joining, "tunnels", return_value=[]), \
             patch.object(dashboard.pinggy_joining, "public_status", return_value=PUBLIC):
            self.panel.snapshot()
            self.finish_refresh()
            self.assertTrue(self.panel.snapshot()["remote_joining"]["available"])

    def test_inflight_file_refresh_cannot_replace_newly_saved_ai_configuration(self):
        entered = threading.Event()

        def blocked(root):
            entered.set()
            self.release.wait(5)
            return []

        self.panel.state = "stopped"
        self.panel.worker = None
        with patch.object(dashboard.community, "backups", side_effect=blocked), \
             patch.object(dashboard.pinggy_joining, "tunnels", return_value=[]):
            self.panel.snapshot()
            self.assertTrue(entered.wait(1))
            self.responsive(lambda: self.panel.ai_config({"enabled": True, "apiKey": "test-only-private-ai-key"}))
            self.finish_refresh()
            status = self.panel.snapshot()
            self.assertTrue(status["ai"]["enabled"])
            self.assertTrue(status["ai"]["hasKey"])
            self.assertNotIn("test-only-private-ai-key", json.dumps(status))

    def test_close_does_not_wait_for_status_worker_or_publish_its_late_result(self):
        entered = threading.Event()

        def blocked(root):
            entered.set()
            self.release.wait(5)
            return []

        with patch.object(dashboard.pinggy_joining, "tunnels", side_effect=blocked), \
             patch.object(dashboard.pinggy_joining, "public_status", return_value=PUBLIC) as public:
            self.panel.snapshot()
            self.assertTrue(entered.wait(1))
            worker = self.panel.status_worker
            self.responsive(self.panel.close)
            self.finish_refresh()
            self.assertFalse(self.panel.snapshot()["remote_joining"]["available"])
            self.assertIs(self.panel.status_worker, worker)
            self.assertEqual(public.call_count, 1)

    def test_local_cache_publishes_before_remote_completion_and_roster_expires(self):
        entered = threading.Event()
        clock = [100.0]
        roster = {"players": [{"name": "FreshPlayer", "uuid": "saved-uuid"}],
                  "tick_ms": 1.25, "available": True}

        def blocked(root):
            entered.set()
            self.release.wait(5)
            return []

        with patch.object(dashboard, "time", Mock(monotonic=lambda: clock[0], time=time.time)), \
             patch.object(dashboard.community, "roster", return_value=roster), \
             patch.object(dashboard.pinggy_joining, "tunnels", side_effect=blocked):
            self.panel.snapshot()
            self.assertTrue(entered.wait(1))
            fresh = self.panel.snapshot()
            self.assertTrue(fresh["roster"]["available"])
            self.assertEqual(fresh["roster"]["players"][0]["name"], "FreshPlayer")
            self.assertFalse(fresh["remote_joining"]["available"])
            clock[0] += dashboard.ROSTER_CACHE_SECONDS + 1
            expired = self.responsive(self.panel.snapshot)
            self.assertFalse(expired["roster"]["available"])
            self.assertEqual(expired["roster"]["players"], [])
            self.finish_refresh()

    def test_guide_download_uses_only_the_bounded_validated_bytes(self):
        pdf = self.root / dashboard.FRIEND_GUIDE
        original = b"%PDF-1.4\nvalidated friend guide"
        pdf.write_bytes(original)
        pdf.with_suffix(".md").write_text("Java Server Address: java.example.test:43297.\n"
                                          "Bedrock Address: bedrock.example.test, Port: 60912.\n")
        with patch.object(dashboard.pinggy_joining, "tunnels", return_value=[]), \
             patch.object(dashboard.pinggy_joining, "public_status", return_value=PUBLIC):
            self.panel.snapshot()
            self.finish_refresh()
            self.assertTrue(self.panel.snapshot()["friend_guide"]["available"])
            secret = self.root / "private.txt"
            secret.write_text("private file must never be served")
            pdf.unlink()
            pdf.symlink_to(secret)
            self.assertEqual(self.panel.friend_guide_content(), original)
            self.assertNotIn(b"private file", self.panel.friend_guide_content())
            self.panel.remote_checked = 0
            self.panel.snapshot()
            self.finish_refresh()
            self.assertFalse(self.panel.snapshot()["friend_guide"]["available"])
            self.assertIsNone(self.panel.friend_guide_content())

    def test_cached_guide_expires_even_when_the_file_refresh_is_stalled(self):
        entered = threading.Event()
        clock = [100.0]
        self.panel.remote_checked = clock[0]
        self.panel.guide_bytes = b"%PDF-1.4\npreviously validated guide"

        def blocked():
            entered.set()
            self.release.wait(5)
            return True

        with patch.object(dashboard, "time", Mock(monotonic=lambda: clock[0], time=time.time)), \
             patch.object(self.panel, "accepted", side_effect=blocked):
            self.panel.snapshot()
            self.assertTrue(entered.wait(1))
            self.assertIsNotNone(self.panel.friend_guide_content())
            clock[0] += dashboard.REMOTE_INTERVAL + 1
            self.assertIsNone(self.responsive(self.panel.friend_guide_content))
            self.assertFalse(self.responsive(self.panel.snapshot)["friend_guide"]["available"])
            self.finish_refresh()


if __name__ == "__main__":
    unittest.main()
