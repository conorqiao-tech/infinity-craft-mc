"""Private, checksum-pinned Java runtime; never changes the system Java installation."""
import json
import os
from pathlib import Path
import platform
import shutil
import tarfile
import tempfile
import zipfile


def platform_key():
    system = {"Darwin": "darwin", "Windows": "windows", "Linux": "linux"}.get(platform.system())
    arch = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "x64", "amd64": "x64"}.get(platform.machine().lower())
    return str(system) + "-" + str(arch)


def java_in(folder):
    name = "java.exe" if os.name == "nt" else "java"
    return next((p for p in folder.rglob(name) if p.parent.name == "bin" and p.is_file()), None)


def extract_archive(archive, destination):
    """Validate every member before extracting into an empty, private directory."""
    destination = destination.resolve()

    def safe(name):
        if not (destination / name).resolve().is_relative_to(destination):
            raise RuntimeError("Runtime archive contains an unsafe path")

    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as bundle:
            for member in bundle.infolist():
                safe(member.filename)
                if (member.external_attr >> 16) & 0o170000 == 0o120000:
                    raise RuntimeError("Runtime ZIP contains a symbolic link")
            bundle.extractall(destination)
    else:
        with tarfile.open(archive) as bundle:
            members = bundle.getmembers()
            for member in members:
                safe(member.name)
                if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                    raise RuntimeError("Runtime archive contains a special file")
                if member.issym():
                    safe(str(Path(member.name).parent / member.linkname))
                elif member.islnk():
                    safe(member.linkname)
            # Python's data filter also validates paths after preceding links are created.
            if hasattr(tarfile, "data_filter"):
                bundle.extractall(destination, filter="data")
            else:
                # Older Python: links cannot be ancestors of any other member.
                links = {Path(m.name) for m in members if m.issym() or m.islnk()}
                if any(links.intersection(Path(m.name).parents) for m in members):
                    raise RuntimeError("Use the double-click launcher to extract this runtime safely")
                bundle.extractall(destination)


def install_java(root, fetch, emit):
    lock = json.loads((root / "runtime.lock.json").read_text())
    entry = lock["platforms"].get(platform_key(), {}).get("java")
    if not entry:
        raise RuntimeError("Automatic Java setup supports Mac Intel/Apple silicon, Windows x64, and Linux x64/arm64. Use --java for this computer.")
    emit("Setting up a private Java 21 runtime. Your system settings will stay the same.")
    archive = root / ".runtime/downloads" / entry["filename"]
    fetch(dict(entry, path=str(archive.relative_to(root))), root)
    runtime = root / ".runtime"
    with tempfile.TemporaryDirectory(prefix="java-stage-", dir=runtime) as temporary:
        staged = Path(temporary) / "java"
        staged.mkdir()
        extract_archive(archive, staged)
        if java_in(staged) is None:
            raise RuntimeError("The Java download did not contain its executable")
        destination = runtime / "java"
        if destination.exists():
            shutil.rmtree(destination)
        staged.replace(destination)
    archive.unlink(missing_ok=True)
    return str(java_in(destination))
