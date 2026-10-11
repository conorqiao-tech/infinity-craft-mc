#!/usr/bin/env python3
"""Run native GameTests with the exact release server mods, in an isolated test folder."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from prepare_crossplay import VERSION, expected_native_tests
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK = "fabric-gametest-api-v1-4.0.32+3434d6d95d.jar"
FRAMEWORK_URL = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-gametest-api-v1/4.0.32+3434d6d95d/" + FRAMEWORK


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, help="Java 25 executable")
    args = parser.parse_args()
    # Vanilla's headless TestServer uses only the flat_all_dimensions preset and drops data-pack dimensions.
    # The test-only preset includes the exact production dimension JSON definitions.
    fixture = json.loads((ROOT / "java/src/gametest/resources/data/minecraft/worldgen/world_preset/flat_all_dimensions.json").read_text())["dimensions"]
    for file in (ROOT / "java/src/main/resources/data/convergence/dimension").glob("*.json"):
        assert fixture["convergence:" + file.stem] == json.loads(file.read_text()), "Dimension test fixture drift"

    subprocess.run([str(ROOT / "java/gradlew"), "-p", str(ROOT / "java"), "--no-daemon", "build", "gametestJar"],
        env={**__import__("os").environ, "JAVA_HOME": str(Path(args.java).resolve().parents[1])}, check=True)
    subprocess.run([__import__("sys").executable, str(ROOT / "tools/prepare_crossplay.py")], check=True)
    subprocess.run([__import__("sys").executable, str(ROOT / "server/server.py"), "--setup"], check=True)
    runtime = ROOT / "research/integration"
    # Native tests must not inherit a generated lobby marker from an earlier build.
    # This is the disposable GameTest world, never the playable server/fabric/world.
    shutil.rmtree(runtime / "world", ignore_errors=True)
    mods = runtime / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    for file in mods.glob("*.jar"):
        file.unlink()  # Only our disposable test folder.
    for file in (ROOT / "server/fabric/mods").glob("*.jar"):
        shutil.copy2(file, mods / file.name)
    test_mod = ROOT / f"java/build/libs/Infinity-Armor-{VERSION}-gametest.jar"
    shutil.copy2(test_mod, mods / test_mod.name)
    framework = urllib.request.urlopen(FRAMEWORK_URL, timeout=60).read()
    expected = (ROOT / "tools/gametest-framework.sha256").read_text().split()[0]
    if hashlib.sha256(framework).hexdigest() != expected:
        raise RuntimeError("Unexpected GameTest framework download")
    # 26.x is unobfuscated, so the framework's annotation defaults need no repair.
    (mods / FRAMEWORK).write_bytes(framework)
    shutil.copy2(ROOT / "server/fabric/fabric-server-launch.jar", runtime / "fabric-server-launch.jar")
    (runtime / "config/polymer").mkdir(parents=True, exist_ok=True)
    (runtime / "config/polymer/auto-host.json").write_text('{"enabled":false}')
    report = runtime / "results.xml"
    report.unlink(missing_ok=True)
    with (runtime / "integration.log").open("w") as log:
        result = subprocess.run([args.java, "-Xmx2G", "-Dfabric-api.gametest",
            "-Dfabric-api.gametest.report-file=" + str(report), "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=runtime, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    tests = list(ET.parse(report).getroot().iter("testcase")) if report.exists() else []
    failures = [t.get("name") for t in tests if t.find("failure") is not None or t.find("error") is not None]
    if result.returncode or len(tests) != expected_native_tests() or failures or any(t.find('skipped') is not None for t in tests):
        raise RuntimeError(f"Native test failure: {len(tests)} tests, {failures}; see {runtime / 'integration.log'}")
    print(f"All {len(tests)} native tests passed with release dependencies.")


if __name__ == "__main__":
    main()
