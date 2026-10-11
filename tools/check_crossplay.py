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
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK = "fabric-gametest-api-v1-3.1.27+4fc5413f3e.jar"
FRAMEWORK_URL = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-gametest-api-v1/3.1.27+4fc5413f3e/" + FRAMEWORK


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, help="Java 21 executable")
    args = parser.parse_args()
    # Vanilla's headless TestServer uses only the flat preset and drops data-pack dimensions.
    # The test-only flat preset includes the exact production dimension JSON definitions.
    fixture = json.loads((ROOT / "java/src/gametest/resources/data/minecraft/worldgen/world_preset/flat.json").read_text())["dimensions"]
    for file in (ROOT / "java/src/main/resources/data/convergence/dimension").glob("*.json"):
        assert fixture["convergence:" + file.stem] == json.loads(file.read_text()), "Dimension test fixture drift"

    subprocess.run([str(ROOT / "java/gradlew"), "-p", str(ROOT / "java"), "--no-daemon", "build", "remapGametestJar"],
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
    import io
    with zipfile.ZipFile(io.BytesIO(framework)) as archive:
        entries = {name: archive.read(name) for name in archive.namelist()
                   if not (name.startswith("META-INF/") and name.endswith((".SF", ".RSA", ".DSA")))}
    # Test-only upstream framework repair: annotation defaults use Enum.name(), not
    # intermediary field names. This does not modify Minecraft or any released mod.
    name = "net/fabricmc/fabric/api/gametest/v1/GameTest.class"
    before = b"\x00\x0bfield_11467"
    assert entries[name].count(before) == 1
    entries[name] = entries[name].replace(before, b"\x00\x04NONE")
    entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\n\r\n"
    with zipfile.ZipFile(mods / FRAMEWORK, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
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
