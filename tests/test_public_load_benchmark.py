"""Offline contract checks for the opt-in synthetic public-load diagnostic runner."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT/'tools'))
SPEC = importlib.util.spec_from_file_location('public_load_benchmark', PROJECT/'tools/benchmark_public_load.py')
load = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load)

WARMUP, SAMPLES = 20, 100


def distribution(low, high):
    return {'unit': 'nanoseconds', 'method': 'nearest-rank', 'samples': SAMPLES,
            'min': low, 'p50': low, 'p95': high, 'p99': high, 'max': high, 'mean': float(low)}


def raw_result():
    return {
        'status': 'passed', 'failure': None, 'schema': 2,
        'workload': {'fake_players': 8}, 'limits': {'warmup_ticks': WARMUP, 'sample_ticks': SAMPLES},
        'measurements': {
            'server_ticks': {'first': 200, 'last': 200 + SAMPLES - 1, 'recorded': SAMPLES},
            'start_to_end_callback_span_nanos': distribution(1_000_000, 4_000_000),
            'start_to_start_period_nanos': distribution(51_000_000, 55_000_000),
            'sampler_fault': None,
            'concurrency': {'expected': 8, 'samples': SAMPLES, 'connected_min': 8, 'connected_max': 8,
                            'active_min': 8, 'active_max': 8, 'server_players_min': 8, 'server_players_max': 8,
                            'disconnects_since_join': 0, 'menu_open_player_ticks': 0},
            'actions': {'moves_sent': 8*SAMPLES, 'moves_accepted': 8*SAMPLES, 'hotbar_changes': 20, 'hotbar_skipped': 0}},
        'cleanup': {'players_closed': 8, 'sampler_cleared': True, 'players_still_listed': 0,
                    'connections_still_registered': 0, 'channels_still_open': 0}}


REPORT = ('<testsuite>' + ''.join(f'<testcase name="case{i}"/>' for i in range(3)) + '</testsuite>')


def option(command, name):
    return Path(next(arg.split('=', 1)[1] for arg in command if arg.startswith('-D'+name+'=')))


class FakeLauncher:
    def __init__(self, raw=None, report=REPORT, log='[Server thread/INFO]: Done\n', code=0, failure=None):
        self.raw, self.report, self.log, self.code, self.failure = raw, report, log, code, failure
        self.calls = []

    def __call__(self, command, cwd, log_path, timeout):
        self.calls.append((command, Path(cwd), timeout))
        Path(log_path).write_text(self.log)
        if self.failure:
            raise self.failure
        if self.report is not None:
            option(command, 'fabric-api.gametest.report-file').write_text(self.report)
        if self.raw is not None:
            option(command, 'infinity.public-load.output').write_text(json.dumps(self.raw))
        return self.code


class PublicLoadRunnerTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for name in ('java', 'mod.jar', 'tests.jar'):
            (self.root/name).write_bytes(name.encode())
        self.runtime = self.root/'research/integration'
        self.runtime.mkdir(parents=True)
        self.output = self.root/'research/public-load/runs/1'
        patcher = patch.object(load, 'ROOT', self.root)
        patcher.start(); self.addCleanup(patcher.stop)
        patcher = patch.object(load, 'runtime_inputs', return_value={'libraries/x.jar': 'pinned'})
        patcher.start(); self.addCleanup(patcher.stop)
        self.prepared = []

    def prepare(self, source, target, mod, tests):
        self.prepared.append(Path(target))

    def run_main(self, launcher, output=None):
        argv = ['--java', str(self.root/'java'), '--mod', str(self.root/'mod.jar'), '--tests', str(self.root/'tests.jar'),
                '--runtime', str(self.runtime), '--output', str(output or self.output),
                '--warmup', str(WARMUP), '--samples', str(SAMPLES)]
        return load.main(argv, launcher=launcher, prepare=self.prepare, version=lambda java: 'openjdk version "25.0.1"')

    def assert_failed(self, launcher):
        self.assertEqual(self.run_main(launcher), 1)
        self.assertFalse((self.output/load.RECEIPT).exists(), 'A failed run must never leave a passing receipt')
        failure = json.loads((self.output/load.FAILURE).read_text())
        self.assertEqual(failure['status'], 'failed')
        self.assertEqual(list(self.output.glob('.*.partial')), [])
        return failure

    def test_success_writes_complete_integrity_receipt_without_capacity_claim(self):
        launcher = FakeLauncher(raw_result())
        self.assertEqual(self.run_main(launcher), 0)
        receipt = json.loads((self.output/load.RECEIPT).read_text())
        self.assertEqual(receipt['status'], 'passed')
        self.assertIsNone(receipt['capacity_claim'])
        self.assertIn('not a capacity', receipt['status_meaning'])
        self.assertIn('32-player capability', receipt['not_established'])
        self.assertEqual(receipt['artifacts'][load.RAW], load.sha256(self.output/load.RAW))
        self.assertEqual(receipt['server_warning_counts'], {'server_behind_schedule': 0, 'movement_too_fast': 0, 'packet_delivery_failures': 0})
        self.assertFalse((self.output/load.FAILURE).exists())
        self.assertEqual(list(self.output.glob('.*.partial')), [])
        command, cwd, timeout = launcher.calls[0]
        self.assertIn('-Dinfinity.gametest.paced=true', command)
        self.assertEqual(self.prepared, [cwd])
        self.assertFalse(cwd.exists(), 'The disposable runtime and its world are deleted after the run')
        self.assertFalse(load.inside(cwd, self.root/'server'))
        self.assertEqual(timeout, load.timeout_seconds(WARMUP, SAMPLES))

    def test_receipt_link_failure_is_reported_as_failure(self):
        real_link = os.link
        calls = []
        def failing_once(source, target):
            calls.append(target)
            if len(calls) == 1:
                raise OSError('disk full')
            return real_link(source, target)
        with patch.object(load.os, 'link', side_effect=failing_once):
            failure = self.assert_failed(FakeLauncher(raw_result()))
        self.assertIn('disk full', failure['reason'])

    def test_partial_receipt_write_leaves_no_result_and_fails(self):
        with patch.object(load.os, 'fsync', side_effect=OSError('flush failed')):
            self.assertEqual(self.run_main(FakeLauncher(raw_result())), 1)
        self.assertFalse((self.output/load.RECEIPT).exists())
        self.assertFalse((self.output/load.FAILURE).exists())
        self.assertEqual(list(self.output.glob('.*.partial')), [])

    def test_write_receipt_never_replaces_an_existing_file(self):
        self.output.mkdir(parents=True)
        existing = self.output/load.RECEIPT
        existing.write_text('previous')
        with self.assertRaises(FileExistsError):
            load.write_receipt(existing, {'status': 'passed'})
        self.assertEqual(existing.read_text(), 'previous')
        self.assertEqual(list(self.output.glob('.*.partial')), [])

    def test_stale_output_is_refused_before_launch(self):
        self.output.mkdir(parents=True)
        (self.output/load.RECEIPT).write_text('{"status":"passed"}')
        launcher = FakeLauncher(raw_result())
        self.assertEqual(self.run_main(launcher), 2)
        self.assertEqual(launcher.calls, [])
        self.assertEqual((self.output/load.RECEIPT).read_text(), '{"status":"passed"}')

    def test_live_server_locations_are_refused(self):
        launcher = FakeLauncher(raw_result())
        self.assertEqual(self.run_main(launcher, output=self.root/'server/fabric/results'), 2)
        self.assertFalse((self.root/'server').exists())
        with self.assertRaisesRegex(load.DiagnosticFailure, 'disposable research'):
            load.check_locations(self.root/'server/fabric', self.output)
        other = self.root/'elsewhere-live-server'; other.mkdir()
        with self.assertRaisesRegex(load.DiagnosticFailure, 'disposable research'):
            load.check_locations(other, self.output)
        self.assertEqual(self.run_main(launcher, output=other/'results'), 2)
        self.assertFalse((other/'results').exists())
        self.assertEqual(launcher.calls, [])

    def test_symlinked_output_runtime_and_runtime_children_are_refused(self):
        elsewhere = self.root/'elsewhere-live-server'; elsewhere.mkdir()
        runs = self.root/'research/public-load/runs'; runs.mkdir(parents=True)
        (runs/'linked').symlink_to(elsewhere, target_is_directory=True)
        launcher = FakeLauncher(raw_result())
        self.assertEqual(self.run_main(launcher, output=runs/'linked/results'), 2)
        self.assertFalse((elsewhere/'results').exists())
        (self.runtime/'mods').mkdir()
        (self.runtime/'mods/external.jar').symlink_to(elsewhere/'private.jar')
        self.assertEqual(self.run_main(launcher), 2)
        self.assertEqual(launcher.calls, [])
        self.assertFalse(self.output.exists())
        (self.runtime/'mods/external.jar').unlink()
        (self.runtime/'.fabric').symlink_to(elsewhere, target_is_directory=True)
        self.assertEqual(self.run_main(launcher), 2)
        self.assertEqual(launcher.calls, [])
        self.assertFalse(self.output.exists())

    def test_stalled_subprocess_is_killed_and_fails(self):
        class Stalled:
            killed = False
            def wait(self, timeout=None):
                if not self.killed:
                    raise subprocess.TimeoutExpired('java', timeout)
                return -9
            def kill(self):
                self.killed = True
        process = Stalled()
        with self.assertRaisesRegex(load.DiagnosticFailure, 'killed'):
            load.launch(['java'], self.root, self.root/'stalled.log', 1, popen=lambda *a, **k: process)
        self.assertTrue(process.killed)
        failure = load.DiagnosticFailure('Disposable server exceeded 1s and was killed')
        report = self.assert_failed(FakeLauncher(failure=failure))
        self.assertIn('killed', report['reason'])

    def test_disconnected_fake_player_fails(self):
        reported = raw_result()
        reported.update(status='failed', failure='1 fake player disconnects since join')
        reported['measurements']['concurrency'].update(connected_min=7, disconnects_since_join=1)
        self.assertIn('disconnects', self.assert_failed(FakeLauncher(reported))['reason'])
        inconsistent = raw_result()
        inconsistent['measurements']['concurrency']['disconnects_since_join'] = 1
        with self.assertRaisesRegex(load.DiagnosticFailure, 'disconnects'):
            load.validate_raw(inconsistent, WARMUP, SAMPLES)

    def test_incomplete_cleanup_fails(self):
        for key, value in (('players_still_listed', 1), ('sampler_cleared', False), ('close_error', 'boom')):
            data = raw_result(); data['cleanup'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(load.DiagnosticFailure, 'cleanup'):
                load.validate_raw(data, WARMUP, SAMPLES)
        data = raw_result(); data['cleanup']['channels_still_open'] = 2
        self.assertIn('cleanup', self.assert_failed(FakeLauncher(data))['reason'])

    def test_negative_unordered_or_unpaced_timings_fail(self):
        cases = [('start_to_end_callback_span_nanos', dict(min=-1)),
                 ('start_to_end_callback_span_nanos', dict(p50=5_000_000)),
                 ('start_to_end_callback_span_nanos', dict(samples=SAMPLES-1)),
                 ('start_to_start_period_nanos', dict(min=49_999_999))]
        for field, change in cases:
            data = raw_result(); data['measurements'][field].update(change)
            with self.subTest(field=field, change=change), self.assertRaises(load.DiagnosticFailure):
                load.validate_raw(data, WARMUP, SAMPLES)
        data = raw_result(); data['measurements']['sampler_fault'] = 'Monotonic clock went backwards: 10 -> 5'
        with self.assertRaisesRegex(load.DiagnosticFailure, 'backwards'):
            load.validate_raw(data, WARMUP, SAMPLES)

    def test_missing_movement_ticks_or_menus_fail(self):
        changes = [lambda m: m['actions'].update(moves_accepted=8*SAMPLES-1),
                   lambda m: m['server_ticks'].update(last=200+SAMPLES),
                   lambda m: m['concurrency'].update(active_min=7),
                   lambda m: m['concurrency'].update(menu_open_player_ticks=3)]
        for index, change in enumerate(changes):
            data = raw_result(); change(data['measurements'])
            with self.subTest(index=index), self.assertRaises(load.DiagnosticFailure):
                load.validate_raw(data, WARMUP, SAMPLES)

    def test_relevant_server_warnings_are_counted_in_receipt(self):
        log = ("[Server thread/WARN]: Can't keep up!\n"
               "[Server thread/WARN]: fake moved too quickly!\n"
               "[Server thread/WARN]: Failed to deliver packet\n")
        self.assertEqual(self.run_main(FakeLauncher(raw_result(), log=log)), 0)
        receipt = json.loads((self.output/load.RECEIPT).read_text())
        self.assertEqual(receipt['server_warning_counts'], {'server_behind_schedule': 1, 'movement_too_fast': 1, 'packet_delivery_failures': 1})

    def test_exit_code_failed_case_or_error_log_fail(self):
        self.assert_failed(FakeLauncher(raw_result(), code=1))
        self.output = self.root/'research/public-load/runs/2'
        self.assert_failed(FakeLauncher(raw_result(), report=REPORT.replace('<testcase name="case2"/>',
                                                                            '<testcase name="case2"><failure/></testcase>')))
        self.output = self.root/'research/public-load/runs/3'
        failure = self.assert_failed(FakeLauncher(raw_result(),
            log='[Server thread/ERROR]: Polymer packet context missing\n'))
        self.assertIn('ERROR', failure['reason'])
        self.output = self.root/'research/public-load/runs/4'
        self.assert_failed(FakeLauncher(None))

    def test_timeout_is_bounded_and_covers_minimum_paced_time(self):
        for warmup, samples in ((20, 100), (100, 600), (200, 1200)):
            seconds = load.timeout_seconds(warmup, samples)
            self.assertGreater(seconds, (warmup + samples) * 0.05)
            self.assertLessEqual(seconds, 900)

    def test_diagnostic_registration_exists_only_in_the_temporary_jar(self):
        manifest = {'version': 'fixture', 'entrypoints': {'fabric-gametest': ['ordinary.One']},
                    'mixins': [load.PACING_MIXINS], 'custom': {'infinity-opt-in-gametests': [load.DIAGNOSTIC]}}
        source = self.root/'original.jar'
        with zipfile.ZipFile(source, 'w') as archive:
            archive.writestr('fabric.mod.json', json.dumps(manifest)); archive.writestr('data.txt', b'bytes')
        original = source.read_bytes()
        destination = self.root/'diagnostic.jar'
        load.diagnostic_test_jar(source, destination)
        self.assertEqual(source.read_bytes(), original)
        with zipfile.ZipFile(destination) as archive:
            result = json.loads(archive.read('fabric.mod.json'))
            self.assertEqual(archive.read('data.txt'), b'bytes')
        self.assertEqual(result['entrypoints']['fabric-gametest'], [load.DIAGNOSTIC])
        self.assertEqual(result['mixins'], manifest['mixins'])
        for broken in ({'entrypoints': {'fabric-gametest': ['ordinary.One', load.DIAGNOSTIC]}},
                       {'mixins': []}, {'custom': {}}):
            changed = copy.deepcopy(manifest); changed.update(broken)
            with zipfile.ZipFile(source, 'w') as archive:
                archive.writestr('fabric.mod.json', json.dumps(changed))
            with self.subTest(broken=broken), self.assertRaises(load.DiagnosticFailure):
                load.diagnostic_test_jar(source, self.root/'rejected.jar')


class StandardSuiteExclusionTests(unittest.TestCase):
    def test_diagnostic_is_declared_opt_in_and_never_in_regular_native_groups(self):
        import check_crossplay
        import prepare_crossplay
        manifest = json.loads((PROJECT/'java/src/gametest/resources/fabric.mod.json').read_text())
        custom = manifest['custom']
        self.assertIn(load.DIAGNOSTIC, custom['infinity-opt-in-gametests'])
        self.assertNotIn(load.DIAGNOSTIC, manifest['entrypoints']['fabric-gametest'])
        self.assertNotIn(load.DIAGNOSTIC, custom['infinity-isolated-gametests'] + custom['infinity-exclusive-gametests'])
        for label, classes in check_crossplay.test_groups(manifest):
            self.assertNotIn(load.DIAGNOSTIC, classes, label)
        regular = 1 + sum(len(re.findall(r'@GameTest\b', (PROJECT/'java/src/gametest/java'/(name.replace('.', '/')+'.java')).read_text()))
                          for name in manifest['entrypoints']['fabric-gametest'])
        self.assertEqual(prepare_crossplay.expected_native_tests(), regular)

    def test_runner_case_count_matches_the_opt_in_sources(self):
        sources = PROJECT/'java/src/gametest/java/dev/convergence'
        self.assertEqual(len(re.findall(r'@GameTest\b', (sources/'PublicLoadDiagnostic.java').read_text())), load.EXPECTED_CASES-1)
        self.assertNotRegex((sources/'PublicLoadMetrics.java').read_text(), r'@GameTest\b')


if __name__ == '__main__':
    unittest.main()
