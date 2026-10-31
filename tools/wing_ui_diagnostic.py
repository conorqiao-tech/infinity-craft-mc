"""Pure, copy-only explore.51 r5 wing UI diagnostic and its paired r6 cleanup.

Input is the approved normal r4 pack's member dictionary. ``apply`` returns a
temporary r5 copy that adds one small front-torso board to the native-elytra
attachable. The board is deliberately NOT gated by UI or camera context, because
those contexts are what it measures. It therefore also appears in world views
and on ordinary and held elytra until the r6 cleanup is installed. ``cleanup``
returns a clean r4 copy whose only change is the manifest cache revision.

Nothing here executes Molang, renders pixels, installs a pack or grants owner
approval. A visible board cell is evidence about one render, never a repair.
"""
import copy
import hashlib
import json
import math
from pathlib import Path
import re
import types


TOOLS = Path(__file__).resolve().parent
FROZEN_R2 = 'wing_render_diagnostic.py'
FROZEN_R2_SHA256 = '93892119ddac366157b4b94685ed3d488eb3bb2821e1d937d26b055da4af1e62'
FROZEN_COMBINED_SHA256 = '3a6c36a6ba8194d92122f4f89b595966a7b796e50cb1dfc5da45b759d87db3d5'


def require(value, message):
    if not value:
        raise ValueError(message)


def _load(name, expected_sha256=None):
    """Execute exactly the hashed bytes, so a changed helper is never imported."""
    path = TOOLS/name
    require(path.is_file() and not path.is_symlink(), 'Required helper is missing or linked: '+name)
    data = path.read_bytes()
    if expected_sha256 is not None:
        require(hashlib.sha256(data).hexdigest() == expected_sha256, 'A frozen historical helper changed: '+name)
    module = types.ModuleType('_infinityhub_wing_ui_r5_'+name.removesuffix('.py'))
    module.__file__ = str(path)
    exec(compile(data, str(path), 'exec'), module.__dict__)
    return module


R2 = _load(FROZEN_R2, FROZEN_R2_SHA256)
COMBINED = _load('combined_wing_resources.py', FROZEN_COMBINED_SHA256)


NORMAL_REVISION = (2, 13, 51004)
REVISION = (2, 13, 51005)
CLEANUP_REVISION = (2, 13, 51006)
ORIGINAL_ASSET_COUNTS = (174, 27)  # Original PNG and .geo.json members of approved r4.
ELYTRA = 'attachables/combined_convergence_elytra.json'
SHIELD_ATTACHABLES = ('attachables/held/shield.json', 'attachables/held/catalog_shield.json')
FLAG = 'variable.infinityhub_winged_chest'
INFINITY_FLAG = 'variable.infinityhub_infinity_winged_chest'
PROPERTY = 'infinityhub:winged_chest'
INFINITY_PROPERTY = 'infinityhub:infinity_winged_chest'
PRODUCTION_STATEMENTS = (
    "variable.infinityhub_winged_chest = context.item_slot == 'torso' ? "
    "((context.owning_entity->query.has_property('infinityhub:winged_chest')) ? "
    "(context.owning_entity->query.property('infinityhub:winged_chest')) : 0.0) : 0.0;",
    "variable.infinityhub_infinity_winged_chest = context.item_slot == 'torso' ? "
    "((context.owning_entity->query.has_property('infinityhub:infinity_winged_chest')) ? "
    "(context.owning_entity->query.property('infinityhub:infinity_winged_chest')) : 0.0) : 0.0;",
)
PRODUCTION_VISIBILITY = FLAG+' && (query.is_in_ui || !query.is_first_person)'
TEXTURE = 'textures/convergence/models/armor/armor42/infinity.png'
TEXTURE_SHA256 = 'e7dd7843aaf8e9046a9e0f6c36a6d9728787a61175a8096a56ffeeda5b77d0f1'
TEXTURE_REF = TEXTURE.removesuffix('.png')
ARMOR_MATERIAL = 'armor'
ACCEPTED_GEOMETRIES = (
    'models/entity/original_armor/cuirass.geo.json',
    'models/entity/elemental41/infinity_chestplate.geo.json',
    'models/entity/original_armor/combined_convergence_wings.geo.json',
)

OWNED_TOKEN = 'wing_ui_r5'
GEOMETRY = 'models/entity/diagnostics/wing_ui_r5.geo.json'
CONTROLLERS = 'render_controllers/diagnostics/wing_ui_r5.json'
LEGEND = 'diagnostics/wing_ui_r5.json'
OWNED_ENTRIES = (GEOMETRY, CONTROLLERS, LEGEND)
ALIAS = 'infinityhub_wing_ui_r5'
GEOMETRY_ID = 'geometry.infinityhub.wing_ui_r5'
CONTROLLER = 'controller.render.infinityhub.wing_ui_r5'
ROOT_BONE = 'infinityhub_wing_ui_r5_root'
FRAME_BONE = 'infinityhub_wing_ui_r5_frame'
NOTCH_BONE = 'infinityhub_wing_ui_r5_notch'
CELL_PREFIX = 'infinityhub_wing_ui_r5_cell_'
VARIABLE_PREFIX = 'variable.infinityhub_wing_ui_r5_'
NAME_SUFFIX = ' | TEMPORARY DIAGNOSTIC WING UI R5'
DESCRIPTION_PREFIX = ('TEMPORARY DIAGNOSTIC: a chest test panel also appears in world views and on ordinary '
                      'and held elytra; requires owner approval and the paired 2.13.51006 cleanup. ')

LAYOUT = (('A', 'T', 'S', 'H'), ('P', 'D', 'Q', 'V'), ('U', 'O', 'F', 'R'), ('I', 'J', 'E', 'Z'))
CELLS = tuple(letter for row in LAYOUT for letter in row)
RC_CONDITIONS = {'A': '1.0', 'Z': '0.0', 'V': FLAG, 'I': INFINITY_FLAG, 'E': PRODUCTION_VISIBILITY}
OWNER = 'context.owning_entity->'


