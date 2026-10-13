"""Fail-closed evidence and isolated test registration for the armor comparison."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(PROJECT/'tools'))
SPEC = importlib.util.spec_from_file_location('capacity_benchmark',PROJECT/'tools/benchmark_capacity.py')
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)
import package_crossplay


class CapacityBenchmarkContractTests(unittest.TestCase):
    def test_rejects_empty_or_incomplete_comparison_pairs(self):
        self.assertEqual(benchmark.capacity_labels(1),{'baseline-1','optimized-1'})
        self.assertEqual(len(benchmark.capacity_labels(3)),6)
        for repeats in (0,4,True,'2',None):
            with self.subTest(repeats=repeats),self.assertRaises(RuntimeError):benchmark.capacity_labels(repeats)

    def test_package_rejects_a_replaced_or_unbound_native_test_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            test_jar = Path(directory)/'tested.jar';test_jar.write_bytes(b'exact native tests')
            expected = hashlib.sha256(test_jar.read_bytes()).hexdigest()
            provenance = {'test_mod_sha256':expected}
            self.assertEqual(package_crossplay.verified_test_hash(provenance,test_jar),expected)
            with self.assertRaises(RuntimeError):package_crossplay.verified_test_hash({},test_jar)
            test_jar.write_bytes(b'replaced tests')
            with self.assertRaises(RuntimeError):package_crossplay.verified_test_hash(provenance,test_jar)

    @staticmethod
    def valid():
        native = {key: True for key in ('base_health_unchanged','base_absorption_unchanged','gear_components_unchanged','effects_renewed',
                                     'saved_attribute_nbt_unchanged','saved_equipment_nbt_unchanged','tracking_established')}
        for key,value in [('maximum_health',60),('maximum_absorption',16),('health',60),('absorption',16)]:
            native[key+'_before'] = native[key+'_after'] = value
        native.update(health_boost_renewals=2,absorption_renewals=2,owner_player_ticks=120,observer_player_ticks=120)
        counts = {key:0 for key in benchmark.COUNTS}
        for key in benchmark.COUNTS[:3]:counts[key]=3
        return dict(schema=1,ticks=120,warmup_ticks=40,tick_start=50,tick_end=170,
                    subjects=dict(owner=counts.copy(),observer=counts.copy()),native=native)

    def test_rejects_missing_corrupt_or_inconsistent_native_counts(self):
        valid = self.valid()
        self.assertIs(benchmark.validate_measurement(valid),valid)
        quiet=copy.deepcopy(valid)
        quiet['subjects']={role:{key:0 for key in benchmark.COUNTS} for role in ('owner','observer')}
        self.assertIs(benchmark.validate_measurement(quiet),quiet) # Proven tracking/renewals may deliver no capacity packets.
        for field,value in [('synthetic_health_refreshes',True),('total_metadata_packets',-1),('max_health_attribute_deliveries',4),('synthetic_metadata_refreshes',4)]:
            data = copy.deepcopy(valid);data['subjects']['observer'][field] = value
            with self.subTest(field=field),self.assertRaises(RuntimeError):benchmark.validate_measurement(data)
        data = copy.deepcopy(valid);del data['subjects']['owner']['capacity_attribute_packets']
        with self.assertRaises(RuntimeError):benchmark.validate_measurement(data)

    def test_rejects_incomplete_ticks_and_changed_native_gameplay(self):
        valid = self.valid()
        cases = []
        for field,value in [('schema',True),('ticks',119),('tick_end',171),('warmup_ticks',39)]:
            data = copy.deepcopy(valid);data[field] = value;cases.append(data)
        for field,value in [('health_after',59),('maximum_absorption_after',float('nan')),('effects_renewed',False),('gear_components_unchanged',False),('saved_attribute_nbt_unchanged',False),('tracking_established',False),('health_boost_renewals',1),('absorption_renewals',True),('owner_player_ticks',0),('observer_player_ticks',119)]:
            data = copy.deepcopy(valid);data['native'][field] = value;cases.append(data)
        for data in cases:
            with self.subTest(data=data),self.assertRaises(RuntimeError):benchmark.validate_measurement(data)

    def test_selects_one_fixture_only_in_the_temporary_test_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory);original = root/'original.jar';selected = root/'selected.jar'
            manifest = dict(version='fixture',entrypoints={'fabric-gametest':['ordinary.Test']},
                            mixins=['client-vitals-performance.mixins.json'],custom={'kept':True})
            with zipfile.ZipFile(original,'w') as archive:
                archive.writestr('fabric.mod.json',json.dumps(manifest));archive.writestr('resource.txt',b'unchanged')
            before = original.read_bytes()
            benchmark.benchmark_test_jar(original,selected)
            self.assertEqual(original.read_bytes(),before)
            with zipfile.ZipFile(selected) as archive:
                result = json.loads(archive.read('fabric.mod.json'))
                self.assertEqual(result['entrypoints']['fabric-gametest'],['dev.convergence.NativeArmorRefreshGameTests'])
                self.assertEqual(result['mixins'],[])
                self.assertEqual(result['version'],manifest['version'])
                self.assertEqual(result['custom'],manifest['custom'])
                self.assertEqual(archive.read('resource.txt'),b'unchanged')


if __name__ == '__main__':
    unittest.main()
