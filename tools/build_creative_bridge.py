#!/usr/bin/env python3
"""Build the pinned Geyser adapter, without Gradle or downloading dependencies.

The separate startup proof is enabled with -Dinfinity.creative.proof=true in an
isolated Geyser working directory containing infinity-catalogue.json. Its
extensions/infinitycreativebridge/ready.json includes actual request/codec
results and encoded packets for the separate Via/Fabric acceptance test.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
from snapshot5_dependencies import MINECRAFT

ROOT = Path(__file__).resolve().parents[1]
PIN = "17a9e00f3be106c797066664679ca73ebf25af693a750f378a9669ca93b4c07e"


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(65_536), b""):
            result.update(block)
    return result.hexdigest()


def validate_catalogue(path: Path) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= 131_072:
        raise ValueError("Missing or oversized public catalogue")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate catalogue JSON key")
            result[key] = value
        return result
    with path.open("rb") as stream:
        raw = stream.read(131_073)
    if len(raw) > 131_072:
        raise ValueError("Catalogue exceeded bounded read")
    data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    if not isinstance(data, dict) or set(data) != {"format_version", "minecraft", "items"} or type(data["format_version"]) is not int or data["format_version"] != 1 or data["minecraft"] != MINECRAFT:
        raise ValueError("Unsupported catalogue header")
    items = data["items"]
    if not isinstance(items, list) or not 1 <= len(items) <= 128:
        raise ValueError("Invalid catalogue count")
    required = {"path", "base", "name", "icon", "max_stack_size", "category", "protection", "handheld", "native_appearance"}
    allowed = required | {"max_damage", "slot"}
    import re
    paths = set()
    for item in items:
        if not isinstance(item, dict) or not required <= set(item) <= allowed:
            raise ValueError("Invalid catalogue item fields")
        item_path = item["path"]
        if not isinstance(item_path, str) or not re.fullmatch(r"[a-z0-9_]{1,64}", item_path) or item_path in paths:
            raise ValueError("Invalid or duplicate canonical item path")
        paths.add(item_path)
        if not isinstance(item["base"], str) or not re.fullmatch(r"minecraft:[a-z0-9_]{1,86}", item["base"]) or item["base"] == "minecraft:air":
            raise ValueError("Invalid vanilla item base")
        name, icon = item["name"], item["icon"]
        if not isinstance(name, str) or not 1 <= len(name) <= 128 or not name.strip() or any(ord(c) < 32 or ord(c) == 127 or c == "§" for c in name):
            raise ValueError("Invalid literal item name")
        if not isinstance(icon, str) or not re.fullmatch(r"[a-z0-9_./:]{1,128}", icon):
            raise ValueError("Invalid icon atlas key")
        if item["category"] not in {"equipment", "construction", "items", "nature"}:
            raise ValueError("Invalid Creative category")
        for key, low, high in (("max_stack_size", 1, 64), ("protection", 0, 30), ("max_damage", 1, 2**31 - 1)):
            if key in item and (type(item[key]) is not int or not low <= item[key] <= high):
                raise ValueError("Invalid numeric item component: " + key)
        if "max_damage" in item and item["max_stack_size"] != 1:
            raise ValueError("Damageable items must not stack")
        if "slot" in item and item["slot"] not in {"head", "chest", "legs", "feet", "body", "offhand", "mainhand"}:
            raise ValueError("Invalid equipment slot")
        if type(item["handheld"]) is not bool or type(item["native_appearance"]) is not bool:
            raise ValueError("Invalid item flag")
    return {"sha256": hashlib.sha256(raw).hexdigest(), "items": len(items)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--geyser", type=Path, default=ROOT / "server/geyser/Geyser-Standalone.jar")
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--output", type=Path, default=ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar")
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--catalogue", type=Path)
    parser.add_argument("--validate-only", action="store_true", help="Validate inputs only; does not run a JVM or write files")
    args = parser.parse_args()
    geyser = args.geyser.resolve()
    if not geyser.is_file() or digest(geyser) != PIN:
        raise SystemExit("Unsupported Geyser jar: exact pinned standalone artifact required")
    extension = ROOT / "geyser-extension"
    sources = sorted((extension / "src/main/java").rglob("*.java"))
    if not sources or len(sources) > 32 or any(p.stat().st_size > 262_144 for p in sources):
        raise SystemExit("Missing or oversized bridge sources")
    manifest = extension / "extension.yml"
    input_paths = [Path(__file__).resolve(), ROOT / "tools/snapshot5_dependencies.py", manifest, *sources]
    inputs = {str(p.relative_to(ROOT)): digest(p) for p in input_paths}
    catalogue = validate_catalogue(args.catalogue.resolve()) if args.catalogue else None
    if args.validate_only:
        print(json.dumps({"geyser_sha256": PIN, "sources": inputs, "catalogue": catalogue}, indent=2))
        return
    if not args.javac or not Path(args.javac).is_file():
        raise SystemExit("Select the documented JDK compiler with --javac")
    output = args.output.resolve(); output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="infinity-creative-build-") as temp:
        work = Path(temp); classes = work / "classes"; classes.mkdir()
        subprocess.run([args.javac, "--release", "21", "-encoding", "UTF-8", "-classpath", str(geyser), "-d", str(classes), *map(str, sources)], check=True, cwd=ROOT)
        if {str(p.relative_to(ROOT)): digest(p) for p in input_paths} != inputs or digest(geyser) != PIN:
            raise SystemExit("Bridge source/dependency changed during compilation; rebuild a frozen source snapshot")
        entries = {str(p.relative_to(classes)).replace(os.sep, "/"): p.read_bytes() for p in sorted(classes.rglob("*.class"))}
        entries["extension.yml"] = manifest.read_bytes()
        entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\nImplementation-Title: Infinity Creative Bridge\nImplementation-Version: 1.0.0\n\n"
        temporary = work / "InfinityCreativeBridge.jar"
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name, body in sorted(entries.items()):
                entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED; entry.external_attr = 0o644 << 16
                archive.writestr(entry, body)
        shutil.copyfile(temporary, output)
    receipt = {"format_version": 1, "geyser_sha256": PIN, "source_hashes": inputs,
               "extension_sha256": digest(output), "catalogue": catalogue,
               "proof_status": "not run by build; isolated Geyser startup proof and Via/Fabric validation required"}
    if args.receipt:
        args.receipt.parent.mkdir(parents=True, exist_ok=True)
        args.receipt.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), **receipt}, indent=2))


if __name__ == "__main__":
    main()
