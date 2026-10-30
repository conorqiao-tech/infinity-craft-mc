#!/usr/bin/env python3
"""Run the opt-in synthetic eight-player public-load diagnostic in a fresh disposable GameTest world.

Requires trusted prebuilt mod and test jars, Java 25 and the verified cached runtime.
It never builds or installs a server. Runtime and output must be under this checkout's
research directories; the temporary world is created under the output and deleted afterwards.
This runner does not enforce network isolation; use a separately network-denied environment.

A "passed" receipt means only that the specified synthetic workload ran, was sampled completely
and was cleaned up. It is not a capacity, TPS, latency or public-readiness result.
"""
import argparse
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

from benchmark_helpers import copy_cached_runtime, mod_id, runtime_inputs, sha256

ROOT = Path(__file__).resolve().parents[1]
DIAGNOSTIC = 'dev.convergence.PublicLoadDiagnostic'
PACING_MIXINS = 'destination-chunks-performance.mixins.json'
PLAYERS = 8
PACED_NANOS = 50_000_000
EXPECTED_CASES = 3  # Fabric's minecraft:always_pass baseline plus the diagnostic's two methods.
RAW, REPORT, LOG, RECEIPT, FAILURE = 'raw.json', 'report.xml', 'server.log', 'receipt.json', 'failure.json'
LOG_ERROR = re.compile(r'/(ERROR|FATAL)\]')
WARNING_PATTERNS = {
    'server_behind_schedule': re.compile(r"Can't keep up!"),
    'movement_too_fast': re.compile(r'moved too quickly!'),
    'packet_delivery_failures': re.compile(r'Failed to deliver packet'),
}
STATUS_MEANING = ('passed = the specified synthetic workload ran, was sampled completely and was cleaned up. '
                  'It is not a capacity, TPS, latency or public-readiness result.')
NOT_ESTABLISHED = ['real network or inbound packet decoding', 'authentication, encryption or compression',
                   'Geyser/Bedrock/iPad clients', 'real player behavior or public workload', '32-player capability',
                   'production 20 TPS or whole-tick latency', 'public readiness']


class DiagnosticFailure(RuntimeError):
    pass


def inside(path, parent):
    path, parent = Path(path).resolve(), Path(parent).resolve()
    return path == parent or parent in path.parents


def check_locations(runtime, output):
    """Restrict inputs and outputs to this checkout's disposable research directories."""
    root = Path(os.path.abspath(ROOT))
    allowed = {'runtime': root/'research/integration', 'output': root/'research/public-load/runs'}
    for label, supplied in (('runtime', runtime), ('output', output)):
        lexical = Path(os.path.abspath(supplied))
        try:
            relative = lexical.relative_to(root)
        except ValueError:
            raise DiagnosticFailure(f'Refusing {label} outside the disposable research directory: {supplied}') from None
        current = root
        for component in relative.parts:
            current /= component
            if current.is_symlink():
                raise DiagnosticFailure(f'Refusing symbolic-link {label} path: {current}')
        if not inside(lexical, allowed[label]) or (label == 'output' and lexical == allowed[label]):
            raise DiagnosticFailure(f'Refusing {label} outside the disposable research directory: {supplied}')
    if not Path(runtime).is_dir():
        raise DiagnosticFailure(f'Missing cached runtime folder: {runtime}')
    if Path(output).exists():
        raise DiagnosticFailure(f'Output already exists; refusing stale or mixed results: {output}')


def reject_linked_runtime(runtime):
    """Do not follow runtime symlinks into a server, settings, or another folder."""
    for rel in ('fabric-server-launch.jar', 'libraries', 'versions', '.fabric/server', 'mods'):
        entry = Path(runtime)
        for component in Path(rel).parts:
            entry /= component
            if entry.is_symlink():
                raise DiagnosticFailure(f'Refusing symbolic-link runtime input: {entry}')
        if entry.is_dir():
            for parent, dirs, files in os.walk(entry, followlinks=False):
                for name in dirs + files:
                    candidate = Path(parent)/name
                    if candidate.is_symlink():
                        raise DiagnosticFailure(f'Refusing symbolic-link runtime input: {candidate}')


