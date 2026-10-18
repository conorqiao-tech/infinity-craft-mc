"""Evaluate authored armor visibility rules, not the Bedrock engine or its queries.

The owner observed shoulder/arm shells without head, torso, thighs or feet.
These resource-only regressions inject independent camera/UI/query values into
the actual source and prepared-pack expressions. They cannot establish which
values an iPad supplies, attachable activation, native culling, or rendered pixels.
"""
import itertools
import json
from pathlib import Path
import re
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
BED = ROOT / "bedrock/resource_pack"
PACK = ROOT / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack"
PREFIXES = ("convergence:", "infinity_catalog:")
SLOTS = ("head", "chest", "legs", "feet")
SUITS = [("helmet", "cuirass", "leggings", "boots")]
SUITS += [tuple(suit + "_" + piece for piece in ("helmet", "chestplate", "leggings", "boots"))
          for suit in ("aurora", "ember", "elemental", "infinity")]
ARMOR_SLOTS = {name: "slot.armor." + slot for suit in SUITS for name, slot in zip(suit, SLOTS)}
EXPECTED_JOINTS = {"head": {"head"}, "chest": {"body", "rightarm", "leftarm"},
                   "legs": {"rightleg", "leftleg"}, "feet": {"rightleg", "leftleg"}}


class VisibilityExpression:
    """Small, fail-closed subset: booleans, !/&&/||, grouping and item queries.

    No eval, Python translation, variable assignment or arbitrary Molang calls.
    Limits keep an accidental generator change from becoming an unbounded parser.
    """
    TOKEN = re.compile(r"\s*(\&\&|\|\||[!(),]|'[^'\\\r\n]*'|[A-Za-z_][A-Za-z0-9_.]*|[0-9]+(?:\.[0-9]+)?)")
    FLAGS = {"query.is_first_person", "query.is_in_ui", "context.is_first_person"}
    VALID_SLOTS = {"slot.armor." + slot for slot in SLOTS} | {
        "slot.weapon.mainhand", "slot.weapon.offhand"}

    def __init__(self, expression):
        if not isinstance(expression, str) or not 0 < len(expression) <= 1024:
            raise ValueError("Visibility expression must contain 1–1024 characters")
        self.tokens = []
        cursor = 0
        while cursor < len(expression.rstrip()):
            match = self.TOKEN.match(expression, cursor)
            if match is None:
                raise ValueError("Unsupported visibility syntax at " + str(cursor))
            self.tokens.append(match.group(1))
            cursor = match.end()
        if not 0 < len(self.tokens) <= 128:
            raise ValueError("Visibility expression must contain 1–128 tokens")
        self.cursor = 0
        self.tree = self.parse_or(0)
        if self.cursor != len(self.tokens):
            raise ValueError("Unexpected trailing visibility tokens")

    def take(self, token):
        if self.cursor < len(self.tokens) and self.tokens[self.cursor] == token:
            self.cursor += 1
            return True
        return False

    def require(self, token):
        if not self.take(token):
            raise ValueError("Expected visibility token " + token)

    def parse_or(self, depth):
        result = self.parse_and(depth)
        while self.take("||"):
            result = ("or", result, self.parse_and(depth))
        return result

    def parse_and(self, depth):
        result = self.parse_unary(depth)
        while self.take("&&"):
            result = ("and", result, self.parse_unary(depth))
        return result

    def parse_unary(self, depth):
        if depth > 16:
            raise ValueError("Visibility expression nesting exceeds 16")
        if self.take("!"):
            return ("not", self.parse_unary(depth + 1))
        if self.take("("):
            result = self.parse_or(depth + 1)
            self.require(")")
            return result
        if self.cursor >= len(self.tokens):
            raise ValueError("Incomplete visibility expression")
        token = self.tokens[self.cursor]
        self.cursor += 1
        if token in self.FLAGS:
            return ("flag", token)
        if token in ("true", "false", "0", "0.0", "1", "1.0"):
            return ("constant", token in ("true", "1", "1.0"))
        if token != "query.is_item_name_any":
            raise ValueError("Unsupported visibility value " + token)
        self.require("(")
        slot = self.string()
        if slot not in self.VALID_SLOTS:
            raise ValueError("Unsupported equipment slot " + slot)
        self.require(",")
        if self.take("0"):
            self.require(",")
        names = [self.string()]
        while self.take(","):
            names.append(self.string())
        self.require(")")
        return ("item", slot, frozenset(names))

    def string(self):
        if self.cursor >= len(self.tokens) or not self.tokens[self.cursor].startswith("'"):
            raise ValueError("Expected a literal equipment slot/item identifier")
        value = self.tokens[self.cursor][1:-1]
        self.cursor += 1
        return value

    def evaluate(self, equipment, first_person, in_ui, legacy_first_person):
        flags = {"query.is_first_person": first_person, "query.is_in_ui": in_ui,
                 "context.is_first_person": legacy_first_person}

        def visit(node):
            kind = node[0]
            if kind == "flag":
                return flags[node[1]]
            if kind == "constant":
                return node[1]
            if kind == "item":
                return equipment.get(node[1], "") in node[2]
            if kind == "not":
                return not visit(node[1])
            if kind == "and":
                return visit(node[1]) and visit(node[2])
            if kind == "or":
                return visit(node[1]) or visit(node[2])
            raise ValueError("Unknown visibility expression node")

        return bool(visit(self.tree))


