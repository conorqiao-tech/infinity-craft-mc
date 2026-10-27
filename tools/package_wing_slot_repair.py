#!/usr/bin/env python3
"""Package the narrowly scoped explore.51 r4 torso-slot appearance candidate.

The three immutable archives establish native51 -> r1 -> normal r3 provenance.
Only the pack cache revision and two positive equipment-slot predicates change.
The torso slot value remains an inference until a device comparison: this tool
does not execute Molang, install on a server, or grant live installation approval.
"""
import argparse
import copy
import json
from pathlib import Path

import package_armor_visibility_repair as BASE
import package_wing_appearance_repair as R1
import package_wing_diagnostic as PAIR


ROOT = Path(__file__).resolve().parents[1]
VERSION = '2.13.0-explore.51'
NATIVE_SHA256 = '9ea69bb0114dc6bdbf236790c1105190ec0fb4ab350eee0eeb0b4c39c2865eab'
R1_SHA256 = '87e0efba1f37a6f1159d72879647b85d1bdf6c07fa0263790e7609ea15cf9641'
R3_SHA256 = 'b32debbdc7ed336c6bd0bc86ac30ae298d09cec7635b1eb702fe81f5fdb41417'
REPAIR_VERSION = (2, 13, 51004)
REPAIR_NAME = 'wing-slot-repair-explore51-r4'
REPAIR_PREFIX = 'Infinity_Armor_Wing_Slot_Repair_explore51-r4/'
NOTES_PATH = 'WING_SLOT_REPAIR.md'
ATTACHABLE = R1.ATTACHABLE
SELECTOR_COUNTS = {ATTACHABLE: 2}
ALLOWED_PACK_CHANGES = {'manifest.json', ATTACHABLE}
OLD_PREDICATE = R1.OWNER_SLOT_QUERY+" == 'body'"
NEW_PREDICATE = "context.item_slot == 'torso'"
FROZEN_HELPER_HASHES = {
    'tools/package_armor_visibility_repair.py':'33d64c891db8ad18f5225b8774877550dbae761841dbe0da652fe60bbbe1453f',
    'tools/package_wing_appearance_repair.py':'268504da2052c220202fe7a5ca1b5759c1b79d296528156715864ad0992521ae',
    'tools/package_wing_diagnostic.py':'65fff22949c3e5b0a4cfc0b199450b2302b59a6f70d96a5e3f4fc39c90e3ebeb',
    'tools/wing_render_diagnostic.py':'93892119ddac366157b4b94685ed3d488eb3bb2821e1d937d26b055da4af1e62',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def same_json(first, second):
    encode = lambda value: json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False)
    return encode(first) == encode(second)


def frozen_helpers():
    for name, expected in FROZEN_HELPER_HASHES.items():
        require(BASE.file_digest(BASE.contained_file(ROOT,name)) == expected, 'A frozen historical resource validator changed')
    require(Path(BASE.__file__).resolve() == ROOT/'tools/package_armor_visibility_repair.py'
            and Path(R1.__file__).resolve() == ROOT/'tools/package_wing_appearance_repair.py'
            and Path(PAIR.__file__).resolve() == ROOT/'tools/package_wing_diagnostic.py'
            and Path(PAIR.DIAGNOSTIC.__file__).resolve() == ROOT/'tools/wing_render_diagnostic.py',
            'Historical validator imported from a different source root')


def validate_attachable(old, new):
    expected = copy.deepcopy(old)
    try:
        scripts = expected['minecraft:attachable']['description']['scripts']
        historical = [R1._baseline_selector(variable,name).replace(R1.OLD_SLOT_QUERY,R1.OWNER_SLOT_QUERY)
                      for variable,name in R1.SELECTORS]
        require(scripts['pre_animation'] == historical and all(value.count(OLD_PREDICATE) == 1 for value in historical),
                'Baseline must contain exactly both historical r3 owner-bone selectors')
        scripts['pre_animation'] = [value.replace(OLD_PREDICATE,NEW_PREDICATE) for value in historical]
    except (KeyError,TypeError,AttributeError) as error:
        raise ValueError('Malformed winged-chest attachable') from error
    require(same_json(expected,new), 'Only the two positive torso-slot predicates may change')
    return 2


