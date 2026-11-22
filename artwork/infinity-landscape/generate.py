#!/usr/bin/env python3
"""Author a small, original Infinity landscape preview with no external artwork."""
import json
import struct
import uuid
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
JAVA = ROOT / 'java'
BEDROCK = ROOT / 'bedrock'
ALIASES = {
    'stone': 'stone', 'cobblestone': 'cobblestone', 'dirt': 'dirt',
    'oak_planks': 'planks_oak', 'oak_log': 'log_oak',
    'coal_ore': 'coal_ore', 'iron_ore': 'iron_ore', 'gold_ore': 'gold_ore',
    'diamond_ore': 'diamond_ore', 'netherrack': 'netherrack',
    'end_stone': 'end_stone', 'obsidian': 'obsidian',
    'redstone_ore': 'redstone_ore', 'emerald_ore': 'emerald_ore',
    'lapis_ore': 'lapis_ore', 'copper_ore': 'copper_ore',
    'deepslate': 'deepslate/deepslate',
    'deepslate_coal_ore': 'deepslate/deepslate_coal_ore',
    'deepslate_iron_ore': 'deepslate/deepslate_iron_ore',
    'deepslate_gold_ore': 'deepslate/deepslate_gold_ore',
    'deepslate_diamond_ore': 'deepslate/deepslate_diamond_ore',
    'deepslate_redstone_ore': 'deepslate/deepslate_redstone_ore',
    'deepslate_emerald_ore': 'deepslate/deepslate_emerald_ore',
    'deepslate_lapis_ore': 'deepslate/deepslate_lapis_ore',
    'deepslate_copper_ore': 'deepslate/deepslate_copper_ore',
    'stone_bricks': 'stonebrick', 'bricks': 'brick',
    'nether_bricks': 'nether_brick', 'end_stone_bricks': 'end_bricks',
    'sand': 'sand', 'gravel': 'gravel',
    'spruce_planks': 'planks_spruce', 'birch_planks': 'planks_birch',
    'jungle_planks': 'planks_jungle', 'acacia_planks': 'planks_acacia',
    'dark_oak_planks': 'planks_big_oak', 'mangrove_planks': 'mangrove_planks',
    'cherry_planks': 'cherry_planks', 'bamboo_planks': 'bamboo_planks',
    'crimson_planks': 'huge_fungus/crimson_planks', 'warped_planks': 'huge_fungus/warped_planks',
    'granite': 'stone_granite', 'diorite': 'stone_diorite', 'andesite': 'stone_andesite',
    'amethyst_block': 'amethyst_block', 'amethyst_cluster': 'amethyst_cluster',
    'calcite': 'calcite', 'tuff': 'tuff', 'sandstone': 'sandstone_normal',
}

ITEM_ALIASES = {
    'iron_ingot': 'iron_ingot', 'gold_ingot': 'gold_ingot',
    'copper_ingot': 'copper_ingot', 'netherite_ingot': 'netherite_ingot',
    'diamond': 'diamond', 'emerald': 'emerald', 'coal': 'coal',
    'redstone': 'redstone_dust', 'amethyst_shard': 'amethyst_shard',
    'raw_iron': 'raw_iron', 'raw_gold': 'raw_gold', 'raw_copper': 'raw_copper',
}

def png(width, height, rows):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    raw = b''.join(b'\0' + bytes(channel for pixel in row for channel in pixel) for row in rows)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')

def noise(x, y, seed):
    n = (x * 1973 + y * 9277 + seed * 26699 + x * y * 17) & 0xffffffff
    n = (n ^ (n >> 13)) * 1274126177 & 0xffffffff
    return ((n ^ (n >> 16)) % 15) - 7

def field(base, seed):
    return [[tuple(max(0, min(255, c + noise(x, y, seed))) for c in base) + (255,) for x in range(16)] for y in range(16)]

