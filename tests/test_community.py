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

    def test_backup_publishes_only_after_closed_zip_is_synchronized(self):
        import stat
        events = []
        backups = self.root / "backups"
        real_sync_file = community.sync_file

        def published():
            return ([p.suffix for p in backups.glob("*.partial")], [p.suffix for p in backups.glob("*.zip")])

        def sync_file(stream):
            real_sync_file(stream)
            partial = list(backups.glob("*.partial"))
            self.assertEqual(published(), ([".partial"], []))
            # A readable central directory proves the ZIP was closed before syncing.
            with zipfile.ZipFile(partial[0]) as archive:
                self.assertIsNone(archive.testzip())
                self.assertEqual(archive.read("my-world/level.dat"), b"test world header")
            events.append("sync zip")

        def sync_directory(path):
            # The backup folder is synchronized only after the rename.
            events.append(("sync directory", Path(path), published()))

        # Recorded rather than real directory syncs keep this ordering check platform-independent.
        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_file", side_effect=sync_file), \
             patch.object(community, "sync_directory", side_effect=sync_directory):
            name = community.backup(self.root, {"level-name": "my-world"})
        self.assertEqual(events, [("sync directory", self.root, ([], [])), "sync zip",
                                  ("sync directory", backups, ([], [".zip"]))])
        self.assertEqual([p.name for p in backups.iterdir()], [name])
        if community.os.name == "posix":
            self.assertEqual(stat.S_IMODE((backups / name).stat().st_mode), 0o600)
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_zip_sync_failure_publishes_no_backup(self):
        import errno
        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_file", side_effect=OSError(errno.EIO, "injected file sync failure")), \
             patch.object(community, "sync_directory") as sync_directory:
            with self.assertRaisesRegex(OSError, "injected file sync failure"):
                community.backup(self.root, {"level-name": "my-world"})
        # Only the new backups folder's parent was synchronized; no rename followed.
        sync_directory.assert_called_once_with(self.root)
        self.assertEqual(list((self.root / "backups").iterdir()), [])
        self.assertFalse((self.root / "launcher.lock").exists())
        self.assertEqual((self.world / "region/r.0.0.mca").read_bytes(), b"test region bytes")

    def test_backup_directory_sync_failure_is_not_reported_as_success(self):
        import errno

        def sync_directory(path):
            if Path(path) == self.root / "backups":
                raise OSError(errno.EIO, "injected directory sync failure")

        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "sync_directory", side_effect=sync_directory):
            with self.assertRaisesRegex(OSError, "injected directory sync failure"):
                community.backup(self.root, {"level-name": "my-world"})
            with self.assertRaisesRegex(OSError, "injected directory sync failure"):
                community.backup(self.root, {"level-name": "my-world"}, locked=True)
        self.assertEqual(list((self.root / "backups").glob("*.partial")), [])
        self.assertFalse((self.root / "launcher.lock").exists())
        self.assertEqual((self.world / "region/r.0.0.mca").read_bytes(), b"test region bytes")

    def closed_descriptor(self):
        descriptor = community.os.open(self.root / "fabric/server.properties", community.os.O_RDONLY)
        community.os.close(descriptor)
        return descriptor

    def test_flush_uses_full_sync_where_available_and_propagates_real_failures(self):
        import errno
        with tempfile.TemporaryFile() as stream:
            descriptor = stream.fileno()
            # A closed descriptor makes the real fsync fail, without patching os.
            with patch.object(community, "FULL_FSYNC", False):
                community.flush_descriptor(descriptor)
                with self.assertRaises(OSError):
                    community.flush_descriptor(self.closed_descriptor())
            with patch.object(community, "FULL_FSYNC", True):
                with patch.object(community, "full_fsync") as full:
                    community.flush_descriptor(descriptor)
                full.assert_called_once_with(descriptor)
                # Unsupported F_FULLFSYNC falls back to a real fsync, whose failures still propagate.
                with patch.object(community, "full_fsync", side_effect=OSError(errno.ENOTSUP, "unsupported")):
                    community.flush_descriptor(descriptor)
                    with self.assertRaises(OSError) as raised:
                        community.flush_descriptor(self.closed_descriptor())
                    self.assertNotEqual(raised.exception.errno, errno.ENOTSUP)
                with patch.object(community, "full_fsync", side_effect=OSError(errno.EIO, "injected device failure")):
                    with self.assertRaisesRegex(OSError, "injected device failure"):
                        community.flush_descriptor(descriptor)

    @unittest.skipUnless(community.sys.platform == "darwin", "F_FULLFSYNC is macOS-only")
    def test_macos_full_sync_succeeds_on_files_and_directories(self):
        with tempfile.TemporaryFile() as stream:
            community.full_fsync(stream.fileno())
        community.sync_directory(self.root)

    def test_unconfirmable_directory_sync_still_saves_a_flushed_backup(self):
        real_sync_file = community.sync_file
        with patch.object(community, "DIRECTORY_SYNC", False), \
             patch.object(community, "sync_file", side_effect=real_sync_file) as sync_file, \
             patch.object(community, "sync_directory") as sync_directory:
            name = community.backup(self.root, {"level-name": "my-world"})
            sync_file.assert_called_once()
            sync_directory.assert_not_called()
            # The saved archive is never accepted as a confirmed restore point here.
            with self.assertRaisesRegex(community.DurabilityError, "stable storage"):
                community.confirm_backup_durable(self.root, name)
        with patch.object(community, "DIRECTORY_SYNC", False):
            with self.assertRaises(community.DurabilityError):
                community.sync_directory(self.root)
        with zipfile.ZipFile(self.root / "backups" / name) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("my-world/region/r.0.0.mca"), b"test region bytes")
        self.assertEqual([p.name for p in (self.root / "backups").iterdir()], [name])
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_dashboard_manual_backup_states_unconfirmed_directory_durability(self):
        from types import SimpleNamespace
        fake_launcher = SimpleNamespace(properties=launcher.properties,
                                        join_addresses=lambda args: {"host": "", "java": "", "local_java": "", "bedrock_port": 0, "local_only": True})
        panel = dashboard.Panel(fake_launcher, launcher.parse_args([]), self.root)
        # Only the module flag is mocked; directory syncs are recorded so both branches run anywhere.
        for supported in (False, True):
            with self.subTest(directory_sync=supported):
                before = set((self.root / "backups").glob("*.zip")) if (self.root / "backups").exists() else set()
                with patch.object(community, "DIRECTORY_SYNC", supported), patch.object(community, "sync_directory"):
                    panel.backup()
                    panel.worker.join(timeout=60)
                self.assertFalse(panel.worker.is_alive())
                self.assertEqual((panel.state, panel.error), ("stopped", ""))
                created = set((self.root / "backups").glob("*.zip")) - before
                self.assertEqual(len(created), 1)
                saved = created.pop()
                with zipfile.ZipFile(saved) as archive:
                    self.assertEqual(archive.read("my-world/region/r.0.0.mca"), b"test region bytes")
                expected = "Backup saved: backups/" + saved.name
                if not supported:
                    expected += "; " + community.UNCONFIRMED_BACKUP_NOTE + "."
                    self.assertIn("cannot confirm", expected)
                self.assertEqual(list(panel.logs)[-1], expected)
                self.assertFalse((self.root / "launcher.lock").exists())

    def test_confirming_backup_flushes_archive_then_its_folders(self):
        name = community.backup(self.root, {"level-name": "my-world"})
        events = []

        def flush(descriptor):
            events.append(("flush", community.os.fstat(descriptor).st_size))

        def sync_directory(path):
            events.append(("sync directory", Path(path)))

        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "flush_descriptor", side_effect=flush), \
             patch.object(community, "sync_directory", side_effect=sync_directory):
            community.confirm_backup_durable(self.root, name)
        size = (self.root / "backups" / name).stat().st_size
        self.assertEqual(events, [("flush", size), ("sync directory", self.root / "backups"),
                                  ("sync directory", self.root)])

    def test_confirming_backup_propagates_sync_failures_and_rejects_other_files(self):
        import errno
        name = community.backup(self.root, {"level-name": "my-world"})
        with patch.object(community, "DIRECTORY_SYNC", True), \
             patch.object(community, "flush_descriptor", side_effect=OSError(errno.EIO, "injected archive sync failure")), \
             patch.object(community, "sync_directory") as sync_directory:
            with self.assertRaisesRegex(OSError, "injected archive sync failure"):
                community.confirm_backup_durable(self.root, name)
        sync_directory.assert_not_called()
        with patch.object(community, "DIRECTORY_SYNC", True), patch.object(community, "flush_descriptor"), \
             patch.object(community, "sync_directory", side_effect=OSError(errno.EIO, "injected directory sync failure")):
            with self.assertRaisesRegex(OSError, "injected directory sync failure"):
                community.confirm_backup_durable(self.root, name)
        with patch.object(community, "DIRECTORY_SYNC", True):
            for bad in (None, "", "../" + name, "other.zip", name + ".partial"):
                with self.subTest(bad=bad), self.assertRaises(ValueError):
                    community.confirm_backup_durable(self.root, bad)

    def test_backup_follows_decoded_world_and_only_skips_genuinely_new_worlds(self):
        world = self.root / "fabric/world2"
        world.mkdir()
        (world / "level.dat").write_bytes(b"escaped world")
        (self.root / "fabric/server.properties").write_bytes(b"level-name=world\\u0032\n")
        name = community.backup(self.root, launcher.properties(self.root / "fabric/server.properties"))
        with zipfile.ZipFile(self.root / "backups" / name) as archive:
            self.assertEqual(archive.read("world2/level.dat"), b"escaped world")
        fresh = self.root / "fabric/fresh"
        fresh.mkdir()
        (fresh / "session.lock").write_bytes(b"lock")
        self.assertIsNone(community.backup(self.root, {"level-name": "fresh"}))
        old_only = self.root / "fabric/restored"
        old_only.mkdir()
        (old_only / "level.dat_old").write_bytes(b"previous header")
        name = community.backup(self.root, {"level-name": "restored"})
        with zipfile.ZipFile(self.root / "backups" / name) as archive:
            self.assertEqual(archive.read("restored/level.dat_old"), b"previous header")
        (self.root / "fabric/not-a-folder").write_bytes(b"file")
        with self.assertRaisesRegex(ValueError, "not a world folder"):
            community.backup(self.root, {"level-name": "not-a-folder"})
        self.assertFalse((self.root / "launcher.lock").exists())

    def test_server_name_is_written_as_equivalent_java_text(self):
        propfile = self.root / "fabric/server.properties"
        self.world.rename(self.root / "fabric/my world=2")
        propfile.write_bytes(rb"level-name=my world\=2" + b"\nmotd=old\n")
        args = launcher.parse_args([])
        args.community = community.settings({"server_name": 'Café "Crew" #1'})
        launcher.configure(args, self.root)
        propfile.read_bytes().decode("ascii")
        props = launcher.properties(propfile)
        self.assertEqual(props["motd"], 'Café "Crew" #1 | Java + Bedrock | 5 Game Modes')
        self.assertEqual(props["level-name"], "my world=2")

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
