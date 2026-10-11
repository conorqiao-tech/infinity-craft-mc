#!/usr/bin/env python3
"""Run native GameTests with the exact release server mods, in an isolated test folder."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
from prepare_crossplay import VERSION, expected_native_tests
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK = "fabric-gametest-api-v1-4.0.32+3434d6d95d.jar"
FRAMEWORK_URL = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-gametest-api-v1/4.0.32+3434d6d95d/" + FRAMEWORK


def test_groups(manifest):
    registered = manifest["entrypoints"]["fabric-gametest"]
    isolated = manifest.get("custom", {}).get("infinity-isolated-gametests", [])
    exclusive = manifest.get("custom", {}).get("infinity-exclusive-gametests", [])
    if (not isinstance(isolated, list) or any(not isinstance(name, str) for name in isolated)
        or len(set(isolated)) != len(isolated) or not set(isolated) <= set(registered)):
        raise ValueError("Isolated tests must be unique registered native test classes")
    if (not isinstance(exclusive, list) or any(not isinstance(name, str) for name in exclusive)
        or len(set(exclusive)) != len(exclusive) or not set(exclusive) <= set(registered)
        or set(exclusive) & set(isolated)):
        raise ValueError("Exclusive tests must be unique registered classes outside the shared isolated group")
    regular = [name for name in registered if name not in isolated and name not in exclusive]
    shared = [(label, names) for label, names in (("default", regular), ("native-play", isolated)) if names]
    return shared + [("exclusive-" + str(i), [name]) for i, name in enumerate(exclusive)]


def selected_test_jar(source, destination, classes):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as result:
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename == "fabric.mod.json":
                manifest = json.loads(data)
                manifest["entrypoints"]["fabric-gametest"] = classes
                data = json.dumps(manifest, indent=2).encode()
            result.writestr(item, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, help="Java 25 executable")
    args = parser.parse_args()
    def source_hashes():
        return {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                for p in (ROOT / "java/src").rglob("*") if p.is_file()}
    checked_sources = source_hashes()
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
    release_mod = ROOT / f"server/fabric/mods/Infinity-Armor-{VERSION}.jar"
    tested_mod_sha = hashlib.sha256(release_mod.read_bytes()).hexdigest()
    assert hashlib.sha256((mods / release_mod.name).read_bytes()).hexdigest() == tested_mod_sha
    test_mod = ROOT / f"java/build/libs/Infinity-Armor-{VERSION}-gametest.jar"
    tested_test_sha = hashlib.sha256(test_mod.read_bytes()).hexdigest()
    shutil.copy2(test_mod, mods / test_mod.name)
    expected = (ROOT / "tools/gametest-framework.sha256").read_text().split()[0]
    # Reuse Gradle's cached copy when it matches the pinned checksum; download only otherwise.
    gradle_home = Path(__import__("os").environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cached = [p.read_bytes() for p in (gradle_home / "caches/modules-2/files-2.1").glob("net.fabricmc.fabric-api/fabric-gametest-api-v1/*/*/" + FRAMEWORK)]
    framework = next((data for data in cached if hashlib.sha256(data).hexdigest() == expected), None)
    if framework is None:
        framework = urllib.request.urlopen(FRAMEWORK_URL, timeout=60).read()
    if hashlib.sha256(framework).hexdigest() != expected:
        raise RuntimeError("Unexpected GameTest framework download")
    # 26.x is unobfuscated, so the framework's annotation defaults need no repair.
    (mods / FRAMEWORK).write_bytes(framework)
    shutil.copy2(ROOT / "server/fabric/fabric-server-launch.jar", runtime / "fabric-server-launch.jar")
    (runtime / "config/polymer").mkdir(parents=True, exist_ok=True)
    (runtime / "config/polymer/auto-host.json").write_text('{"enabled":false}')
    manifest = json.loads((ROOT / "java/src/gametest/resources/fabric.mod.json").read_text())
    groups = test_groups(manifest)
    expected_count = expected_native_tests()
    (runtime / "results.xml").unlink(missing_ok=True)
    (runtime / "provenance.json").unlink(missing_ok=True)
    combined = ET.Element("testsuites")
    group_records = []
    with (runtime / "integration.log").open("w") as combined_log:
        for label, classes in groups:
            target = runtime
            if label != "default":
                from benchmark_helpers import copy_cached_runtime
                target = ROOT / ("research/integration-" + label)
                target.mkdir(parents=True, exist_ok=True)
                shutil.rmtree(target / "world", ignore_errors=True)
                # Only this disposable runtime is replaced; no playable settings or saves.
                for folder in ("libraries", "versions", ".fabric/server", "mods"):
                    shutil.rmtree(target / folder, ignore_errors=True)
                copy_cached_runtime(runtime, target)
                shutil.copytree(mods, target / "mods")
                (target / "config/polymer").mkdir(parents=True, exist_ok=True)
                (target / "config/polymer/auto-host.json").write_text('{"enabled":false}')
            assert hashlib.sha256(test_mod.read_bytes()).hexdigest() == tested_test_sha, "Test jar changed between native groups"
            selected = target / "mods" / test_mod.name
            selected_test_jar(test_mod, selected, classes)
            selected_sha = hashlib.sha256(selected.read_bytes()).hexdigest()
            group_report = runtime / ("results-" + label + ".xml")
            group_report.unlink(missing_ok=True)
            group_log = runtime / ("integration-" + label + ".log")
            with group_log.open("w") as log:
                tmp = __import__("os").environ.get("GAMETEST_TMPDIR")
                paced = label.startswith("exclusive-")
                result = subprocess.run([args.java, "-Xmx2G", *(["-Djava.io.tmpdir=" + tmp] if tmp else []),
                    *(["-Dinfinity.gametest.paced=true"] if paced else []), "-Dfabric-api.gametest",
                    "-Dfabric-api.gametest.report-file=" + str(group_report), "-jar", "fabric-server-launch.jar", "nogui"],
                    cwd=target, stdout=log, stderr=subprocess.STDOUT, timeout=300)
            combined_log.write(group_log.read_text()); combined_log.flush()
            tree = ET.parse(group_report) if group_report.exists() else None
            cases = list(tree.getroot().iter("testcase")) if tree is not None else []
            expected_group = 1 + sum(len(re.findall(r"@GameTest\b", (ROOT / "java/src/gametest/java" /
                (name.replace(".", "/") + ".java")).read_text())) for name in classes)
            failures = [t.get("name") for t in cases if any(t.find(kind) is not None for kind in ("failure", "error", "skipped"))]
            if result.returncode or len(cases) != expected_group or failures:
                raise RuntimeError(f"Native group {label} failed: {len(cases)}/{expected_group} cases, {failures}; see {group_log}")
            assert hashlib.sha256(selected.read_bytes()).hexdigest() == selected_sha, "Selected native test jar changed during its group"
            group_records.append(dict(name=label, registered_classes=classes, native_tests=len(cases), pacing_ms=50 if paced else 0,
                report_sha256=hashlib.sha256(group_report.read_bytes()).hexdigest(), selected_test_sha256=selected_sha))
            # Each independent world runs Vanilla's same baseline. Verify it every
            # time, then include it once in the aggregate instead of inflating coverage.
            if len(group_records) > 1:
                baseline = [t for t in cases if t.get("name") == "minecraft:always_pass"]
                assert len(baseline) == 1
                for parent in tree.getroot().iter():
                    for child in list(parent):
                        if child is baseline[0]: parent.remove(child)
            combined.append(tree.getroot())
    report = runtime / "results.xml"
    ET.ElementTree(combined).write(report, encoding="utf-8", xml_declaration=True)
    tests = list(combined.iter("testcase"))
    failures = [t.get("name") for t in tests if t.find("failure") is not None or t.find("error") is not None]
    if len(tests) != expected_count or len({t.get("name") for t in tests}) != len(tests) or failures or any(t.find('skipped') is not None for t in tests):
        raise RuntimeError(f"Native test failure: {len(tests)} tests, {failures}; see {runtime / 'integration.log'}")
    if (source_hashes() != checked_sources or hashlib.sha256(release_mod.read_bytes()).hexdigest() != tested_mod_sha
        or hashlib.sha256(test_mod.read_bytes()).hexdigest() != tested_test_sha):
        raise RuntimeError("Source or mod changed during native checks; rerun validation")
    (runtime / "provenance.json").write_text(json.dumps({"format": 1, "java_sources": checked_sources,
        "mod_sha256": tested_mod_sha, "report_sha256": hashlib.sha256(report.read_bytes()).hexdigest(),
        "native_tests": len(tests), "test_groups": group_records,
        "test_mod_sha256": tested_test_sha}, indent=2) + "\n")
    print(f"All {len(tests)} native tests passed with release dependencies.")


if __name__ == "__main__":
    main()
