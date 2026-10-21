#!/usr/bin/env python3
"""Rebuild the experimental Snapshot 5 ports from public pinned source.

All checkouts, Maven publications and logs stay in a disposable research folder.
Nothing publishes upstream or touches a playable server. Staging accepts only
the exact artifacts reviewed and tested in this repository's dependency lock.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
INPUTS = ROOT / "compatibility/snapshot5"
LAYOUT = {"polymer": "upstream-polymer", "floodgate": "upstream-floodgate",
          "viaversion": "upstream-bridge/ViaVersion",
          "viabackwards": "upstream-bridge/ViaBackwards", "viafabric": "upstream-viafabric"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True, help="Full Java 25 JDK")
    parser.add_argument("--output", type=Path, default=ROOT / "research/snapshot5-rebuild")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--stage", action="store_true", help="Stage hash-matching output in vendor/snapshot5")
    args = parser.parse_args()
    output = args.output.resolve()
    if ROOT / "research" not in output.parents:
        raise ValueError("Build output must be a disposable folder under research/")
    jdk = args.java_home.resolve()
    if not (jdk / "bin/javac").is_file():
        raise ValueError("A complete Java 25 JDK is required")
    env = {**os.environ, "JAVA_HOME": str(jdk),
           "PATH": str(jdk / "bin") + os.pathsep + os.environ.get("PATH", "")}
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((INPUTS / "sources.json").read_text())
    for entry in manifest["projects"]:
        target = output / LAYOUT[entry["name"]]
        patch = INPUTS / entry["patch"]
        if digest(patch) != entry["patch_sha256"]:
            raise ValueError("Source patch checksum mismatch: " + entry["name"])
        if not target.exists():
            target.mkdir(parents=True)
            subprocess.run(["git", "init", "--quiet", str(target)], check=True)
            subprocess.run(["git", "-C", str(target), "fetch", "--depth", "1",
                            entry["repository"], entry["commit"]], check=True)
            subprocess.run(["git", "-C", str(target), "checkout", "--detach", "FETCH_HEAD"], check=True)
            subprocess.run(["git", "-C", str(target), "apply", "--check", str(patch)], check=True)
            subprocess.run(["git", "-C", str(target), "apply", str(patch)], check=True)
        head = subprocess.check_output(["git", "-C", str(target), "rev-parse", "HEAD"], text=True).strip()
        if head != entry["commit"]:
            raise ValueError("Unexpected upstream commit: " + entry["name"])
        for name, expected in entry["changed_inputs"].items():
            path = target / name
            if (digest(path) if path.is_file() else None) != expected:
                raise ValueError("Changed port input: " + entry["name"] + "/" + name)
        license_file = next(p for p in (target / "LICENSE", target / "LICENSE.txt", target / "LICENSE.md") if p.is_file())
        if digest(license_file) != entry["license_sha256"]:
            raise ValueError("Upstream license changed")
    if args.prepare_only:
        print("All five pinned public source patches verified.")
        return

    def build(name, *tasks):
        target = output / LAYOUT[name]
        command = [str(target / "gradlew"), "--no-daemon", "--console=plain", *tasks]
        with (output / (name + "-build.log")).open("w") as log:
            subprocess.run(command, cwd=target, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)

    maven = output / "upstream-bridge/m2-repository"
    local = "-Dmaven.repo.local=" + str(maven)
    build("viaversion", local, ":viaversion-api:publishToMavenLocal", ":viaversion-common:publishToMavenLocal")
    build("viabackwards", local, ":viabackwards-common:test", ":viabackwards:shadowJar")
    build("viafabric", "build")
    build("floodgate", ":fabric:build")
    # A private file repository, never an upstream publish destination.
    polymer_maven = output / "polymer-maven"
    init = output / "polymer-private-maven.gradle"
    init.write_text("gradle.projectsEvaluated { allprojects { project -> "
                    "project.publishing.repositories.maven { name = 'Snapshot5Private'; url = uri(" +
                    json.dumps(str(polymer_maven)) + ") } } }\n")
    build("polymer", "jar", "-I", str(init), "publishAllPublicationsToSnapshot5PrivateRepository")
    artifacts = {
        "polymer-bundled-0.18.5-infinity-snapshot5+26.4-snapshot-5.jar": output / LAYOUT["polymer"] / "build/libs/polymer-bundled-0.18.5-infinity-snapshot5+26.4-snapshot-5.jar",
        "floodgate-fabric-2.2.7-infinity-snapshot5.jar": output / LAYOUT["floodgate"] / "fabric/build/libs/floodgate-fabric.jar",
        "ViaFabric-0.4.22-infinity-snapshot5.1.jar": output / LAYOUT["viafabric"] / "build/libs/ViaFabric-0.4.22-infinity-snapshot5.1.jar",
        "ViaBackwards-5.13.0-infinity-snapshot5.2.jar": output / LAYOUT["viabackwards"] / "build/libs/ViaBackwards-5.13.0-infinity-snapshot5.2.jar"}
    hashes = {name: digest(path) for name, path in artifacts.items()}
    expected = json.loads((INPUTS / "artifacts.json").read_text())["sha256"]
    receipt = {"source_manifest_sha256": digest(INPUTS / "sources.json"), "artifacts": hashes,
               "exact_reviewed_artifacts": hashes == expected, "live_server_touched": False}
    (output / "build-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if hashes != expected:
        raise RuntimeError("Rebuilt artifacts differ from the reviewed bytes; inspect and revalidate before staging")
    if args.stage:
        vendor = ROOT / "vendor/snapshot5"
        vendor.mkdir(parents=True, exist_ok=True)
        for name, path in artifacts.items():
            shutil.copy2(path, vendor / name)
        shutil.copytree(polymer_maven, vendor / "maven", dirs_exist_ok=True)
    print("Experimental dependencies rebuilt and exact hashes verified.")


if __name__ == "__main__":
    main()
