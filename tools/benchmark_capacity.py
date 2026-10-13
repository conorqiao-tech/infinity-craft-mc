#!/usr/bin/env python3
"""Compare actual native armor-refresh traffic in fresh disposable worlds.

Requires prebuilt subjects/tests and the verified pinned cached runtime. Never
builds, downloads, packages, copies private settings or controls a playable world.
"""
import argparse
import json
import math
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

from benchmark_helpers import copy_cached_runtime, mod_id, runtime_inputs, sha256

ROOT = Path(__file__).resolve().parents[1]
COUNTS = ('capacity_attribute_packets', 'max_health_attribute_deliveries',
          'max_absorption_attribute_deliveries', 'synthetic_health_refreshes',
          'synthetic_absorption_refreshes', 'synthetic_metadata_refreshes',
          'total_health_packets', 'total_metadata_packets')


def capacity_labels(repeats):
    if type(repeats) is not int or not 1 <= repeats <= 3:
        raise RuntimeError('Armor comparison requires one to three complete baseline/optimized pairs')
    return {f'{subject}-{repeat}' for subject in ('baseline','optimized') for repeat in range(1,repeats+1)}


def evidence_hashes(output, labels):
    names = {'summary.json'} | {label+suffix for label in labels for suffix in ('.json','.xml','.log')}
    return {name:sha256(output/name) for name in sorted(names)}


def benchmark_test_jar(source, destination):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as result:
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename == 'fabric.mod.json':
                manifest = json.loads(data)
                manifest['entrypoints']['fabric-gametest'] = ['dev.convergence.NativeArmorRefreshGameTests']
                # No unrelated test probes: the fixture observes actual connection outputs.
                manifest['mixins'] = []
                data = json.dumps(manifest, indent=2).encode()
            result.writestr(item, data)


