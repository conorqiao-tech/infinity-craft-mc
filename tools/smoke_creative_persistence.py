#!/usr/bin/env python3
"""Prove real framed Creative ingress, native disk saves and saved same-UUID rejoin.

Requires the current successful ordinary native suite and actual Geyser/Via wire
proof. Compiles only an opt-in supplementary class outside java/src, augments an
isolated copy of the exact tested jar, and runs a new disposable world. It never
downloads, changes production sources, replaces prior receipts, or touches the
playable server. A missing real packet hook, save or login is a failed proof.
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
from package_crossplay import verified_creative_wire
from prepare_crossplay import VERSION, expected_native_tests
from smoke_creative_wire import bounded_json, digest, java_sources, NESTED, VIAFABRIC, VIABACKWARDS

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/native_checks/CreativeBridgePersistenceGameTests.java"
CLASS = "dev.convergence.CreativeBridgePersistenceGameTests"


def supplementary_sources() -> dict[str, str]:
    """Bind imported proof/copy helpers as well as the two new source files."""
    paths = [Path(__file__).resolve(), SOURCE,
             ROOT / "tools/benchmark_helpers.py", ROOT / "tools/prepare_crossplay.py",
             ROOT / "tools/package_crossplay.py", ROOT / "tools/smoke_creative_wire.py",
             ROOT / "tools/verify_owned_equipment.py", ROOT / "tools/crossplay_resources.py"]
    return {path.relative_to(ROOT).as_posix(): digest(path) for path in paths}


def passed_cases(report: Path, expected: int) -> list:
    cases = list(ET.parse(report).getroot().iter("testcase")) if report.is_file() else []
    if len(cases) != expected or any(case.find(kind) is not None
            for case in cases for kind in ("failure", "error", "skipped")):
        raise ValueError(f"Required native report did not pass all {expected} cases: {report}")
    return cases


def augment_test_jar(original: Path, destination: Path, classes: Path) -> None:
    additions = {path.relative_to(classes).as_posix(): path.read_bytes()
                 for path in classes.rglob("*.class")}
    if CLASS.replace(".", "/") + ".class" not in additions:
        raise RuntimeError("The supplementary persistence class was not compiled")
    if original.resolve() == destination.resolve():
        raise ValueError("Never modify the original native-tested jar")
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
    parser.add_argument("--receipt", type=Path,
                        help="New research receipt; defaults to a unique versioned path, never overwrites an existing proof")
    parser.add_argument("--compile-classpath", help="Exact existing gametest compileClasspath; otherwise read offline Gradle metadata")
    args = parser.parse_args()
    java = args.java.resolve(); javac = (args.javac or java.with_name("javac")).resolve()
    if not java.is_file() or not javac.is_file():
        raise ValueError("Select the documented Java runtime and matching compiler")
    runtime = args.native_runtime.resolve(); capture = args.capture.resolve(); catalogue = args.catalogue.resolve()
    run_id = uuid.uuid4().hex
    receipt_path = (args.receipt or ROOT / f"research/integration/creative-bridge/persistence-{VERSION}-{run_id}.json").resolve()
    research = ROOT / "research"
    if research not in runtime.parents or research not in receipt_path.parents:
        raise ValueError("Only disposable research runtimes and research receipt paths are supported")
    if receipt_path.exists():
        raise ValueError("Preserve the existing proof; choose a new --receipt path")
    if receipt_path in {capture, catalogue, capture.with_name("startup.json"), runtime / "provenance.json", runtime / "results.xml"}:
        raise ValueError("The new receipt must not replace any existing input evidence")
    frozen_sources = java_sources(); source_hashes = supplementary_sources()
    provenance_file = runtime / "provenance.json"; provenance = bounded_json(provenance_file)
    native_report = runtime / "results.xml"
    if provenance["java_sources"] != frozen_sources or provenance["report_sha256"] != digest(native_report):
        raise ValueError("Native source/report provenance is stale; finish the current ordinary suite first")
    passed_cases(native_report, expected_native_tests())
    mod = ROOT / f"server/fabric/mods/Infinity-Armor-{VERSION}.jar"
    original_tests = ROOT / f"java/build/libs/Infinity-Armor-{VERSION}-gametest.jar"
    if (digest(mod) != provenance["mod_sha256"] or digest(original_tests) != provenance["test_mod_sha256"]
            or digest(runtime / "mods" / mod.name) != provenance["mod_sha256"]):
        raise ValueError("Current release, original native jar and cached executed release must match successful provenance")
    lock_file = ROOT / "server/dependencies.lock.json"; lock = bounded_json(lock_file)
    # Reuse the fully verified current wire proof rather than trusting its success label.
    wire_evidence = verified_creative_wire(ROOT, lock, provenance)
    wire_hashes = {name: hashlib.sha256(data).hexdigest() for name, data in wire_evidence.items()}
    wire_receipt_file = ROOT / "research/integration/creative-bridge/wire-proof.json"
    wire_receipt_hash = digest(wire_receipt_file)
    captured = bounded_json(capture); startup_file = capture.with_name("startup.json"); startup = bounded_json(startup_file)
    capture_hash = digest(capture); startup_hash = digest(startup_file); catalogue_hash = digest(catalogue)
    bounded_json(catalogue, 131_072)
    if (startup["ready_sha256"] != capture_hash or catalogue_hash != captured["catalogue_sha256"]
            or catalogue_hash != startup["catalogue_sha256"]
            or capture_hash != bounded_json(wire_receipt_file)["capture_sha256"]):
        raise ValueError("Actual ready/startup/catalogue bytes must match the current verified wire proof")
    hotbar = captured["proof"]["encoded_hotbar_packets"]
    distinct = {(sample["path"], sample["count"]) for sample in hotbar}
    identities = {sample["path"] for sample in hotbar}
    if len(hotbar) != 180 or len(distinct) != 60 or len(identities) != 46:
        raise ValueError("Require the complete actual capture: 180 inputs, 60 item/count pairs and all 46 identities")
    pinned = {entry["path"]: entry["sha256"] for entry in lock["downloads"] + lock["bundled"]}
    for name in (VIAFABRIC, VIABACKWARDS):
        for path in (ROOT / "server/fabric/mods" / name, runtime / "mods" / name):
            if digest(path) != pinned["fabric/mods/" + name]:
                raise ValueError("Pinned Via dependency differs: " + str(path))
    geyser = ROOT / "server/geyser/Geyser-Standalone.jar"
    extension = ROOT / "server/geyser/extensions/InfinityCreativeBridge.jar"
    if (captured["geyser_sha256"] != digest(geyser) or digest(geyser) != pinned["geyser/Geyser-Standalone.jar"]
            or startup["extension_sha256"] != digest(extension)
            or digest(extension) != pinned["geyser/extensions/InfinityCreativeBridge.jar"]):
        raise ValueError("Capture does not match the current pinned Geyser/extension bytes")
    runtime_hashes = runtime_inputs(runtime)
    input_paths = [provenance_file, native_report, mod, original_tests, lock_file, capture, startup_file, catalogue,
                   wire_receipt_file, geyser, extension,
                   ROOT / "server/fabric/mods" / VIAFABRIC, ROOT / "server/fabric/mods" / VIABACKWARDS]
    frozen_inputs = {str(path): digest(path) for path in input_paths}
    target = research / "creative-persistence" / run_id; target.mkdir(parents=True, exist_ok=False)
    copy_cached_runtime(runtime, target); shutil.copytree(runtime / "mods", target / "mods")
    selected_mod = target / "mods" / mod.name
    if digest(selected_mod) != provenance["mod_sha256"]:
        raise RuntimeError("Copied executed production mod differs from native-tested bytes")
    (target / "config/polymer").mkdir(parents=True)
    (target / "config/polymer/auto-host.json").write_text('{"enabled":false}')
    captured_catalogue = target / "captured-catalogue.json"; captured_packets = target / "captured-ready.json"
    shutil.copy2(catalogue, captured_catalogue); shutil.copy2(capture, captured_packets)
    if digest(captured_catalogue) != catalogue_hash or digest(captured_packets) != capture_hash:
        raise RuntimeError("Actual capture bytes changed while creating the disposable fixture")
    with zipfile.ZipFile(target / "mods" / VIAFABRIC) as archive:
        via_version = target / "viaversion-pinned.jar"; via_version.write_bytes(archive.read(NESTED))
    compiled = target / "compiled"; compiled.mkdir()
    classpath = args.compile_classpath
    if not classpath:
        init = target / "compile-classpath.gradle"
        init.write_text("allprojects { afterEvaluate { p -> tasks.register('infinityPersistenceClasspath') { doLast { println('INFINITY_PERSIST_CP=' + p.sourceSets.gametest.compileClasspath.asPath) } } } }\n")
        result = subprocess.run([str(ROOT / "java/gradlew"), "-p", str(ROOT / "java"), "--no-daemon", "--offline", "-q", "-I", str(init), "infinityPersistenceClasspath"],
                                env={**os.environ, "JAVA_HOME": str(java.parents[1])}, text=True, capture_output=True, check=True, timeout=120)
        values = [line[len("INFINITY_PERSIST_CP="):] for line in result.stdout.splitlines() if line.startswith("INFINITY_PERSIST_CP=")]
        if len(values) != 1:
            raise RuntimeError("Cannot read exact existing gametest compile metadata")
        classpath = values[0]
    classpath = os.pathsep.join([classpath, str(original_tests), str(via_version), str(target / "mods" / VIABACKWARDS)])
    subprocess.run([str(javac), "--release", "25", "-encoding", "UTF-8", "-classpath", classpath, "-d", str(compiled), str(SOURCE)], check=True, timeout=120)
    selected_tests = target / "mods" / original_tests.name
    augment_test_jar(original_tests, selected_tests, compiled); selected_hash = digest(selected_tests)
    report = target / "results.xml"; native_proof = target / "native-persistence.json"; log_path = target / "persistence.log"
    with log_path.open("w") as log:
        result = subprocess.run([str(java), "-Xmx2G", "-Dinfinity.gametest.paced=true", "-Dfabric-api.gametest",
                                 "-Dinfinity.creative.capture=" + str(captured_packets),
                                 "-Dinfinity.creative.catalogue=" + str(captured_catalogue),
                                 "-Dinfinity.creative.persistence.report=" + str(native_proof),
                                 "-Dfabric-api.gametest.report-file=" + str(report), "-jar", "fabric-server-launch.jar", "nogui"],
                                cwd=target, stdout=log, stderr=subprocess.STDOUT, timeout=300)
    if result.returncode:
        raise RuntimeError(f"Supplementary real ingress/disk/rejoin process failed; see {log_path}")
    try:
        cases = passed_cases(report, 2)
    except ValueError as error:
        raise RuntimeError(f"Supplementary real ingress/disk/rejoin proof failed; see {log_path}") from error
    native = bounded_json(native_proof)
    expected_counts = {"hotbar_cases": 180, "distinct_item_count_cases": 60, "disk_cases": 60,
                       "rejoin_cases": 60, "canonical_identities": 46, "native_save_batches": 2,
                       "genuine_reconnects": 2, "variant_inputs": 5, "framed_inbound_packets": 245,
                       "backpack_styles_preserved": 3}
    expected_true = ("catalogue_equivalent", "receive_hook_canonical_before_listener",
                     "renamed_damaged_weapon_preserved", "personal_backpack_preserved", "observer_file_unchanged")
    if (any(native.get(field) != value for field, value in expected_counts.items())
            or any(native.get(field) is not True for field in expected_true)
            or native["capture_sha256"] != capture_hash or native["catalogue_sha256"] != catalogue_hash
            or native["native_catalogue_sha256"] != digest(target / "crossplay-export/infinity-catalogue.json")
            or native["viabackwards_sha256"] != pinned["fabric/mods/" + VIABACKWARDS]
            or native["viafabric_sha256"] != pinned["fabric/mods/" + VIAFABRIC]
            or native["viaversion_nested_sha256"] != digest(via_version)
            or native["observer_check_scope"] != "before fixture cleanup and ordinary server shutdown saves"
            or len(native["observer_file_sha256"]) != 64):
        raise RuntimeError("Actual native persistence proof does not cover its exact input/count/session/state requirements")
    executed_dependencies = {name: digest(target / "mods" / name) for name in (VIAFABRIC, VIABACKWARDS)}
    if any(value != pinned["fabric/mods/" + name] for name, value in executed_dependencies.items()):
        raise RuntimeError("Executed Via bytes differ from the exact pinned inputs")
    current_wire_evidence = verified_creative_wire(ROOT, bounded_json(lock_file), provenance)
    if (java_sources() != frozen_sources or supplementary_sources() != source_hashes
            or {str(path): digest(path) for path in input_paths} != frozen_inputs
            or runtime_inputs(runtime) != runtime_hashes or digest(selected_mod) != provenance["mod_sha256"]
            or digest(selected_tests) != selected_hash or digest(captured_packets) != capture_hash
            or digest(captured_catalogue) != catalogue_hash
            or {name: hashlib.sha256(data).hexdigest() for name, data in current_wire_evidence.items()} != wire_hashes):
        raise RuntimeError("Required sources/artifacts/proofs changed during the supplementary check; preserve this failed attempt and rerun")
    output = {"format_version": 1, "version": VERSION, "source_hashes": source_hashes,
              "java_sources": frozen_sources, "native_provenance_sha256": digest(provenance_file),
              "mod_sha256": digest(mod), "original_test_mod_sha256": digest(original_tests),
              "persistence_test_mod_sha256": selected_hash, "wire_receipt_sha256": wire_receipt_hash,
              "wire_evidence_sha256": wire_hashes, "capture_sha256": capture_hash,
              "geyser_startup_sha256": startup_hash, "captured_catalogue_sha256": catalogue_hash,
              "native_catalogue_sha256": native["native_catalogue_sha256"],
              "extension_sha256": digest(extension), "runtime_inputs": runtime_hashes,
              "executed_via_dependencies": executed_dependencies, "native_tests": len(cases),
              "report_sha256": digest(report), "native_proof_sha256": digest(native_proof),
              "log_sha256": digest(log_path), "runtime": target.relative_to(ROOT).as_posix(), "proof": native}
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation preserves every earlier successful proof, including explore.37.
    with receipt_path.open("x") as output_file:
        output_file.write(json.dumps(output, indent=2) + "\n")
    print(f"Supplementary persistence proof passed: 180 real inbound inputs, 60 disk/rejoin item-count cases, all 46 identities; {receipt_path}")


if __name__ == "__main__":
    main()