def validate_pack(old_data, new_data, expected_counts=None):
    old, new = BASE.safe_zip(old_data), BASE.safe_zip(new_data)
    require(old.keys() == new.keys(), 'Slot repair cannot add or delete any pack entry')
    changed = {name for name in old if old[name] != new[name]}
    require(changed == ALLOWED_PACK_CHANGES, 'Slot repair changes exactly the manifest and combined attachable')
    PAIR.validate_revision(old,PAIR.CLEANUP_REVISION)
    old_manifest, new_manifest = BASE.json_object(old['manifest.json']), BASE.json_object(new['manifest.json'])
    BASE.validate_manifest(old_manifest,new_manifest,REPAIR_VERSION)
    normalized = copy.deepcopy(new_manifest)
    normalized['header']['version'] = old_manifest['header']['version']
    for before,after in zip(old_manifest['modules'],normalized['modules']):
        after['version'] = before['version']
    require(same_json(normalized,old_manifest), 'Manifest metadata and JSON value types must remain unchanged')
    counts = {ATTACHABLE:validate_attachable(BASE.json_object(old[ATTACHABLE]),BASE.json_object(new[ATTACHABLE]))}
    require(counts == SELECTOR_COUNTS and (expected_counts is None or expected_counts == SELECTOR_COUNTS),
            'Both winged-chest selectors must receive the slot predicate repair')
    report = PAIR.pack_delta(old_data,new_data,REPAIR_VERSION)
    report.update(slot_predicate_replacements=counts,positive_slot_predicate=NEW_PREDICATE,
                  slot_value_inferred=True,guarded_owning_actor_properties_preserved=True,
                  native_elytra_fallback_preserved=True,
                  unchanged_png_entries=sum(name.endswith('.png') for name in old),
                  unchanged_geometry_entries=sum(name.endswith('.geo.json') for name in old),
                  runtime_molang_context_verified=False,device_rendering_verified=False)
    return report


def validate_history_receipt(entries, patch, unchanged, mappings, expected_new, notes_name):
    require(isinstance(patch,dict) and patch.get('format_version') == 1
            and patch.get('expected_new_sha256') == expected_new
            and patch.get('payload_sha256') == {name:BASE.digest(data) for name,data in entries.items() if name != 'PATCH.json'}
            and patch.get('bundle_files') == sorted(entries)
            and same_json(patch.get('unchanged_locked_artifacts_sha256'),unchanged)
            and same_json(patch.get('unchanged_mapping_sha256'),mappings)
            and patch.get('device_rendering_verified') is False and patch.get('live_installation_performed') is False
            and entries[notes_name].strip(), 'Historical receipt has different payloads, native pins, mappings or proof boundaries')


