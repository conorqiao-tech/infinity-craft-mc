import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import zipfile
from unittest.mock import patch

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

    def test_roster_preserves_valid_player_fields_and_never_reads_while_offline(self):
        players = [{"name": ".Bedrock_User", "uuid": "9b644bbc-50f5-4640-a6eb-358a4c48f388",
                    "mode": "SURVIVAL", "rank": "OP"}]
        status = self.root / "fabric/community-status.json"
        status.write_text(json.dumps({"updated": time.time() * 1000, "players": players, "tick_ms": 12.5}))
        self.assertEqual(community.roster(self.root, True), {"players": players, "tick_ms": 12.5, "available": True})
        with patch.object(Path, "open") as read:
            self.assertEqual(community.roster(self.root, False), {"players": [], "tick_ms": None, "available": False})
        read.assert_not_called()

    def test_roster_only_returns_public_native_player_fields(self):
        player={"name": ".Test", "uuid": "test", "mode": "SURVIVAL", "rank": "OP"}
        status=self.root / "fabric/community-status.json"
        status.write_text(json.dumps({"updated": time.time()*1000, "players": [{**player,"privateToken":"must-stay-private","details":{"secret":"hidden"}}], "tick_ms": 1.5, "privateConfig":"hidden"}))
        self.assertEqual(community.roster(self.root,True),{"players":[player],"tick_ms":1.5,"available":True})

    def test_roster_rejects_malformed_schema_and_nonfinite_numbers(self):
        status = self.root / "fabric/community-status.json"
        valid = {"updated": time.time() * 1000, "players": [], "tick_ms": 12.5}
        invalid = [None, [], "status", {}, {"players": [], "tick_ms": 0},
                   *[{**valid, "updated": value} for value in (None, True, "recent", {}, [], float("nan"), float("inf"), 10 ** 400)],
                   *[{**valid, "tick_ms": value} for value in (None, True, "fast", {}, [], -1, float("nan"), float("inf"), 10 ** 400)],
                   *[{**valid, "players": value} for value in (None, {}, "players", [None], ["player"], [{}], [{"name": []}], [{"name": ""}])]]
        for value in invalid:
            with self.subTest(value=value):
                status.write_text(json.dumps(value))
                self.assertEqual(community.roster(self.root, True), {"players": [], "tick_ms": None, "available": False})

    def test_roster_rejects_invalid_player_fields_and_excessive_player_count(self):
        status = self.root / "fabric/community-status.json"
        player = {"name": ".Test", "uuid": "test", "mode": "SURVIVAL", "rank": "OP"}
        invalid = [{**player, "name": "x" * 65}, {**player, "name": "Test\nstop"},
                   {**player, "uuid": None}, {**player, "uuid": "x" * 65},
                   {**player, "mode": []}, {**player, "mode": "x" * 65},
                   {**player, "rank": 1}, {**player, "rank": "x" * 161}]
        for value in invalid:
            with self.subTest(player=value):
                status.write_text(json.dumps({"updated": time.time() * 1000, "players": [value], "tick_ms": 0}))
                self.assertFalse(community.roster(self.root, True)["available"])
        status.write_text(json.dumps({"updated": time.time() * 1000,
                                      "players": [player] * community.ROSTER_MAX_PLAYERS, "tick_ms": 0}))
        self.assertTrue(community.roster(self.root, True)["available"])
        status.write_text(json.dumps({"updated": time.time() * 1000,
                                      "players": [player] * (community.ROSTER_MAX_PLAYERS + 1), "tick_ms": 0}))
        self.assertFalse(community.roster(self.root, True)["available"])

    def test_roster_read_is_bounded_and_oversized_data_is_unavailable(self):
        import io
        content = b" " * (community.ROSTER_MAX_BYTES + 100)
        stream = io.BytesIO(content)
        with patch.object(Path, "open", return_value=stream), patch.object(stream, "read", wraps=stream.read) as read:
            self.assertFalse(community.roster(self.root, True)["available"])
            read.assert_called_once_with(community.ROSTER_MAX_BYTES + 1)
        status = self.root / "fabric/community-status.json"
        valid = json.dumps({"updated": time.time() * 1000, "players": [], "tick_ms": 0}).encode()
        status.write_bytes(valid + b" " * (community.ROSTER_MAX_BYTES - len(valid)))
        self.assertTrue(community.roster(self.root, True)["available"])
        status.write_bytes(status.read_bytes() + b" ")
        self.assertFalse(community.roster(self.root, True)["available"])

    def test_roster_rejects_bad_encoding_truncated_and_deeply_nested_json(self):
        status = self.root / "fabric/community-status.json"
        for content in (b"\xff", b'{"updated":', b"[" * 2000 + b"0" + b"]" * 2000):
            with self.subTest(content_length=len(content)):
                status.write_bytes(content)
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