def resources(read_json, names):
    """Resolve only the selected armor definitions, never legacy duplicates."""
    attachables, geometries, controllers = {}, {}, {}
    for name in names:
        if name.startswith(("attachables/refresh27/", "attachables/elemental41/")) and name.endswith(".json"):
            description = read_json(name)["minecraft:attachable"]["description"]
            identifier = description["identifier"]
            if identifier in attachables:
                raise ValueError("Duplicate active attachable " + identifier)
            attachables[identifier] = description
        elif name.startswith(("models/entity/original_armor/", "models/entity/elemental41/")) and name.endswith(".geo.json"):
            for geometry in read_json(name)["minecraft:geometry"]:
                identifier = geometry["description"]["identifier"]
                if identifier in geometries:
                    raise ValueError("Duplicate active geometry " + identifier)
                geometries[identifier] = geometry
        elif name in ("render_controllers/original_static_armor.json", "render_controllers/elemental41.json"):
            for identifier, controller in read_json(name)["render_controllers"].items():
                if identifier in controllers:
                    raise ValueError("Duplicate active armor controller " + identifier)
                controllers[identifier] = controller
    return attachables, geometries, controllers


class ArmorRenderVisibilityTests(unittest.TestCase):
    def check_resources(self, data, location):
        attachables, geometries, controllers = data
        identities = {prefix + name for name in ARMOR_SLOTS for prefix in PREFIXES}
        self.assertEqual(len(identities), 40)
        self.assertTrue(identities <= attachables.keys(), (location, identities - attachables.keys()))
        # The backpack is a non-arm chest attachment with the same camera rule.
        item_slots = dict(ARMOR_SLOTS, backpack="slot.armor.chest")
        checked = set()
        for name, slot in item_slots.items():
            for prefix in PREFIXES:
                identity = prefix + name
                with self.subTest(location=location, identity=identity):
                    description = attachables[identity]
                    geometry = geometries[description["geometry"]["default"]]
                    self.assertEqual(len(description["render_controllers"]), 1)
                    controller = controllers[description["render_controllers"][0]]
                    expressions = {}
                    for entry in controller["part_visibility"]:
                        self.assertEqual(len(entry), 1)
                        bone_name, expression = next(iter(entry.items()))
                        self.assertNotIn(bone_name, expressions)
                        expressions[bone_name] = VisibilityExpression(expression)
                    self.assertEqual(set(expressions), {bone["name"] for bone in geometry["bones"]})
                    joints = {bone["binding"].strip("'") for bone in geometry["bones"]}
                    expected_joints = {"body"} if name == "backpack" else EXPECTED_JOINTS[slot.rsplit(".", 1)[1]]
                    self.assertEqual(joints, expected_joints)
                    # Both identities may appear on the wire, regardless of which
                    # alias definition selected this same worn model/controller.
                    for worn_prefix, first, ui, legacy in itertools.product(PREFIXES, (False, True), (False, True), (False, True)):
                        matching = worn_prefix + name
                        # Correct equipment, empty slot while held, vanilla gear
                        # while held, wrong custom gear, and the right item in a
                        # wrong armor slot must all be distinguished by the rule.
                        wrong_slot = "slot.armor.feet" if slot != "slot.armor.feet" else "slot.armor.head"
                        other_name = next(item for item, item_slot in item_slots.items()
                                          if item_slot == slot and item != name)
                        states = (
                            ("equipped", {slot: matching}, True),
                            ("held_only", {"slot.weapon.mainhand": matching}, False),
                            ("offhand_only", {"slot.weapon.offhand": matching}, False),
                            ("vanilla_equipped_while_held", {slot: "minecraft:leather_helmet", "slot.weapon.mainhand": matching}, False),
                            ("different_custom_in_same_slot", {slot: worn_prefix + other_name}, False),
                            ("wrong_armor_slot", {wrong_slot: matching}, False),
                        )
                        for state, equipment, equipped in states:
                            for bone in geometry["bones"]:
                                arm = bone["binding"] in ("'rightarm'", "'leftarm'")
                                expected = equipped and (arm or ui or not first)
                                actual = expressions[bone["name"]].evaluate(equipment, first, ui, legacy)
                                with self.subTest(state=state, worn=matching, bone=bone["name"], first_person=first, ui=ui, legacy_first_person=legacy):
                                    self.assertEqual(actual, expected,
                                                     "Authored rule must follow render queries, allow UI, and reject held/unmatched items; engine queries are not evaluated")
                    checked.add(identity)
        self.assertEqual(checked & identities, identities)

    def test_source_armor_and_aliases_follow_render_camera_and_ui(self):
        names = [path.relative_to(BED).as_posix() for path in BED.rglob("*.json")]
        self.check_resources(resources(lambda name: json.loads((BED / name).read_text()), names), "source")

    def test_prepared_pack_armor_and_aliases_follow_render_camera_and_ui(self):
        with zipfile.ZipFile(PACK) as archive:
            self.assertEqual(len(archive.namelist()), len(set(archive.namelist())))
            self.check_resources(resources(lambda name: json.loads(archive.read(name)), archive.namelist()), "prepared pack")

    def test_historical_gate_reproduces_shoulder_only_regression(self):
        equipped = "query.is_item_name_any('slot.armor.chest', 0, 'convergence:cuirass', 'infinity_catalog:cuirass')"
        arms = VisibilityExpression(equipped)
        torso = VisibilityExpression(equipped + " && !context.is_first_person")
        equipment = {"slot.armor.chest": "convergence:cuirass"}
        self.assertTrue(arms.evaluate(equipment, False, False, True))
        self.assertFalse(torso.evaluate(equipment, False, False, True))
        self.assertFalse(torso.evaluate(equipment, False, True, True))

    def test_visibility_evaluator_rejects_unrecognized_or_unbounded_syntax(self):
        for expression in ("query.unrecognized", "query.is_in_ui; return 1;", "1 + 1",
                           "(" * 18 + "true" + ")" * 18, "true || " * 129 + "true"):
            with self.subTest(expression=expression[:70]):
                with self.assertRaises(ValueError):
                    VisibilityExpression(expression)


if __name__ == "__main__":
    unittest.main()
