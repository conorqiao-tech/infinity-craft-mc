"""R5 temporary UI probe and R6 cleanup must bind the immutable R4 resource stack."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools'))
try:
    import package_wing_ui_diagnostic as UI
finally:
    sys.path.pop(0)
from test_wing_diagnostic_package import zip_bytes, json_bytes, revision
from test_wing_slot_repair import Fixture as R4Fixture

BASE,PAIR,PROBE=UI.BASE,UI.PAIR,UI.PROBE


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False)


class WingUIDiagnosticPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        root=self.root=Path(self.temp.name)
        r4=root/'r4';r4.mkdir()
        self.r4=R4Fixture(r4);self.r4.build()  # Synthetic, self-contained R4 ZIP and prepared server.
        self.r4_zip,self.server,self.mappings=self.r4.output,self.r4.server,self.r4.mappings
        self.r4_sha=BASE.file_digest(self.r4_zip)
        self.r4_entries=PAIR.read_zip(self.r4_zip,self.r4_sha,UI.R4_PREFIX,maximum_entries=4)
        self.dnotes,self.cnotes=root/'r5.md',root/'r6.md'
        self.dnotes.write_text('Temporary probe panels also show on ordinary/held elytra and in the world.\n')
        self.cnotes.write_text('Restores every R4 member except a manifest cache revision.\n')
        self.dout,self.cout,self.pout=root/'ui/r5.zip',root/'ui/r6.zip',root/'ui/PAIR.json'

    def build(self, **kwargs):
        return UI.build_pair(self.r4_zip,self.server,self.mappings,self.dnotes,self.cnotes,self.dout,self.cout,self.pout,
            acknowledge_temporary_diagnostic=kwargs.pop('acknowledge_temporary_diagnostic',True),
            expected_r4_sha256=kwargs.pop('expected_r4_sha256',self.r4_sha),**kwargs)

    def assert_refused(self, error=ValueError, **kwargs):
        with self.assertRaises(error):self.build(**kwargs)
        for path in (self.dout,self.cout,self.pout):self.assertFalse(path.exists(),path.name)

    def rewrite_r4(self, entries, prefix=UI.R4_PREFIX):
        self.r4_zip.write_bytes(zip_bytes({prefix+name:data for name,data in entries.items()}))
        self.r4_sha=BASE.file_digest(self.r4_zip)

    def phase(self, path, prefix):
        files=BASE.safe_zip(path.read_bytes())
        return {name[len(prefix):]:data for name,data in files.items()},files

    def inputs(self):
        paths=[self.r4_zip,self.dnotes,self.cnotes,*(p for p in self.server.rglob('*') if p.is_file()),
               *(p for p in self.mappings.rglob('*') if p.is_file())]
        return {p:p.read_bytes() for p in paths}

    def test_four_file_phases_bind_r4_r5_r6_and_keep_proof_boundaries_false(self):
        before=self.inputs();pair=self.build();self.assertEqual(before,self.inputs())
        r5,raw5=self.phase(self.dout,UI.DIAGNOSTIC_PREFIX);r6,raw6=self.phase(self.cout,UI.CLEANUP_PREFIX)
        self.assertEqual(set(r5),{BASE.PACK_PATH,BASE.LOCK_PATH,UI.DIAGNOSTIC_NOTES,'PATCH.json'})
        self.assertEqual(set(r6),{BASE.PACK_PATH,BASE.LOCK_PATH,UI.CLEANUP_NOTES,'PATCH.json'})
        self.assertTrue(all(name.startswith(UI.DIAGNOSTIC_PREFIX) for name in raw5))
        self.assertTrue(all(name.startswith(UI.CLEANUP_PREFIX) for name in raw6))
        p5,p6=BASE.json_object(r5['PATCH.json']),BASE.json_object(r6['PATCH.json'])
        r4_hashes={BASE.LOCK_PATH:BASE.digest(self.r4_entries[BASE.LOCK_PATH]),BASE.PACK_PATH:BASE.digest(self.r4_entries[BASE.PACK_PATH])}
        self.assertEqual(p5['baseline'],{'file':self.r4_zip.name,'sha256':self.r4_sha,'version':UI.VERSION})
        self.assertEqual(p5['expected_old_sha256'],r4_hashes)
        self.assertEqual(p5['expected_new_sha256'],{BASE.LOCK_PATH:BASE.digest(r5[BASE.LOCK_PATH]),BASE.PACK_PATH:BASE.digest(r5[BASE.PACK_PATH])})
        self.assertEqual(p6['baseline'],{'file':self.dout.name,'sha256':BASE.file_digest(self.dout),'version':UI.VERSION})
        self.assertEqual(p6['expected_old_sha256'],p5['expected_new_sha256'])
        self.assertEqual(p5['cleanup_target'],{'pack_sha256':BASE.digest(r6[BASE.PACK_PATH]),'lock_sha256':BASE.digest(r6[BASE.LOCK_PATH]),
            'resource_revision':[2,13,51006],'restores_r4_members_except_manifest':True,'baseline_r4_pack_sha256':r4_hashes[BASE.PACK_PATH]})
        self.assertEqual(p5['role'],'temporary_diagnostic');self.assertEqual(p6['role'],'diagnostic_cleanup')
        self.assertEqual(p5['diagnostic'],UI.ACKNOWLEDGMENT);self.assertTrue(p5['diagnostic']['probe_panels_on_ordinary_elytra'])
        self.assertIn('probe_validator_report',p5);self.assertIn('probe_validator_report',p6)
        self.assertEqual(len(p5['pack_changes']['added_entries']),3);self.assertEqual(p5['pack_changes']['deleted_entries'],[])
        self.assertEqual(set(p5['pack_changes']['changed_entries']),{'manifest.json',UI.ATTACHABLE})
        self.assertEqual(p6['pack_changes']['deleted_entries'],p5['pack_changes']['added_entries'])
        self.assertEqual(set(p6['pack_changes']['r4_to_cleanup']['changed_entries']),{'manifest.json'})
        self.assertEqual(p6['baseline_r4_pack_sha256'],r4_hashes[BASE.PACK_PATH])
        for phase,metadata in ((r5,p5),(r6,p6)):
            self.assertEqual(metadata['bundle_files'],sorted(phase))
            self.assertEqual(metadata['payload_sha256'],{n:BASE.digest(d) for n,d in phase.items() if n!='PATCH.json'})
            self.assertEqual(metadata['unchanged_locked_artifacts_sha256'],self.r4.history.unchanged)
            self.assertEqual(metadata['unchanged_mapping_sha256'],self.r4.history.mapping_hashes)
            self.assertEqual(metadata['provenance']['frozen_historical_helpers_sha256'],UI.FROZEN_HELPER_HASHES)
            self.assertEqual(metadata['provenance']['probe_helper_sha256'],BASE.file_digest(PROBE.__file__))
        for metadata in (p5,p6,pair):
            self.assertIs(metadata['owner_approval_required'],True)
            for key in ('live_installation_approval_received','live_installation_performed',
                        'runtime_molang_context_verified','device_rendering_verified'):self.assertIs(metadata[key],False,key)
        self.assertEqual(pair,BASE.json_object(self.pout.read_bytes()))
        self.assertEqual(pair['installed_r4_baseline']['sha256'],self.r4_sha)
        self.assertEqual(pair['diagnostic']['sha256'],BASE.file_digest(self.dout));self.assertEqual(pair['cleanup']['sha256'],BASE.file_digest(self.cout))
        self.assertEqual(pair['diagnostic']['resource_revision'],[2,13,51005]);self.assertEqual(pair['cleanup']['resource_revision'],[2,13,51006])
        self.assertIs(pair['cleanup_contract']['same_pack_sha256_as_r4'],False)
        for text in (r5['PATCH.json'],r6['PATCH.json'],self.pout.read_bytes()):self.assertNotIn(str(self.root).encode(),text)

    def test_cleanup_is_exact_r4_except_manifest_revision_and_probe_keeps_all_assets(self):
        self.build()
        r4=BASE.safe_zip(self.r4_entries[BASE.PACK_PATH])
        r5=BASE.safe_zip(self.phase(self.dout,UI.DIAGNOSTIC_PREFIX)[0][BASE.PACK_PATH])
        r6_pack=self.phase(self.cout,UI.CLEANUP_PREFIX)[0][BASE.PACK_PATH];r6=BASE.safe_zip(r6_pack)
        self.assertEqual(r6.keys(),r4.keys());self.assertEqual({n for n in r4 if r4[n]!=r6[n]},{'manifest.json'})
        self.assertNotEqual(BASE.digest(r6_pack),BASE.digest(self.r4_entries[BASE.PACK_PATH]))
        self.assertEqual(canonical(json.loads(revision(r6,51004)['manifest.json'])),canonical(json.loads(r4['manifest.json'])))
        for name,data in r4.items():
            if name.endswith(('.png','.geo.json')):self.assertEqual(r5[name],data,name)

    def test_phase_locks_are_typed_r4_copies_with_only_the_pack_checksum_changed(self):
        self.build();r4_lock=BASE.json_object(self.r4_entries[BASE.LOCK_PATH])
        for path,prefix in ((self.dout,UI.DIAGNOSTIC_PREFIX),(self.cout,UI.CLEANUP_PREFIX)):
            files=self.phase(path,prefix)[0];lock=BASE.json_object(files[BASE.LOCK_PATH])
            for group in ('downloads','bundled'):
                for entry in lock[group]:
                    if entry['path']==BASE.PACK_PATH:
                        self.assertEqual(entry['sha256'],BASE.digest(files[BASE.PACK_PATH]))
                        entry['sha256']=BASE.digest(self.r4_entries[BASE.PACK_PATH])
            self.assertEqual(canonical(lock),canonical(r4_lock))
        r4_pack=self.r4_entries[BASE.PACK_PATH];new_pack=r4_pack+b'\0'
        for kind in ('integer_to_boolean','boolean_to_integer'):
            coerced=copy.deepcopy(r4_lock)
            if kind=='integer_to_boolean':coerced['creative_bridge']['format_version']=True
            else:coerced['snapshot5']['experimental']=1
            with self.subTest(kind=kind),self.assertRaises(ValueError):UI.derived_lock(r4_lock,r4_pack,new_pack,coerced)

    def test_acknowledgment_must_be_literal_true(self):
        for value in (False,1,'true',None,[True]):
            with self.subTest(value=value):self.assert_refused(acknowledge_temporary_diagnostic=value)

    def test_production_pin_refuses_fixture_and_wrong_hashes(self):
        with self.assertRaises(ValueError):
            UI.build_pair(self.r4_zip,self.server,self.mappings,self.dnotes,self.cnotes,self.dout,self.cout,self.pout,
                          acknowledge_temporary_diagnostic=True)
        self.assertFalse(self.dout.exists())
        for value in ('0'*64,self.r4_sha.upper(),None):
            with self.subTest(value=value):self.assert_refused(expected_r4_sha256=value)

    def test_frozen_helpers_and_r4_packager_pins_cannot_be_replaced(self):
        for target,name in ((UI.PINNED_SOURCES,UI.R4_PACKAGER),(UI.FROZEN_HELPER_HASHES,'tools/package_wing_diagnostic.py')):
            with self.subTest(name=name),patch.dict(target,{name:'0'*64}):self.assert_refused()

    def test_rebound_malformed_r4_receipt_is_refused_structurally(self):
        original=copy.deepcopy(self.r4_entries)
        def mutate(metadata,entries,kind):
            if kind=='extra_payload':entries['extra.txt']=b'unapproved'
            elif kind=='missing_notes':
                entries.pop(UI.REPAIR.NOTES_PATH);metadata['payload_sha256'].pop(UI.REPAIR.NOTES_PATH)
                metadata['bundle_files'].remove(UI.REPAIR.NOTES_PATH)
            elif kind=='payload_hash':entries[UI.REPAIR.NOTES_PATH]+=b'changed'
            elif kind=='device_proof':metadata['device_rendering_verified']=True
            elif kind=='integer_approval':metadata['owner_approval_required']=1
            elif kind=='lineage':metadata['lineage']['r3']['sha256']='0'*64
            elif kind=='lineage_path':metadata['lineage']['r1']['file']='private/r1.zip'
            elif kind=='provenance':metadata['provenance']['packager_sha256']='0'*64
            elif kind=='predicate':metadata['pack_changes']['positive_slot_predicate']="context.item_slot == 'body'"
            elif kind=='role':metadata['role']='temporary_diagnostic'
            elif kind=='invented_proof':metadata['ipad_verified']=True
            elif kind=='native_pin':metadata['unchanged_locked_artifacts_sha256']['runtime.lock.json']='0'*64
        for kind in ('extra_payload','missing_notes','payload_hash','device_proof','integer_approval','lineage',
                     'lineage_path','provenance','predicate','role','invented_proof','native_pin'):
            entries=copy.deepcopy(original);metadata=BASE.json_object(entries['PATCH.json'])
            mutate(metadata,entries,kind);entries['PATCH.json']=json_bytes(metadata);self.rewrite_r4(entries)
            with self.subTest(kind=kind):self.assert_refused()
        self.rewrite_r4(original,prefix=PAIR.CLEANUP_PREFIX);self.assert_refused()

    def test_native_runtime_mapping_and_prepared_r4_drift_are_refused(self):
        targets=[*(self.server/name for name in self.r4.history.artifacts),self.server/'runtime.lock.json',
                 self.server/BASE.PACK_PATH,self.server/BASE.LOCK_PATH,*(self.mappings/name for name in BASE.MAPPINGS)]
        for target in targets:
            previous=target.read_bytes();target.write_bytes(previous+b' ')
            with self.subTest(name=target.name):self.assert_refused()
            target.write_bytes(previous)

    def test_independent_member_delta_checks_refuse_probe_scope_violations(self):
        r4_pack=self.r4_entries[BASE.PACK_PATH];r4=BASE.safe_zip(r4_pack)
        probe=PROBE.apply(dict(r4),expected_asset_counts=None);added=sorted(probe.keys()-r4.keys())
        png=next(n for n in r4 if n.endswith('.png'));geo=next(n for n in r4 if n.endswith('.geo.json'))
        other=next(n for n in r4 if n not in (png,geo,'manifest.json',UI.ATTACHABLE))
        attachable=json.loads(probe[UI.ATTACHABLE])
        attachable['minecraft:attachable']['description']['scripts']['pre_animation'][0]+=' 1.0;'
        bad={'deleted_png':{k:v for k,v in probe.items() if k!=png},'changed_png':{**probe,png:probe[png]+b'x'},
             'changed_geometry':{**probe,geo:probe[geo]+b' '},'other_resource':{**probe,other:probe[other]+b' '},
             'fourth_resource':{**probe,'textures/extra_probe.png':b'x'},'missing_resource':{k:v for k,v in probe.items() if k!=added[0]},
             'production_selector':{**probe,UI.ATTACHABLE:json_bytes(attachable)},
             'r4_revision':revision(probe,51004)}
        for kind,members in bad.items():
            with self.subTest(kind=kind),self.assertRaises(ValueError):UI.diagnostic_delta(r4_pack,PAIR.pack_bytes(members))
        good=PAIR.pack_bytes(probe);cleanup=revision(r4,51006)
        manifest=json.loads(cleanup['manifest.json']);manifest['header']['name']+=' changed'
        bad={'same_as_r4':r4,'probe_survives':{**cleanup,added[0]:probe[added[0]]},
             'attachable_kept':{**cleanup,UI.ATTACHABLE:probe[UI.ATTACHABLE]},'manifest_label':{**cleanup,'manifest.json':json_bytes(manifest)},
             'png':{**cleanup,png:r4[png]+b'x'},'r5_revision':revision(r4,51005)}
        UI.cleanup_delta(r4_pack,good,PAIR.pack_bytes(cleanup))
        for kind,members in bad.items():
            with self.subTest(kind=kind),self.assertRaises(ValueError):UI.cleanup_delta(r4_pack,good,PAIR.pack_bytes(members))
        with patch.object(PROBE,'cleanup',lambda members,**kwargs:dict(members)),\
                patch.object(PROBE,'validate_cleanup',lambda old,new,**kwargs:{}):
            self.assert_refused()

    def test_existing_outputs_inputs_live_server_staging_and_collisions_are_refused(self):
        self.dout.parent.mkdir();self.dout.write_bytes(b'existing candidate')
        with self.assertRaises(ValueError):self.build()
        self.assertEqual(self.dout.read_bytes(),b'existing candidate');self.assertFalse(self.cout.exists());self.dout.unlink()
        before=self.inputs();mapping=self.mappings/BASE.MAPPINGS[0]
        cases={'r4_input':dict(dout=self.r4_zip),'mapping_alias':dict(pout=self.mappings/'..'/self.mappings.name/mapping.name),
               'server_staging':dict(cout=self.server/'r6.zip'),'collision':dict(cout=self.dout),
               'wrong_extension':dict(pout=self.root/'ui/PAIR.zip'),'live':dict(dout=PAIR.LIVE_ROOT/'never-create-ui-test.zip')}
        defaults=dict(dout=self.dout,cout=self.cout,pout=self.pout)
        for kind,override in cases.items():
            self.dout,self.cout,self.pout=(override.get(k,v) for k,v in defaults.items())
            with self.subTest(kind=kind),self.assertRaises(ValueError):self.build()
            self.assertFalse((PAIR.LIVE_ROOT/'never-create-ui-test.zip').exists())
            self.dout,self.cout,self.pout=defaults.values()
            for path in defaults.values():self.assertFalse(path.exists())
        self.assertEqual(before,self.inputs())

    def test_partial_write_failure_removes_created_outputs(self):
        blocker=self.root/'blocker';blocker.write_bytes(b'not a folder');self.pout=blocker/'PAIR.json'
        self.assert_refused(error=OSError)
        self.assertEqual(blocker.read_bytes(),b'not a folder')


if __name__=='__main__':
    unittest.main()
