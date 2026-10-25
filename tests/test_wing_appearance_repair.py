"""The wing comparison package must not broaden its two-selector repair."""
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
try:
    import package_wing_appearance_repair as REPAIR
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


def selector(variable, property_name, owner=False):
    query = "query.item_slot_to_bone_name(context.item_slot)"
    if owner:
        query = "(context.owning_entity->" + query + ")"
    return (variable + " = " + query + " == 'body' ? ((context.owning_entity->query.has_property('"
            + property_name + "')) ? (context.owning_entity->query.property('" + property_name
            + "')) : 0.0) : 0.0;")


class Fixture:
    def __init__(self, root):
        self.root, self.server, self.mappings = root, root / "server", root / "mappings"
        self.server.mkdir()
        self.mappings.mkdir()
        self.baseline, self.output = root / "baseline51.zip", root / "output/repair.zip"
        self.notes = root / "notes.md"
        self.notes.write_text("Wing appearance candidate: static checks do not prove iPad Molang or rendering.\n")
        self.manifest = {
            "format_version": 2,
            "header": {"uuid": "e62e5f9c-a11e-53cd-8430-495f301dfe4a", "version": [2, 13, 51000]},
            "modules": [{"type": "resources", "uuid": "5d7e0237-c610-5d08-908c-98f23291753c",
                         "version": [2, 13, 51000]}],
        }
        self.old_attachable = {"format_version": "1.10.0", "minecraft:attachable": {"description": {
            "identifier": "minecraft:elytra",
            "materials": {"default": "elytra", "enchanted": "elytra_glint"},
            "textures": {"default": "textures/models/armor/elytra", "convergence_wing": "textures/owned_wings"},
            "geometry": {"default": "geometry.elytra", "infinity_armor": "geometry.owned_armor"},
            "animations": {"default_controller": "controller.animation.elytra.default"},
            "scripts": {
                "parent_setup": "variable.chest_layer_visible = 0.0;",
                "pre_animation": [selector("variable.infinityhub_winged_chest", "infinityhub:winged_chest"),
                                  selector("variable.infinityhub_infinity_winged_chest", "infinityhub:infinity_winged_chest")],
                "animate": [{"default_controller": "!variable.infinityhub_winged_chest"},
                            {"convergence_controller": "variable.infinityhub_winged_chest"}],
            },
            "render_controllers": [{"controller.render.armor": "!variable.infinityhub_winged_chest"},
                                   {"controller.render.convergence.combined_armor": "variable.infinityhub_winged_chest"},
                                   {"controller.render.convergence.combined_wings": "variable.infinityhub_winged_chest"}],
        }}}
        self.new_attachable = copy.deepcopy(self.old_attachable)
        self.new_attachable["minecraft:attachable"]["description"]["scripts"]["pre_animation"] = [
            selector("variable.infinityhub_winged_chest", "infinityhub:winged_chest", owner=True),
            selector("variable.infinityhub_infinity_winged_chest", "infinityhub:infinity_winged_chest", owner=True)]
        self.old_pack = {
            "manifest.json": json_bytes(self.manifest), REPAIR.ATTACHABLE: json_bytes(self.old_attachable),
            "textures/original.png": b"original artwork", "models/entity/armor.geo.json": b"accepted armor fit",
            "render_controllers/combined.json": b"native fallback and marked renderers unchanged",
        }
        self.new_pack = copy.deepcopy(self.old_pack)
        manifest = copy.deepcopy(self.manifest)
        manifest["header"]["version"] = [2, 13, 51001]
        manifest["modules"][0]["version"] = [2, 13, 51001]
        self.new_pack["manifest.json"] = json_bytes(manifest)
        self.new_pack[REPAIR.ATTACHABLE] = json_bytes(self.new_attachable)
        self.artifacts = {
            "fabric/mods/Infinity-Armor-2.13.0-explore.51.jar": b"unchanged explore51 mod",
            "geyser/extensions/InfinityCreativeBridge.jar": b"unchanged creative bridge",
            "geyser/Geyser-Standalone.jar": b"unchanged downloaded dependency",
        }
        download = "geyser/Geyser-Standalone.jar"
        self.old_lock = {
            "minecraft": "26.4-snapshot-5", "version": "2.13.0-explore.51",
            "downloads": [{"path": download, "sha256": BASE.digest(self.artifacts[download])}],
            "bundled": [{"path": name, "sha256": BASE.digest(data)} for name, data in self.artifacts.items()
                        if name != download] + [{"path": BASE.PACK_PATH, "sha256": BASE.digest(archive_bytes(self.old_pack))}],
            "creative_bridge": {"source_hashes": {"fixture.java": "a" * 64}},
        }
        self.new_lock = copy.deepcopy(self.old_lock)
        mapping_inputs = {}
        for name in BASE.MAPPINGS:
            data = json_bytes({"mapping": name})
            (self.mappings / name).write_bytes(data)
            mapping_inputs["/fixture/" + name] = BASE.digest(data)
        runtime = json_bytes({"java": "25", "sha256": "b" * 64})
        self.baseline_entries = {
            BASE.LOCK_PATH: json_bytes(self.old_lock), BASE.PACK_PATH: archive_bytes(self.old_pack),
            "runtime.lock.json": runtime,
            "checks/armor-equipment-native.json": json_bytes({"input_sha256": mapping_inputs}),
            **{name: data for name, data in self.artifacts.items() if name != download},
        }
        for name, data in {**self.artifacts, "runtime.lock.json": runtime}.items():
            target = self.server / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.write_baseline()
        self.write_pack()

    def write_baseline(self):
        self.baseline.write_bytes(archive_bytes({BASE.BASELINE_PREFIX + name: data
                                                for name, data in self.baseline_entries.items()}))
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
        return REPAIR.build_repair(self.baseline, self.server, self.mappings, self.notes, self.output,
                                   expected_baseline_sha256=kwargs.pop("expected_baseline_sha256", self.baseline_hash), **kwargs)


class WingAppearanceRepairTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.fixture = Fixture(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def assert_refused(self, **kwargs):
        with self.assertRaises(ValueError):
            self.fixture.build(**kwargs)
        self.assertFalse(self.fixture.output.exists())

    def test_exact_resource_delta_has_four_payloads_and_no_runtime_rendering_claim(self):
        f = self.fixture
        before = f.baseline.read_bytes()
        report = f.build()
        payloads = BASE.safe_zip(f.output.read_bytes())
        self.assertEqual(set(payloads), {REPAIR.REPAIR_PREFIX + name for name in
                                        (BASE.LOCK_PATH, BASE.PACK_PATH, REPAIR.NOTES_PATH, "PATCH.json")})
        self.assertEqual(report["baseline"]["version"], "2.13.0-explore.51")
        self.assertEqual(set(report["pack_changes"]["changed_entries"]), {"manifest.json", REPAIR.ATTACHABLE})
        self.assertEqual(report["pack_changes"]["selector_owner_context_replacements"], {REPAIR.ATTACHABLE: 2})
        self.assertEqual(report["pack_changes"]["unchanged_entry_count"], 3)
        self.assertEqual(report["pack_changes"]["version"], [2, 13, 51001])
        self.assertFalse(report["pack_changes"]["runtime_molang_context_verified"])
        self.assertFalse(report["device_rendering_verified"])
        self.assertFalse(report["live_installation_performed"])
        self.assertEqual(report["provenance"]["packager"], "tools/package_wing_appearance_repair.py")
        self.assertEqual(report, json.loads(payloads[REPAIR.REPAIR_PREFIX + "PATCH.json"]))
        for name, checksum in report["payload_sha256"].items():
            self.assertEqual(BASE.digest(payloads[REPAIR.REPAIR_PREFIX + name]), checksum)
        self.assertNotIn(str(f.root), json.dumps(report))
        self.assertEqual(f.baseline.read_bytes(), before)

    def test_production_pin_and_explicit_wrong_hash_refuse_a_caller_fixture(self):
        f = self.fixture
        with self.assertRaises(ValueError):
            REPAIR.build_repair(f.baseline, f.server, f.mappings, f.notes, f.output)
        self.assert_refused(expected_baseline_sha256="0" * 64)

    def test_both_selector_repairs_are_required_and_coverage_cannot_be_reduced(self):
        f = self.fixture
        for index in (0, 1):
            candidate = copy.deepcopy(f.new_attachable)
            candidate["minecraft:attachable"]["description"]["scripts"]["pre_animation"][index] = (
                f.old_attachable["minecraft:attachable"]["description"]["scripts"]["pre_animation"][index])
            with self.subTest(index=index), self.assertRaises(ValueError):
                REPAIR.validate_attachable(f.old_attachable, candidate)
        with self.assertRaises(ValueError):
            REPAIR.validate_pack(archive_bytes(f.old_pack), archive_bytes(f.new_pack), {REPAIR.ATTACHABLE: 1})

    def test_unknown_missing_extra_and_reordered_baseline_selectors_are_rejected(self):
        f = self.fixture
        for kind in ("unknown", "missing", "extra", "reordered", "already_repaired"):
            old = copy.deepcopy(f.old_attachable)
            selectors = old["minecraft:attachable"]["description"]["scripts"]["pre_animation"]
            if kind == "unknown": selectors[1] = selectors[1].replace("infinityhub:infinity_winged_chest", "infinityhub:foreign")
            elif kind == "missing": selectors.pop()
            elif kind == "extra": selectors.append(selectors[0])
            elif kind == "reordered": selectors.reverse()
            else: selectors[0] = f.new_attachable["minecraft:attachable"]["description"]["scripts"]["pre_animation"][0]
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_attachable(old, f.new_attachable)

    def test_current_attachable_query_unknown_owner_and_malformed_selector_are_rejected(self):
        f = self.fixture
        for query in ("query.item_slot_to_bone_name(context.item_slot)",
                      "(context.other_entity->query.item_slot_to_bone_name(context.item_slot))",
                      "(context.owning_entity->query.item_slot_to_bone_name('slot.armor.chest'))", "'body'"):
            candidate = copy.deepcopy(f.new_attachable)
            expressions = candidate["minecraft:attachable"]["description"]["scripts"]["pre_animation"]
            expressions[0] = expressions[0].replace(REPAIR.OWNER_SLOT_QUERY, query)
            with self.subTest(query=query), self.assertRaises(ValueError):
                REPAIR.validate_attachable(f.old_attachable, candidate)

    def test_slot_property_and_missing_property_guards_cannot_be_broadened(self):
        f = self.fixture
        for kind in ("slot", "has_property", "property", "false_fallback", "always_on"):
            candidate = copy.deepcopy(f.new_attachable)
            expressions = candidate["minecraft:attachable"]["description"]["scripts"]["pre_animation"]
            if kind == "slot": expressions[1] = expressions[1].replace("== 'body'", "!= 'none'")
            elif kind == "has_property": expressions[1] = expressions[1].replace("query.has_property", "query.property")
            elif kind == "property": expressions[1] = expressions[1].replace("infinityhub:infinity_winged_chest", "infinityhub:winged_chest")
            elif kind == "false_fallback": expressions[1] = expressions[1].replace(": 0.0", ": 1.0")
            else: expressions[1] = "variable.infinityhub_infinity_winged_chest = 1.0;"
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_attachable(f.old_attachable, candidate)

    def test_native_fallback_item_identity_animation_and_other_attachable_fields_cannot_change(self):
        f = self.fixture
        for kind in ("identifier", "native_texture", "native_geometry", "materials", "animate", "renderer", "parent_setup", "extra"):
            candidate = copy.deepcopy(f.new_attachable)
            description = candidate["minecraft:attachable"]["description"]
            if kind == "identifier": description["identifier"] = "convergence:elytra"
            elif kind == "native_texture": description["textures"]["default"] = "textures/owned_wings"
            elif kind == "native_geometry": description["geometry"]["default"] = "Geometry.infinity_armor"
            elif kind == "materials": description["materials"]["default"] = "armor"
            elif kind == "animate": description["scripts"]["animate"][0] = {"default_controller": "1.0"}
            elif kind == "renderer": description["render_controllers"][0] = "controller.render.armor"
            elif kind == "parent_setup": description["scripts"]["parent_setup"] = "variable.chest_layer_visible = 1.0;"
            else: candidate["unapproved"] = True
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_attachable(f.old_attachable, candidate)

    def test_out_of_scope_artwork_geometry_and_renderer_changes_are_rejected(self):
        f = self.fixture
        for name in ("textures/original.png", "models/entity/armor.geo.json", "render_controllers/combined.json"):
            original = f.new_pack[name]
            f.new_pack[name] = b"unapproved replacement"
            f.write_pack()
            with self.subTest(name=name): self.assert_refused()
            f.new_pack[name] = original

    def test_added_deleted_unchanged_and_manifest_only_pack_deltas_are_rejected(self):
        f = self.fixture
        for kind in ("added", "deleted", "unchanged", "manifest_only"):
            candidate = copy.deepcopy(f.new_pack)
            if kind == "added": candidate["extra.json"] = b"{}"
            elif kind == "deleted": candidate.pop("textures/original.png")
            elif kind == "unchanged": candidate = f.old_pack
            else: candidate[REPAIR.ATTACHABLE] = f.old_pack[REPAIR.ATTACHABLE]
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_pack(archive_bytes(f.old_pack), archive_bytes(candidate))

    def test_manifest_identity_and_exact_new_header_and_module_revisions_are_preserved(self):
        f = self.fixture
        for kind in ("uuid", "header", "module", "old_revision"):
            candidate = copy.deepcopy(f.new_pack)
            manifest = json.loads(candidate["manifest.json"])
            old = copy.deepcopy(f.old_pack)
            if kind == "uuid": manifest["header"]["uuid"] = "00000000-0000-0000-0000-000000000001"
            elif kind == "header": manifest["header"]["version"] = [2, 13, 51000]
            elif kind == "module": manifest["modules"][0]["version"] = [2, 13, 51002]
            else:
                previous = json.loads(old["manifest.json"])
                previous["header"]["version"] = previous["modules"][0]["version"] = [2, 13, 50000]
                old["manifest.json"] = json_bytes(previous)
            candidate["manifest.json"] = json_bytes(manifest)
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                REPAIR.validate_pack(archive_bytes(old), archive_bytes(candidate))

    def test_dependency_lock_cannot_change_release_mod_bridge_download_or_pack_binding(self):
        f = self.fixture
        original = copy.deepcopy(f.new_lock)
        for kind in ("version", "minecraft", "download", "mod_path", "bridge_source", "pack_sha"):
            f.new_lock = copy.deepcopy(original)
            if kind == "version": f.new_lock["version"] = "2.13.0-explore.52"
            elif kind == "minecraft": f.new_lock["minecraft"] = "26.3"
            elif kind == "download": f.new_lock["downloads"][0]["sha256"] = "0" * 64
            elif kind == "mod_path": f.new_lock["bundled"][0]["path"] = "fabric/mods/replaced.jar"
            elif kind == "bridge_source": f.new_lock["creative_bridge"]["source_hashes"]["fixture.java"] = "0" * 64
            else: next(e for e in f.new_lock["bundled"] if e["path"] == BASE.PACK_PATH)["sha256"] = "0" * 64
            f.write_lock()
            with self.subTest(kind=kind): self.assert_refused()

    def test_prepared_mod_bridge_download_runtime_and_mapping_bytes_cannot_drift(self):
        f = self.fixture
        targets = [*(f.server / name for name in f.artifacts), f.server / "runtime.lock.json",
                   f.mappings / BASE.MAPPINGS[0]]
        for target in targets:
            original = target.read_bytes()
            target.write_bytes(b"unapproved replacement")
            with self.subTest(name=target.name): self.assert_refused()
            target.write_bytes(original)

    def test_fixture_baseline_must_identify51_and_verify_its_bundled_artifacts(self):
        f = self.fixture
        lock = copy.deepcopy(f.old_lock)
        lock["version"] = "2.13.0-explore.50"
        f.baseline_entries[BASE.LOCK_PATH] = json_bytes(lock)
        f.write_baseline()
        self.assert_refused()
        f.baseline_entries[BASE.LOCK_PATH] = json_bytes(f.old_lock)
        f.baseline_entries["geyser/extensions/InfinityCreativeBridge.jar"] = b"forged baseline"
        f.write_baseline()
        self.assert_refused()

    def test_missing_notes_and_ambiguous_mapping_provenance_are_rejected(self):
        f = self.fixture
        f.notes.write_text("  ")
        self.assert_refused()
        f.notes.write_text("Device comparison candidate")
        proof = json.loads(f.baseline_entries["checks/armor-equipment-native.json"])
        name = BASE.MAPPINGS[0]
        proof["input_sha256"]["/second/" + name] = BASE.digest((f.mappings / name).read_bytes())
        f.baseline_entries["checks/armor-equipment-native.json"] = json_bytes(proof)
        f.write_baseline()
        self.assert_refused()

    def test_existing_output_and_baseline_are_never_overwritten(self):
        f = self.fixture
        f.output.parent.mkdir()
        f.output.write_bytes(b"existing candidate")
        with self.assertRaises(FileExistsError): f.build()
        self.assertEqual(f.output.read_bytes(), b"existing candidate")
        before = f.baseline.read_bytes()
        f.output = f.baseline
        with self.assertRaises(ValueError): f.build()
        self.assertEqual(f.baseline.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
