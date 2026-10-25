"""Resource closure and independent diagnostic controls, not client pixel proof."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'tools'))
import wing_render_diagnostic as probe


def encode(value):
    return (json.dumps(value, indent=2)+'\n').encode()


class WingRenderDiagnosticTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        resources = ROOT/'bedrock/resource_pack'
        cls.source = {p.relative_to(resources).as_posix():p.read_bytes()
                      for p in resources.rglob('*') if p.is_file()}
        cls.source.pop('attachables/elytra.json', None)
        for name in ('attachables/held/shield.json', 'attachables/held/catalog_shield.json'):
            cls.source.pop(name, None)
        manifest = json.loads(cls.source['manifest.json'])
        manifest['header']['version'] = list(probe.NORMAL_REVISION)
        for module in manifest['modules']:
            module['version'] = list(probe.NORMAL_REVISION)
        cls.source['manifest.json'] = encode(manifest)

    def setUp(self):
        self.normal = dict(self.source)
        self.diagnostic = probe.apply(self.normal)

    def alter(self, path, mutation):
        value = json.loads(self.diagnostic[path])
        mutation(value)
        self.diagnostic[path] = encode(value)

    def reject(self):
        with self.assertRaises(ValueError):
            probe.validate_overlay(self.normal, self.diagnostic)

    def test_apply_is_deterministic_copy_and_cleanup_starts_with_exact_clean_input(self):
        previous = dict(self.normal)
        self.assertEqual(probe.apply(self.normal), self.diagnostic)
        self.assertEqual(probe.build_overlay(self.normal), self.diagnostic)
        self.assertEqual(self.normal, previous)
        self.assertIsNot(self.normal, self.diagnostic)
        # Cleanup is the exact original pack, not an attempted partial inverse.
        cleanup = dict(self.normal)
        self.assertEqual(cleanup, previous)
        self.assertTrue(set((probe.GEOMETRY, probe.CONTROLLERS, probe.LEGEND)).isdisjoint(cleanup))
        with self.assertRaises(ValueError):
            probe.apply(self.diagnostic)

    def test_manifest_marks_temporary_scope_without_new_pack_identity(self):
        old = json.loads(self.normal['manifest.json'])
        new = json.loads(self.diagnostic['manifest.json'])
        self.assertIn('TEMPORARY WING DIAGNOSTIC R2', new['header']['name'])
        self.assertIn('ordinary and held elytra', new['header']['description'])
        self.assertEqual(new['header']['version'], [2, 13, 51002])
        self.assertEqual(new['header']['uuid'], old['header']['uuid'])
        self.assertEqual([m['uuid'] for m in new['modules']], [m['uuid'] for m in old['modules']])
        self.assertTrue(all(m['version'] == [2, 13, 51002] for m in new['modules']))
        self.assertEqual(old['header']['version'], [2, 13, 51003])

    def test_exact_owned_resource_delta_and_false_proof_boundaries(self):
        report = probe.validate_overlay(self.normal, self.diagnostic)
        self.assertEqual(set(report['added_entries']), {probe.GEOMETRY, probe.CONTROLLERS, probe.LEGEND})
        self.assertEqual(set(report['changed_entries']), {'manifest.json', probe.ELYTRA, *probe.HELMETS, probe.LANG})
        self.assertEqual(report['deleted_entries'], [])
        self.assertTrue(report['temporary'] and report['owner_approval_required'])
        self.assertTrue(report['ordinary_elytra_badges'] and report['held_elytra_badges'])
        self.assertFalse(report['live_installation_approval_received'])
        self.assertFalse(report['device_rendering_verified'])
        self.assertFalse(report['runtime_molang_context_verified'])

    def test_original_native_elytra_and_production_combined_gates_are_unchanged(self):
        old = json.loads(self.normal[probe.ELYTRA])
        new = json.loads(self.diagnostic[probe.ELYTRA])
        desc = new['minecraft:attachable']['description']
        for key, alias in (('materials', probe.MATERIAL_ALIAS), ('textures', probe.TEXTURE_ALIAS),
                           ('geometry', probe.GEOMETRY_ALIAS)):
            desc[key].pop(alias)
        self.assertEqual(desc['render_controllers'].pop(), probe.ELYTRA_CONTROLLER)
        appended = desc['scripts']['pre_animation'][-5:]
        del desc['scripts']['pre_animation'][-5:]
        self.assertEqual(appended[0], probe.ALIVE+' = 1.0;')
        self.assertEqual(new, old)
        self.assertEqual(desc['identifier'], 'minecraft:elytra')
        self.assertEqual(desc['textures']['default'], 'textures/models/armor/elytra')
        self.assertEqual(desc['geometry']['default'], 'geometry.elytra')

    def test_no_existing_png_geometry_animation_or_native_controller_bytes_change(self):
        for name, data in self.normal.items():
            if name.endswith('.png') or name.startswith(('models/', 'animations/', 'animation_controllers/', 'render_controllers/')):
                self.assertEqual(self.diagnostic[name], data, name)

    def test_one_unconditional_badge_controller_with_independent_bone_conditions(self):
        attachable = json.loads(self.diagnostic[probe.ELYTRA])['minecraft:attachable']['description']
        self.assertEqual(attachable['render_controllers'][-1], probe.ELYTRA_CONTROLLER)
        controllers = json.loads(self.diagnostic[probe.CONTROLLERS])['render_controllers']
        actual = controllers[probe.ELYTRA_CONTROLLER]
        visibility = {key:value for row in actual['part_visibility'] for key,value in row.items()}
        self.assertEqual(visibility['body'], '1.0')
        self.assertEqual(visibility[probe.ROOT_BONE], '1.0')
        self.assertEqual(visibility[probe.ROOT_BONE+'_a'], '1.0')
        self.assertEqual(visibility[probe.ROOT_BONE+'_z'], '0.0')
        self.assertEqual(visibility[probe.ROOT_BONE+'_t'], probe.ALIVE)
        scripts = attachable['scripts']['pre_animation'][2:]
        assignments = {line.split(' = ', 1)[0]:line.split(' = ', 1)[1].removesuffix(';') for line in scripts}
        self.assertEqual(len(assignments), 5)
        self.assertEqual(assignments[probe.ALIVE], '1.0')
        for label in ('H', 'P', 'B', 'S'):
            condition = visibility[probe.ROOT_BONE+'_'+label.lower()]
            self.assertNotIn('variable.infinityhub_winged_chest', condition)
            self.assertNotIn(probe.ALIVE, condition)
            # Independent observations occur in the same attachable context as
            # production. Render controllers consume values, not owner queries
            # whose own context inheritance is still unproven on the device.
            self.assertIn(condition, assignments)
            self.assertNotIn('variable.', assignments[condition])
        self.assertIn('has_property', assignments[visibility[probe.ROOT_BONE+'_p']])
        self.assertIn("'body'", assignments[visibility[probe.ROOT_BONE+'_b']])
        self.assertIn('context.owning_entity->query.item_slot_to_bone_name', assignments[visibility[probe.ROOT_BONE+'_s']])

    def test_raw_and_catalog_helmet_controls_use_same_geometry_material_texture_pattern(self):
        controllers = json.loads(self.diagnostic[probe.CONTROLLERS])['render_controllers']
        active, control = controllers[probe.ELYTRA_CONTROLLER], controllers[probe.HELMET_CONTROLLER]
        self.assertEqual({k:v for k,v in active.items() if k != 'part_visibility'},
                         {k:v for k,v in control.items() if k != 'part_visibility'})
        visibility = {k:v for row in control['part_visibility'] for k,v in row.items()}
        self.assertEqual(visibility[probe.ROOT_BONE+'_a'], '1.0')
        for label in ('T', 'H', 'P', 'B', 'S', 'V', 'E', 'Z'):
            self.assertEqual(visibility[probe.ROOT_BONE+'_'+label.lower()], '0.0')
        for name in probe.HELMETS:
            old = json.loads(self.normal[name])
            new = json.loads(self.diagnostic[name])
            desc = new['minecraft:attachable']['description']
            self.assertEqual(desc['render_controllers'].pop(), probe.HELMET_CONTROLLER)
            for key, alias in (('materials', probe.MATERIAL_ALIAS), ('textures', probe.TEXTURE_ALIAS),
                               ('geometry', probe.GEOMETRY_ALIAS)):
                desc[key].pop(alias)
            self.assertEqual(old, new)

    def test_opaque_uv_sampling_covers_every_glyph_face_and_unique_bones(self):
        report = probe.validate_overlay(self.normal, self.diagnostic)
        uv = report['opaque_uv']
        self.assertEqual(uv['rgba'][3], 255)
        self.assertGreater(sum(uv['rgba'][:3]), 600)
        self.assertTrue(probe.opaque_rectangle(self.normal[probe.TEXTURE], (*uv['uv'], 1, 1)))
        model = json.loads(self.diagnostic[probe.GEOMETRY])['minecraft:geometry'][0]
        self.assertGreaterEqual(model['description']['visible_bounds_width'], 8)
        self.assertGreaterEqual(model['description']['visible_bounds_height'], 8)
        bones = model['bones']
        self.assertEqual(len({b['name'] for b in bones}), len(bones))
        self.assertEqual(bones[1]['name'], probe.ROOT_BONE)
        for bone in bones[2:]:
            self.assertEqual(bone['parent'], probe.ROOT_BONE)
            self.assertTrue(bone['cubes'])
            for cube in bone['cubes']:
                for face in cube['uv'].values():
                    self.assertEqual(face, {'uv': uv['uv'], 'uv_size': [1, 1]})

    def test_sampled_transparent_texel_and_corrupt_png_are_refused(self):
        width, height, pixels = probe.rgba_pixels(self.normal[probe.TEXTURE])
        clear = next((x, y) for y in range(height) for x in range(width) if pixels[(y*width+x)*4+3] != 255)
        with self.assertRaises(ValueError):
            probe.opaque_rectangle(self.normal[probe.TEXTURE], (*clear, 1, 1))
        with self.assertRaises(ValueError):
            probe.opaque_rectangle(self.normal[probe.TEXTURE], (width, height, 1, 1))
        damaged = bytearray(self.normal[probe.TEXTURE]);damaged[33] ^= 1
        with self.assertRaises(ValueError):
            probe.rgba_pixels(bytes(damaged))

    def test_machine_legend_has_distinct_positions_shapes_and_invalid_negative_control_rule(self):
        legend = json.loads(self.diagnostic[probe.LEGEND])
        self.assertEqual([m['label'] for m in legend['markers']], list('ATHPBSVEZ'))
        self.assertEqual(len({(m['row'], m['column']) for m in legend['markers']}), 9)
        self.assertEqual(len({tuple(m['glyph']) for m in legend['markers']}), 9)
        self.assertIn('MUST NEVER', next(m for m in legend['markers'] if m['label'] == 'Z')['meaning'])
        self.assertTrue(legend['read_with_both_hands_empty'])
        self.assertTrue(legend['helmet_control']['same_geometry_material_texture_and_uv'])
        for marker in legend['markers']:
            if marker['label'] in ('T', 'H', 'P', 'B', 'S'):
                self.assertEqual(marker['evaluation_stage'], 'attachable.pre_animation')
                self.assertNotIn('variable.infinityhub_diag_r2_', marker['probe_expression'])

    def test_english_control_labels_preserve_custom_item_names_and_state_locale_limit(self):
        language = self.diagnostic[probe.LANG].decode()
        for line in self.normal[probe.LANG].decode().splitlines():
            self.assertIn(line, language)
        for key, value in probe.LANG_LABELS.items():
            self.assertIn(key+'='+value, language)
        self.assertTrue(json.loads(self.diagnostic[probe.LEGEND])['english_labels_only'])

    def test_missing_stage_or_literal_false_control_inversion_is_rejected(self):
        for label, condition in (('Z', '1.0'), ('A', '0.0'), ('T', '0.0')):
            with self.subTest(label=label):
                self.diagnostic = probe.apply(self.normal)
                def alter(value):
                    rows = value['render_controllers'][probe.ELYTRA_CONTROLLER]['part_visibility']
                    next(row for row in rows if probe.ROOT_BONE+'_'+label.lower() in row)[probe.ROOT_BONE+'_'+label.lower()] = condition
                self.alter(probe.CONTROLLERS, alter)
                self.reject()
        self.diagnostic = probe.apply(self.normal)
        self.alter(probe.CONTROLLERS, lambda value: value['render_controllers'][probe.ELYTRA_CONTROLLER]['part_visibility'].pop())
        self.reject()

    def test_property_coupling_or_removing_has_property_guard_is_rejected(self):
        for condition in ('variable.infinityhub_winged_chest', "context.owning_entity->query.property('infinityhub:winged_chest')"):
            with self.subTest(condition=condition):
                self.diagnostic = probe.apply(self.normal)
                def alter(value):
                    rows = value['render_controllers'][probe.ELYTRA_CONTROLLER]['part_visibility']
                    next(row for row in rows if probe.ROOT_BONE+'_p' in row)[probe.ROOT_BONE+'_p'] = condition
                self.alter(probe.CONTROLLERS, alter)
                self.reject()

    def test_conditional_controller_list_cannot_masquerade_as_independent_stage(self):
        self.alter(probe.ELYTRA, lambda value: value['minecraft:attachable']['description']['render_controllers'].__setitem__(
            -1, {probe.ELYTRA_CONTROLLER: 'variable.infinityhub_winged_chest'}))
        self.reject()

    def test_new_probe_assignments_may_not_reuse_production_or_each_other(self):
        original = dict(self.diagnostic)
        for replacement in ('variable.infinityhub_winged_chest', 'variable.infinityhub_diag_r2_h'):
            with self.subTest(replacement=replacement):
                self.diagnostic = dict(original)
                self.alter(probe.ELYTRA, lambda value: value['minecraft:attachable']['description']['scripts']['pre_animation'].__setitem__(
                    4, 'variable.infinityhub_diag_r2_p = '+replacement+';'))
                self.reject()

    def test_modifying_production_gate_animation_material_or_item_identity_is_rejected(self):
        original = dict(self.diagnostic)
        for field, replacement in (('identifier', 'minecraft:netherite_chestplate'),
                                  ('animations', {}), ('materials', {'default':'entity_alphatest'})):
            with self.subTest(field=field):
                self.diagnostic = dict(original)
                self.alter(probe.ELYTRA, lambda value: value['minecraft:attachable']['description'].__setitem__(field, replacement))
                self.reject()
        self.diagnostic = dict(original)
        self.alter(probe.ELYTRA, lambda value: value['minecraft:attachable']['description']['scripts']['pre_animation'].__setitem__(
            0, 'variable.infinityhub_winged_chest = 1.0;'))
        self.reject()

    def test_native_asset_changes_extra_files_and_unowned_gameplay_fields_are_rejected(self):
        original = dict(self.diagnostic)
        for name in (probe.TEXTURE, 'models/entity/elemental41/infinity_helmet.geo.json'):
            with self.subTest(asset=name):
                self.diagnostic = dict(original);self.diagnostic[name] += b'changed';self.reject()
        self.diagnostic = dict(original);self.diagnostic['functions/dispatch.mcfunction'] = b'op @a';self.reject()
        self.diagnostic = dict(original)
        self.alter(probe.ELYTRA, lambda value: value['minecraft:attachable']['description'].update(events={'run_command':'op @a'}))
        self.reject()

    def test_missing_resource_hidden_proof_claim_and_wrong_revision_are_rejected(self):
        self.diagnostic.pop(probe.GEOMETRY);self.reject()
        self.diagnostic = probe.apply(self.normal)
        self.alter(probe.LEGEND, lambda value: value.update(device_rendering_verified=True))
        self.reject()
        with self.assertRaises(ValueError):
            probe.apply(self.normal, [2, 13, 51003])
        bad = dict(self.normal);manifest = json.loads(bad['manifest.json']);manifest['modules'][0]['version'] = [2,13,51001]
        bad['manifest.json'] = encode(manifest)
        with self.assertRaises(ValueError):
            probe.apply(bad)


if __name__ == '__main__':
    unittest.main()
