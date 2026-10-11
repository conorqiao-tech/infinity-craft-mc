"""Keep real-codec fixtures isolated without omitting registered regressions."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import check_crossplay


class NativeGroupTests(unittest.TestCase):
    def test_every_registration_occurs_in_exactly_one_disposable_group(self):
        manifest = {"entrypoints": {"fabric-gametest": ["A", "B", "C"]},
                    "custom": {"infinity-isolated-gametests": ["C", "B"]}}
        self.assertEqual(check_crossplay.test_groups(manifest), [("default", ["A"]), ("native-play", ["C", "B"])])
        for invalid in (["D"], ["B", "B"], "B", [True]):
            manifest["custom"]["infinity-isolated-gametests"] = invalid
            with self.assertRaises(ValueError): check_crossplay.test_groups(manifest)

    def test_selection_preserves_original_jar_codecs_probes_and_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "original.jar"; selected = Path(directory) / "selected.jar"
            manifest = {"entrypoints": {"fabric-gametest": ["A", "B"], "main": ["M"]},
                        "mixins": ["probe.json"], "custom": {"kept": True}}
            with zipfile.ZipFile(source, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps(manifest))
                jar.writestr("native.class", b"complete original test bytecode")
                jar.writestr("fixture.json", b'{"world":"isolated"}')
            before = hashlib.sha256(source.read_bytes()).digest()
            check_crossplay.selected_test_jar(source, selected, ["B"])
            self.assertEqual(hashlib.sha256(source.read_bytes()).digest(), before)
            with zipfile.ZipFile(source) as original, zipfile.ZipFile(selected) as jar:
                current = json.loads(jar.read("fabric.mod.json")); current["entrypoints"]["fabric-gametest"] = ["A", "B"]
                self.assertEqual(current, manifest)
                for name in ("native.class", "fixture.json"):
                    self.assertEqual(jar.read(name), original.read(name))
