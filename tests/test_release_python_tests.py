import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import release_python_tests as release


class ReleasePythonReceiptTests(unittest.TestCase):
    def setUp(self):
        self.platforms = {'darwin': sorted(release.MAC_ONLY | release.LINUX_ONLY | {'other.test'}),
                          'linux': sorted(release.LINUX_ONLY)}
        self.patches = [
            patch.object(release, 'test_suite', side_effect=lambda _, platform: self.platforms[platform]),
            patch.object(release, 'methods', side_effect=iter),
            patch.object(release, 'receipt_inputs', return_value={'source.py': 'pinned'}),
        ]
        for stub in self.patches:
            stub.start()
            self.addCleanup(stub.stop)

    def receipt(self, platform):
        return {'format': 1, 'platform': platform, 'inputs': {'source.py': 'pinned'},
                'tests': release.expected_outcomes(self.platforms[platform], platform)}

    def test_requires_all_platform_results(self):
        for platform in self.platforms:
            release.verify_receipt(Path('.'), self.receipt(platform), platform)
        for key in ('inputs', 'tests'):
            invalid = copy.deepcopy(self.receipt('linux'))
            invalid[key].pop(next(iter(invalid[key])))
            with self.assertRaisesRegex(ValueError, 'changed or contains unexpected'):
                release.verify_receipt(Path('.'), invalid, 'linux')

    def test_unrelated_skip_is_not_a_platform_exception(self):
        receipt = self.receipt('darwin')
        receipt['tests']['other.test'] = {'status': 'skipped', 'reason': release.MAC_SKIP}
        with self.assertRaisesRegex(ValueError, 'other.test'):
            release.verify_receipt(Path('.'), receipt, 'darwin')

    def test_duplicate_or_removed_expected_test_rejected(self):
        for platform in self.platforms:
            with self.subTest(platform=platform):
                receipt = self.receipt(platform)
                self.platforms[platform].append(self.platforms[platform][0])
                with self.assertRaisesRegex(ValueError, 'duplicate'):
                    release.verify_receipt(Path('.'), receipt, platform)
                self.platforms[platform].pop()
        self.platforms['darwin'].remove(next(iter(release.MAC_ONLY)))
        with self.assertRaisesRegex(ValueError, 'missing'):
            release.verify_receipt(Path('.'), self.receipt('darwin'), 'darwin')
