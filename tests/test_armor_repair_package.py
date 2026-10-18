"""The repair ZIP must prove its narrow delta and preserve the full release."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("armor_repair_packager", ROOT / "tools/package_armor_visibility_repair.py")
PACKAGER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGER)


def archive_bytes(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items() if isinstance(entries, dict) else entries:
            archive.writestr(name, data)
    return output.getvalue()


def json_bytes(data):
    return json.dumps(data, sort_keys=True).encode("utf-8")


class Fixture:
    def __init__(self, root):
        self.root = root
        self.baseline = root / "immutable-explore43.zip"
        self.server = root / "prepared-server"
        self.mappings = root / "prepared-mappings"
        self.notes = root / "repair.md"
        self.output = root / "repair-output/repair.zip"
        self.server.mkdir()
        self.mappings.mkdir()
        self.notes.write_text("Resource-only repair; device rendering still requires an iPad check.\n")
        manifest = {"format_version": 2,
                    "header": {"uuid": "e62e5f9c-a11e-53cd-8430-495f301dfe4a", "version": [2, 13, 43]},
                    "modules": [{"type": "resources", "uuid": "5d7e0237-c610-5d08-908c-98f23291753c", "version": [2, 13, 43]}]}
        self.old_pack = {"manifest.json": json_bytes(manifest), "textures/unchanged.bin": b"original artwork"}
        for name in PACKAGER.CONTROLLERS:
            guard = "query.is_item_name_any('slot.armor.head', 0, 'convergence:helmet', 'infinity_catalog:helmet')"
            controller = {"format_version": "1.8.0", "render_controllers": {"controller.render.fixture": {
                "geometry": "Geometry.default", "part_visibility": [
                    {"fixture_head": guard + PACKAGER.LEGACY_SUFFIX}, {"fixture_arm": guard}]}}}
            self.old_pack[name] = json_bytes(controller)
        self.new_pack = copy.deepcopy(self.old_pack)
        manifest["header"]["version"] = list(PACKAGER.REPAIR_VERSION)
        manifest["modules"][0]["version"] = list(PACKAGER.REPAIR_VERSION)
        self.new_pack["manifest.json"] = json_bytes(manifest)
        for name in PACKAGER.CONTROLLERS:
            self.new_pack[name] = self.old_pack[name].replace(PACKAGER.LEGACY_SUFFIX.encode(), PACKAGER.REVISED_SUFFIX.encode())
        artifacts = {"fabric/mods/Infinity-Armor-2.13.0-explore.43.jar": b"same production mod",
                     "geyser/extensions/InfinityCreativeBridge.jar": b"same bridge",
                     "geyser/Geyser-Standalone.jar": b"same downloaded Geyser"}
        old_pack_data = archive_bytes(self.old_pack)
        self.old_lock = {"minecraft": "26.3", "version": "2.13.0-explore.43",
                         "downloads": [{"path": "geyser/Geyser-Standalone.jar",
                                        "sha256": PACKAGER.digest(artifacts["geyser/Geyser-Standalone.jar"])}],
                         "bundled": [{"path": name, "sha256": PACKAGER.digest(data)}
                                     for name, data in artifacts.items() if name != "geyser/Geyser-Standalone.jar"]
                                    + [{"path": PACKAGER.PACK_PATH, "sha256": PACKAGER.digest(old_pack_data)}],
                         "creative_bridge": {"source_hashes": {"fixture.java": "b" * 64}}}
        self.new_lock = copy.deepcopy(self.old_lock)
        inputs = {}
        for name in PACKAGER.MAPPINGS:
            data = json_bytes({"mapping": name})
            (self.mappings / name).write_bytes(data)
            inputs["/unread/baseline-export/" + name] = PACKAGER.digest(data)
        runtime = json_bytes({"java": "25", "sha256": "c" * 64})
        self.baseline_entries = {PACKAGER.LOCK_PATH: json_bytes(self.old_lock), PACKAGER.PACK_PATH: old_pack_data,
                                 "runtime.lock.json": runtime,
                                 "checks/armor-equipment-native.json": json_bytes({"input_sha256": inputs}),
                                 **{name: data for name, data in artifacts.items() if name != "geyser/Geyser-Standalone.jar"}}
        for name, data in {**artifacts, "runtime.lock.json": runtime}.items():
            target = self.server / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.write_baseline()
        self.write_pack()

    def write_baseline(self, extra=()):
        entries = [(PACKAGER.BASELINE_PREFIX + name, data) for name, data in self.baseline_entries.items()]
        self.baseline.write_bytes(archive_bytes(entries + list(extra)))
        self.baseline_hash = PACKAGER.digest(self.baseline.read_bytes())

    def write_lock(self):
        (self.server / PACKAGER.LOCK_PATH).write_bytes(json_bytes(self.new_lock))

    def write_pack(self):
        data = archive_bytes(self.new_pack)
        target = self.server / PACKAGER.PACK_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        next(entry for entry in self.new_lock["bundled"] if entry["path"] == PACKAGER.PACK_PATH)["sha256"] = PACKAGER.digest(data)
        self.write_lock()

    def build(self, **kwargs):
        return PACKAGER.build_repair(self.baseline, self.server, self.mappings, self.notes, self.output,
                                      expected_baseline_sha256=kwargs.pop("expected_baseline_sha256", self.baseline_hash), **kwargs)


class ArmorRepairPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def assert_refused(self, **kwargs):
        with self.assertRaises(ValueError):
            self.fixture.build(**kwargs)
        self.assertFalse(self.fixture.output.exists(), "A refused repair cannot leave an output ZIP")

    def test_complete_resource_delta_bundles_only_four_files_and_hashes(self):
        f = self.fixture
        baseline_bytes = f.baseline.read_bytes()
        report = f.build(expected_counts=dict.fromkeys(PACKAGER.CONTROLLERS, 1))
        bundle = PACKAGER.safe_zip(f.output.read_bytes())
        expected = {PACKAGER.LOCK_PATH, PACKAGER.PACK_PATH, PACKAGER.NOTES_PATH, "PATCH.json"}
        self.assertEqual(set(bundle), {PACKAGER.REPAIR_PREFIX + name for name in expected})
        self.assertEqual(set(report["bundle_files"]), expected)
        for name, checksum in report["payload_sha256"].items():
            self.assertEqual(PACKAGER.digest(bundle[PACKAGER.REPAIR_PREFIX + name]), checksum)
        self.assertEqual(report, json.loads(bundle[PACKAGER.REPAIR_PREFIX + "PATCH.json"]))
        self.assertEqual(set(report["pack_changes"]["changed_entries"]), PACKAGER.ALLOWED_PACK_CHANGES)
        self.assertEqual(report["pack_changes"]["unchanged_entry_count"], 1)
        self.assertEqual(report["pack_changes"]["added_entries"], [])
        self.assertEqual(report["pack_changes"]["deleted_entries"], [])
        self.assertFalse(report["device_rendering_verified"])
        self.assertFalse(report["live_installation_performed"])
        self.assertNotIn(str(f.root), json.dumps(report))
        self.assertEqual(f.baseline.read_bytes(), baseline_bytes)

    def test_exact_baseline_hash_is_required(self):
        self.assert_refused(expected_baseline_sha256="0" * 64)

    def test_default_baseline_pin_cannot_accept_a_caller_fixture(self):
        f = self.fixture
        with self.assertRaises(ValueError):
            PACKAGER.build_repair(f.baseline, f.server, f.mappings, f.notes, f.output)

    def test_zip_rejects_traversal_absolute_windows_and_runtime_paths(self):
        for name in ("../escape", "/absolute", "C:/windows", "folder\\escape", "folder//x", "world/player.dat", "key.pem"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                PACKAGER.safe_zip(archive_bytes({name: b"x"}))

    def test_zip_rejects_duplicate_and_case_colliding_names(self):
        for names in (("same", "same"), ("same", "SAME")):
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                data = archive_bytes([(name, b"x") for name in names])
            with self.subTest(names=names), self.assertRaises(ValueError):
                PACKAGER.safe_zip(data)

    def test_zip_rejects_symlinks_and_bounded_expansion(self):
        link = zipfile.ZipInfo("linked")
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(ValueError):
            PACKAGER.safe_zip(archive_bytes([(link, b"outside")]))
        with patch.object(PACKAGER, "MAX_TOTAL", 8), self.assertRaises(ValueError):
            PACKAGER.safe_zip(archive_bytes({"oversized": b"123456789"}))
        with self.assertRaises(ValueError):
            PACKAGER.safe_zip(archive_bytes({"a": b"", "b": b""}), maximum_entries=1)

    def test_unsafe_baseline_is_rejected_even_with_matching_caller_hash(self):
        self.fixture.write_baseline(extra=[("../unsafe", b"x")])
        self.assert_refused()

    def test_duplicate_pack_member_is_rejected(self):
        f = self.fixture
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            duplicate = archive_bytes(list(f.new_pack.items()) + [("manifest.json", f.new_pack["manifest.json"])])
        (f.server / PACKAGER.PACK_PATH).write_bytes(duplicate)
        self.assert_refused()

    def test_added_and_deleted_pack_entries_are_rejected(self):
        for add in (True, False):
            with self.subTest(add=add):
                entries = copy.deepcopy(self.fixture.new_pack)
                if add:
                    entries["extra.txt"] = b"unapproved"
                else:
                    entries.pop("textures/unchanged.bin")
                with self.assertRaises(ValueError):
                    PACKAGER.validate_pack(archive_bytes(self.fixture.old_pack), archive_bytes(entries))

    def test_artwork_or_other_pack_bytes_cannot_change(self):
        self.fixture.new_pack["textures/unchanged.bin"] = b"changed art"
        self.fixture.write_pack()
        self.assert_refused()

    def test_controller_equipped_item_guard_cannot_change(self):
        name = PACKAGER.CONTROLLERS[0]
        self.fixture.new_pack[name] = self.fixture.new_pack[name].replace(b"convergence:helmet", b"convergence:wrong")
        self.fixture.write_pack()
        self.assert_refused()

    def test_controller_arms_and_unrelated_fields_cannot_change(self):
        f = self.fixture
        original = json.loads(f.new_pack[PACKAGER.CONTROLLERS[0]])
        for kind in ("arm", "geometry", "extra"):
            with self.subTest(kind=kind):
                data = copy.deepcopy(original)
                controller = data["render_controllers"]["controller.render.fixture"]
                if kind == "arm":
                    controller["part_visibility"][1]["fixture_arm"] = "false"
                elif kind == "geometry":
                    controller["geometry"] = "Geometry.changed"
                else:
                    data["unapproved"] = True
                with self.assertRaises(ValueError):
                    PACKAGER.validate_controller(json.loads(f.old_pack[PACKAGER.CONTROLLERS[0]]), data)

    def test_legacy_or_incomplete_visibility_repair_is_rejected(self):
        for suffix in (PACKAGER.LEGACY_SUFFIX, " && !query.is_first_person", ""):
            with self.subTest(suffix=suffix):
                old = json.loads(self.fixture.old_pack[PACKAGER.CONTROLLERS[0]])
                new = json.loads(self.fixture.new_pack[PACKAGER.CONTROLLERS[0]].replace(PACKAGER.REVISED_SUFFIX.encode(), suffix.encode()))
                with self.assertRaises(ValueError):
                    PACKAGER.validate_controller(old, new)

    def test_real_coverage_count_rejects_a_partial_fixture(self):
        self.assert_refused(expected_counts=PACKAGER.VISIBILITY_COUNTS)

    def test_manifest_uuid_and_metadata_cannot_change(self):
        f = self.fixture
        for field, value in (("uuid", "00000000-0000-0000-0000-000000000001"), ("description", "changed")):
            with self.subTest(field=field):
                data = json.loads(f.new_pack["manifest.json"])
                data["header"][field] = value
                with self.assertRaises(ValueError):
                    PACKAGER.validate_manifest(json.loads(f.old_pack["manifest.json"]), data)

    def test_header_and_every_module_require_exact_increased_version(self):
        f = self.fixture
        for target in ("header", "module"):
            for version in ([2, 13, 43], [2, 13, 43002], [2, True, 43001]):
                with self.subTest(target=target, version=version):
                    data = json.loads(f.new_pack["manifest.json"])
                    (data["header"] if target == "header" else data["modules"][0])["version"] = version
                    with self.assertRaises(ValueError):
                        PACKAGER.validate_manifest(json.loads(f.old_pack["manifest.json"]), data)

    def test_lock_version_mod_and_bridge_source_metadata_cannot_change(self):
        f = self.fixture
        original = copy.deepcopy(f.new_lock)
        for kind in ("version", "dependency", "bridge_source"):
            with self.subTest(kind=kind):
                f.new_lock = copy.deepcopy(original)
                if kind == "version":
                    f.new_lock["version"] = "new full release"
                elif kind == "dependency":
                    f.new_lock["downloads"][0]["sha256"] = "0" * 64
                else:
                    f.new_lock["creative_bridge"]["source_hashes"]["fixture.java"] = "0" * 64
                f.write_lock()
                self.assert_refused()

    def test_new_lock_pack_checksum_must_match_prepared_bytes(self):
        f = self.fixture
        next(entry for entry in f.new_lock["bundled"] if entry["path"] == PACKAGER.PACK_PATH)["sha256"] = "0" * 64
        f.write_lock()
        self.assert_refused()

    def test_baseline_bundled_bytes_must_match_its_lock(self):
        f = self.fixture
        f.baseline_entries["geyser/extensions/InfinityCreativeBridge.jar"] = b"bad baseline bridge"
        f.write_baseline()
        self.assert_refused()

    def test_prepared_mod_geyser_and_bridge_bytes_must_remain_unchanged(self):
        f = self.fixture
        for name in ("fabric/mods/Infinity-Armor-2.13.0-explore.43.jar", "geyser/Geyser-Standalone.jar",
                     "geyser/extensions/InfinityCreativeBridge.jar"):
            with self.subTest(name=name):
                target = f.server / name
                original = target.read_bytes()
                target.write_bytes(b"unapproved artifact")
                self.assert_refused()
                target.write_bytes(original)

    def test_java_runtime_lock_must_remain_byte_identical(self):
        (self.fixture.server / "runtime.lock.json").write_bytes(b"different Java runtime")
        self.assert_refused()

    def test_mapping_change_and_ambiguous_provenance_are_rejected(self):
        f = self.fixture
        target = f.mappings / PACKAGER.MAPPINGS[0]
        original = target.read_bytes()
        target.write_bytes(b"different mapping")
        self.assert_refused()
        target.write_bytes(original)
        proof = json.loads(f.baseline_entries["checks/armor-equipment-native.json"])
        proof["input_sha256"]["/second/" + PACKAGER.MAPPINGS[0]] = PACKAGER.digest(original)
        f.baseline_entries["checks/armor-equipment-native.json"] = json_bytes(proof)
        f.write_baseline()
        self.assert_refused()

    def test_json_duplicate_keys_are_rejected(self):
        with self.assertRaises(ValueError):
            PACKAGER.json_object(b'{"a": 1, "a": 2}')

    def test_linked_artifact_outside_declared_root_is_rejected(self):
        f = self.fixture
        target = f.server / "geyser/Geyser-Standalone.jar"
        target.unlink()
        outside = f.root / "outside.jar"
        outside.write_bytes(b"same downloaded Geyser")
        target.symlink_to(outside)
        self.assert_refused()

    def test_existing_outputs_are_never_overwritten(self):
        f = self.fixture
        f.output.parent.mkdir()
        f.output.write_bytes(b"previous release or repair")
        with self.assertRaises(FileExistsError):
            f.build()
        self.assertEqual(f.output.read_bytes(), b"previous release or repair")

    def test_output_cannot_replace_the_baseline(self):
        f = self.fixture
        before = f.baseline.read_bytes()
        f.output = f.baseline
        with self.assertRaises(ValueError):
            f.build()
        self.assertEqual(f.baseline.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
