#!/usr/bin/env python3
"""Package only validated crossplay artifacts; exclude all runtime state and credentials."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
from prepare_crossplay import ROOT, VERSION, expected_native_tests, expected_launcher_tests


def archive(path, entries, prefix):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output:
        for name, data in sorted(entries.items()):
            assert not name.endswith(("key.pem", "eula.txt"))
            info = zipfile.ZipInfo(prefix + "/" + name, (2026, 9, 24, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (0o755 if name.endswith((".sh", ".command", "/gradlew")) else 0o644) << 16
            output.writestr(info, data)
    with zipfile.ZipFile(path) as output:
        assert output.testzip() is None


def main():
    report = ROOT / "research/integration/results.xml"
    tests = list(ET.parse(report).getroot().iter("testcase"))
    assert len(tests) == expected_native_tests() and all(t.find("failure") is None and t.find("error") is None and t.find("skipped") is None for t in tests)
    assert report.stat().st_mtime >= max(p.stat().st_mtime for p in (ROOT / "java/src").rglob("*") if p.is_file())
    testlog_file = ROOT / "research/launcher-tests.log"
    testlog = testlog_file.read_text()
    assert f"Ran {expected_launcher_tests()} tests" in testlog and "\nOK" in testlog
    assert testlog_file.stat().st_mtime >= max(p.stat().st_mtime for p in [
        *(ROOT / "server").glob("*.py"), *(ROOT / "tests").glob("test_*.py")])
    mod = ROOT / f"server/fabric/mods/Infinity-Armor-{VERSION}.jar"
    assert mod.stat().st_mtime >= max(p.stat().st_mtime for p in (ROOT / "java/src/main").rglob("*") if p.is_file())
    assert report.stat().st_mtime >= mod.stat().st_mtime
    geyser_log_file = ROOT / "research/geyser-smoke.log"
    smokelog = geyser_log_file.read_text()
    assert "Geyser mappings, localhost listener, and launcher shutdown passed." in smokelog and "ERROR" not in smokelog
    assert geyser_log_file.stat().st_mtime >= mod.stat().st_mtime
    textures = json.loads((ROOT / "TEXTURE_SHA256.json").read_text())
    for name, expected in textures.items():
        if (ROOT / name).exists():
            assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == expected
    server = ROOT / "server"
    lock = json.loads((server / "dependencies.lock.json").read_text())
    assert lock["version"] == VERSION
    entries = {name: (server / name).read_bytes() for name in ["README.md", "VALIDATION.md", "THIRD_PARTY.md",
        "TEXTURE_CREDITS.md", "server.py", "dependencies.lock.json", "Start-Mac.command", "start.sh", "start.bat",
        "runtime.py", "runtime.lock.json", "dashboard.py", "dashboard.html", "bootstrap.sh", "bootstrap.ps1", "START_HERE.txt",
        "community.py", "MODES.md", "LOBBIES.md", "MEMBERSHIPS.md", "REWARDS.md", "TRADING.md", "AGENTS_GUIDE.md", "EXPANSION.md", "EXPLORATION.md", "PLAYER_TRADING.md", "HOSTING.md", "infinity.service.example",
        "Start-Pinggy-Mac.command", "Stop-Pinggy-Mac.command", "PINGGY_JOINING.md", "pinggy_install.py", "pinggy_joining.py", "remote_joining.py", "remote_tunnels.py"]}
    for pin in sorted((server / "setup").glob("*.txt")):
        entries["setup/" + pin.name] = pin.read_bytes()
    for file in lock["bundled"]:
        data = (server / file["path"]).read_bytes()
        assert hashlib.sha256(data).hexdigest() == file["sha256"]
        entries[file["path"]] = data
    entries["checks/java-gametest-results.xml"] = report.read_bytes()
    entries["checks/launcher-tests.txt"] = testlog.encode()
    fullstack_log_file = ROOT / "research/fullstack-smoke.log"
    fullstack = fullstack_log_file.read_text()
    assert "Full-stack lifecycle passed:" in fullstack and "ERROR" not in fullstack
    assert f"convergence {VERSION}" in fullstack
    assert fullstack_log_file.stat().st_mtime >= mod.stat().st_mtime
    entries["checks/fullstack-smoke.txt"] = ("\n".join(line for line in fullstack.splitlines() if line.startswith("Full-stack")) + "\n").encode()
    entries["checks/geyser-smoke.txt"] = re.sub(r"\x1b\[[0-9;]*m", "", smokelog).encode()
    dist = ROOT / "dist/lobbies"
    dist.mkdir(exist_ok=True, parents=True)
    server_archive = dist / f"Infinity_Armor_Lobbies_Server_v{VERSION}.zip"
    source_archive = dist / f"Infinity_Armor_Lobbies_Source_v{VERSION}.zip"
    archive(server_archive, entries, "Infinity_Armor_Lobbies_Server")
    source = {"server/" + k: v for k, v in entries.items()}
    for directory in ["java/src", "java/gradle", "bedrock/resource_pack", "vendor"]:
        for file in (ROOT / directory).rglob("*"):
            if file.is_file():
                source[file.relative_to(ROOT).as_posix()] = file.read_bytes()
    for name in ["java/build.gradle", "java/settings.gradle", "java/gradle.properties", "java/gradlew", "java/gradlew.bat",
        "TEXTURE_CREDITS.md", "TEXTURE_SHA256.json", "tools/check_crossplay.py", "tools/prepare_crossplay.py",
        "tools/smoke_geyser.py", "tools/generate_creative_assets.py", "tools/package_crossplay.py", "tools/gametest-framework.sha256", "tools/smoke_fullstack.py",
        *(p.relative_to(ROOT).as_posix() for p in sorted((ROOT / "tests").glob("test_*.py")))]:
        source[name] = (ROOT / name).read_bytes()
    source["README.md"] = (ROOT / "CROSSPLAY_SOURCE.md").read_bytes()
    source["ORIGINAL_GUIDE_2.3.0.md"] = (ROOT / "README.md").read_bytes()
    archive(source_archive, source, "Infinity_Armor_Lobbies_Source")
    shutil.copy2(server / "README.md", dist / "Infinity_Armor_Lobbies_Guide.md")
    shutil.copy2(server / "HOSTING.md", dist / "Infinity_Armor_Public_Hosting.md")
    shutil.copy2(server / "MODES.md", dist / "Infinity_Armor_Game_Modes.md")
    shutil.copy2(server / "LOBBIES.md", dist / "Infinity_Armor_Lobbies.md")
    shutil.copy2(server / "MEMBERSHIPS.md", dist / "Infinity_Armor_Memberships.md")
    shutil.copy2(server / "REWARDS.md", dist / "Infinity_Armor_Rewards.md")
    shutil.copy2(server / "TRADING.md", dist / "Infinity_Armor_Trading.md")
    shutil.copy2(server / "AGENTS_GUIDE.md", dist / "Infinity_Armor_Agents.md")
    shutil.copy2(server / "EXPANSION.md", dist / "Infinity_Armor_Expansion.md")
    shutil.copy2(server / "EXPLORATION.md", dist / "Infinity_Armor_Exploration.md")
    shutil.copy2(server / "PINGGY_JOINING.md", dist / "Infinity_Armor_Pinggy_Joining.md")
    shutil.copy2(server / "PLAYER_TRADING.md", dist / "Infinity_Armor_Player_Trading.md")
    (dist / "Infinity_Armor_Tebex_Setup.md").unlink(missing_ok=True)
    files = sorted([server_archive, source_archive, *(dist / name for name in [
        "Infinity_Armor_Lobbies_Guide.md", "Infinity_Armor_Public_Hosting.md",
        "Infinity_Armor_Game_Modes.md", "Infinity_Armor_Lobbies.md", "Infinity_Armor_Memberships.md", "Infinity_Armor_Rewards.md", "Infinity_Armor_Trading.md", "Infinity_Armor_Agents.md", "Infinity_Armor_Expansion.md", "Infinity_Armor_Exploration.md", "Infinity_Armor_Pinggy_Joining.md", "Infinity_Armor_Player_Trading.md"])])
    (dist / "SHA256SUMS.txt").write_text("\n".join(hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name for p in files) + "\n")
    for file in files:
        print(file.name, file.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
