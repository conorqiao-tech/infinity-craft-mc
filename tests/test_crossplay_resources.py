"""Regression checks for the native elytra resource-only repair."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("crossplay_resources", ROOT / "tools/crossplay_resources.py")
resources = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resources)


class ResourceRepairTests(unittest.TestCase):
    def test_repair_removes_global_elytra_overrides_and_preserves_custom_content(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "old.mcpack", Path(directory) / "fixed.mcpack"
            manifest = {"header": {"uuid": "unchanged-header", "version": [2, 12, 39]},
                        "modules": [{"uuid": "unchanged-module", "version": [2, 12, 39], "type": "resources"}]}
            files = {"manifest.json": json.dumps(manifest).encode(),
                     "attachables/elytra.json": b"old model", "textures/items/elytra.png": b"old icon",
                     "textures/items/broken_elytra.png": b"old broken icon",
                     "textures/convergence/items/refresh27/chestplate.png": b"Infinity Creative icon",
                     "attachables/refresh27/cuirass.json": b"independent original armor",
                     "texts/en_US.lang": b"item.convergence.chestplate=Convergence Wings\n"}
            with zipfile.ZipFile(source, "w") as z:
                for name, data in files.items(): z.writestr(name, data)
            result = resources.repair_pack(source, target, "2.13.0-explore.39")
            self.assertEqual(result["version"], [2, 13, 39])
            self.assertEqual(set(result["removed"]), {"attachables/elytra.json", "textures/items/elytra.png", "textures/items/broken_elytra.png"})
            with zipfile.ZipFile(target) as z:
                expected = {n: v for n, v in files.items() if n not in result["removed"]}
                self.assertEqual(set(z.namelist()), set(expected))
                for name, data in expected.items():
                    if name != "manifest.json": self.assertEqual(z.read(name), data)
                fixed = json.loads(z.read("manifest.json"))
                self.assertEqual(fixed["header"]["uuid"], manifest["header"]["uuid"])
                self.assertEqual(fixed["modules"][0]["uuid"], manifest["modules"][0]["uuid"])
                self.assertEqual(fixed["modules"][0]["version"], [2, 13, 39])
            repeat = Path(directory) / "repeat.mcpack"
            self.assertEqual(resources.repair_pack(target, repeat, "2.13.0-explore.39")["removed"], [])
            self.assertEqual(target.read_bytes(), repeat.read_bytes(), "The deterministic repair must be idempotent")
            with zipfile.ZipFile(source) as z: self.assertIn("attachables/elytra.json", z.namelist())

    def test_repair_rejects_path_escape_without_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "bad.mcpack", Path(directory) / "fixed.mcpack"
            with zipfile.ZipFile(source, "w") as z: z.writestr("../outside", b"unsafe")
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                resources.repair_pack(source, target, "2.13.0-explore.39")
            self.assertFalse(target.exists())

    def test_repair_rejects_behavior_packs(self):
        with tempfile.TemporaryDirectory() as directory:
            source, target = Path(directory) / "behavior.mcpack", Path(directory) / "fixed.mcpack"
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("manifest.json", json.dumps({"header": {"version": [2, 12, 39]}, "modules": [{"type": "data"}]}))
            with self.assertRaisesRegex(ValueError, "standalone"):
                resources.repair_pack(source, target, "2.13.0-explore.39")
            self.assertFalse(target.exists())

    def test_cache_versions_follow_the_actual_release_minor_and_future_updates(self):
        self.assertGreater(resources.resource_version("2.13.0-explore.39"), [2, 12, 39])
        self.assertGreater(resources.resource_version("2.13.0-explore.40"), resources.resource_version("2.13.0-explore.39"))
        self.assertGreater(resources.resource_version("3.0.0-explore.1"), [2, 13, 40])
        with self.assertRaisesRegex(ValueError, "release version"):
            resources.resource_version("unknown")

    def test_resource_only_repairs_replace_cached_43_without_blocking_44(self):
        self.assertEqual(resources.resource_version("2.13.0-explore.42"), [2, 13, 42])
        self.assertEqual(resources.resource_version("2.13.0-explore.43"), [2, 13, 43001])
        self.assertGreater(resources.resource_version("2.13.0-explore.43"), [2, 13, 43])
        self.assertEqual(resources.resource_version("2.13.0-explore.44"), [2, 13, 44001])
        with patch.dict(resources.RESOURCE_REPAIRS, {}, clear=True):
            self.assertEqual(resources.resource_version("2.13.0-explore.43"), [2, 13, 43000])
        with patch.dict(resources.RESOURCE_REPAIRS, {(2, 13, 43): 999}):
            self.assertLess(resources.resource_version("2.13.0-explore.43"), resources.resource_version("2.13.0-explore.44"))
        for invalid in (-1, 1000, True, 1.5):
            with self.subTest(invalid=invalid), patch.dict(resources.RESOURCE_REPAIRS, {(2, 13, 43): invalid}):
                with self.assertRaisesRegex(ValueError, "between 0 and 999"):
                    resources.resource_version("2.13.0-explore.43")


if __name__ == "__main__":
    unittest.main()
