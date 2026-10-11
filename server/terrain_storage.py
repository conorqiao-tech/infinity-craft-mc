"""Read native saves and retain exact chunk records around personal locations.

No player or entity NBT is rewritten. Unsupported or damaged storage stops the
refresh before any saved terrain moves.
"""
import gzip
import json
import math
from pathlib import Path
import re
import struct
import uuid
import zlib

LIMIT = 64 * 1024 * 1024
REGION_LIMIT = 256 * 1024 * 1024
KEPT_LIMIT = 256 * 1024 * 1024
REGION = re.compile(r"r\.(-?\d+)\.(-?\d+)\.mca")
PETS = {"minecraft:" + name for name in ("wolf", "cat", "parrot", "horse", "donkey", "mule",
        "llama", "trader_llama", "skeleton_horse", "zombie_horse", "camel", "camel_husk", "nautilus", "zombie_nautilus")}


class NBT:
    def __init__(self, data):
        if len(data) > LIMIT:
            raise ValueError("Native NBT exceeds the refresh safety limit")
        self.data, self.offset = data, 0

    def read(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError("Truncated native NBT")
        value = self.data[self.offset:self.offset + size]
        self.offset += size
        return value

    def number(self, fmt):
        return struct.unpack(">" + fmt, self.read(struct.calcsize(">" + fmt)))[0]

    def text(self):
        # Only names/coordinates are inspected; copied records retain original
        # Java modified-UTF bytes, including book text and custom item names.
        return self.read(self.number("H")).decode("utf-8", "surrogateescape")

    def value(self, kind, depth=0):
        if depth > 64:
            raise ValueError("Native NBT is too deeply nested")
        if kind in range(1, 7):
            return self.number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        if kind == 8:
            return self.text()
        if kind == 10:
            result = {}
            while True:
                child = self.number("B")
                if child == 0:
                    return result
                name = self.text()
                if name in result:
                    raise ValueError("Duplicate native NBT field")
                result[name] = self.value(child, depth + 1)
        if kind in (7, 9, 11, 12):
            child = self.number("B") if kind == 9 else None
            size = self.number("i")
            if not 0 <= size <= LIMIT or (kind != 7 and size > 1_000_000):
                raise ValueError("Invalid native NBT array size")
            if kind == 7:
                return self.read(size)
            if kind == 9:
                return [self.value(child, depth + 1) for _ in range(size)]
            return [self.number("i" if kind == 11 else "q") for _ in range(size)]
        raise ValueError("Unsupported native NBT tag")

    def parse(self):
        if self.number("B") != 10:
            raise ValueError("Native save root must be a compound")
        self.text()
        result = self.value(10)
        if self.offset != len(self.data):
            raise ValueError("Trailing native NBT data")
        return result


def unpack(data, compression):
    if compression in (1, 2):
        stream = zlib.decompressobj(31 if compression == 1 else 15)
        result = stream.decompress(data, LIMIT + 1)
        if len(result) > LIMIT or stream.unconsumed_tail or not stream.eof:
            raise ValueError("Native compressed record exceeds safety limit or is incomplete")
        return result
    if compression == 3:
        return data
    raise ValueError("Unsupported native region compression; terrain was retained")


def saved_nbt(path):
    return NBT(unpack(path.read_bytes(), 1)).parse()


def identity(value):
    try:
        if isinstance(value, dict):
            return identity(value.get("uuid", value.get("UUID")))
        if isinstance(value, list) and len(value) == 4:
            return str(uuid.UUID(bytes=b"".join(struct.pack(">i", n) for n in value)))
        if isinstance(value, str):
            return str(uuid.UUID(value))
    except (ValueError, TypeError, struct.error):
        pass
    return None


def region_records(path):
    match = REGION.fullmatch(path.name)
    if not match:
        raise ValueError("Invalid native region filename")
    if path.stat().st_size > REGION_LIMIT:
        raise ValueError("Native region exceeds refresh memory limit; terrain was retained")
    data = path.read_bytes()
    if not data:
        return []  # Vanilla creates empty POI/entity regions lazily.
    if len(data) < 8192 or len(data) % 4096:
        raise ValueError("Damaged native region file")
    rx, rz = map(int, match.groups())
    used = {0, 1}
    records = []
    for slot in range(1024):
        entry = int.from_bytes(data[slot * 4:slot * 4 + 4], "big")
        if not entry:
            continue
        start, size = entry >> 8, entry & 255
        sectors = set(range(start, start + size))
        if start < 2 or not size or (start + size) * 4096 > len(data) or used & sectors:
            raise ValueError("Invalid or overlapping native chunk sectors")
        used.update(sectors)
        record = data[start * 4096:(start + size) * 4096]
        length = int.from_bytes(record[:4], "big")
        if not 1 <= length <= len(record) - 4:
            raise ValueError("Invalid native chunk record length")
        x, z = rx * 32 + slot % 32, rz * 32 + slot // 32
        external = path.parent / ("c." + str(x) + "." + str(z) + ".mcc") if record[4] & 128 else None
        if external and (external.is_symlink() or not external.is_file()):
            raise ValueError("Missing or unsafe external native chunk")
        if external and external.stat().st_size > LIMIT:
            raise ValueError("External native chunk exceeds the refresh safety limit")
        payload = external.read_bytes() if external else record[5:4 + length]
        records.append((slot, (x, z), record, data[4096 + slot * 4:4100 + slot * 4],
                        payload, record[4] & 127, external))
    return records


def prepare(world):
    protected = {}
    def area(dimension, x, z, radius=2):
        if not isinstance(dimension, str) or not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", dimension):
            raise ValueError("Invalid saved personal dimension")
        if (not isinstance(x, (float, int)) or not isinstance(z, (float, int))
                or not math.isfinite(x) or not math.isfinite(z) or abs(x) > 30_000_000 or abs(z) > 30_000_000):
            raise ValueError("Invalid saved personal position")
        folder = dimension.replace(":", "/", 1)
        cx, cz = math.floor(x) // 16, math.floor(z) // 16
        protected.setdefault(folder, set()).update((cx + dx, cz + dz)
                    for dx in range(-radius, radius + 1) for dz in range(-radius, radius + 1))

    def locations(value, default=None):
        if isinstance(value, dict):
            dimension = value.get("Dimension", value.get("dimension", default))
            pos = value.get("Pos", value.get("pos", value.get("exit_portal_location",
                            value.get("ExitPortal", value.get("exit_portal")))))
            if dimension and isinstance(pos, list) and len(pos) == 3:
                area(dimension, pos[0], pos[2])
            if dimension and ("x" in value or "X" in value) and ("z" in value or "Z" in value):
                area(dimension, value.get("x", value.get("X")), value.get("z", value.get("Z")))
            for key, item in value.items():
                if key == "place" and isinstance(item, str):
                    locations(json.loads(item), dimension)
                elif isinstance(item, (dict, list)):
                    locations(item, dimension)
        elif isinstance(value, list):
            for item in value:
                if isinstance(item, dict):
                    locations(item, default)

    vehicles = set()
    for path in (world / "players/data").glob("*.dat"):
        player = saved_nbt(path)
        if not isinstance(player.get("Dimension"), str) or not isinstance(player.get("Pos"), list) or len(player["Pos"]) != 3:
            raise ValueError("Player save lacks the expected native position; terrain was retained")
        locations(player)
        vehicle = player.get("RootVehicle", {})
        if isinstance(vehicle, dict):
            entity = vehicle.get("Entity", {})
            value = identity(entity.get("UUID")) if isinstance(entity, dict) else None
            if value:
                vehicles.add(value)
    locations(saved_nbt(world / "level.dat"))
    # The native hub builder is all-or-nothing. Its marker skips rebuilding,
    # so retain every existing record in the bounded six-lobby footprint.
    # Floors, arches, signs and connecting bridges lie inside [-60, 60].
    if (world / "infinity-built-in-lobbies.json").is_file():
        area("convergence:hub", 0, 0, 4)
    community = world / "infinity-community.json"
    if community.is_file():
        locations(json.loads(community.read_text()))
    area("minecraft:the_end", 0, 0, 8)
    boss = world / "dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat"
    if boss.is_file():
        locations(saved_nbt(boss), "minecraft:the_end")
    roster = world / "infinity-agents.json"
    agents = json.loads(roster.read_text()).get("agents", {}) if roster.is_file() else {}
    helpers = {identity(key) for key in agents}
    if None in helpers:
        raise ValueError("Invalid saved helper UUID; terrain refresh was cancelled")
    locations(agents)
    found = set()
    for path in (world / "dimensions").rglob("entities/r.*.*.mca"):
        dimension = path.parent.parent.relative_to(world / "dimensions").as_posix().replace("/", ":", 1)
        for record in region_records(path):
            entities = NBT(unpack(record[4], record[5])).parse()
            def passengers(entities):
                for entity in entities:
                    yield entity
                    yield from passengers(entity.get("Passengers", []))
            for entity in passengers(entities.get("Entities", entities.get("entities", []))):
                key = identity(entity.get("UUID"))
                if key in helpers:
                    found.add(key)
                if (key in helpers or key in vehicles or "infinity_agent" in entity.get("Tags", [])
                        or (entity.get("id") in PETS and identity(entity.get("Owner", entity.get("owner"))))
                        or entity.get("id") == "minecraft:ender_dragon"):
                    locations(entity, dimension)
    if helpers - found:
        raise ValueError("A saved helper entity could not be located; terrain refresh was cancelled")
    # Preserve existing End gateway blocks and their linked landing locations.
    for path in (world / "dimensions/minecraft/the_end/region").glob("r.*.*.mca"):
        for record in region_records(path):
            chunk = NBT(unpack(record[4], record[5])).parse()
            for entity in chunk.get("block_entities", []):
                if entity.get("id") == "minecraft:end_gateway":
                    locations(entity, "minecraft:the_end")

    outputs = {}
    total = 0
    def keep(name, data):
        nonlocal total
        total += len(data) - len(outputs.get(name, b""))
        if total > KEPT_LIMIT:
            raise ValueError("Protected terrain exceeds refresh memory limit; terrain was retained")
        outputs[name] = data
    for path in (world / "dimensions").rglob("r.*.*.mca"):
        if path.parent.name not in ("region", "poi", "entities"):
            continue
        dimension = path.parent.parent.relative_to(world / "dimensions").as_posix()
        selected = protected.get(dimension, set())
        if not selected:
            continue
        records = [r for r in region_records(path) if r[1] in selected]
        if not records:
            continue
        header, chunks = bytearray(8192), bytearray()
        for slot, pos, record, timestamp, payload, compression, external in records:
            start = 2 + len(chunks) // 4096
            size = len(record) // 4096
            header[slot * 4:slot * 4 + 4] = ((start << 8) | size).to_bytes(4, "big")
            header[4096 + slot * 4:4100 + slot * 4] = timestamp
            chunks.extend(record)
            if external:
                keep(external.relative_to(world).as_posix(), payload)
        keep(path.relative_to(world).as_posix(), bytes(header + chunks))
    return outputs, {name: len(chunks) for name, chunks in protected.items()}
