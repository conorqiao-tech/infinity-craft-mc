"""The grouped native runner must fail on incomplete or false-green evidence."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from check_native_gametests import group_cases, validate_total


class NativeCheckTests(unittest.TestCase):
    def test_requires_unique_complete_clean_group_and_vanilla_control(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            vanilla = '<testcase name="minecraft:always_pass"/>'
            project = '<testcase name="convergence_tests:case"/>'
            report.write_text(f"<testsuite>{vanilla}{project}</testsuite>")
            self.assertEqual(group_cases(report, 2), ["convergence_tests:case"])
            for body in (vanilla, project * 2, vanilla * 2, vanilla + "<testcase/>",
                         *(vanilla + f'<testcase name="x"><{kind}/></testcase>'
                           for kind in ("failure", "error", "skipped"))):
                report.write_text(f"<testsuite>{body}</testsuite>")
                with self.subTest(body=body), self.assertRaises(ValueError):
                    group_cases(report, 2)

    def test_total_counts_control_only_once_and_rejects_duplicate_cases(self):
        self.assertEqual(validate_total([["a"], ["b", "c"]], 4), 4)
        for groups, total in (([["a"], ["a"]], 3), ([["a"], ["b"]], 4)):
            with self.assertRaises(ValueError):
                validate_total(groups, total)
