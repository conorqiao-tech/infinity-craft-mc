#!/usr/bin/env python3
"""Package a temporary explore.51 r5 inventory-preview probe and its r6 cleanup.

The immutable owner-approved r4 ZIP is the only resource baseline. The r5 probe
adds owned diagnostic resources whose panels are also visible on ordinary and
held elytra and in the world; it is not a client fix. The r6 cleanup restores
every r4 pack member except a cache-revision manifest bump. A packaging
acknowledgment records that scope only: it grants no live installation approval,
and neither package establishes iPad rendering or changes the native stack.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
# Pin every reused historical helper before importing it.
FROZEN_HELPER_HASHES = {
    'tools/package_armor_visibility_repair.py':'33d64c891db8ad18f5225b8774877550dbae761841dbe0da652fe60bbbe1453f',
    'tools/package_wing_appearance_repair.py':'268504da2052c220202fe7a5ca1b5759c1b79d296528156715864ad0992521ae',
    'tools/package_wing_diagnostic.py':'65fff22949c3e5b0a4cfc0b199450b2302b59a6f70d96a5e3f4fc39c90e3ebeb',
    'tools/wing_render_diagnostic.py':'93892119ddac366157b4b94685ed3d488eb3bb2821e1d937d26b055da4af1e62',
}
R4_PACKAGER = 'tools/package_wing_slot_repair.py'
R4_PACKAGER_SHA256 = '86854685c2ee510c20f93503ea104f11520582aa002afa56412a514ccc22b0e0'
PINNED_SOURCES = {**FROZEN_HELPER_HASHES, R4_PACKAGER:R4_PACKAGER_SHA256}


def _source_digest(name):
    path = ROOT/name
    if path.is_symlink() or not path.is_file():
        raise ValueError('Pinned historical helper is missing or linked: '+name)
    return hashlib.sha256(path.read_bytes()).hexdigest()


for _name, _expected in PINNED_SOURCES.items():
    if _source_digest(_name) != _expected:
        raise ImportError('A frozen historical resource validator changed: '+_name)

import package_armor_visibility_repair as BASE  # noqa: E402
import package_wing_diagnostic as PAIR  # noqa: E402
import package_wing_slot_repair as REPAIR  # noqa: E402
import wing_ui_diagnostic as PROBE  # noqa: E402


VERSION = '2.13.0-explore.51'
MINECRAFT = '26.4-snapshot-5'
R4_SHA256 = '97b10b64d6b5da05be2e1353cda2b95598e4644bf37580a54b8eeee0b76a02c3'
R4_PACK_SHA256 = '9f53d59e915088756c1486c095739a75a84e4261f5ab18d65b5bd951f4875c84'
R4_LOCK_SHA256 = '354fbc14cff2440e77f3280607d7635b3305d33738f8aad29b1c6d9d6ddd3f98'
R4_PREFIX = REPAIR.REPAIR_PREFIX
R4_REVISION = REPAIR.REPAIR_VERSION
DIAGNOSTIC_REVISION = (2, 13, 51005)
CLEANUP_REVISION = (2, 13, 51006)
DIAGNOSTIC_NAME = 'wing-ui-diagnostic-explore51-r5'
CLEANUP_NAME = 'wing-ui-cleanup-explore51-r6'
DIAGNOSTIC_PREFIX = 'Infinity_Armor_Wing_UI_Diagnostic_explore51-r5/'
CLEANUP_PREFIX = 'Infinity_Armor_Wing_UI_Cleanup_explore51-r6/'
DIAGNOSTIC_NOTES = 'WING_UI_DIAGNOSTIC.md'
CLEANUP_NOTES = 'WING_UI_CLEANUP.md'
ATTACHABLE = REPAIR.ATTACHABLE
PROBE_ADDED_ENTRIES = 3
R4_PAYLOADS = frozenset((BASE.LOCK_PATH, BASE.PACK_PATH, REPAIR.NOTES_PATH, 'PATCH.json'))
R4_PATCH_KEYS = frozenset((
    'format_version', 'repair', 'role', 'version', 'baseline', 'lineage', 'expected_old_sha256',
    'expected_new_sha256', 'pack_changes', 'unchanged_locked_artifacts_sha256', 'unchanged_mapping_sha256',
    'payload_sha256', 'bundle_files', 'provenance', 'slot_value_inferred', 'runtime_molang_context_verified',
    'device_rendering_verified', 'live_installation_performed', 'owner_approval_required',
    'live_installation_approval_received'))
ACKNOWLEDGMENT = {
    'temporary':True, 'probe_panels_on_winged_chest':True, 'probe_panels_on_ordinary_elytra':True,
    'probe_panels_on_held_elytra':True, 'probe_panels_in_world':True, 'independent_controls_required':True,
    'client_fix_claimed':False, 'owner_approval_required':True, 'live_installation_approval_received':False,
}
PROOF_BOUNDARIES = {
    'owner_approval_required':True, 'live_installation_approval_received':False,
    'live_installation_performed':False, 'runtime_molang_context_verified':False,
    'device_rendering_verified':False,
}


require = REPAIR.require
same_json = REPAIR.same_json


def is_sha(value):
    return isinstance(value, str) and bool(BASE.SHA256.fullmatch(value))


def frozen_helpers():
    require(all(_source_digest(name) == expected for name, expected in PINNED_SOURCES.items())
            and {**FROZEN_HELPER_HASHES, R4_PACKAGER:R4_PACKAGER_SHA256} == PINNED_SOURCES
            and REPAIR.FROZEN_HELPER_HASHES == FROZEN_HELPER_HASHES,
            'A frozen historical resource validator changed')
    require(all(Path(module.__file__).resolve() == ROOT/name for module, name in (
                (BASE, 'tools/package_armor_visibility_repair.py'), (PAIR, 'tools/package_wing_diagnostic.py'),
                (REPAIR, R4_PACKAGER), (PAIR.DIAGNOSTIC, 'tools/wing_render_diagnostic.py'),
                (REPAIR.R1, 'tools/package_wing_appearance_repair.py'), (PROBE, 'tools/wing_ui_diagnostic.py'))),
            'Historical validator imported from a different source root')


def json_text(value):
    try:
        json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)
    except (TypeError, ValueError) as error:
        raise ValueError('Validator report is not strict JSON') from error
    return value


def members(value):
    require(isinstance(value, dict) and value and all(isinstance(name, str) and isinstance(data, bytes)
            for name, data in value.items()), 'Probe API must return complete dict[path, bytes] pack members')
    return value


def selector_lines():
    return [REPAIR.R1._baseline_selector(variable, name).replace(REPAIR.R1.OLD_SLOT_QUERY, REPAIR.R1.OWNER_SLOT_QUERY)
            .replace(REPAIR.OLD_PREDICATE, REPAIR.NEW_PREDICATE) for variable, name in REPAIR.R1.SELECTORS]


def validate_r4(r4, server_root, mapping_root, expected_r4_sha256):
    """Check the four-file r4 receipt for internal coherence and prepared inputs."""
    require(set(r4) == R4_PAYLOADS, 'R4 ZIP must contain exactly its four public files')
    require(r4[REPAIR.NOTES_PATH].strip(), 'R4 notes are empty')
    pack, lock_data = r4[BASE.PACK_PATH], r4[BASE.LOCK_PATH]
    patch, lock = BASE.json_object(r4['PATCH.json']), BASE.json_object(lock_data)
    pack_members = BASE.safe_zip(pack)
    PAIR.validate_revision(pack_members, R4_REVISION)
    try:
        attachable = BASE.json_object(pack_members[ATTACHABLE])
        lines = attachable['minecraft:attachable']['description']['scripts']['pre_animation']
    except (KeyError, TypeError) as error:
        raise ValueError('R4 pack lacks the winged-chest attachable') from error
    require(lines == selector_lines(), 'R4 pack must contain exactly both positive torso-slot selectors')
    require(lock.get('version') == VERSION and lock.get('minecraft') == MINECRAFT, 'Wrong R4 release identity')
    entries = BASE.lock_entries(lock)
    require(entries.get(BASE.PACK_PATH) == BASE.digest(pack), 'R4 lock does not bind its pack')
    require(set(patch) == R4_PATCH_KEYS and same_json(patch['format_version'], 1) and patch['repair'] == REPAIR.REPAIR_NAME
            and patch['role'] == 'resource_repair' and patch['version'] == VERSION
            and patch['slot_value_inferred'] is True
            and all(patch[key] is value for key, value in PROOF_BOUNDARIES.items()),
            'R4 receipt role, version or proof boundaries differ')
    require(patch['expected_new_sha256'] == {BASE.LOCK_PATH:BASE.digest(lock_data), BASE.PACK_PATH:BASE.digest(pack)}
            and patch['payload_sha256'] == {name:BASE.digest(data) for name, data in sorted(r4.items()) if name != 'PATCH.json'}
            and patch['bundle_files'] == sorted(r4), 'R4 receipt payload declarations differ from its ZIP')
    previous, baseline, lineage = patch['expected_old_sha256'], patch['baseline'], patch['lineage']
    require(isinstance(previous, dict) and set(previous) == {BASE.LOCK_PATH, BASE.PACK_PATH}
            and all(is_sha(value) for value in previous.values()) and previous[BASE.PACK_PATH] != BASE.digest(pack),
            'R4 predecessor hashes are malformed')
    require(isinstance(lineage, dict) and set(lineage) == {'native51', 'r1', 'r3', 'native51_to_r1_contract_verified',
            'r1_to_normal_r3_contract'} and lineage['native51_to_r1_contract_verified'] is True
            and all(isinstance(lineage[key], dict) and set(lineage[key]) == {'file', 'sha256'}
                    and isinstance(lineage[key]['file'], str)
                    and BASE.safe_name(lineage[key]['file']) == Path(lineage[key]['file']).name
                    and is_sha(lineage[key]['sha256']) for key in ('native51', 'r1', 'r3'))
            and baseline == {**lineage['r3'], 'version':VERSION}, 'R4 native51/r1/r3 lineage is malformed')
    if expected_r4_sha256 == R4_SHA256:
        require([lineage[key]['sha256'] for key in ('native51', 'r1', 'r3')]
                == [REPAIR.NATIVE_SHA256, REPAIR.R1_SHA256, REPAIR.R3_SHA256]
                and BASE.digest(pack) == R4_PACK_SHA256 and BASE.digest(lock_data) == R4_LOCK_SHA256,
                'Production R4 lineage differs from the reviewed pins')
    normal = lineage['r1_to_normal_r3_contract']
    require(isinstance(normal, dict) and normal.get('version') == list(PAIR.CLEANUP_REVISION)
            and normal.get('added_entries') == [] and normal.get('deleted_entries') == sorted(PAIR.SHIELD_ATTACHABLES)
            and isinstance(normal.get('changed_entries'), dict) and set(normal['changed_entries']) == {'manifest.json'}
            and normal.get('native_shield_fallback') is True and normal.get('device_rendering_verified') is False,
            'R4 r1-to-normal-r3 contract is malformed')
    changes = patch['pack_changes']
    require(isinstance(changes, dict) and changes.get('version') == list(R4_REVISION)
            and changes.get('added_entries') == [] and changes.get('deleted_entries') == []
            and isinstance(changes.get('changed_entries'), dict)
            and set(changes['changed_entries']) == REPAIR.ALLOWED_PACK_CHANGES
            and all(isinstance(value, dict) and is_sha(value.get('old_sha256'))
                    and value.get('new_sha256') == BASE.digest(pack_members[name]) != value['old_sha256']
                    for name, value in changes['changed_entries'].items())
            and changes.get('unchanged_entry_count') == len(pack_members)-2
            and changes.get('unchanged_png_entries') == sum(name.endswith('.png') for name in pack_members)
            and changes.get('unchanged_geometry_entries') == sum(name.endswith('.geo.json') for name in pack_members)
            and changes.get('slot_predicate_replacements') == REPAIR.SELECTOR_COUNTS
            and changes.get('positive_slot_predicate') == REPAIR.NEW_PREDICATE
            and changes.get('slot_value_inferred') is True
            and changes.get('runtime_molang_context_verified') is False
            and changes.get('device_rendering_verified') is False, 'R4 pack-change report differs from its pack')
    require(patch['provenance'] == {'packager':R4_PACKAGER, 'packager_sha256':R4_PACKAGER_SHA256,
            'frozen_historical_helpers_sha256':FROZEN_HELPER_HASHES}, 'R4 validator provenance changed')
    unchanged, mappings = patch['unchanged_locked_artifacts_sha256'], patch['unchanged_mapping_sha256']
    expected_unchanged = {name:value for name, value in entries.items() if name != BASE.PACK_PATH}
    require(isinstance(unchanged, dict) and isinstance(unchanged.get('runtime.lock.json'), str)
            and is_sha(unchanged['runtime.lock.json'])
            and {k:v for k, v in unchanged.items() if k != 'runtime.lock.json'} == expected_unchanged,
            'R4 native pins disagree with its lock')
    for name, checksum in unchanged.items():
        require(BASE.file_digest(BASE.contained_file(server_root, name)) == checksum, 'Prepared native dependency changed: '+name)
    require(isinstance(mappings, dict) and set(mappings) == set(BASE.MAPPINGS)
            and all(is_sha(value) for value in mappings.values()), 'R4 mapping pins are malformed')
    for name, checksum in mappings.items():
        actual = BASE.digest(BASE.bounded_file(BASE.contained_file(mapping_root, name), BASE.MAX_JSON))
        require(actual == checksum, 'Prepared mapping differs from R4: '+name)
    server_pack = BASE.bounded_file(BASE.contained_file(server_root, BASE.PACK_PATH), BASE.MAX_ARCHIVE)
    server_lock = BASE.bounded_file(BASE.contained_file(server_root, BASE.LOCK_PATH), BASE.MAX_JSON)
    require(server_pack == pack and server_lock == lock_data, 'Prepared server pack/lock are not the exact R4 baseline')
    return patch, lock, copy.deepcopy(unchanged), copy.deepcopy(mappings)


def typed_manifest(old, new, *, labels=False):
    """Restore old versions (and, for the probe, header labels); types must match exactly."""
    normalized = copy.deepcopy(new)
    try:
        if labels:
            for key in ('name', 'description'):
                require(isinstance(normalized['header'].get(key), str) and isinstance(old['header'].get(key), str)
                        and old['header'][key] in normalized['header'][key], 'Probe manifest label must retain the R4 text')
                normalized['header'][key] = old['header'][key]
        normalized['header']['version'] = old['header']['version']
        require(len(old['modules']) == len(normalized['modules']), 'Pack modules changed')
        for before, after in zip(old['modules'], normalized['modules']):
            after['version'] = before['version']
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError('Malformed phase manifest') from error
    require(same_json(normalized, old), 'Manifest metadata and JSON value types must remain unchanged')


def append_only(old, new):
    """Every R4 value survives with its JSON type; dicts may gain keys and lists may gain a suffix."""
    if isinstance(old, dict):
        return isinstance(new, dict) and all(key in new and append_only(value, new[key]) for key, value in old.items())
    if isinstance(old, list):
        return (isinstance(new, list) and len(new) >= len(old)
                and all(append_only(value, new[index]) for index, value in enumerate(old)))
    return type(old) is type(new) and same_json(old, new)


def check_assets(old, new):
    require(all(new.get(name) == data for name, data in old.items() if name.endswith(('.png', '.geo.json'))),
            'Original PNG and geometry bytes must remain exact')


def diagnostic_delta(r4_pack, diagnostic_pack):
    old, new = BASE.safe_zip(r4_pack), BASE.safe_zip(diagnostic_pack)
    added = new.keys()-old.keys()
    require(old.keys() <= new.keys() and len(added) == PROBE_ADDED_ENTRIES,
            'Diagnostic adds exactly three owned resources and deletes nothing')
    require({name for name in old if old[name] != new[name]} == {'manifest.json', ATTACHABLE},
            'Diagnostic changes exactly the manifest and the elytra attachable')
    check_assets(old, new)
    PAIR.validate_revision(new, DIAGNOSTIC_REVISION)
    typed_manifest(BASE.json_object(old['manifest.json']), BASE.json_object(new['manifest.json']), labels=True)
    old_attachable, new_attachable = BASE.json_object(old[ATTACHABLE]), BASE.json_object(new[ATTACHABLE])
    require(append_only(old_attachable, new_attachable)
            and new_attachable['minecraft:attachable']['description']['scripts']['pre_animation'][:2] == selector_lines(),
            'Elytra attachable must be append-only and retain both production selectors first')
    report = PAIR.pack_delta(r4_pack, diagnostic_pack, DIAGNOSTIC_REVISION)
    report.update(unchanged_png_entries=sum(n.endswith('.png') for n in old),
                  unchanged_geometry_entries=sum(n.endswith('.geo.json') for n in old),
                  production_selectors_preserved=True, attachable_append_only=True)
    return report


def cleanup_delta(r4_pack, diagnostic_pack, cleanup_pack):
    old, new, probe = BASE.safe_zip(r4_pack), BASE.safe_zip(cleanup_pack), BASE.safe_zip(diagnostic_pack)
    require(old.keys() == new.keys() and not (probe.keys()-old.keys()) & new.keys(),
            'Cleanup must keep exactly the R4 member names; no probe resource survives')
    require({name for name in old if old[name] != new[name]} == {'manifest.json'},
            'Cleanup may change only the manifest bytes')
    check_assets(old, new)
    PAIR.validate_revision(new, CLEANUP_REVISION)
    old_manifest, new_manifest = BASE.json_object(old['manifest.json']), BASE.json_object(new['manifest.json'])
    BASE.validate_manifest(old_manifest, new_manifest, CLEANUP_REVISION)
    typed_manifest(old_manifest, new_manifest)
    require(BASE.digest(cleanup_pack) != BASE.digest(r4_pack), 'Cleanup cannot claim the original R4 pack hash')
    report = PAIR.pack_delta(diagnostic_pack, cleanup_pack, CLEANUP_REVISION)
    report.update(r4_to_cleanup=PAIR.pack_delta(r4_pack, cleanup_pack, CLEANUP_REVISION),
                  diagnostic_resources_removed=sorted(probe.keys()-old.keys()),
                  restores_r4_members_except_manifest=True, manifest_revision_only_difference=True)
    return report


def derived_lock(old_lock, old_pack, new_pack, base_lock):
    """Copy the R4 lock, update only the pack checksum, then compare typed JSON."""
    data = PAIR.lock_for_pack(base_lock, new_pack)
    new_lock = BASE.json_object(data)
    BASE.validate_lock(old_lock, new_lock, BASE.digest(old_pack), BASE.digest(new_pack))
    normalized = copy.deepcopy(new_lock)
    for group in ('downloads', 'bundled'):
        for entry in normalized[group]:
            if entry['path'] == BASE.PACK_PATH:
                entry['sha256'] = BASE.digest(old_pack)
    require(same_json(normalized, old_lock), 'Only the resource-pack checksum may change, preserving all JSON types')
    return data


def provenance():
    return {'packager':'tools/package_wing_ui_diagnostic.py', 'packager_sha256':BASE.file_digest(__file__),
            'probe_helper':'tools/wing_ui_diagnostic.py', 'probe_helper_sha256':BASE.file_digest(PROBE.__file__),
            'r4_packager':R4_PACKAGER, 'r4_packager_sha256':R4_PACKAGER_SHA256,
            'frozen_historical_helpers_sha256':dict(FROZEN_HELPER_HASHES)}


def phase_patch(name, role, baseline, old_lock, old_pack, new_lock, new_pack, notes_name, notes,
                report, unchanged, mappings):
    payloads = {BASE.LOCK_PATH:new_lock, BASE.PACK_PATH:new_pack, notes_name:notes}
    patch = {'format_version':1, 'repair':name, 'role':role, 'version':VERSION, 'baseline':baseline,
             'expected_old_sha256':{BASE.LOCK_PATH:BASE.digest(old_lock), BASE.PACK_PATH:BASE.digest(old_pack)},
             'expected_new_sha256':{BASE.LOCK_PATH:BASE.digest(new_lock), BASE.PACK_PATH:BASE.digest(new_pack)},
             'pack_changes':report, 'unchanged_locked_artifacts_sha256':unchanged, 'unchanged_mapping_sha256':mappings,
             'payload_sha256':{key:BASE.digest(value) for key, value in sorted(payloads.items())},
             'bundle_files':sorted([*payloads, 'PATCH.json']), 'provenance':provenance(), **PROOF_BOUNDARIES}
    return patch, payloads


def build_pair(r4_zip, server_root, mapping_root, diagnostic_notes, cleanup_notes,
               diagnostic_output, cleanup_output, pair_output,
               *, acknowledge_temporary_diagnostic=False, expected_r4_sha256=R4_SHA256):
    """Validate everything and build all bytes before any output is written.

    expected_r4_sha256 exists for caller-bound synthetic fixtures; the CLI always
    pins the approved R4 ZIP. The acknowledgment is a packaging choice only.
    """
    require(type(acknowledge_temporary_diagnostic) is bool and acknowledge_temporary_diagnostic,
            'Packaging must acknowledge temporary probe panels on ordinary/held elytra and in the world; '
            'this is not live approval')
    frozen_helpers()
    r4 = PAIR.read_zip(r4_zip, expected_r4_sha256, R4_PREFIX, maximum_entries=4)
    r4_patch, r4_lock, unchanged, mappings = validate_r4(r4, server_root, mapping_root, expected_r4_sha256)
    r4_pack, r4_lock_data = r4[BASE.PACK_PATH], r4[BASE.LOCK_PATH]
    r4_members = BASE.safe_zip(r4_pack)
    # Production keeps the probe's strict approved-R4 asset counts; only a
    # caller-bound fixture hash relaxes them for tracked-source fixtures.
    kwargs = {} if expected_r4_sha256 == R4_SHA256 else {'expected_asset_counts':None}
    diagnostic_members = members(PROBE.apply(dict(r4_members), **kwargs))
    cleanup_members = members(PROBE.cleanup(dict(r4_members), **kwargs))
    require(r4_members == BASE.safe_zip(r4_pack), 'Probe API mutated the R4 members')
    overlay_report = json_text(PROBE.validate_overlay(dict(r4_members), dict(diagnostic_members), **kwargs))
    cleanup_check = json_text(PROBE.validate_cleanup(dict(r4_members), dict(cleanup_members), **kwargs))
    require(isinstance(overlay_report, dict) and isinstance(cleanup_check, dict), 'Probe validators must return reports')
    diagnostic_pack, cleanup_pack = PAIR.pack_bytes(diagnostic_members), PAIR.pack_bytes(cleanup_members)
    diagnostic_report = diagnostic_delta(r4_pack, diagnostic_pack)
    cleanup_report = cleanup_delta(r4_pack, diagnostic_pack, cleanup_pack)
    diagnostic_lock = derived_lock(r4_lock, r4_pack, diagnostic_pack, r4_lock)
    cleanup_lock = derived_lock(r4_lock, r4_pack, cleanup_pack, r4_lock)
    BASE.validate_lock(BASE.json_object(diagnostic_lock), BASE.json_object(cleanup_lock),
                       BASE.digest(diagnostic_pack), BASE.digest(cleanup_pack))
    dnotes, cnotes = BASE.bounded_file(diagnostic_notes, 256*1024), BASE.bounded_file(cleanup_notes, 256*1024)
    require(dnotes.strip() and cnotes.strip(), 'Both diagnostic and cleanup notes must be nonempty')
    r4_identity = {'file':Path(r4_zip).name, 'sha256':expected_r4_sha256, 'version':VERSION}
    r4_hashes = {'pack_sha256':BASE.digest(r4_pack), 'lock_sha256':BASE.digest(r4_lock_data),
                 'resource_revision':list(R4_REVISION)}
    r4_receipt = {'patch_sha256':BASE.digest(r4['PATCH.json']), 'repair':r4_patch['repair'],
                  'baseline':r4_patch['baseline'], 'lineage':r4_patch['lineage'],
                  'expected_old_sha256':r4_patch['expected_old_sha256'], 'provenance':r4_patch['provenance']}
    cleanup_target = {'pack_sha256':BASE.digest(cleanup_pack), 'lock_sha256':BASE.digest(cleanup_lock),
                      'resource_revision':list(CLEANUP_REVISION), 'restores_r4_members_except_manifest':True,
                      'baseline_r4_pack_sha256':BASE.digest(r4_pack)}
    dpatch, dpayloads = phase_patch(DIAGNOSTIC_NAME, 'temporary_diagnostic', r4_identity, r4_lock_data, r4_pack,
        diagnostic_lock, diagnostic_pack, DIAGNOSTIC_NOTES, dnotes, diagnostic_report, unchanged, mappings)
    dpatch.update(diagnostic=copy.deepcopy(ACKNOWLEDGMENT), probe_validator_report=overlay_report,
                  r4_receipt=r4_receipt, cleanup_target=cleanup_target)
    dpayloads['PATCH.json'] = BASE.encoded_json(dpatch)
    dzip = PAIR.pack_bytes(dpayloads, DIAGNOSTIC_PREFIX)
    cpatch, cpayloads = phase_patch(CLEANUP_NAME, 'diagnostic_cleanup',
        {'file':Path(diagnostic_output).name, 'sha256':BASE.digest(dzip), 'version':VERSION},
        diagnostic_lock, diagnostic_pack, cleanup_lock, cleanup_pack, CLEANUP_NOTES, cnotes,
        cleanup_report, unchanged, mappings)
    cpatch.update(probe_validator_report=cleanup_check, diagnostic_resources_removed=True,
                  restores_r4_members_except_manifest=True, baseline_r4=dict(r4_identity, **r4_hashes),
                  baseline_r4_pack_sha256=BASE.digest(r4_pack))
    cpayloads['PATCH.json'] = BASE.encoded_json(cpatch)
    czip = PAIR.pack_bytes(cpayloads, CLEANUP_PREFIX)
    pair = {'format_version':1, 'version':VERSION, 'minecraft':MINECRAFT,
        'installed_r4_baseline':dict(r4_identity, **r4_hashes),
        'diagnostic':{'file':Path(diagnostic_output).name, 'sha256':BASE.digest(dzip),
                      'resource_revision':list(DIAGNOSTIC_REVISION), 'pack_sha256':BASE.digest(diagnostic_pack),
                      'lock_sha256':BASE.digest(diagnostic_lock)},
        'cleanup':{'file':Path(cleanup_output).name, 'sha256':BASE.digest(czip),
                   'resource_revision':list(CLEANUP_REVISION), 'pack_sha256':BASE.digest(cleanup_pack),
                   'lock_sha256':BASE.digest(cleanup_lock)},
        'diagnostic_scope':copy.deepcopy(ACKNOWLEDGMENT),
        'cleanup_contract':{'restores_r4_members_except_manifest':True, 'manifest_revision_only_difference':True,
                            'baseline_r4_pack_sha256':BASE.digest(r4_pack), 'same_pack_sha256_as_r4':False},
        'unchanged_locked_artifacts_sha256':unchanged, 'unchanged_mapping_sha256':mappings, **PROOF_BOUNDARIES}
    outputs = [Path(path) for path in (diagnostic_output, cleanup_output, pair_output)]
    require(outputs[0].suffix.lower() == outputs[1].suffix.lower() == '.zip' and outputs[2].suffix.lower() == '.json',
            'Pair requires two ZIP outputs and a separate JSON receipt')
    require(len({path.resolve() for path in outputs}) == 3, 'Diagnostic, cleanup and pair receipt outputs must be distinct')
    staging = [Path(server_root).resolve(), Path(mapping_root).resolve(), (ROOT/'server').resolve()]
    require(not any(path.resolve().is_relative_to(root) for path in outputs for root in staging),
            'Candidate outputs cannot be staged into server, mapping or source server folders')
    inputs = [r4_zip, diagnostic_notes, cleanup_notes, Path(server_root)/BASE.PACK_PATH, Path(server_root)/BASE.LOCK_PATH,
              *(Path(server_root)/name for name in unchanged), *(Path(mapping_root)/name for name in mappings)]
    PAIR.write_exclusive_outputs(dict(zip(outputs, (dzip, czip, BASE.encoded_json(pair)))), inputs)
    return pair


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--r4-baseline', type=Path,
        default=ROOT/'dist/wing-slot-repair-explore51-r4/Infinity_Armor_Wing_Slot_Repair_explore51-r4.zip')
    parser.add_argument('--server', type=Path, default=ROOT/'server')
    parser.add_argument('--mappings', type=Path, default=ROOT/'research/integration/crossplay-export')
    parser.add_argument('--diagnostic-notes', type=Path, default=ROOT/'server'/DIAGNOSTIC_NOTES)
    parser.add_argument('--cleanup-notes', type=Path, default=ROOT/'server'/CLEANUP_NOTES)
    parser.add_argument('--diagnostic-output', type=Path,
        default=ROOT/'dist/wing-ui-diagnostic-explore51-r5/Infinity_Armor_Wing_UI_Diagnostic_explore51-r5.zip')
    parser.add_argument('--cleanup-output', type=Path,
        default=ROOT/'dist/wing-ui-cleanup-explore51-r6/Infinity_Armor_Wing_UI_Cleanup_explore51-r6.zip')
    parser.add_argument('--pair-output', type=Path, default=ROOT/'dist/wing-ui-diagnostic-explore51-r5/PAIR.json')
    parser.add_argument('--acknowledge-temporary-diagnostic', action='store_true',
        help='Record temporary probe panels on ordinary/held elytra and in the world; grants no live approval')
    args = parser.parse_args()
    try:
        pair = build_pair(args.r4_baseline, args.server, args.mappings, args.diagnostic_notes, args.cleanup_notes,
            args.diagnostic_output, args.cleanup_output, args.pair_output,
            acknowledge_temporary_diagnostic=args.acknowledge_temporary_diagnostic)
    except (ValueError, OSError) as error:
        parser.exit(1, 'Wing UI diagnostic pair packaging refused: '+str(error)+'\n')
    print(json.dumps(pair, indent=2))


if __name__ == '__main__':
    main()