def diagnostic_test_jar(source, destination):
    """Temporary jar registering only the diagnostic. The original jar is never modified."""
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as result:
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename == 'fabric.mod.json':
                manifest = json.loads(data)
                if DIAGNOSTIC not in manifest.get('custom', {}).get('infinity-opt-in-gametests', []):
                    raise DiagnosticFailure('Test jar does not declare the diagnostic as opt-in')
                if DIAGNOSTIC in manifest['entrypoints']['fabric-gametest']:
                    raise DiagnosticFailure('Test jar registers the diagnostic in its regular suite')
                if PACING_MIXINS not in manifest.get('mixins', []):
                    raise DiagnosticFailure('Test jar lacks the test-only pacing mixin')
                manifest['entrypoints']['fabric-gametest'] = [DIAGNOSTIC]
                data = json.dumps(manifest, indent=2).encode()
            result.writestr(item, data)


def prepare_runtime(source, target, mod, tests):
    copy_cached_runtime(source, target)
    # Avoid Minecraft treating a missing settings file as a startup error.
    # This empty file is created only in the disposable runtime.
    (target/'server.properties').write_text('')
    mods = target/'mods'
    mods.mkdir()
    for dependency in (source/'mods').glob('*.jar'):
        if mod_id(dependency) not in ('convergence', 'convergence_tests'):
            shutil.copy2(dependency, mods/dependency.name)
    shutil.copy2(mod, mods/'public-load-subject.jar')
    diagnostic_test_jar(tests, mods/'public-load-tests.jar')
    polymer = target/'config/polymer'
    polymer.mkdir(parents=True)
    (polymer/'auto-host.json').write_text('{"enabled":false}')


def timeout_seconds(warmup, samples):
    """Startup allowance plus four times the minimum paced duration (>=50 ms per tick)."""
    return 180 + math.ceil((warmup + samples + 80) * PACED_NANOS / 1e9 * 4)


