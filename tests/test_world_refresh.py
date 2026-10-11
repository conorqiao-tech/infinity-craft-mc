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

    def write_mod(self, path, content):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("fabric.mod.json", '{"version":"2.13.0-explore.24"}')
            archive.writestr("content.txt", content)

    def policy(self, **values):
        config = dict(format=1, enabled=True, last_release="previous", refresh_next_start=False)
        config.update(values)
        refresh.atomic_json(self.root / refresh.POLICY, config)
        return config

    def apply(self):
        return refresh.maybe_refresh(self.root, "world", self.backup, lambda _: None)

    def assert_original(self):
        self.assertEqual({p.relative_to(self.world).as_posix(): p.read_bytes()
                          for p in self.world.rglob("*") if p.is_file()}, self.original)

    def test_public_default_and_disabled_policy_preserve_world(self):
        self.assertIsNone(self.apply())
        self.policy(enabled=False, refresh_next_start=True)
        self.assertIsNone(self.apply())
        self.assert_original()

    def test_enabling_records_baseline_without_erasing_current_terrain(self):
        self.policy(last_release="")
        self.assertIsNone(self.apply())
        self.assertEqual(refresh.read_policy(self.root)["last_release"], refresh.release_id(self.root))
        self.assert_original()

    def test_changed_release_archives_terrain_and_keeps_personal_entity_data_exact(self):
        self.policy()
        rollback = self.apply()
        manifest = json.loads((rollback / "MANIFEST.json").read_text())
        self.assertEqual(manifest["state"], "committed")
        for name, data in self.original.items():
            moved = any(name == p or name.startswith(p + "/") for p in manifest["moved_paths"])
            self.assertEqual((rollback / "terrain" / name if moved else self.world / name).read_bytes(), data)
        self.assertFalse((self.root / refresh.JOURNAL).exists())
        self.assertFalse(refresh.read_policy(self.root)["refresh_next_start"])

    def test_same_release_restart_does_not_clear_new_terrain_again(self):
        self.policy()
        self.apply()
        new = self.world / "dimensions/minecraft/overworld/region/new.mca"
        new.parent.mkdir(parents=True)
        new.write_bytes(b"new build")
        self.assertIsNone(self.apply())
        self.assertEqual(new.read_bytes(), b"new build")

    def test_generation_markers_survive_with_protected_partial_courses(self):
        self.policy()
        rollback = self.apply()
        manifest = json.loads((rollback / "MANIFEST.json").read_text())
        for name in refresh.MARKERS:
            self.assertEqual((self.world / name).read_bytes(), self.original[name])
            self.assertIn(name, manifest["preserved_files"])
            self.assertNotIn(name, manifest["moved_paths"])

    def test_manual_refresh_can_run_once_on_same_release(self):
        self.policy(last_release=refresh.release_id(self.root), refresh_next_start=True)
        self.assertIsNotNone(self.apply())
        self.assertIsNone(self.apply())

    def test_changed_mod_checksum_is_an_update_even_with_same_version(self):
        old = refresh.release_id(self.root)
        self.policy(last_release=old)
        path = self.root / "fabric/mods/Infinity-Armor-test.jar"
        self.write_mod(path, "updated installed mod")
        lock = json.loads((self.root / "dependencies.lock.json").read_text())
        lock["bundled"][0]["sha256"] = refresh.digest(path)
        (self.root / "dependencies.lock.json").write_text(json.dumps(lock))
        self.assertIsNotNone(self.apply())

    def test_package_version_change_without_mod_change_keeps_terrain(self):
        self.policy(last_release=refresh.release_id(self.root))
        lock = json.loads((self.root / "dependencies.lock.json").read_text())
        lock["version"] = "launcher-only-update"
        (self.root / "dependencies.lock.json").write_text(json.dumps(lock))
        self.assertIsNone(self.apply())
        self.assert_original()

    def test_nested_dimension_is_refreshed(self):
        nested = self.world / "dimensions/infinity/games/hub/region/r.0.0.mca"
        nested.parent.mkdir(parents=True)
        nested.write_bytes(b"nested saved terrain")
        self.backup = community.backup(self.root, {"level-name": "world"}, locked=True)
        self.policy()
        rollback = self.apply()
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
        self.apply()
        self.assertEqual(metadata.read_bytes(), b"retained border")
        self.assertFalse(terrain.exists())

    def test_missing_or_corrupt_backup_stops_before_moving_anything(self):
        self.policy()
        with self.assertRaises(ValueError):
            refresh.maybe_refresh(self.root, "world", None)
        (self.root / "backups" / self.backup).write_bytes(b"corrupt ZIP")
        with self.assertRaises(Exception):
            self.apply()
        self.assert_original()

    def test_stale_backup_rejects_changed_player_data(self):
        self.policy()
        path = self.world / "players/data/player.dat"
        path.write_bytes(b"new inventory")
        with self.assertRaisesRegex(ValueError, "differs"):
            self.apply()
        self.assertEqual(path.read_bytes(), b"new inventory")
        self.assertTrue((self.world / "dimensions/minecraft/overworld/region/r.0.0.mca").exists())

    def test_malformed_policy_and_wrong_mod_checksum_stop_before_regeneration(self):
        self.policy(enabled="yes")
        with self.assertRaisesRegex(ValueError, "policy"):
            self.apply()
        self.policy()
        (self.root / "fabric/mods/Infinity-Armor-test.jar").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "installed Infinity"):
            self.apply()
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
                self.apply()
        self.assert_original()
        self.assertEqual(refresh.read_policy(self.root), original_policy)
        self.assertFalse((self.root / refresh.JOURNAL).exists())

    def test_interrupted_move_is_recovered_on_next_start(self):
        original_policy = self.policy()
        original_replace = refresh.os.replace
        def replace(source, target):
            if Path(source).name == "poi":
                raise KeyboardInterrupt()
            return original_replace(source, target)
        with patch.object(refresh.os, "replace", side_effect=replace):
            with self.assertRaises(KeyboardInterrupt):
                self.apply()
        self.assertTrue((self.root / refresh.JOURNAL).exists())
        disabled = self.policy(enabled=False)
        refresh.recover(self.root)
        self.assert_original()
        self.assertEqual(refresh.read_policy(self.root), disabled)
        record = json.loads(next((self.root / "backups").glob("Terrain-Refresh-*/MANIFEST.json")).read_text())
        self.assertEqual(record["state"], "rolled_back")

    def test_committed_refresh_is_finalized_without_repeating_after_interruption(self):
        self.policy()
        original_write = refresh.atomic_json
        def write(path, value):
            if path.name == refresh.POLICY and value.get("last_release") == refresh.release_id(self.root):
                raise KeyboardInterrupt()
            return original_write(path, value)
        with patch.object(refresh, "atomic_json", side_effect=write):
            with self.assertRaises(KeyboardInterrupt):
                self.apply()
        self.assertEqual(json.loads((self.root / refresh.JOURNAL).read_text())["state"], "committed")
        self.assertIsNone(self.apply())
        self.assertFalse((self.root / refresh.JOURNAL).exists())
        self.assertEqual(refresh.read_policy(self.root)["last_release"], refresh.release_id(self.root))
        record = json.loads(next((self.root / "backups").glob("Terrain-Refresh-*/MANIFEST.json")).read_text())
        self.assertEqual(record["state"], "committed")

    def test_missing_archived_and_original_terrain_blocks_recovery(self):
        self.policy()
        original_replace = refresh.os.replace
        def replace(source, target):
            if Path(source).name == "poi":
                raise KeyboardInterrupt()
            return original_replace(source, target)
        with patch.object(refresh.os, "replace", side_effect=replace):
            with self.assertRaises(KeyboardInterrupt):
                self.apply()
        record = json.loads((self.root / refresh.JOURNAL).read_text())
        import shutil
        archived = self.root / "backups" / record["rollback"] / "terrain" / record["moved_paths"][0]
        shutil.rmtree(archived)
        with self.assertRaisesRegex(ValueError, "missing"):
            refresh.recover(self.root)
        self.assertTrue((self.root / refresh.JOURNAL).exists())

    def test_launcher_recovers_before_creating_its_fresh_startup_backup(self):
        self.policy()
        original_replace = refresh.os.replace
        def replace(source, target):
            if Path(source).name == "poi":
                raise KeyboardInterrupt()
            return original_replace(source, target)
        with patch.object(refresh.os, "replace", side_effect=replace):
            with self.assertRaises(KeyboardInterrupt):
                self.apply()
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
                self.apply()
        refresh.recover(self.root)
        self.assert_original()

    def test_legacy_layout_is_rejected_and_lock_required(self):
        self.policy()
        (self.root / "launcher.lock").unlink()
        with self.assertRaisesRegex(ValueError, "locked"):
            self.apply()
        (self.root / "launcher.lock").write_text("lease")
        import shutil
        shutil.rmtree(self.world / "players")
        with self.assertRaisesRegex(ValueError, "26.3"):
            self.apply()

    def test_world_escape_and_symlink_are_rejected(self):
        self.policy()
        with self.assertRaisesRegex(ValueError, "Unsafe"):
            refresh.maybe_refresh(self.root, "../world", self.backup)
        (self.world / "escape").symlink_to(self.root / "dependencies.lock.json")
        with self.assertRaises(ValueError):
            self.apply()
