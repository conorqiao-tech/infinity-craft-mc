import gzip
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import terrain_storage as storage


def nbt(value):
    def kind(v):
        return {int: 3, float: 6, str: 8, list: 9, dict: 10}[type(v)]
    def text(v):
        data = v.encode(); return struct.pack(">H", len(data)) + data
    def payload(v):
        tag = kind(v)
        if tag == 3: return struct.pack(">i", v)
        if tag == 6: return struct.pack(">d", v)
        if tag == 8: return text(v)
        if tag == 9:
            return bytes([kind(v[0]) if v else 10]) + struct.pack(">i", len(v)) + b"".join(payload(x) for x in v)
        return b"".join(bytes([kind(x)]) + text(k) + payload(x) for k, x in v.items()) + b"\x00"
    return b"\x0a\x00\x00" + payload(value)


def region(path, chunks):
    path.parent.mkdir(parents=True, exist_ok=True)
    header, data = bytearray(8192), bytearray()
    for (x, z), value in chunks.items():
        body = zlib.compress(nbt(value))
        raw = (len(body) + 1).to_bytes(4, "big") + b"\x02" + body
        raw += b"\x00" * (-len(raw) % 4096)
        slot = x % 32 + z % 32 * 32
        header[slot * 4:slot * 4 + 4] = (((2 + len(data) // 4096) << 8) | (len(raw) // 4096)).to_bytes(4, "big")
        data.extend(raw)
    path.write_bytes(header + data)


class TerrainStorageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.world = Path(self.temp.name)
        (self.world / "players/data").mkdir(parents=True)
        (self.world / "level.dat").write_bytes(gzip.compress(nbt({"Data": {
            "spawn": {"dimension": "minecraft:overworld", "pos": [0, 80, 0]}}})))

    def tearDown(self):
        self.temp.cleanup()

    def test_saved_players_mode_locations_and_inventory_are_not_rewritten(self):
        path = self.world / "players/data/player.dat"
        original = gzip.compress(nbt({"Dimension": "minecraft:overworld", "Pos": [160.5, 80.0, 160.5],
            "Inventory": [{"id": "minecraft:diamond", "count": 37}],
            "InfinityModes": {"profiles": {"HARDCORE": {"place": json.dumps({
                "dimension": "convergence:hardcore", "x": -160.5, "y": 80, "z": -160.5})}}}}))
        path.write_bytes(original)
        file = self.world / "dimensions/minecraft/overworld/region/r.0.0.mca"
        region(file, {(10, 10): {"kept": "player terrain"}, (20, 20): {"removed": "old terrain"}})
        outputs, counts = storage.prepare(self.world)
        target = Path(self.temp.name) / "output/r.0.0.mca";target.parent.mkdir();target.write_bytes(outputs[file.relative_to(self.world).as_posix()])
        records = storage.region_records(target)
        self.assertEqual([r[1] for r in records], [(10, 10)])
        old = storage.region_records(file)[0]
        self.assertEqual(records[0][2], old[2])
        self.assertEqual(path.read_bytes(), original)
        self.assertEqual(counts["convergence/hardcore"], 25)

    def test_helpers_and_owned_pets_keep_chunks_and_ordinary_mobs_do_not(self):
        helper = "11111111-1111-1111-1111-111111111111"
        (self.world / "infinity-agents.json").write_text(json.dumps({"agents": {helper: {}}}))
        file = self.world / "dimensions/convergence/creative/entities/r.0.0.mca"
        region(file, {(10, 10): {"Entities": [{"id": "minecraft:iron_golem", "UUID": helper, "Pos": [160.5, 80.0, 160.5]}]},
                      (15, 15): {"Entities": [{"id": "minecraft:wolf", "owner": {"uuid": helper}, "Pos": [240.5, 80.0, 240.5]}]},
                      (25, 25): {"Entities": [{"id": "minecraft:pig", "Pos": [400.5, 80.0, 400.5]}]}})
        terrain = file.parent.parent / "region/r.0.0.mca"
        region(terrain, {(10, 10): {}, (15, 15): {}, (25, 25): {}})
        outputs, counts = storage.prepare(self.world)
        target = Path(self.temp.name) / "output/r.0.0.mca";target.parent.mkdir();target.write_bytes(outputs[file.relative_to(self.world).as_posix()])
        self.assertEqual([r[1] for r in storage.region_records(target)], [(10, 10), (15, 15)])
        self.assertEqual(counts["convergence/creative"], 50)

    def test_built_hub_footprint_is_kept_even_without_a_player_in_hub(self):
        (self.world / "infinity-built-in-lobbies.json").write_text('{"version":1}')
        file = self.world / "dimensions/convergence/hub/region/r.0.0.mca"
        region(file, {(3, 3): {"lobby": "adventure"}, (20, 20): {"removed": "distant terrain"}})
        outputs, counts = storage.prepare(self.world)
        target = Path(self.temp.name) / "output/r.0.0.mca";target.parent.mkdir()
        target.write_bytes(outputs[file.relative_to(self.world).as_posix()])
        self.assertEqual([r[1] for r in storage.region_records(target)], [(3, 3)])
        self.assertEqual(counts["convergence/hub"], 81)

    def test_native_hub_footprint_contract_still_fits_retained_area(self):
        import re
        source = (Path(__file__).resolve().parents[1] / "java/src/main/java/dev/convergence/LobbyServer.java").read_text()
        centers = re.findall(r'new BlockPos\((-?\d+), 81, (-?\d+)\)', source)
        self.assertEqual(len(centers), 6)
        for x, z in centers:
            for value in (int(x), int(z)):
                self.assertGreaterEqual((value - 12) // 16, -4)
                self.assertLessEqual((value + 12) // 16, 4)

    def test_unknown_helper_stops_planning_before_mutation(self):
        (self.world / "infinity-agents.json").write_text('{"agents":{"missing-helper":{}}}')
        with self.assertRaisesRegex(ValueError, "helper"):
            storage.prepare(self.world)

    def test_owned_projectiles_and_items_do_not_protect_old_terrain(self):
        file = self.world / "dimensions/convergence/creative/entities/r.0.0.mca"
        owner = "11111111-1111-1111-1111-111111111111"
        region(file, {(10, 10): {"Entities": [{"id": "minecraft:arrow", "Owner": owner, "Pos": [160.5, 80.0, 160.5]},
                                              {"id": "minecraft:item", "Owner": owner, "Pos": [160.5, 80.0, 160.5]}]}})
        outputs, counts = storage.prepare(self.world)
        self.assertNotIn("convergence/creative", counts)
        self.assertNotIn(file.relative_to(self.world).as_posix(), outputs)

    def test_normalized_roster_uuid_and_missing_current_player_position(self):
        helper = "ABCDEFABABCDEFABABCDEFABABCDEFAB"
        (self.world / "infinity-agents.json").write_text(json.dumps({"agents": {helper: {}}}))
        file = self.world / "dimensions/convergence/creative/entities/r.0.0.mca"
        region(file, {(10, 10): {"Entities": [{"id": "minecraft:iron_golem", "UUID": str(__import__('uuid').UUID(helper)),
                                               "Pos": [160.5, 80.0, 160.5]}]}})
        self.assertIn(file.relative_to(self.world).as_posix(), storage.prepare(self.world)[0])
        (self.world / "players/data/player.dat").write_bytes(gzip.compress(nbt({"Inventory": []})))
        with self.assertRaisesRegex(ValueError, "position"):
            storage.prepare(self.world)

    def test_native_region_and_total_retention_memory_limits_fail_closed(self):
        from unittest.mock import patch
        path = self.world / "dimensions/minecraft/overworld/region/r.0.0.mca"
        region(path, {(0, 0): {}})
        with patch.object(storage, "REGION_LIMIT", 256):
            with self.assertRaisesRegex(ValueError, "memory"):
                storage.prepare(self.world)
        with patch.object(storage, "KEPT_LIMIT", 256):
            with self.assertRaisesRegex(ValueError, "memory"):
                storage.prepare(self.world)

    def test_end_arena_gateways_and_linked_locations_are_retained(self):
        file = self.world / "dimensions/minecraft/the_end/region/r.0.0.mca"
        region(file, {(0, 0): {"block_entities": []}, (15, 15): {"block_entities": [{
            "id": "minecraft:end_gateway", "x": 240, "z": 240, "exit_portal": [1024, 80, 1024]}]}})
        outer = file.parent / "r.2.2.mca";region(outer, {(64, 64): {"block_entities": []}})
        outputs, counts = storage.prepare(self.world)
        self.assertIn(outer.relative_to(self.world).as_posix(), outputs)
        self.assertGreater(counts["minecraft/the_end"], 289)

    def test_native_empty_entity_regions_are_valid(self):
        path = self.world / "dimensions/convergence/hub/entities/r.0.0.mca"
        path.parent.mkdir(parents=True);path.write_bytes(b"")
        self.assertEqual(storage.region_records(path), [])
        storage.prepare(self.world)

    def test_damaged_nbt_and_regions_fail_closed(self):
        with self.assertRaises(ValueError): storage.NBT(b"\x0a").parse()
        path = self.world / "r.0.0.mca";path.write_bytes(b"truncated")
        with self.assertRaises(ValueError): storage.region_records(path)
        with self.assertRaises(ValueError): storage.unpack(b"", 4)

    def test_decompression_bomb_is_rejected(self):
        from unittest.mock import patch
        with patch.object(storage, "LIMIT", 256):
            with self.assertRaises(ValueError): storage.unpack(zlib.compress(b"x" * 1024), 2)

    def test_negative_chunk_coordinates_and_external_payload_are_preserved(self):
        path = self.world / "dimensions/minecraft/overworld/region/r.-1.-1.mca"
        region(path, {(-1, -1): {"kept": 1}})
        payload = storage.region_records(path)[0][4]
        raw = bytearray(path.read_bytes())
        raw[8192:8197] = b"\x00\x00\x00\x01\x82"
        path.write_bytes(raw)
        external = path.parent / "c.-1.-1.mcc"
        external.write_bytes(payload)
        outputs, _ = storage.prepare(self.world)
        records = storage.region_records(path)
        self.assertEqual(records[0][1], (-1, -1))
        self.assertIn(path.relative_to(self.world).as_posix(), outputs)
        self.assertEqual(outputs[external.relative_to(self.world).as_posix()], payload)
