#!/usr/bin/env python3
"""Refresh playable files and checksums after building the Java crossplay mod."""
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import uuid
import zipfile
from generate_odyssey_assets import verify as verify_odyssey
from crossplay_resources import NATIVE_SPRITE_ATTACHABLES, resource_version, vanilla_equipment_override
from combined_wing_resources import ATTACHABLE as COMBINED_ELYTRA, RENDERERS as COMBINED_RENDERERS, validate as validate_combined_wings
from snapshot5_dependencies import (MINECRAFT, PROTOCOL, FABRIC_API, FABRIC_API_SHA256,
                                   digest as bounded_digest, snapshot_inputs, staged_forks)

ROOT = Path(__file__).resolve().parents[1]
VERSION = "2.13.0-explore.49"


def fabric_api_input():
    gradle = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    candidates = [ROOT / "vendor" / FABRIC_API, *sorted((gradle / "caches/modules-2/files-2.1" /
        "net.fabricmc.fabric-api/fabric-api/0.164.1+26.4").glob("*/" + FABRIC_API))]
    for path in candidates:
        if path.is_file() and bounded_digest(path) == FABRIC_API_SHA256:
            return path
    raise RuntimeError("Build against the pinned snapshot Fabric API before preparing the server")


def expected_native_tests():
    """Count registered test methods plus Fabric's one framework baseline."""
    manifest = json.loads((ROOT / "java/src/gametest/resources/fabric.mod.json").read_text())
    sources = ROOT / "java/src/gametest/java"
    annotated = {p.relative_to(sources).with_suffix("").as_posix().replace("/", ".")
                 for p in sources.rglob("*.java") if re.search(r"@GameTest\b", p.read_text())}
    registered = set(manifest["entrypoints"]["fabric-gametest"])
    opt_in = set(manifest.get("custom", {}).get("infinity-opt-in-gametests", []))
    assert not registered & opt_in, "Opt-in benchmarks must stay outside the normal native suite"
    assert annotated == registered | opt_in, "Register every native test class or explicitly declare its opt-in benchmark"
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
    forks = staged_forks()
    fork_sources = snapshot_inputs()
    api = fabric_api_input()
    mods = server / "fabric/mods"
    mods.mkdir(parents=True, exist_ok=True)
    # Only replace older builds of this same mod, not third-party mods.
    for old in mods.glob("Infinity-Armor-*.jar"):
        if old.name != f"Infinity-Armor-{VERSION}.jar":
            old.unlink()
    mod = ROOT / f"java/build/libs/Infinity-Armor-{VERSION}.jar"
    with zipfile.ZipFile(mod) as jar:
        assert json.loads(jar.read("fabric.mod.json"))["version"] == VERSION
        assert not any("GameTests" in name or name.startswith("net/minecraft/") for name in jar.namelist())
    # Local ports are reviewed build outputs, never fabricated upstream downloads.
    for file in [mod, api, *(ROOT / "vendor/snapshot5" / name for name in forks)]:
        shutil.copy2(file, mods / file.name)

    resources = ROOT / "bedrock/resource_pack"
    files = {p.relative_to(resources).as_posix(): p.read_bytes() for p in resources.rglob("*") if p.is_file()}
    for name in list(files):
        # Do not replace ordinary armor or elytra assets. Native Bedrock flight
        # intentionally keeps the engine's elytra appearance and animations.
        if vanilla_equipment_override(name) or name in NATIVE_SPRITE_ATTACHABLES:
            del files[name]
            continue
        # Only item-specific original armor/tools.
        # Exclude legacy duplicates and unsupported custom-glider definitions.
        if name.startswith("attachables/") and name != COMBINED_ELYTRA and not name.startswith(("attachables/refresh27/", "attachables/held/", "attachables/elemental41/")):
            del files[name]
        if name.startswith("render_controllers/") and name not in ["render_controllers/infinity_visor.json", "render_controllers/flight.json", "render_controllers/original_static_armor.json", "render_controllers/original_tools.json", "render_controllers/elemental41.json", COMBINED_RENDERERS]:
            del files[name]
    # One guarded, native-identity overlay is permitted. The old unconditional
    # elytra override and every ordinary texture/icon override remain excluded.
    validate_combined_wings(files)
    manifest = json.loads(files["manifest.json"])
    # Bedrock caches UUID+version. Reusing [2,12,0] across exploration builds
    # kept stale armor/resources installed after a server update.
    pack_version = resource_version(VERSION)
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
        atlas["texture_data"].setdefault("convergence_" + name, {"textures": "textures/convergence/blocks/" + name})
    files["textures/item_texture.json"] = (json.dumps(atlas, indent=2) + "\n").encode()
    terrain = json.loads(files["textures/terrain_texture.json"])
    for texture in sorted((resources / "textures/convergence/blocks").glob("*.png")):
        terrain["texture_data"].setdefault("convergence_" + texture.stem, {"textures": "textures/convergence/blocks/" + texture.stem})
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
    # The adapter depends on the exact Geyser internals. Refuse stale or unbuilt
    # source instead of packaging Creative entries that cannot become real gear.
    bridge = server / "geyser/extensions/InfinityCreativeBridge.jar"
    from build_creative_bridge import PIN, digest
    receipt = json.loads((ROOT / "research/integration/creative-bridge/build.json").read_text())
    expected_inputs = [ROOT / "geyser-extension/extension.yml", ROOT / "tools/build_creative_bridge.py", ROOT / "tools/snapshot5_dependencies.py",
                       *sorted((ROOT / "geyser-extension/src/main/java").rglob("*.java"))]
    assert receipt["geyser_sha256"] == PIN == digest(server / "geyser/Geyser-Standalone.jar")
    assert receipt["source_hashes"] == {p.relative_to(ROOT).as_posix(): digest(p) for p in expected_inputs}, \
        "Creative bridge source changed; rebuild it before preparing"
    assert receipt["extension_sha256"] == digest(bridge), "Creative bridge jar differs from its build receipt"
    lock["version"] = VERSION
    lock["minecraft"] = MINECRAFT
    lock["snapshot5"] = {"experimental": True, "protocol": PROTOCOL, "source_inputs": fork_sources}
    lock["creative_bridge"] = {key: receipt[key] for key in ("format_version", "geyser_sha256", "source_hashes", "extension_sha256")}
    lock["bundled"] = [{"path": str(p.relative_to(server)), "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
        for p in [mods / mod.name, mods / api.name, pack, bridge, *(mods / name for name in forks)]]
    assert snapshot_inputs() == fork_sources, "Local port provenance changed while preparing"
    lock_path.write_text(json.dumps(lock, indent=2) + "\n")
    print("Prepared crossplay server artifacts and bundled file checksums.")


if __name__ == "__main__":
    prepare()
