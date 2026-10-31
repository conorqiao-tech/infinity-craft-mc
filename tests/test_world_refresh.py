import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import community
import world_refresh as refresh


class TerrainRefreshTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.world = self.root / "fabric/world"
        self.world.mkdir(parents=True)
        files = {"level.dat": b"level", "players/data/player.dat": b"inventory-stats-backpack",
                 "players/stats/player.json": b"stats", "players/advancements/player.json": b"advancements",
                 "infinity-agents.json": b"helper roster", "infinity-memberships.json": b"ranks",
                 "data/minecraft/scoreboard.dat": b"scores", "data/minecraft/world_gen_settings.dat": b"seed"}
        for dimension in ("minecraft/overworld", "minecraft/the_end", "convergence/hub"):
            prefix = "dimensions/" + dimension + "/"
            files.update({prefix + "region/r.0.0.mca": b"terrain-and-chests",
                          prefix + "poi/r.0.0.mca": b"old-POI",
                          prefix + "entities/r.0.0.mca": b"helper-full-NBT-and-pets",
                          prefix + "data/minecraft/chunk_tickets.dat": b"tickets",
                          prefix + "data/minecraft/raids.dat": b"raids",
                          prefix + "data/minecraft/world_border.dat": b"border"})
        files["dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat"] = b"boss progress"
        files.update({name: b"built marker" for name in refresh.MARKERS})
        for name, data in files.items():
            p = self.world / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        self.original = files
        self.storage = patch.object(refresh.terrain_storage, "prepare", return_value=(
            {name: data for name, data in files.items() if "/entities/" in name}, {}))
        self.storage.start()
        mod = self.root / "fabric/mods/Infinity-Armor-test.jar"
        mod.parent.mkdir(parents=True)
        self.write_mod(mod, "installed mod")
        (self.root / "dependencies.lock.json").write_text(json.dumps({"version": "2.13.0-explore.24",
            "bundled": [{"path": mod.relative_to(self.root).as_posix(), "sha256": refresh.digest(mod)}]}))
        self.backup = community.backup(self.root, {"level-name": "world"})
        (self.root / "launcher.lock").write_text("test lease")

    def tearDown(self):
        self.storage.stop()
        self.temp.cleanup()

    def write_mod(self, path, content, version="2.13.0-explore.24"):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps({"version": version}))
            archive.writestr("content.txt", content)

    def update_mod(self, content, version="2.13.0-explore.24"):
        path = self.root / "fabric/mods/Infinity-Armor-test.jar"
        self.write_mod(path, content, version)
        lock = json.loads((self.root / "dependencies.lock.json").read_text())
        lock["bundled"][0]["sha256"] = refresh.digest(path)
        (self.root / "dependencies.lock.json").write_text(json.dumps(lock))

    def policy(self, **values):
        config = dict(format=1, enabled=True, last_release="previous", refresh_next_start=False)
        config.update(values)
        refresh.atomic_json(self.root / refresh.POLICY, config)
        return config

    def transaction(self):
        # The retained earlier transaction; only used here to create real
        # journals for recovery, never by the launcher.
        return refresh.regenerate(self.root, "world", self.backup, lambda _: None)

    def snapshot(self):
        return {p.relative_to(self.world).as_posix(): p.read_bytes()
                for p in self.world.rglob("*") if p.is_file()}

    def assert_original(self):
        self.assertEqual(self.snapshot(), self.original)

    def start(self):
        """Run the real launcher up to opening Java; return the world as Java would see it."""
        import server as launcher
        seen = []

        def open_java(*args, **kwargs):
            seen.append(self.snapshot())
            raise InterruptedError
        (self.root / "launcher.lock").unlink(missing_ok=True)
        with patch.object(launcher, "check_ports"), patch.object(launcher, "emit") as emit, \
             patch.object(launcher, "java_command", return_value="java"), \
             patch.object(launcher, "setup"), patch.object(launcher, "accept_eula"), \
             patch.object(launcher.owner_rules, "load"), \
             patch.object(launcher, "Service", side_effect=open_java), \
             patch.object(refresh.terrain_storage, "prepare") as prepare, \
             patch.object(community, "confirm_backup_durable") as confirm, \
             patch.object(refresh, "regenerate", side_effect=AssertionError("launcher regenerated terrain")):
            launcher.run_server(launcher.parse_args([]), root=self.root, console=False)
        prepare.assert_not_called()
        confirm.assert_not_called()
        self.assertEqual(len(seen), 1, "Java was not reached exactly once")
        self.assertFalse((self.root / "launcher.lock").exists())
        return seen[0], [call.args[0] for call in emit.call_args_list]

    def refresh_folders(self):
        return sorted((self.root / "backups").glob("Terrain-Refresh-*"))

    def assert_no_new_refresh(self, policy_bytes=None, folders=()):
        self.assertFalse((self.root / refresh.JOURNAL).exists())
        self.assertEqual(self.refresh_folders(), list(folders))
        if policy_bytes is not None:
            self.assertEqual((self.root / refresh.POLICY).read_bytes(), policy_bytes)

    # Ordinary starts keep every world exactly as saved.

    def test_start_without_policy_keeps_world_and_writes_no_policy(self):
        for _ in range(2):
            before_java, _ = self.start()
            self.assertEqual(before_java, self.original)
        self.assert_original()
        self.assertFalse((self.root / refresh.POLICY).exists())
        self.assert_no_new_refresh()

    def test_changed_mod_checksum_and_version_do_not_regenerate_legacy_enabled_world(self):
        legacy = {
            "enabled_previous_release": dict(last_release="previous"),
            "enabled_without_baseline": dict(last_release=""),
            "enabled_and_pending_same_release": dict(last_release=None, refresh_next_start=True),
            "enabled_and_pending_after_update": dict(refresh_next_start=True),
            "disabled_but_pending": dict(enabled=False, refresh_next_start=True),
        }
        for number, (case, values) in enumerate(legacy.items()):
            with self.subTest(case=case):
                if values.get("last_release", "") is None:
                    values = dict(values, last_release=refresh.release_id(self.root))
                self.policy(**values)
                policy_bytes = (self.root / refresh.POLICY).read_bytes()
                # Each case is a different installed release: new checksum, and new version too.
                self.update_mod("update " + case, "2.13.0-explore." + str(25 + number))
                before_java, messages = self.start()
                self.assertEqual(before_java, self.original)
                self.assert_original()
                self.assert_no_new_refresh(policy_bytes)
                self.assertTrue(any("does not regenerate terrain" in m and "left unchanged" in m for m in messages))
                self.assertFalse(any("regenerated" in m.lower() for m in messages))

    def test_same_version_with_new_checksum_and_package_only_change_keep_world(self):
        self.policy(last_release=refresh.release_id(self.root), refresh_next_start=True)
        policy_bytes = (self.root / refresh.POLICY).read_bytes()
        self.update_mod("same version, different checksum")
        self.assertEqual(self.start()[0], self.original)
        lock = json.loads((self.root / "dependencies.lock.json").read_text())
        lock["version"] = "launcher-only-update"
        (self.root / "dependencies.lock.json").write_text(json.dumps(lock))
        self.assertEqual(self.start()[0], self.original)
        self.assert_original()
        self.assert_no_new_refresh(policy_bytes)

    def test_repeated_starts_keep_every_dimension_and_player_mod_sentinels_exact(self):
        extra = {"dimensions/infinity/games/hub/region/r.-1.2.mca": b"nested player build",
                 "dimensions/infinity/games/hub/poi/r.-1.2.mca": b"nested POI",
                 "dimensions/infinity/games/hub/entities/r.-1.2.mca": b"nested pets",
                 "dimensions/convergence/creative/region/r.3.-4.mca": b"creative build with chests",
                 "dimensions/convergence/creative/entities/r.3.-4.mca": b"helper golem",
                 "dimensions/minecraft/the_nether/region/r.0.0.mca": b"nether portal room",
                 "players/data/11111111-1111-1111-1111-111111111111.dat": b"\x1f\x8bplayer NBT",
                 "data/convergence/backpacks.dat": b"mod backpack data",
                 "infinity-records.json": b"mod records"}
        for name, data in extra.items():
            path = self.world / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        self.original.update(extra)
        self.policy(refresh_next_start=True)
        policy_bytes = (self.root / refresh.POLICY).read_bytes()
        for attempt in range(3):
            self.update_mod("release " + str(attempt), "2.13.0-explore." + str(30 + attempt))
            before_java, _ = self.start()
            self.assertEqual(before_java, self.original)
        self.assert_original()
        self.assert_no_new_refresh(policy_bytes)
        # Normal startup backups still run and each one matches the stopped world.
        backups = sorted((self.root / "backups").glob("Infinity-World-*.zip"), key=lambda p: p.stat().st_mtime_ns)
        self.assertGreaterEqual(len(backups), 2)
        refresh.verify_backup(self.root, self.world, backups[-1].name)

    def test_unreadable_or_linked_legacy_policy_keeps_world_and_is_not_rewritten(self):
        cases = {"malformed": b'{"format": 1, "enabled": "yes"}\n', "not_json": b"enabled=true\n"}
        for case, content in cases.items():
            with self.subTest(case=case):
                (self.root / refresh.POLICY).write_bytes(content)
                before_java, messages = self.start()
                self.assertEqual(before_java, self.original)
                self.assertEqual((self.root / refresh.POLICY).read_bytes(), content)
                self.assertTrue(any("unreadable and was left unchanged" in m for m in messages))
        (self.root / refresh.POLICY).unlink()
        target = self.root / "elsewhere.json"
        target.write_text(json.dumps(dict(format=1, enabled=True, refresh_next_start=True)))
        (self.root / refresh.POLICY).symlink_to(target)
        self.assertEqual(self.start()[0], self.original)
        self.assertTrue((self.root / refresh.POLICY).is_symlink())
        target.unlink()
        self.assertEqual(self.start()[0], self.original)
        self.assertTrue((self.root / refresh.POLICY).is_symlink())
        self.assert_original()
        self.assert_no_new_refresh()

    def test_startup_check_writes_nothing_and_needs_no_directory_durability(self):
        # Each case: policy overrides, and whether startup reports an ignored refresh request.
        cases = {"no_policy": (None, False),
                 "disabled": (dict(enabled=False), False),
                 "disabled_but_pending": (dict(enabled=False, refresh_next_start=True), True),
                 "enabled_without_baseline": (dict(last_release=""), True),
                 "enabled_and_pending": (dict(refresh_next_start=True), True)}
        for case, (values, reported) in cases.items():
            with self.subTest(case=case):
                path = self.root / refresh.POLICY
                path.unlink(missing_ok=True)
                if values is not None:
                    config = dict(format=1, enabled=True, last_release="previous", refresh_next_start=False)
                    config.update(values)
                    path.write_text(json.dumps(config))
                content = path.read_bytes() if values is not None else None
                messages = []
                with patch.object(community, "DIRECTORY_SYNC", False), \
                     patch.object(community, "confirm_backup_durable") as confirm, \
                     patch.object(refresh, "atomic_bytes") as write, \
                     patch.object(refresh.terrain_storage, "prepare") as prepare:
                    self.assertIsNone(refresh.startup_check(self.root, messages.append))
                confirm.assert_not_called()
                write.assert_not_called()
                prepare.assert_not_called()
                self.assertEqual(messages != [], reported)
                if reported:
                    self.assertIn("left unchanged and is not acted on", messages[0])
                self.assertEqual(path.read_bytes() if values is not None else None, content)
                self.assertFalse(path.exists() and values is None)
                self.assert_original()
                self.assert_no_new_refresh()

    def test_startup_without_directory_sync_runs_normally_with_legacy_policy(self):
        self.policy(refresh_next_start=True)
        policy_bytes = (self.root / refresh.POLICY).read_bytes()
        with patch.object(community, "DIRECTORY_SYNC", False):
            before_java, messages = self.start()
        self.assertEqual(before_java, self.original)
        saved = [m for m in messages if "World backup saved before startup" in m]
        self.assertEqual(len(saved), 1)
        self.assertIn("cannot confirm", saved[0])
        self.assert_no_new_refresh(policy_bytes)

    def test_unsynchronized_startup_backup_stops_before_java(self):
        import errno
        import server as launcher
        self.policy()
        (self.root / "launcher.lock").unlink()

        def fail_backups_directory(path):
            if Path(path).name == "backups":
                raise OSError(errno.EIO, "injected directory sync failure")

        failures = {"file": patch.object(community, "sync_file", side_effect=OSError(errno.EIO, "injected file sync failure")),
                    "directory": patch.object(community, "sync_directory", side_effect=fail_backups_directory)}
        for case, failure in failures.items():
            with self.subTest(case=case):
                # Directory sync is forced on so injected failures are exercised on every platform.
                with patch.object(launcher, "check_ports"), patch.object(launcher, "emit") as emit, \
                     patch.object(launcher, "java_command") as java, \
                     patch.object(community, "DIRECTORY_SYNC", True), failure, \
                     patch.object(refresh, "startup_check") as startup_check:
                    with self.assertRaisesRegex(OSError, "injected " + case + " sync failure"):
                        launcher.run_server(launcher.parse_args([]), root=self.root, console=False)
                startup_check.assert_not_called()
                java.assert_not_called()
                self.assertFalse(any("backed up" in str(call) for call in emit.call_args_list))
                self.assert_original()
                self.assertEqual(self.refresh_folders(), [])
                self.assertEqual(list((self.root / "backups").glob("*.partial")), [])
                self.assertFalse((self.root / refresh.JOURNAL).exists())
                self.assertFalse((self.root / "launcher.lock").exists())

    # Interrupted transactions from earlier releases are still recovered first.

    def interrupt_move(self):
        original_replace = refresh.os.replace

        def replace(source, target):
            if Path(source).name == "poi":
                raise KeyboardInterrupt()
            return original_replace(source, target)
        with patch.object(refresh.os, "replace", side_effect=replace):
            with self.assertRaises(KeyboardInterrupt):
                self.transaction()
        self.assertTrue((self.root / refresh.JOURNAL).exists())

    def test_interrupted_move_is_rolled_back_before_backup_and_never_relaunched(self):
        self.policy(refresh_next_start=True)
        self.interrupt_move()
        folders = self.refresh_folders()
        self.assertEqual(len(folders), 1)
        policy_bytes = (self.root / refresh.POLICY).read_bytes()
        self.update_mod("later release", "2.13.0-explore.40")
        # Only the start that finds the journal reports the rollback; neither start refreshes.
        for reported in (1, 0):
            before_java, messages = self.start()
            self.assertEqual(before_java, self.original)
            self.assert_no_new_refresh(policy_bytes, folders)
            self.assertEqual(sum("Rolled back an interrupted terrain refresh" in m for m in messages), reported)
        record = json.loads((folders[0] / "MANIFEST.json").read_text())
        self.assertEqual(record["state"], "rolled_back")
        fresh = max((self.root / "backups").glob("Infinity-World-*.zip"), key=lambda p: p.stat().st_mtime_ns)
        refresh.verify_backup(self.root, self.world, fresh.name)

    def test_committed_earlier_transaction_is_finalized_without_another_refresh(self):
        self.policy(refresh_next_start=True)
        original_write = refresh.atomic_json

        def write(path, value):
            if path.name == refresh.POLICY and value.get("last_release") == refresh.release_id(self.root):
                raise KeyboardInterrupt()
            return original_write(path, value)
        with patch.object(refresh, "atomic_json", side_effect=write):
            with self.assertRaises(KeyboardInterrupt):
                self.transaction()
        self.assertEqual(json.loads((self.root / refresh.JOURNAL).read_text())["state"], "committed")
        folders = self.refresh_folders()
        after_earlier_refresh = self.snapshot()
        self.update_mod("later release", "2.13.0-explore.41")
        before_java, messages = self.start()
        self.assertEqual(before_java, after_earlier_refresh)
        self.assertTrue(any("Finished recording a terrain refresh completed by an earlier start" in m
                            and refresh.POLICY + " was updated" in m for m in messages))
        policy = refresh.read_policy(self.root)
        self.assertTrue(policy["enabled"])
        self.assertFalse(policy["refresh_next_start"])
        policy_bytes = (self.root / refresh.POLICY).read_bytes()
        self.assertEqual(self.start()[0], after_earlier_refresh)
        self.assert_no_new_refresh(policy_bytes, folders)
        record = json.loads((folders[0] / "MANIFEST.json").read_text())
        self.assertEqual(record["state"], "committed")

    def test_unrecoverable_journal_stops_startup_before_backup_or_java(self):
        import shutil
        import server as launcher
        self.policy()
        self.interrupt_move()
        record = json.loads((self.root / refresh.JOURNAL).read_text())
        shutil.rmtree(self.root / "backups" / record["rollback"] / "terrain" / record["moved_paths"][0])
        backups = set((self.root / "backups").glob("Infinity-World-*.zip"))
        (self.root / "launcher.lock").unlink()
        with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
             patch.object(launcher, "java_command") as java, patch.object(launcher, "Service") as service:
            with self.assertRaisesRegex(ValueError, "missing"):
                launcher.run_server(launcher.parse_args([]), root=self.root, console=False)
        java.assert_not_called()
        service.assert_not_called()
        self.assertTrue((self.root / refresh.JOURNAL).exists())
        self.assertEqual(set((self.root / "backups").glob("Infinity-World-*.zip")), backups)

    def test_startup_check_refuses_a_journal_that_was_not_recovered(self):
        self.policy()
        self.interrupt_move()
        with self.assertRaisesRegex(ValueError, "unrecovered"):
            refresh.startup_check(self.root, lambda _: None)
        self.assertTrue((self.root / refresh.JOURNAL).exists())

    def test_launcher_recovers_before_creating_its_fresh_startup_backup(self):
        self.policy()
        self.interrupt_move()
        (self.root / "launcher.lock").unlink()
        import server as launcher
        args = launcher.parse_args([])
        with patch.object(launcher, "check_ports"), \
             patch.object(launcher, "java_command", side_effect=InterruptedError), \
             patch.object(launcher, "emit"):
            launcher.run_server(args, root=self.root, console=False)
        self.assert_original()
        fresh = max((self.root / "backups").glob("Infinity-World-*.zip"), key=lambda p: p.stat().st_mtime_ns)
        refresh.verify_backup(self.root, self.world, fresh.name)
        self.assertFalse((self.root / refresh.JOURNAL).exists())

    # The retained earlier transaction, which defines the recovery format.

    def test_transaction_archives_terrain_and_keeps_personal_entity_data_exact(self):
        self.policy()
        rollback = self.transaction()
        manifest = json.loads((rollback / "MANIFEST.json").read_text())
        self.assertEqual(manifest["state"], "committed")
        for name, data in self.original.items():
            moved = any(name == p or name.startswith(p + "/") for p in manifest["moved_paths"])
            self.assertEqual((rollback / "terrain" / name if moved else self.world / name).read_bytes(), data)
        self.assertFalse((self.root / refresh.JOURNAL).exists())
        self.assertFalse(refresh.read_policy(self.root)["refresh_next_start"])

    def test_generation_markers_survive_with_protected_partial_courses(self):
        self.policy()
        rollback = self.transaction()
        manifest = json.loads((rollback / "MANIFEST.json").read_text())
        for name in refresh.MARKERS:
            self.assertEqual((self.world / name).read_bytes(), self.original[name])
            self.assertIn(name, manifest["preserved_files"])
            self.assertNotIn(name, manifest["moved_paths"])

    def test_nested_dimension_is_archived_by_transaction(self):
        nested = self.world / "dimensions/infinity/games/hub/region/r.0.0.mca"
        nested.parent.mkdir(parents=True)
        nested.write_bytes(b"nested saved terrain")
        self.backup = community.backup(self.root, {"level-name": "world"}, locked=True)
        self.policy()
        rollback = self.transaction()
        self.assertFalse(nested.exists())
        self.assertEqual((rollback / "terrain" / nested.relative_to(self.world)).read_bytes(), b"nested saved terrain")

    def test_dimension_named_region_does_not_archive_its_metadata(self):
        dimension = self.world / "dimensions/infinity/region"
        terrain = dimension / "region/r.0.0.mca"
        terrain.parent.mkdir(parents=True)
        terrain.write_bytes(b"saved terrain")
        metadata = dimension / "data/minecraft/world_border.dat"
        metadata.parent.mkdir(parents=True)
        metadata.write_bytes(b"retained border")
        self.backup = community.backup(self.root, {"level-name": "world"}, locked=True)
        self.policy()
        self.transaction()
        self.assertEqual(metadata.read_bytes(), b"retained border")
        self.assertFalse(terrain.exists())

    def test_missing_or_corrupt_backup_stops_before_moving_anything(self):
        self.policy()
        with self.assertRaises(ValueError):
            refresh.regenerate(self.root, "world", None)
        (self.root / "backups" / self.backup).write_bytes(b"corrupt ZIP")
        with self.assertRaises(Exception):
            self.transaction()
        self.assert_original()

    def test_stale_backup_rejects_changed_player_data(self):
        self.policy()
        path = self.world / "players/data/player.dat"
        path.write_bytes(b"new inventory")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.transaction()
        self.assertEqual(path.read_bytes(), b"new inventory")
        self.assertTrue((self.world / "dimensions/minecraft/overworld/region/r.0.0.mca").exists())

    def test_malformed_policy_and_wrong_mod_checksum_stop_transaction(self):
        self.policy(enabled="yes")
        with self.assertRaisesRegex(ValueError, "policy"):
            self.transaction()
        self.policy()
        (self.root / "fabric/mods/Infinity-Armor-test.jar").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "installed Infinity"):
            self.transaction()
        self.assert_original()

    def test_failure_mid_move_rolls_back_every_chunk_and_policy(self):
        original_policy = self.policy()
        original_replace = refresh.os.replace
        failed = False
        def replace(source, target):
            nonlocal failed
            if Path(source).name == "poi" and not failed:
                failed = True
                raise OSError("disk error")
            return original_replace(source, target)
        with patch.object(refresh.os, "replace", side_effect=replace):
            with self.assertRaisesRegex(OSError, "disk error"):
                self.transaction()
        self.assert_original()
        self.assertEqual(refresh.read_policy(self.root), original_policy)
        self.assertFalse((self.root / refresh.JOURNAL).exists())

    def test_interrupted_move_recovery_preserves_owner_policy_edits(self):
        self.policy()
        self.interrupt_move()
        disabled = self.policy(enabled=False)
        refresh.recover(self.root)
        self.assert_original()
        self.assertEqual(refresh.read_policy(self.root), disabled)
        record = json.loads(next((self.root / "backups").glob("Terrain-Refresh-*/MANIFEST.json")).read_text())
        self.assertEqual(record["state"], "rolled_back")

    def test_missing_archived_and_original_terrain_blocks_recovery(self):
        self.policy()
        self.interrupt_move()
        record = json.loads((self.root / refresh.JOURNAL).read_text())
        import shutil
        archived = self.root / "backups" / record["rollback"] / "terrain" / record["moved_paths"][0]
        shutil.rmtree(archived)
        with self.assertRaisesRegex(ValueError, "missing"):
            refresh.recover(self.root)
        self.assertTrue((self.root / refresh.JOURNAL).exists())

    def assert_refresh_did_not_start(self, policy):
        self.assert_original()
        self.assertEqual(refresh.read_policy(self.root), policy)
        self.assertFalse((self.root / refresh.JOURNAL).exists())
        self.assertEqual(self.refresh_folders(), [])

    def test_unconfirmed_backup_blocks_transaction_before_any_terrain_change(self):
        policy = self.policy()
        with patch.object(community, "DIRECTORY_SYNC", False):
            self.backup = community.backup(self.root, {"level-name": "world"}, locked=True)
            with patch.object(refresh.terrain_storage, "prepare") as prepare:
                with self.assertRaisesRegex(community.DurabilityError, "terrain refresh was not performed"):
                    self.transaction()
        prepare.assert_not_called()
        self.assert_refresh_did_not_start(policy)

    def test_injected_backup_sync_errors_block_transaction_on_every_platform(self):
        import errno
        policy = self.policy()

        def fail_backups_directory(path):
            if Path(path).name == "backups":
                raise OSError(errno.EIO, "injected directory sync failure")

        failures = {"archive": patch.object(community, "flush_descriptor",
                                            side_effect=OSError(errno.EIO, "injected archive sync failure")),
                    "directory": patch.object(community, "sync_directory", side_effect=fail_backups_directory)}
        for case, failure in failures.items():
            with self.subTest(case=case):
                with patch.object(community, "DIRECTORY_SYNC", True), failure, \
                     patch.object(refresh.terrain_storage, "prepare") as prepare:
                    with self.assertRaisesRegex(OSError, "injected " + case + " sync failure"):
                        self.transaction()
                prepare.assert_not_called()
                self.assert_refresh_did_not_start(policy)

    def test_transaction_confirms_the_verified_backup_before_preparing_terrain(self):
        self.policy()
        events = []
        prepare = refresh.terrain_storage.prepare
        with patch.object(community, "confirm_backup_durable",
                          side_effect=lambda root, name: events.append(("confirm", name))), \
             patch.object(refresh.terrain_storage, "prepare",
                          side_effect=lambda world: events.append("prepare") or prepare(world)):
            self.assertIsNotNone(self.transaction())
        self.assertEqual(events, [("confirm", self.backup), "prepare"])

    def test_interrupted_protected_chunk_restore_rolls_back_complete_original_world(self):
        self.policy()
        write = refresh.atomic_bytes
        def interrupted(path, value):
            if path.is_relative_to(self.world.resolve()) and path.name == "r.0.0.mca":
                write(path, value)
                (path.parent / (path.name + ".refresh-tmp-interrupted")).write_bytes(b"partial write")
                raise KeyboardInterrupt()
            return write(path, value)
        with patch.object(refresh, "atomic_bytes", side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.transaction()
        refresh.recover(self.root)
        self.assert_original()

    def test_interrupted_protected_chunk_restore_is_recovered_by_ordinary_start(self):
        self.policy(refresh_next_start=True)
        write = refresh.atomic_bytes
        def interrupted(path, value):
            if path.is_relative_to(self.world.resolve()) and path.name == "r.0.0.mca":
                write(path, value)
                (path.parent / (path.name + ".refresh-tmp-interrupted")).write_bytes(b"partial write")
                raise KeyboardInterrupt()
            return write(path, value)
        with patch.object(refresh, "atomic_bytes", side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                self.transaction()
        folders = self.refresh_folders()
        self.assertEqual(self.start()[0], self.original)
        self.assert_no_new_refresh(folders=folders)

    def test_legacy_layout_is_rejected_and_lock_required(self):
        self.policy()
        (self.root / "launcher.lock").unlink()
        with self.assertRaisesRegex(ValueError, "locked"):
            self.transaction()
        (self.root / "launcher.lock").write_text("lease")
        import shutil
        shutil.rmtree(self.world / "players")
        with self.assertRaisesRegex(ValueError, "26.3"):
            self.transaction()

    def test_world_escape_and_symlink_are_rejected(self):
        self.policy()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            refresh.regenerate(self.root, "../world", self.backup)
        (self.world / "escape").symlink_to(self.root / "dependencies.lock.json")
        with self.assertRaises(ValueError):
            self.transaction()


if __name__ == "__main__":
    unittest.main()
