"""Original asset contracts and prepared client pack coverage for Elemental Forge."""
import importlib.util
import json
from pathlib import Path
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]


class ElementalAssetTests(unittest.TestCase):
    def test_authored_images_uv_joint_bindings_and_complete_suit_seams(self):
        spec = importlib.util.spec_from_file_location("elemental_asset_validation", ROOT / "artwork/elemental41/validate.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.validate()

    def test_prepared_pack_keeps_all_new_canonical_and_catalog_wearables(self):
        bed = ROOT / "bedrock/resource_pack"
        with zipfile.ZipFile(ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
            for path in (bed / "attachables/elemental41").glob("*.json"):
                relative = path.relative_to(bed).as_posix()
                self.assertEqual(pack.read(relative), path.read_bytes())
            self.assertEqual(len([name for name in pack.namelist() if name.startswith("attachables/elemental41/")]), 16)
            for name in ["render_controllers/elemental41.json", "models/entity/original_armor/leggings.geo.json",
                         "models/entity/original_armor/aurora_leggings.geo.json", "models/entity/original_armor/ember_leggings.geo.json"]:
                self.assertEqual(pack.read(name), (bed / name).read_bytes(), "The prepared pack contains the actual repaired model")

    def test_new_recipes_have_unique_shapes_and_discoverable_real_results(self):
        data = ROOT / "java/src/main/resources/data/convergence"
        manifest = json.loads((ROOT / "artwork/elemental41/manifest.json").read_text())
        names = manifest["icons"] + manifest["blocks"]
        self.assertEqual(len(set(names)), 17)
        layouts = set()
        for name in names:
            recipe = json.loads((data / f"recipe/{name}.json").read_text())
            self.assertEqual(recipe["result"]["id"], "convergence:" + name)
            layout = tuple(tuple(recipe["key"].get(cell, "") for cell in row) for row in recipe["pattern"])
            self.assertNotIn(layout, layouts, "Each new layout crafts exactly one intended item")
            layouts.add(layout)
            discovery = json.loads((data / f"advancement/recipes/elemental41/{name}.json").read_text())
            self.assertEqual(discovery["rewards"]["recipes"], ["convergence:" + name])
            self.assertIn("has_core", discovery["criteria"])
            self.assertEqual(discovery["criteria"]["has_the_recipe"]["conditions"], {"recipes": "convergence:" + name}, "26.3 uses the native plural recipe predicate")


if __name__ == "__main__":
    unittest.main()
