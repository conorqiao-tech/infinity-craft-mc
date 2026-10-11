"""Offline contract checks for the disposable native helper benchmark runner."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('helper_benchmark', PROJECT/'tools/benchmark_helpers.py')
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


class HelperBenchmarkContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runtime = self.root/'runtime'
        self.runtime.mkdir()
        self.cache = self.runtime/'.fabric/server'
        self.cache.mkdir(parents=True)
        for directory in ['libraries', 'versions', 'mods']:
            (self.runtime/directory).mkdir()
        self.launch = self.runtime/'fabric-server-launch.jar'
        self.loader = self.cache/'fabric-loader-server-0.19.5-minecraft-26.3.jar'
        self.jar(self.launch, {'install.properties': 'game-version=26.3\nfabric-loader-version=0.19.5\n'})
        self.jar(self.loader, {'META-INF/MANIFEST.MF': 'Manifest-Version: 1.0\nClass-Path: ../../libraries/dependency.jar\n'})
        (self.cache/'26.3-server.jar').write_bytes(b'pinned-server')
        (self.runtime/'libraries/dependency.jar').write_bytes(b'pinned-dependency')
        (self.runtime/'versions/server.jar').write_bytes(b'extracted-server')
        self.framework = self.runtime/'mods/fabric-gametest-api-v1-4.0.32+3434d6d95d.jar'
        self.framework.write_bytes(b'pinned-native-framework')
        (self.root/'tools').mkdir()
        (self.root/'tools/gametest-framework.sha256').write_text(benchmark.sha256(self.framework)+'\n')
        self.root_patch = patch.object(benchmark, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    @staticmethod
    def jar(path, entries):
        with zipfile.ZipFile(path, 'w') as archive:
            for name, data in entries.items():
                archive.writestr(name, data)

    def test_selective_cache_copy_preserves_pinned_bytes_and_omits_owner_state(self):
        inputs = benchmark.runtime_inputs(self.runtime)
        for name in ['world/player.dat', 'config/key.pem', '.fabric/processedMods/stale.jar', '.fabric/server/private.txt']:
            path = self.runtime/name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'private-fixture-sentinel')
        destination = self.root/'isolated'
        destination.mkdir()
        benchmark.copy_cached_runtime(self.runtime, destination)
        for name in ['fabric-server-launch.jar', '.fabric/server/26.3-server.jar',
                     '.fabric/server/fabric-loader-server-0.19.5-minecraft-26.3.jar',
                     'libraries/dependency.jar', 'versions/server.jar']:
            self.assertEqual(benchmark.sha256(destination/name), inputs[name])
        for name in ['world', 'config', 'mods', '.fabric/processedMods', '.fabric/server/private.txt']:
            self.assertFalse((destination/name).exists(), name)

    def test_incomplete_or_incorrect_runtime_fails_before_any_launch(self):
        with patch.object(benchmark.subprocess, 'run', side_effect=AssertionError('No offline check may launch Java')):
            server = self.cache/'26.3-server.jar'
            original = server.read_bytes(); server.unlink()
            with self.assertRaisesRegex(RuntimeError, 'Missing pinned cached'):
                benchmark.runtime_inputs(self.runtime)
            server.write_bytes(original)
            dependency = self.runtime/'libraries/dependency.jar'
            original = dependency.read_bytes(); dependency.unlink()
            with self.assertRaisesRegex(RuntimeError, 'loader dependency'):
                benchmark.runtime_inputs(self.runtime)
            dependency.write_bytes(original)
            self.jar(self.launch, {'install.properties': 'game-version=26.2\nfabric-loader-version=0.19.5\n'})
            with self.assertRaisesRegex(RuntimeError, 'Unexpected FabricInstaller'):
                benchmark.runtime_inputs(self.runtime)
            self.jar(self.launch, {'install.properties': 'game-version=26.3\nfabric-loader-version=0.19.5\n'})
            self.framework.write_bytes(b'wrong-framework')
            with self.assertRaisesRegex(RuntimeError, 'unvalidated native GameTest framework'):
                benchmark.runtime_inputs(self.runtime)

    def test_benchmark_only_registration_is_temporary_and_preserves_original(self):
        source = self.root/'original-tests.jar'
        manifest = {'version': 'fixture', 'entrypoints': {'fabric-gametest': ['ordinary.One', 'ordinary.Two']},
                    'mixins': ['helper-performance.mixins.json'],
                    'custom': {'infinity-opt-in-gametests': ['dev.convergence.HelperPerformanceBenchmark']}}
        self.jar(source, {'fabric.mod.json': json.dumps(manifest), 'fixture.txt': b'resource-bytes'})
        original = source.read_bytes()
        destination = self.root/'benchmark-tests.jar'
        benchmark.benchmark_test_jar(source, destination)
        self.assertEqual(source.read_bytes(), original)
        with zipfile.ZipFile(destination) as archive:
            result = json.loads(archive.read('fabric.mod.json'))
            self.assertEqual(result['entrypoints']['fabric-gametest'], ['dev.convergence.HelperPerformanceBenchmark'])
            self.assertEqual(result['version'], manifest['version'])
            self.assertEqual(result['mixins'], manifest['mixins'])
            self.assertEqual(result['custom'], manifest['custom'])
            self.assertEqual(archive.read('fixture.txt'), b'resource-bytes')


if __name__ == '__main__':
    unittest.main()
