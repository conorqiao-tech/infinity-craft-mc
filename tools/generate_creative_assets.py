#!/usr/bin/env python3
"""Original deterministic pixel art for the Creative collection; no source images."""
import json
from pathlib import Path
import shutil
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'java/src/main/resources'
ASSETS = JAVA / 'assets/convergence'
BEDROCK = ROOT / 'bedrock/resource_pack'
BLOCKS = {'aurora_tiles': ('Aurora Tiles', 'amethyst_shard'), 'obsidian_lattice': ('Obsidian Lattice', 'obsidian'),
          'copper_circuit': ('Copper Circuit', 'copper_ingot'), 'moonstone': ('Moonstone', 'quartz'),
          'sunstone_lamp': ('Sunstone Lamp', 'glowstone'), 'verdant_mosaic': ('Verdant Mosaic', 'moss_block')}


def png(path, width, height, pixels):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    raw = b''.join(b'\x00' + bytes(v for rgba in row for v in rgba) for row in pixels)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
                     + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def rgba(rgb):
    return (*rgb, 255)


def tint(rgb, delta):
    return tuple(max(0, min(255, v + delta)) for v in rgb)


def block_art(name):
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            noise = ((x * 13 + y * 7 + x * y * 3) % 9) - 4
            if name == 'aurora_tiles':
                palette = [(28, 111, 113), (41, 149, 148), (62, 102, 148), (54, 141, 161)]
                color = palette[(x // 4 + y // 4) % 4]
                if x % 4 == 0 or y % 4 == 0: color = (13, 49, 66)
                elif x % 4 == 1 or y % 4 == 1: color = tint(color, 28)
                if (x, y) in [(2, 2), (6, 10), (14, 6)]: color = (174, 253, 228)
            elif name == 'obsidian_lattice':
                color = (29, 24, 44)
                diagonal = (x + y) % 8 == 0 or (x - y) % 8 == 0
                if diagonal: color = (115, 69, 161)
                elif (x + y) % 8 == 1 or (x - y) % 8 == 1: color = (62, 39, 97)
                if x % 8 == 0 and y % 8 == 0: color = (204, 145, 220)
            elif name == 'copper_circuit':
                color = (115, 60, 37)
                if x == 0 or y == 0: color = (59, 40, 34)
                if x == 1 or y == 1: color = (208, 132, 75)
                trace = (y == 5 and 3 <= x <= 12) or (x == 12 and 5 <= y <= 12) or (y == 12 and 5 <= x <= 12) or (x == 5 and 8 <= y <= 12)
                if trace: color = (52, 171, 143)
                if (x, y) in [(3, 5), (5, 8), (12, 12)]: color = (151, 235, 198)
            elif name == 'moonstone':
                color = (182, 195, 213)
                vein = (x + 2 * y + y // 3) % 13
                if vein < 2: color = (116, 137, 168)
                elif vein == 2: color = (229, 236, 246)
                if x in (0, 15) or y in (0, 15): color = (146, 161, 190)
                if (x, y) in [(4, 4), (10, 12), (12, 6)]: color = (244, 247, 255)
            elif name == 'sunstone_lamp':
                color = (72, 42, 26)
                if x in (1, 14) or y in (1, 14): color = (181, 106, 42)
                distance = (x - 7.5) ** 2 + (y - 7.5) ** 2
                if distance < 35: color = (240, 157, 48)
                if distance < 22: color = (255, 200, 79)
                if distance < 9: color = (255, 239, 149)
                if x == 7 or y == 7: color = tint(color, 10)
                if (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13)]: color = (231, 175, 73)
            else:
                color = (29, 66, 51)
                cell_x, cell_y = x % 8, y % 8
                distance = abs(cell_x - 3) + abs(cell_y - 3)
                if distance <= 5: color = (61, 124, 78)
                if distance <= 3: color = (99, 172, 102)
                if cell_x == 3 or cell_y == 3: color = (143, 203, 117)
                if x % 8 == 0 or y % 8 == 0: color = (164, 176, 144)
            row.append(rgba(tint(color, noise)))
        pixels.append(row)
    return pixels


def item_art(name):
    out = [[(0, 0, 0, 0) for _ in range(16)] for _ in range(16)]
    aurora = name.startswith('aurora')
    base = (35, 127, 139) if aurora else (118, 43, 31)
    bright = (121, 238, 221) if aurora else (255, 172, 74)
    shadow = (17, 59, 80) if aurora else (65, 25, 34)
    def pixel(x, y, c): out[y][x] = rgba(c)
    kind = name.split('_')[-1]
    if kind == 'helmet':
        cells = [(x, y) for y in range(3, 12) for x in range(3, 13) if y < 8 or x < 5 or x > 10]
    elif kind == 'chestplate':
        cells = [(x, y) for y in range(3, 14) for x in range(2, 14) if (y > 5 and 4 <= x <= 11) or (y <= 5 and not 6 <= x <= 9)]
    elif kind == 'leggings':
        cells = [(x, y) for y in range(3, 14) for x in range(4, 12) if y < 7 or x < 7 or x > 8]
    elif kind == 'boots':
        cells = [(x, y) for y in range(5, 13) for x in range(2, 14) if x in (3, 4, 5, 10, 11, 12) or y >= 10 and x != 7 and x != 8]
    else:
        # Two diagonal building wands: prism fork and angular carving head.
        for t in range(9):
            for dx in (0, 1): pixel(3 + t + dx, 13 - t, (177, 119, 57))
        if name == 'builder_wand':
            cells = [(x, y) for y in range(1, 7) for x in range(9, 15) if abs(x - 11) + abs(y - 3) < 5]
            base, bright, shadow = (41, 142, 146), (147, 251, 224), (23, 71, 94)
        else:
            cells = [(x, y) for y in range(1, 7) for x in range(7, 15) if y < 4 or x > 10]
            base, bright, shadow = (161, 88, 46), (255, 199, 107), (79, 41, 38)
    cell_set = set(cells)
    for x, y in cells:
        color = bright if (x - 1, y) not in cell_set or y % 5 == 0 else shadow if (x + 1, y) not in cell_set else base
        pixel(x, y, color)
    if kind in ('helmet', 'chestplate'):
        pixel(7, 5 if kind == 'helmet' else 9, (224, 255, 245) if aurora else (255, 239, 179))
        pixel(8, 5 if kind == 'helmet' else 9, bright)
    return out


def worn_art(outfit, leggings=False):
    out = [[(0, 0, 0, 0) for _ in range(64)] for _ in range(32)]
    aurora = outfit == 'aurora'
    base = (34, 112, 125) if aurora else (99, 39, 32)
    seam = (122, 232, 216) if aurora else (245, 160, 59)
    dark = (18, 55, 73) if aurora else (57, 25, 29)
    # Standard 64x32 humanoid equipment UV islands: head, torso, leg, arm.
    regions = [(0, 0, 32, 16), (0, 16, 16, 32), (16, 16, 40, 32)]
    if not leggings: regions += [(40, 16, 56, 32)]
    for left, top, right, bottom in regions:
        for y in range(top, bottom):
            for x in range(left, right):
                c = base
                if x % 4 == 0 or y in (top, bottom - 1): c = dark
                if y in (top + 2, bottom - 3): c = seam
                if aurora and (x + y) % 11 == 0: c = (99, 179, 192)
                if not aurora and (x - y) % 9 == 0: c = (175, 68, 39)
                out[y][x] = rgba(tint(c, (x * 7 + y * 3) % 7 - 3))
    # Visor band, centerpiece, and leg cuffs are distinct between both collections.
    if not leggings:
        for y in range(8, 11):
            for x in range(8, 16): out[y][x] = rgba((163, 253, 228) if aurora else (255, 186, 73))
        for y in range(21, 26):
            for x in range(23, 27):
                if abs(x - 24) + abs(y - 23) <= 3: out[y][x] = rgba(seam)
    return out


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def generate():
    names = {}
    for name, (title, ingredient) in BLOCKS.items():
        tex = ASSETS / f'textures/block/{name}.png'
        png(tex, 16, 16, block_art(name))
        for target in [BEDROCK / f'textures/convergence/blocks/{name}.png']:
            target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(tex, target)
        write_json(ASSETS / f'blockstates/{name}.json', {'variants': {'': {'model': f'convergence:block/{name}'}}})
        write_json(ASSETS / f'models/block/{name}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': f'convergence:block/{name}'}})
        write_json(ASSETS / f'models/item/{name}.json', {'parent': f'convergence:block/{name}'})
        write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': f'convergence:item/{name}'}})
        write_json(JAVA / f'data/convergence/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'convergence:{name}'}]}]})
        recipe = {'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['SSS', 'SMS', 'SSS'], 'key': {'S': 'minecraft:stone', 'M': 'minecraft:' + ingredient}, 'result': {'id': 'convergence:' + name, 'count': 8}}
        write_json(JAVA / f'data/convergence/recipe/{name}.json', recipe)
        names['block.convergence.' + name] = title
    for name in ['builder_wand', 'sculptor_wand'] + [outfit + '_' + slot for outfit in ('aurora', 'ember') for slot in ('helmet', 'chestplate', 'leggings', 'boots')]:
        tex = ASSETS / f'textures/item/{name}.png'
        png(tex, 16, 16, item_art(name))
        target = BEDROCK / f'textures/convergence/items/{name}.png'; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(tex, target)
        write_json(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:item/handheld' if name.endswith('wand') else 'minecraft:item/generated', 'textures': {'layer0': 'convergence:item/' + name}})
        write_json(ASSETS / f'items/{name}.json', {'model': {'type': 'minecraft:model', 'model': 'convergence:item/' + name}})
        names['item.convergence.' + name] = name.replace('_', ' ').title()
    for outfit in ('aurora', 'ember'):
        asset = outfit + '_armor'
        write_json(ASSETS / f'equipment/{asset}.json', {'layers': {'humanoid': [{'texture': 'convergence:' + asset}], 'humanoid_leggings': [{'texture': 'convergence:' + asset}]}})
        png(ASSETS / f'textures/entity/equipment/humanoid/{asset}.png', 64, 32, worn_art(outfit))
        png(ASSETS / f'textures/entity/equipment/humanoid_leggings/{asset}.png', 64, 32, worn_art(outfit, True))
    write_json(ROOT / 'research/creative-gear-names.json', names)
    # A review sheet of original source pixels, with no resampling artifacts.
    art = [block_art(n) for n in BLOCKS] + [item_art(n) for n in ['builder_wand', 'sculptor_wand', 'aurora_helmet', 'aurora_chestplate', 'ember_helmet', 'ember_chestplate']]
    sheet = [[(12, 23, 32, 255) for _ in range(6 * 96)] for _ in range(2 * 96)]
    for index, sprite in enumerate(art):
        for y in range(16):
            for x in range(16):
                color = sprite[y][x]
                if not color[3]: continue
                for dy in range(4):
                    for dx in range(4): sheet[index // 6 * 96 + 16 + y * 4 + dy][index % 6 * 96 + 16 + x * 4 + dx] = color
    png(ROOT / 'research/creative-gear-original-art.png', 576, 192, sheet)
    print('Generated original Creative collection textures, models, loot tables, and recipes.')


if __name__ == '__main__': generate()
