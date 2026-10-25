#!/usr/bin/env python3
"""Package a temporary wing probe and its exact normal shield-fallback cleanup.

Both ZIPs are resource-only candidates against immutable explore.51/r1 inputs.
The diagnostic intentionally marks ordinary elytra too. A packaging acknowledgment
records that scope; it grants no live installation approval. Neither package can
establish iPad rendering, change the native game stack, or modify a live world.
"""
import argparse
import copy
import io
import json
import os
from pathlib import Path, PurePosixPath
import stat
import zipfile

import package_armor_visibility_repair as BASE
import package_wing_appearance_repair as R1
import wing_render_diagnostic as DIAGNOSTIC


ROOT = Path(__file__).resolve().parents[1]
LIVE_ROOT = Path.home()/'Downloads'/'Infinity Armor Server 2.9.0'
VERSION = '2.13.0-explore.51'
NATIVE_SHA256 = '9ea69bb0114dc6bdbf236790c1105190ec0fb4ab350eee0eeb0b4c39c2865eab'
R1_SHA256 = '87e0efba1f37a6f1159d72879647b85d1bdf6c07fa0263790e7609ea15cf9641'
R1_REVISION = (2, 13, 51001)
DIAGNOSTIC_REVISION = (2, 13, 51002)
CLEANUP_REVISION = (2, 13, 51003)
DIAGNOSTIC_NAME = 'wing-render-diagnostic-explore51-r2'
CLEANUP_NAME = 'shield-grip-cleanup-explore51-r3'
DIAGNOSTIC_PREFIX = 'Infinity_Armor_Wing_Render_Diagnostic_explore51-r2/'
CLEANUP_PREFIX = 'Infinity_Armor_Shield_Grip_Cleanup_explore51-r3/'
DIAGNOSTIC_NOTES = 'WING_RENDER_DIAGNOSTIC.md'
CLEANUP_NOTES = 'SHIELD_GRIP_CLEANUP.md'
SHIELD_ATTACHABLES = frozenset(('attachables/held/shield.json', 'attachables/held/catalog_shield.json'))
ACKNOWLEDGMENT = {
    'temporary': True, 'ordinary_elytra_badges': True, 'owner_approval_required': True,
    'live_installation_approval_received': False,
}


def require(value, message):
    if not value:
        raise ValueError(message)


def read_zip(path, expected_sha256, prefix, maximum_entries=512):
    data = BASE.bounded_file(path, BASE.MAX_ARCHIVE)
    require(isinstance(expected_sha256, str) and BASE.SHA256.fullmatch(expected_sha256)
            and BASE.digest(data) == expected_sha256, 'Input ZIP is not its exact immutable baseline')
    entries = BASE.safe_zip(data, maximum_entries=maximum_entries)
    require(all(name.startswith(prefix) and name != prefix for name in entries), 'Unexpected baseline ZIP root')
    return {name[len(prefix):]: value for name, value in entries.items()}


