"""Keep expensive opt-in measurements separate without hiding missing regressions."""
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import prepare_crossplay


class NativeRegistrationTests(unittest.TestCase):
    def fixture(self, root, registered, opt_in=None):
        resources = root / "java/src/gametest/resources"
        sources = root / "java/src/gametest/java/dev/convergence"
        resources.mkdir(parents=True)
        sources.mkdir(parents=True)
        (sources / "Regression.java").write_text("@GameTest void first() {}\n@GameTest void second() {}\n")
        (sources / "Benchmark.java").write_text("@GameTest void expensiveMeasurement() {}\n")
        manifest = {"entrypoints": {"fabric-gametest": registered}}
        if opt_in is not None:
            manifest["custom"] = {"infinity-opt-in-gametests": opt_in}
        (resources / "fabric.mod.json").write_text(json.dumps(manifest))

    def test_explicit_benchmark_does_not_inflate_regular_native_count(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, ["dev.convergence.Regression"], ["dev.convergence.Benchmark"])
            with patch.object(prepare_crossplay, "ROOT", root):
                self.assertEqual(prepare_crossplay.expected_native_tests(), 3)

    def test_undeclared_annotated_class_still_fails_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, ["dev.convergence.Regression"])
            with patch.object(prepare_crossplay, "ROOT", root), self.assertRaises(AssertionError):
                prepare_crossplay.expected_native_tests()

    def test_benchmark_cannot_also_run_in_the_regular_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, ["dev.convergence.Regression", "dev.convergence.Benchmark"], ["dev.convergence.Benchmark"])
            with patch.object(prepare_crossplay, "ROOT", root), self.assertRaises(AssertionError):
                prepare_crossplay.expected_native_tests()
