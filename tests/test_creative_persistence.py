"""Release-gate regressions using genuine supplemental persistence evidence.

No JVM or live world is opened. Successful evidence is never synthesized: the
source-only checkout can skip dependent checks until the real proof is prepared,
and a release validation must rerun this module with zero skips.
"""
import copy
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import package_crossplay
from smoke_creative_wire import VIAFABRIC, VIABACKWARDS

CHECKS = Path("research/integration/creative-bridge")
PROVENANCE = Path("research/integration/provenance.json")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class CreativePersistencePackagingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="infinity-persistence-package-test-")
        self.addCleanup(self.temporary.cleanup)
        self.isolated = Path(self.temporary.name)

    def copy_file(self, relative):
        relative = Path(relative)
        self.assertFalse(relative.is_absolute())
        self.assertNotIn("..", relative.parts)
        target = self.isolated / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
        return target

    def write_receipt(self, receipt):
        path = self.isolated / CHECKS / "persistence-proof.json"
        path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")

    def proof_header(self):
        proof = ROOT / CHECKS / "persistence-proof.json"
        if not proof.is_file():
            self.skipTest("The genuine new persistence proof is not yet prepared; rerun release checks with zero skips")
        receipt = json.loads(self.copy_file(CHECKS / "persistence-proof.json").read_text(encoding="utf-8"))
        provenance = json.loads(self.copy_file(PROVENANCE).read_text(encoding="utf-8"))
        for relative in receipt["source_hashes"]:
            self.copy_file(relative)
        self.assertEqual(receipt["source_hashes"],
                         {name:digest(self.isolated / name) for name in receipt["source_hashes"]},
                         "Negative fixtures must begin with the actually validated source bytes")
        self.assertEqual(receipt["version"],package_crossplay.VERSION)
        self.assertEqual(receipt["java_sources"],provenance["java_sources"])
        self.assertEqual(receipt["mod_sha256"],provenance["mod_sha256"])
        self.assertEqual(receipt["original_test_mod_sha256"],provenance["test_mod_sha256"])
        self.assertEqual(receipt["native_provenance_sha256"],digest(self.isolated / PROVENANCE))
        return receipt,provenance

    def wire_inputs(self, receipt):
        wire_path = self.copy_file(CHECKS / "wire-proof.json")
        wire = json.loads(wire_path.read_text(encoding="utf-8"))
        self.assertEqual(digest(wire_path),receipt["wire_receipt_sha256"])
        for relative in wire["source_hashes"]:
            self.copy_file(relative)
        self.assertEqual(wire["source_hashes"],
                         {name:digest(self.isolated / name) for name in wire["source_hashes"]},
                         "The nested wire gate must read its genuinely validated source bytes")
        runtime = Path(wire["runtime"])
        self.assertEqual(runtime.parts[:2],("research","creative-wire"))
        relative_files = [
            CHECKS / "ready.json", CHECKS / "startup.json",
            Path("research/integration/crossplay-export/infinity-catalogue.json"),
            runtime / "results.xml", runtime / "native-wire.json", runtime / "wire.log",
            runtime / "crossplay-export/infinity-catalogue.json",
            runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}.jar",
            runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}-gametest.jar",
            Path("server/geyser/extensions/InfinityCreativeBridge.jar"),
        ]
        for dependency in (VIAFABRIC,VIABACKWARDS):
            relative_files.extend((runtime / "mods" / dependency,Path("server/fabric/mods") / dependency))
        for relative in relative_files:
            self.copy_file(relative)
        return json.loads((ROOT / "server/dependencies.lock.json").read_text(encoding="utf-8"))

    def evidence_inputs(self):
        receipt,provenance = self.proof_header()
        lock = self.wire_inputs(receipt)
        runtime = Path(receipt["runtime"])
        self.assertEqual(runtime.parts[:2],("research","creative-persistence"))
        for relative in (
            runtime / "results.xml", runtime / "native-persistence.json", runtime / "persistence.log",
            runtime / "captured-ready.json", runtime / "captured-catalogue.json",
            runtime / "crossplay-export/infinity-catalogue.json",
            runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}.jar",
            runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}-gametest.jar",
        ):
            self.copy_file(relative)
        # Late semantic rejections precede cached runtime validation, so the
        # large Minecraft/Fabric caches are deliberately absent in these fixtures.
        self.assertFalse((self.isolated / runtime / ".fabric").exists())
        return receipt,provenance,lock,runtime

    def replace_native(self, receipt, runtime, native):
        target = self.isolated / runtime / "native-persistence.json"
        target.write_text(json.dumps(native, indent=2) + "\n", encoding="utf-8")
        receipt["proof"] = native
        receipt["native_proof_sha256"] = digest(target)
        self.write_receipt(receipt)

    def test_missing_receipt_cannot_reuse_other_native_evidence(self):
        with self.assertRaisesRegex(ValueError,"Missing or oversized required proof"):
            package_crossplay.verified_creative_persistence(self.isolated,{}, {})

    def test_missing_or_unsupported_format_is_refused_before_artifact_reads(self):
        receipt,provenance = self.proof_header()
        variants = [None,0,2,"1",True,1.0]
        for value in variants:
            with self.subTest(format_version=value,value_type=type(value).__name__):
                changed = copy.deepcopy(receipt)
                if value is None:
                    changed.pop("format_version",None)
                else:
                    changed["format_version"] = value
                self.write_receipt(changed)
                self.assertFalse((self.isolated / CHECKS / "wire-proof.json").exists())
                with self.assertRaisesRegex(RuntimeError,"Unsupported Creative persistence proof format"):
                    package_crossplay.verified_creative_persistence(self.isolated,{},provenance)

    def test_empty_missing_or_extra_executed_via_binding_is_refused_before_artifact_reads(self):
        receipt,provenance = self.proof_header()
        original = receipt["executed_via_dependencies"]
        self.assertEqual(set(original),{VIAFABRIC,VIABACKWARDS},"The real proof must record both exact executed translators")
        variants = [({},"empty"),
                    ({VIAFABRIC:original[VIAFABRIC]},"missing ViaBackwards"),
                    ({VIABACKWARDS:original[VIABACKWARDS]},"missing ViaFabric"),
                    (dict(original,unrelated_dependency="0"*64),"extra dependency")]
        for bindings,label in variants:
            with self.subTest(bindings=label):
                changed = copy.deepcopy(receipt)
                changed["executed_via_dependencies"] = bindings
                self.write_receipt(changed)
                self.assertFalse((self.isolated / CHECKS / "wire-proof.json").exists())
                with self.assertRaisesRegex(RuntimeError,"Creative persistence must record both executed Via dependencies"):
                    package_crossplay.verified_creative_persistence(self.isolated,{},provenance)

    def test_changed_supplementary_or_imported_source_rejects_before_wire_or_runtime(self):
        receipt,provenance = self.proof_header()
        for relative in receipt["source_hashes"]:
            with self.subTest(source=relative):
                target = self.isolated / relative
                original = target.read_bytes()
                try:
                    target.write_bytes(original+b"\n# edited after the genuine persistence proof\n")
                    self.assertFalse((self.isolated / CHECKS / "wire-proof.json").exists())
                    with self.assertRaisesRegex(RuntimeError,"Creative persistence source differs from its proof"):
                        package_crossplay.verified_creative_persistence(self.isolated,{},provenance)
                finally:
                    target.write_bytes(original)

    def test_different_native_mod_test_jar_or_java_source_binding_rejects_before_wire(self):
        _,provenance = self.proof_header()
        variants = {}
        for field in ("mod_sha256","test_mod_sha256"):
            changed = copy.deepcopy(provenance)
            changed[field] = "0"*64 if changed[field] != "0"*64 else "1"*64
            variants[field] = changed
        changed = copy.deepcopy(provenance)
        source = next(iter(changed["java_sources"]))
        changed["java_sources"][source] = "0"*64 if changed["java_sources"][source] != "0"*64 else "1"*64
        variants["java_sources"] = changed
        for field,changed in variants.items():
            with self.subTest(binding=field):
                self.assertFalse((self.isolated / CHECKS / "wire-proof.json").exists())
                with self.assertRaisesRegex(RuntimeError,"Creative persistence proof differs from the native-tested source/jars"):
                    package_crossplay.verified_creative_persistence(self.isolated,{},changed)

    def test_changed_native_provenance_file_rejects_even_when_decoded_values_match(self):
        _,provenance = self.proof_header()
        path = self.isolated / PROVENANCE
        path.write_bytes(path.read_bytes()+b"\n")
        with self.assertRaisesRegex(RuntimeError,"Creative persistence proof differs from the native-tested source/jars"):
            package_crossplay.verified_creative_persistence(self.isolated,{},provenance)

    def test_different_wire_capture_binding_rejects_before_persistence_runtime(self):
        receipt,provenance = self.proof_header()
        lock = self.wire_inputs(receipt)
        receipt["wire_receipt_sha256"] = "0"*64
        self.write_receipt(receipt)
        with self.assertRaisesRegex(RuntimeError,"Creative persistence proof uses a different wire capture"):
            package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_absolute_parent_or_nonresearch_runtime_path_is_refused_before_runtime_reads(self):
        receipt,provenance = self.proof_header()
        lock = self.wire_inputs(receipt)
        for relative in ("/tmp/infinity-live-world","research/creative-persistence/../../server","server/fabric"):
            with self.subTest(runtime=relative):
                changed = copy.deepcopy(receipt)
                changed["runtime"] = relative
                self.write_receipt(changed)
                with self.assertRaisesRegex(RuntimeError,"Creative persistence runtime must be an isolated research world"):
                    package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_changed_report_bytes_reject_before_parsing_or_cached_runtime_reads(self):
        _,provenance,lock,runtime = self.evidence_inputs()
        report = self.isolated / runtime / "results.xml"
        report.write_bytes(report.read_bytes()+b"\n<!-- changed after native validation -->\n")
        with self.assertRaisesRegex(RuntimeError,"Creative persistence evidence changed"):
            package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_failing_errored_or_skipped_native_case_cannot_be_rebound_as_a_passing_report(self):
        receipt,provenance,lock,runtime = self.evidence_inputs()
        report = self.isolated / runtime / "results.xml"
        original = report.read_bytes()
        for status in ("failure","error","skipped"):
            with self.subTest(status=status):
                tree = ET.fromstring(original)
                case = next(tree.iter("testcase"))
                ET.SubElement(case,status).text = "Rejected release fixture"
                report.write_bytes(ET.tostring(tree,encoding="utf-8",xml_declaration=True))
                changed = copy.deepcopy(receipt)
                changed["report_sha256"] = digest(report)
                self.write_receipt(changed)
                with self.assertRaisesRegex(RuntimeError,"Creative persistence native tests must both pass without skips"):
                    package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_incomplete_receive_disk_rejoin_or_variant_counts_are_refused(self):
        receipt,provenance,lock,runtime = self.evidence_inputs()
        counts = ("hotbar_cases","distinct_item_count_cases","disk_cases","rejoin_cases",
                  "canonical_identities","native_save_batches","genuine_reconnects","variant_inputs",
                  "framed_inbound_packets","backpack_styles_preserved")
        for field in counts:
            with self.subTest(count=field):
                native = copy.deepcopy(receipt["proof"])
                native[field] -= 1
                self.replace_native(copy.deepcopy(receipt),runtime,native)
                with self.assertRaisesRegex(RuntimeError,"Creative persistence proof does not cover receive, disk, reconnect and observer boundaries"):
                    package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_missing_receive_component_backpack_or_observer_guarantee_is_refused(self):
        receipt,provenance,lock,runtime = self.evidence_inputs()
        flags = ("catalogue_equivalent","receive_hook_canonical_before_listener",
                 "renamed_damaged_weapon_preserved","personal_backpack_preserved","observer_file_unchanged")
        for field in flags:
            with self.subTest(guarantee=field):
                native = copy.deepcopy(receipt["proof"])
                native[field] = False
                self.replace_native(copy.deepcopy(receipt),runtime,native)
                with self.assertRaisesRegex(RuntimeError,"Creative persistence proof does not cover receive, disk, reconnect and observer boundaries"):
                    package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_wrong_observer_scope_or_invalid_saved_file_digest_is_refused(self):
        receipt,provenance,lock,runtime = self.evidence_inputs()
        for field,value in (("observer_check_scope","after ordinary server shutdown saves"),
                            ("observer_file_sha256","not-a-native-file-sha256")):
            with self.subTest(field=field):
                native = copy.deepcopy(receipt["proof"])
                native[field] = value
                self.replace_native(copy.deepcopy(receipt),runtime,native)
                with self.assertRaisesRegex(RuntimeError,"Creative persistence proof does not cover receive, disk, reconnect and observer boundaries"):
                    package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_tampered_executed_packet_capture_copy_is_refused_before_cached_runtime_reads(self):
        receipt,provenance,lock,runtime = self.evidence_inputs()
        capture = self.isolated / runtime / "captured-ready.json"
        self.assertEqual(digest(capture),receipt["capture_sha256"],"Start with the actual native-executed capture bytes")
        original = capture.read_bytes()
        capture.write_bytes(original+b" \n")
        self.assertEqual(json.loads(capture.read_bytes()),json.loads(original),"Even equivalent JSON must preserve its exact executed-input binding")
        self.assertFalse((self.isolated / runtime / ".fabric").exists())
        with self.assertRaisesRegex(RuntimeError,"Creative persistence executed capture differs from its bound inputs"):
            package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_tampered_executed_catalogue_copy_is_refused_before_cached_runtime_reads(self):
        receipt,provenance,lock,runtime = self.evidence_inputs()
        catalogue = self.isolated / runtime / "captured-catalogue.json"
        self.assertEqual(digest(catalogue),receipt["captured_catalogue_sha256"],"Start with the actual native-executed catalogue bytes")
        original = catalogue.read_bytes()
        catalogue.write_bytes(original+b" \n")
        self.assertEqual(json.loads(catalogue.read_bytes()),json.loads(original))
        self.assertFalse((self.isolated / runtime / ".fabric").exists())
        with self.assertRaisesRegex(RuntimeError,"Creative persistence executed capture differs from its bound inputs"):
            package_crossplay.verified_creative_persistence(self.isolated,lock,provenance)

    def test_genuinely_prepared_current_proof_passes_with_exact_bundled_bytes(self):
        receipt,provenance = self.proof_header()
        lock = json.loads((ROOT / "server/dependencies.lock.json").read_text(encoding="utf-8"))
        result = package_crossplay.verified_creative_persistence(ROOT,lock,provenance)
        runtime = Path(receipt["runtime"])
        self.assertEqual(result,{
            "checks/creative-persistence-proof.json":(ROOT / CHECKS / "persistence-proof.json").read_bytes(),
            "checks/creative-persistence-native.json":(ROOT / runtime / "native-persistence.json").read_bytes(),
            "checks/creative-persistence-native-catalogue.json":(ROOT / runtime / "crossplay-export/infinity-catalogue.json").read_bytes(),
            "checks/creative-persistence-results.xml":(ROOT / runtime / "results.xml").read_bytes(),
            "checks/creative-persistence-test.jar":(ROOT / runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}-gametest.jar").read_bytes(),
            "checks/creative-persistence-log.txt":(ROOT / runtime / "persistence.log").read_bytes(),
        })


if __name__ == "__main__":
    unittest.main()