def dot(tile, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16: tile[y][x] = tuple(color) + (255,)

def line(tile, points, color):
    for x, y in points: dot(tile, x, y, color)

def stone(seed=1):
    tile = field((82, 86, 107), seed)
    line(tile, [(1,4),(2,4),(3,5),(4,5),(4,6),(10,10),(11,10),(12,11),(13,11)], (54, 60, 83))
    line(tile, [(7,1),(7,2),(8,2),(8,3),(13,5),(14,5)], (113, 120, 139))
    line(tile, [(3,12),(4,12),(5,12),(5,13),(6,13)], (96, 154, 156))
    return tile

def amethyst_cluster():
    # Five joined crystal tips with transparent gaps. The broad base meets the
    # supporting block when Minecraft projects the sprite onto its crossed planes.
    palette = {
        '.': (0, 0, 0, 0), 's': (74, 48, 108, 255), 'v': (130, 79, 170, 255),
        'l': (193, 144, 222, 255), 'h': (236, 208, 249, 255), 't': (159, 235, 218, 255),
    }
    rows = (
        '................', '.......h........', '......hlv.......', '......hlvs......',
        '...l..tlvs......', '..hlv.llvs...h..', '..hlvsllvs..hlv.', '..vlvsllvs..tlvs',
        'h.vlvsllvs.hlvs.', 'hl.vvslvvs.lvs..', 'hlv.vslvvslvvs..', '.lvsslvsstlvvs..',
        '.vlvsllsvlvvsl..', '..vvslvllvvslvs.', '...svvvvsvvvvvs.', '..ssvvssvvssvss.',
    )
    return [[palette[shade] for shade in row] for row in rows]

def amethyst_block():
    tile = field((132, 88, 160), 18)
    # Fracture planes repeat across adjacent blocks. Keep the polished teal
    # reflections sparse so the host stone reads as violet next to the buds.
    for ox, oy in ((1, 3), (9, 11)):
        for dy, (left, right) in enumerate(((3, 4), (2, 5), (1, 6), (0, 6), (1, 5), (2, 4))):
            for dx in range(left, right + 1):
                shade = (92, 59, 127) if dx >= 4 else (169, 122, 195)
                if dx == left and dy < 4:
                    shade = (205, 163, 224)
                dot(tile, (ox + dx) % 16, (oy + dy) % 16, shade)
        dot(tile, (ox + 3) % 16, oy, (159, 220, 209))
    return tile

def make(name):
    if name.endswith('_ore'):
        deep = name.startswith('deepslate_')
        ore = name[len('deepslate_'):] if deep else name
        tile = field((48, 53, 70), 9) if deep else stone(1)
        colors = {'coal_ore': ((28,32,43),(119,126,139)), 'iron_ore': ((182,132,105),(228,185,149)),
                  'gold_ore': ((177,127,43),(251,209,88)), 'diamond_ore': ((54,158,164),(133,244,226)),
                  'redstone_ore': ((155,39,57),(249,94,110)), 'emerald_ore': ((27,132,89),(100,229,156)),
                  'lapis_ore': ((36,72,155),(104,151,240)), 'copper_ore': ((159,89,59),(226,150,99))}
        dark, light = colors[ore]
        shapes = {
            'coal_ore': ([(1,2),(8,5),(3,11),(12,12)], [(0,0),(1,0),(0,1),(1,1),(2,1),(1,2)]),
            'iron_ore': ([(2,2),(10,3),(5,8),(12,12)], [(0,0),(1,0),(0,1),(1,1),(1,2),(2,2)]),
            'gold_ore': ([(3,2),(10,5),(2,10),(11,12)], [(0,0),(1,0),(2,0),(0,1),(1,1),(2,1)]),
            'diamond_ore': ([(1,3),(9,1),(5,8),(12,11)], [(1,0),(0,1),(1,1),(2,1),(1,2)]),
            'redstone_ore': ([(1,1),(10,2),(4,7),(11,11)], [(0,0),(2,0),(1,1),(0,2),(2,2)]),
            'emerald_ore': ([(2,1),(10,3),(4,8),(12,11)], [(0,0),(1,0),(0,1),(1,1),(0,2),(1,2)]),
            'lapis_ore': ([(1,2),(9,4),(5,9),(12,12)], [(0,0),(1,0),(0,1),(0,2),(1,2),(2,2)]),
            'copper_ore': ([(2,3),(10,1),(4,9),(11,11)], [(0,0),(2,0),(0,1),(1,1),(2,1),(1,2)]),
        }
        anchors, shape = shapes[ore]
        for x,y in anchors:
            for dx,dy in shape: dot(tile,x+dx,y+dy,dark)
            for dx,dy in shape[:2]: dot(tile,x+dx,y+dy,light)
        return tile
    if name == 'amethyst_block': return amethyst_block()
    if name == 'amethyst_cluster': return amethyst_cluster()
    if name == 'stone': return stone()
    if name == 'cobblestone':
        tile = field((91, 95, 113), 2)
        for y in (0, 5, 10, 15): line(tile, [(x,y) for x in range(16)], (52, 58, 77))
        for y in range(15):
            for x in ((4, 11) if (y//5)%2 == 0 else (1, 8, 15)): dot(tile,x,y,(52,58,77))
        line(tile, [(5,2),(6,2),(7,2),(7,3),(8,3),(12,7),(13,7)], (102,167,164)); return tile
    if name == 'dirt':
        tile = field((104, 76, 68), 3)
        line(tile, [(1,2),(2,2),(2,3),(6,6),(7,6),(8,7),(11,12),(12,12),(13,13)], (69, 58, 69))
        line(tile, [(4,11),(5,11),(5,12),(10,3),(11,3)], (130, 135, 109)); return tile
    planks = {'oak_planks':(151,112,83), 'spruce_planks':(108,79,62), 'birch_planks':(208,193,150),
              'jungle_planks':(169,118,94), 'acacia_planks':(181,96,65), 'dark_oak_planks':(71,50,43),
              'mangrove_planks':(133,61,56), 'cherry_planks':(216,164,164), 'bamboo_planks':(188,172,99),
              'crimson_planks':(119,59,83), 'warped_planks':(57,131,126)}
    if name in planks:
        base = planks[name]; tile = field(base, list(planks).index(name)+4)
        seam = tuple(max(0,c-45) for c in base)
        for y in (0,4,8,12): line(tile, [(x,y) for x in range(16)], seam)
        for y in range(16):
            if y % 4 != 0: dot(tile, 3 if (y//4)%2==0 else 11, y, seam)
        accent = (135,198,190) if name != 'warped_planks' else (187,226,212)
        line(tile, [(6,6),(7,6),(8,6),(8,7),(9,7)], accent); return tile
    if name == 'oak_log':
        tile = field((108, 78, 65), 5)
        for x in (1,5,10,14): line(tile, [(x,y) for y in range(16)], (71,56,63))
        line(tile, [(2,4),(3,4),(3,5),(6,10),(7,10),(8,11),(11,2),(12,2)], (153,116,89)); return tile
    if name in ('stone_bricks', 'bricks', 'nether_bricks', 'end_stone_bricks'):
        bases = {'stone_bricks':(103,105,122), 'bricks':(150,83,78),
                 'nether_bricks':(76,40,57), 'end_stone_bricks':(180,169,174)}
        tile = field(bases[name], 12)
        seam = tuple(max(0,c-36) for c in bases[name])
        for y in (0,4,8,12): line(tile, [(x,y) for x in range(16)], seam)
        for y in range(16):
            if y % 4: dot(tile, 4 if (y//4)%2==0 else 12, y, seam)
        line(tile, [(6,6),(7,6),(8,6),(8,7)], (104,164,163)); return tile
    if name in ('deepslate', 'sand', 'gravel', 'granite', 'diorite', 'andesite', 'calcite', 'tuff', 'sandstone'):
        bases = {'deepslate':(48,53,70), 'sand':(205,190,145), 'gravel':(109,101,102),
                 'granite':(153,106,94), 'diorite':(206,206,202), 'andesite':(132,133,138),
                 'calcite':(221,213,207), 'tuff':(104,109,94), 'sandstone':(207,185,135)}
        tile = field(bases[name], list(bases).index(name)+9)
        line(tile, [(2,3),(3,3),(7,8),(8,8),(13,12),(14,12)], tuple(max(0,c-28) for c in bases[name]))
        return tile
    bases = {'netherrack': (112,48,64), 'end_stone': (183,177,162), 'obsidian': (43,32,65)}
    tile = field(bases[name], {'netherrack':6,'end_stone':7,'obsidian':8}[name])
    accents = {'netherrack':(174,81,103), 'end_stone':(135,123,157), 'obsidian':(113,70,146)}
    line(tile, [(2,3),(3,3),(4,4),(9,2),(10,2),(10,3),(12,9),(13,9),(5,12),(6,12),(7,13)], accents[name])
    return tile

def make_item(name):
    """Draw recognizable, transparent material icons without source artwork."""
    colors = {
        'iron_ingot': (190, 200, 207), 'gold_ingot': (229, 179, 55),
        'copper_ingot': (188, 112, 76), 'netherite_ingot': (72, 65, 78),
        'diamond': (74, 198, 198), 'emerald': (49, 178, 111),
        'coal': (46, 50, 59), 'redstone': (190, 45, 58),
        'amethyst_shard': (157, 100, 203), 'raw_iron': (191, 145, 115),
        'raw_gold': (225, 174, 55), 'raw_copper': (178, 103, 70),
    }
    shapes = {
        'ingot': [(4,12),(3,13),(3,13),(2,12),(2,12),(1,11),(2,11),(3,10)],
        'diamond': [(7,8),(5,10),(4,11),(3,12),(3,12),(4,11),(5,10),(6,9),(7,8)],
        'emerald': [(7,8),(5,10),(4,11),(4,11),(4,11),(4,11),(4,11),(5,10),(7,8)],
        'coal': [(6,9),(4,11),(3,12),(3,12),(2,12),(3,12),(3,11),(4,10),(6,9)],
        'redstone': [(7,8),(5,9),(4,10),(5,11),(3,11),(2,12),(3,12),(4,11),(5,10)],
        'amethyst_shard': [(7,8),(6,8),(6,9),(5,9),(5,10),(4,10),(4,11),(4,11),(5,10),(6,9)],
        'raw': [(5,9),(4,11),(3,12),(3,12),(2,12),(3,12),(4,11),(4,10),(6,9)],
    }
    kind = 'ingot' if name.endswith('_ingot') else 'raw' if name.startswith('raw_') else name
    spans = shapes[kind]
    top = 2 if kind == 'amethyst_shard' else 3 if kind in ('diamond','emerald') else 4
    pixels = {(x, top+y) for y,(left,right) in enumerate(spans) for x in range(left,right+1)}
    tile = [[(0,0,0,0) for _ in range(16)] for _ in range(16)]
    base = colors[name]
    for x,y in pixels:
        edge = any((x+dx,y+dy) not in pixels for dx,dy in ((-1,0),(1,0),(0,-1),(0,1)))
        adjustment = -48 if edge else 14 if y <= top+2 else 0
        dot(tile,x,y,tuple(max(0,min(255,c+adjustment)) for c in base))
    if kind == 'ingot':
        line(tile, [(5,7),(6,7),(7,8),(8,8),(9,7),(10,7)], (100, 191, 184))
    elif kind in ('diamond','emerald','amethyst_shard'):
        line(tile, [(6,top+3),(7,top+2),(8,top+2)], tuple(min(255,c+55) for c in base))
    else:
        line(tile, [(5,top+3),(8,top+5),(10,top+4)], tuple(min(255,c+45) for c in base))
    return tile

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)

def main():
    for name, alias in ALIASES.items():
        data = png(16,16,make(name))
        write(JAVA / 'assets/minecraft/textures/block' / (name + '.png'), data)
        write(BEDROCK / 'textures/blocks' / (alias + '.png'), data)
    write(JAVA / 'assets/minecraft/textures/block/amethyst_cluster.png.mcmeta',
          (json.dumps({'texture': {'mipmap_strategy': 'strict_cutout'}}, indent=2)+'\n').encode())
    for name, alias in ITEM_ALIASES.items():
        data = png(16,16,make_item(name))
        write(JAVA / 'assets/minecraft/textures/item' / (name + '.png'), data)
        write(BEDROCK / 'textures/items' / (alias + '.png'), data)
    write(JAVA / 'pack.mcmeta', (json.dumps({'pack': {'description': 'Infinity Landscape preview',
        'min_format': [100,2], 'max_format': [100,2]}}, indent=2)+'\n').encode())
    namespace = uuid.UUID('7c2a6351-5fa4-47f0-a00e-5132b47acc2f')
    pack = {'format_version':2, 'header':{'name':'Infinity Landscape Preview',
        'description':'Original optional Infinity terrain and materials preview; no behavior pack.',
        'uuid':str(uuid.uuid5(namespace,'header')), 'version':[0,4,0], 'min_engine_version':[1,21,90]},
        'modules':[{'type':'resources','uuid':str(uuid.uuid5(namespace,'resources')),'version':[0,4,0]}]}
    write(BEDROCK / 'manifest.json', (json.dumps(pack,indent=2)+'\n').encode())
    print('Generated',len(ALIASES),'terrain and',len(ITEM_ALIASES),'material textures for each edition.')

if __name__ == '__main__': main()
