import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
BUILDER = ROOT / 'tools/build_infinity_landscape_preview.py'
PAIRS = {'stone':'stone', 'cobblestone':'cobblestone', 'dirt':'dirt',
         'oak_planks':'planks_oak', 'oak_log':'log_oak', 'coal_ore':'coal_ore',
         'iron_ore':'iron_ore', 'gold_ore':'gold_ore', 'diamond_ore':'diamond_ore',
         'netherrack':'netherrack', 'end_stone':'end_stone', 'obsidian':'obsidian'}
PAIRS.update({'redstone_ore':'redstone_ore', 'emerald_ore':'emerald_ore', 'lapis_ore':'lapis_ore', 'copper_ore':'copper_ore',
              'deepslate':'deepslate/deepslate',
              **{'deepslate_'+n+'_ore':'deepslate/deepslate_'+n+'_ore' for n in ('coal','iron','gold','diamond','redstone','emerald','lapis','copper')},
              'stone_bricks':'stonebrick', 'bricks':'brick', 'nether_bricks':'nether_brick', 'end_stone_bricks':'end_bricks',
              'sand':'sand', 'gravel':'gravel'})
PAIRS.update({'spruce_planks':'planks_spruce', 'birch_planks':'planks_birch', 'jungle_planks':'planks_jungle',
              'acacia_planks':'planks_acacia', 'dark_oak_planks':'planks_big_oak', 'mangrove_planks':'mangrove_planks',
              'cherry_planks':'cherry_planks', 'bamboo_planks':'bamboo_planks',
              'crimson_planks':'huge_fungus/crimson_planks', 'warped_planks':'huge_fungus/warped_planks',
              'granite':'stone_granite', 'diorite':'stone_diorite', 'andesite':'stone_andesite',
              'gold_block':'gold_block', 'cut_copper':'cut_copper',
              'amethyst_block':'amethyst_block', 'amethyst_cluster':'amethyst_cluster',
              'calcite':'calcite', 'tuff':'tuff', 'sandstone':'sandstone_normal'})
PAIRS.update({f'wheat_stage{i}': f'wheat_stage_{i}' for i in range(8)})
ITEMS = {name:name for name in ('iron_ingot','gold_ingot','copper_ingot','netherite_ingot',
                                'diamond','emerald','coal','amethyst_shard','raw_iron','raw_gold','raw_copper')}
ITEMS.update({'redstone': 'redstone_dust', 'map': 'map_empty', 'spyglass': 'spyglass'})

class InfinityLandscapePreviewTests(unittest.TestCase):
    def test_source_and_server_texture_credits_match(self):
        self.assertEqual((ROOT / 'TEXTURE_CREDITS.md').read_bytes(),
                         (ROOT / 'server/TEXTURE_CREDITS.md').read_bytes())

    def test_archives_have_matching_real_textures_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(BUILDER), '--output', directory]
            subprocess.run(command, check=True, capture_output=True)
            java = Path(directory) / 'Infinity-Landscape-Java-preview.zip'
            bedrock = Path(directory) / 'Infinity-Landscape-Bedrock-preview.mcpack'
            before = {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in (java,bedrock)}
            with zipfile.ZipFile(java) as j, zipfile.ZipFile(bedrock) as b:
                self.assertIsNone(j.testzip()); self.assertIsNone(b.testzip())
                self.assertEqual(len(j.namelist()), 75); self.assertEqual(len(b.namelist()), 74)
                meta = json.loads(j.read('pack.mcmeta'))['pack']
                self.assertEqual(meta['min_format'], [100,2]); self.assertEqual(meta['max_format'], [100,2])
                manifest = json.loads(b.read('manifest.json'))
                self.assertEqual(manifest['format_version'], 2)
                self.assertEqual(manifest['header']['version'], [0,7,0])
                self.assertNotEqual(manifest['header']['uuid'], manifest['modules'][0]['uuid'])
                self.assertEqual(manifest['modules'][0]['version'], manifest['header']['version'])
                cluster_path = 'assets/minecraft/textures/block/amethyst_cluster.png'
                cluster_meta = json.loads(j.read(cluster_path + '.mcmeta'))
                self.assertEqual(cluster_meta['texture']['mipmap_strategy'], 'strict_cutout')
                textures = {}
                for name, alias in PAIRS.items():
                    data = j.read('assets/minecraft/textures/block/'+name+'.png')
                    self.assertEqual(data, b.read('textures/blocks/'+alias+'.png'))
                    self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                    self.assertEqual(struct.unpack('>IIBB', data[16:26]), (16,16,8,6))
                    if name in ('amethyst_cluster', 'amethyst_block', 'gold_block', 'cut_copper'):
                        size = struct.unpack('>I', data[33:37])[0]
                        raw = zlib.decompress(data[41:41+size])
                        alpha = {raw[y*65+1+x*4+3] for y in range(16) for x in range(16)}
                        self.assertEqual(alpha, {0, 255} if name == 'amethyst_cluster' else {255})
                    textures[name] = hashlib.sha256(data).digest()
                heights = []
                gold_pixels = []
                for stage in range(8):
                    data = j.read(f'assets/minecraft/textures/block/wheat_stage{stage}.png')
                    size = struct.unpack('>I', data[33:37])[0]
                    raw = zlib.decompress(data[41:41+size])
                    self.assertEqual(len(raw), 16 * 65)
                    top = 16
                    gold = 0
                    for y in range(16):
                        for x in range(16):
                            start = 65*y + 1 + 4*x
                            red, green, blue, alpha = raw[start:start+4]
                            self.assertIn(alpha, (0, 255))
                            if alpha:
                                top = min(top, y)
                                if red > green > blue:
                                    gold += 1
                    heights.append(16 - top)
                    gold_pixels.append(gold)
                self.assertTrue(all(a < b for a, b in zip(heights, heights[1:])), heights)
                self.assertEqual(gold_pixels[:7], [0] * 7)
                self.assertGreater(gold_pixels[7], 40)
                self.assertEqual(len(set(textures.values())), len(PAIRS), 'Every terrain tile must remain visually distinct')
                items = {}
                for name, alias in ITEMS.items():
                    data = j.read('assets/minecraft/textures/item/'+name+'.png')
                    self.assertEqual(data, b.read('textures/items/'+alias+'.png'))
                    self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                    self.assertEqual(struct.unpack('>IIBB', data[16:26]), (16,16,8,6))
                    # The generator writes a single IDAT using unfiltered RGBA rows.
                    size = struct.unpack('>I', data[33:37])[0]
                    self.assertEqual(data[37:41], b'IDAT')
                    raw = zlib.decompress(data[41:41+size])
                    alpha = {raw[y*65+1+x*4+3] for y in range(16) for x in range(16)}
                    self.assertEqual(alpha, {0,255}, name)
                    items[name] = hashlib.sha256(data).digest()
                self.assertEqual(len(set(items.values())), len(ITEMS), 'Every item icon must remain distinct')
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(before, {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in (java,bedrock)})

if __name__ == '__main__': unittest.main()
