#!/usr/bin/env python3
"""Refresh playable files and checksums after building the Java crossplay mod."""
import hashlib
import json
import re
from pathlib import Path
import shutil
import uuid
import zipfile
from generate_odyssey_assets import verify as verify_odyssey

ROOT = Path(__file__).resolve().parents[1]
VERSION = "2.13.0-explore.24"


def expected_native_tests():
    """Count registered test methods plus Fabric's one framework baseline."""
    manifest = json.loads((ROOT / "java/src/gametest/resources/fabric.mod.json").read_text())
    sources = ROOT / "java/src/gametest/java"
    annotated = {p.relative_to(sources).with_suffix("").as_posix().replace("/", ".")
                 for p in sources.rglob("*.java") if re.search(r"@GameTest\b", p.read_text())}
    assert annotated == set(manifest["entrypoints"]["fabric-gametest"]), "Register every native test class"
    return 1 + sum(len(re.findall(r"@GameTest\b", (ROOT / "java/src/gametest/java" / (name.replace(".", "/") + ".java")).read_text()))
                   for name in manifest["entrypoints"]["fabric-gametest"])


def expected_launcher_tests():
    import unittest
    return unittest.TestLoader().discover(str(ROOT / "tests"), pattern="test_*.py").countTestCases()


def prepare():
    verify_odyssey()
    server = ROOT / "server"
    if (server / "launcher.lock").exists():
        raise RuntimeError("Stop the live server before installing a new mod build.")
    mods = server / "fabric/mods"
    mods.mkdir(parents=True, exist_ok=True)
    # Only replace older builds of this same mod, not third-party mods.
    for old in mods.glob("Infinity-Armor-*.jar"):
        if old.name != f"Infinity-Armor-{VERSION}.jar":
            old.unlink()
    mod = ROOT / f"java/build/libs/Infinity-Armor-{VERSION}.jar"
    api = ROOT / "vendor/fabric-api-0.161.0+26.3.jar"
    with zipfile.ZipFile(mod) as jar:
        assert json.loads(jar.read("fabric.mod.json"))["version"] == VERSION
        assert not any("GameTests" in name or name.startswith("net/minecraft/") for name in jar.namelist())
    for file in [mod, api]:
        shutil.copy2(file, mods / file.name)

    resources = ROOT / "bedrock/resource_pack"
    files = {p.relative_to(resources).as_posix(): p.read_bytes() for p in resources.rglob("*") if p.is_file()}
    for name in list(files):
        # Ordinary native armor must keep Minecraft's texture. Geyser's native
        # wearable fallback cannot select a different skin just for our stack.
        if re.fullmatch(r"textures/models/armor/(netherite|leather)_[12]\.png",name) or re.fullmatch(r"textures/items/(netherite|leather)_(helmet|chestplate|leggings|boots)\.png",name):
            del files[name]
            continue
        if name.startswith("attachables/") and name not in ["attachables/helmet.json", "attachables/leggings.json", "attachables/boots.json", "attachables/elytra.json"]:
            del files[name]
        if name.startswith("render_controllers/") and name not in ["render_controllers/infinity_visor.json", "render_controllers/flight.json"]:
            del files[name]
    manifest = json.loads(files["manifest.json"])
    # Bedrock caches UUID+version. Reusing [2,12,0] across exploration builds
    # kept stale armor/resources installed after a server update.
    pack_revision = int(VERSION.rsplit(".", 1)[-1])
    pack_version = [2, 12, pack_revision]
    manifest["header"].update({"name": "Infinity Armor Crossplay Resources", "version": pack_version,
        "description": "Resources for the shared Fabric/Geyser world. No behavior pack required.",
        "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, "https://infinity-armor.local/crossplay/resources"))})
    manifest.pop("dependencies", None)
    for module in manifest["modules"]:
        module["version"] = pack_version
        module["uuid"] = str(uuid.uuid5(uuid.NAMESPACE_URL, "https://infinity-armor.local/crossplay/resources/" + module["type"]))
    files["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    atlas = json.loads(files["textures/item_texture.json"])
    for texture in sorted((resources / "textures/convergence/blocks").glob("*.png")):
        name = texture.stem
        atlas["texture_data"]["convergence_" + name] = {"textures": "textures/convergence/blocks/" + name}
    files["textures/item_texture.json"] = (json.dumps(atlas, indent=2) + "\n").encode()
    terrain = json.loads(files["textures/terrain_texture.json"])
    for texture in sorted((resources / "textures/convergence/blocks").glob("*.png")):
        terrain["texture_data"]["convergence_" + texture.stem] = {"textures": "textures/convergence/blocks/" + texture.stem}
    files["textures/terrain_texture.json"] = (json.dumps(terrain, indent=2) + "\n").encode()
    language = json.loads((ROOT / "java/src/main/resources/assets/convergence/lang/en_us.json").read_text())
    files["texts/en_US.lang"] += ("\n" + "\n".join(k + "=" + v for k, v in language.items()) + "\n").encode()
    pack = server / "geyser/packs/Infinity_Armor_Crossplay.mcpack"
    pack.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(pack, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 9, 22, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    shutil.copy2(ROOT / "TEXTURE_CREDITS.md", server / "TEXTURE_CREDITS.md")
    for file in [server / "Start-Mac.command", server / "start.sh"]:
        file.chmod(0o755)
    lock_path = server / "dependencies.lock.json"
    lock = json.loads(lock_path.read_text())
    lock["version"] = VERSION
    lock["bundled"] = [{"path": str(p.relative_to(server)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
        for p in [mods / mod.name, mods / api.name, pack]]
    lock_path.write_text(json.dumps(lock, indent=2) + "\n")
    print("Prepared crossplay server artifacts and bundled file checksums.")


if __name__ == "__main__":
    prepare()
