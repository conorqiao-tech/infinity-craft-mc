import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community
import server as launcher
import dashboard


class CommunityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/my-world"
        self.world.mkdir(parents=True)
        (self.world / "level.dat").write_bytes(b"test world header")
        (self.world / "region").mkdir()
        (self.world / "region/r.0.0.mca").write_bytes(b"test region bytes")
        (self.root / "fabric/server.properties").write_text("level-name=my-world\n")

    def tearDown(self):
        self.temp.cleanup()

    def test_backup_copies_world_and_moderation_without_runtime_keys(self):
        (self.root / "fabric/ops.json").write_text('[{"name":"Test"}]')
        (self.root / "fabric/config").mkdir()
        (self.root / "fabric/config/key.pem").write_text("do not include")
        name = community.backup(self.root, {"level-name": "my-world"})
        with zipfile.ZipFile(self.root / "backups" / name) as backup:
            self.assertIsNone(backup.testzip())
            self.assertEqual(backup.read("my-world/region/r.0.0.mca"), b"test region bytes")
            self.assertIn("ops.json", backup.namelist())
            self.assertNotIn("config/key.pem", backup.namelist())
        self.assertFalse((self.root / "launcher.lock").exists())
        self.assertEqual(community.backups(self.root)[0]["name"], name)

    def test_backup_never_runs_under_an_active_world_lock(self):
        (self.root / "launcher.lock").write_text("running")
        with self.assertRaisesRegex(ValueError, "Stop"):
            community.backup(self.root, {"level-name": "my-world"})
        self.assertEqual((self.root / "launcher.lock").read_text(), "running")
        self.assertFalse((self.root / "backups").exists())

    def test_backup_rejects_path_escape_and_symlinks_without_partial_archives(self):
        with self.assertRaisesRegex(ValueError, "inside"):
            community.backup(self.root, {"level-name": "../../elsewhere"})
        (self.world / "linked").symlink_to(self.root / "fabric/server.properties")
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            community.backup(self.root, {"level-name": "my-world"})
        self.assertFalse((self.root / "launcher.lock").exists())
        self.assertFalse(list((self.root / "backups").iterdir()))

    def test_missing_world_is_not_reported_as_a_successful_backup(self):
        self.assertIsNone(community.backup(self.root, {"level-name": "not-created"}))
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_server_settings_preserve_world_and_authentication(self):
        args = launcher.parse_args([])
        args.community = community.settings({"server_name": "Infinity Community", "max_players": 40, "whitelist": True, "rules": "Be kind.\nDo not grief."})
        launcher.configure(args, self.root)
        props = launcher.properties(self.root / "fabric/server.properties")
        self.assertEqual(props["level-name"], "my-world")
        self.assertEqual(props["online-mode"], "true")
        self.assertEqual(props["white-list"], "true")
        self.assertEqual(props["max-players"], "40")
        self.assertEqual(props["spawn-protection"], "32")
        config = json.loads((self.root / "fabric/config/infinity-community.json").read_text())
        self.assertEqual(config["rules"], ["Be kind.", "Do not grief."])
        self.assertIn('primary-motd: "Infinity Community"', (self.root / "geyser/config.yml").read_text())
        for bad in [{"server_name": "x\nonline-mode=false"}, {"max_players": 9999}, {"max_players": True}, {"rules": []}, {"whitelist": "yes"}]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                dashboard.Panel.valid_settings(bad)

    def test_roster_only_reports_fresh_running_data(self):
        status = self.root / "fabric/community-status.json"
        status.write_text(json.dumps({"updated": time.time() * 1000, "players": [{"name": ".Test", "uuid": "test"}], "tick_ms": 12.5}))
        self.assertTrue(community.roster(self.root, True)["available"])
        self.assertFalse(community.roster(self.root, False)["available"])
        status.write_text(json.dumps({"updated": 0, "players": [], "tick_ms": 0}))
        self.assertFalse(community.roster(self.root, True)["available"])

    def test_moderation_rejects_command_injection_and_accepts_bedrock_names(self):
        self.assertEqual(community.moderation_command("mute", ".Bedrock_User"), "community mute .Bedrock_User 10")
        for name in ["@a", "Player\nstop", "Player reason", ""]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                community.moderation_command("ban", name)
        with self.assertRaises(ValueError):
            community.moderation_command("op", "Player")


if __name__ == "__main__":
    unittest.main()