def launch(command, cwd, log_path, timeout, popen=subprocess.Popen):
    """External watchdog: a stuck server thread cannot stop itself, so the subprocess is killed here."""
    with open(log_path, 'w') as log:
        process = popen(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                raise DiagnosticFailure(f'Disposable server exceeded {timeout}s and did not exit after kill')
            raise DiagnosticFailure(f'Disposable server exceeded {timeout}s and was killed')
        except BaseException:
            process.kill()
            process.wait(timeout=30)
            raise


def validate_report(path):
    if not path.is_file():
        raise DiagnosticFailure('GameTest report was not written')
    cases = list(ET.parse(path).getroot().iter('testcase'))
    failed = [case.get('name') for case in cases
              if any(case.find(kind) is not None for kind in ('failure', 'error', 'skipped'))]
    if len(cases) != EXPECTED_CASES or failed:
        raise DiagnosticFailure(f'GameTest report has {len(cases)}/{EXPECTED_CASES} cases; failed: {failed}')
    return len(cases)


def scan_log(path):
    if not path.is_file():
        raise DiagnosticFailure('Server log was not written')
    errors = 0
    first_error = None
    warnings = {name: 0 for name in WARNING_PATTERNS}
    with open(path, errors='replace') as log:
        for line in log:
            if LOG_ERROR.search(line):
                errors += 1
                if first_error is None:
                    first_error = line.rstrip()[:300]
            for name, pattern in WARNING_PATTERNS.items():
                warnings[name] += bool(pattern.search(line))
    if errors:
        raise DiagnosticFailure(f'{errors} ERROR/FATAL log lines, first: {first_error}')
    return warnings


def integer(value):
    return type(value) is int


def validate_distribution(name, data, samples):
    if not isinstance(data, dict) or data.get('unit') != 'nanoseconds' or data.get('samples') != samples:
        raise DiagnosticFailure(f'{name} lacks nanosecond units or {samples} samples')
    values = [data.get(key) for key in ('min', 'p50', 'p95', 'p99', 'max')]
    if not all(integer(value) and value >= 0 for value in values) or values != sorted(values):
        raise DiagnosticFailure(f'{name} percentiles are negative, non-integer or out of order')
    return data


def validate_raw(data, warmup, samples):
    """Independently re-checks the in-game result; nothing is inferred from missing fields."""
    if not isinstance(data, dict) or data.get('schema') != 2:
        raise DiagnosticFailure('Unexpected raw result schema')
    if data.get('status') != 'passed' or data.get('failure') is not None:
        raise DiagnosticFailure(f"In-game diagnostic reported {data.get('status')}: {data.get('failure')}")
    if data.get('workload', {}).get('fake_players') != PLAYERS:
        raise DiagnosticFailure('Raw result is not the eight-player workload')
    limits = data.get('limits', {})
    if limits.get('warmup_ticks') != warmup or limits.get('sample_ticks') != samples:
        raise DiagnosticFailure('Raw result limits differ from the requested run')
    m = data.get('measurements', {})
    ticks = m.get('server_ticks', {})
    if not (integer(ticks.get('first')) and integer(ticks.get('last')) and ticks.get('recorded') == samples
            and ticks['last'] - ticks['first'] + 1 == samples):
        raise DiagnosticFailure('Recorded server ticks are incomplete or not consecutive')
    if m.get('sampler_fault') is not None:
        raise DiagnosticFailure(f"Sampler fault: {m['sampler_fault']}")
    validate_distribution('start_to_end_callback_span_nanos', m.get('start_to_end_callback_span_nanos'), samples)
    period = validate_distribution('start_to_start_period_nanos', m.get('start_to_start_period_nanos'), samples)
    if period['min'] < PACED_NANOS:
        raise DiagnosticFailure('Tick periods below the test pacing sleep; the run was not paced')
    k = m.get('concurrency', {})
    for key in ('connected_min', 'connected_max', 'active_min', 'active_max', 'server_players_min', 'server_players_max'):
        if k.get(key) != PLAYERS:
            raise DiagnosticFailure(f'Concurrency {key} is {k.get(key)}, expected {PLAYERS}')
    if k.get('samples') != samples:
        raise DiagnosticFailure('Concurrency was not sampled on every measured tick')
    if k.get('disconnects_since_join') != 0:
        raise DiagnosticFailure(f"{k.get('disconnects_since_join')} fake player disconnects")
    if k.get('menu_open_player_ticks') != 0:
        raise DiagnosticFailure('A container menu opened outside the declared workload')
    actions = m.get('actions', {})
    if actions.get('moves_sent') != PLAYERS*samples or actions.get('moves_accepted') != actions.get('moves_sent'):
        raise DiagnosticFailure('Not every measured movement was sent and accepted')
    cleanup = data.get('cleanup', {})
    if (cleanup.get('players_closed') != PLAYERS or cleanup.get('sampler_cleared') is not True
            or any(cleanup.get(key) != 0 for key in ('players_still_listed', 'connections_still_registered', 'channels_still_open'))
            or 'close_error' in cleanup or 'cleanup_error' in cleanup):
        raise DiagnosticFailure(f'Fixture cleanup incomplete: {cleanup}')
    return data


def write_receipt(path, receipt):
    """Complete, flushed and closed before it appears; never replaces an existing file."""
    path = Path(path)
    temporary = path.with_name('.' + path.name + '.partial')
    try:
        with open(temporary, 'x') as stream:
            stream.write(json.dumps(receipt, indent=2) + '\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)  # Fails rather than replacing an existing receipt.
    finally:
        # Once linked, the receipt is complete; a leftover dot-file is not a failure.
        try:
            temporary.unlink()
        except OSError:
            pass


def java_version(java):
    return subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True, timeout=60).stderr


def parse(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    for name in ('java', 'mod', 'tests'):
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--runtime', type=Path, default=ROOT/'research/integration')
    parser.add_argument('--output', type=Path, default=ROOT/'research/public-load/runs'/str(time.time_ns()))
    parser.add_argument('--warmup', type=int, default=100)
    parser.add_argument('--samples', type=int, default=600)
    args = parser.parse_args(argv)
    if not 20 <= args.warmup <= 200 or not 100 <= args.samples <= 1200:
        parser.error('Use warmup 20..200 and samples 100..1200')
    for path in (args.java, args.mod, args.tests):
        if not path.is_file():
            parser.error('Missing prebuilt input: ' + str(path))
    return args


def main(argv=None, *, launcher=launch, prepare=prepare_runtime, version=java_version):
    args = parse(argv)
    try:
        check_locations(args.runtime, args.output)
        reject_linked_runtime(args.runtime)
    except DiagnosticFailure as failure:
        print('Public-load diagnostic refused: ' + str(failure), file=sys.stderr)
        return 2
    runtime, output = args.runtime.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    started = time.time()
    try:
        if 'version "25' not in version(args.java):
            raise DiagnosticFailure('Use the verified Java 25 runtime')
        inputs = {name: sha256(path) for name, path in (('mod', args.mod), ('tests', args.tests))}
        dependencies = runtime_inputs(runtime)
        timeout = timeout_seconds(args.warmup, args.samples)
        with tempfile.TemporaryDirectory(prefix='.public-load-', dir=output) as directory:
            disposable = Path(directory)
            prepare(runtime, disposable, args.mod, args.tests)
            command = [str(args.java), '-Xmx2G', '-Dinfinity.gametest.paced=true', '-Dfabric-api.gametest',
                       '-Dfabric-api.gametest.report-file='+str(output/REPORT),
                       '-Dinfinity.public-load.output='+str(output/RAW),
                       '-Dinfinity.public-load.warmup='+str(args.warmup),
                       '-Dinfinity.public-load.samples='+str(args.samples),
                       '-jar', 'fabric-server-launch.jar', 'nogui']
            code = launcher(command, disposable, output/LOG, timeout)
        if code != 0:
            raise DiagnosticFailure(f'Disposable server exited with {code}')
        cases = validate_report(output/REPORT)
        warning_counts = scan_log(output/LOG)
        if not (output/RAW).is_file():
            raise DiagnosticFailure('In-game raw result was not written')
        raw = validate_raw(json.loads((output/RAW).read_text()), args.warmup, args.samples)
        if inputs != {name: sha256(path) for name, path in (('mod', args.mod), ('tests', args.tests))}:
            raise DiagnosticFailure('Input jars changed during the run')
        if dependencies != runtime_inputs(runtime):
            raise DiagnosticFailure('Cached runtime changed during the run')
        receipt = {'status': 'passed', 'status_meaning': STATUS_MEANING, 'capacity_claim': None,
                   'not_established': NOT_ESTABLISHED, 'java': version(args.java), 'inputs': inputs,
                   'runtime_jars': dependencies, 'server_warning_counts': warning_counts, 'warmup': args.warmup, 'samples': args.samples,
                   'subprocess_timeout_seconds': timeout, 'gametest_cases': cases,
                   'elapsed_seconds': round(time.time()-started, 3),
                   'artifacts': {name: sha256(output/name) for name in (RAW, REPORT, LOG)},
                   'world': 'Generated in a temporary folder and deleted after the run',
                   'measurements': raw['measurements']}
        write_receipt(output/RECEIPT, receipt)
    except BaseException as failure:
        try:
            write_receipt(output/FAILURE, {'status': 'failed', 'reason': f'{type(failure).__name__}: {failure}',
                                           'status_meaning': 'No measurement from this run may be used.'})
        except BaseException as receipt_failure:
            print('Failure receipt not written: ' + str(receipt_failure), file=sys.stderr)
        if not isinstance(failure, Exception):
            raise
        print('Public-load diagnostic FAILED: ' + str(failure), file=sys.stderr)
        return 1
    print('Public-load integrity receipt (not a capacity result): ' + str(output/RECEIPT))
    return 0


if __name__ == '__main__':
    sys.exit(main())
