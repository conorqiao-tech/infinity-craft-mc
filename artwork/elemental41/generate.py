"""Original Elemental/Infinity art from blank pixel canvases.

No source image, vanilla armor texture or existing armor file is loaded. The
only shared layout is the engine's documented equipment UV/joint contract.
Run with Python 3; no image package is required. The preview is an authored
asset inspection sheet, not evidence of rendering on a Minecraft client.
"""
from pathlib import Path
import binascii
import hashlib
import json
import math
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]
ART = Path(__file__).resolve().parent
JAVA = ROOT / "java/src/main/resources/assets/convergence"
BED = ROOT / "bedrock/resource_pack"
UV = {
    "head_top": (8, 0, 16, 8), "head_bottom": (16, 0, 24, 8),
    "head_right": (0, 8, 8, 16), "head_front": (8, 8, 16, 16),
    "head_left": (16, 8, 24, 16), "head_back": (24, 8, 32, 16),
    "leg_top": (4, 16, 8, 20), "leg_bottom": (8, 16, 12, 20),
    "leg_right": (0, 20, 4, 32), "leg_front": (4, 20, 8, 32),
    "leg_left": (8, 20, 12, 32), "leg_back": (12, 20, 16, 32),
    "body_top": (20, 16, 28, 20), "body_bottom": (28, 16, 36, 20),
    "body_right": (16, 20, 20, 32), "body_front": (20, 20, 28, 32),
    "body_left": (28, 20, 32, 32), "body_back": (32, 20, 40, 32),
    "arm_top": (44, 16, 48, 20), "arm_bottom": (48, 16, 52, 20),
    "arm_right": (40, 20, 44, 32), "arm_front": (44, 20, 48, 32),
    "arm_left": (48, 20, 52, 32), "arm_back": (52, 20, 56, 32),
}
FIRE, WATER, EARTH, AIR = "ff724b", "4edcec", "79c96f", "d7caff"
SUITS = {
    "elemental": {"base": "263d4d", "shade": "172632", "edge": "bb9265", "light": "e1c6a0", "glow": "effcfa"},
    "infinity": {"base": "26234d", "shade": "121628", "edge": "d6af54", "light": "f6e1a8", "glow": "b3e8ff"},
}
PIECES = ("helmet", "chestplate", "leggings", "boots")
RELICS = ("firebrand", "tidecaller", "terra_gauntlet", "windstep")
BLOCKS = ("ember_crystal", "tide_glass", "earth_rune", "air_lattice", "infinity_prism")
NAMES = {"firebrand": "Firebrand", "tidecaller": "Tidecaller", "terra_gauntlet": "Terra Gauntlet", "windstep": "Windstep",
         "ember_crystal": "Ember Crystal", "tide_glass": "Tide Glass", "earth_rune": "Earth Rune", "air_lattice": "Air Lattice", "infinity_prism": "Infinity Prism"}
for suit in SUITS:
    for piece in PIECES:
        NAMES[suit + "_" + piece] = suit.title() + " " + piece.title()


def rgba(value):
    if isinstance(value, tuple):
        return value
    value = value.lstrip("#")
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


