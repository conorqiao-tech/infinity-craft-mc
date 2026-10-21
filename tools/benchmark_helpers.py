#!/usr/bin/env python3
"""Compare actual native helper control work in fresh disposable GameTest worlds.

Requires prebuilt jars; never builds, packages, downloads or operates a playable server.
The expensive benchmark entrypoint is registered only in the temporary test jar.
"""
import argparse
import hashlib
import json
import math
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from snapshot5_dependencies import MINECRAFT, LOADER, FRAMEWORK, FRAMEWORK_SHA256

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mod_id(path):
    with zipfile.ZipFile(path) as archive:
        return json.loads(archive.read('fabric.mod.json'))['id']


def runtime_inputs(runtime):
    """Reject an incomplete cached install before FabricInstaller can fetch anything."""
    runtime = runtime.resolve()
    cache = runtime/'.fabric/server'
    launch = runtime/'fabric-server-launch.jar'
    server = cache/(MINECRAFT+'-server.jar')
    loader = cache/('fabric-loader-server-'+LOADER+'-minecraft-'+MINECRAFT+'.jar')
    for path in [launch, server, loader]:
        if not path.is_file():
            raise RuntimeError('Missing pinned cached runtime input: '+str(path))
    with zipfile.ZipFile(launch) as archive:
        properties = dict(line.split('=', 1) for line in archive.read('install.properties').decode().splitlines() if '=' in line)
    if properties.get('game-version')!=MINECRAFT or properties.get('fabric-loader-version')!=LOADER:
        raise RuntimeError('Unexpected FabricInstaller pinned runtime')
    with zipfile.ZipFile(loader) as archive:
        manifest = archive.read('META-INF/MANIFEST.MF').decode().replace('\r\n', '\n').replace('\n ', '')
    classpath = next((line[len('Class-Path: '):] for line in manifest.splitlines() if line.startswith('Class-Path: ')), '')
    if not classpath:
        raise RuntimeError('Cached Fabric loader lacks its dependency classpath')
    for entry in classpath.split():
        dependency = (cache/entry).resolve()
        if runtime not in dependency.parents or not dependency.is_file():
            raise RuntimeError('Missing or external cached loader dependency: '+entry)
    framework = runtime/'mods'/FRAMEWORK
    expected = (ROOT/'tools/gametest-framework.sha256').read_text().split()[0]
    if expected!=FRAMEWORK_SHA256 or not framework.is_file() or sha256(framework)!=expected:
        raise RuntimeError('Missing or unvalidated native GameTest framework')
    paths = [launch]
    for folder in ['libraries', 'versions', '.fabric/server', 'mods']:
        if not (runtime/folder).is_dir():
            raise RuntimeError('Missing cached runtime folder: '+folder)
        paths.extend((runtime/folder).rglob('*.jar'))
    return {path.relative_to(runtime).as_posix(): sha256(path) for path in sorted(paths)}


def copy_cached_runtime(source, destination):
    shutil.copy2(source/'fabric-server-launch.jar', destination/'fabric-server-launch.jar')
    for folder in ['libraries', 'versions']:
        shutil.copytree(source/folder, destination/folder)
    cache = destination/'.fabric/server'; cache.mkdir(parents=True)
    for name in [MINECRAFT+'-server.jar', 'fabric-loader-server-'+LOADER+'-minecraft-'+MINECRAFT+'.jar']:
        shutil.copy2(source/'.fabric/server'/name, cache/name)


def benchmark_test_jar(source, destination):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as result:
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename == 'fabric.mod.json':
                manifest = json.loads(data)
                manifest['entrypoints']['fabric-gametest'] = ['dev.convergence.HelperPerformanceBenchmark']
                data = json.dumps(manifest, indent=2).encode()
            result.writestr(item, data)


def distribution(values):
    values = sorted(values)
    return {name: values[min(len(values)-1, math.ceil(len(values)*fraction)-1)]
            for name, fraction in [('p50', .5), ('p95', .95), ('p99', .99), ('max', 1)]}


