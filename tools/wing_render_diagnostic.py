"""Copy-only temporary Bedrock wing probe; no client-rendering proof or live controls.

Input is the separately validated normal r3 pack. This produces r2 solely for
the planned live r1 -> temporary r2 -> normal r3 sequence. Ordinary and held
elytra intentionally gain diagnostic glyphs until the exact normal pack returns.
"""
import copy
import hashlib
import json
from pathlib import PurePosixPath
import struct
import zlib


NORMAL_REVISION = (2, 13, 51003)
REVISION = (2, 13, 51002)
ELYTRA = 'attachables/combined_convergence_elytra.json'
HELMETS = ('attachables/elemental41/infinity_helmet.json',
           'attachables/elemental41/catalog_infinity_helmet.json')
LANG = 'texts/en_US.lang'
GEOMETRY = 'models/entity/diagnostics/wing_render_r2.geo.json'
CONTROLLERS = 'render_controllers/diagnostics/wing_render_r2.json'
LEGEND = 'diagnostics/wing_render_r2.json'
TEXTURE = 'textures/convergence/models/armor/armor42/infinity.png'
TEXTURE_SHA256 = 'e7dd7843aaf8e9046a9e0f6c36a6d9728787a61175a8096a56ffeeda5b77d0f1'
GEOMETRY_ID = 'geometry.infinityhub.wing_render_diagnostic_r2'
ROOT_BONE = 'infinityhub_wing_diag_r2_root'
MATERIAL_ALIAS = 'infinityhub_diag_r2'
TEXTURE_ALIAS = 'infinityhub_diag_r2'
GEOMETRY_ALIAS = 'infinityhub_diag_r2'
ELYTRA_CONTROLLER = 'controller.render.infinityhub.wing_diag_r2'
HELMET_CONTROLLER = 'controller.render.infinityhub.wing_diag_r2_helmet_control'
ALIVE = 'variable.infinityhub_diag_r2_pre_animation'
MARKERS = ('A', 'T', 'H', 'P', 'B', 'S', 'V', 'E', 'Z')
PROBE_EXPRESSIONS = {
    'A': '1.0',
    'T': ALIVE,
    'H': "context.owning_entity->query.has_property('infinityhub:winged_chest')",
    'P': "(context.owning_entity->query.has_property('infinityhub:winged_chest')) ? (context.owning_entity->query.property('infinityhub:winged_chest')) : 0.0",
    'B': "query.item_slot_to_bone_name(context.item_slot) == 'body'",
    'S': "(context.owning_entity->query.item_slot_to_bone_name(context.item_slot)) == 'body'",
    'V': 'variable.infinityhub_winged_chest',
    'E': 'variable.infinityhub_winged_chest && (query.is_in_ui || !query.is_first_person)',
    'Z': '0.0',
}
PROBE_VARIABLES = {letter: 'variable.infinityhub_diag_r2_'+letter.lower() for letter in ('H', 'P', 'B', 'S')}
CONDITIONS = {**PROBE_EXPRESSIONS, **PROBE_VARIABLES}
MEANINGS = {
    'A': 'Unconditional attachable/diagnostic geometry load control',
    'T': 'Diagnostic pre_animation constant is visible to this renderer',
    'H': 'The owning actor exposes infinityhub:winged_chest',
    'P': 'The guarded owning-actor winged-chest property reads true, without slot gating',
    'B': 'The original plain equipment-slot query returns body',
    'S': 'The r1 owning-actor equipment-slot query returns body',
    'V': 'The existing production pre_animation combined-wing flag is true',
    'E': 'The production body/wing viewport visibility predicate permits rendering',
    'Z': 'Literal false negative control: MUST NEVER APPEAR; invalidate all readings if visible',
}
GLYPHS = {
    'A': ('010', '101', '111', '101', '101'),
    'T': ('111', '010', '010', '010', '010'),
    'H': ('101', '101', '111', '101', '101'),
    'P': ('110', '101', '110', '100', '100'),
    'B': ('110', '101', '110', '101', '110'),
    'S': ('111', '100', '111', '001', '111'),
    'V': ('101', '101', '101', '101', '010'),
    'E': ('111', '100', '110', '100', '111'),
    'Z': ('111', '001', '010', '100', '111'),
}
LANG_LABELS = {
    'container.inventory': 'Inventory [R2 wing diagnostic]',
    'container.creative': 'Item Selection [R2 wing diagnostic]',
    'item.elytra.name': 'Elytra [R2 diagnostic]',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def encoded(value):
    return (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False)+'\n').encode()


