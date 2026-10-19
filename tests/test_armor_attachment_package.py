"""A native-joint resource patch must preserve the pinned game's other bytes."""
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
    import package_armor_attachment_repair as REPAIR
finally:
    sys.path.pop(0)
BASE = REPAIR.BASE


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
        self.baseline, self.output = root / "baseline45.zip", root / "output/repair.zip"
        self.notes = root / "notes.md"
        self.notes.write_text("Native-joint candidate; device rendering remains unverified.\n")
        self.manifest = {"format_version": 2,
                         "header": {"uuid": "e62e5f9c-a11e-53cd-8430-495f301dfe4a", "version": [2, 13, 45000]},
                         "modules": [{"type": "resources", "uuid": "5d7e0237-c610-5d08-908c-98f23291753c", "version": [2, 13, 45000]}]}
        self.old_pack = {"manifest.json": json_bytes(self.manifest), "textures/original.png": b"original pixels",
                         "models/entity/original_armor/flight_shell.geo.json": b"unchanged inactive wings",
                         "attachables/armor.json": b"unchanged item identities"}
        for name, joints in REPAIR.GEOMETRY_JOINTS.items():
            bones = [{"name": old_name, "binding": "'" + joint + "'", "pivot": [0, 24, 0],
                      "cubes": [{"origin": [-2, 0, -2], "size": [4, 12, 4], "uv": [0, 0]}]}
                     for old_name, joint in joints]
            self.old_pack[name] = json_bytes({"format_version": "1.19.30", "minecraft:geometry": [
                {"description": {"identifier": "geometry.fixture." + Path(name).stem,
                                 "texture_width": 128, "texture_height": 64}, "bones": bones}]})
        for name, controllers in REPAIR.CONTROLLER_BONES.items():
            self.old_pack[name] = json_bytes({"format_version": "1.8.0", "render_controllers": {
                controller: {"geometry": "Geometry.default", "textures": ["Texture.default"],
                             "materials": [{"*": "Material.default"}],
                             "part_visibility": [{old_name: "query.is_in_ui || !query.is_first_person"}
                                                 for old_name in renames]}
                for controller, renames in controllers.items()}})
        self.new_pack = copy.deepcopy(self.old_pack)
        manifest = copy.deepcopy(self.manifest)
        manifest["header"]["version"] = list(REPAIR.REPAIR_VERSION)
        manifest["modules"][0]["version"] = list(REPAIR.REPAIR_VERSION)
        self.new_pack["manifest.json"] = json_bytes(manifest)
        for name, joints in REPAIR.GEOMETRY_JOINTS.items():
            geometry = json.loads(self.old_pack[name])
            bones = geometry["minecraft:geometry"][0]["bones"]
            for bone, (_, joint) in zip(bones, joints):
                bone["name"] = REPAIR.NATIVE_NAMES[joint]
                bone.pop("binding")
            self.new_pack[name] = json_bytes(geometry)
        for name, controllers in REPAIR.CONTROLLER_BONES.items():
            controller = json.loads(self.old_pack[name])
            for key, renames in controllers.items():
                entry = controller["render_controllers"][key]
                entry["part_visibility"] = [{renames[k]: v for k, v in part.items()} for part in entry["part_visibility"]]
            self.new_pack[name] = json_bytes(controller)
        self.artifacts = {"fabric/mods/Infinity-Armor-2.13.0-explore.45.jar": b"unchanged mod",
                          "geyser/extensions/InfinityCreativeBridge.jar": b"unchanged bridge",
                          "geyser/Geyser-Standalone.jar": b"unchanged downloaded dependency"}
        self.old_lock = {"minecraft": "26.3", "version": REPAIR.RELEASE_VERSION,
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
        next(e for e in self.new_lock["bundled"] if e["path"] == BASE.PACK_PATH)["sha256"] = BASE.digest(data)
        self.write_lock()

    def build(self, **kwargs):
        return REPAIR.build_repair(self.baseline, self.server, self.mappings, self.notes, self.output,
                                   expected_baseline_sha256=kwargs.pop("expected_baseline_sha256", self.baseline_hash), **kwargs)


class ArmorAttachmentPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def assert_refused(self, **kwargs):
        with self.assertRaises(ValueError):
            self.fixture.build(**kwargs)
        self.assertFalse(self.fixture.output.exists())

    def test_complete_native_joint_delta_bundles_four_files_without_device_claims(self):
        f = self.fixture
        baseline = f.baseline.read_bytes()
        report = f.build()
        payload = BASE.safe_zip(f.output.read_bytes())
        self.assertEqual(set(payload), {REPAIR.REPAIR_PREFIX + n for n in (BASE.PACK_PATH, BASE.LOCK_PATH, REPAIR.NOTES_PATH, "PATCH.json")})
        self.assertEqual(report["pack_changes"]["geometry_native_joints"], REPAIR.GEOMETRY_COUNTS)
        self.assertEqual(sum(REPAIR.GEOMETRY_COUNTS.values()), 41)
        self.assertEqual(report["pack_changes"]["empty_ancestors"], 0)
        self.assertEqual(report["pack_changes"]["controller_bone_renames"], dict(zip(REPAIR.CONTROLLERS, (25, 16))))
        self.assertEqual(set(report["pack_changes"]["changed_entries"]), REPAIR.ALLOWED_PACK_CHANGES)
        self.assertEqual(len(REPAIR.ALLOWED_PACK_CHANGES), 24)
        self.assertEqual(report["baseline"]["version"], REPAIR.RELEASE_VERSION)
        self.assertEqual(report["pack_changes"]["version"], [2, 13, 45001])
        self.assertFalse(report["device_rendering_verified"])
        self.assertFalse(report["live_installation_performed"])
        self.assertFalse(report["pack_changes"]["runtime_native_joint_rendering_verified"])
        self.assertEqual(report["provenance"]["packager"], "tools/package_armor_attachment_repair.py")
        self.assertEqual(report, json.loads(payload[REPAIR.REPAIR_PREFIX + "PATCH.json"]))
        for name, checksum in report["payload_sha256"].items():
            self.assertEqual(BASE.digest(payload[REPAIR.REPAIR_PREFIX + name]), checksum)
        self.assertNotIn(str(f.root), json.dumps(report))
        self.assertEqual(f.baseline.read_bytes(), baseline)

    def test_default_production_pin_refuses_fixture_and_wrong_hash(self):
        f = self.fixture
        with self.assertRaises(ValueError):
            REPAIR.build_repair(f.baseline, f.server, f.mappings, f.notes, f.output)
        self.assert_refused(expected_baseline_sha256="0" * 64)

    def test_geometry_coverage_cannot_be_reduced(self):
        f = self.fixture
        name = next(iter(REPAIR.GEOMETRY_JOINTS))
        f.new_pack[name] = f.old_pack[name]
        f.write_pack()
        self.assert_refused()
        with self.assertRaises(ValueError):
            REPAIR.validate_pack(archive_bytes(f.old_pack), archive_bytes(f.new_pack), {name: 1})

    def test_flat_native_joints_reject_binding_offsets_parents_anchors_and_reordering(self):
        f = self.fixture
        name = "models/entity/elemental41/infinity_leggings.geo.json"
        old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
        for kind in ("binding", "local_offset", "parent", "pivot", "missing_bone", "ancestor", "order", "wrong_case"):
            candidate = copy.deepcopy(new)
            bones = candidate["minecraft:geometry"][0]["bones"]
            if kind == "binding": bones[0]["binding"] = "'rightleg'"
            elif kind == "local_offset": bones[0]["cubes"][0]["origin"][0] += 1.9
            elif kind == "parent": bones[0]["parent"] = "body"
            elif kind == "pivot": bones[0]["pivot"] = [0, 12, 0]
            elif kind == "missing_bone": bones.pop(0)
            elif kind == "ancestor": bones.insert(0, {"name": "body", "pivot": [0, 24, 0]})
            elif kind == "order": bones[0], bones[1] = bones[1], bones[0]
            else: bones[0]["name"] = "rightleg"
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_geometry(old, candidate, REPAIR.GEOMETRY_JOINTS[name])

    def test_no_synthetic_body_or_waist_is_added_to_flat_armor_skeleton(self):
        f = self.fixture
        for name in ("models/entity/original_armor/cuirass.geo.json", "models/entity/original_armor/backpack.geo.json",
                     "models/entity/elemental41/infinity_helmet.geo.json"):
            old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
            candidate = copy.deepcopy(new)
            candidate["minecraft:geometry"][0]["bones"].insert(1, {"name": "body", "pivot": [0, 24, 0], "parent": "waist"})
            with self.subTest(name=name), self.assertRaises(ValueError):
                REPAIR.validate_geometry(old, candidate, REPAIR.GEOMETRY_JOINTS[name])

    def test_cubes_uvs_pivots_identifier_and_other_geometry_fields_cannot_change(self):
        f = self.fixture
        name = "models/entity/original_armor/aurora_chestplate.geo.json"
        old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
        for kind in ("origin", "size", "uv", "pivot", "identifier", "extra_model", "extra_field"):
            candidate = copy.deepcopy(new)
            geometry = candidate["minecraft:geometry"][0]
            if kind in ("origin", "size", "uv"): geometry["bones"][0]["cubes"][0][kind] = [9, 9, 9]
            elif kind == "pivot": geometry["bones"][0]["pivot"] = [0, 0, 0]
            elif kind == "identifier": geometry["description"][kind] = "geometry.changed"
            elif kind == "extra_model": candidate["minecraft:geometry"].append(copy.deepcopy(geometry))
            else: candidate["extra"] = True
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_geometry(old, candidate, REPAIR.GEOMETRY_JOINTS[name])

    def test_controller_names_are_exact_while_expressions_materials_and_order_remain(self):
        f = self.fixture
        name = REPAIR.CONTROLLERS[0]
        old, new = json.loads(f.old_pack[name]), json.loads(f.new_pack[name])
        key = "controller.render.convergence.cuirass_original"
        for kind in ("old_name", "expression", "material", "missing_controller", "extra_guard", "order"):
            candidate = copy.deepcopy(new)
            entry = candidate["render_controllers"][key]
            if kind == "old_name": entry["part_visibility"][1] = {"infinity_right_arm": "query.is_in_ui || !query.is_first_person"}
            elif kind == "expression": entry["part_visibility"][1] = {"rightArm": True}
            elif kind == "material": entry["materials"] = [{"*": "Material.changed"}]
            elif kind == "missing_controller": candidate["render_controllers"].pop(key)
            elif kind == "extra_guard": entry["part_visibility"].append({"waist": True})
            else: entry["part_visibility"].reverse()
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_controller(old, candidate, REPAIR.CONTROLLER_BONES[name])

    def test_pixels_inactive_wings_and_item_identities_are_byte_preserved(self):
        f = self.fixture
        for name in ("textures/original.png", "models/entity/original_armor/flight_shell.geo.json", "attachables/armor.json"):
            previous = f.new_pack[name]
            f.new_pack[name] = b"unapproved change"
            f.write_pack()
            with self.subTest(name=name): self.assert_refused()
            f.new_pack[name] = previous

    def test_pack_entries_and_manifest_metadata_cannot_change(self):
        f = self.fixture
        for kind in ("added", "deleted", "uuid", "old_revision", "module_revision"):
            candidate = copy.deepcopy(f.new_pack)
            manifest = json.loads(candidate["manifest.json"])
            if kind == "added": candidate["extra.json"] = b"{}"
            elif kind == "deleted": candidate.pop("textures/original.png")
            elif kind == "uuid": manifest["header"]["uuid"] = "00000000-0000-0000-0000-000000000001"
            elif kind == "old_revision": manifest["header"]["version"] = [2, 13, 45000]
            else: manifest["modules"][0]["version"] = [2, 13, 45002]
            candidate["manifest.json"] = json_bytes(manifest)
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_pack(archive_bytes(f.old_pack), archive_bytes(candidate))

    def test_lock_version_dependency_paths_sources_and_pack_digest_cannot_be_forged(self):
        f = self.fixture
        original = copy.deepcopy(f.new_lock)
        for kind in ("version", "download", "mod_path", "bridge_source", "pack_sha"):
            f.new_lock = copy.deepcopy(original)
            if kind == "version": f.new_lock["version"] = "2.13.0-explore.46"
            elif kind == "download": f.new_lock["downloads"][0]["sha256"] = "0" * 64
            elif kind == "mod_path": f.new_lock["bundled"][0]["path"] = "fabric/mods/changed.jar"
            elif kind == "bridge_source": f.new_lock["creative_bridge"]["source_hashes"]["fixture.java"] = "0" * 64
            else: next(e for e in f.new_lock["bundled"] if e["path"] == BASE.PACK_PATH)["sha256"] = "0" * 64
            f.write_lock()
            with self.subTest(kind=kind): self.assert_refused()

    def test_even_fixture_baseline_must_identify45_and_contain_verified_bundled_artifacts(self):
        f = self.fixture
        lock = copy.deepcopy(f.old_lock)
        lock["version"] = "2.13.0-explore.44"
        f.baseline_entries[BASE.LOCK_PATH] = json_bytes(lock)
        f.write_baseline()
        self.assert_refused()
        f.baseline_entries[BASE.LOCK_PATH] = json_bytes(f.old_lock)
        f.baseline_entries["geyser/extensions/InfinityCreativeBridge.jar"] = b"forged baseline"
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

    def test_mapping_drift_and_ambiguous_mapping_provenance_are_rejected(self):
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

    def test_private_traversal_and_duplicate_json_are_rejected(self):
        f = self.fixture
        for name in ("../escape", "world/player.dat", "players/data.dat", ".dynu/key.json", ".remote/settings", "settings.json", "ops.json", "key.pem"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                BASE.safe_zip(archive_bytes({name: b"private"}))
        with self.assertRaises(ValueError): BASE.json_object(b'{"bones": [], "bones": []}')
        f.baseline_entries[".dynu/key.json"] = b"private"
        f.write_baseline()
        self.assert_refused()

    def test_missing_notes_and_symlinked_inputs_are_rejected(self):
        f = self.fixture
        f.notes.write_text("  ")
        self.assert_refused()
        f.notes.write_text("Device rendering unverified")
        name = "geyser/Geyser-Standalone.jar"
        outside = f.root / "outside.jar"
        outside.write_bytes(f.artifacts[name])
        (f.server / name).unlink()
        (f.server / name).symlink_to(outside)
        self.assert_refused()

    def test_existing_outputs_and_immutable_baseline_are_never_overwritten(self):
        f = self.fixture
        f.output.parent.mkdir()
        f.output.write_bytes(b"existing artifact")
        with self.assertRaises(FileExistsError): f.build()
        self.assertEqual(f.output.read_bytes(), b"existing artifact")
        f.output = f.baseline
        original = f.baseline.read_bytes()
        with self.assertRaises(ValueError): f.build()
        self.assertEqual(f.baseline.read_bytes(), original)

    def test_cli_retains_production_pin_and_separate_output(self):
        with patch.object(sys, "argv", [str(REPAIR.__file__)]), patch.object(sys, "stderr", io.StringIO()), \
                patch.object(REPAIR, "build_repair", side_effect=ValueError("stop before output")) as build:
            with self.assertRaises(SystemExit) as error:
                REPAIR.main()
        self.assertEqual(error.exception.code, 1)
        args, kwargs = build.call_args
        self.assertEqual(args[0], ROOT / "dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.45.zip")
        self.assertEqual(args[4], ROOT / "dist/armor-attachment-explore45-r1/Infinity_Armor_Armor_Attachment_Repair_explore45-r1.zip")
        self.assertEqual(kwargs, {})
        self.assertEqual(REPAIR.build_repair.__kwdefaults__["expected_baseline_sha256"], REPAIR.BASELINE_SHA256)


if __name__ == "__main__":
    unittest.main()