def _guarded(receiver, name):
    return ("("+receiver+"query.has_property('"+name+"')) ? "
            "("+receiver+"query.property('"+name+"')) : 0.0")


# Each probe is evaluated independently in the attachable's pre_animation. None
# reads a production flag or another probe variable.
PROBES = {
    'T': '1.0',
    'S': "context.item_slot == 'torso'",
    'H': OWNER+"query.has_property('"+PROPERTY+"')",
    'P': _guarded(OWNER, PROPERTY),
    'D': "query.has_property('"+PROPERTY+"')",
    'Q': _guarded('', PROPERTY),
    'U': 'query.is_in_ui',
    'O': OWNER+'query.is_in_ui',
    'F': 'query.is_first_person',
    'R': OWNER+'query.is_first_person',
    'J': _guarded(OWNER, INFINITY_PROPERTY),
}
PROBE_ORDER = tuple(letter for letter in CELLS if letter in PROBES and letter != 'T') + ('T',)
require(set(PROBES) | set(RC_CONDITIONS) == set(CELLS) and not set(PROBES) & set(RC_CONDITIONS),
        'Every cell needs exactly one evaluation stage')


def variable(letter):
    return VARIABLE_PREFIX+letter.lower()


def cell_bone(letter):
    return CELL_PREFIX+letter.lower()


CONDITIONS = {letter: RC_CONDITIONS.get(letter, variable(letter)) for letter in CELLS}
PROBE_STATEMENTS = tuple(variable(letter)+' = '+PROBES[letter]+';' for letter in PROBE_ORDER)
STAGES = {letter: ('render_controller.literal' if letter in ('A', 'Z')
                   else 'render_controller.part_visibility' if letter in RC_CONDITIONS
                   else 'attachable.pre_animation.sentinel' if letter == 'T'
                   else 'attachable.pre_animation') for letter in CELLS}
MEANINGS = {
    'A': 'Literal positive control written directly in the render controller.',
    'T': 'A constant assigned after all query probes in pre_animation reached this render; freshness is unproved.',
    'S': "context.item_slot == 'torso' was true in pre_animation.",
    'H': 'The owning entity reported infinityhub:winged_chest through has_property.',
    'P': 'The guarded owning-entity infinityhub:winged_chest value read true.',
    'D': 'The plain attachable query reported infinityhub:winged_chest through has_property.',
    'Q': 'The guarded plain-query infinityhub:winged_chest value read true.',
    'V': 'The existing production primary flag read true in the render controller.',
    'U': 'Plain query.is_in_ui read true in pre_animation.',
    'O': 'Owning-entity query.is_in_ui read true in pre_animation.',
    'F': 'Plain query.is_first_person read true in pre_animation.',
    'R': 'Owning-entity query.is_first_person read true in pre_animation.',
    'I': 'The existing production Infinity flag read true in the render controller.',
    'J': 'The guarded owning-entity infinityhub:infinity_winged_chest value read true.',
    'E': 'The exact existing production body/wing visibility predicate permitted this render.',
    'Z': 'Literal negative control: MUST be absent, otherwise every reading is invalid.',
}
FALSE_READINGS = {
    'S': 'Only means the torso equality was false; it does not reveal the raw slot string or whether one exists.',
    'H': 'Ambiguous: the owner may be unresolved, or the property schema may be unavailable to this query.',
    'P': 'The value was false, absent or unreadable; it does not identify which.',
    'D': 'Ambiguous: the plain query may not reach the wearer, or the schema may be unavailable.',
    'Q': 'The value was false, absent or unreadable; it does not identify which.',
    'U': 'A pre_animation reading; it does not prove the render-controller-scope value.',
    'O': 'A pre_animation reading; it does not prove the render-controller-scope value.',
    'F': 'A pre_animation reading; it does not prove the render-controller-scope value.',
    'R': 'A pre_animation reading; it does not prove the render-controller-scope value.',
    'J': 'The value was false, absent or unreadable; it does not identify which.',
}

# Board geometry in player model units. Front is -Z (the accepted wings sit at
# +Z). Column 1 is -X, the wearer's right, seen on the viewer's left from the front.
X_LIMITS, Y_LIMITS = (-2.5, 2.5), (15.0, 21.0)
REAR_Z = -3.2            # Backplate rear plane; accepted fronts are -2.42 and -3.05.
PLATE_Z = -3.45          # Backplate front plane = rear plane of every lit cube.
FRONT_Z = -3.7
LIT_MAX_Z = -3.25
BODY_SCALE_MARGIN = 1.067
MIN_CLEARANCE = 0.1
MIN_SEPARATION = 0.1
BOARD = (-2.5, 2.5, 15.0, 20.4)
FRAME_WIDTH, LINE_WIDTH, WELL_SIZE, CELL_INSET = 0.3, 0.2, (0.95, 1.05), 0.15
NOTCH = (-2.5, -1.6, 20.4, 21.0)
FACES = ('north', 'south', 'east', 'west', 'up', 'down')
PIVOT = [0, 24, 0]
EPS = 1e-6
MAX_JSON = 2*1024*1024


def parsed(raw):
    """Bounded, strict UTF-8 JSON object with duplicate and non-finite rejection."""
    require(isinstance(raw, bytes) and 0 < len(raw) <= MAX_JSON, 'Missing or oversized JSON member')

    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result

    def constant(name):
        raise ValueError('Non-finite JSON number: '+name)

    try:
        value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique, parse_constant=constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError('Invalid UTF-8 JSON member') from error
    require(isinstance(value, dict), 'Expected a JSON object')
    return value