class Canvas:
    def __init__(self, width, height, color=(0, 0, 0, 0)):
        self.w, self.h = width, height
        self.p = [rgba(color)] * (width * height)

    def put(self, x, y, color):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[y * self.w + x] = rgba(color)

    def rect(self, x0, y0, x1, y1, color):
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.put(x, y, color)

    def polygon(self, points, color):
        for y in range(max(0, min(p[1] for p in points)), min(self.h, max(p[1] for p in points) + 1)):
            for x in range(max(0, min(p[0] for p in points)), min(self.w, max(p[0] for p in points) + 1)):
                inside = False
                previous = points[-1]
                for current in points:
                    x1, y1 = previous; x2, y2 = current
                    if (y1 > y + 0.5) != (y2 > y + 0.5) and x + 0.5 < (x2 - x1) * (y + 0.5 - y1) / (y2 - y1) + x1:
                        inside = not inside
                    previous = current
                if inside:
                    self.put(x, y, color)

    def line(self, a, b, color, width=1):
        x0, y0 = a; x1, y1 = b
        length = max(abs(x1 - x0), abs(y1 - y0), 1)
        for n in range(length + 1):
            x = round(x0 + (x1 - x0) * n / length)
            y = round(y0 + (y1 - y0) * n / length)
            self.rect(x, y, x + width, y + width, color)

    def copy(self, source, x0, y0, scale=1, box=None):
        sx0, sy0, sx1, sy1 = box or (0, 0, source.w, source.h)
        for y in range(sy0, sy1):
            for x in range(sx0, sx1):
                color = source.p[y * source.w + x]
                if color[3]:
                    self.rect(x0 + (x - sx0) * scale, y0 + (y - sy0) * scale,
                              x0 + (x - sx0 + 1) * scale, y0 + (y - sy0 + 1) * scale, color)

    def save(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        def chunk(kind, data):
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", binascii.crc32(kind + data) & 0xffffffff)
        raw = b"".join(b"\0" + bytes(c for p in self.p[y * self.w:(y + 1) * self.w] for c in p) for y in range(self.h))
        path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">2I5B", self.w, self.h, 8, 6, 0, 0, 0))
                         + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def star(c, x, y, color, size=3):
    c.line((x - size, y), (x + size, y), color)
    c.line((x, y - size), (x, y + size), color)
    c.put(x - 1, y - 1, color); c.put(x + 1, y + 1, color)


def armor_atlas(suit):
    colors = SUITS[suit]
    atlas = Canvas(128, 64)
    for face, rect in UV.items():
        x0, y0, x1, y1 = (n * 2 for n in rect)
        atlas.rect(x0, y0, x1, y1, colors["base"])
        atlas.rect(x0, y1 - 2, x1, y1, colors["shade"])
        atlas.rect(x0, y0, x1, y0 + 1, colors["light"])
        atlas.rect(x0, y0, x0 + 1, y1, colors["edge"])
        atlas.rect(x1 - 1, y0, x1, y1, colors["edge"])
        # Narrow facets, not a noisy all-over photograph or stretched icon.
        for y in range(y0 + 3, y1 - 2, 6):
            atlas.rect(x0 + 2, y, min(x0 + 4, x1 - 1), y + 1, colors["shade"])
            atlas.rect(max(x0 + 1, x1 - 4), y + 1, x1 - 2, y + 2, colors["edge"])
        if face == "head_front":
            atlas.rect(x0 + 2, y0 + 5, x1 - 2, y0 + 10, colors["shade"])
            atlas.rect(x0 + 2, y0 + 6, x1 - 2, y0 + 8, WATER if suit == "elemental" else colors["glow"])
            atlas.rect(x0 + 6, y0 + 11, x1 - 6, y1 - 1, colors["edge"])
        if face == "body_front":
            cx, cy = (x0 + x1) // 2, y0 + 10
            if suit == "elemental":
                for dx, dy, color in ((-3, -3, FIRE), (1, -3, WATER), (-3, 1, EARTH), (1, 1, AIR)):
                    atlas.rect(cx + dx, cy + dy, cx + dx + 3, cy + dy + 3, color)
                atlas.put(cx, cy, colors["glow"])
            else:
                star(atlas, cx, cy, colors["light"], 5)
                atlas.rect(cx - 1, cy - 1, cx + 2, cy + 2, colors["glow"])
            atlas.rect(x0 + 2, y1 - 5, x1 - 2, y1 - 3, colors["edge"])
        if face == "leg_front":
            atlas.rect(x0 + 2, y0 + 7, x1 - 2, y0 + 10, colors["edge"])
            atlas.rect(x0 + 3, y0 + 8, x1 - 3, y0 + 9, EARTH if suit == "elemental" else colors["glow"])
        if face == "arm_front":
            atlas.rect(x0 + 1, y0 + 3, x1 - 1, y0 + 6, AIR if suit == "elemental" else colors["edge"])
    return atlas