def validate_measurement(data):
    if type(data.get('schema')) is not int or data.get('schema') != 1 or data.get('ticks') != 120 or data.get('warmup_ticks') != 40:
        raise RuntimeError('Incomplete native armor measurement window')
    if type(data.get('tick_start')) is not int or type(data.get('tick_end')) is not int or data['tick_start'] < 0 or data['tick_end']-data['tick_start'] != 120:
        raise RuntimeError('Native tick window differs from the reported measurement')
    for role in ('owner', 'observer'):
        counts = data.get('subjects', {}).get(role, {})
        if any(type(counts.get(key)) is not int or counts[key] < 0 for key in COUNTS):
            raise RuntimeError('Incomplete native packet counts for '+role)
        if any(counts[key] > counts['capacity_attribute_packets'] for key in COUNTS[1:3]):
            raise RuntimeError('Capacity fields exceed actual packet deliveries for '+role)
        if (len({counts[key] for key in COUNTS[3:6]}) != 1
            or counts['synthetic_metadata_refreshes'] > counts['capacity_attribute_packets']
            or counts['synthetic_metadata_refreshes'] > counts['total_metadata_packets']
            or role == 'owner' and counts['synthetic_health_refreshes'] > counts['total_health_packets']):
            raise RuntimeError('Synthetic refresh groups exceed actual delivered packets for '+role)
    native = data.get('native', {})
    for key in ('base_health_unchanged', 'base_absorption_unchanged', 'gear_components_unchanged', 'effects_renewed',
                'saved_attribute_nbt_unchanged', 'saved_equipment_nbt_unchanged', 'tracking_established'):
        if native.get(key) is not True:
            raise RuntimeError('Native armor invariant failed: '+key)
    if any(type(native.get(key)) is not int or native[key] < 2 for key in ('health_boost_renewals', 'absorption_renewals')):
        raise RuntimeError('Actual registered native armor renewal was not observed')
    if any(type(native.get(key)) is not int or native[key] != 120 for key in ('owner_player_ticks','observer_player_ticks')):
        raise RuntimeError('Native player connections did not tick throughout the window')
    for key in ('maximum_health', 'maximum_absorption', 'health', 'absorption'):
        before, after = native.get(key+'_before'), native.get(key+'_after')
        if type(before) not in (int, float) or type(after) not in (int, float) or not math.isfinite(before) or before != after:
            raise RuntimeError('Native armor stats changed: '+key)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'baseline', 'optimized', 'tests'):
        parser.add_argument('--'+name, required=True, type=Path)
    parser.add_argument('--runtime', type=Path, default=ROOT/'research/integration')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--repeats', type=int, default=2)
    args = parser.parse_args()
    if not 1 <= args.repeats <= 3 or any(not path.is_file() for path in (args.java,args.baseline,args.optimized,args.tests)):
        parser.error('Use prebuilt files and repeats 1..3')
    version = subprocess.run([str(args.java), '-version'], capture_output=True, text=True, check=True).stderr
    if 'version "25' not in version:
        parser.error('Use the verified Java 25 runtime')
    inputs = {name: sha256(path) for name,path in [('baseline',args.baseline),('optimized',args.optimized),('tests',args.tests)]}
    dependencies = runtime_inputs(args.runtime)
    output = args.output or ROOT/'research/capacity-performance/runs'/str(time.time_ns())
    output.mkdir(parents=True, exist_ok=False)
    provenance = dict(java=version,inputs=inputs,runtime_jars=dependencies,
        java_sources={p.relative_to(ROOT).as_posix():sha256(p) for p in (ROOT/'java/src').rglob('*') if p.is_file()},
        repeats=args.repeats, note='Actual native connection packet counts; unpaced test-server ticks, not live TPS or client latency.')
    (output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    samples = {}
    for repeat in range(args.repeats):
        order = [('baseline',args.baseline),('optimized',args.optimized)]
        if repeat % 2: order.reverse()
        for name,subject in order:
            label = name+'-'+str(repeat+1)
            report = (output/(label+'.xml')).resolve()
            raw = (output/(label+'.json')).resolve()
            with tempfile.TemporaryDirectory(prefix='armor-refresh-benchmark-') as directory:
                runtime = Path(directory)
                copy_cached_runtime(args.runtime,runtime)
                mods = runtime/'mods';mods.mkdir()
                for dependency in (args.runtime/'mods').glob('*.jar'):
                    if mod_id(dependency) not in ('convergence','convergence_tests'):
                        shutil.copy2(dependency,mods/dependency.name)
                shutil.copy2(subject,mods/'armor-subject.jar')
                benchmark_test_jar(args.tests,mods/'armor-measurement-tests.jar')
                config = runtime/'config/polymer';config.mkdir(parents=True)
                (config/'auto-host.json').write_text('{"enabled":false}')
                with (output/(label+'.log')).open('w') as log:
                    completed = subprocess.run([str(args.java),'-Xmx2G','-Dfabric-api.gametest',
                        '-Dfabric-api.gametest.report-file='+str(report),
                        '-Dinfinity.capacity-performance.output='+str(raw),'-jar','fabric-server-launch.jar','nogui'],
                        cwd=runtime,stdout=log,stderr=subprocess.STDOUT,timeout=300)
                tests = list(ET.parse(report).getroot().iter('testcase')) if report.exists() else []
                if completed.returncode or len(tests)!=2 or any(t.find('failure') is not None or t.find('error') is not None or t.find('skipped') is not None for t in tests):
                    raise RuntimeError('Native armor comparison failed: '+str(output/(label+'.log')))
                samples[label] = validate_measurement(json.loads(raw.read_text()))
                (output/'summary.json').write_text(json.dumps(samples,indent=2)+'\n')
                print(label+': '+json.dumps(samples[label]['subjects']),flush=True)
    if inputs != {name: sha256(path) for name,path in [('baseline',args.baseline),('optimized',args.optimized),('tests',args.tests)]} or dependencies != runtime_inputs(args.runtime):
        raise RuntimeError('Input jars or cached runtime changed during comparison')
    provenance['artifacts'] = evidence_hashes(output,capacity_labels(args.repeats))
    (output/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print('Native packet counts, logs and provenance: '+str(output.resolve()))


if __name__ == '__main__':
    main()
