"""Resource regressions for isolated original gear, UV coverage and mapped armor.

Uses only the launcher's standard Python library. This validates pack contents,
not an authenticated client rendering result.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "java/src/main/resources/assets/convergence"
ART = ROOT / "artwork/refresh27"


def rgba_png(data):
    """Decode the bounded 8-bit RGBA atlases, including all PNG filter types."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    cursor = 8; compressed = bytearray(); width = height = None
    while cursor < len(data):
        size = struct.unpack(">I", data[cursor:cursor + 4])[0]
        kind = data[cursor + 4:cursor + 8]; payload = data[cursor + 8:cursor + 8 + size]
        if kind == b"IHDR":
            width, height, depth, color, compression, filter_, interlace = struct.unpack(">IIBBBBB", payload)
            assert (depth, color, compression, filter_, interlace) == (8, 6, 0, 0, 0)
            assert width <= 256 and height <= 256
        if kind == b"IDAT": compressed.extend(payload)
        cursor += size + 12
    stride = width * 4
    raw = zlib.decompress(bytes(compressed)); assert len(raw) == height * (stride + 1)
    rows = []; previous = bytearray(stride)
    for y in range(height):
        at = y * (stride + 1); kind = raw[at]; row = bytearray(raw[at + 1:at + 1 + stride])
        assert kind in range(5)
        for x in range(stride):
            a = row[x - 4] if x >= 4 else 0; b = previous[x]; c = previous[x - 4] if x >= 4 else 0
            if kind == 1: prediction = a
            elif kind == 2: prediction = b
            elif kind == 3: prediction = (a + b) // 2
            elif kind == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                prediction = a if pa <= pb and pa <= pc else b if pb <= pc else c
            else: prediction = 0
            row[x] = (row[x] + prediction) % 256
        rows.append(row); previous = row
    return width, height, rows


