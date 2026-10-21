"""Reject altered/mislabeled experimental dependency sources before packaging."""
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import snapshot5_dependencies as pins


class SnapshotDependencyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ROOT / "compatibility/snapshot5", self.root / "compatibility/snapshot5")
        (self.root / "tools").mkdir()
        for name in ("snapshot5_dependencies.py", "build_snapshot5_dependencies.py"):
            shutil.copy2(ROOT / "tools" / name, self.root / "tools" / name)

    def test_source_proof_binds_all_patches_licenses_and_rebuild_tools(self):
        inputs = pins.snapshot_inputs(self.root)
        self.assertEqual(len(inputs), 14)
        self.assertIn("tools/build_snapshot5_dependencies.py", inputs)
        for project in pins.bounded_json(self.root / "compatibility/snapshot5/sources.json")["projects"]:
            self.assertIn("compatibility/snapshot5/" + project["patch"], inputs)
            self.assertIn("compatibility/snapshot5/" + project["license"], inputs)
        patch = self.root / "compatibility/snapshot5/polymer.patch"
        patch.write_bytes(patch.read_bytes() + b"\nchanged source\n")
        with self.assertRaisesRegex(ValueError, "Changed local-port patch"):
            pins.snapshot_inputs(self.root)

    def test_mislabelled_or_extra_artifact_cannot_enter_bundle(self):
        path = self.root / "compatibility/snapshot5/artifacts.json"
        original = pins.bounded_json(path)
        for mutation in ("version", "extra", "hash"):
            manifest = copy.deepcopy(original)
            if mutation == "version": manifest["minecraft"] = "26.3"
            if mutation == "extra": manifest["sha256"]["unreviewed.jar"] = "0" * 64
            if mutation == "hash": manifest["sha256"][next(iter(manifest["sha256"]))] = "not-a-hash"
            path.write_text(json.dumps(manifest))
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                pins.fork_artifacts(self.root)

    def test_local_ports_have_no_upstream_download_substitute(self):
        with self.assertRaisesRegex(ValueError, "Missing or oversized"):
            pins.staged_forks(self.root)
        lock = {"minecraft": pins.MINECRAFT, "snapshot5": {"experimental": True, "protocol": pins.PROTOCOL,
                "source_inputs": pins.snapshot_inputs(self.root)}, "downloads": [],
                "bundled": [{"path": "fabric/mods/" + name, "sha256": value}
                            for name, value in pins.fork_artifacts(self.root).items()]}
        pins.verify_lock(lock, self.root)
        lock["bundled"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "exact reviewed local ports"):
            pins.verify_lock(lock, self.root)

    def test_duplicate_oversized_or_escaping_manifest_is_rejected(self):
        path = self.root / "duplicate.json"
        path.write_text('{"sha256":{},"sha256":{}}')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            pins.bounded_json(path)
        path.write_bytes(b" " * (2 * 1024 * 1024 + 1))
        with self.assertRaisesRegex(ValueError, "oversized"):
            pins.bounded_json(path)
        source = self.root / "compatibility/snapshot5/sources.json"
        manifest = pins.bounded_json(source)
        manifest["projects"][0]["patch"] = "../../outside.patch"
        source.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "escapes"):
            pins.snapshot_inputs(self.root)