def armor_icon(suit, piece):
    c = Canvas(32, 32)
    p = SUITS[suit]
    outline = p["shade"]
    if piece == "helmet":
        c.polygon([(7, 7), (11, 4), (22, 4), (25, 8), (25, 24), (21, 26), (21, 15), (11, 15), (11, 26), (7, 24)], outline)
        c.polygon([(8, 8), (12, 5), (21, 5), (24, 9), (24, 23), (22, 24), (22, 13), (10, 13), (10, 24), (8, 23)], p["base"])
        c.rect(9, 7, 23, 9, p["edge"]); c.rect(10, 10, 23, 13, WATER if suit == "elemental" else p["glow"])
        c.rect(15, 5, 18, 8, p["light"])
    elif piece == "chestplate":
        shape = [(4, 7), (10, 5), (12, 9), (20, 9), (22, 5), (28, 7), (29, 16), (24, 18), (24, 27), (8, 27), (8, 18), (3, 16)]
        c.polygon(shape, outline)
        c.polygon([(5, 8), (9, 7), (12, 11), (20, 11), (23, 7), (27, 8), (28, 15), (23, 17), (23, 26), (9, 26), (9, 17), (4, 15)], p["base"])
        c.rect(10, 24, 23, 26, p["edge"])
        c.line((5, 9), (10, 11), p["edge"], 2); c.line((22, 11), (26, 9), p["edge"], 2)
        if suit == "elemental":
            for x, y, color in ((13, 14, FIRE), (17, 14, WATER), (13, 18, EARTH), (17, 18, AIR)):
                c.rect(x, y, x + 3, y + 3, color)
        else:
            star(c, 16, 17, p["light"], 4); c.put(16, 17, p["glow"])
    elif piece == "leggings":
        c.polygon([(7, 5), (25, 5), (24, 27), (18, 27), (17, 16), (15, 16), (14, 27), (8, 27)], outline)
        c.polygon([(8, 6), (24, 6), (23, 26), (19, 26), (18, 14), (14, 14), (13, 26), (9, 26)], p["base"])
        c.rect(8, 7, 24, 10, p["edge"]); c.rect(10, 18, 13, 21, p["light"]); c.rect(19, 18, 22, 21, p["light"])
        c.rect(14, 7, 18, 10, EARTH if suit == "elemental" else p["glow"])
    else:
        for x in (5, 18):
            c.polygon([(x + 1, 8), (x + 8, 8), (x + 8, 22), (x + 10, 23), (x + 10, 27), (x, 27), (x, 22)], outline)
            c.rect(x + 2, 9, x + 7, 23, p["base"]); c.rect(x + 1, 23, x + 9, 26, p["base"])
            c.rect(x + 2, 10, x + 7, 12, p["edge"]); c.rect(x + 1, 25, x + 9, 26, p["light"])
            c.rect(x + 3, 16, x + 6, 19, FIRE if suit == "elemental" else p["glow"])
    return c