def summarize(rows):
    result = {}
    for count in [0, 1, 4, 128]:
        samples = [row['work'] for row in rows if row['hostiles'] == count]
        if not samples:
            raise RuntimeError('Missing benchmark population ' + str(count))
        counts = {}
        for field in ['controls', 'queries', 'candidates', 'predicateVisits', 'sightCalls', 'sightRays',
                      'focusLookups', 'focusPeerVisits', 'moveRequests', 'pathSearches']:
            values = [sample[field] for sample in samples]
            counts[field] = {'min': min(values), 'max': max(values), 'total': sum(values)}
        result[str(count)] = {'samples': len(samples), 'counts': counts,
                             'control_batch_nanos': distribution([sample['controlNanos'] for sample in samples]),
                             'path_batch_nanos': distribution([sample['pathNanos'] for sample in samples])}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--baseline', required=True, type=Path)
    parser.add_argument('--optimized', required=True, type=Path)
    parser.add_argument('--tests', required=True, type=Path)
    parser.add_argument('--runtime', type=Path, default=ROOT/'research/integration')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--repeats', type=int, default=3)
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--samples', type=int, default=100)
    args = parser.parse_args()
    if not 1 <= args.repeats <= 5 or not 1 <= args.warmup <= 100 or not 1 <= args.samples or args.warmup+args.samples > 300:
        parser.error('Use repeats 1..5, warmup 1..100, and warmup+samples <=300')
    for path in [args.java, args.baseline, args.optimized, args.tests]:
        if not path.is_file():
            parser.error('Missing prebuilt input: ' + str(path))
    java_version = subprocess.run([str(args.java), '-version'], capture_output=True, text=True, check=True).stderr
    if 'version "25' not in java_version:
        parser.error('Use the verified Java 25 runtime')
    output = args.output or ROOT/'research/helper-performance/runs'/str(time.time_ns())
    output.mkdir(parents=True, exist_ok=False)
    inputs = {name: sha256(path) for name, path in [('baseline', args.baseline), ('optimized', args.optimized), ('tests', args.tests)]}
    source_hashes = {path.relative_to(ROOT).as_posix(): sha256(path) for path in (ROOT/'java/src').rglob('*') if path.is_file()}
    dependencies = runtime_inputs(args.runtime)
    provenance = {'java': java_version, 'inputs': inputs, 'java_sources': source_hashes,
                  'runtime_jars': dependencies,
                  'warmup': args.warmup, 'samples': args.samples, 'repeats': args.repeats,
                  'timing_note': 'Instrumented native control time; noisy JVM samples, not a server TPS guarantee.'}
    (output/'provenance.json').write_text(json.dumps(provenance, indent=2)+'\n')
    summaries = {}
    for repeat in range(args.repeats):
        order = [('baseline', args.baseline), ('optimized', args.optimized)]
        if repeat % 2:
            order.reverse()
        for variant, mod in order:
            run_name = variant+'-'+str(repeat+1)
            with tempfile.TemporaryDirectory(prefix='helper-benchmark-') as directory:
                runtime = Path(directory)
                copy_cached_runtime(args.runtime, runtime)
                mods = runtime/'mods'; mods.mkdir()
                for dependency in (args.runtime/'mods').glob('*.jar'):
                    if mod_id(dependency) not in ['convergence', 'convergence_tests']:
                        shutil.copy2(dependency, mods/dependency.name)
                shutil.copy2(mod, mods/'helper-subject.jar')
                benchmark_test_jar(args.tests, mods/'helper-benchmark-tests.jar')
                polymer = runtime/'config/polymer'; polymer.mkdir(parents=True)
                (polymer/'auto-host.json').write_text('{"enabled":false}')
                report = output/(run_name+'.xml'); rows_file = output/(run_name+'.json')
                command = [str(args.java), '-Xmx2G', '-Dfabric-api.gametest',
                           '-Dfabric-api.gametest.report-file='+str(report.resolve()),
                           '-Dinfinity.helper-performance.output='+str(rows_file.resolve()),
                           '-Dinfinity.helper-performance.warmup='+str(args.warmup),
                           '-Dinfinity.helper-performance.samples='+str(args.samples),
                           '-jar', 'fabric-server-launch.jar', 'nogui']
                with (output/(run_name+'.log')).open('w') as log:
                    completed = subprocess.run(command, cwd=runtime, stdout=log, stderr=subprocess.STDOUT, timeout=600)
                tests = list(ET.parse(report).getroot().iter('testcase')) if report.exists() else []
                if completed.returncode or len(tests)!=2 or any(test.find('failure') is not None or test.find('error') is not None or test.find('skipped') is not None for test in tests):
                    raise RuntimeError('Native benchmark failed: see '+str(output/(run_name+'.log')))
                rows = json.loads(rows_file.read_text())
                if len(rows) != args.samples*4:
                    raise RuntimeError('Incomplete native samples')
                summaries[run_name] = summarize(rows)
                (output/'summary.json').write_text(json.dumps(summaries, indent=2)+'\n')
                print(run_name+': '+json.dumps(summaries[run_name]), flush=True)
    if inputs != {name: sha256(path) for name, path in [('baseline', args.baseline), ('optimized', args.optimized), ('tests', args.tests)]}:
        raise RuntimeError('Input jars changed during measurement')
    if dependencies != runtime_inputs(args.runtime):
        raise RuntimeError('Cached runtime dependencies changed during measurement')
    print('Raw counters, timings, logs and provenance: '+str(output.resolve()))


if __name__ == '__main__':
    main()
