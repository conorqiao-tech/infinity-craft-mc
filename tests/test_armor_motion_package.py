"""A resource-only motion candidate cannot silently replace artwork or gameplay."""
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
try:
    import package_armor_motion_repair as MOTION
finally:
    sys.path.pop(0)
BASE = MOTION.BASE


def archive_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return stream.getvalue()


def json_bytes(value):
    return json.dumps(value, sort_keys=True).encode()


class Fixture:
    def __init__(self, root):
        self.root, self.server, self.mappings = root, root / "server", root / "mappings"
        self.server.mkdir()
        self.mappings.mkdir()
        self.baseline, self.output = root / "baseline44.zip", root / "output/repair.zip"
        self.notes = root / "notes.md"
        self.notes.write_text("Diagnostic resource candidate; device motion check pending.\n")
        self.manifest = {"format_version": 2,
                         "header": {"uuid": "e62e5f9c-a11e-53cd-8430-495f301dfe4a", "version": [2, 13, 44000]},
                         "modules": [{"type": "resources", "uuid": "5d7e0237-c610-5d08-908c-98f23291753c", "version": [2, 13, 44000]}]}
        self.old_pack = {"manifest.json": json_bytes(self.manifest), "textures/original.png": b"original pixels",
                         "models/entity/original_armor/flight_shell.geo.json": b"unchanged inactive wings",
                         "render_controllers/armor.json": b"unchanged visibility",
                         "attachables/armor.json": b"unchanged item identities"}
        for name, bindings in MOTION.GEOMETRY_BINDINGS.items():
            bones = [{"name": "body", "binding": "'body'", "pivot": [0, 24, 0], "cubes": []}]
            bones += [{"name": "limb" + str(index), "binding": binding, "pivot": [index, 22, 0],
                       "cubes": [{"origin": [0, 0, 0], "size": [4, 12, 4], "uv": [0, 0]}]}
                      for index, binding in enumerate(bindings)]
            self.old_pack[name] = json_bytes({"format_version": "1.19.30", "minecraft:geometry": [
                {"description": {"identifier": "geometry.fixture." + Path(name).stem,
                                 "texture_width": 128, "texture_height": 64}, "bones": bones}]})
        self.new_pack = copy.deepcopy(self.old_pack)
        manifest = copy.deepcopy(self.manifest)
        manifest["header"]["version"] = list(MOTION.REPAIR_VERSION)
        manifest["modules"][0]["version"] = list(MOTION.REPAIR_VERSION)
        self.new_pack["manifest.json"] = json_bytes(manifest)
        for name, bindings in MOTION.GEOMETRY_BINDINGS.items():
            for binding in bindings:
                self.new_pack[name] = self.new_pack[name].replace(binding.encode(), binding.lower().encode())
        self.artifacts = {"fabric/mods/Infinity-Armor-2.13.0-explore.44.jar": b"unchanged mod",
                          "geyser/extensions/InfinityCreativeBridge.jar": b"unchanged bridge",
                          "geyser/Geyser-Standalone.jar": b"unchanged downloaded dependency"}
        self.old_lock = {"minecraft": "26.3", "version": MOTION.RELEASE_VERSION,
                         "downloads": [{"path": "geyser/Geyser-Standalone.jar", "sha256": BASE.digest(self.artifacts["geyser/Geyser-Standalone.jar"])}],
                         "bundled": [{"path": name, "sha256": BASE.digest(data)} for name, data in self.artifacts.items()
                                     if name != "geyser/Geyser-Standalone.jar"] +
                                    [{"path": BASE.PACK_PATH, "sha256": BASE.digest(archive_bytes(self.old_pack))}],
                         "creative_bridge": {"source_hashes": {"fixture.java": "a" * 64}}}
        self.new_lock = copy.deepcopy(self.old_lock)
        inputs = {}
        for name in BASE.MAPPINGS:
            data = json_bytes({"mapping": name})
            (self.mappings / name).write_bytes(data)
            inputs["/fixture/" + name] = BASE.digest(data)
        runtime = json_bytes({"java": "25", "sha256": "b" * 64})
        self.baseline_entries = {BASE.LOCK_PATH: json_bytes(self.old_lock), BASE.PACK_PATH: archive_bytes(self.old_pack),
                                 "runtime.lock.json": runtime, "checks/armor-equipment-native.json": json_bytes({"input_sha256": inputs}),
                                 **{name: data for name, data in self.artifacts.items() if name != "geyser/Geyser-Standalone.jar"}}
        for name, data in {**self.artifacts, "runtime.lock.json": runtime}.items():
            target = self.server / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.write_baseline()
        self.write_pack()

    def write_baseline(self):
        self.baseline.write_bytes(archive_bytes({BASE.BASELINE_PREFIX + name: data for name, data in self.baseline_entries.items()}))
        self.baseline_hash = BASE.file_digest(self.baseline)

    def write_lock(self):
        (self.server / BASE.LOCK_PATH).write_bytes(json_bytes(self.new_lock))

    def write_pack(self):
        data = archive_bytes(self.new_pack)
        target = self.server / BASE.PACK_PATH
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        next(entry for entry in self.new_lock["bundled"] if entry["path"] == BASE.PACK_PATH)["sha256"] = BASE.digest(data)
        self.write_lock()

    def build(self, **kwargs):
        return MOTION.build_repair(self.baseline, self.server, self.mappings, self.notes, self.output,
                                   expected_baseline_sha256=kwargs.pop("expected_baseline_sha256", self.baseline_hash), **kwargs)


class ArmorMotionPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def assert_refused(self, **kwargs):
        with self.assertRaises(ValueError):
            self.fixture.build(**kwargs)
        self.assertFalse(self.fixture.output.exists())

    def test_exact_thirty_literal_delta_bundles_four_files_without_gameplay_or_device_claims(self):
        f = self.fixture
        original = f.baseline.read_bytes()
        report = f.build()
        payload = BASE.safe_zip(f.output.read_bytes())
        expected = {BASE.PACK_PATH, BASE.LOCK_PATH, MOTION.NOTES_PATH, "PATCH.json"}
        self.assertEqual(set(payload), {MOTION.REPAIR_PREFIX + name for name in expected})
        self.assertEqual(report["pack_changes"]["binding_replacements"], MOTION.BINDING_COUNTS)
        self.assertEqual(sum(report["pack_changes"]["binding_replacements"].values()), 30)
        self.assertEqual(set(report["pack_changes"]["changed_entries"]), MOTION.ALLOWED_PACK_CHANGES)
        self.assertEqual(report["pack_changes"]["unchanged_entry_count"], 4)
        self.assertEqual(report["baseline"]["version"], MOTION.RELEASE_VERSION)
        self.assertEqual(report["pack_changes"]["version"], [2, 13, 44001])
        self.assertFalse(report["device_rendering_verified"])
        self.assertFalse(report["live_installation_performed"])
        self.assertFalse(report["pack_changes"]["runtime_binding_case_hypothesis_verified"])
        self.assertEqual(report["provenance"]["packager"], "tools/package_armor_motion_repair.py")
        self.assertEqual(report, json.loads(payload[MOTION.REPAIR_PREFIX + "PATCH.json"]))
        for name, sha in report["payload_sha256"].items():
            self.assertEqual(BASE.digest(payload[MOTION.REPAIR_PREFIX + name]), sha)
        self.assertNotIn(str(f.root), json.dumps(report))
        self.assertEqual(f.baseline.read_bytes(), original)

    def test_production_default_refuses_unpinned_fixture(self):
        f = self.fixture
        with self.assertRaises(ValueError):
            MOTION.build_repair(f.baseline, f.server, f.mappings, f.notes, f.output)
        self.assert_refused(expected_baseline_sha256="0" * 64)

    def test_active_geometry_coverage_cannot_be_reduced(self):
        f = self.fixture
        one = next(iter(MOTION.GEOMETRY_BINDINGS))
        f.new_pack[one] = f.old_pack[one]
        f.write_pack()
        self.assert_refused()
        with self.assertRaises(ValueError):
            MOTION.validate_pack(archive_bytes(f.old_pack), archive_bytes(f.new_pack), {one: 2})

    def test_only_exact_quoted_lowercase_binding_is_accepted(self):
        f = self.fixture
        name, bindings = next(iter(MOTION.GEOMETRY_BINDINGS.items()))
        old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
        for value in (bindings[0], "rightarm", "'rightArm'", "query.item_slot_to_bone_name(0)", "'leftarm'", None):
            with self.subTest(value=value):
                candidate = copy.deepcopy(new)
                candidate["minecraft:geometry"][0]["bones"][1]["binding"] = value
                with self.assertRaises(ValueError):
                    MOTION.validate_geometry(old, candidate, bindings)

    def test_geometry_identifiers_names_pivots_cubes_uvs_and_parent_cannot_change(self):
        f = self.fixture
        name, bindings = next(iter(MOTION.GEOMETRY_BINDINGS.items()))
        old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
        for field in ("identifier", "name", "pivot", "origin", "size", "uv", "parent", "extra"):
            with self.subTest(field=field):
                candidate = copy.deepcopy(new)
                geo = candidate["minecraft:geometry"][0]
                bone = geo["bones"][1]
                if field == "identifier": geo["description"][field] = "geometry.changed"
                elif field in ("name", "parent"): bone[field] = "changed"
                elif field == "pivot": bone[field] = [9, 9, 9]
                elif field == "extra": candidate[field] = True
                else: bone["cubes"][0][field] = [9, 9, 9]
                with self.assertRaises(ValueError):
                    MOTION.validate_geometry(old, candidate, bindings)

    def test_body_binding_and_geometry_lists_cannot_change(self):
        f = self.fixture
        name, bindings = next(iter(MOTION.GEOMETRY_BINDINGS.items()))
        old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
        for kind in ("body", "missing_bone", "extra_bone", "extra_model", "missing_model"):
            candidate = copy.deepcopy(new)
            if kind == "body": candidate["minecraft:geometry"][0]["bones"][0]["binding"] = "'head'"
            elif kind == "missing_bone": candidate["minecraft:geometry"][0]["bones"].pop()
            elif kind == "extra_bone": candidate["minecraft:geometry"][0]["bones"].append({"name": "extra"})
            elif kind == "extra_model": candidate["minecraft:geometry"].append(copy.deepcopy(candidate["minecraft:geometry"][0]))
            else: candidate["minecraft:geometry"] = []
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                MOTION.validate_geometry(old, candidate, bindings)

    def test_pixels_wings_controllers_and_attachables_remain_byte_identical(self):
        f = self.fixture
        for name in ("textures/original.png", "models/entity/original_armor/flight_shell.geo.json",
                     "render_controllers/armor.json", "attachables/armor.json"):
            with self.subTest(name=name):
                original = f.new_pack[name]
                f.new_pack[name] = b"unapproved change"
                f.write_pack()
                self.assert_refused()
                f.new_pack[name] = original

    def test_pack_entries_cannot_be_added_or_removed(self):
        f = self.fixture
        for add in (True, False):
            candidate = copy.deepcopy(f.new_pack)
            if add: candidate["extra.json"] = b"{}"
            else: candidate.pop("textures/original.png")
            with self.subTest(add=add), self.assertRaises(ValueError):
                MOTION.validate_pack(archive_bytes(f.old_pack), archive_bytes(candidate))

    def test_manifest_uuid_metadata_module_and_revision_changes_are_rejected(self):
        f = self.fixture
        original = json.loads(f.new_pack["manifest.json"])
        for kind in ("uuid", "description", "extra_module", "header_old", "module_wrong", "header_boolean"):
            candidate = copy.deepcopy(original)
            if kind == "uuid": candidate["header"]["uuid"] = "00000000-0000-0000-0000-000000000001"
            elif kind == "description": candidate["header"]["description"] = "changed"
            elif kind == "extra_module": candidate["modules"].append(copy.deepcopy(candidate["modules"][0]))
            elif kind == "header_old": candidate["header"]["version"] = [2, 13, 44000]
            elif kind == "module_wrong": candidate["modules"][0]["version"] = [2, 13, 44002]
            else: candidate["header"]["version"] = [2, True, 44001]
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                BASE.validate_manifest(f.manifest, candidate, MOTION.REPAIR_VERSION)

    def test_lock_version_dependency_and_identity_metadata_cannot_change(self):
        f = self.fixture
        original = copy.deepcopy(f.new_lock)
        for kind in ("version", "download", "mod_path", "bridge_source"):
            f.new_lock = copy.deepcopy(original)
            if kind == "version": f.new_lock["version"] = "2.13.0-explore.45"
            elif kind == "download": f.new_lock["downloads"][0]["sha256"] = "0" * 64
            elif kind == "mod_path": f.new_lock["bundled"][0]["path"] = "fabric/mods/changed.jar"
            else: f.new_lock["creative_bridge"]["source_hashes"]["fixture.java"] = "0" * 64
            f.write_lock()
            with self.subTest(kind=kind): self.assert_refused()

    def test_even_caller_pinned_baseline_must_still_identify_explore44(self):
        f = self.fixture
        old = copy.deepcopy(f.old_lock)
        old["version"] = "2.13.0-explore.43"
        f.baseline_entries[BASE.LOCK_PATH] = json_bytes(old)
        f.write_baseline()
        self.assert_refused()

    def test_mod_bridge_download_and_runtime_bytes_cannot_change(self):
        f = self.fixture
        for name in (*f.artifacts, "runtime.lock.json"):
            path = f.server / name
            original = path.read_bytes()
            path.write_bytes(b"unapproved replacement")
            with self.subTest(name=name): self.assert_refused()
            path.write_bytes(original)

    def test_mapping_and_ambiguous_baseline_mapping_provenance_are_rejected(self):
        f = self.fixture
        path = f.mappings / BASE.MAPPINGS[0]
        original = path.read_bytes()
        path.write_bytes(b"changed IDs")
        self.assert_refused()
        path.write_bytes(original)
        proof = json.loads(f.baseline_entries["checks/armor-equipment-native.json"])
        proof["input_sha256"]["/duplicate/" + BASE.MAPPINGS[0]] = BASE.digest(original)
        f.baseline_entries["checks/armor-equipment-native.json"] = json_bytes(proof)
        f.write_baseline()
        self.assert_refused()

    def test_private_and_traversal_paths_are_rejected_before_output(self):
        f = self.fixture
        for name in ("../escape", "world/player.dat", "players/data.dat", ".dynu/key.json", ".remote/settings", "settings.json", "ops.json", "key.pem"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                BASE.safe_zip(archive_bytes({name: b"private"}))
        f.baseline_entries[".dynu/key.json"] = b"private"
        f.write_baseline()
        self.assert_refused()

    def test_forged_pack_lock_checksum_and_baseline_artifact_are_rejected(self):
        f = self.fixture
        next(entry for entry in f.new_lock["bundled"] if entry["path"] == BASE.PACK_PATH)["sha256"] = "0" * 64
        f.write_lock()
        self.assert_refused()
        f.write_pack()
        f.baseline_entries["geyser/extensions/InfinityCreativeBridge.jar"] = b"forged baseline"
        f.write_baseline()
        self.assert_refused()

    def test_missing_notes_and_symlinked_artifacts_are_rejected(self):
        f = self.fixture
        f.notes.write_text("  ")
        self.assert_refused()
        f.notes.write_text("Device rendering pending")
        name = "geyser/Geyser-Standalone.jar"
        outside = f.root / "outside.jar"
        outside.write_bytes(f.artifacts[name])
        (f.server / name).unlink()
        (f.server / name).symlink_to(outside)
        self.assert_refused()

    def test_existing_outputs_and_baseline_are_never_overwritten(self):
        f = self.fixture
        f.output.parent.mkdir()
        f.output.write_bytes(b"existing artifact")
        with self.assertRaises(FileExistsError): f.build()
        self.assertEqual(f.output.read_bytes(), b"existing artifact")
        f.output = f.baseline
        original = f.baseline.read_bytes()
        with self.assertRaises(ValueError): f.build()
        self.assertEqual(f.baseline.read_bytes(), original)

    def test_cli_retains_production_pin_and_output_without_publishing(self):
        with patch.object(sys, "argv", [str(MOTION.__file__)]), patch.object(sys, "stderr", io.StringIO()), \
                patch.object(MOTION, "build_repair", side_effect=ValueError("stop before output")) as build:
            with self.assertRaises(SystemExit) as error:
                MOTION.main()
        self.assertEqual(error.exception.code, 1)
        args, kwargs = build.call_args
        self.assertEqual(args[0], ROOT / "dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.44.zip")
        self.assertEqual(args[4], ROOT / "dist/armor-motion-explore44-r1/Infinity_Armor_Armor_Motion_Repair_explore44-r1.zip")
        self.assertEqual(kwargs, {})
        self.assertEqual(MOTION.build_repair.__kwdefaults__["expected_baseline_sha256"], MOTION.BASELINE_SHA256)


if __name__ == "__main__":
    unittest.main()
