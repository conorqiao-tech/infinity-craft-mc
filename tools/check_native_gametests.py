#!/usr/bin/env python3
"""Run every regular Fabric GameTest in its declared disposable group."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from benchmark_helpers import copy_cached_runtime, runtime_inputs
from check_crossplay import selected_test_jar, test_groups
from prepare_crossplay import VERSION, expected_native_tests, fabric_api_input
from snapshot5_dependencies import FRAMEWORK, FRAMEWORK_SHA256, staged_forks

ROOT = Path(__file__).resolve().parents[1]
VANILLA = "minecraft:always_pass"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def group_cases(report, expected):
    cases = list(ET.parse(report).getroot().iter("testcase"))
    names = [case.get("name") for case in cases]
    failed = [case.get("name") for case in cases
              if any(case.find(kind) is not None for kind in ("failure", "error", "skipped"))]
    if (len(cases) != expected or names.count(VANILLA) != 1 or None in names
            or len(set(names)) != len(names) or failed):
        raise ValueError(f"Expected {expected} unique passing cases with a vanilla control; "
                         f"found {len(cases)}, failing or skipped: {failed}")
    return [name for name in names if name != VANILLA]


def validate_total(groups, expected):
    names = [name for group in groups for name in group]
    if len(names) + 1 != expected or len(set(names)) != len(names):
        raise ValueError(f"Expected {expected} unique cases including one vanilla control")
    return len(names) + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path, help="Path to a Java 25 executable")
    parser.add_argument("--cached-runtime", required=True, type=Path,
                        help="Existing prepared research runtime; only launcher and dependency caches are read")
    parser.add_argument("--jobs", type=int, default=1, help="Concurrent test worlds (1–3)")
    args = parser.parse_args()
    if not 1 <= args.jobs <= 3:
        parser.error("--jobs must be between 1 and 3")
    java = args.java.resolve(strict=True)
    cached = args.cached_runtime.resolve(strict=True)
    # Validate pinned launcher, loader, classpath and framework before copying any files.
    cache_before = runtime_inputs(cached)
    manifest_path = ROOT / "java/src/gametest/resources/fabric.mod.json"
    groups = test_groups(json.loads(manifest_path.read_text()))
    expected = expected_native_tests()
    source = ROOT / "java/src"
    before = {path: digest(path) for path in source.rglob("*") if path.is_file()}
    output = Path(tempfile.mkdtemp(prefix="infinity-native-"))
    print(f"Native test reports: {output}", flush=True)

    subprocess.run([str(ROOT / "java/gradlew"), "-p", str(ROOT / "java"), "--offline",
                    "--no-daemon", "jar", "gametestJar"],
                   env={**os.environ, "JAVA_HOME": str(java.parent.parent)}, check=True)
    jars = ROOT / "java/build/libs"
    test_jar = jars / f"Infinity-Armor-{VERSION}-gametest.jar"
    mods = [jars / f"Infinity-Armor-{VERSION}.jar", fabric_api_input(),
            *(ROOT / "vendor/snapshot5" / name for name in staged_forks()),
            cached / "mods" / FRAMEWORK]
    if digest(mods[-1]) != FRAMEWORK_SHA256:
        raise RuntimeError("Pinned GameTest framework changed")
    hashes = {path: digest(path) for path in [*mods, test_jar]}

    def run_group(group):
        label, classes = group
        target = output / label
        target.mkdir()
        copy_cached_runtime(cached, target)
        (target / "mods").mkdir()
        (target / "config/polymer").mkdir(parents=True)
        (target / "config/polymer/auto-host.json").write_text('{"enabled":false}')
        (target / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=0\n")
        for mod in mods:
            shutil.copy2(mod, target / "mods" / mod.name)
            if digest(target / "mods" / mod.name) != hashes[mod]:
                raise RuntimeError(f"Mod input changed while preparing {label}: {mod.name}")
        selected_test_jar(test_jar, target / "mods" / test_jar.name, classes)
        report, log = output / (label + ".xml"), output / (label + ".log")
        count = 1 + sum(len(re.findall(r"@GameTest\b", (source / "gametest/java" /
                        (name.replace(".", "/") + ".java")).read_text())) for name in classes)
        try:
            with log.open("w") as sink:
                run = subprocess.run([str(java), "-Xmx2G",
                    "-XX:ActiveProcessorCount=" + ("4" if label == "default" else "2"),
                    *(["-Dinfinity.gametest.paced=true"] if label.startswith("exclusive-") else []),
                    "-Dfabric-api.gametest", f"-Dfabric-api.gametest.report-file={report}",
                    "-jar", "fabric-server-launch.jar", "nogui"], cwd=target,
                    stdout=sink, stderr=subprocess.STDOUT, timeout=300)
            names = group_cases(report, count)
            if run.returncode:
                raise ValueError(f"GameTest process exited {run.returncode}")
        except (OSError, ValueError, ET.ParseError, subprocess.TimeoutExpired) as exc:
            raise RuntimeError(f"Native group {label} failed; see {log}: {exc}") from exc
        shutil.rmtree(target)  # This run's passing disposable world only.
        print(f"{label}: {count} passed", flush=True)
        return names

    # The large default group checks bounded asynchronous chunk preparation.
    # Do not compete with another test JVM while it is exercising those bounds.
    results, failures = [], []
    parallel = []
    for group in groups:
        if group[0] == "default":
            results.append(run_group(group))
        else:
            parallel.append(group)
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for task in [pool.submit(run_group, group) for group in parallel]:
            try:
                results.append(task.result())
            except Exception as exc:
                failures.append(str(exc))
    if failures:
        raise SystemExit("\n".join(failures))
    total = validate_total(results, expected)
    if (before != {path: digest(path) for path in source.rglob("*") if path.is_file()}
            or hashes != {path: digest(path) for path in hashes}
            or runtime_inputs(cached) != cache_before):
        raise RuntimeError("Source, runtime cache or test inputs changed; rerun native checks")
    print(f"All {total} native GameTests passed across {len(groups)} disposable worlds.")


if __name__ == "__main__":
    main()