def typed(value):
    """Order- and type-exact JSON identity: 1, 1.0 and true are all different."""
    return json.dumps(value, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def encoded(value):
    return R2.encoded(value)


def _number(value):
    return type(value) in (int, float) and math.isfinite(value)


def _versions(manifest):
    try:
        modules = manifest['modules']
        require(isinstance(modules, list) and modules, 'Pack manifest needs modules')
        return [manifest['header']['version'], *(module['version'] for module in modules)]
    except (KeyError, TypeError) as error:
        raise ValueError('Malformed pack manifest') from error


def _require_revision(manifest, expected):
    require(all(isinstance(v, list) and len(v) == 3 and all(type(n) is int for n in v)
                and tuple(v) == tuple(expected) for v in _versions(manifest)),
            'Pack header and every module must use the exact phase revision')


def _with_revision(manifest, revision):
    result = copy.deepcopy(manifest)
    result['header']['version'] = list(revision)
    for module in result['modules']:
        module['version'] = list(revision)
    return result


def _description(files):
    value = parsed(files[ELYTRA])
    try:
        description = value['minecraft:attachable']['description']
        require(isinstance(description, dict) and description.get('identifier') == 'minecraft:elytra',
                'The native elytra attachable identity changed')
        for key in ('materials', 'textures', 'geometry', 'animations', 'scripts'):
            require(isinstance(description[key], dict), 'Malformed native elytra attachable: '+key)
        require(isinstance(description['render_controllers'], list), 'Malformed native elytra renderers')
    except (KeyError, TypeError) as error:
        raise ValueError('Malformed native elytra attachable') from error
    return value, description


def _texels(files):
    data = files.get(TEXTURE)
    require(isinstance(data, bytes) and hashlib.sha256(data).hexdigest() == TEXTURE_SHA256
            and R2.TEXTURE_SHA256 == TEXTURE_SHA256, 'The accepted Infinity texture changed')
    width, height, pixels = R2.rgba_pixels(data)
    require((width, height) == (128, 64), 'Accepted Infinity texture dimensions changed')
    opaque = []
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[(y*width+x)*4:(y*width+x)*4+4]
            if a == 255:
                opaque.append((x, y, r, g, b))
    require(opaque, 'Accepted texture has no fully opaque texels')
    luminance = lambda t: (299*t[2]+587*t[3]+114*t[4])//1000
    gold = max(opaque, key=lambda t: (t[2]+t[3]-2*t[4], -t[1], -t[0]))
    dark = min(opaque, key=lambda t: (luminance(t), t[1], t[0]))
    require(gold[2] >= 150 and gold[3] >= 100 and gold[2]+gold[3]-2*gold[4] >= 150,
            'Accepted texture lacks an opaque bright gold texel')
    require(luminance(dark) <= 64 and luminance(gold)-luminance(dark) >= 100,
            'Accepted texture lacks an opaque dark texel with sufficient contrast')
    for texel in (gold, dark):
        R2.opaque_rectangle(data, (texel[0], texel[1], 1, 1))
    describe = lambda t: {'uv': [t[0], t[1]], 'rgba': [t[2], t[3], t[4], 255], 'luminance': luminance(t)}
    return {'texture': TEXTURE, 'sha256': TEXTURE_SHA256, 'width': width, 'height': height,
            'gold': describe(gold), 'dark': describe(dark), 'alpha_fully_opaque': True}


def _accepted_boxes(files):
    boxes = []
    for name in ACCEPTED_GEOMETRIES:
        value = parsed(files[name])
        try:
            for geometry in value['minecraft:geometry']:
                for bone in geometry['bones']:
                    require(not any(bone.get('rotation', [0, 0, 0])), 'Accepted geometry bone rotation is not modeled')
                    for cube in bone.get('cubes', []):
                        require('rotation' not in cube, 'Accepted cube rotation is not modeled')
                        inflate = cube.get('inflate', 0)
                        o, s = cube['origin'], cube['size']
                        require(_number(inflate) and all(_number(n) for n in (*o, *s)) and len(o) == len(s) == 3,
                                'Malformed accepted geometry cube')
                        boxes.append(tuple(v for axis in range(3)
                                           for v in (o[axis]-inflate, o[axis]+s[axis]+inflate)))
        except (KeyError, TypeError) as error:
            raise ValueError('Malformed accepted geometry: '+name) from error
    require(boxes, 'Accepted chest geometry has no cubes')
    return boxes


def _normal(files, expected_asset_counts=ORIGINAL_ASSET_COUNTS):
    """Validate the exact approved r4 member set and return its derived context."""
    R2.checked_files(files)
    require(not any(OWNED_TOKEN in name or 'infinityhub_diag_r2' in name or 'wing_render_r2' in name for name in files),
            'The normal pack already contains diagnostic resources; use the clean r4 original')
    require(not any(name in files for name in SHIELD_ATTACHABLES), 'The normal native shield fallback changed')
    for name, data in files.items():
        if name.endswith(('.json', '.lang')):
            require(OWNED_TOKEN.encode() not in data and b'infinityhub_diag_r2' not in data,
                    'A normal resource already references diagnostic aliases: '+name)
    manifest = parsed(files['manifest.json'])
    _require_revision(manifest, NORMAL_REVISION)
    header = manifest['header']
    require(isinstance(header.get('name'), str) and isinstance(header.get('description'), str)
            and 'TEMPORARY' not in header['name'] and 'TEMPORARY' not in header['description'],
            'Normal r4 manifest identity is malformed or already marked temporary')
    value, description = _description(files)
    require(typed(description['scripts'].get('pre_animation')) == typed(list(PRODUCTION_STATEMENTS)),
            'The two production r4 pre_animation statements must be string-exact')
    require(not any(ALIAS in description[key] for key in ('materials', 'textures', 'geometry'))
            and CONTROLLER not in description['render_controllers'],
            'The normal attachable already contains owned diagnostic aliases')
    require(description['materials'].get('convergence_armor') == ARMOR_MATERIAL
            and description['textures'].get('infinity_armor') == TEXTURE_REF,
            'The accepted armor material or Infinity texture alias changed')
    try:
        COMBINED.validate(files)
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError('Normal combined-wing resources are incomplete') from error
    counts = (sum(n.endswith('.png') for n in files), sum(n.endswith('.geo.json') for n in files))
    require(expected_asset_counts is None or counts == tuple(expected_asset_counts),
            'Unexpected original PNG or geometry member count')
    return {'manifest': manifest, 'attachable': value, 'texels': _texels(files),
            'accepted_boxes': _accepted_boxes(files), 'counts': counts}


def _round(value):
    value = round(value, 4)
    return 0.0 if value == 0 else value


def _face_uv(texel):
    return {face: {'uv': list(texel['uv']), 'uv_size': [1, 1]} for face in FACES}


def _cube(x0, x1, y0, y1, z0, z1, texel):
    return {'origin': [_round(x0), _round(y0), _round(z0)],
            'size': [_round(x1-x0), _round(y1-y0), _round(z1-z0)], 'uv': _face_uv(texel)}


def _wells():
    """Row-major cell wells: (x0, x1, y0, y1), row 1 at the top, column 1 at -X."""
    (bx0, bx1, by0, by1), (w, h) = BOARD, WELL_SIZE
    left, top = bx0+FRAME_WIDTH, by1-FRAME_WIDTH
    return [(left+c*(w+LINE_WIDTH), left+c*(w+LINE_WIDTH)+w, top-r*(h+LINE_WIDTH)-h, top-r*(h+LINE_WIDTH))
            for r in range(4) for c in range(4)]


def geometry(texels):
    gold, dark = texels['gold'], texels['dark']
    bx0, bx1, by0, by1 = BOARD
    ix0, ix1, iy0, iy1 = bx0+FRAME_WIDTH, bx1-FRAME_WIDTH, by0+FRAME_WIDTH, by1-FRAME_WIDTH
    wells = _wells()
    front = lambda x0, x1, y0, y1: _cube(x0, x1, y0, y1, FRONT_Z, PLATE_Z, gold)
    frame = [front(bx0, bx1, iy1, by1), front(bx0, bx1, by0, iy0),
             front(bx0, ix0, iy0, iy1), front(ix1, bx1, iy0, iy1)]
    for r in range(3):  # Full-width gold lines separate the four rows.
        y0 = wells[r*4][2]
        frame.append(front(ix0, ix1, y0-LINE_WIDTH, y0))
    for r in range(4):  # Per-row gold segments separate the four columns.
        for c in range(3):
            x0, (_, _, y0, y1) = wells[r*4+c][1], wells[r*4]
            frame.append(front(x0, x0+LINE_WIDTH, y0, y1))
    cells = [{'name': cell_bone(letter), 'parent': ROOT_BONE, 'pivot': list(PIVOT),
              'cubes': [front(x0+CELL_INSET, x1-CELL_INSET, y0+CELL_INSET, y1-CELL_INSET)]}
             for letter, (x0, x1, y0, y1) in zip(CELLS, wells)]
    bones = [{'name': 'body', 'pivot': list(PIVOT)},
             {'name': ROOT_BONE, 'parent': 'body', 'pivot': list(PIVOT),
              'cubes': [_cube(bx0, bx1, by0, by1, PLATE_Z, REAR_Z, dark)]},
             {'name': FRAME_BONE, 'parent': ROOT_BONE, 'pivot': list(PIVOT), 'cubes': frame},
             {'name': NOTCH_BONE, 'parent': ROOT_BONE, 'pivot': list(PIVOT), 'cubes': [front(*NOTCH)]},
             *cells]
    return {'format_version': '1.19.30', 'minecraft:geometry': [{
        'description': {'identifier': GEOMETRY_ID, 'texture_width': texels['width'],
                        'texture_height': texels['height'], 'visible_bounds_width': 2.5,
                        'visible_bounds_height': 3, 'visible_bounds_offset': [0, 1, 0]},
        'bones': bones}]}


def controller():
    visibility = [{'body': '1.0'}, {ROOT_BONE: '1.0'}, {FRAME_BONE: '1.0'}, {NOTCH_BONE: '1.0'},
                  *({cell_bone(letter): CONDITIONS[letter]} for letter in CELLS)]
    return {'format_version': '1.8.0', 'render_controllers': {CONTROLLER: {
        'geometry': 'Geometry.'+ALIAS, 'materials': [{'*': 'Material.'+ALIAS}],
        'textures': ['Texture.'+ALIAS], 'part_visibility': visibility}}}


def legend(texels):
    return {
        'format': 1, 'diagnostic': 'temporary_wing_ui_r5',
        'version': list(REVISION), 'normal_version': list(NORMAL_REVISION), 'cleanup_version': list(CLEANUP_REVISION),
        'temporary': True, 'owner_approval_required': True,
        'live_installation_approval_received': False, 'live_installation_performed': False,
        'device_rendering_verified': False, 'runtime_molang_context_verified': False,
        'fresh_ui_pre_animation_execution_verified': False,
        'raw_slot_string_observed': False, 'panel_presence_fixes_ui': False,
        'world_panel_visible': True, 'ordinary_elytra_panel': True, 'held_elytra_panel': True,
        'ui_or_camera_gated': False, 'helmet_control': False, 'language_changes': False,
        'native_gameplay_changes': False, 'artwork_changes': False,
        'validity_gate': {
            'required_visible': ['frame', 'notch', 'A', 'T'], 'required_absent': ['Z'],
            'otherwise': 'Uninterpretable: record the observation but read no signal cell.',
            'panel_absent_in_ui_but_visible_in_world': (
                'No signal interpretation. The added pass, geometry or UI route is unresolved; this is not proof '
                'that every query is false. The normal r4 gray pass may still bypass the custom attachable in the UI.'),
            'pre_animation_snapshot_caveat': (
                'T proves that an assigned value reached this render, not that pre_animation executed freshly '
                'for this UI actor. UI cloning or caching could carry earlier values. Pre-animation cells are '
                'snapshots of the values reaching the renderer; V, I and E are direct render-controller reads.'),
        },
        'reading': {
            'view': 'Front only. The body and accepted wings occlude the board from behind.',
            'orientation': ('The gold notch marks the A corner, above row 1. From the front, unmirrored, the notch '
                            'is top-left. If it appears top-right the image is mirrored: read columns from the notch side.'),
            'coordinate_frame': 'Player model space; front is -Z; column 1 is -X, the wearer\'s right.',
            'lit_cell': 'Gold square inside a dark well.',
            'off_cell': 'Dark well enclosed by the permanent gold grid; distinguishable from occlusion of the board.',
            'first_person': 'Production first-person hiding is unchanged; whether this board is drawn there is unknown.',
        },
        'geometry': {'identifier': GEOMETRY_ID, 'parent_bone': 'body', 'pivot': list(PIVOT),
                     'x_limits': list(X_LIMITS), 'y_limits': list(Y_LIMITS), 'rear_z': REAR_Z,
                     'lit_rear_z': PLATE_Z, 'lit_front_z': FRONT_Z,
                     'native_wing_occlusion_ruled_out': False, 'body_scale_margin': BODY_SCALE_MARGIN},
        'texels': copy.deepcopy(texels),
        'notch': {'bone': NOTCH_BONE, 'condition': '1.0', 'corner_of': 'A', 'independent_of_cell': True},
        'frame': {'bones': [ROOT_BONE, FRAME_BONE], 'condition': '1.0'},
        'cells': [{'label': letter, 'row': index//4+1, 'column': index%4+1, 'bone': cell_bone(letter),
                   'condition': CONDITIONS[letter], 'evaluation_stage': STAGES[letter],
                   'probe_expression': PROBES.get(letter, CONDITIONS[letter]),
                   'meaning': MEANINGS[letter], 'false_reading': FALSE_READINGS.get(letter)}
                  for index, letter in enumerate(CELLS)],
    }


def apply(normal_files, *, expected_asset_counts=ORIGINAL_ASSET_COUNTS):
    """Return a new r5 member dictionary; never modify the normal input."""
    context = _normal(normal_files, expected_asset_counts)
    result = dict(normal_files)
    manifest = _with_revision(context['manifest'], REVISION)
    manifest['header']['name'] += NAME_SUFFIX
    manifest['header']['description'] = DESCRIPTION_PREFIX+manifest['header']['description']
    result['manifest.json'] = encoded(manifest)
    value = copy.deepcopy(context['attachable'])
    description = value['minecraft:attachable']['description']
    description['materials'][ALIAS] = ARMOR_MATERIAL
    description['textures'][ALIAS] = TEXTURE_REF
    description['geometry'][ALIAS] = GEOMETRY_ID
    description['scripts']['pre_animation'].extend(PROBE_STATEMENTS)
    description['render_controllers'].append(CONTROLLER)
    result[ELYTRA] = encoded(value)
    result[GEOMETRY] = encoded(geometry(context['texels']))
    result[CONTROLLERS] = encoded(controller())
    result[LEGEND] = encoded(legend(context['texels']))
    return result


build_overlay = apply


def cleanup(normal_files, *, expected_asset_counts=ORIGINAL_ASSET_COUNTS):
    """Return a clean r4 copy whose only change is the manifest revision."""
    context = _normal(normal_files, expected_asset_counts)
    result = dict(normal_files)
    result['manifest.json'] = encoded(_with_revision(context['manifest'], CLEANUP_REVISION))
    return result


def _box(cube):
    require(isinstance(cube, dict) and set(cube) == {'origin', 'size', 'uv'}, 'Unexpected diagnostic cube fields')
    o, s = cube['origin'], cube['size']
    require(all(isinstance(v, list) and len(v) == 3 and all(_number(n) for n in v) for v in (o, s))
            and all(n > 0 for n in s), 'Diagnostic cube needs finite positive dimensions')
    return (o[0], o[0]+s[0], o[1], o[1]+s[1], o[2], o[2]+s[2])


def _overlap_xy(a, b, margin=0.0):
    return (a[0] < b[1]+margin-EPS and b[0] < a[1]+margin-EPS
            and a[2] < b[3]+margin-EPS and b[2] < a[3]+margin-EPS)


def _overlap_3d(a, b):
    return _overlap_xy(a, b) and a[4] < b[5]-EPS and b[4] < a[5]-EPS


def _close(a, b):
    return abs(a-b) <= EPS


def _validate_uv(cube, texel, role):
    uv = cube['uv']
    require(isinstance(uv, dict) and list(uv) == list(FACES)
            and all(typed(uv[face]) == typed({'uv': texel['uv'], 'uv_size': [1, 1]}) for face in FACES),
            'Diagnostic '+role+' must sample its opaque accepted texel on every face')


def _validate_geometry(raw, context):
    texels, value = context['texels'], parsed(raw)
    require(set(value) == {'format_version', 'minecraft:geometry'} and value['format_version'] == '1.19.30'
            and isinstance(value['minecraft:geometry'], list) and len(value['minecraft:geometry']) == 1,
            'Diagnostic geometry must define exactly one model')
    model = value['minecraft:geometry'][0]
    require(isinstance(model, dict) and set(model) == {'description', 'bones'}
            and typed(model['description']) == typed(geometry(texels)['minecraft:geometry'][0]['description']),
            'Diagnostic geometry identity, atlas size or bounds changed')
    bones = model['bones']
    expected_names = ['body', ROOT_BONE, FRAME_BONE, NOTCH_BONE, *map(cell_bone, CELLS)]
    require(isinstance(bones, list) and [b.get('name') if isinstance(b, dict) else None for b in bones] == expected_names,
            'Diagnostic bones must be body, root, frame, notch and the 16 row-major cells, once each')
    parents = {'body': None, ROOT_BONE: 'body', FRAME_BONE: ROOT_BONE, NOTCH_BONE: ROOT_BONE,
               **{cell_bone(letter): ROOT_BONE for letter in CELLS}}
    by_name = {}
    for bone in bones:
        name = bone['name']
        allowed = {'name', 'pivot'} | ({'parent'} if parents[name] else set()) | ({'cubes'} if name != 'body' else set())
        require(set(bone) == allowed and bone.get('parent') == parents[name] and typed(bone['pivot']) == typed(PIVOT),
                'Diagnostic bone parent, pivot or pose changed: '+name)
        by_name[name] = [_box(cube) for cube in bone.get('cubes', [])]
    require(len(by_name[ROOT_BONE]) == 1 and len(by_name[NOTCH_BONE]) == 1
            and all(len(by_name[cell_bone(letter)]) == 1 for letter in CELLS) and len(by_name[FRAME_BONE]) >= 4,
            'Diagnostic backplate, notch and every cell need exactly one cube')
    for name, role, texel in ((ROOT_BONE, 'backplate', 'dark'), (FRAME_BONE, 'frame', 'gold'),
                              (NOTCH_BONE, 'notch', 'gold'), *((cell_bone(l), 'cell', 'gold') for l in CELLS)):
        for cube in next(b for b in bones if b['name'] == name)['cubes']:
            _validate_uv(cube, texels[texel], role)
    plate = by_name[ROOT_BONE][0]
    frame, notch = by_name[FRAME_BONE], by_name[NOTCH_BONE][0]
    cells = [by_name[cell_bone(letter)][0] for letter in CELLS]
    lit = [*frame, notch, *cells]
    every = [plate, *lit]
    # Footprint and depth: the dark backplate is the rearmost plane, and every
    # lit cube lies entirely in front of it, so the board cannot hide a cell.
    require(all(X_LIMITS[0]-EPS <= b[0] and b[1] <= X_LIMITS[1]+EPS
                and Y_LIMITS[0]-EPS <= b[2] and b[3] <= Y_LIMITS[1]+EPS for b in every),
            'Diagnostic board leaves its front-torso footprint')
    require(_close(plate[5], REAR_Z) and all(b[5] <= plate[4]+EPS for b in lit)
            and all(b[5] <= LIT_MAX_Z+EPS for b in lit) and max(b[5] for b in every) <= REAR_Z+EPS,
            'Diagnostic lit cubes must sit in front of the rearmost dark backplate')
    require(len({(c[4], c[5]) for c in cells}) == 1, 'All diagnostic cells must share one depth')
    require(all(plate[0]-EPS <= b[0] and b[1] <= plate[1]+EPS and plate[2]-EPS <= b[2] and b[3] <= plate[3]+EPS
                for b in [*frame, *cells]), 'The dark backplate must back the whole frame and grid')
    accepted_min_z = min(box[4] for box in context['accepted_boxes'])
    require(REAR_Z <= accepted_min_z-MIN_CLEARANCE
            and all(b[5] <= accepted_min_z*BODY_SCALE_MARGIN-MIN_CLEARANCE for b in lit)
            and not any(_overlap_3d(b, box) for b in every for box in context['accepted_boxes']),
            'Diagnostic board must clear every accepted chest, sleeve and wing cube')
    # Row-major layout: four rows by descending Y, four columns by ascending X.
    centers = [((c[0]+c[1])/2, (c[2]+c[3])/2) for c in cells]
    ys = sorted({round(y, 4) for _, y in centers}, reverse=True)
    xs = sorted({round(x, 4) for x, _ in centers})
    require(len(ys) == 4 and len(xs) == 4 and all(
        round(centers[i][1], 4) == ys[i//4] and round(centers[i][0], 4) == xs[i%4] for i in range(16)),
        'Diagnostic cells must form the exact row-major 4x4 layout')
    require(all(not _overlap_xy(a, b, MIN_SEPARATION) for i, a in enumerate(cells) for b in cells[i+1:]),
            'Diagnostic cells must be clearly separated cubes')
    require(all(not _overlap_xy(c, b, MIN_SEPARATION) for c in cells for b in [*frame, notch]),
            'Every cell needs a visible dark ring inside the gold grid')
    # Permanent frame on all four sides and grid lines between rows and columns.
    lo_x, hi_x = min(c[0] for c in cells), max(c[1] for c in cells)
    lo_y, hi_y = min(c[2] for c in cells), max(c[3] for c in cells)
    sides = (lambda b: _close(b[0], plate[0]) and _close(b[1], plate[1]) and _close(b[2], plate[2]),
             lambda b: _close(b[0], plate[0]) and _close(b[1], plate[1]) and _close(b[3], plate[3]),
             lambda b: _close(b[0], plate[0]) and b[2] <= lo_y+EPS and b[3] >= hi_y-EPS,
             lambda b: _close(b[1], plate[1]) and b[2] <= lo_y+EPS and b[3] >= hi_y-EPS)
    require(all(any(side(b) for b in frame) for side in sides), 'The permanent gold frame needs all four sides')
    for r in range(3):
        upper, lower = cells[r*4:r*4+4], cells[r*4+4:r*4+8]
        require(any(b[2] >= max(c[3] for c in lower)-EPS and b[3] <= min(c[2] for c in upper)+EPS
                    and b[0] <= lo_x+EPS and b[1] >= hi_x-EPS for b in frame),
                'A full-width gold grid line must separate every pair of rows')
    for r in range(4):
        for c in range(3):
            left, right = cells[r*4+c], cells[r*4+c+1]
            require(any(b[0] >= left[1]-EPS and b[1] <= right[0]+EPS and b[2] <= min(left[2], right[2])+EPS
                        and b[3] >= max(left[3], right[3])-EPS for b in frame),
                    'A gold grid segment must separate every pair of columns')
    # Orientation notch: outside the grid, above row 1, on the A (-X) corner,
    # independent of the A cell and not touching the T column.
    a_cell, t_cell = cells[0], cells[1]
    require(notch[2] >= max(b[3] for b in frame)-EPS and (notch[0]+notch[1])/2 < 0
            and notch[0] < a_cell[1]-EPS and a_cell[0] < notch[1]-EPS and notch[1] < t_cell[0]-EPS
            and not _overlap_xy(notch, a_cell, MIN_SEPARATION),
            'The orientation notch must mark only the A corner, separately from the A cell')


def _validate_controller(raw):
    value = parsed(raw)
    require(typed(value) == typed(controller()), 'Diagnostic render controller differs from its exact contract')
    entry = value['render_controllers'][CONTROLLER]
    visibility = entry['part_visibility']
    require(all(isinstance(part, dict) and len(part) == 1 for part in visibility), 'One bone per visibility entry')
    flat = [next(iter(part.items())) for part in visibility]
    bones = [bone for bone, _ in flat]
    require(len(bones) == len(set(bones)) == 20 and '*' not in bones, 'Diagnostic visibility bones must be unique')
    conditions = dict(flat)
    require(all(conditions[b] == '1.0' for b in ('body', ROOT_BONE, FRAME_BONE, NOTCH_BONE)),
            'The board root, frame and notch must be literal, ungated controls')
    require(conditions[cell_bone('A')] == '1.0' and conditions[cell_bone('Z')] == '0.0',
            'A must be literal 1.0 and Z literal 0.0 in the render controller')
    require(conditions[cell_bone('E')] == PRODUCTION_VISIBILITY
            and conditions[cell_bone('V')] == FLAG and conditions[cell_bone('I')] == INFINITY_FLAG,
            'V, I and E must read the exact existing production expressions')
    others = [c for b, c in flat if b != cell_bone('E')]
    require(not any('is_in_ui' in c or 'is_first_person' in c for c in others),
            'Only the exact production predicate cell may mention UI or camera context')
    for letter in PROBE_ORDER:
        require(conditions[cell_bone(letter)] == variable(letter), 'Probe cell must read its own probe variable')


def _validate_probe(statement, letter):
    prefix = variable(letter)+' = '
    require(isinstance(statement, str) and statement.startswith(prefix) and statement.endswith(';'),
            'Malformed probe assignment')
    expression = statement[len(prefix):-1]
    require(expression == PROBES[letter], 'Probe condition changed: '+letter)
    require(not re.search(r'\b(?:variable|v|temp|t)\.', expression),
            'Probe assignments cannot depend on production flags or other probes')
    if 'query.property(' in expression:
        match = re.fullmatch(r"\((?P<r>(?:context\.owning_entity->)?)query\.has_property\('(?P<p>[^']+)'\)\) \? "
                             r"\((?P=r)query\.property\('(?P=p)'\)\) : 0\.0", expression)
        require(match is not None, 'Every property read needs its own matching has_property guard')


def _strip_attachable(diagnostic, normal):
    """Remove exactly the owned additions, then require the parsed original."""
    value = copy.deepcopy(diagnostic)
    try:
        description = value['minecraft:attachable']['description']
        for key, expected in (('materials', ARMOR_MATERIAL), ('textures', TEXTURE_REF), ('geometry', GEOMETRY_ID)):
            section = description[key]
            require(list(section)[-1] == ALIAS and section[ALIAS] == expected,
                    'Owned '+key+' alias must be appended exactly once')
            del section[ALIAS]
        statements = description['scripts']['pre_animation']
        require(isinstance(statements, list) and typed(statements[:2]) == typed(list(PRODUCTION_STATEMENTS))
                and len(statements) == 2+len(PROBE_ORDER), 'Production statements must stay first and exact')
        for statement, letter in zip(statements[2:], PROBE_ORDER):
            _validate_probe(statement, letter)
        require(len({s for s in statements[2:]}) == len(PROBE_ORDER)
                and len({s.split(' = ', 1)[0] for s in statements[2:]}) == len(PROBE_ORDER),
                'Probe assignments must be unique')
        del statements[2:]
        renderers = description['render_controllers']
        require(renderers and renderers[-1] == CONTROLLER and type(renderers[-1]) is str
                and renderers.count(CONTROLLER) == 1, 'The diagnostic controller must be one unconditional string')
        renderers.pop()
    except (KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('Malformed diagnostic attachable') from error
    require(typed(value) == typed(normal), 'Removing the owned additions must restore the exact r4 attachable')


def validate_contract(normal_files, diagnostic_files, *, expected_asset_counts=ORIGINAL_ASSET_COUNTS):
    """Structural r5 checks that do not rely on reproducing apply()."""
    context = _normal(normal_files, expected_asset_counts)
    R2.checked_files(diagnostic_files)
    require(diagnostic_files.keys() == normal_files.keys() | set(OWNED_ENTRIES)
            and not set(OWNED_ENTRIES) & normal_files.keys(), 'Diagnostic adds or removes an unowned member')
    changed = sorted(n for n in normal_files if diagnostic_files[n] != normal_files[n])
    require(changed == sorted(('manifest.json', ELYTRA)), 'Only the manifest and native elytra attachable may change')
    manifest = parsed(diagnostic_files['manifest.json'])
    _require_revision(manifest, REVISION)
    try:
        name, text = manifest['header']['name'], manifest['header']['description']
        require(isinstance(name, str) and isinstance(text, str)
                and name == context['manifest']['header']['name']+NAME_SUFFIX
                and text == DESCRIPTION_PREFIX+context['manifest']['header']['description'],
                'Diagnostic manifest must explicitly mark its temporary panels')
        normalized = _with_revision(manifest, NORMAL_REVISION)
        normalized['header']['name'] = context['manifest']['header']['name']
        normalized['header']['description'] = context['manifest']['header']['description']
    except (KeyError, TypeError) as error:
        raise ValueError('Malformed diagnostic manifest') from error
    require(typed(normalized) == typed(context['manifest']), 'Diagnostic manifest changed another field or type')
    _strip_attachable(parsed(diagnostic_files[ELYTRA]), context['attachable'])
    _validate_geometry(diagnostic_files[GEOMETRY], context)
    _validate_controller(diagnostic_files[CONTROLLERS])
    record = parsed(diagnostic_files[LEGEND])
    require(typed(record) == typed(legend(context['texels'])), 'Diagnostic legend differs from its exact contract')
    for key, expected in (('temporary', True), ('owner_approval_required', True), ('world_panel_visible', True),
                          ('ordinary_elytra_panel', True), ('held_elytra_panel', True),
                          ('live_installation_approval_received', False), ('live_installation_performed', False),
                          ('device_rendering_verified', False), ('runtime_molang_context_verified', False),
                          ('fresh_ui_pre_animation_execution_verified', False),
                          ('ui_or_camera_gated', False), ('helmet_control', False), ('language_changes', False),
                          ('native_gameplay_changes', False), ('raw_slot_string_observed', False)):
        require(record.get(key) is expected, 'Legend scope flag must be the typed value: '+key)
    return context


def validate_overlay(normal_files, diagnostic_files, *, expected_asset_counts=ORIGINAL_ASSET_COUNTS):
    """Reject every delta outside the exact r5 overlay; return a receipt."""
    context = validate_contract(normal_files, diagnostic_files, expected_asset_counts=expected_asset_counts)
    expected = apply(normal_files, expected_asset_counts=expected_asset_counts)
    require(expected.keys() == diagnostic_files.keys()
            and all(diagnostic_files[name] == data for name, data in expected.items()),
            'Diagnostic differs from the exact deterministic r5 overlay')
    unchanged = [n for n in normal_files if n not in ('manifest.json', ELYTRA)]
    return {
        'format': 1, 'diagnostic': 'temporary_wing_ui_r5', 'version': list(REVISION),
        'normal_version': list(NORMAL_REVISION), 'cleanup_version': list(CLEANUP_REVISION),
        'added_entries': sorted(OWNED_ENTRIES), 'changed_entries': sorted(('manifest.json', ELYTRA)),
        'deleted_entries': [], 'unchanged_entry_count': len(unchanged),
        'original_png_entries_unchanged': context['counts'][0],
        'original_geometry_entries_unchanged': context['counts'][1],
        'production_pre_animation_preserved_first': True, 'stripped_attachable_equals_normal': True,
        'cells': [{'label': l, 'condition': CONDITIONS[l], 'evaluation_stage': STAGES[l]} for l in CELLS],
        'validity_controls': {'visible': ['frame', 'notch', 'A', 'T'], 'absent': ['Z']},
        'texels': copy.deepcopy(context['texels']),
        'temporary': True, 'world_panel_visible': True, 'ordinary_elytra_panel': True, 'held_elytra_panel': True,
        'ui_or_camera_gated': False, 'owner_approval_required': True,
        'live_installation_approval_received': False, 'live_installation_performed': False,
        'device_rendering_verified': False, 'runtime_molang_context_verified': False,
        'fresh_ui_pre_animation_execution_verified': False,
    }


def validate_cleanup(normal_files, clean_files, *, expected_asset_counts=ORIGINAL_ASSET_COUNTS):
    """Independently require r4 members with only the manifest revision raised."""
    context = _normal(normal_files, expected_asset_counts)
    R2.checked_files(clean_files)
    require(clean_files.keys() == normal_files.keys(), 'Cleanup must contain exactly the normal r4 members')
    require(all(clean_files[n] == data for n, data in normal_files.items() if n != 'manifest.json'),
            'Cleanup changed a member other than the manifest')
    require(not any(OWNED_TOKEN in name for name in clean_files)
            and not any(OWNED_TOKEN.encode() in data or ALIAS.encode() in data
                        for name, data in clean_files.items() if name.endswith(('.json', '.lang'))),
            'Cleanup retains diagnostic resources, aliases or probe variables')
    manifest = parsed(clean_files['manifest.json'])
    _require_revision(manifest, CLEANUP_REVISION)
    require(typed(_with_revision(manifest, NORMAL_REVISION)) == typed(context['manifest']),
            'Cleanup manifest changed its UUID, name, description, another field or a type')
    require(clean_files['manifest.json'] == encoded(_with_revision(context['manifest'], CLEANUP_REVISION)),
            'Cleanup manifest encoding differs from the deterministic r6 manifest')
    return {
        'format': 1, 'cleanup': 'wing_ui_r6', 'version': list(CLEANUP_REVISION),
        'normal_version': list(NORMAL_REVISION), 'changed_entries': ['manifest.json'],
        'added_entries': [], 'deleted_entries': [], 'unchanged_entry_count': len(normal_files)-1,
        'manifest_only_version_delta_from_normal': True, 'owned_diagnostic_resources_absent': True,
        'pack_bytes_identical_to_normal': False,
        'original_png_entries_unchanged': context['counts'][0],
        'original_geometry_entries_unchanged': context['counts'][1],
        'device_rendering_verified': False, 'live_installation_performed': False,
        'owner_approval_required': True, 'live_installation_approval_received': False,
    }
