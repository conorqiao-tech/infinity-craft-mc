"""Original held resource closure and regeneration, distinct from device rendering."""
import ast
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BED = ROOT / "bedrock/resource_pack"
SPEC = importlib.util.spec_from_file_location("original_held_resources", ROOT / "artwork/refresh27/held_models.py")
HELD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELD)


class HeldArtworkTests(unittest.TestCase):
    def test_owned_shield_hand_predicates_follow_native_mainhand_priority(self):
        # Evaluate the small boolean query subset, not an invented Bedrock
        # renderer. Expected hands follow pinned GeyserSession.attemptToBlock.
        # This cannot establish how an ordinary native offhand shield renders.
        def evaluate(expression, mainhand, offhand, blocking):
            def item_query(match):
                args = re.findall(r"'([^']+)'", match.group(1))
                actual = mainhand if args[0] == "slot.weapon.mainhand" else offhand
                self.assertIn(args[0], ("slot.weapon.mainhand", "slot.weapon.offhand"))
                return str(actual in args[1:])

            expression = re.sub(r"query\.is_item_name_any\(([^()]+)\)", item_query, expression)
            expression = expression.replace("query.blocking", str(blocking)).replace("&&", " and ").replace("!", " not ")

            def boolean(node):
                if isinstance(node, ast.Constant) and type(node.value) is bool:
                    return node.value
                if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
                    return not boolean(node.operand)
                if isinstance(node, ast.BoolOp) and isinstance(node.op, ast.And):
                    return all(boolean(value) for value in node.values)
                self.fail("Unsupported predicate syntax: " + ast.dump(node))

            return boolean(ast.parse(expression, mode="eval").body)

        resources = HELD.build_resources()
        for namespace, prefix in (("convergence", ""), ("infinity_catalog", "catalog_")):
            own = namespace + ":shield"
            cases = [(own, "minecraft:air", True, (True, False)),
                     (own, "convergence:shield", True, (True, False)),
                     (own, "infinity_catalog:shield", True, (True, False)),
                     (own, "minecraft:shield", True, (True, False)),
                     ("minecraft:shield", own, True, (False, False)),
                     ("minecraft:iron_sword", own, True, (False, True)),
                     ("minecraft:air", own, True, (False, True)),
                     ("minecraft:air", "minecraft:shield", True, (False, False)),
                     (own, own, False, (False, False))]
            scripts = resources["attachables/held/" + prefix + "shield.json"]["minecraft:attachable"]["description"]["scripts"]
            expressions = [statement.split(" = ", 1)[1].removesuffix(";") for statement in scripts["pre_animation"][:2]]
            for mainhand, offhand, blocking, expected in cases:
                with self.subTest(namespace=namespace, mainhand=mainhand, offhand=offhand, blocking=blocking):
                    self.assertEqual(tuple(evaluate(expression, mainhand, offhand, blocking) for expression in expressions), expected)

    def test_models_regenerate_in_empty_directory_without_importing_native_models(self):
        before = {p.relative_to(BED).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in BED.rglob("*.png")}
        expected = HELD.encoded_resources()
        with tempfile.TemporaryDirectory(prefix="infinity-held-art-") as temporary:
            empty = Path(temporary)
            HELD.generate(empty)
            actual = {p.relative_to(empty).as_posix(): p.read_bytes() for p in empty.rglob("*") if p.is_file()}
            self.assertEqual(actual, expected)
            HELD.generate(empty)
            self.assertEqual(actual, {p.relative_to(empty).as_posix(): p.read_bytes() for p in empty.rglob("*") if p.is_file()})
        self.assertEqual(before, {p.relative_to(BED).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in BED.rglob("*.png")})
        for name, data in expected.items():
            self.assertEqual(data, (BED / name).read_bytes(), name)

    def test_prepared_pack_contains_exact_models_textures_and_animation_closure(self):
        with zipfile.ZipFile(ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
            resources = {name: json.loads(pack.read(name)) for name in HELD.encoded_resources()}
            self.assertEqual(HELD.validate(resources), {"files":14,"attachables":8,"geometries":12,"animations":11,"render_controllers":4})
            for name, data in HELD.encoded_resources().items():
                self.assertEqual(pack.read(name), data, name)
            for name, body in resources.items():
                if not name.startswith("attachables/"):
                    continue
                desc = body["minecraft:attachable"]["description"]
                self.assertFalse(desc["identifier"].startswith("minecraft:"), "Original items must never override ordinary native weapons")
                for key, texture in desc["textures"].items():
                    if key != "enchanted":
                        self.assertEqual(pack.read(texture+".png"), (BED / (texture+".png")).read_bytes())

    def test_missing_geometry_animation_controller_or_wrong_namespace_is_rejected(self):
        for target in ("geometry", "animation", "controller", "namespace"):
            with self.subTest(target=target):
                broken = copy.deepcopy(HELD.build_resources())
                desc = broken["attachables/held/shield.json"]["minecraft:attachable"]["description"]
                if target == "geometry": desc["geometry"]["default"] = "geometry.minecraft.shield"
                elif target == "animation": desc["animations"]["block"] = "animation.missing"
                elif target == "controller": desc["render_controllers"] = ["controller.render.missing"]
                else: desc["identifier"] = "minecraft:shield"
                with self.assertRaises(AssertionError):
                    HELD.validate(broken)


if __name__ == "__main__":
    unittest.main()
