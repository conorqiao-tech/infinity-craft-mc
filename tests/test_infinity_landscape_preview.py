import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

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

class InfinityLandscapePreviewTests(unittest.TestCase):
    def test_archives_have_matching_real_textures_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            command = [sys.executable, str(BUILDER), '--output', directory]
            subprocess.run(command, check=True, capture_output=True)
            java = Path(directory) / 'Infinity-Landscape-Java-preview.zip'
            bedrock = Path(directory) / 'Infinity-Landscape-Bedrock-preview.mcpack'
            before = {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in (java,bedrock)}
            with zipfile.ZipFile(java) as j, zipfile.ZipFile(bedrock) as b:
                self.assertIsNone(j.testzip()); self.assertIsNone(b.testzip())
                self.assertEqual(len(j.namelist()), 32); self.assertEqual(len(b.namelist()), 32)
                meta = json.loads(j.read('pack.mcmeta'))['pack']
                self.assertEqual(meta['min_format'], [100,2]); self.assertEqual(meta['max_format'], [100,2])
                manifest = json.loads(b.read('manifest.json'))
                self.assertEqual(manifest['format_version'], 2)
                self.assertNotEqual(manifest['header']['uuid'], manifest['modules'][0]['uuid'])
                textures = {}
                for name, alias in PAIRS.items():
                    data = j.read('assets/minecraft/textures/block/'+name+'.png')
                    self.assertEqual(data, b.read('textures/blocks/'+alias+'.png'))
                    self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
                    self.assertEqual(struct.unpack('>IIBB', data[16:26]), (16,16,8,6))
                    textures[name] = hashlib.sha256(data).digest()
                self.assertEqual(len(set(textures.values())), len(PAIRS), 'Every terrain tile must remain visually distinct')
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(before, {p:hashlib.sha256(p.read_bytes()).hexdigest() for p in (java,bedrock)})

if __name__ == '__main__': unittest.main()
