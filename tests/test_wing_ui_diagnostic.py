"""Independent resource-closure checks; these do not execute Bedrock Molang."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
import wing_ui_diagnostic as probe

ELYTRA = 'attachables/combined_convergence_elytra.json'


def encode(value):
    return (json.dumps(value, indent=2, allow_nan=False) + '\n').encode()


def typed(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


class WingUiDiagnosticTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assets = ROOT / 'bedrock/resource_pack'
        cls.source = {p.relative_to(assets).as_posix(): p.read_bytes()
                      for p in assets.rglob('*') if p.is_file()}
        for name in ('attachables/elytra.json', 'attachables/held/shield.json',
                     'attachables/held/catalog_shield.json'):
            cls.source.pop(name, None)
        manifest = json.loads(cls.source['manifest.json'])
        for part in (manifest['header'], *manifest['modules']):
            part['version'] = [2, 13, 51004]
        cls.source['manifest.json'] = encode(manifest)

    def setUp(self):
        self.normal = dict(self.source)
        self.diagnostic = probe.apply(self.normal, expected_asset_counts=None)
        self.clean = probe.cleanup(self.normal, expected_asset_counts=None)

    def altered(self, files, name, change):
        result = dict(files)
        value = json.loads(result[name])
        change(value)
        result[name] = encode(value)
        return result

    def reject_overlay(self, files):
        with self.assertRaises(ValueError):
            probe.validate_overlay(self.normal, files, expected_asset_counts=None)

    def added(self, prefix):
        return [n for n in set(self.diagnostic) - set(self.normal) if n.startswith(prefix)]

    def controller(self):
        names = self.added('render_controllers/')
        self.assertEqual(len(names), 1)
        value = json.loads(self.diagnostic[names[0]])
        self.assertEqual(len(value['render_controllers']), 1)
        return names[0], value

    def test_copy_determinism_and_normal_input_is_never_mutated(self):
        before = dict(self.normal)
        self.assertEqual(self.diagnostic, probe.apply(self.normal, expected_asset_counts=None))
        self.assertEqual(self.clean, probe.cleanup(self.normal, expected_asset_counts=None))
        self.assertEqual(self.normal, before)
        self.assertIsNot(self.normal, self.diagnostic)
        self.assertIsNot(self.normal, self.clean)
        self.assertIsInstance(probe.validate_overlay(self.normal, self.diagnostic, expected_asset_counts=None), dict)
        self.assertIsInstance(probe.validate_cleanup(self.normal, self.clean, expected_asset_counts=None), dict)

    def test_exact_owned_members_only(self):
        added = set(self.diagnostic) - set(self.normal)
        self.assertEqual(len(added), 3)
        self.assertEqual(len(self.added('models/')), 1)
        self.assertEqual(len(self.added('render_controllers/')), 1)
        self.assertEqual(len(added - set(self.added('models/'))
                             - set(self.added('render_controllers/'))), 1)
        self.assertFalse(set(self.normal) - set(self.diagnostic))
        changed = {n for n in self.normal if self.normal[n] != self.diagnostic[n]}
        self.assertEqual(changed, {'manifest.json', ELYTRA})

    def test_original_artwork_geometries_renderers_and_animations_are_exact(self):
        for name, data in self.normal.items():
            if name.endswith('.png') or name.startswith(
                    ('models/', 'render_controllers/', 'animations/', 'animation_controllers/')):
                self.assertEqual(self.diagnostic[name], data, name)
                self.assertEqual(self.clean[name], data, name)

    def test_subtracting_only_appended_data_restores_original_attachable_with_types(self):
        old = json.loads(self.normal[ELYTRA])
        new = json.loads(self.diagnostic[ELYTRA])
        before = old['minecraft:attachable']['description']
        after = new['minecraft:attachable']['description']
        for section in ('materials', 'textures', 'geometry'):
            aliases = set(after[section]) - set(before[section])
            self.assertEqual(len(aliases), 1, section)
            for alias in aliases:
                del after[section][alias]
        self.assertEqual(len(after['render_controllers']), len(before['render_controllers']) + 1)
        self.assertIsInstance(after['render_controllers'].pop(), str)
        original = before['scripts']['pre_animation']
        self.assertEqual(after['scripts']['pre_animation'][:len(original)], original)
        self.assertGreater(len(after['scripts']['pre_animation']), len(original))
        del after['scripts']['pre_animation'][len(original):]
        self.assertEqual(typed(old), typed(new))

    def test_probe_is_not_gated_on_the_context_it_is_measuring(self):
        new = json.loads(self.diagnostic[ELYTRA])['minecraft:attachable']['description']
        self.assertIsInstance(new['render_controllers'][-1], str)
        changed = self.altered(self.diagnostic, ELYTRA,
            lambda obj: obj['minecraft:attachable']['description']['render_controllers'].__setitem__(
                -1, {new['render_controllers'][-1]: 'query.is_in_ui'}))
        self.reject_overlay(changed)

    def test_native_and_combined_production_gates_cannot_be_modified(self):
        for change in (
            lambda d: d['scripts']['pre_animation'].__setitem__(0, 'variable.infinityhub_winged_chest = 1.0;'),
            lambda d: d['render_controllers'].__setitem__(0, {'controller.render.armor': '1.0'}),
            lambda d: d['scripts']['animate'].__setitem__(0, {'default_controller': '0.0'}),
        ):
            altered = self.altered(self.diagnostic, ELYTRA,
                lambda obj: change(obj['minecraft:attachable']['description']))
            self.reject_overlay(altered)

    def test_positive_negative_and_scope_controls_cannot_be_changed(self):
        name, original = self.controller()
        control = next(iter(original['render_controllers'].values()))
        self.assertIn('part_visibility', control)
        zeroes = [(i, k) for i, row in enumerate(control['part_visibility'])
                  for k, v in row.items() if v == '0.0']
        ones = [(i, k) for i, row in enumerate(control['part_visibility'])
                for k, v in row.items() if v == '1.0']
        self.assertEqual(len(zeroes), 1)
        self.assertGreaterEqual(len(ones), 3)
        for pairs, wrong in ((zeroes, '1.0'), (ones, 'query.is_in_ui')):
            for i, key in pairs:
                obj = copy.deepcopy(original)
                next(iter(obj['render_controllers'].values()))['part_visibility'][i][key] = wrong
                changed = dict(self.diagnostic); changed[name] = encode(obj)
                self.reject_overlay(changed)

    def test_missing_pre_animation_sentinel_is_rejected(self):
        old_count = len(json.loads(self.normal[ELYTRA])['minecraft:attachable']['description']['scripts']['pre_animation'])
        obj = json.loads(self.diagnostic[ELYTRA])
        lines = obj['minecraft:attachable']['description']['scripts']['pre_animation']
        self.assertGreater(len(lines), old_count)
        del lines[old_count]
        changed = dict(self.diagnostic); changed[ELYTRA] = encode(obj)
        self.reject_overlay(changed)

    def test_all_sixteen_cells_use_the_independent_specified_expressions_and_stages(self):
        path = next(n for n in set(self.diagnostic) - set(self.normal)
                    if not n.startswith(('models/', 'render_controllers/')))
        cells = json.loads(self.diagnostic[path])['cells']
        self.assertEqual([c['label'] for c in cells], list('ATSHPDQV UOFRIJEZ'.replace(' ', '')))
        owner = 'context.owning_entity->'
        wing, suit = 'infinityhub:winged_chest', 'infinityhub:infinity_winged_chest'
        def guarded(receiver, name):
            return "("+receiver+"query.has_property('"+name+"')) ? ("+receiver+"query.property('"+name+"')) : 0.0"
        pre = {
            'T': '1.0', 'S': "context.item_slot == 'torso'",
            'H': owner+"query.has_property('"+wing+"')", 'P': guarded(owner, wing),
            'D': "query.has_property('"+wing+"')", 'Q': guarded('', wing),
            'U': 'query.is_in_ui', 'O': owner+'query.is_in_ui',
            'F': 'query.is_first_person', 'R': owner+'query.is_first_person',
            'J': guarded(owner, suit),
        }
        rc = {'A': '1.0', 'Z': '0.0', 'V': 'variable.infinityhub_winged_chest',
              'I': 'variable.infinityhub_infinity_winged_chest',
              'E': 'variable.infinityhub_winged_chest && (query.is_in_ui || !query.is_first_person)'}
        desc = json.loads(self.diagnostic[ELYTRA])['minecraft:attachable']['description']
        assignments = {line.split(' = ', 1)[0]: line.split(' = ', 1)[1][:-1]
                       for line in desc['scripts']['pre_animation'][2:]}
        sentinel = next(cell for cell in cells if cell['label'] == 'T')['condition']
        self.assertEqual(desc['scripts']['pre_animation'][-1], sentinel+' = 1.0;')
        _, controller = self.controller()
        conditions = {k: v for row in next(iter(controller['render_controllers'].values()))['part_visibility']
                      for k, v in row.items()}
        self.assertEqual(len(assignments), 11)
        for index, cell in enumerate(cells):
            label = cell['label']
            self.assertEqual((cell['row'], cell['column']), (index//4+1, index%4+1))
            self.assertEqual(conditions[cell['bone']], cell['condition'])
            if label in pre:
                self.assertEqual(assignments[cell['condition']], pre[label])
                self.assertEqual(cell['probe_expression'], pre[label])
                stage = 'attachable.pre_animation.sentinel' if label == 'T' else 'attachable.pre_animation'
            else:
                self.assertEqual(cell['condition'], rc[label])
                stage = 'render_controller.literal' if label in ('A', 'Z') else 'render_controller.part_visibility'
            self.assertEqual(cell['evaluation_stage'], stage)
        record = json.loads(self.diagnostic[path])
        self.assertIs(record['fresh_ui_pre_animation_execution_verified'], False)

    def test_structural_validator_requires_column_lines_in_the_fourth_row_too(self):
        path = self.added('models/')[0]
        obj = json.loads(self.diagnostic[path])
        bones = obj['minecraft:geometry'][0]['bones']
        def box(cube):
            o, s = cube['origin'], cube['size']
            return (o[0], o[0]+s[0], o[1], o[1]+s[1])
        left = box(next(b for b in bones if b['name'] == probe.cell_bone('I'))['cubes'][0])
        right = box(next(b for b in bones if b['name'] == probe.cell_bone('J'))['cubes'][0])
        frame = next(b for b in bones if b['name'] == probe.FRAME_BONE)
        matches = [i for i, cube in enumerate(frame['cubes'])
                   if left[1] <= box(cube)[0] and box(cube)[1] <= right[0]
                   and box(cube)[2] <= left[2] and box(cube)[3] >= left[3]]
        self.assertEqual(len(matches), 1)
        del frame['cubes'][matches[0]]
        changed = dict(self.diagnostic); changed[path] = encode(obj)
        with self.assertRaises(ValueError):
            probe.validate_contract(self.normal, changed, expected_asset_counts=None)

    def test_probe_geometry_reparenting_or_overlap_is_rejected(self):
        names = self.added('models/')
        self.assertEqual(len(names), 1)
        name = names[0]
        obj = json.loads(self.diagnostic[name])
        bones = obj['minecraft:geometry'][0]['bones']
        children = [b for b in bones if 'parent' in b and b.get('cubes')]
        self.assertGreaterEqual(len(children), 16)
        bad_parent = copy.deepcopy(obj)
        target = next(b for b in bad_parent['minecraft:geometry'][0]['bones'] if b.get('name') == children[0]['name'])
        target['parent'] = target['name']
        changed = dict(self.diagnostic); changed[name] = encode(bad_parent)
        self.reject_overlay(changed)
        bad_cube = copy.deepcopy(obj)
        target = next(b for b in bad_cube['minecraft:geometry'][0]['bones'] if b.get('name') == children[0]['name'])
        target['cubes'][0]['origin'][2] = 0
        changed[name] = encode(bad_cube)
        self.reject_overlay(changed)

    def test_unowned_member_changes_additions_and_deletions_are_rejected(self):
        sample = next(n for n in self.normal if n.endswith('.png'))
        changed = dict(self.diagnostic); changed[sample] += b'changed'
        self.reject_overlay(changed)
        changed = dict(self.diagnostic); changed['private/player-data.json'] = b'{}'
        self.reject_overlay(changed)
        changed = dict(self.diagnostic); del changed[sample]
        self.reject_overlay(changed)

    def test_r6_is_clean_r4_with_typed_manifest_version_change_only(self):
        self.assertEqual(set(self.clean), set(self.normal))
        for name, data in self.normal.items():
            if name != 'manifest.json':
                self.assertEqual(self.clean[name], data, name)
        before = json.loads(self.normal['manifest.json'])
        after = json.loads(self.clean['manifest.json'])
        self.assertEqual(after['header']['version'], [2, 13, 51006])
        self.assertTrue(all(m['version'] == [2, 13, 51006] for m in after['modules']))
        after['header']['version'] = before['header']['version']
        for old, new in zip(before['modules'], after['modules']):
            new['version'] = old['version']
        self.assertEqual(typed(before), typed(after))

    def test_cleanup_cannot_retain_any_probe_or_change_an_identity(self):
        for name in set(self.diagnostic) - set(self.normal):
            changed = dict(self.clean); changed[name] = self.diagnostic[name]
            with self.assertRaises(ValueError):
                probe.validate_cleanup(self.normal, changed, expected_asset_counts=None)
        changed = self.altered(self.clean, 'manifest.json',
            lambda obj: obj['header'].__setitem__('uuid', '00000000-0000-0000-0000-000000000000'))
        with self.assertRaises(ValueError):
            probe.validate_cleanup(self.normal, changed, expected_asset_counts=None)

    def test_cleanup_source_is_normal_r4_not_the_temporary_overlay(self):
        self.assertEqual(probe.cleanup(self.normal, expected_asset_counts=None), self.clean)
        with self.assertRaises(ValueError):
            probe.cleanup(self.diagnostic, expected_asset_counts=None)

    def test_false_proof_and_approval_metadata_cannot_be_coerced(self):
        added = set(self.diagnostic) - set(self.normal)
        legend = next(n for n in added if not n.startswith(('models/', 'render_controllers/')))
        obj = json.loads(self.diagnostic[legend])
        def replace_first_boolean(value):
            if isinstance(value, dict):
                for key, child in value.items():
                    if type(child) is bool:
                        value[key] = int(child); return True
                    if replace_first_boolean(child): return True
            elif isinstance(value, list):
                for child in value:
                    if replace_first_boolean(child): return True
            return False
        self.assertTrue(replace_first_boolean(obj))
        changed = dict(self.diagnostic); changed[legend] = encode(obj)
        self.reject_overlay(changed)

    def test_changed_combined_validator_is_rejected_before_import_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory)
            for name in ('wing_ui_diagnostic.py', 'wing_render_diagnostic.py'):
                (tools/name).write_bytes((ROOT/'tools'/name).read_bytes())
            marker = tools/'unreviewed-code-executed'
            (tools/'combined_wing_resources.py').write_text(
                'from pathlib import Path\nPath('+repr(str(marker))+').write_text("unexpected")\n')
            spec = importlib.util.spec_from_file_location('_unreviewed_wing_ui_fixture', tools/'wing_ui_diagnostic.py')
            module = importlib.util.module_from_spec(spec)
            with self.assertRaisesRegex(ValueError, 'frozen historical helper changed'):
                spec.loader.exec_module(module)
            self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
