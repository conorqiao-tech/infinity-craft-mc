"""Launcher-patch packages require world-preservation smoke evidence bound to the exact packaged bytes."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import package_crossplay as package
import smoke_fullstack as smoke

RUNS = ["Full-stack startup and clean shutdown passed, run " + str(n) for n in (1, 2, 3)]
MARKER = ("World preservation passed: with a legacy enabled/pending refresh policy, no terrain was regenerated "
          "and the policy was left unchanged.")
LIFECYCLE = "Full-stack lifecycle passed: real Fabric + Geyser. No player login tested."
OLD = ("Terrain refresh passed: unprotected block erased once; player NBT/inventory and stats retained. "
       "Test root: /tmp/old")
MOD = "fabric/mods/Infinity-Armor-test.jar"
BRIDGE = "geyser/extensions/InfinityCreativeBridge.jar"
GEYSER = "geyser/Geyser-Standalone.jar"
LAUNCH = "fabric/fabric-server-launch.jar"


class WorldPreservationEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.server = self.root / "server"
        self.log = self.root / "world-preservation-fullstack.log"
        files = {LAUNCH: b"fabric launcher", GEYSER: b"geyser standalone", MOD: b"tested mod", BRIDGE: b"tested bridge"}
        for name, data in files.items():
            self.write_file("server/" + name, data)
        self.write_lock(downloads=[LAUNCH, GEYSER], bundled=[MOD, BRIDGE])
        self.write_file("server/runtime.lock.json", b'{"java": "25"}')
        self.write_file("server/fabric/eula.txt", b"eula=true")
        self.write_file("server/server.py", b"launcher")
        self.write_file("server/world_refresh.py", b"preservation")
        self.write_file("server/unpackaged_helper.py", b"helper")
        for name in package.WORLD_PRESERVATION_SOURCES:
            self.write_file(name, b"source " + name.encode())

    def write_file(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def write_lock(self, downloads, bundled, version="2.13.0-explore.51"):
        entry = lambda path: {"path": path, "sha256": hashlib.sha256((self.server / path).read_bytes()).hexdigest()}
        lock = {"version": version, "downloads": [entry(p) for p in downloads], "bundled": [entry(p) for p in bundled]}
        (self.server / "dependencies.lock.json").write_text(json.dumps(lock, indent=2))

    def entries(self):
        """Package entries as main() assembles them: locks, packaged launcher modules and bundled files."""
        lock = json.loads((self.server / "dependencies.lock.json").read_bytes())
        names = ["dependencies.lock.json", "runtime.lock.json", "server.py", "world_refresh.py",
                 *(entry["path"] for entry in lock["bundled"])]
        return {name: (self.server / name).read_bytes() for name in names}

    def record(self, **changes):
        _, files = smoke.tested_inputs(self.root)
        line = smoke.provenance_line(files, "a" * 64, 'openjdk version "25"', {"fabric/libraries/x.jar": "b" * 64}, 3)
        if changes:
            record = json.loads(line[len(smoke.PROVENANCE_PREFIX):])
            record.update(changes)
            line = smoke.PROVENANCE_PREFIX + json.dumps(record, sort_keys=True, separators=(",", ":"))
        return line

    def write_log(self, lines):
        self.log.write_text("\n".join(["Full-stack cold lobby load remained responsive, run 1", *lines]) + "\n")

    def smoke_log(self, provenance=None):
        self.write_log([*RUNS, provenance or self.record(), MARKER, "World preservation test root: /private/x", LIFECYCLE])

    def verify(self, entries=None):
        tested = package.world_preservation_inputs(self.root, self.entries() if entries is None else entries)
        return package.verified_world_preservation(self.log, tested)

    def assert_rejected(self, message, entries=None):
        with self.assertRaisesRegex(RuntimeError, message):
            self.verify(entries)

    # Accepted evidence.

    def test_smoke_record_for_the_exact_candidate_is_packaged(self):
        provenance = self.record()
        self.smoke_log(provenance)
        evidence = self.verify().decode().splitlines()
        self.assertEqual(evidence, ["Full-stack cold lobby load remained responsive, run 1", *RUNS,
                                    provenance, MARKER, LIFECYCLE])
        record = json.loads(provenance[len(smoke.PROVENANCE_PREFIX):])
        self.assertEqual(set(record["files"]), {
            "server/dependencies.lock.json", "server/runtime.lock.json", "server/" + LAUNCH, "server/" + GEYSER,
            "server/" + MOD, "server/" + BRIDGE, "server/server.py", "server/world_refresh.py",
            "server/unpackaged_helper.py", *package.WORLD_PRESERVATION_SOURCES})
        self.assertIn("not all redistributable", record["not_packaged"])
        # No EULA, private settings or local absolute paths are recorded.
        self.assertFalse(any("eula" in name or "settings" in name or name.startswith("/") for name in record["files"]))
        self.assertNotIn(str(self.root), provenance)
        self.assertEqual(smoke.SOURCE_INPUTS, package.WORLD_PRESERVATION_SOURCES)

    # Candidate bytes that differ from what the smoke tested.

    def test_changed_mod_or_bridge_without_matching_lock_is_rejected(self):
        self.smoke_log()
        for path in (MOD, BRIDGE):
            with self.subTest(path=path):
                entries = self.entries()
                entries[path] = b"tampered " + path.encode()
                self.assert_rejected("locked SHA-256", entries)

    def test_consistently_relocked_mod_or_bridge_is_stale_evidence(self):
        self.smoke_log()
        for path in (MOD, BRIDGE):
            with self.subTest(path=path):
                self.write_file("server/" + path, b"different build " + path.encode())
                self.write_lock(downloads=[LAUNCH, GEYSER], bundled=[MOD, BRIDGE])
                self.assert_rejected("different bytes for server/(dependencies.lock.json|" + path + ")")

    def test_changed_lock_bytes_are_rejected(self):
        self.smoke_log()
        entries = self.entries()
        entries["dependencies.lock.json"] = entries["dependencies.lock.json"].replace(b"explore.51", b"explore.52")
        self.assert_rejected("different bytes for server/dependencies.lock.json", entries)
        entries = self.entries()
        entries["runtime.lock.json"] = b'{"java": "21"}'
        self.assert_rejected("different bytes for server/runtime.lock.json", entries)

    def test_changed_unbundled_download_is_rejected(self):
        self.smoke_log()
        self.write_file("server/" + GEYSER, b"other geyser")
        self.assert_rejected("locked SHA-256: " + GEYSER)
        self.write_lock(downloads=[LAUNCH, GEYSER], bundled=[MOD, BRIDGE])
        self.assert_rejected("different bytes")

    def test_changed_added_or_removed_sources_are_rejected(self):
        self.smoke_log()
        entries = self.entries()
        entries["world_refresh.py"] = b"changed launcher module"
        self.assert_rejected("different bytes for server/world_refresh.py", entries)
        for name in ("tools/smoke_fullstack.py", "tests/test_terrain_storage.py", "server/unpackaged_helper.py"):
            with self.subTest(name=name):
                original = (self.root / name).read_bytes()
                self.write_file(name, b"edited after the smoke")
                self.assert_rejected("different bytes for " + name)
                self.write_file(name, original)
        self.write_file("server/new_module.py", b"added after the smoke")
        self.assert_rejected("different set of files")
        (self.root / "server/new_module.py").unlink()
        (self.root / "server/unpackaged_helper.py").unlink()
        self.assert_rejected("different set of files")

    def test_swapped_entry_bytes_are_rejected(self):
        self.smoke_log()
        tested = package.world_preservation_inputs(self.root, self.entries())
        tested["server/" + MOD], tested["server/" + BRIDGE] = tested["server/" + BRIDGE], tested["server/" + MOD]
        with self.assertRaisesRegex(RuntimeError, "different bytes"):
            package.verified_world_preservation(self.log, tested)
        entries = self.entries()
        entries[MOD], entries[BRIDGE] = entries[BRIDGE], entries[MOD]
        self.assert_rejected("locked SHA-256", entries)

    def test_unsafe_or_duplicate_lock_entries_are_rejected_by_smoke_and_packager(self):
        good = {"path": MOD, "sha256": "c" * 64}
        cases = {"parent": {"path": "../outside.jar", "sha256": "c" * 64},
                 "absolute": {"path": "/etc/passwd", "sha256": "c" * 64},
                 "dot": {"path": "fabric/./mods/x.jar", "sha256": "c" * 64},
                 "backslash": {"path": "fabric\\mods\\x.jar", "sha256": "c" * 64},
                 "empty": {"path": "", "sha256": "c" * 64},
                 "unpinned": {"path": "fabric/mods/y.jar", "sha256": "C" * 64},
                 "duplicate": good}
        for case, entry in cases.items():
            for check in (smoke.lock_entries, package.safe_lock_entries):
                with self.subTest(case=case, check=check.__module__):
                    with self.assertRaisesRegex(RuntimeError, "Unsafe, duplicate or unpinned"):
                        check({"downloads": [good], "bundled": [entry]})

    def test_smoke_refuses_missing_or_mismatched_candidate_files(self):
        (self.server / MOD).write_bytes(b"not the locked mod")
        with self.assertRaisesRegex(RuntimeError, "differs from its locked SHA-256"):
            smoke.tested_inputs(self.root)
        (self.server / MOD).unlink()
        with self.assertRaisesRegex(RuntimeError, "Missing or unsafe"):
            smoke.tested_inputs(self.root)

    def test_each_cycle_rejects_changed_isolated_or_source_inputs(self):
        isolated = self.root / "isolated"
        shutil.copytree(self.server, isolated)
        java = self.root / "java"
        java.write_bytes(b"test-java")
        java_hash = hashlib.sha256(java.read_bytes()).hexdigest()
        lock_bytes, tested = smoke.tested_inputs(self.root)
        locked = smoke.lock_entries(json.loads(lock_bytes))
        verify = lambda: smoke.verify_cycle_inputs(self.root, isolated, locked, lock_bytes, tested, java, java_hash, {})
        verify()
        for path in (MOD, BRIDGE, GEYSER, "dependencies.lock.json", "runtime.lock.json"):
            with self.subTest(path=path):
                target = isolated / path
                original = target.read_bytes()
                target.write_bytes(b"altered during a cycle")
                with self.assertRaises(RuntimeError):
                    verify()
                target.write_bytes(original)
                verify()
        java.write_bytes(b"changed-java")
        with self.assertRaisesRegex(RuntimeError, "Java changed"):
            verify()

    def test_isolated_libraries_are_observed_and_checked_at_each_boundary(self):
        isolated = self.root / "isolated"
        source_library = self.server / "fabric/libraries/a.jar"
        source_library.parent.mkdir(parents=True, exist_ok=True)
        source_library.write_bytes(b"copied library")
        shutil.copytree(self.server, isolated)
        copied = smoke.library_hashes(self.server)
        # A first startup may download additional libraries. Its actual isolated
        # ready-time snapshot becomes the baseline for shutdown and restarts.
        extra = isolated / "fabric/libraries/downloaded.jar"
        extra.write_bytes(b"downloaded library")
        observed = smoke.verify_runtime_libraries(isolated, copied)
        self.assertEqual(observed["fabric/libraries/downloaded.jar"], hashlib.sha256(b"downloaded library").hexdigest())
        self.assertEqual(smoke.verify_runtime_libraries(isolated, copied, observed), observed)
        extra.write_bytes(b"altered")
        with self.assertRaisesRegex(RuntimeError, "libraries changed"):
            smoke.verify_runtime_libraries(isolated, copied, observed)
        extra.write_bytes(b"downloaded library")
        (isolated / "fabric/libraries/a.jar").write_bytes(b"changed copied library")
        with self.assertRaisesRegex(RuntimeError, "copied Fabric library changed"):
            smoke.verify_runtime_libraries(isolated, copied, observed)

    # Missing, malformed or misplaced evidence.

    def test_old_refresh_evidence_alone_or_beside_new_evidence_is_rejected(self):
        self.write_log([*RUNS, OLD, LIFECYCLE])
        self.assert_rejected("old terrain refresh")
        self.write_log([*RUNS, self.record(), OLD, MARKER, LIFECYCLE])
        self.assert_rejected("old terrain refresh")

    def test_missing_duplicate_or_misplaced_provenance_is_rejected(self):
        record = self.record()
        cases = {"missing": [*RUNS, MARKER, LIFECYCLE],
                 "duplicate": [*RUNS, record, record, MARKER, LIFECYCLE],
                 "indented_copy": [*RUNS, record, "  " + record, MARKER, LIFECYCLE],
                 "before_last_run": [*RUNS[:2], record, RUNS[2], MARKER, LIFECYCLE],
                 "after_marker": [*RUNS, MARKER, record, LIFECYCLE]}
        for case, lines in cases.items():
            with self.subTest(case=case):
                self.write_log(lines)
                self.assert_rejected("exactly one provenance record")

    def test_malformed_or_noncanonical_provenance_is_rejected(self):
        canonical = self.record()[len(smoke.PROVENANCE_PREFIX):]
        cases = {"malformed": (canonical[:-1], "malformed"),
                 "duplicate_key": ('{"format":1,' + canonical[1:], "repeats a key"),
                 "spaced": (json.dumps(json.loads(canonical), sort_keys=True), "not canonical"),
                 "unsorted": (json.dumps(dict(reversed(list(json.loads(canonical).items()))), separators=(",", ":")), "not canonical")}
        for case, (text, message) in cases.items():
            with self.subTest(case=case):
                self.smoke_log(smoke.PROVENANCE_PREFIX + text)
                self.assert_rejected(message)

    def test_unexpected_provenance_structure_is_rejected(self):
        cases = {"runs": dict(runs=2), "bool_runs": dict(runs=True), "kind": dict(kind="terrain-refresh"),
                 "format": dict(format=2), "bool_format": dict(format=True), "java": dict(java={}), "libraries": dict(libraries=[]),
                 "note": dict(not_packaged=""), "extra": dict(eula="true")}
        for case, changes in cases.items():
            with self.subTest(case=case):
                self.smoke_log(self.record(**changes))
                self.assert_rejected("unexpected structure")
        self.smoke_log(self.record(libraries={"server/fabric/libraries/x.jar": "not-a-hash"}))
        self.assert_rejected("invalid SHA-256")
        files = json.loads(self.record()[len(smoke.PROVENANCE_PREFIX):])["files"]
        del files["server/" + GEYSER]
        self.smoke_log(self.record(files=files))
        self.assert_rejected("different set of files")

    def test_incomplete_or_misordered_runs_are_rejected(self):
        record = self.record()
        cases = {"no_marker": [*RUNS, record, LIFECYCLE],
                 "missing_third_restart": [*RUNS[:2], record, MARKER, LIFECYCLE],
                 "marker_before_last_restart": [*RUNS[:2], MARKER, RUNS[2], record, LIFECYCLE],
                 "duplicate_marker": [*RUNS, record, MARKER, MARKER, LIFECYCLE],
                 "indented_marker_only": [*RUNS, record, "  " + MARKER, LIFECYCLE]}
        for case, lines in cases.items():
            with self.subTest(case=case):
                self.write_log(lines)
                self.assert_rejected("did not complete every restart")

    def test_missing_final_lifecycle_is_rejected(self):
        self.write_log([*RUNS, self.record(), LIFECYCLE, MARKER])
        self.assert_rejected("lifecycle")
        self.write_log([*RUNS, self.record(), MARKER])
        self.assert_rejected("lifecycle")

    def test_error_records_are_rejected(self):
        self.write_log([*RUNS, "[Server thread/ERROR]: failed to save chunk", self.record(), MARKER, LIFECYCLE])
        self.assert_rejected("errors")


if __name__ == "__main__":
    unittest.main()
