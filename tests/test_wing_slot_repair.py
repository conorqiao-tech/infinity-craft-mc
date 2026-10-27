"""R4 changes two positive torso predicates and preserves the proven stack."""
import copy
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
try:
    import package_wing_slot_repair as REPAIR
finally:
    sys.path.pop(0)
from test_wing_diagnostic_package import Fixture as HistoricalFixture, zip_bytes, json_bytes, revision

BASE,PAIR,R1=REPAIR.BASE,REPAIR.PAIR,REPAIR.R1


class Fixture:
    def __init__(self, root):
        self.root=root
        historical=root/'historical';historical.mkdir()
        self.history=HistoricalFixture(historical)
        self.history.build()  # Disposable, internally bound r1 -> r2 -> r3 fixture.
        self.native_zip,self.r1_zip,self.r3_zip=self.history.native_zip,self.history.r1_zip,self.history.cleanup_output
        self.native_sha,self.r1_sha=self.history.native_sha,self.history.r1_sha
        self.r3_sha=BASE.file_digest(self.r3_zip)
        self.server,self.mappings=self.history.server,self.history.mappings
        self.old_pack=self.history.normal_pack
        self.old_files=copy.deepcopy(self.history.normal_files)
        self.new_files=revision(self.old_files,51004)
        self.old_attachable=json.loads(self.old_files[REPAIR.ATTACHABLE])
        self.new_attachable=copy.deepcopy(self.old_attachable)
        self.new_attachable['minecraft:attachable']['description']['scripts']['pre_animation']=[
            variable+" = context.item_slot == 'torso' ? ((context.owning_entity->query.has_property('"+name+"')) ? "
            "(context.owning_entity->query.property('"+name+"')) : 0.0) : 0.0;" for variable,name in R1.SELECTORS]
        self.new_files[REPAIR.ATTACHABLE]=json_bytes(self.new_attachable)
        self.notes=root/'notes.md';self.notes.write_text('Torso slot value is inferred; iPad/Molang comparison remains unverified.\n')
        self.output=root/'output/slot-repair.zip'
        self.write_pack()

    def write_pack(self):
        data=zip_bytes(self.new_files)
        (self.server/BASE.PACK_PATH).write_bytes(data)
        self.new_lock_data=PAIR.lock_for_pack(BASE.json_object(self.history.normal_lock_data),data)
        (self.server/BASE.LOCK_PATH).write_bytes(self.new_lock_data)

    def rewrite_r3(self, entries):
        self.r3_zip.write_bytes(zip_bytes({PAIR.CLEANUP_PREFIX+name:data for name,data in entries.items()}))
        self.r3_sha=BASE.file_digest(self.r3_zip)

    def build(self, **kwargs):
        return REPAIR.build_repair(self.native_zip,self.r1_zip,self.r3_zip,self.server,self.mappings,self.notes,self.output,
            expected_native_sha256=kwargs.pop('expected_native_sha256',self.native_sha),
            expected_r1_sha256=kwargs.pop('expected_r1_sha256',self.r1_sha),
            expected_r3_sha256=kwargs.pop('expected_r3_sha256',self.r3_sha),**kwargs)


class WingSlotRepairTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.f=Fixture(Path(self.temp.name))

    def assert_refused(self, **kwargs):
        with self.assertRaises(ValueError):self.f.build(**kwargs)
        self.assertFalse(self.f.output.exists())

    def test_resource_only_four_file_bundle_binds_r3_and_marks_slot_inference_unverified(self):
        f=self.f
        originals={p:p.read_bytes() for p in (f.native_zip,f.r1_zip,f.r3_zip)}
        report=f.build();files=BASE.safe_zip(f.output.read_bytes())
        self.assertEqual(set(files),{REPAIR.REPAIR_PREFIX+name for name in (BASE.PACK_PATH,BASE.LOCK_PATH,REPAIR.NOTES_PATH,'PATCH.json')})
        self.assertEqual(report,BASE.json_object(files[REPAIR.REPAIR_PREFIX+'PATCH.json']))
        self.assertEqual(report['baseline'],{'file':f.r3_zip.name,'sha256':f.r3_sha,'version':'2.13.0-explore.51'})
        self.assertEqual(report['expected_old_sha256'][BASE.PACK_PATH],BASE.digest(f.old_pack))
        self.assertEqual(report['expected_new_sha256'][BASE.PACK_PATH],BASE.digest(files[REPAIR.REPAIR_PREFIX+BASE.PACK_PATH]))
        self.assertEqual(set(report['pack_changes']['changed_entries']),{'manifest.json',REPAIR.ATTACHABLE})
        self.assertEqual(report['pack_changes']['slot_predicate_replacements'],{REPAIR.ATTACHABLE:2})
        self.assertEqual(report['pack_changes']['positive_slot_predicate'],"context.item_slot == 'torso'")
        self.assertEqual(report['pack_changes']['version'],[2,13,51004])
        self.assertEqual(report['pack_changes']['added_entries'],[]);self.assertEqual(report['pack_changes']['deleted_entries'],[])
        self.assertTrue(report['slot_value_inferred']);self.assertTrue(report['pack_changes']['slot_value_inferred'])
        self.assertFalse(report['runtime_molang_context_verified']);self.assertFalse(report['device_rendering_verified'])
        self.assertFalse(report['live_installation_performed']);self.assertFalse(report['live_installation_approval_received'])
        self.assertTrue(report['owner_approval_required'])
        self.assertEqual(report['provenance']['frozen_historical_helpers_sha256'],REPAIR.FROZEN_HELPER_HASHES)
        self.assertEqual(report['unchanged_locked_artifacts_sha256'],f.history.unchanged)
        self.assertEqual(report['unchanged_mapping_sha256'],f.history.mapping_hashes)
        for name,checksum in report['payload_sha256'].items():self.assertEqual(BASE.digest(files[REPAIR.REPAIR_PREFIX+name]),checksum)
        self.assertNotIn(str(f.root),json.dumps(report))
        self.assertEqual(originals,{p:p.read_bytes() for p in originals})

    def test_default_immutable_cli_pins_refuse_fixture_and_all_three_wrong_hashes(self):
        f=self.f
        with self.assertRaises(ValueError):
            REPAIR.build_repair(f.native_zip,f.r1_zip,f.r3_zip,f.server,f.mappings,f.notes,f.output)
        for key in ('expected_native_sha256','expected_r1_sha256','expected_r3_sha256'):
            with self.subTest(key=key):self.assert_refused(**{key:'0'*64})

    def test_frozen_historical_validator_hashes_cannot_be_silently_replaced(self):
        with patch.dict(REPAIR.FROZEN_HELPER_HASHES,{'tools/package_wing_diagnostic.py':'0'*64}):self.assert_refused()

    def test_both_selectors_require_positive_exact_torso_predicate(self):
        f=self.f
        for index in (0,1):
            for predicate in (R1.OWNER_SLOT_QUERY+" == 'body'","context.item_slot == 'body'","c.item_slot == 'torso'",
                              "context.item_slot != 'main_hand' && context.item_slot != 'off_hand'","1.0"):
                candidate=copy.deepcopy(f.new_attachable)
                expressions=candidate['minecraft:attachable']['description']['scripts']['pre_animation']
                expressions[index]=expressions[index].replace("context.item_slot == 'torso'",predicate)
                with self.subTest(index=index,predicate=predicate),self.assertRaises(ValueError):REPAIR.validate_attachable(f.old_attachable,candidate)

    def test_baseline_unknown_missing_extra_reordered_or_already_torso_selectors_rejected(self):
        f=self.f
        for kind in ('unknown','missing','extra','reordered','already_torso'):
            old=copy.deepcopy(f.old_attachable);expressions=old['minecraft:attachable']['description']['scripts']['pre_animation']
            if kind=='unknown':expressions[1]=expressions[1].replace('infinityhub:infinity_winged_chest','infinityhub:foreign')
            elif kind=='missing':expressions.pop()
            elif kind=='extra':expressions.append(expressions[0])
            elif kind=='reordered':expressions.reverse()
            else:expressions[0]=f.new_attachable['minecraft:attachable']['description']['scripts']['pre_animation'][0]
            with self.subTest(kind=kind),self.assertRaises(ValueError):REPAIR.validate_attachable(old,f.new_attachable)

    def test_guarded_owner_properties_and_false_fallbacks_are_preserved(self):
        f=self.f
        for kind in ('missing_guard','foreign_owner','wrong_property','true_fallback','extra_or'):
            candidate=copy.deepcopy(f.new_attachable);expressions=candidate['minecraft:attachable']['description']['scripts']['pre_animation']
            if kind=='missing_guard':expressions[1]=expressions[1].replace('query.has_property','query.property')
            elif kind=='foreign_owner':expressions[1]=expressions[1].replace('context.owning_entity','context.other_entity')
            elif kind=='wrong_property':expressions[1]=expressions[1].replace('infinityhub:infinity_winged_chest','infinityhub:winged_chest')
            elif kind=='true_fallback':expressions[1]=expressions[1].replace(': 0.0',': 1.0')
            else:expressions[1]=expressions[1].replace("== 'torso'","== 'torso' || 1.0")
            with self.subTest(kind=kind),self.assertRaises(ValueError):REPAIR.validate_attachable(f.old_attachable,candidate)

    def test_native_elytra_aliases_renderer_and_animation_are_not_replaced(self):
        f=self.f
        for kind in ('identifier','texture','geometry','renderer','animation','parent_setup'):
            candidate=copy.deepcopy(f.new_attachable);d=candidate['minecraft:attachable']['description']
            if kind=='identifier':d['identifier']='convergence:winged_chest'
            elif kind=='texture':d['textures']['default']='textures/convergence/models/armor/armor_wings'
            elif kind=='geometry':d['geometry']['default']='geometry.convergence.combined_wings_original'
            elif kind=='renderer':d['render_controllers'][0]='controller.render.armor'
            elif kind=='animation':d['animations']['default_controller']='controller.animation.changed'
            else:d['scripts']['parent_setup']='variable.chest_layer_visible = 1.0;'
            with self.subTest(kind=kind),self.assertRaises(ValueError):REPAIR.validate_attachable(f.old_attachable,candidate)

    def test_unrelated_json_scalar_types_cannot_change_through_python_bool_equality(self):
        old=copy.deepcopy(self.f.old_attachable);new=copy.deepcopy(self.f.new_attachable)
        old['fixture_untouched_number']=1;new['fixture_untouched_number']=True
        with self.assertRaises(ValueError):REPAIR.validate_attachable(old,new)

    def test_unchanged_png_geometry_controller_and_animation_bytes_are_enforced(self):
        f=self.f
        names=[next(n for n in f.new_files if n.endswith('.png')),next(n for n in f.new_files if n.endswith('.geo.json')),
               next(n for n in f.new_files if n.startswith('render_controllers/')),next(n for n in f.new_files if n.startswith('animations/'))]
        for name in names:
            previous=f.new_files[name];f.new_files[name]+=b'unapproved';f.write_pack()
            with self.subTest(name=name):self.assert_refused()
            f.new_files[name]=previous

    def test_no_entries_or_removed_shield_and_diagnostic_resources_can_be_added(self):
        f=self.f
        for name in ('extra.json',*sorted(PAIR.SHIELD_ATTACHABLES),PAIR.DIAGNOSTIC.LEGEND):
            candidate=copy.deepcopy(f.new_files);candidate[name]=b'{}'
            with self.subTest(name=name),self.assertRaises(ValueError):REPAIR.validate_pack(f.old_pack,zip_bytes(candidate))
        candidate=copy.deepcopy(f.new_files);candidate.pop(next(n for n in candidate if n.endswith('.png')))
        with self.assertRaises(ValueError):REPAIR.validate_pack(f.old_pack,zip_bytes(candidate))

    def test_manifest_requires_exact_increased_header_and_every_module_version(self):
        f=self.f
        for kind in ('header','module','uuid','diagnostic_label','boolean_engine'):
            candidate=copy.deepcopy(f.new_files);manifest=json.loads(candidate['manifest.json'])
            if kind=='header':manifest['header']['version']=[2,13,51003]
            elif kind=='module':manifest['modules'][0]['version']=[2,13,51005]
            elif kind=='uuid':manifest['header']['uuid']='00000000-0000-0000-0000-000000000001'
            elif kind=='diagnostic_label':manifest['header']['name']+=' DIAGNOSTIC'
            else:manifest['header']['min_engine_version'][0]=True
            candidate['manifest.json']=json_bytes(manifest)
            with self.subTest(kind=kind),self.assertRaises(ValueError):REPAIR.validate_pack(f.old_pack,zip_bytes(candidate))

    def test_manifest_only_stale_r4_and_partial_coverage_are_rejected(self):
        f=self.f
        candidate=copy.deepcopy(f.new_files);candidate[REPAIR.ATTACHABLE]=f.old_files[REPAIR.ATTACHABLE]
        with self.assertRaises(ValueError):REPAIR.validate_pack(f.old_pack,zip_bytes(candidate))
        with self.assertRaises(ValueError):REPAIR.validate_pack(f.old_pack,f.old_pack)
        with self.assertRaises(ValueError):REPAIR.validate_pack(f.old_pack,zip_bytes(f.new_files),{REPAIR.ATTACHABLE:1})

    def test_game_native_mod_bridge_download_runtime_and_mapping_bytes_cannot_drift(self):
        f=self.f
        for target in [*(f.server/name for name in f.history.artifacts),f.server/'runtime.lock.json',f.mappings/BASE.MAPPINGS[0]]:
            previous=target.read_bytes();target.write_bytes(b'unapproved replacement')
            with self.subTest(name=target.name):self.assert_refused()
            target.write_bytes(previous)

    def test_resource_lock_cannot_change_version_mod_path_or_native_provenance(self):
        f=self.f;original=BASE.json_object(f.new_lock_data)
        for kind in ('release','minecraft','mod','bridge_source','pack_hash'):
            lock=copy.deepcopy(original)
            if kind=='release':lock['version']='2.13.0-explore.52'
            elif kind=='minecraft':lock['minecraft']='26.3'
            elif kind=='mod':lock['bundled'][0]['path']='fabric/mods/replaced.jar'
            elif kind=='bridge_source':lock['creative_bridge']['source_hashes']['fixture.java']='0'*64
            else:next(v for v in lock['bundled'] if v['path']==BASE.PACK_PATH)['sha256']='0'*64
            (f.server/BASE.LOCK_PATH).write_bytes(json_bytes(lock))
            with self.subTest(kind=kind):self.assert_refused()

    def test_resource_lock_cannot_use_boolean_integer_equality_to_change_unrelated_types(self):
        f=self.f;original=BASE.json_object(f.new_lock_data)
        for kind in ('integer_to_boolean','boolean_to_integer'):
            lock=copy.deepcopy(original)
            if kind=='integer_to_boolean':lock['creative_bridge']['format_version']=True
            else:lock['snapshot5']['experimental']=1
            # The shared historical validator accepts these equal Python
            # values; r4's typed normalization must independently refuse them.
            BASE.validate_lock(BASE.json_object(f.history.normal_lock_data),lock,BASE.digest(f.old_pack),
                               BASE.digest((f.server/BASE.PACK_PATH).read_bytes()))
            (f.server/BASE.LOCK_PATH).write_bytes(json_bytes(lock))
            with self.subTest(kind=kind):self.assert_refused()

    def test_re_pinned_fixture_cannot_forge_r3_cleanup_scope_or_provenance(self):
        f=self.f;old=PAIR.read_zip(f.r3_zip,f.r3_sha,PAIR.CLEANUP_PREFIX,maximum_entries=4)
        for kind in ('report','provenance','new_pack_hash','extra_payload'):
            entries=copy.deepcopy(old);metadata=BASE.json_object(entries['PATCH.json'])
            if kind=='report':metadata['pack_changes']['native_shield_fallback']=False
            elif kind=='provenance':metadata['provenance']['packager_sha256']='0'*64
            elif kind=='new_pack_hash':metadata['expected_new_sha256'][BASE.PACK_PATH]='0'*64
            else:entries['extra.txt']=b'unapproved'
            entries['PATCH.json']=json_bytes(metadata);f.rewrite_r3(entries)
            with self.subTest(kind=kind):self.assert_refused()

    def test_unsafe_private_and_linked_archive_entries_refused_with_matching_fixture_hash(self):
        f=self.f
        for name in ('settings.json','world/level.dat','../escape'):
            data=zip_bytes({PAIR.CLEANUP_PREFIX+name:b'private'});f.r3_zip.write_bytes(data);f.r3_sha=BASE.digest(data)
            with self.subTest(name=name):self.assert_refused()
        stream=io.BytesIO();entry=zipfile.ZipInfo(PAIR.CLEANUP_PREFIX+'linked');entry.external_attr=(stat.S_IFLNK|0o777)<<16
        with zipfile.ZipFile(stream,'w') as archive:archive.writestr(entry,b'outside')
        f.r3_zip.write_bytes(stream.getvalue());f.r3_sha=BASE.digest(stream.getvalue());self.assert_refused()

    def test_notes_required_and_linked_prepared_pack_refused(self):
        f=self.f;previous=f.notes.read_bytes();f.notes.write_text('  ');self.assert_refused();f.notes.write_bytes(previous)
        target=f.server/BASE.PACK_PATH;outside=f.root/'outside.mcpack';outside.write_bytes(target.read_bytes())
        target.unlink();target.symlink_to(outside);self.assert_refused()

    def test_existing_outputs_inputs_and_live_folder_are_never_overwritten(self):
        f=self.f;f.output.parent.mkdir();f.output.write_bytes(b'existing candidate')
        with self.assertRaises(ValueError):f.build()
        self.assertEqual(f.output.read_bytes(),b'existing candidate')
        f.output=f.r3_zip;previous=f.r3_zip.read_bytes()
        with self.assertRaises(ValueError):f.build()
        self.assertEqual(f.r3_zip.read_bytes(),previous)
        f.output=PAIR.LIVE_ROOT/'never-create-slot-repair-test.zip'
        with self.assertRaises(ValueError):f.build()
        self.assertFalse(f.output.exists())


if __name__=='__main__':
    unittest.main()