def relic_icon(name):
    c = Canvas(32, 32)
    if name == "firebrand":
        c.line((8, 27), (22, 13), "482d32", 4)
        c.line((9, 27), (23, 13), "c49558", 2)
        c.polygon([(15, 16), (15, 9), (19, 11), (21, 3), (24, 9), (28, 7), (27, 17), (22, 21)], "ad3b3f")
        c.polygon([(18, 15), (21, 7), (22, 13), (26, 11), (25, 17), (22, 19)], FIRE)
        c.polygon([(20, 16), (22, 12), (24, 16), (22, 18)], "ffe3a0")
        c.line((10, 18), (20, 23), "d6af54", 2)
    elif name == "tidecaller":
        c.line((11, 28), (19, 9), "164f69", 4)
        c.line((12, 28), (20, 9), "72c4cf", 2)
        c.polygon([(14, 13), (15, 8), (20, 3), (26, 9), (25, 16), (20, 20)], "164f69")
        c.polygon([(16, 12), (20, 5), (24, 11), (23, 15), (20, 17)], WATER)
        c.polygon([(18, 12), (20, 8), (22, 12), (20, 15)], "effcfa")
        c.line((6, 23), (13, 25), "d6af54", 2)
    elif name == "terra_gauntlet":
        c.polygon([(7, 12), (9, 7), (24, 7), (27, 13), (26, 23), (21, 27), (11, 25), (7, 20)], "263f35")
        c.polygon([(9, 13), (11, 9), (22, 9), (25, 14), (23, 22), (20, 24), (12, 23), (9, 19)], "71946b")
        for x in (10, 14, 18, 22):
            c.rect(x, 6, x + 3, 13, "bdad7a")
        c.rect(13, 14, 22, 21, "365947"); c.rect(16, 14, 19, 21, EARTH); c.rect(14, 17, 21, 19, EARTH)
        c.rect(11, 23, 22, 26, "bb9265")
    else:
        c.polygon([(5, 26), (10, 12), (18, 4), (27, 3), (26, 11), (17, 20)], "46466a")
        c.polygon([(7, 25), (12, 13), (19, 6), (25, 5), (23, 11), (16, 18)], AIR)
        c.line((7, 26), (24, 6), "f7f5ff")
        c.line((9, 21), (18, 18), "8b80ba"); c.line((13, 16), (22, 12), "8b80ba")
        c.line((4, 11), (10, 11), "b3e8ff"); c.line((2, 15), (8, 15), "b3e8ff")
    return c


