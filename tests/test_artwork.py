"""Resource regressions for isolated original gear, UV coverage and mapped armor.

Uses only the launcher's standard Python library. This validates pack contents,
not an authenticated client rendering result.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import struct
import tempfile
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


def model_references(value):
    """Walk conditional/selected models as well as ordinary single sprites."""
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "model" and isinstance(child, str):
                yield child
            else:
                yield from model_references(child)
    elif isinstance(value, list):
        for child in value:
            yield from model_references(child)


def selected_model(node, properties):
    """Evaluate the asset contract for real use-state/display-context examples."""
    kind = node["type"]
    if kind == "minecraft:model":
        return node["model"]
    value = properties.get(node["property"])
    if kind == "minecraft:condition":
        return selected_model(node["on_true" if value else "on_false"], properties)
    if kind == "minecraft:select":
        for case in node["cases"]:
            when = case["when"] if isinstance(case["when"], list) else [case["when"]]
            if value in when:
                return selected_model(case["model"], properties)
        return selected_model(node["fallback"], properties)
    if kind == "minecraft:range_dispatch":
        current = node["fallback"]
        for entry in node["entries"]:
            if float(value or 0) * node.get("scale", 1) >= entry["threshold"]:
                current = entry["model"]
        return selected_model(current, properties)
    raise AssertionError("Unexpected item model type: " + kind)


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
            references = list(model_references(definition))
            self.assertIn("convergence:item/"+name,references)
            for reference in references:
                self.assertTrue(reference.startswith("convergence:item/"), "Custom sprites never select a native gear model")
                self.assertTrue((JAVA / ("models/"+reference.split(":",1)[1]+".json")).is_file(),reference)
            model = json.loads((JAVA / f"models/item/{name}.json").read_text())
            repaired_armor = {"helmet", "cuirass", "leggings", "boots"} | {
                outfit+"_"+piece for outfit in ("aurora", "ember")
                for piece in ("helmet", "chestplate", "leggings", "boots")}
            folder = "armor42" if name in repaired_armor else "refresh27"
            self.assertEqual(model["textures"]["layer0"],"convergence:item/"+folder+"/"+name)
            image = JAVA / f"textures/item/{folder}/{name}.png"
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
            held = ("bow", "crossbow", "shield")
            forge = tuple(suit + "_" + piece for suit in ("elemental", "infinity")
                          for piece in ("helmet", "chestplate", "leggings", "boots"))
            self.assertCountEqual(identifiers,[prefix+name for prefix in ("convergence:","infinity_catalog:") for name in (*wearable, *held, *forge)])
            self.assertEqual(len(identifiers),len(set(identifiers)),"Legacy duplicate attachables must not override the refreshed ones")
            for name,slot in wearable.items():
                desc = json.loads(pack.read(f"attachables/refresh27/{name}.json"))["minecraft:attachable"]["description"]
                self.assertEqual(desc["identifier"],"convergence:"+name)
                self.assertEqual(desc["geometry"]["default"],"geometry.convergence."+name+"_original")
                self.assertEqual(desc["render_controllers"],["controller.render.convergence."+name+"_original"])
                geometry = json.loads(pack.read(f"models/entity/original_armor/{name}.geo.json"))["minecraft:geometry"][0]
                self.assertEqual(geometry["description"]["identifier"],desc["geometry"]["default"])
                self.assertIn(desc["textures"]["default"]+".png",pack.namelist())
                alias = json.loads(pack.read(f"attachables/refresh27/catalog_{name}.json"))["minecraft:attachable"]["description"]
                self.assertEqual(alias,dict(desc,identifier="infinity_catalog:"+name),"Prediction aliases share exactly the real item's worn artwork")
            controllers = json.loads(pack.read("render_controllers/original_static_armor.json"))["render_controllers"]
            self.assertCountEqual(controllers,["controller.render.convergence."+name+"_original" for name in wearable])
            self.assertNotIn("attachables/elytra.json",pack.namelist(),"Ordinary elytra must keep its native model, cape behavior and animations")
            self.assertNotIn("textures/items/elytra.png",pack.namelist())
            self.assertNotIn("textures/items/broken_elytra.png",pack.namelist())
            for material in ("netherite","leather"):
                for layer in (1,2): self.assertNotIn(f"textures/models/armor/{material}_{layer}.png",pack.namelist())
                for piece in ("helmet","chestplate","leggings","boots"):
                    self.assertNotIn(f"textures/items/{material}_{piece}.png",pack.namelist())
            manifest = json.loads(pack.read("manifest.json"))
            version = re.search(r'^VERSION = "([^"\n]+)"$', (ROOT / "tools/prepare_crossplay.py").read_text(), re.MULTILINE).group(1)
            spec = importlib.util.spec_from_file_location("resource_revision", ROOT / "tools/crossplay_resources.py")
            resources = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(resources)
            expected_version = resources.resource_version(version)
            self.assertEqual(manifest["header"]["version"],expected_version)
            self.assertTrue(all(m["version"] == expected_version for m in manifest["modules"]))

    def test_independent_static_armor_binds_each_moving_joint_and_opaque_original_uv(self):
        bed = ROOT / "bedrock/resource_pack"
        controllers = json.loads((bed / "render_controllers/original_static_armor.json").read_text())["render_controllers"]
        expected = {"helmet":{"head"},"cuirass":{"body","rightArm","leftArm"},
                    "leggings":{"rightLeg","leftLeg"},"boots":{"rightLeg","leftLeg"},"backpack":{"body"}}
        for outfit in ("aurora","ember"):
            for piece in ("helmet","chestplate","leggings","boots"):
                expected[outfit+"_"+piece] = expected["cuirass" if piece=="chestplate" else piece]
        self.assertCountEqual([p.stem.replace(".geo","") for p in (bed / "models/entity/original_armor").glob("*.geo.json")],
                              list(expected)+["flight_shell"])
        for name,joints in expected.items():
            desc = json.loads((bed / f"attachables/refresh27/{name}.json").read_text())["minecraft:attachable"]["description"]
            geo = json.loads((bed / f"models/entity/original_armor/{name}.geo.json").read_text())["minecraft:geometry"][0]
            self.assertEqual(geo["description"]["identifier"],"geometry.convergence."+name+"_original")
            self.assertNotIn("geometry.humanoid",json.dumps(geo)+json.dumps(desc))
            self.assertEqual({b["binding"] for b in geo["bones"]},{"'"+joint+"'" for joint in joints},"Arms/legs inherit their own animated player joint")
            self.assertEqual(len(geo["bones"]),len(joints),"No copied player skeleton or unused geometry bones")
            width,height,rows = rgba_png((bed / (desc["textures"]["default"]+".png")).read_bytes())
            controller = controllers[desc["render_controllers"][0]]
            self.assertEqual(controller["geometry"],"Geometry.default")
            self.assertIn("Material.enchanted",controller["materials"][0]["*"])
            self.assertEqual(controller["textures"],["Texture.default","Texture.enchanted"])
            self.assertCountEqual([next(iter(entry)) for entry in controller["part_visibility"]],[b["name"] for b in geo["bones"]])
            for entry in controller["part_visibility"]:
                value = next(iter(entry.values()))
                self.assertIn("convergence:"+name,value)
                self.assertIn("infinity_catalog:"+name,value)
            for b in geo["bones"]:
                self.assertTrue(b["cubes"])
                for cube in b["cubes"]:
                    self.assertTrue(all(n>0 for n in cube["size"]))
                    self.assertCountEqual(cube["uv"],("up","down","east","west","north","south"))
                    for face in cube["uv"].values():
                        x,y = face["uv"]; w,h = face["uv_size"]
                        self.assertTrue(0<=x<x+w<=width and 0<=y<y+h<=height,"Every authored face stays inside its original atlas")
                        for py in range(y,y+h):
                            for px in range(x,x+w):
                                self.assertEqual(rows[py][px*4+3],255,"No face samples the transparent unused UV cells")

    def test_original_flight_shell_has_animated_joint_bindings_and_opaque_faces(self):
        bed = ROOT / "bedrock/resource_pack"
        wings = json.loads((bed / "attachables/elytra.json").read_text())["minecraft:attachable"]["description"]
        geometry = json.loads((bed / "models/entity/original_armor/flight_shell.geo.json").read_text())["minecraft:geometry"][0]
        self.assertEqual(geometry["description"]["identifier"],"geometry.convergence.flight_shell_original")
        self.assertEqual(wings["geometry"],{"default":"geometry.elytra","infinity_armor":geometry["description"]["identifier"]})
        self.assertEqual(wings["animations"],{"default_controller":"controller.animation.elytra.default",
            **{state:"animation.elytra."+state for state in ("default","gliding","sneaking","sleeping","swimming")}})
        self.assertEqual(wings["scripts"],{"parent_setup":"variable.chest_layer_visible = 0.0;","animate":["default_controller"]})
        self.assertEqual(wings["materials"],{"default":"elytra","enchanted":"elytra_glint",
            "infinity_armor":"armor","infinity_armor_enchanted":"armor_enchanted"})
        self.assertEqual(wings["render_controllers"],["controller.render.armor","controller.render.convergence.flight_chest"])
        controller = json.loads((bed / "render_controllers/flight.json").read_text())["render_controllers"]["controller.render.convergence.flight_chest"]
        self.assertEqual(controller["geometry"],"Geometry.infinity_armor")
        self.assertEqual(controller["textures"],["Texture.infinity_armor","Texture.enchanted"])
        bones = geometry["bones"]
        self.assertEqual({b["binding"] for b in bones},{"'body'","'rightArm'","'leftArm'"})
        self.assertEqual(len(bones),3,"The extra shell binds only its torso and short shoulder guards, with no wing skeleton")
        self.assertTrue(all(b["name"].startswith("infinity_flight_") for b in bones))
        static = json.loads((bed / "models/entity/original_armor/cuirass.geo.json").read_text())["minecraft:geometry"][0]
        self.assertNotEqual([b["cubes"] for b in bones],[b["cubes"] for b in static["bones"]],
                            "Flight uses an open segmented harness, not a copied solid chestplate")
        width,height,rows = rgba_png((bed / (wings["textures"]["infinity_armor"]+".png")).read_bytes())
        self.assertEqual((geometry["description"]["texture_width"],geometry["description"]["texture_height"]),(width,height))
        for b in bones:
            for cube in b["cubes"]:
                self.assertTrue(all(n>0 for n in cube["size"]))
                self.assertCountEqual(cube["uv"],("up","down","east","west","north","south"))
                for face in cube["uv"].values():
                    x,y = face["uv"]; w,h = face["uv_size"]
                    self.assertTrue(0<=x<x+w<=width and 0<=y<y+h<=height)
                    for py in range(y,y+h):
                        for px in range(x,x+w):
                            self.assertEqual(rows[py][px*4+3],255,"The flight shell must not sample unused transparent cells")

    def test_bedrock_generator_reproduces_models_without_other_armor_models_or_png_changes(self):
        spec = importlib.util.spec_from_file_location("original_bedrock_models",ART / "resource_models.py")
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        source = ROOT / "bedrock/resource_pack"
        with tempfile.TemporaryDirectory() as directory:
            isolated = Path(directory)
            bed = isolated / "bedrock/resource_pack"
            bed.mkdir(parents=True)
            # No existing static geometry, attachables or controller is supplied.
            # Keep only the native flight attachment and bitmap inputs untouched.
            shutil.copytree(source / "textures",bed / "textures")
            native = json.loads((source / "attachables/elytra.json").read_text())
            native["minecraft:attachable"]["description"]["geometry"]["infinity_armor"] = "geometry.humanoid.armor.chestplate"
            (bed / "attachables").mkdir()
            (bed / "attachables/elytra.json").write_text(json.dumps(native))
            (isolated / "artwork/refresh27").mkdir(parents=True)
            shutil.copy2(ART / "uv-manifest.json",isolated / "artwork/refresh27/uv-manifest.json")
            hashes = lambda:{p.relative_to(bed).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in bed.rglob("*.png")}
            before_pngs = hashes()
            generator.ROOT = isolated
            generator.BED = bed
            generator.bedrock_models()
            generated = {p.relative_to(bed).as_posix():p.read_bytes() for p in bed.rglob("*.json")}
            self.assertEqual(hashes(),before_pngs,"Model generation must preserve all custom and legacy native PNG bytes")
            updated = json.loads((bed / "attachables/elytra.json").read_text())
            native["minecraft:attachable"]["description"]["geometry"]["infinity_armor"] = "geometry.convergence.flight_shell_original"
            self.assertEqual(updated,native,"Only the extra chest geometry may change in the native flight attachment")
            for name,data in generated.items():
                self.assertEqual(data,(source / name).read_bytes(),"The checked-in model must reproduce without inherited geometry: "+name)
            generator.bedrock_models()
            self.assertEqual(generated,{p.relative_to(bed).as_posix():p.read_bytes() for p in bed.rglob("*.json")})
            self.assertEqual(hashes(),before_pngs)

    def test_ranged_blocking_and_spear_models_have_real_state_and_context_branches(self):
        items = {n:json.loads((JAVA / f"items/{n}.json").read_text())["model"] for n in ("bow","crossbow","shield","spear")}
        choose = lambda name,**p:selected_model(items[name],p).split("/")[-1]
        self.assertEqual(choose("bow",**{"minecraft:using_item":False,"minecraft:use_duration":20}),"bow")
        for ticks,stage in ((1,0),(13,1),(18,2)):
            self.assertEqual(choose("bow",**{"minecraft:using_item":True,"minecraft:use_duration":ticks}),f"bow_pulling_{stage}")
        for pull,stage in ((0.1,0),(0.58,1),(1,2)):
            self.assertEqual(choose("crossbow",**{"minecraft:using_item":True,"minecraft:crossbow/pull":pull}),f"crossbow_pulling_{stage}")
        for charge in ("arrow","rocket"):
            self.assertEqual(choose("crossbow",**{"minecraft:charge_type":charge,"minecraft:using_item":False}),"crossbow_"+charge)
        self.assertEqual(choose("shield",**{"minecraft:display_context":"gui","minecraft:using_item":True}),"shield_icon")
        self.assertEqual(choose("shield",**{"minecraft:display_context":"firstperson_righthand","minecraft:using_item":True}),"shield_blocking")
        self.assertEqual(choose("shield",**{"minecraft:display_context":"firstperson_lefthand","minecraft:using_item":False}),"shield")
        for context in ("gui","ground","fixed","on_shelf"):
            self.assertEqual(choose("spear",**{"minecraft:display_context":context}),"spear")
        self.assertEqual(choose("spear",**{"minecraft:display_context":"thirdperson_righthand"}),"spear_in_hand")
        for name in ("bow","crossbow"):
            poses = [json.loads((JAVA / f"models/item/{name}_pulling_{i}.json").read_text())["display"] for i in range(3)]
            self.assertEqual(len({json.dumps(p,sort_keys=True) for p in poses}),3,"Drawing changes the held pose, not just an unused model identifier")

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
