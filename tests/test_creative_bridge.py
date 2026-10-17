"""Reject malformed local Creative catalogues and stale bundled adapters.

These checks use temporary configuration files and inspect prepared artifacts;
they neither start a JVM nor prove authenticated client inventory/rendering.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import package_crossplay
from verify_owned_equipment import creative_capture_counts, PROTOCOLS

PINNED_GEYSER_SHA256 = "17a9e00f3be106c797066664679ca73ebf25af693a750f378a9669ca93b4c07e"
SPEC = importlib.util.spec_from_file_location(
    "creative_bridge_builder_for_tests", ROOT / "tools/build_creative_bridge.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65_536), b""):
            digest.update(block)
    return digest.hexdigest()


def catalogue():
    return {
        "format_version": 1,
        "minecraft": "26.3",
        "items": [{
            "path": "aurora_chestplate",
            "base": "minecraft:netherite_chestplate",
            "name": "Aurora Chestplate",
            "icon": "convergence_aurora_chestplate",
            "max_stack_size": 1,
            "category": "equipment",
            "max_damage": 640,
            "slot": "chest",
            "protection": 8,
            "handheld": False,
            "native_appearance": False,
        }],
    }


class CreativeCatalogueTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="infinity-catalogue-test-")
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / "infinity-catalogue.json"

    def validate(self, data):
        self.path.write_text(json.dumps(data), encoding="utf-8")
        return BUILDER.validate_catalogue(self.path)

    def test_valid_catalogue_preserves_public_item_count_and_exact_input_digest(self):
        result = self.validate(catalogue())
        self.assertEqual(result, {"items": 1, "sha256": sha256(self.path)})

    def test_duplicate_header_or_item_keys_cannot_silently_replace_definitions(self):
        valid = json.dumps(catalogue())
        candidates = (
            valid.replace('"format_version": 1', '"format_version": 1, "format_version": 1'),
            valid.replace('"base": "minecraft:netherite_chestplate"',
                          '"base": "minecraft:netherite_chestplate", "base": "minecraft:diamond"'),
        )
        for raw in candidates:
            with self.subTest(raw_header=raw[:70]):
                self.path.write_text(raw, encoding="utf-8")
                with self.assertRaises(ValueError):
                    BUILDER.validate_catalogue(self.path)

    def test_size_boundary_accepts_bounded_json_and_refuses_one_extra_byte(self):
        raw = json.dumps(catalogue()).encode("utf-8")
        self.path.write_bytes(raw + b" " * (131_072 - len(raw)))
        self.assertEqual(BUILDER.validate_catalogue(self.path)["items"], 1)
        with self.path.open("ab") as stream:
            stream.write(b" ")
        with self.assertRaises(ValueError):
            BUILDER.validate_catalogue(self.path)

    def test_duplicate_canonical_paths_cannot_create_two_pickup_identities(self):
        data = catalogue()
        duplicate = copy.deepcopy(data["items"][0])
        duplicate["base"] = "minecraft:diamond_chestplate"
        data["items"].append(duplicate)
        with self.assertRaises(ValueError):
            self.validate(data)

    def test_unknown_fields_cannot_smuggle_nbt_or_commands_into_public_definitions(self):
        for location, field in (("header", "commands"), ("item", "nbt")):
            with self.subTest(location=location):
                data = catalogue()
                target = data if location == "header" else data["items"][0]
                target[field] = {"untrusted": "ignored fields must instead be rejected"}
                with self.assertRaises(ValueError):
                    self.validate(data)

    def test_booleans_cannot_impersonate_numeric_version_or_item_components(self):
        for field in ("format_version", "max_stack_size", "max_damage", "protection"):
            with self.subTest(field=field):
                data = catalogue()
                target = data if field == "format_version" else data["items"][0]
                target[field] = True
                with self.assertRaises(ValueError):
                    self.validate(data)

    def test_invalid_equipment_slots_or_nonvanilla_bases_are_refused(self):
        for field, value in (("slot", "chestplate"), ("slot", ""),
                             ("base", "minecraft:air"), ("base", "infinity_catalog:aurora_chestplate"),
                             ("base", "netherite_chestplate"), ("base", "minecraft:../diamond")):
            with self.subTest(field=field, value=value):
                data = catalogue()
                data["items"][0][field] = value
                with self.assertRaises(ValueError):
                    self.validate(data)

    def test_damageable_stack_and_excessive_registration_count_are_refused(self):
        data = catalogue()
        data["items"][0]["max_stack_size"] = 64
        with self.assertRaises(ValueError):
            self.validate(data)
        data = catalogue()
        template = data["items"][0]
        data["items"] = [dict(template, path=f"piece_{index}") for index in range(129)]
        with self.assertRaises(ValueError):
            self.validate(data)


class PreparedCreativeBridgeTests(unittest.TestCase):
    def test_packaging_refuses_source_changed_after_build_before_opening_jars(self):
        lock = json.loads((ROOT / "server/dependencies.lock.json").read_text(encoding="utf-8"))
        inputs = [ROOT / "tools/build_creative_bridge.py", ROOT / "geyser-extension/extension.yml",
                  *sorted((ROOT / "geyser-extension/src/main/java").rglob("*.java"))]
        with tempfile.TemporaryDirectory(prefix="infinity-stale-bridge-test-") as temporary:
            isolated = Path(temporary)
            for original in inputs:
                copied = isolated / original.relative_to(ROOT)
                copied.parent.mkdir(parents=True, exist_ok=True)
                copied.write_bytes(original.read_bytes())
            self.assertEqual(lock["creative_bridge"]["source_hashes"],
                             {path.relative_to(ROOT).as_posix(): sha256(isolated / path.relative_to(ROOT))
                              for path in inputs},
                             "The isolated fixture must start from the genuinely prepared source")
            changed = next(path for path in inputs if path.suffix == ".java")
            copied = isolated / changed.relative_to(ROOT)
            with copied.open("ab") as stream:
                stream.write(b"\n// Source edited after the prepared bridge build.\n")
            # No jar exists here: only a stale source check can reject at this stage.
            with self.assertRaisesRegex(RuntimeError, "Creative bridge source differs from the prepared jar"):
                package_crossplay.verified_creative_bridge(isolated, lock)

    def test_prepared_adapter_is_bound_to_current_sources_and_exact_geyser(self):
        server = ROOT / "server"
        lock_path = server / "dependencies.lock.json"
        bridge = server / "geyser/extensions/InfinityCreativeBridge.jar"
        geyser = server / "geyser/Geyser-Standalone.jar"
        if not lock_path.is_file() or not bridge.is_file() or not geyser.is_file():
            self.skipTest("Prepared bridge artifacts are not present in this source-only checkout")
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
        self.assertIn("creative_bridge", lock,
                      "A prepared extension must have a source/dependency binding in the lock")
        binding = lock["creative_bridge"]
        self.assertEqual(type(binding["format_version"]), int)
        self.assertEqual(binding["format_version"], 1)
        self.assertEqual(BUILDER.PIN, PINNED_GEYSER_SHA256)
        self.assertEqual(binding["geyser_sha256"], PINNED_GEYSER_SHA256)
        self.assertEqual(sha256(geyser), PINNED_GEYSER_SHA256,
                         "A newer or altered Geyser jar needs an adapter compatibility review")

        source_root = ROOT / "geyser-extension"
        inputs = [ROOT / "tools/build_creative_bridge.py", source_root / "extension.yml",
                  *sorted((source_root / "src/main/java").rglob("*.java"))]
        self.assertTrue(any(path.suffix == ".java" for path in inputs))
        expected = {path.relative_to(ROOT).as_posix(): sha256(path) for path in inputs}
        self.assertEqual(binding["source_hashes"], expected,
                         "Edited, added or removed bridge source must force a rebuild before packaging")

        extension_hash = sha256(bridge)
        self.assertEqual(binding["extension_sha256"], extension_hash)
        records = [entry for entry in lock["bundled"]
                   if entry["path"] == "geyser/extensions/InfinityCreativeBridge.jar"]
        self.assertEqual(len(records), 1, "The adapter must appear exactly once in bundled checksums")
        self.assertEqual(records[0]["sha256"], extension_hash,
                         "The packaged add-on and its dependency/source receipt must agree")
        with zipfile.ZipFile(bridge) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(archive.read("extension.yml"), (source_root / "extension.yml").read_bytes())
            self.assertIn("dev/infinityhub/geyser/InfinityCreativeBridge.class", archive.namelist())


class CreativeWirePackagingTests(unittest.TestCase):
    """Exercise the release gate against copied, actually generated proof bytes."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="infinity-wire-package-test-")
        self.addCleanup(self.temporary.cleanup)
        self.isolated = Path(self.temporary.name)
        self.checks = Path("research/integration/creative-bridge")

    def copy_file(self, relative):
        target = self.isolated / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
        return target

    def copy_proof_header(self):
        required = [self.checks / "wire-proof.json", Path("research/integration/provenance.json")]
        if not all((ROOT / relative).is_file() for relative in required):
            self.skipTest("Actual supplementary/native proof is not prepared in this source-only checkout")
        receipt_path = self.copy_file(required[0])
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
        provenance = json.loads((ROOT / required[1]).read_text(encoding="utf-8"))
        for relative in ("tools/smoke_creative_wire.py",
                         "tools/native_checks/CreativeBridgeWireGameTests.java"):
            self.copy_file(relative)
        self.assertEqual(receipt["source_hashes"],
                         {relative: sha256(self.isolated / relative)
                          for relative in receipt["source_hashes"]},
                         "The rejection fixture must start from the genuinely validated supplementary source")
        self.assertEqual(receipt["java_sources"], provenance["java_sources"])
        self.assertEqual(receipt["mod_sha256"], provenance["mod_sha256"])
        self.assertEqual(receipt["original_test_mod_sha256"], provenance["test_mod_sha256"])
        return receipt, provenance

    def test_missing_wire_receipt_refuses_without_accepting_other_native_evidence(self):
        with self.assertRaisesRegex(ValueError, "Missing or oversized required proof"):
            package_crossplay.verified_creative_wire(self.isolated, {}, {})

    def test_changed_supplementary_source_refuses_before_opening_proof_artifacts(self):
        receipt, provenance = self.copy_proof_header()
        for relative in receipt["source_hashes"]:
            with self.subTest(source=relative):
                target = self.isolated / relative
                original = target.read_bytes()
                try:
                    target.write_bytes(original + b"\n# changed after the successful supplementary proof\n")
                    # No XML, native JSON or jars are copied: the source check must reject first.
                    with self.assertRaisesRegex(RuntimeError, "Creative wire source differs from its proof"):
                        package_crossplay.verified_creative_wire(self.isolated, {}, provenance)
                finally:
                    target.write_bytes(original)

    def test_different_original_native_test_jar_binding_refuses(self):
        receipt, provenance = self.copy_proof_header()
        changed = copy.deepcopy(provenance)
        changed["test_mod_sha256"] = "0" * 64
        self.assertNotEqual(changed["test_mod_sha256"], receipt["original_test_mod_sha256"])
        with self.assertRaisesRegex(RuntimeError, "Creative wire proof differs from the current native-tested source/jars"):
            package_crossplay.verified_creative_wire(self.isolated, {}, changed)

    def test_different_normal_native_source_binding_refuses(self):
        _, provenance = self.copy_proof_header()
        changed = copy.deepcopy(provenance)
        relative = next(iter(changed["java_sources"]))
        original = changed["java_sources"][relative]
        changed["java_sources"][relative] = ("0" if original[0] != "0" else "1") + original[1:]
        with self.assertRaisesRegex(RuntimeError, "Creative wire proof differs from the current native-tested source/jars"):
            package_crossplay.verified_creative_wire(self.isolated, {}, changed)

    def test_genuinely_prepared_current_wire_proof_passes_from_public_temporary_copy(self):
        receipt, provenance = self.copy_proof_header()
        runtime = Path(receipt["runtime"])
        relative_files = [
            self.checks / "ready.json", self.checks / "startup.json",
            Path("research/integration/crossplay-export/infinity-catalogue.json"),
            runtime / "results.xml", runtime / "native-wire.json", runtime / "wire.log",
            runtime / "crossplay-export/infinity-catalogue.json",
            runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}.jar",
            runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}-gametest.jar",
            runtime / "mods/ViaFabric-0.4.22+186-26.x.jar",
            runtime / "mods/ViaBackwards-5.12.0.jar",
            Path("server/fabric/mods") / f"Infinity-Armor-{package_crossplay.VERSION}.jar",
            Path("server/geyser/extensions/InfinityCreativeBridge.jar"),
            Path("server/fabric/mods/ViaFabric-0.4.22+186-26.x.jar"),
            Path("server/fabric/mods/ViaBackwards-5.12.0.jar"),
        ]
        for relative in relative_files:
            self.copy_file(relative)
        lock = json.loads((ROOT / "server/dependencies.lock.json").read_text(encoding="utf-8"))
        result = package_crossplay.verified_creative_wire(self.isolated, lock, provenance)
        self.assertEqual(result, {
            "checks/creative-bridge-wire-proof.json": (self.isolated / self.checks / "wire-proof.json").read_bytes(),
            "checks/creative-bridge-native-wire.json": (self.isolated / runtime / "native-wire.json").read_bytes(),
            "checks/creative-bridge-wire-results.xml": (self.isolated / runtime / "results.xml").read_bytes(),
            "checks/creative-bridge-captured-catalogue.json": (self.isolated / "research/integration/crossplay-export/infinity-catalogue.json").read_bytes(),
            "checks/creative-bridge-native-catalogue.json": (self.isolated / runtime / "crossplay-export/infinity-catalogue.json").read_bytes(),
            "checks/creative-bridge-wire-test.jar": (self.isolated / runtime / "mods" / f"Infinity-Armor-{package_crossplay.VERSION}-gametest.jar").read_bytes(),
            "checks/creative-bridge-wire-log.txt": (self.isolated / runtime / "wire.log").read_bytes(),
        })
        expected = creative_capture_counts(
            json.loads((self.isolated / "research/integration/crossplay-export/infinity-catalogue.json").read_text()),
            json.loads((self.isolated / self.checks / "ready.json").read_text()))
        self.assertEqual(receipt["proof"]["hotbar_cases"], expected["hotbar_cases"])
        self.assertEqual(receipt["proof"]["overflow_cases"], expected["canonical_identities"] * len(PROTOCOLS))
        self.assertEqual(receipt["proof"]["survival_cases"], expected["hotbar_cases"])
        self.assertEqual(receipt["proof"]["via_survival_cases"], expected["hotbar_cases"])


if __name__ == "__main__":
    unittest.main()
