"""A temporary visual probe must ship an exact, separately bound cleanup."""
import copy
import io
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools'))
try:
    import package_wing_diagnostic as PACKAGE
finally:
    sys.path.pop(0)
BASE, R1, DIAGNOSTIC = PACKAGE.BASE, PACKAGE.R1, PACKAGE.DIAGNOSTIC


def json_bytes(value):
    return BASE.encoded_json(value)


def zip_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return stream.getvalue()


def revision(entries, number):
    result = copy.deepcopy(entries)
    manifest = json.loads(result['manifest.json'])
    for part in (manifest['header'], *manifest['modules']):
        part['version'] = [2, 13, number]
    result['manifest.json'] = json_bytes(manifest)
    return result


class Fixture:
    def __init__(self, root):
        self.root, self.server, self.mappings = root, root/'server', root/'mappings'
        self.server.mkdir();self.mappings.mkdir()
        self.native_zip, self.r1_zip = root/'native51.zip', root/'r1.zip'
        self.diagnostic_pack = root/'diagnostic.mcpack'
        self.diagnostic_notes, self.cleanup_notes = root/'diagnostic.md', root/'cleanup.md'
        self.diagnostic_notes.write_text('Temporary ordinary-elytra badges; packaging is not live approval.\n')
        self.cleanup_notes.write_text('Restore the exact normal r3 pack with native shield fallback.\n')
        self.diagnostic_output, self.cleanup_output, self.pair_output = root/'output/d2.zip', root/'output/r3.zip', root/'output/PAIR.json'
        resources = ROOT/'bedrock/resource_pack'
        # Use shipped source assets, so tests need no built/dist artifacts. The
        # diagnostic helper's marker graph and helmet control get real inputs.
        r1_files = {p.relative_to(resources).as_posix():p.read_bytes() for p in resources.rglob('*') if p.is_file()}
        r1_files.pop('attachables/elytra.json', None)  # Rejected source-only legacy override.
        for name in PACKAGE.SHIELD_ATTACHABLES:
            r1_files[name] = b'{"fixture":"old detached shield renderer"}'
        self.r1_files = revision(r1_files, 51001)
        self.native_files = revision(self.r1_files, 51000)
        attachable = json.loads(self.native_files[R1.ATTACHABLE])
        scripts = attachable['minecraft:attachable']['description']['scripts']
        scripts['pre_animation'] = [expression.replace(R1.OWNER_SLOT_QUERY, R1.OLD_SLOT_QUERY)
                                    for expression in scripts['pre_animation']]
        self.native_files[R1.ATTACHABLE] = json_bytes(attachable)
        self.normal_files = revision(self.r1_files, 51003)
        for name in PACKAGE.SHIELD_ATTACHABLES:
            del self.normal_files[name]
        self.artifacts = {
            'fabric/mods/Infinity-Armor-2.13.0-explore.51.jar':b'the same native51 application',
            'geyser/extensions/InfinityCreativeBridge.jar':b'the same bridge',
            'geyser/Geyser-Standalone.jar':b'the same downloaded native dependency',
        }
        download = 'geyser/Geyser-Standalone.jar'
        self.native_pack = zip_bytes(self.native_files)
        self.r1_pack = zip_bytes(self.r1_files)
        self.native_lock = {
            'version':'2.13.0-explore.51','minecraft':'26.4-snapshot-5',
            'downloads':[{'path':download,'sha256':BASE.digest(self.artifacts[download])}],
            'bundled':[{'path':name,'sha256':BASE.digest(data)} for name,data in self.artifacts.items() if name != download]
                       + [{'path':BASE.PACK_PATH,'sha256':BASE.digest(self.native_pack)}],
            'creative_bridge':{'source_hashes':{'fixture.java':'a'*64}},
        }
        runtime = json_bytes({'java':'25','sha256':'b'*64})
        self.unchanged = {name:BASE.digest(data) for name,data in self.artifacts.items()}
        self.unchanged['runtime.lock.json'] = BASE.digest(runtime)
        self.mapping_hashes, inputs = {}, {}
        for name in BASE.MAPPINGS:
            data = json_bytes({'mapping':name})
            (self.mappings/name).write_bytes(data)
            self.mapping_hashes[name] = inputs['/fixture/'+name] = BASE.digest(data)
        self.native_entries = {
            BASE.LOCK_PATH:json_bytes(self.native_lock), BASE.PACK_PATH:self.native_pack,
            'runtime.lock.json':runtime,'checks/armor-equipment-native.json':json_bytes({'input_sha256':inputs}),
            **{name:data for name,data in self.artifacts.items() if name != download},
        }
        self.native_zip.write_bytes(zip_bytes({BASE.BASELINE_PREFIX+n:data for n,data in self.native_entries.items()}))
        self.native_sha = BASE.file_digest(self.native_zip)
        self.r1_lock_data = PACKAGE.lock_for_pack(self.native_lock, self.r1_pack)
        r1_notes = b'Previous immutable r1 repair notes'
        self.r1_entries = {BASE.LOCK_PATH:self.r1_lock_data, BASE.PACK_PATH:self.r1_pack, R1.NOTES_PATH:r1_notes}
        r1_patch = {
            'format_version':1,'repair':R1.REPAIR_NAME,
            'baseline':{'file':self.native_zip.name,'sha256':self.native_sha,'version':PACKAGE.VERSION},
            'pack_changes':R1.validate_pack(self.native_pack,self.r1_pack,R1.SELECTOR_COUNTS),
            'unchanged_locked_artifacts_sha256':self.unchanged,'unchanged_mapping_sha256':self.mapping_hashes,
            'expected_old_sha256':{BASE.LOCK_PATH:BASE.digest(self.native_entries[BASE.LOCK_PATH]),BASE.PACK_PATH:BASE.digest(self.native_pack)},
            'expected_new_sha256':{BASE.LOCK_PATH:BASE.digest(self.r1_lock_data),BASE.PACK_PATH:BASE.digest(self.r1_pack)},
            'payload_sha256':{name:BASE.digest(data) for name,data in self.r1_entries.items()},
            'bundle_files':sorted([*self.r1_entries,'PATCH.json']),
            'device_rendering_verified':False,'live_installation_performed':False,
        }
        self.r1_entries['PATCH.json'] = json_bytes(r1_patch)
        self.write_r1()
        for name,data in {**self.artifacts,'runtime.lock.json':runtime}.items():
            target=self.server/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        self.write_normal()
        self.diagnostic_files = DIAGNOSTIC.apply(self.normal_files)
        self.write_diagnostic()

    def write_r1(self):
        self.r1_zip.write_bytes(zip_bytes({R1.REPAIR_PREFIX+n:data for n,data in self.r1_entries.items()}))
        self.r1_sha=BASE.file_digest(self.r1_zip)

    def write_normal(self):
        self.normal_pack=zip_bytes(self.normal_files)
        target=self.server/BASE.PACK_PATH;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(self.normal_pack)
        self.normal_lock_data=PACKAGE.lock_for_pack(BASE.json_object(self.r1_lock_data),self.normal_pack)
        (self.server/BASE.LOCK_PATH).write_bytes(self.normal_lock_data)

    def write_diagnostic(self):
        self.diagnostic_data=zip_bytes(self.diagnostic_files)
        self.diagnostic_pack.write_bytes(self.diagnostic_data)

    def build(self, **kwargs):
        return PACKAGE.build_pair(self.r1_zip,self.native_zip,self.server,self.mappings,self.diagnostic_pack,
            self.diagnostic_notes,self.cleanup_notes,self.diagnostic_output,self.cleanup_output,self.pair_output,
            acknowledge_temporary_diagnostic=kwargs.pop('acknowledge_temporary_diagnostic',True),
            expected_r1_sha256=kwargs.pop('expected_r1_sha256',self.r1_sha),
            expected_native_sha256=kwargs.pop('expected_native_sha256',self.native_sha),**kwargs)


class WingDiagnosticPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.f=Fixture(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def assert_refused(self, **kwargs):
        with self.assertRaises(ValueError):self.f.build(**kwargs)
        self.assertFalse(self.f.diagnostic_output.exists())
        self.assertFalse(self.f.cleanup_output.exists())
        self.assertFalse(self.f.pair_output.exists())

    def test_pair_binds_both_four_file_candidates_and_exact_normal_cleanup_bytes(self):
        f=self.f;originals=[f.r1_zip.read_bytes(),f.native_zip.read_bytes(),f.diagnostic_pack.read_bytes()]
        pair=f.build()
        diagnostic=BASE.safe_zip(f.diagnostic_output.read_bytes());cleanup=BASE.safe_zip(f.cleanup_output.read_bytes())
        self.assertEqual(set(diagnostic),{PACKAGE.DIAGNOSTIC_PREFIX+n for n in (BASE.PACK_PATH,BASE.LOCK_PATH,PACKAGE.DIAGNOSTIC_NOTES,'PATCH.json')})
        self.assertEqual(set(cleanup),{PACKAGE.CLEANUP_PREFIX+n for n in (BASE.PACK_PATH,BASE.LOCK_PATH,PACKAGE.CLEANUP_NOTES,'PATCH.json')})
        dp=BASE.json_object(diagnostic[PACKAGE.DIAGNOSTIC_PREFIX+'PATCH.json'])
        cp=BASE.json_object(cleanup[PACKAGE.CLEANUP_PREFIX+'PATCH.json'])
        PACKAGE.validate_diagnostic_metadata(dp)
        self.assertEqual(cleanup[PACKAGE.CLEANUP_PREFIX+BASE.PACK_PATH],f.normal_pack)
        self.assertEqual(cleanup[PACKAGE.CLEANUP_PREFIX+BASE.LOCK_PATH],f.normal_lock_data)
        self.assertEqual(cp['expected_old_sha256'],dp['expected_new_sha256'])
        self.assertEqual(set(dp['pack_changes']['deleted_entries']),set(PACKAGE.SHIELD_ATTACHABLES))
        self.assertEqual(set(cp['pack_changes']['deleted_entries']),f.diagnostic_files.keys()-f.normal_files.keys())
        self.assertEqual(dp['pack_changes']['changed_entries']['manifest.json']['old_sha256'],BASE.digest(f.r1_files['manifest.json']))
        self.assertEqual(cp['pack_changes']['changed_entries']['manifest.json']['old_sha256'],BASE.digest(f.diagnostic_files['manifest.json']))
        self.assertEqual(cp['baseline']['sha256'],BASE.file_digest(f.diagnostic_output))
        self.assertEqual(dp['cleanup_target']['pack_sha256'],cp['expected_new_sha256'][BASE.PACK_PATH])
        self.assertEqual(dp['cleanup_target']['resource_revision'],[2,13,51003])
        self.assertEqual(pair['diagnostic']['sha256'],BASE.file_digest(f.diagnostic_output))
        self.assertEqual(pair['cleanup']['sha256'],BASE.file_digest(f.cleanup_output))
        self.assertEqual(pair,BASE.json_object(f.pair_output.read_bytes()))
        self.assertTrue(pair['cleanup_restores_exact_prepared_normal_pack'])
        for patch in (dp,cp,pair):
            self.assertFalse(patch['device_rendering_verified']);self.assertFalse(patch['live_installation_performed'])
            self.assertFalse(patch['live_installation_approval_received']);self.assertTrue(patch['owner_approval_required'])
        self.assertNotIn(str(f.root),json.dumps(pair))
        self.assertEqual(originals,[f.r1_zip.read_bytes(),f.native_zip.read_bytes(),f.diagnostic_pack.read_bytes()])

    def test_default_production_pins_and_wrong_fixture_hashes_are_rejected(self):
        f=self.f
        with self.assertRaises(ValueError):
            PACKAGE.build_pair(f.r1_zip,f.native_zip,f.server,f.mappings,f.diagnostic_pack,f.diagnostic_notes,f.cleanup_notes,
                f.diagnostic_output,f.cleanup_output,f.pair_output,acknowledge_temporary_diagnostic=True)
        self.assert_refused(expected_r1_sha256='0'*64)
        self.assert_refused(expected_native_sha256='0'*64)

    def test_packaging_acknowledgment_is_explicit_and_does_not_accept_integer_truthiness(self):
        for value in (False,1,'yes',None):
            with self.subTest(value=value):self.assert_refused(acknowledge_temporary_diagnostic=value)

    def test_unacknowledged_or_approved_diagnostic_metadata_is_rejected(self):
        valid={'role':'temporary_diagnostic','diagnostic':copy.deepcopy(PACKAGE.ACKNOWLEDGMENT),
               'device_rendering_verified':False,'live_installation_performed':False,
               'owner_approval_required':True,'live_installation_approval_received':False}
        for key,value in (('temporary',False),('ordinary_elytra_badges',False),('owner_approval_required',False),
                          ('live_installation_approval_received',True),('temporary',1)):
            candidate=copy.deepcopy(valid);candidate['diagnostic'][key]=value
            with self.subTest(key=key,value=value),self.assertRaises(ValueError):PACKAGE.validate_diagnostic_metadata(candidate)
        for key in ('diagnostic','role'):
            candidate=copy.deepcopy(valid);del candidate[key]
            with self.subTest(missing=key),self.assertRaises(ValueError):PACKAGE.validate_diagnostic_metadata(candidate)
        for key,value in (('owner_approval_required',False),('live_installation_approval_received',True)):
            candidate=copy.deepcopy(valid);candidate[key]=value
            with self.subTest(top_level=key),self.assertRaises(ValueError):PACKAGE.validate_diagnostic_metadata(candidate)

    def test_normal_cleanup_removes_exactly_two_shield_attachables_and_only_bumps_manifest(self):
        f=self.f
        for kind in ('one_shield','other_deleted','artwork','extra'):
            candidate=copy.deepcopy(f.normal_files)
            if kind=='one_shield':
                name=next(iter(PACKAGE.SHIELD_ATTACHABLES));candidate[name]=f.r1_files[name]
            elif kind=='other_deleted':candidate.pop(R1.ATTACHABLE)
            elif kind=='artwork':candidate[next(n for n in candidate if n.endswith('.png'))]+=b'changed'
            else:candidate['textures/unapproved.bin']=b'changed'
            with self.subTest(kind=kind),self.assertRaises(ValueError):PACKAGE.validate_normal_pack(f.r1_pack,zip_bytes(candidate))

    def test_cleanup_phase_revision_cannot_reuse_r1_or_diagnostic_revision(self):
        for number in (51001,51002,51004):
            with self.subTest(revision=number),self.assertRaises(ValueError):
                PACKAGE.validate_normal_pack(self.f.r1_pack,zip_bytes(revision(self.f.normal_files,number)))

    def test_diagnostic_missing_marker_or_unrelated_asset_mutation_is_rejected(self):
        f=self.f;original=copy.deepcopy(f.diagnostic_files)
        added=sorted(original.keys()-f.normal_files.keys());self.assertTrue(added)
        for kind in ('missing_marker','extra','artwork'):
            f.diagnostic_files=copy.deepcopy(original)
            if kind=='missing_marker':f.diagnostic_files.pop(added[0])
            elif kind=='extra':f.diagnostic_files['textures/unapproved.bin']=b'changed'
            else:f.diagnostic_files[next(n for n in f.normal_files if n.endswith('.png'))]+=b'changed'
            f.write_diagnostic()
            with self.subTest(kind=kind):self.assert_refused()

    def test_wrong_diagnostic_header_or_module_phase_revision_is_rejected(self):
        f=self.f
        for target in ('header','module'):
            candidate=copy.deepcopy(f.diagnostic_files);manifest=json.loads(candidate['manifest.json'])
            (manifest['header'] if target=='header' else manifest['modules'][0])['version']=[2,13,51003]
            candidate['manifest.json']=json_bytes(manifest)
            f.diagnostic_pack.write_bytes(zip_bytes(candidate))
            with self.subTest(target=target):self.assert_refused()

    def test_cleanup_requires_exact_pack_bytes_even_when_reencoding_has_identical_entries(self):
        f=self.f
        stream=io.BytesIO(f.normal_pack)
        with zipfile.ZipFile(stream,'a') as archive:archive.comment=b'different encoding, identical resource members'
        other=stream.getvalue()
        self.assertEqual(BASE.safe_zip(other),BASE.safe_zip(f.normal_pack))
        self.assertNotEqual(other,f.normal_pack)
        with self.assertRaises(ValueError):
            PACKAGE.validate_cleanup_pack(f.diagnostic_data,other,BASE.digest(f.normal_pack))

    def test_diagnostic_resources_cannot_leak_into_the_normal_cleanup(self):
        f=self.f
        marker=next(n for n in f.diagnostic_files if n not in f.normal_files)
        f.normal_files[marker]=f.diagnostic_files[marker];f.write_normal()
        self.assert_refused()

    def test_native_mod_bridge_download_runtime_and_mapping_cannot_change(self):
        f=self.f
        for target in [*(f.server/n for n in f.artifacts),f.server/'runtime.lock.json',f.mappings/BASE.MAPPINGS[0]]:
            original=target.read_bytes();target.write_bytes(b'unapproved replacement')
            with self.subTest(name=target.name):self.assert_refused()
            target.write_bytes(original)

    def test_normal_lock_cannot_change_game_version_mod_identity_or_bridge_metadata(self):
        f=self.f;original=BASE.json_object(f.normal_lock_data)
        for kind in ('release','minecraft','mod','bridge_source'):
            lock=copy.deepcopy(original)
            if kind=='release':lock['version']='2.13.0-explore.52'
            elif kind=='minecraft':lock['minecraft']='26.3'
            elif kind=='mod':lock['bundled'][0]['path']='fabric/mods/replaced.jar'
            else:lock['creative_bridge']['source_hashes']['fixture.java']='0'*64
            (f.server/BASE.LOCK_PATH).write_bytes(json_bytes(lock))
            with self.subTest(kind=kind):self.assert_refused()

    def test_r1_receipt_hash_or_extra_payload_cannot_be_forged_with_a_new_fixture_pin(self):
        f=self.f;original=copy.deepcopy(f.r1_entries)
        for kind in ('receipt','extra_payload'):
            f.r1_entries=copy.deepcopy(original)
            if kind=='receipt':
                patch=BASE.json_object(f.r1_entries['PATCH.json']);patch['expected_new_sha256'][BASE.PACK_PATH]='0'*64
                f.r1_entries['PATCH.json']=json_bytes(patch)
            else:f.r1_entries['extra.txt']=b'unreviewed'
            f.write_r1()
            with self.subTest(kind=kind):self.assert_refused()

    def test_unsafe_private_traversal_duplicate_and_symlink_zip_members_are_rejected(self):
        f=self.f
        for name in ('settings.json','fabric/config/infinity-ai.json','world/level.dat','../escape'):
            f.r1_entries[R1.REPAIR_PREFIX+name]=b'private'
            data=zip_bytes({R1.REPAIR_PREFIX+name:b'private'})
            f.r1_zip.write_bytes(data);f.r1_sha=BASE.digest(data)
            with self.subTest(name=name):self.assert_refused()
        stream=io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(stream,'w') as archive:
                archive.writestr(R1.REPAIR_PREFIX+'same',b'a');archive.writestr(R1.REPAIR_PREFIX+'same',b'b')
        f.r1_zip.write_bytes(stream.getvalue());f.r1_sha=BASE.digest(stream.getvalue());self.assert_refused()
        stream=io.BytesIO();entry=zipfile.ZipInfo(R1.REPAIR_PREFIX+'linked');entry.external_attr=(stat.S_IFLNK|0o777)<<16
        with zipfile.ZipFile(stream,'w') as archive:archive.writestr(entry,b'outside')
        f.r1_zip.write_bytes(stream.getvalue());f.r1_sha=BASE.digest(stream.getvalue());self.assert_refused()

    def test_both_notes_required_and_linked_inputs_refused(self):
        f=self.f;old=f.cleanup_notes.read_bytes();f.cleanup_notes.write_text('  ');self.assert_refused();f.cleanup_notes.write_bytes(old)
        outside=f.root/'outside.mcpack';outside.write_bytes(f.diagnostic_data)
        f.diagnostic_pack.unlink();f.diagnostic_pack.symlink_to(outside);self.assert_refused()

    def test_existing_cleanup_output_refuses_pair_before_any_diagnostic_file_is_written(self):
        f=self.f;f.cleanup_output.parent.mkdir();f.cleanup_output.write_bytes(b'existing cleanup')
        with self.assertRaises(ValueError):f.build()
        self.assertFalse(f.diagnostic_output.exists());self.assertFalse(f.pair_output.exists())
        self.assertEqual(f.cleanup_output.read_bytes(),b'existing cleanup')

    def test_output_cannot_replace_an_immutable_input_or_enter_live_server(self):
        f=self.f;before=f.r1_zip.read_bytes();f.diagnostic_output=f.r1_zip
        with self.assertRaises(ValueError):f.build()
        self.assertEqual(f.r1_zip.read_bytes(),before)
        f.diagnostic_output=PACKAGE.LIVE_ROOT/'never-create-diagnostic-test.zip'
        with self.assertRaises(ValueError):f.build()
        self.assertFalse(f.diagnostic_output.exists())

    def test_two_candidate_outputs_cannot_share_a_path(self):
        self.f.cleanup_output=self.f.diagnostic_output
        self.assert_refused()


if __name__=='__main__':
    unittest.main()