def pack_bytes(entries, prefix=''):
    """Bounded caller-validated entries; stable encoding for package reproducibility."""
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            BASE.safe_name(name)
            info = zipfile.ZipInfo(prefix+name, (2026, 10, 25, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, data)
    result = stream.getvalue()
    BASE.safe_zip(result)
    return result


def validate_normal_pack(r1_data, normal_data):
    """The production cleanup removes only the two detached shield models."""
    old, new = BASE.safe_zip(r1_data), BASE.safe_zip(normal_data)
    require(SHIELD_ATTACHABLES <= old.keys() and old.keys()-new.keys() == SHIELD_ATTACHABLES
            and not new.keys()-old.keys(), 'Normal shield fallback must remove exactly its two custom attachables')
    changed = {name for name in new if new[name] != old[name]}
    require(changed == {'manifest.json'}, 'Normal shield cleanup changed artwork or another resource')
    old_manifest, new_manifest = BASE.json_object(old['manifest.json']), BASE.json_object(new['manifest.json'])
    try:
        versions = [old_manifest['header']['version'], *(v['version'] for v in old_manifest['modules'])]
        require(old_manifest['modules'] and all(v == list(R1_REVISION) for v in versions), 'Normal cleanup requires the r1 cache baseline')
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError('Malformed normal cleanup manifest') from error
    BASE.validate_manifest(old_manifest, new_manifest, CLEANUP_REVISION)
    return {
        'changed_entries': {'manifest.json': {'old_sha256':BASE.digest(old['manifest.json']),
                                             'new_sha256':BASE.digest(new['manifest.json'])}},
        'added_entries': [], 'deleted_entries': sorted(SHIELD_ATTACHABLES),
        'unchanged_entry_count': len(new)-1, 'version': list(CLEANUP_REVISION),
        'native_shield_fallback': True, 'device_rendering_verified': False,
    }


def pack_delta(old_data, new_data, revision):
    old, new = BASE.safe_zip(old_data), BASE.safe_zip(new_data)
    changed = {name for name in old.keys() & new.keys() if old[name] != new[name]}
    return {
        'changed_entries': {name:{'old_sha256':BASE.digest(old[name]),'new_sha256':BASE.digest(new[name])}
                            for name in sorted(changed)},
        'added_entries': sorted(new.keys()-old.keys()),
        'deleted_entries': sorted(old.keys()-new.keys()),
        'unchanged_entry_count': sum(old[name] == new[name] for name in old.keys() & new.keys()),
        'version': list(revision),
    }


def validate_cleanup_pack(diagnostic_data, normal_data, expected_normal_sha256):
    require(isinstance(expected_normal_sha256, str) and BASE.SHA256.fullmatch(expected_normal_sha256)
            and BASE.digest(normal_data) == expected_normal_sha256,
            'Cleanup must restore the exact pre-reviewed normal pack bytes')
    normal, diagnostic = BASE.safe_zip(normal_data), BASE.safe_zip(diagnostic_data)
    validate_revision(normal, CLEANUP_REVISION)
    validate_revision(diagnostic, DIAGNOSTIC_REVISION)
    overlay = DIAGNOSTIC.validate_overlay(normal, diagnostic)
    report = pack_delta(diagnostic_data, normal_data, CLEANUP_REVISION)
    report.update(normal_pack_sha256=expected_normal_sha256,
                  diagnostic_overlay_fully_removed=True, native_shield_fallback=True,
                  validated_diagnostic_overlay=overlay, device_rendering_verified=False)
    return report


def validate_revision(entries, expected):
    try:
        manifest = BASE.json_object(entries['manifest.json'])
        versions = [manifest['header']['version'], *(module['version'] for module in manifest['modules'])]
        require(manifest['modules'] and all(isinstance(v, list) and len(v) == 3
                and all(type(n) is int for n in v) and tuple(v) == tuple(expected) for v in versions),
                'Pack header and every module must use the exact phase revision')
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError('Malformed phase pack manifest') from error


def validate_diagnostic_metadata(patch):
    require(isinstance(patch, dict) and patch.get('role') == 'temporary_diagnostic'
            and patch.get('diagnostic') == ACKNOWLEDGMENT
            and all(type(patch['diagnostic'].get(k)) is bool for k in ACKNOWLEDGMENT)
            and patch.get('owner_approval_required') is True
            and patch.get('live_installation_approval_received') is False
            and patch.get('device_rendering_verified') is False
            and patch.get('live_installation_performed') is False,
            'Temporary ordinary-elytra diagnostic scope and unapproved live status must be explicit')


def lock_for_pack(lock, data):
    result = copy.deepcopy(lock)
    matches = 0
    for group in ('downloads', 'bundled'):
        for entry in result[group]:
            if entry['path'] == BASE.PACK_PATH:
                entry['sha256'] = BASE.digest(data)
                matches += 1
    require(matches == 1, 'Pack dependency must occur exactly once')
    return BASE.encoded_json(result)


def native_provenance(native, server_root, mapping_root):
    try:
        old_lock = BASE.json_object(native[BASE.LOCK_PATH])
        runtime = native['runtime.lock.json']
        proof = BASE.json_object(native['checks/armor-equipment-native.json'])
    except KeyError as error:
        raise ValueError('Original51 package lacks native/runtime/mapping provenance') from error
    require(old_lock.get('version') == VERSION and old_lock.get('minecraft') == '26.4-snapshot-5', 'Wrong original native51 release')
    entries = BASE.lock_entries(old_lock)
    for entry in old_lock['bundled']:
        require(entry['path'] in native, 'Original51 ZIP lacks a bundled dependency')
    unchanged = {}
    for name, checksum in entries.items():
        if name in native:
            require(BASE.digest(native[name]) == checksum, 'Original51 dependency bytes disagree with its lock')
        if name != BASE.PACK_PATH:
            require(BASE.file_digest(BASE.contained_file(server_root, name)) == checksum, 'Prepared native dependency changed: '+name)
            unchanged[name] = checksum
    require(BASE.bounded_file(BASE.contained_file(server_root, 'runtime.lock.json'), BASE.MAX_JSON) == runtime,
            'Prepared Java runtime changed')
    unchanged['runtime.lock.json'] = BASE.digest(runtime)
    inputs, mappings = proof.get('input_sha256'), {}
    require(isinstance(inputs, dict), 'Original51 mapping hashes missing')
    for name in BASE.MAPPINGS:
        matches = [value for path, value in inputs.items() if PurePosixPath(path).name == name]
        require(len(matches) == 1 and isinstance(matches[0], str) and BASE.SHA256.fullmatch(matches[0]),
                'Missing/ambiguous original51 mapping hash')
        actual = BASE.digest(BASE.bounded_file(BASE.contained_file(mapping_root, name), BASE.MAX_JSON))
        require(actual == matches[0], 'Prepared mapping differs from original51: '+name)
        mappings[name] = actual
    return old_lock, unchanged, mappings


def repair_patch(name, role, baseline, old_lock_data, old_pack, new_lock_data, new_pack,
                 notes_name, notes, pack_report, unchanged, mappings):
    payloads = {BASE.LOCK_PATH:new_lock_data, BASE.PACK_PATH:new_pack, notes_name:notes}
    return {
        'format_version':1, 'repair':name, 'role':role, 'baseline':baseline,
        'version':VERSION,
        'expected_old_sha256': {BASE.LOCK_PATH:BASE.digest(old_lock_data), BASE.PACK_PATH:BASE.digest(old_pack)},
        'expected_new_sha256': {BASE.LOCK_PATH:BASE.digest(new_lock_data), BASE.PACK_PATH:BASE.digest(new_pack)},
        'pack_changes':pack_report, 'unchanged_locked_artifacts_sha256':unchanged,
        'unchanged_mapping_sha256':mappings,
        'payload_sha256':{key:BASE.digest(value) for key,value in sorted(payloads.items())},
        'bundle_files':sorted([*payloads, 'PATCH.json']),
        'provenance': {
            'packager':'tools/package_wing_diagnostic.py', 'packager_sha256':BASE.file_digest(__file__),
            'shared_validator':'tools/package_armor_visibility_repair.py', 'shared_validator_sha256':BASE.file_digest(BASE.__file__),
            'diagnostic_helper':'tools/wing_render_diagnostic.py', 'diagnostic_helper_sha256':BASE.file_digest(DIAGNOSTIC.__file__),
        },
        'device_rendering_verified':False, 'live_installation_performed':False,
        'owner_approval_required':True, 'live_installation_approval_received':False,
    }, payloads


def write_exclusive_outputs(outputs, inputs):
    paths = [Path(path) for path in outputs]
    forbidden = {Path(path).resolve() for path in inputs}
    require(len({p.resolve() for p in paths}) == len(paths), 'Pair output paths must be distinct')
    for path in paths:
        require(path.resolve() not in forbidden and not path.resolve().is_relative_to(LIVE_ROOT),
                'Candidate output cannot replace an input or enter the live server')
        require(not path.exists() and not path.is_symlink(), 'Candidate output already exists')
        # Resolve aliases for the input/live/collision checks above. macOS uses
        # /var -> /private/var for ordinary temporary directories. Exclusive
        # creation below still refuses every existing output or final symlink.
    created = []
    try:
        for path, data in outputs.items():
            path = Path(path);path.parent.mkdir(parents=True, exist_ok=True)
            with path.open('xb') as stream:
                identity = os.fstat(stream.fileno())
                created.append((path, identity.st_dev, identity.st_ino))
                stream.write(data)
    except BaseException:
        for path, dev, ino in created:
            if path.exists() and not path.is_symlink() and (path.stat().st_dev, path.stat().st_ino) == (dev, ino):
                path.unlink()
        raise


def build_pair(r1_zip, native_zip, server_root, mapping_root, diagnostic_pack,
               diagnostic_notes, cleanup_notes, diagnostic_output, cleanup_output, pair_output,
               *, acknowledge_temporary_diagnostic=False,
               expected_r1_sha256=R1_SHA256, expected_native_sha256=NATIVE_SHA256):
    """All scope, provenance and pair checks complete before any output is written.

    Caller-pinned fixture baselines are available to tests. The CLI never changes
    the production51/r1 pins. The acknowledgment is a packaging choice only.
    """
    require(type(acknowledge_temporary_diagnostic) is bool and acknowledge_temporary_diagnostic,
            'Packaging must acknowledge temporary badges on ordinary elytra; this is not live approval')
    native = read_zip(native_zip, expected_native_sha256, BASE.BASELINE_PREFIX)
    r1 = read_zip(r1_zip, expected_r1_sha256, R1.REPAIR_PREFIX, maximum_entries=4)
    require(set(r1) == {BASE.PACK_PATH, BASE.LOCK_PATH, R1.NOTES_PATH, 'PATCH.json'}, 'Unexpected r1 resource package payloads')
    original_lock, unchanged, mappings = native_provenance(native, server_root, mapping_root)
    r1_lock = BASE.json_object(r1[BASE.LOCK_PATH])
    BASE.validate_lock(original_lock, r1_lock, BASE.digest(native[BASE.PACK_PATH]), BASE.digest(r1[BASE.PACK_PATH]))
    require(r1_lock.get('version') == VERSION, 'Wrong r1 release')
    r1_report = R1.validate_pack(native[BASE.PACK_PATH], r1[BASE.PACK_PATH], R1.SELECTOR_COUNTS)
    r1_patch = BASE.json_object(r1['PATCH.json'])
    require(r1_patch.get('format_version') == 1 and r1_patch.get('repair') == R1.REPAIR_NAME
            and r1_patch.get('baseline') == {'file':Path(native_zip).name,'sha256':expected_native_sha256,'version':VERSION}
            and r1_patch.get('pack_changes') == r1_report
            and r1_patch.get('unchanged_locked_artifacts_sha256') == unchanged
            and r1_patch.get('unchanged_mapping_sha256') == mappings,
            'r1 repair does not bind the immutable original51 stack')
    require(r1_patch.get('expected_old_sha256') == {BASE.LOCK_PATH:BASE.digest(native[BASE.LOCK_PATH]), BASE.PACK_PATH:BASE.digest(native[BASE.PACK_PATH])}
            and r1_patch.get('expected_new_sha256') == {BASE.LOCK_PATH:BASE.digest(r1[BASE.LOCK_PATH]), BASE.PACK_PATH:BASE.digest(r1[BASE.PACK_PATH])},
            'r1 lock/pack hashes disagree with its immutable inputs')
    require(r1_patch.get('payload_sha256') == {name:BASE.digest(data) for name,data in r1.items() if name != 'PATCH.json'}
            and r1_patch.get('bundle_files') == sorted(r1)
            and r1_patch.get('device_rendering_verified') is False
            and r1_patch.get('live_installation_performed') is False,
            'r1 receipt payloads or proof boundaries disagree with its immutable package')
    normal_pack = BASE.bounded_file(BASE.contained_file(server_root, BASE.PACK_PATH), BASE.MAX_ARCHIVE)
    normal_lock_data = BASE.bounded_file(BASE.contained_file(server_root, BASE.LOCK_PATH), BASE.MAX_JSON)
    normal_report = validate_normal_pack(r1[BASE.PACK_PATH], normal_pack)
    BASE.validate_lock(r1_lock, BASE.json_object(normal_lock_data), BASE.digest(r1[BASE.PACK_PATH]), BASE.digest(normal_pack))
    diagnostic_data = BASE.bounded_file(diagnostic_pack, BASE.MAX_ARCHIVE)
    validate_revision(BASE.safe_zip(diagnostic_data), DIAGNOSTIC_REVISION)
    overlay = DIAGNOSTIC.validate_overlay(BASE.safe_zip(normal_pack), BASE.safe_zip(diagnostic_data))
    diagnostic_report = pack_delta(r1[BASE.PACK_PATH],diagnostic_data,DIAGNOSTIC_REVISION)
    diagnostic_report.update(validated_normal_r3_overlay=overlay, normal_shield_fallback=normal_report,
                             device_rendering_verified=False)
    cleanup_report = validate_cleanup_pack(diagnostic_data, normal_pack, BASE.digest(normal_pack))
    dnotes, cnotes = BASE.bounded_file(diagnostic_notes, 256*1024), BASE.bounded_file(cleanup_notes, 256*1024)
    require(dnotes.strip() and cnotes.strip(), 'Both diagnostic and cleanup notes must be nonempty')
    diagnostic_lock_data = lock_for_pack(r1_lock, diagnostic_data)
    BASE.validate_lock(r1_lock, BASE.json_object(diagnostic_lock_data), BASE.digest(r1[BASE.PACK_PATH]), BASE.digest(diagnostic_data))
    BASE.validate_lock(BASE.json_object(diagnostic_lock_data), BASE.json_object(normal_lock_data), BASE.digest(diagnostic_data), BASE.digest(normal_pack))
    dpatch, dpayloads = repair_patch(DIAGNOSTIC_NAME, 'temporary_diagnostic',
        {'file':Path(r1_zip).name,'sha256':expected_r1_sha256,'version':VERSION},
        r1[BASE.LOCK_PATH], r1[BASE.PACK_PATH], diagnostic_lock_data, diagnostic_data,
        DIAGNOSTIC_NOTES, dnotes, diagnostic_report, unchanged, mappings)
    dpatch['diagnostic'] = copy.deepcopy(ACKNOWLEDGMENT)
    dpatch['cleanup_target'] = {'pack_sha256':BASE.digest(normal_pack),'lock_sha256':BASE.digest(normal_lock_data),
                                'resource_revision':list(CLEANUP_REVISION),'normal_pack_delta_from_r1':normal_report}
    validate_diagnostic_metadata(dpatch)
    dpayloads['PATCH.json'] = BASE.encoded_json(dpatch)
    dzip = pack_bytes(dpayloads, DIAGNOSTIC_PREFIX)
    cpatch, cpayloads = repair_patch(CLEANUP_NAME, 'diagnostic_cleanup',
        {'file':Path(diagnostic_output).name,'sha256':BASE.digest(dzip),'version':VERSION},
        diagnostic_lock_data, diagnostic_data, normal_lock_data, normal_pack,
        CLEANUP_NOTES, cnotes, cleanup_report, unchanged, mappings)
    cpatch['diagnostic_resources_removed'] = True
    cpatch['restores_exact_normal_pack_sha256'] = BASE.digest(normal_pack)
    cpayloads['PATCH.json'] = BASE.encoded_json(cpatch)
    czip = pack_bytes(cpayloads, CLEANUP_PREFIX)
    pair = {'format_version':1,'version':VERSION,
        'native_baseline':{'file':Path(native_zip).name,'sha256':expected_native_sha256},
        'installed_r1_baseline':{'file':Path(r1_zip).name,'sha256':expected_r1_sha256},
        'diagnostic':{'file':Path(diagnostic_output).name,'sha256':BASE.digest(dzip),'resource_revision':list(DIAGNOSTIC_REVISION),
                      'pack_sha256':BASE.digest(diagnostic_data),'lock_sha256':BASE.digest(diagnostic_lock_data)},
        'cleanup':{'file':Path(cleanup_output).name,'sha256':BASE.digest(czip),'resource_revision':list(CLEANUP_REVISION),
                   'pack_sha256':BASE.digest(normal_pack),'lock_sha256':BASE.digest(normal_lock_data)},
        'diagnostic_scope':copy.deepcopy(ACKNOWLEDGMENT),
        'cleanup_restores_exact_prepared_normal_pack':True,
        'unchanged_locked_artifacts_sha256':unchanged,'unchanged_mapping_sha256':mappings,
        'device_rendering_verified':False,'live_installation_performed':False,
        'owner_approval_required':True,'live_installation_approval_received':False}
    require(Path(diagnostic_output).suffix.lower() == Path(cleanup_output).suffix.lower() == '.zip'
            and Path(pair_output).suffix.lower() == '.json', 'Pair requires two ZIP outputs and a separate JSON receipt')
    require(len({Path(path).resolve() for path in (diagnostic_output,cleanup_output,pair_output)}) == 3,
            'Diagnostic, cleanup and pair receipt outputs must be distinct')
    outputs = {Path(diagnostic_output):dzip, Path(cleanup_output):czip, Path(pair_output):BASE.encoded_json(pair)}
    write_exclusive_outputs(outputs, [r1_zip,native_zip,diagnostic_pack,diagnostic_notes,cleanup_notes,
                                     Path(server_root)/BASE.PACK_PATH,Path(server_root)/BASE.LOCK_PATH])
    return pair


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--r1-baseline',type=Path,default=ROOT/'dist/wing-appearance-explore51-r1/Infinity_Armor_Wing_Appearance_Repair_explore51-r1.zip')
    parser.add_argument('--native-baseline',type=Path,default=ROOT/'dist/lobbies/Infinity_Armor_Lobbies_Server_v2.13.0-explore.51.zip')
    parser.add_argument('--server',type=Path,default=ROOT/'server')
    parser.add_argument('--mappings',type=Path,default=ROOT/'research/integration/crossplay-export')
    parser.add_argument('--diagnostic-pack',type=Path,required=True)
    parser.add_argument('--diagnostic-notes',type=Path,default=ROOT/'server'/DIAGNOSTIC_NOTES)
    parser.add_argument('--cleanup-notes',type=Path,default=ROOT/'server'/CLEANUP_NOTES)
    parser.add_argument('--diagnostic-output',type=Path,default=ROOT/'dist/wing-render-diagnostic-explore51-r2/Infinity_Armor_Wing_Render_Diagnostic_explore51-r2.zip')
    parser.add_argument('--cleanup-output',type=Path,default=ROOT/'dist/shield-grip-cleanup-explore51-r3/Infinity_Armor_Shield_Grip_Cleanup_explore51-r3.zip')
    parser.add_argument('--pair-output',type=Path,default=ROOT/'dist/wing-render-diagnostic-explore51-r2/PAIR.json')
    parser.add_argument('--acknowledge-temporary-diagnostic',action='store_true',
                        help='Record temporary ordinary-elytra badges as a packaging choice; grants no live approval')
    args = parser.parse_args()
    try:
        pair = build_pair(args.r1_baseline,args.native_baseline,args.server,args.mappings,args.diagnostic_pack,
            args.diagnostic_notes,args.cleanup_notes,args.diagnostic_output,args.cleanup_output,args.pair_output,
            acknowledge_temporary_diagnostic=args.acknowledge_temporary_diagnostic)
    except (ValueError,OSError) as error:
        parser.exit(1,'Diagnostic pair packaging refused: '+str(error)+'\n')
    print(json.dumps(pair,indent=2))


if __name__ == '__main__':
    main()
