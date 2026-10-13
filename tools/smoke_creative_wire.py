#!/usr/bin/env python3
"""Prove captured Geyser Creative bytes through pinned Via and the native Fabric handler.

This supplementary check never rebuilds or changes java/src. It compiles one
outside-source GameTest, adds it to a private copy of the exact already-tested
test jar, and runs that copy in a fresh disposable world. No downloads or live
server controls are used. Missing captures, stale native provenance and failed
translation are failures, not substitutes for client/device testing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid
import xml.etree.ElementTree as ET
import zipfile

from benchmark_helpers import copy_cached_runtime, runtime_inputs
from prepare_crossplay import VERSION, expected_native_tests

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/native_checks/CreativeBridgeWireGameTests.java"
CLASS = "dev.convergence.CreativeBridgeWireGameTests"
NESTED = "META-INF/jars/viaversion-5.12.1-SNAPSHOT.jar"
VIAFABRIC = "ViaFabric-0.4.22+186-26.x.jar"
VIABACKWARDS = "ViaBackwards-5.12.0.jar"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_sources() -> dict[str, str]:
    return {p.relative_to(ROOT).as_posix(): digest(p)
            for p in (ROOT / "java/src").rglob("*") if p.is_file()}


def bounded_json(path: Path, limit: int = 8 * 1024 * 1024) -> dict:
    if not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise ValueError("Missing or oversized required proof: " + str(path))
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate proof key: " + key)
            result[key] = value
        return result
    value = json.loads(path.read_text(), object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise ValueError("Expected proof object: " + str(path))
    return value


def augment_test_jar(original: Path, destination: Path, classes: Path) -> None:
    additions = {p.relative_to(classes).as_posix(): p.read_bytes()
                 for p in classes.rglob("*.class")}
    if CLASS.replace(".", "/") + ".class" not in additions:
        raise RuntimeError("The supplementary wire test was not compiled")
    with zipfile.ZipFile(original) as source, zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        for entry in source.infolist():
            if entry.filename in additions:
                raise RuntimeError("Supplementary class would overwrite an original test class")
            data = source.read(entry.filename)
            if entry.filename == "fabric.mod.json":
                manifest = json.loads(data)
                manifest["entrypoints"]["fabric-gametest"] = [CLASS]
                data = json.dumps(manifest, indent=2).encode()
            output.writestr(entry, data)
        for name, data in sorted(additions.items()):
            output.writestr(name, data)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True, type=Path, help="Documented Java 25 executable")
    parser.add_argument("--javac", type=Path, help="Matching Java 25 compiler; defaults beside --java")
    parser.add_argument("--native-runtime", type=Path, default=ROOT / "research/integration")
    parser.add_argument("--capture", type=Path, default=ROOT / "research/integration/creative-bridge/ready.json")
    parser.add_argument("--catalogue", type=Path, default=ROOT / "research/integration/crossplay-export/infinity-catalogue.json")
    parser.add_argument("--receipt", type=Path, default=ROOT / "research/integration/creative-bridge/wire-proof.json")
    parser.add_argument("--compile-classpath", help="Existing exact gametest compileClasspath; otherwise read it using an offline Gradle init task")
    args = parser.parse_args()
    java = args.java.resolve(); javac = (args.javac or java.with_name("javac")).resolve()
    if not java.is_file() or not javac.is_file():
        raise ValueError("Select the documented Java runtime and matching compiler")
    runtime = args.native_runtime.resolve(); capture = args.capture.resolve(); catalogue = args.catalogue.resolve(); receipt_path = args.receipt.resolve()
    if ROOT / "research" not in runtime.parents:
        raise ValueError("Only an existing disposable research runtime can be used")
    if ROOT / "research" not in receipt_path.parents:
        raise ValueError("The supplemental receipt must stay in the research directory")
    source_hashes = {p.relative_to(ROOT).as_posix(): digest(p) for p in [Path(__file__).resolve(), SOURCE]}
    frozen_sources = java_sources()
    provenance = bounded_json(runtime / "provenance.json")
    report = runtime / "results.xml"
    if provenance["java_sources"] != frozen_sources or provenance["report_sha256"] != digest(report):
        raise ValueError("Native source/report provenance is stale; finish the normal native check first")
    native_cases = list(ET.parse(report).getroot().iter("testcase"))
    if (len(native_cases) != expected_native_tests() or any(case.find(kind) is not None
            for case in native_cases for kind in ["failure", "error", "skipped"])):
        raise ValueError("Normal native suite must be current and successful before the supplemental proof")
    mod = ROOT / f"server/fabric/mods/Infinity-Armor-{VERSION}.jar"
    original_tests = ROOT / f"java/build/libs/Infinity-Armor-{VERSION}-gametest.jar"
    if digest(mod) != provenance["mod_sha256"] or digest(original_tests) != provenance["test_mod_sha256"]:
        raise ValueError("Release or original test jar differs from the successfully tested bytes")
    if digest(runtime / "mods" / mod.name) != provenance["mod_sha256"]:
        raise ValueError("Cached runtime release mod differs from the successfully tested bytes")
    captured = bounded_json(capture)
    startup = bounded_json(capture.with_name("startup.json"))
    if startup["ready_sha256"] != digest(capture):
        raise ValueError("Geyser startup capture checksum is stale")
    catalogue_hash = digest(catalogue)
    bounded_json(catalogue, 131_072)
    if catalogue_hash != captured["catalogue_sha256"] or catalogue_hash != startup["catalogue_sha256"]:
        raise ValueError("Captured catalogue bytes differ from the actual Geyser ready/startup proofs")
    lock = bounded_json(ROOT / "server/dependencies.lock.json")
    pinned = {entry["path"]: entry["sha256"] for entry in lock["downloads"] + lock["bundled"]}
    for name in [VIAFABRIC, VIABACKWARDS]:
        for path in [ROOT / "server/fabric/mods" / name, runtime / "mods" / name]:
            if digest(path) != pinned["fabric/mods/" + name]:
                raise ValueError("Pinned Via dependency differs: " + str(path))
    geyser = ROOT / "server/geyser/Geyser-Standalone.jar"
    extension = ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar"
    if (captured["geyser_sha256"] != digest(geyser) or digest(geyser) != pinned["geyser/Geyser-Standalone.jar"]
            or startup["extension_sha256"] != digest(extension) or digest(extension) != pinned["geyser/extensions/InfinityCreativeBridge.jar"]):
        raise ValueError("Capture does not match the pinned Geyser and compiled extension")
    runtime_hashes = runtime_inputs(runtime)
    receipt_path.unlink(missing_ok=True)
    target = ROOT / "research/creative-wire" / uuid.uuid4().hex
    target.mkdir(parents=True)
    copy_cached_runtime(runtime, target)
    shutil.copytree(runtime / "mods", target / "mods")
    selected_mod = target / "mods" / mod.name
    if digest(selected_mod) != provenance["mod_sha256"]:
        raise RuntimeError("Copied runtime release mod differs from the successfully tested bytes")
    (target / "config/polymer").mkdir(parents=True)
    (target / "config/polymer/auto-host.json").write_text('{"enabled":false}')
    compiled = target / "compiled"; compiled.mkdir()
    captured_catalogue = target / "captured-catalogue.json"
    shutil.copy2(catalogue, captured_catalogue)
    if digest(captured_catalogue) != catalogue_hash:
        raise RuntimeError("Captured catalogue changed while copying the exact native fixture")
    with zipfile.ZipFile(target / "mods" / VIAFABRIC) as archive:
        via_version = target / "viaversion-pinned.jar"
        via_version.write_bytes(archive.read(NESTED))
    classpath = args.compile_classpath
    if not classpath:
        init = target / "compile-classpath.gradle"
        init.write_text("allprojects { afterEvaluate { p -> tasks.register('infinityWireClasspath') { doLast { println('INFINITY_WIRE_CP=' + p.sourceSets.gametest.compileClasspath.asPath) } } } }\n")
        result = subprocess.run([str(ROOT / "java/gradlew"), "-p", str(ROOT / "java"), "--no-daemon", "--offline", "-q", "-I", str(init), "infinityWireClasspath"],
            env={**os.environ, "JAVA_HOME": str(java.parents[1])}, text=True, capture_output=True, check=True, timeout=120)
        values = [line[len("INFINITY_WIRE_CP="):] for line in result.stdout.splitlines() if line.startswith("INFINITY_WIRE_CP=")]
        if len(values) != 1:
            raise RuntimeError("Cannot read the exact existing gametest compile classpath")
        classpath = values[0]
    classpath = os.pathsep.join([classpath, str(original_tests), str(via_version), str(target / "mods" / VIABACKWARDS)])
    subprocess.run([str(javac), "--release", "25", "-encoding", "UTF-8", "-classpath", classpath, "-d", str(compiled), str(SOURCE)], check=True, timeout=120)
    selected = target / "mods" / original_tests.name
    augment_test_jar(original_tests, selected, compiled)
    selected_hash = digest(selected)
    native_report = target / "results.xml"; native_proof = target / "native-wire.json"; log_path = target / "wire.log"
    with log_path.open("w") as log:
        result = subprocess.run([str(java), "-Xmx2G", "-Dinfinity.gametest.paced=true", "-Dfabric-api.gametest",
            "-Dinfinity.creative.capture=" + str(capture), "-Dinfinity.creative.wire.report=" + str(native_proof),
            "-Dinfinity.creative.catalogue=" + str(captured_catalogue),
            "-Dfabric-api.gametest.report-file=" + str(native_report), "-jar", "fabric-server-launch.jar", "nogui"],
            cwd=target, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    cases = list(ET.parse(native_report).getroot().iter("testcase")) if native_report.is_file() else []
    if (result.returncode or len(cases) != 2 or any(case.find(kind) is not None
            for case in cases for kind in ["failure", "error", "skipped"])):
        raise RuntimeError(f"Supplemental Via/Fabric wire proof failed ({len(cases)}/2 cases); see {log_path}")
    native = bounded_json(native_proof)
    proof = captured["proof"]
    if (native["capture_sha256"] != digest(capture) or native["catalogue_sha256"] != captured["catalogue_sha256"]
            or native["catalogue_equivalent"] is not True
            or native["native_catalogue_sha256"] != digest(target / "crossplay-export/infinity-catalogue.json")
            or native["hotbar_cases"] != len(proof["encoded_hotbar_packets"])
            or native["overflow_cases"] != len(proof["encoded_oversized_packets"])
            or native["survival_cases"] != len(proof["encoded_hotbar_packets"])
            or native["via_survival_cases"] != len(proof["encoded_hotbar_packets"])
            or native["viabackwards_sha256"] != pinned["fabric/mods/" + VIABACKWARDS]
            or native["viafabric_sha256"] != pinned["fabric/mods/" + VIAFABRIC]
            or native["viaversion_nested_sha256"] != digest(via_version)):
        raise RuntimeError("Actual native proof does not match its required captures or pinned dependencies")
    if (java_sources() != frozen_sources or {p.relative_to(ROOT).as_posix(): digest(p) for p in [Path(__file__).resolve(), SOURCE]} != source_hashes
            or digest(mod) != provenance["mod_sha256"] or digest(original_tests) != provenance["test_mod_sha256"]
            or digest(selected_mod) != provenance["mod_sha256"]
            or digest(selected) != selected_hash or runtime_inputs(runtime) != runtime_hashes
            or digest(capture) != startup["ready_sha256"] or digest(catalogue) != catalogue_hash
            or digest(captured_catalogue) != catalogue_hash):
        raise RuntimeError("Inputs changed during supplemental proof; rerun the frozen source snapshot")
    output = {"format_version": 1, "source_hashes": source_hashes, "java_sources": frozen_sources,
        "mod_sha256": digest(mod), "original_test_mod_sha256": digest(original_tests), "wire_test_mod_sha256": selected_hash,
        "capture_sha256": digest(capture), "geyser_startup_sha256": digest(capture.with_name("startup.json")),
        "captured_catalogue_sha256": catalogue_hash, "native_catalogue_sha256": native["native_catalogue_sha256"],
        "extension_sha256": digest(extension), "native_tests": len(cases), "report_sha256": digest(native_report),
        "native_proof_sha256": digest(native_proof), "log_sha256": digest(log_path), "runtime": target.relative_to(ROOT).as_posix(), "proof": native}
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    receipt_path.write_text(json.dumps(output, indent=2) + "\n")
    print(f"Supplemental wire proof passed: {native['hotbar_cases']} actual hotbar packets, {native['overflow_cases']} overflow refusals, {native['survival_cases']} Survival refusals; {receipt_path}")


if __name__ == "__main__":
    main()
