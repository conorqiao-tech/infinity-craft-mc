#!/usr/bin/env python3
"""Collect and verify complementary macOS and Linux launcher test receipts."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAC_ONLY = {'test_community.CommunityTests.test_macos_full_sync_succeeds_on_files_and_directories'}
LINUX_ONLY = {'test_native_lock_reentry.NativeLockReentryTests.' + name for name in (
    'test_launcher_death_releases_lock_while_holder_is_suspended',
    'test_linux_watchdog_must_arm_before_opening_world_lock',
    'test_linux_watchdog_that_never_arms_is_reaped_before_backup_returns',
    'test_linux_unacknowledged_watchdog_is_reaped_before_backup_fails',
    'test_linux_watchdog_missing_shutdown_ack_is_reaped_before_backup_returns',
    'test_linux_watchdog_stalled_after_shutdown_ack_is_reaped_before_backup_returns',
    'test_watchdog_death_keeps_the_lock_until_the_launcher_releases',
)}
MAC_SKIP = 'requires the Linux death watchdog'


def inputs(root, paths):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(paths)}


def launcher_sources(root):
    return sorted((root / 'server').glob('*.py'))


def receipt_inputs(root, platform):
    paths = launcher_sources(root)
    if platform == 'linux':
        paths.append(root / 'tests/test_native_lock_reentry.py')
    else:
        paths += sorted((root / 'tests').glob('test_*.py'))
        paths += sorted(p for p in (root / 'tests/fixtures').rglob('*') if p.is_file())
    paths.append(root / 'tools/release_python_tests.py')
    return inputs(root, paths)


def methods(suite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from methods(item)
        else:
            yield item.id()


def test_suite(root, platform):
    loader = unittest.TestLoader()
    if platform == 'linux':
        sys.path.insert(0, str(root / 'tests'))
        return loader.loadTestsFromNames(sorted(LINUX_ONLY))
    return loader.discover(str(root / 'tests'), pattern='test_*.py')


def expected_outcomes(test_ids, platform):
    return {k: {'status': 'skipped', 'reason': MAC_SKIP}
            if platform == 'darwin' and k in LINUX_ONLY else {'status': 'passed'}
            for k in test_ids}


def verify_receipt(root, receipt, platform):
    suite_ids = list(methods(test_suite(root, platform)))
    if len(suite_ids) != len(set(suite_ids)) or not suite_ids:
        raise ValueError('Missing or duplicate launcher test IDs')
    if platform == 'darwin' and not MAC_ONLY | LINUX_ONLY <= set(suite_ids):
        raise ValueError('Platform-specific tests are missing from discovery')
    expected = {'format': 1, 'platform': platform, 'inputs': receipt_inputs(root, platform),
                'tests': expected_outcomes(suite_ids, platform)}
    if receipt != expected:
        different = sorted(k for k in set(expected['tests']) | set(receipt.get('tests', {}))
                           if expected['tests'].get(k) != receipt.get('tests', {}).get(k))
        raise ValueError(f'{platform} launcher receipt changed or contains unexpected results: {different}')


class ReceiptResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.outcomes = {}

    def record(self, test, value):
        if test.id() in self.outcomes:
            raise ValueError(f'Duplicate result: {test.id()}')
        self.outcomes[test.id()] = value

    def addSuccess(self, test):
        super().addSuccess(test)
        self.record(test, {'status': 'passed'})

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self.record(test, {'status': 'skipped', 'reason': reason})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    platform = sys.platform
    if platform not in ('darwin', 'linux'):
        raise SystemExit('Collect release launcher evidence on macOS and Linux')
    suite = test_suite(ROOT, platform)
    frozen = receipt_inputs(ROOT, platform)
    result = unittest.TextTestRunner(verbosity=2, resultclass=ReceiptResult).run(suite)
    if not result.wasSuccessful():
        raise SystemExit(1)
    receipt = {'format': 1, 'platform': platform, 'inputs': frozen, 'tests': result.outcomes}
    verify_receipt(ROOT, receipt, platform)
    with args.output.open('x', encoding='utf-8') as output:
        json.dump(receipt, output, sort_keys=True, indent=2)
        output.write('\n')


if __name__ == '__main__':
    main()