class OriginalArtworkTests(unittest.TestCase):
    def test_backpack_refresh_retains_earned_style_dye_and_equipment_metadata(self):
        equipment = json.loads((JAVA / "equipment/backpack.json").read_text())
        self.assertEqual(equipment["layers"]["humanoid"][0]["dyeable"], {"color_when_undyed": 4831653},
                         "Trail, Emerald and Dragon must retain their saved worn colors")
        spec = importlib.util.spec_from_file_location("refresh27_pack_art", ART / "pack_art.py")
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        overlay = {"texture": "convergence:backpack_overlay", "use_player_texture": True}
        equipment["layers"]["humanoid"].append(overlay.copy())
        generator.equipment_texture(equipment, "humanoid", "convergence:backpack_next")
        self.assertEqual(equipment["layers"]["humanoid"][0]["dyeable"], {"color_when_undyed": 4831653})
        self.assertEqual(equipment["layers"]["humanoid"][1], overlay, "Refreshing base artwork preserves extra layers")
        self.assertEqual(equipment["layers"]["humanoid"][0]["texture"], "convergence:backpack_next")

    def test_generated_asset_hashes_and_all_originals_are_preserved(self):
        manifest = json.loads((ART / "uv-manifest.json").read_text())
        for name, digest in manifest["sha256"].items():
            self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), digest, name)
        for name, digest in manifest["source_sha256"].items():
            self.assertEqual(hashlib.sha256((ART / name).read_bytes()).hexdigest(), digest, name)
        for name, digest in json.loads((ROOT / "TEXTURE_SHA256.json").read_text()).items():
            if (ROOT / name).exists(): self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), digest, name)

    def test_full_native_armor_uv_faces_are_opaque_and_unused_cells_transparent(self):
        manifest = json.loads((ART / "uv-manifest.json").read_text())
        faces = [(8,0,16,8),(16,0,24,8),(0,8,8,16),(8,8,16,16),(16,8,24,16),(24,8,32,16),
                 (4,16,8,20),(8,16,12,20),(0,20,4,32),(4,20,8,32),(8,20,12,32),(12,20,16,32),
                 (20,16,28,20),(28,16,36,20),(16,20,20,32),(20,20,28,32),(28,20,32,32),(32,20,40,32),
                 (44,16,48,20),(48,16,52,20),(40,20,44,32),(44,20,48,32),(48,20,52,32),(52,20,56,32)]
        used = {(x,y) for x0,y0,x1,y1 in faces for y in range(y0*2,y1*2) for x in range(x0*2,x1*2)}
        for collection in ("convergence", "aurora", "ember", "backpack"):
            self.assertCountEqual([tuple(v) for v in manifest["armor"][collection]["uv_rectangles"].values()], faces)
            for layer in ("humanoid", "humanoid_leggings"):
                data = (JAVA / f"textures/entity/equipment/{layer}/{collection}_refresh27.png").read_bytes()
                w,h,rows = rgba_png(data); self.assertEqual((w,h),(128,64))
                for y in range(h):
                    for x in range(w): self.assertEqual(rows[y][x*4+3],255 if (x,y) in used else 0,f"{collection}/{layer} UV at {x},{y}")
                bed = ROOT / f"bedrock/resource_pack/textures/convergence/models/armor/refresh27/{collection}_refresh27.png"
                self.assertEqual(data,bed.read_bytes(),"The two editions use the same packed original face artwork")

    def test_all_registered_icons_and_block_models_use_original_custom_assets(self):
        manifest = json.loads((ART / "uv-manifest.json").read_text())
        for name in manifest["icons"]:
            definition = json.loads((JAVA / f"items/{name}.json").read_text())
            self.assertEqual(definition["model"]["model"],"convergence:item/"+name)
            model = json.loads((JAVA / f"models/item/{name}.json").read_text())
            self.assertEqual(model["textures"]["layer0"],"convergence:item/refresh27/"+name)
            image = JAVA / f"textures/item/refresh27/{name}.png"
            self.assertEqual(rgba_png(image.read_bytes())[:2],(64,64))
        for name in manifest["blocks"]:
            model = json.loads((JAVA / f"models/block/{name}.json").read_text())
            self.assertEqual(model["textures"]["all"],"convergence:block/refresh27/"+name)
            self.assertEqual(rgba_png((JAVA / f"textures/block/refresh27/{name}.png").read_bytes())[:2],(32,32))
        armor = json.loads((JAVA / "equipment/cuirass.json").read_text())
        self.assertNotIn("wings",armor["layers"],"The separate solid chestplate must not draw or grant wings")

    def test_prepared_pack_has_complete_static_attachables_without_native_armor_overrides(self):
        wearable = {"helmet":"head","cuirass":"chest","leggings":"legs","boots":"feet","backpack":"chest"}
        for outfit in ("aurora","ember"):
            for piece,slot in (("helmet","head"),("chestplate","chest"),("leggings","legs"),("boots","feet")):
                wearable[outfit+"_"+piece] = slot
        with zipfile.ZipFile(ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
            identifiers = []
            for name in pack.namelist():
                if name.startswith("attachables/"):
                    identifiers.append(json.loads(pack.read(name))["minecraft:attachable"]["description"]["identifier"])
            self.assertCountEqual(identifiers,["convergence:"+name for name in wearable]+["minecraft:elytra"])
            self.assertEqual(len(identifiers),len(set(identifiers)),"Legacy duplicate attachables must not override the refreshed ones")
            for name,slot in wearable.items():
                desc = json.loads(pack.read(f"attachables/refresh27/{name}.json"))["minecraft:attachable"]["description"]
                self.assertEqual(desc["identifier"],"convergence:"+name)
                part = {"head":"helmet","chest":"chestplate","legs":"leggings","feet":"boots"}[slot]
                self.assertEqual(desc["geometry"]["default"],"geometry.humanoid.armor."+part)
                self.assertEqual(desc["render_controllers"],["controller.render.armor"])
                self.assertIn(desc["textures"]["default"]+".png",pack.namelist())
            for material in ("netherite","leather"):
                for layer in (1,2): self.assertNotIn(f"textures/models/armor/{material}_{layer}.png",pack.namelist())
            manifest = json.loads(pack.read("manifest.json"))
            version = re.search(r'^VERSION = "([^"\n]+)"$', (ROOT / "tools/prepare_crossplay.py").read_text(), re.MULTILINE).group(1)
            expected_version = [2,12,int(version.rsplit(".",1)[-1])]
            self.assertEqual(manifest["header"]["version"],expected_version)
            self.assertTrue(all(m["version"] == expected_version for m in manifest["modules"]))

    def test_preparation_retains_fresh_block_atlases(self):
        names = json.loads((ART / "uv-manifest.json").read_text())["blocks"]
        with zipfile.ZipFile(ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
            for atlas_name in ("item_texture","terrain_texture"):
                atlas = json.loads(pack.read(f"textures/{atlas_name}.json"))["texture_data"]
                for name in names:
                    path = atlas["convergence_"+name]["textures"]
                    self.assertEqual(path,"textures/convergence/blocks/refresh27/"+name)
                    self.assertIn(path+".png",pack.namelist())


if __name__ == "__main__": unittest.main()