def parsed(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate diagnostic resource JSON key')
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def checked_files(files):
    require(isinstance(files, dict) and 0 < len(files) <= 1024, 'Expected a bounded normal pack dictionary')
    total = 0
    for name, data in files.items():
        require(isinstance(name, str) and isinstance(data, bytes), 'Pack entries must be named bytes')
        path = PurePosixPath(name)
        require(path.parts and not path.is_absolute() and '..' not in path.parts and '\\' not in name
                and '\0' not in name and path.as_posix() == name, 'Unsafe diagnostic pack member')
        require(len(data) <= 16*1024**2, 'Diagnostic pack member exceeds size limit')
        total += len(data)
    require(total <= 128*1024**2, 'Diagnostic pack exceeds bounded size')


def rgba_pixels(data):
    """Bounded RGBA8 noninterlaced PNG decoder for checking reused opaque UVs."""
    require(data[:8] == b'\x89PNG\r\n\x1a\n', 'Diagnostic texture is not PNG')
    position, compressed, header, ended = 8, [], None, False
    while position < len(data):
        require(position+12 <= len(data), 'Truncated diagnostic texture chunk')
        size = int.from_bytes(data[position:position+4], 'big')
        kind = data[position+4:position+8]
        require(size <= 16*1024**2 and position+12+size <= len(data), 'Invalid diagnostic texture chunk length')
        payload = data[position+8:position+8+size]
        checksum = int.from_bytes(data[position+8+size:position+12+size], 'big')
        require(zlib.crc32(kind+payload)&0xffffffff == checksum, 'Diagnostic texture CRC failed')
        if kind == b'IHDR':
            require(header is None and size == 13, 'Invalid PNG header')
            header = struct.unpack('>IIBBBBB', payload)
        elif kind == b'IDAT':
            compressed.append(payload)
        elif kind == b'IEND':
            require(size == 0 and position+12 == len(data), 'Invalid PNG ending')
            ended = True
        position += size+12
    require(ended and header is not None and compressed, 'Incomplete diagnostic texture')
    width, height, depth, color, compression, filtering, interlace = header
    require(0 < width <= 2048 and 0 < height <= 2048 and (depth, color, compression, filtering, interlace) == (8, 6, 0, 0, 0),
            'Diagnostic texture requires bounded RGBA8 pixels')
    stride = width*4
    decoder = zlib.decompressobj()
    raw = decoder.decompress(b''.join(compressed), height*(stride+1)+1)
    require(decoder.eof and not decoder.unused_data and len(raw) == height*(stride+1), 'Invalid bounded PNG data')
    previous, result = bytearray(stride), bytearray()
    for row in range(height):
        offset = row*(stride+1)
        filter_type, values = raw[offset], bytearray(raw[offset+1:offset+1+stride])
        require(filter_type <= 4, 'Unsupported PNG filter')
        for i in range(stride):
            left, up = (values[i-4] if i >= 4 else 0), previous[i]
            corner = previous[i-4] if i >= 4 else 0
            if filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = up
            elif filter_type == 3:
                predictor = (left+up)//2
            elif filter_type == 4:
                p = left+up-corner
                distance = (abs(p-left), abs(p-up), abs(p-corner))
                predictor = (left, up, corner)[distance.index(min(distance))]
            else:
                predictor = 0
            values[i] = (values[i]+predictor)&255
        result.extend(values)
        previous = values
    return width, height, bytes(result)


def opaque_rectangle(data, rectangle):
    width, height, pixels = rgba_pixels(data)
    x, y, w, h = rectangle
    require(all(type(v) is int for v in rectangle) and w > 0 and h > 0
            and x >= 0 and y >= 0 and x+w <= width and y+h <= height, 'Diagnostic UV rectangle is out of bounds')
    require(all(pixels[(j*width+i)*4+3] == 255 for j in range(y, y+h) for i in range(x, x+w)),
            'Diagnostic UV rectangle contains transparent texels')
    return True


def selected_uv(files):
    require(TEXTURE in files and hashlib.sha256(files[TEXTURE]).hexdigest() == TEXTURE_SHA256,
            'The accepted diagnostic texture changed')
    width, height, pixels = rgba_pixels(files[TEXTURE])
    require((width, height) == (128, 64), 'Accepted diagnostic texture dimensions changed')
    # Choose one bright fully opaque texel from the accepted Infinity helmet's
    # face region. Every face of every glyph samples exactly that same texel.
    choices = [(sum(pixels[(y*width+x)*4:(y*width+x)*4+3]), -y, -x, x, y)
               for y in range(16, 32) for x in range(16, 32) if pixels[(y*width+x)*4+3] == 255]
    require(choices, 'Accepted helmet texture has no opaque diagnostic texel')
    _, _, _, x, y = max(choices)
    opaque_rectangle(files[TEXTURE], (x, y, 1, 1))
    return {'uv': [x, y], 'uv_size': [1, 1], 'rgba': list(pixels[(y*width+x)*4:(y*width+x)*4+4]),
            'texture_width': width, 'texture_height': height, 'alpha_fully_opaque': True}


def positions():
    return {letter: {'row': index//3+1, 'column': index%3+1,
                     'center_x': (-5.5, 0, 5.5)[index%3], 'top_y': (39, 33, 27)[index//3],
                     'z': 5.5, 'pixel_size': 0.6, 'pixel_depth': 0.35}
            for index, letter in enumerate(MARKERS)}


def geometry(uv):
    face_uv = {face: {'uv': uv['uv'], 'uv_size': uv['uv_size']}
               for face in ('north', 'south', 'east', 'west', 'up', 'down')}
    bones = [{'name': 'body', 'pivot': [0, 24, 0]},
             {'name': ROOT_BONE, 'parent': 'body', 'pivot': [0, 24, 0]}]
    for letter, point in positions().items():
        cubes = []
        for y, row in enumerate(GLYPHS[letter]):
            for x, lit in enumerate(row):
                if lit == '1':
                    cubes.append({'origin': [round(point['center_x']+(x-1.5)*0.6, 3),
                                             round(point['top_y']-(y+1)*0.6, 3), point['z']],
                                  'size': [0.6, 0.6, 0.35], 'uv': copy.deepcopy(face_uv)})
        bones.append({'name': ROOT_BONE+'_'+letter.lower(), 'parent': ROOT_BONE,
                      'pivot': [0, 24, 0], 'cubes': cubes})
    return {'format_version': '1.19.30', 'minecraft:geometry': [{
        'description': {'identifier': GEOMETRY_ID, 'texture_width': uv['texture_width'],
            'texture_height': uv['texture_height'], 'visible_bounds_width': 8,
            'visible_bounds_height': 8, 'visible_bounds_offset': [0, 1.5, 0]}, 'bones': bones}]}


def controller(helmet=False):
    return {'geometry': 'Geometry.'+GEOMETRY_ALIAS,
        'materials': [{'*': 'Material.'+MATERIAL_ALIAS}], 'textures': ['Texture.'+TEXTURE_ALIAS],
        'part_visibility': [{'body': '1.0'}, {ROOT_BONE: '1.0'}, *[
            {ROOT_BONE+'_'+letter.lower(): ('1.0' if letter == 'A' else '0.0') if helmet else CONDITIONS[letter]}
            for letter in MARKERS]]}


def _description(files, name):
    require(name in files, 'Required normal attachable missing: '+name)
    value = parsed(files[name])
    return value, value['minecraft:attachable']['description']


def apply(files, revision=REVISION):
    """Return a deterministic diagnostic COPY; never modify files or normal input."""
    checked_files(files)
    require(tuple(revision) == REVISION and all(type(n) is int for n in revision), 'Wrong diagnostic revision')
    require(not any(n in files for n in (GEOMETRY, CONTROLLERS, LEGEND))
            and not any('infinityhub_diag_r2' in n or 'wing_render_r2' in n for n in files),
            'The normal pack already contains diagnostic resources; use the clean original')
    manifest = parsed(files['manifest.json'])
    versions = [manifest['header']['version'], *(m['version'] for m in manifest['modules'])]
    require(manifest['modules'] and all(v == list(NORMAL_REVISION) for v in versions), 'Expected the normal r3 cleanup pack')
    result = dict(files)
    manifest['header']['version'] = list(revision)
    manifest['header']['name'] += ' | TEMPORARY WING DIAGNOSTIC R2'
    manifest['header']['description'] = ('TEMPORARY DIAGNOSTIC: ordinary and held elytra show test markers; '
        'requires owner-approved cleanup. '+manifest['header']['description'])
    for module in manifest['modules']:
        module['version'] = list(revision)
    result['manifest.json'] = encoded(manifest)
    uv = selected_uv(files)
    for name in (ELYTRA, *HELMETS):
        value, description = _description(files, name)
        if name == ELYTRA:
            require(description['identifier'] == 'minecraft:elytra', 'Native elytra identity changed')
        else:
            expected = 'infinity_catalog:infinity_helmet' if 'catalog_' in name else 'convergence:infinity_helmet'
            require(description['identifier'] == expected, 'Infinity helmet control identity changed')
        for key, alias, entry in (('materials', MATERIAL_ALIAS, 'entity_alphatest'),
                                  ('textures', TEXTURE_ALIAS, TEXTURE.removesuffix('.png')),
                                  ('geometry', GEOMETRY_ALIAS, GEOMETRY_ID)):
            require(alias not in description[key], 'Normal attachable already contains a diagnostic alias')
            description[key][alias] = entry
        if name == ELYTRA:
            scripts = description['scripts']
            require(isinstance(scripts.get('pre_animation'), list)
                    and len(scripts['pre_animation']) == 2 and not any(ALIVE in v for v in scripts['pre_animation']),
                    'Normal combined-wing selector scripts changed')
            # Append owned observations only; never rewrite or bypass the two
            # production selector statements. Each query runs independently in
            # this attachable context, with no dependency on another probe.
            scripts['pre_animation'].append(ALIVE+' = 1.0;')
            scripts['pre_animation'].extend(PROBE_VARIABLES[letter]+' = '+PROBE_EXPRESSIONS[letter]+';'
                                            for letter in ('H', 'P', 'B', 'S'))
        require(isinstance(description['render_controllers'], list), 'Missing normal renderer list')
        description['render_controllers'].append(ELYTRA_CONTROLLER if name == ELYTRA else HELMET_CONTROLLER)
        result[name] = encoded(value)
    result[GEOMETRY] = encoded(geometry(uv))
    result[CONTROLLERS] = encoded({'format_version': '1.8.0', 'render_controllers': {
        ELYTRA_CONTROLLER: controller(), HELMET_CONTROLLER: controller(helmet=True)}})
    raw_language = files.get(LANG, b'').decode('utf-8')
    kept = [line for line in raw_language.splitlines() if line.split('=', 1)[0] not in LANG_LABELS]
    result[LANG] = ('\n'.join(kept).rstrip()+'\n'+'\n'.join(k+'='+v for k, v in LANG_LABELS.items())+'\n').encode()
    result[LEGEND] = encoded({'format': 1, 'diagnostic': 'temporary_wing_render_r2',
        'temporary': True, 'ordinary_elytra_badges': True, 'held_elytra_badges': True,
        'owner_approval_required': True, 'live_installation_approval_received': False,
        'english_labels_only': True, 'device_rendering_verified': False,
        'runtime_molang_context_verified': False, 'read_with_both_hands_empty': True,
        'read_views_separately': ['third_person_back', 'inventory_character'],
        'coordinate_frame': 'Player model space; front/back camera projection may mirror the grid and glyphs',
        'negative_control': 'Z must never be visible; invalidate readings if it appears',
        'helmet_control': {'controller': HELMET_CONTROLLER, 'same_geometry_material_texture_and_uv': True,
                           'unconditional_marker': 'A'},
        'texture': {'path': TEXTURE, 'sha256': TEXTURE_SHA256, **uv},
        'markers': [{'label': letter, 'bone': ROOT_BONE+'_'+letter.lower(),
                     'condition': CONDITIONS[letter], 'meaning': MEANINGS[letter],
                     'probe_expression': '1.0' if letter == 'T' else PROBE_EXPRESSIONS[letter],
                     'evaluation_stage': 'attachable.pre_animation' if letter in ('T', 'H', 'P', 'B', 'S')
                                         else 'render_controller.part_visibility',
                     **positions()[letter], 'glyph': list(GLYPHS[letter])} for letter in MARKERS]})
    return result


build_overlay = apply


def validate_overlay(normal_files, diagnostic_files):
    """Reject every delta outside the exact copy-only temporary diagnostic."""
    checked_files(diagnostic_files)
    expected = apply(normal_files)
    require(expected.keys() == diagnostic_files.keys(), 'Diagnostic added or removed an unowned resource')
    require(all(diagnostic_files[name] == data for name, data in expected.items()),
            'Diagnostic changed beyond its exact temporary marker/UI overlay')
    added = sorted(set(expected)-set(normal_files))
    changed = sorted(n for n in normal_files if expected[n] != normal_files[n])
    return {'format': 1, 'temporary': True, 'ordinary_elytra_badges': True, 'held_elytra_badges': True,
        'owner_approval_required': True, 'live_installation_approval_received': False,
        'version': list(REVISION), 'normal_cleanup_version': list(NORMAL_REVISION),
        'added_entries': added, 'changed_entries': changed, 'deleted_entries': [],
        'marker_labels': list(MARKERS), 'negative_control': 'Z literal 0.0',
        'unconditional_badge_controller_with_per_bone_visibility': True,
        'accepted_pngs_and_normal_native_resources_unchanged': True,
        'opaque_uv': selected_uv(normal_files), 'legend': LEGEND,
        'english_labels_only': True, 'device_rendering_verified': False,
        'runtime_molang_context_verified': False,
        'cleanup_requires_exact_original_normal_pack': True}
