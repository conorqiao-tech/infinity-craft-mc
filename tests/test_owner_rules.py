from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import owner_rules


def login(name):
    return "[08:01:13] [Server thread/INFO]: " + name + "[/192.168.88.12:50503] logged in with entity id 115 at (-545.4, 83.4, -518.8)\n"


class OwnerRulesTests(unittest.TestCase):
    def test_exact_java_and_bedrock_names_are_banned(self):
        for account in ("Aria", "aria", "ARIA", ".Aria", ".aRiA"):
            guard = owner_rules.JoinRules(["aria"])
            self.assertEqual(guard.command_for(login(account)), "ban " + account + " Blocked by the server owner")

    def test_partial_names_and_other_accounts_are_unchanged(self):
        guard = owner_rules.JoinRules(["aria"])
        for account in ("Ariana", ".Ariana", "notAria", "Aria_", "HackerAdmin2927"):
            self.assertIsNone(guard.command_for(login(account)))

    def test_chat_and_console_echo_cannot_trigger_account_bans(self):
        guard = owner_rules.JoinRules(["aria"])
        for line in ("System chat: " + login("Aria"), "> " + login("Aria"),
                     "[08:01:13] [Server thread/INFO]: System chat: " + login("Aria"),
                     login("Aria;stop"), login("Aria\nstop")):
            self.assertIsNone(guard.command_for(line))

    def test_account_ban_is_not_repeated_from_duplicate_log_records(self):
        guard = owner_rules.JoinRules(["aria"])
        command = guard.command_for(login("Aria"))
        self.assertIsNotNone(command)
        guard.mark_sent(command)
        self.assertIsNone(guard.command_for(login("ARIA")))
        self.assertIsNotNone(guard.command_for(login(".Aria")))

    def test_ipv6_and_fabric_logger_prefixes_match_native_logins(self):
        for line in (login(".Aria").replace('/192.168.88.12:50503', '/[2001:db8::1]:50503'),
                     login("Aria").replace('[Server thread/INFO]: ', '[Server thread/INFO] (Minecraft) '),
                     login(".Aria").replace('/192.168.88.12:50503', 'local')):
            self.assertIsNotNone(owner_rules.JoinRules(["Aria"]).command_for(line))

    def test_failed_send_can_be_retried(self):
        guard = owner_rules.JoinRules(["aria"])
        self.assertIsNotNone(guard.command_for(login("Aria")))
        self.assertIsNotNone(guard.command_for(login("Aria")))

    def test_default_has_no_bans_and_invalid_policy_fails_closed(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            self.assertIsNone(owner_rules.load(root).command_for(login("Aria")))
            (root / owner_rules.POLICY).write_text('{"format":1,"blocked_names":["Aria;stop"]}')
            with self.assertRaises(ValueError):
                owner_rules.load(root)
            (root / owner_rules.POLICY).write_text('{"format":1,"blocked_names":["Aria"]}')
            self.assertIsNotNone(owner_rules.load(root).command_for(login(".Aria")))

    def test_native_login_hook_sends_ban_through_actual_process_pipe(self):
        import server as launcher
        import time
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            script = "import sys\nprint(" + repr(login(".Aria").strip()) + ", flush=True)\nfor line in sys.stdin:\n print('received:' + line.strip(), flush=True)\n if line.strip() == 'stop': break\n"
            service = launcher.Service("Test", [sys.executable, "-u", "-c", script], root,
                                       "logged in with entity", join_rules=owner_rules.JoinRules(["Aria"]))
            try:
                service.wait_ready(5)
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    if "received:ban .Aria Blocked by the server owner" in (root / "launcher.log").read_text():
                        break
                    time.sleep(.02)
                else:
                    self.fail("Native login did not receive the exact native ban command")
            finally:
                service.stop()
                service.thread.join(5)
                service.process.stdin.close()
                service.process.stdout.close()
            self.assertEqual(service.process.returncode, 0)

    def test_join_hook_failure_does_not_stop_draining_server_output(self):
        import server as launcher
        class BrokenRule:
            def command_for(self, line):
                raise ValueError("closed console pipe")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            script = "import sys\nprint('first output', flush=True)\nprint('READY', flush=True)\nfor line in sys.stdin:\n print('received:' + line.strip(), flush=True)\n if line.strip() == 'stop': break\n"
            service = launcher.Service("Test", [sys.executable, "-u", "-c", script], root,
                                       "READY", join_rules=BrokenRule())
            try:
                service.wait_ready(5)
            finally:
                service.stop()
                service.thread.join(5)
                service.process.stdin.close()
                service.process.stdout.close()
            self.assertIn("received:stop", (root / "launcher.log").read_text())
            self.assertEqual(service.process.returncode, 0)