def build_repair(native_zip, r1_zip, r3_zip, server_root, mapping_root, notes_path, output,
                 *, expected_native_sha256=NATIVE_SHA256, expected_r1_sha256=R1_SHA256,
                 expected_r3_sha256=R3_SHA256):
    """Caller-pinned tiny baselines are for tests; production CLI pins are immutable."""
    frozen_helpers()
    native = PAIR.read_zip(native_zip,expected_native_sha256,BASE.BASELINE_PREFIX)
    r1 = PAIR.read_zip(r1_zip,expected_r1_sha256,R1.REPAIR_PREFIX,maximum_entries=4)
    r3 = PAIR.read_zip(r3_zip,expected_r3_sha256,PAIR.CLEANUP_PREFIX,maximum_entries=4)
    require(set(r1) == {BASE.PACK_PATH,BASE.LOCK_PATH,R1.NOTES_PATH,'PATCH.json'}
            and set(r3) == {BASE.PACK_PATH,BASE.LOCK_PATH,PAIR.CLEANUP_NOTES,'PATCH.json'},
            'Historical resource ZIPs must contain exactly their four public files')
    original_lock, unchanged, mappings = PAIR.native_provenance(native,server_root,mapping_root)
    r1_lock, r3_lock = BASE.json_object(r1[BASE.LOCK_PATH]), BASE.json_object(r3[BASE.LOCK_PATH])
    BASE.validate_lock(original_lock,r1_lock,BASE.digest(native[BASE.PACK_PATH]),BASE.digest(r1[BASE.PACK_PATH]))
    BASE.validate_lock(r1_lock,r3_lock,BASE.digest(r1[BASE.PACK_PATH]),BASE.digest(r3[BASE.PACK_PATH]))
    r1_report = R1.validate_pack(native[BASE.PACK_PATH],r1[BASE.PACK_PATH],R1.SELECTOR_COUNTS)
    normal_report = PAIR.validate_normal_pack(r1[BASE.PACK_PATH],r3[BASE.PACK_PATH])
    p1,p3 = BASE.json_object(r1['PATCH.json']), BASE.json_object(r3['PATCH.json'])
    old_hashes = {BASE.LOCK_PATH:BASE.digest(native[BASE.LOCK_PATH]),BASE.PACK_PATH:BASE.digest(native[BASE.PACK_PATH])}
    r1_hashes = {BASE.LOCK_PATH:BASE.digest(r1[BASE.LOCK_PATH]),BASE.PACK_PATH:BASE.digest(r1[BASE.PACK_PATH])}
    r3_hashes = {BASE.LOCK_PATH:BASE.digest(r3[BASE.LOCK_PATH]),BASE.PACK_PATH:BASE.digest(r3[BASE.PACK_PATH])}
    validate_history_receipt(r1,p1,unchanged,mappings,r1_hashes,R1.NOTES_PATH)
    validate_history_receipt(r3,p3,unchanged,mappings,r3_hashes,PAIR.CLEANUP_NOTES)
    require(p1.get('repair') == R1.REPAIR_NAME
            and p1.get('baseline') == {'file':Path(native_zip).name,'sha256':expected_native_sha256,'version':VERSION}
            and p1.get('expected_old_sha256') == old_hashes and same_json(p1.get('pack_changes'),r1_report),
            'Historical r1 contract differs from immutable native51')
    require(p1.get('provenance') == {'packager':'tools/package_wing_appearance_repair.py',
                'packager_sha256':FROZEN_HELPER_HASHES['tools/package_wing_appearance_repair.py'],
                'shared_validator':'tools/package_armor_visibility_repair.py',
                'shared_validator_sha256':FROZEN_HELPER_HASHES['tools/package_armor_visibility_repair.py']},
            'Historical r1 validator provenance changed')
    require(p3.get('repair') == PAIR.CLEANUP_NAME and p3.get('role') == 'diagnostic_cleanup'
            and p3.get('version') == VERSION and p3.get('diagnostic_resources_removed') is True
            and p3.get('restores_exact_normal_pack_sha256') == r3_hashes[BASE.PACK_PATH]
            and p3.get('owner_approval_required') is True and p3.get('live_installation_approval_received') is False,
            'Immutable r3 receipt does not identify the normal diagnostic cleanup')
    # The frozen diagnostic transform permits an independent member-level
    # cleanup comparison. Re-encoding this transient pack in memory does not
    # claim to reproduce the historical r2 archive/pack byte encoding.
    diagnostic_members=PAIR.DIAGNOSTIC.apply(BASE.safe_zip(r3[BASE.PACK_PATH]))
    cleanup_report=PAIR.validate_cleanup_pack(PAIR.pack_bytes(diagnostic_members),r3[BASE.PACK_PATH],r3_hashes[BASE.PACK_PATH])
    require(same_json(p3.get('pack_changes'),cleanup_report)
            and p3.get('provenance') == {'packager':'tools/package_wing_diagnostic.py',
                'packager_sha256':FROZEN_HELPER_HASHES['tools/package_wing_diagnostic.py'],
                'shared_validator':'tools/package_armor_visibility_repair.py',
                'shared_validator_sha256':FROZEN_HELPER_HASHES['tools/package_armor_visibility_repair.py'],
                'diagnostic_helper':'tools/wing_render_diagnostic.py',
                'diagnostic_helper_sha256':FROZEN_HELPER_HASHES['tools/wing_render_diagnostic.py']},
            'Historical r3 cleanup member delta or validator provenance changed')
    previous=p3.get('expected_old_sha256');baseline=p3.get('baseline')
    require(isinstance(previous,dict) and set(previous)=={BASE.LOCK_PATH,BASE.PACK_PATH}
            and all(isinstance(value,str) and BASE.SHA256.fullmatch(value) for value in previous.values())
            and isinstance(baseline,dict) and set(baseline)=={'file','sha256','version'} and baseline['version']==VERSION
            and isinstance(baseline['file'],str) and BASE.safe_name(baseline['file'])==Path(baseline['file']).name
            and isinstance(baseline['sha256'],str) and BASE.SHA256.fullmatch(baseline['sha256']),
            'Historical r3 predecessor identity is malformed')
    new_pack = BASE.bounded_file(BASE.contained_file(server_root,BASE.PACK_PATH),BASE.MAX_ARCHIVE)
    new_lock_data = BASE.bounded_file(BASE.contained_file(server_root,BASE.LOCK_PATH),BASE.MAX_JSON)
    new_lock = BASE.json_object(new_lock_data)
    require(new_lock.get('version') == VERSION and new_lock.get('minecraft') == '26.4-snapshot-5',
            'Slot repair cannot change gameplay or Minecraft version')
    BASE.validate_lock(r3_lock,new_lock,r3_hashes[BASE.PACK_PATH],BASE.digest(new_pack))
    normalized_lock=copy.deepcopy(new_lock)
    for group in ('downloads','bundled'):
        for entry in normalized_lock[group]:
            if entry['path']==BASE.PACK_PATH:
                entry['sha256']=r3_hashes[BASE.PACK_PATH]
    require(same_json(normalized_lock,r3_lock), 'Only the resource-pack checksum may change, preserving all JSON types')
    report = validate_pack(r3[BASE.PACK_PATH],new_pack,SELECTOR_COUNTS)
    notes = BASE.bounded_file(notes_path,256*1024)
    require(notes.strip(), 'Slot repair notes cannot be empty')
    payloads = {BASE.LOCK_PATH:new_lock_data,BASE.PACK_PATH:new_pack,NOTES_PATH:notes}
    patch = {'format_version':1,'repair':REPAIR_NAME,'role':'resource_repair','version':VERSION,
        'baseline':{'file':Path(r3_zip).name,'sha256':expected_r3_sha256,'version':VERSION},
        'lineage':{'native51':{'file':Path(native_zip).name,'sha256':expected_native_sha256},
                   'r1':{'file':Path(r1_zip).name,'sha256':expected_r1_sha256},
                   'r3':{'file':Path(r3_zip).name,'sha256':expected_r3_sha256},
                   'native51_to_r1_contract_verified':True,'r1_to_normal_r3_contract':normal_report},
        'expected_old_sha256':r3_hashes,
        'expected_new_sha256':{BASE.LOCK_PATH:BASE.digest(new_lock_data),BASE.PACK_PATH:BASE.digest(new_pack)},
        'pack_changes':report,'unchanged_locked_artifacts_sha256':unchanged,'unchanged_mapping_sha256':mappings,
        'payload_sha256':{name:BASE.digest(data) for name,data in sorted(payloads.items())},
        'bundle_files':sorted([*payloads,'PATCH.json']),
        'provenance':{'packager':'tools/package_wing_slot_repair.py','packager_sha256':BASE.file_digest(__file__),
                      'frozen_historical_helpers_sha256':dict(FROZEN_HELPER_HASHES)},
        'slot_value_inferred':True,'runtime_molang_context_verified':False,'device_rendering_verified':False,
        'live_installation_performed':False,'owner_approval_required':True,'live_installation_approval_received':False}
    payloads['PATCH.json'] = BASE.encoded_json(patch)
    require(Path(output).suffix.lower() == '.zip', 'Slot repair output must be a separate ZIP')
    data = PAIR.pack_bytes(payloads,REPAIR_PREFIX)
    PAIR.write_exclusive_outputs({Path(output):data},[native_zip,r1_zip,r3_zip,notes_path,
                                 Path(server_root)/BASE.PACK_PATH,Path(server_root)/BASE.LOCK_PATH])
    return patch


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-baseline',type=Path,default=ROOT/'dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.51.zip')
    parser.add_argument('--r1-baseline',type=Path,default=ROOT/'dist/wing-appearance-explore51-r1/Infinity_Armor_Wing_Appearance_Repair_explore51-r1.zip')
    parser.add_argument('--r3-baseline',type=Path,default=ROOT/'dist/shield-grip-cleanup-explore51-r3/Infinity_Armor_Shield_Grip_Cleanup_explore51-r3.zip')
    parser.add_argument('--server',type=Path,default=ROOT/'server')
    parser.add_argument('--mappings',type=Path,default=ROOT/'research/integration/crossplay-export')
    parser.add_argument('--notes',type=Path,default=ROOT/'server'/NOTES_PATH)
    parser.add_argument('--output',type=Path,default=ROOT/'dist/wing-slot-repair-explore51-r4/Infinity_Armor_Wing_Slot_Repair_explore51-r4.zip')
    args=parser.parse_args()
    try:
        report=build_repair(args.native_baseline,args.r1_baseline,args.r3_baseline,args.server,args.mappings,args.notes,args.output)
    except (ValueError,OSError) as error:
        parser.exit(1,'Slot repair packaging refused: '+str(error)+'\n')
    print(json.dumps({'output':str(args.output),'sha256':BASE.file_digest(args.output),
        'slot_predicate_replacements':report['pack_changes']['slot_predicate_replacements'],
        'slot_value_inferred':True,'runtime_molang_context_verified':False,'device_rendering_verified':False,
        'live_installation_approval_received':False},indent=2))


if __name__=='__main__':
    main()