def block_tile(name):
    c = Canvas(32, 32)
    colors = {"ember_crystal": ("351d31", "6b3545", FIRE), "tide_glass": ("163448", "246875", WATER),
              "earth_rune": ("293c32", "4d6750", EARTH), "air_lattice": ("30344f", "555d7f", AIR),
              "infinity_prism": ("1f203d", "453b65", "d6af54")}
    base, middle, glow = colors[name]
    c.rect(0, 0, 32, 32, base)
    c.rect(2, 2, 30, 30, middle)
    c.rect(3, 3, 29, 29, base)
    # Opposing edges agree, so the repeat is intentional rather than accidental.
    c.rect(0, 0, 32, 1, glow); c.rect(0, 31, 32, 32, glow)
    c.rect(0, 0, 1, 32, glow); c.rect(31, 0, 32, 32, glow)
    if name == "ember_crystal":
        for x, y, radius in ((10, 12, 5), (22, 23, 4), (23, 7, 3), (8, 25, 2)):
            c.polygon([(x, y - radius), (x + radius, y), (x, y + radius), (x - radius, y)], middle)
            c.line((x, y - radius + 1), (x + radius - 1, y), glow)
            c.line((x, y - radius + 1), (x, y + radius - 1), "ffd69a")
    elif name == "tide_glass":
        for y in (9, 17, 25):
            for x in range(4, 28):
                c.put(x, y + (1 if (x // 4) % 2 else -1), glow)
        c.line((7, 5), (5, 7), "c2fbff"); c.line((25, 24), (21, 28), "c2fbff")
    elif name == "earth_rune":
        for x, y in ((9, 8), (21, 20)):
            c.line((x, y), (x, y + 9), glow, 2); c.line((x - 3, y + 3), (x + 4, y + 3), glow)
            c.line((x, y + 6), (x + 4, y + 9), glow)
        c.rect(5, 23, 12, 26, middle); c.rect(20, 7, 27, 10, middle)
    elif name == "air_lattice":
        for offset in range(-24, 32, 8):
            c.line((max(4, offset), max(4, 4 - offset)), (min(27, offset + 24), min(27, 28 - offset)), middle)
        c.polygon([(16, 5), (27, 16), (16, 27), (5, 16)], glow)
        c.polygon([(16, 8), (24, 16), (16, 24), (8, 16)], base)
        star(c, 16, 16, "f7f5ff", 3)
    else:
        c.polygon([(16, 4), (28, 16), (16, 28), (4, 16)], glow)
        c.polygon([(16, 6), (26, 16), (16, 26), (6, 16)], middle)
        star(c, 16, 16, "f7e9bf", 7)
        c.rect(14, 14, 19, 19, "b3e8ff")
    return c


def face_uv(part):
    return {direction: {"uv": [UV[part + "_" + face][0] * 2, UV[part + "_" + face][1] * 2],
                        "uv_size": [(UV[part + "_" + face][2] - UV[part + "_" + face][0]) * 2,
                                    (UV[part + "_" + face][3] - UV[part + "_" + face][1]) * 2]}
            for direction, face in (("up", "top"), ("down", "bottom"), ("west", "right"), ("north", "front"), ("east", "left"), ("south", "back"))}


def geometry(suit, piece):
    """Independent restrained plates in the engine's upright bind pose."""
    # The native player's outer clothing remains visible under attachables.
    # Shells must clear .25 clothing and .5 hat inflation, not just base skin.
    plate = 0.38 if suit == "elemental" else 0.42
    def box(part, origin, size):
        return {"origin": origin, "size": size, "uv": face_uv(part)}
    def bone(name, joint, pivot, cubes):
        return {"name": suit + "_" + name, "binding": "'" + joint.lower() + "'", "pivot": pivot, "cubes": cubes}
    if piece == "helmet":
        head_plate = max(0.60, plate + 0.20)
        bones = [bone("head", "head", [0, 24, 0], [box("head", [-4 - head_plate, 24 - head_plate, -4 - head_plate], [8 + 2 * head_plate] * 3)])]
    elif piece == "chestplate":
        bones = [bone("body", "body", [0, 24, 0], [box("body", [-4 - plate, 12 - plate, -2 - plate], [8 + 2 * plate, 12 + 2 * plate, 4 + 2 * plate])])]
        for side, joint, x, pivot in (("right_arm", "rightArm", -8, [-5, 22, 0]), ("left_arm", "leftArm", 4, [5, 22, 0])):
            # A distinct arm depth avoids a coplanar front/back strip where
            # animated sleeves meet the body. The silhouette stays compact.
            arm_plate = plate + 0.08
            # Wide/slim sleeve union spans y11.25..24.25. Give its lower
            # edge .1 clearance instead of assuming the wide skin's y12 start.
            bones.append(bone(side, joint, pivot, [box("arm", [x - arm_plate, 11.15, -2 - arm_plate], [4 + 2 * arm_plate, 24 + arm_plate - 11.15, 4 + 2 * arm_plate])]))
    else:
        bones = []
        for side, joint, x, pivot in (("right_leg", "rightLeg", -3.9, [-1.9, 12, 0]), ("left_leg", "leftLeg", -0.1, [1.9, 12, 0])):
            # Enclose the entire pants layer, including the inner thigh. The
            # slight left-leg stagger separates centre faces in all three axes;
            # trimming them to x=0 would instead expose the native skin.
            stagger = 0.012 if side == "left_leg" else 0.0
            leg_plate = plate + (-0.02 if piece == "leggings" else 0.12) + stagger
            y, top = (3.10 - stagger, 12.35 + stagger) if piece == "leggings" else (-leg_plate, 3.50 + stagger)
            # Boots overlap the thinner leggings; the distinct depth avoids a
            # coplanar seam while covering the previously exposed ankle strip.
            bones.append(bone(side, joint, pivot, [box("leg", [x - leg_plate, y, -2 - leg_plate], [4 + 2 * leg_plate, top - y, 4 + 2 * leg_plate])]))
    return {"format_version": "1.19.30", "minecraft:geometry": [{"description": {
        "identifier": "geometry.convergence." + suit + "_" + piece + "_elemental41", "texture_width": 128, "texture_height": 64,
        "visible_bounds_width": 2.5, "visible_bounds_height": 3, "visible_bounds_offset": [0, 1, 0]}, "bones": bones}]}


def geometry_view(suit, atlas, walking=False, geometries=None):
    """Software inspect the actual cuboids/UVs, including a joint pivot pose.

    This deterministic orthographic rasterizer is an asset-design preview; it
    cannot prove the Bedrock renderer, server mapping or animation integration.
    """
    image = Canvas(160, 240, "111a26")
    depth_buffer = [-float("inf")] * (image.w * image.h)
    angle, elevation = math.radians(25), math.radians(12)
    right = (math.cos(angle), 0, math.sin(angle))
    up = (-math.sin(angle) * math.sin(elevation), math.cos(elevation), math.cos(angle) * math.sin(elevation))
    near = (math.sin(angle) * math.cos(elevation), math.sin(elevation), -math.cos(angle) * math.cos(elevation))
    def dot(a, b):
        return sum(x * y for x, y in zip(a, b))
    def triangle(vertices, texcoords, shade):
        a, b, c = vertices
        denominator = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(denominator) < 1e-7:
            return
        for sy in range(max(0, int(min(v[1] for v in vertices))), min(image.h, math.ceil(max(v[1] for v in vertices)) + 1)):
            for sx in range(max(0, int(min(v[0] for v in vertices))), min(image.w, math.ceil(max(v[0] for v in vertices)) + 1)):
                x, y = sx + .5, sy + .5
                wa = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / denominator
                wb = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / denominator
                wc = 1 - wa - wb
                if min(wa, wb, wc) < -1e-6:
                    continue
                depth = wa * a[2] + wb * b[2] + wc * c[2]
                if depth < depth_buffer[sy * image.w + sx]:
                    continue
                u = int(sum(w * uv[0] for w, uv in zip((wa, wb, wc), texcoords)))
                v = int(sum(w * uv[1] for w, uv in zip((wa, wb, wc), texcoords)))
                u, v = max(0, min(atlas.w - 1, u)), max(0, min(atlas.h - 1, v))
                color = atlas.p[v * atlas.w + u]
                if color[3]:
                    image.put(sx, sy, tuple(min(255, round(channel * shade)) for channel in color[:3]) + (color[3],))
                    depth_buffer[sy * image.w + sx] = depth
    actual_geometries = geometries if geometries is not None else [geometry(suit, piece) for piece in PIECES]
    for actual in actual_geometries:
        for bone in actual["minecraft:geometry"][0]["bones"]:
            rotation = 0
            if walking:
                rotation = {"'rightarm'": -28, "'leftarm'": 28, "'rightleg'": 25, "'leftleg'": -25}.get(bone["binding"], 0)
            sine, cosine = math.sin(math.radians(rotation)), math.cos(math.radians(rotation))
            pivot = bone["pivot"]
            def project(vertex):
                x, y, z = vertex
                # Authored crown fins and other detail cubes may rotate about
                # their own pivots before following the animated player joint.
                if cube.get("rotation"):
                    cp = cube.get("pivot", (0, 0, 0))
                    x, y, z = x - cp[0], y - cp[1], z - cp[2]
                    for axis, degrees in enumerate(cube["rotation"]):
                        sn, cs = math.sin(math.radians(degrees)), math.cos(math.radians(degrees))
                        if axis == 0:
                            y, z = y * cs - z * sn, y * sn + z * cs
                        elif axis == 1:
                            x, z = x * cs + z * sn, -x * sn + z * cs
                        else:
                            x, y = x * cs - y * sn, x * sn + y * cs
                    x, y, z = x + cp[0], y + cp[1], z + cp[2]
                y, z = y - pivot[1], z - pivot[2]
                vertex = (x, y * cosine - z * sine + pivot[1], y * sine + z * cosine + pivot[2])
                return (80 + dot(vertex, right) * 5.4, 210 - dot(vertex, up) * 5.4, dot(vertex, near))
            for cube in bone["cubes"]:
                x0, y0, z0 = cube["origin"]; w, h, d = cube["size"]
                x1, y1, z1 = x0 + w, y0 + h, z0 + d
                faces = {"north": ([(x0, y1, z0), (x1, y1, z0), (x1, y0, z0), (x0, y0, z0)], .98),
                         "east": ([(x1, y1, z0), (x1, y1, z1), (x1, y0, z1), (x1, y0, z0)], .72),
                         "up": ([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], 1.12)}
                for direction, (vertices, shade) in faces.items():
                    coords = [project(vertex) for vertex in vertices]
                    u, v = cube["uv"][direction]["uv"]; tw, th = cube["uv"][direction]["uv_size"]
                    uv = ((u, v), (u + tw - .001, v), (u + tw - .001, v + th - .001), (u, v + th - .001))
                    for indices in ((0, 1, 2), (0, 2, 3)):
                        triangle([coords[i] for i in indices], [uv[i] for i in indices], shade)
    return image


def main():
    generated = []
    def save(c, *paths):
        for path in paths:
            c.save(path); generated.append(path)
    atlas = json.loads((BED / "textures/item_texture.json").read_text())
    terrain = json.loads((BED / "textures/terrain_texture.json").read_text())
    controllers = {}
    icons, atlases, tiles = {}, {}, {}
    for suit in SUITS:
        armor = armor_atlas(suit); atlases[suit] = armor
        for layer in ("humanoid", "humanoid_leggings"):
            save(armor, JAVA / f"textures/entity/equipment/{layer}/{suit}_armor.png")
        save(armor, BED / f"textures/convergence/models/armor/elemental41/{suit}.png")
        write_json(JAVA / f"equipment/{suit}_armor.json", {"layers": {layer: [{"texture": f"convergence:{suit}_armor"}] for layer in ("humanoid", "humanoid_leggings")}})
        for piece, slot in zip(PIECES, ("head", "chest", "legs", "feet")):
            name = suit + "_" + piece
            icons[name] = armor_icon(suit, piece)
            geo = geometry(suit, piece)
            write_json(BED / f"models/entity/elemental41/{name}.geo.json", geo)
            controller = f"controller.render.convergence.{name}_elemental41"
            equipped = f"query.is_item_name_any('slot.armor.{slot}', 0, 'convergence:{name}', 'infinity_catalog:{name}')"
            # Inventory previews must show the complete suit even if their
            # render pass also reports first person; gameplay eyes stay clear.
            visibility = [{bone["name"]: equipped + ("" if bone["binding"] in ("'rightarm'", "'leftarm'") else " && (query.is_in_ui || !query.is_first_person)")}
                          for bone in geo["minecraft:geometry"][0]["bones"]]
            controllers[controller] = {"geometry": "Geometry.default", "materials": [{"*": "variable.is_enchanted ? Material.enchanted : Material.default"}],
                                       "textures": ["Texture.default", "Texture.enchanted"], "part_visibility": visibility}
            description = {"identifier": "convergence:" + name, "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                           "textures": {"default": f"textures/convergence/models/armor/armor42/{suit}", "enchanted": "textures/misc/enchanted_actor_glint"},
                           "geometry": {"default": geo["minecraft:geometry"][0]["description"]["identifier"]},
                           "scripts": {"parent_setup": "variable." + {"head": "helmet", "chest": "chest", "legs": "leg", "feet": "boot"}[slot] + "_layer_visible = 0.0;"},
                           "render_controllers": [controller]}
            for prefix, identifier in (("", "convergence:"), ("catalog_", "infinity_catalog:")):
                write_json(BED / f"attachables/elemental41/{prefix}{name}.json", {"format_version": "1.10.0", "minecraft:attachable": {"description": dict(description, identifier=identifier + name)}})
    for name in RELICS:
        icons[name] = relic_icon(name)
    for name, icon in icons.items():
        save(icon, JAVA / f"textures/item/elemental41/{name}.png", BED / f"textures/convergence/items/elemental41/{name}.png")
        write_json(JAVA / f"models/item/{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "convergence:item/elemental41/" + name}})
        write_json(JAVA / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": "convergence:item/" + name}})
        atlas["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/items/elemental41/" + name}
    for name in BLOCKS:
        tile = block_tile(name); tiles[name] = tile
        save(tile, JAVA / f"textures/block/elemental41/{name}.png", BED / f"textures/convergence/blocks/elemental41/{name}.png")
        write_json(JAVA / f"models/block/{name}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "convergence:block/elemental41/" + name}})
        write_json(JAVA / f"models/item/{name}.json", {"parent": "convergence:block/" + name})
        write_json(JAVA / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": "convergence:block/" + name}})
        write_json(JAVA / f"blockstates/{name}.json", {"variants": {"": {"model": "convergence:block/" + name}}})
        atlas["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/blocks/elemental41/" + name}
        terrain["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/blocks/elemental41/" + name}
    write_json(BED / "textures/item_texture.json", atlas)
    write_json(BED / "textures/terrain_texture.json", terrain)
    write_json(BED / "render_controllers/elemental41.json", {"format_version": "1.8.0", "render_controllers": controllers})
    # Replace only our own lang keys, leaving all older names exactly as written.
    lang = BED / "texts/en_US.lang"
    keys = {prefix + name + ".name" for name in NAMES for prefix in ("item.convergence:", "tile.convergence:")}
    old = [line for line in lang.read_text().splitlines() if line.split("=", 1)[0] not in keys]
    entries = [("tile.convergence:" if name in BLOCKS else "item.convergence:") + name + ".name=" + title for name, title in NAMES.items()]
    lang.write_text("\n".join(old).rstrip() + "\n\n" + "\n".join(entries) + "\n")
    preview = Canvas(576, 400, "111a26")
    # Full front bind-pose: real UV faces at consistent scale; no cape/wing bait.
    for index, suit in enumerate(SUITS):
        armor = atlases[suit]; x, y, scale = 36 + index * 144, 20, 3
        for part, dx, dy in (("head", 8, 0), ("body", 8, 16), ("arm", 0, 16), ("arm", 24, 16), ("leg", 8, 40), ("leg", 16, 40)):
            rect = tuple(n * 2 for n in UV[part + "_front"])
            preview.copy(armor, x + dx * scale, y + dy * scale, scale, rect)
    for index, icon in enumerate(icons.values()):
        preview.copy(icon, 332 + index % 4 * 58, 16 + index // 4 * 76, 1)
    for index, tile in enumerate(tiles.values()):
        preview.copy(tile, 20 + index * 108, 292, 3)
    save(preview, ART / "asset-preview.png")
    geometry_preview = Canvas(640, 240, "111a26")
    for index, suit in enumerate(SUITS):
        for pose in range(2):
            geometry_preview.copy(geometry_view(suit, atlases[suit], bool(pose)), (index * 2 + pose) * 160, 0)
    save(geometry_preview, ART / "geometry-preview.png")
    for suit, armor in atlases.items():
        save(armor, ART / f"{suit}-uv.png")
    manifest = {"format": 1, "authorship": "Original pixel canvases and cuboids; no source images loaded", "preview": "Asset inspection only; not a game/client render",
                "armor_uv": {key: list(value) for key, value in UV.items()}, "suits": list(SUITS), "icons": list(icons), "blocks": list(BLOCKS),
                "sha256": {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in generated}}
    write_json(ART / "manifest.json", manifest)
    print(f"Authored {len(icons)} icons, 2 original armor atlases, 8 wearable models, 16 attachables and {len(tiles)} block tiles.")


if __name__ == "__main__":
    main()
