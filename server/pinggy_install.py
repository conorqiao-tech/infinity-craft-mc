"""Install the pinned, official Apple Silicon Pinggy CLI on first use."""

import hashlib
import os
from pathlib import Path
import platform
import stat
import tempfile
import urllib.error
import urllib.request
from urllib.parse import urlsplit


VERSION = "v0.5.9"
URL = "https://github.com/Pinggy-io/cli-js/releases/download/v0.5.9/pinggy-macos-arm64"
SHA256 = "52e2791d5a15163478814af2f08010e0001fc087fde05867a239b150351d2b8e"
MAX_BYTES = 128 * 1024 * 1024
ARM64_MACHO = bytes.fromhex("cffaedfe0c000001")


class InstallError(RuntimeError):
    """An actionable, safe-to-display setup error."""


class HttpsRedirectsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        if urlsplit(newurl).scheme.lower() != "https":
            raise InstallError("The official download redirected away from HTTPS.")
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def safe_directory(path, create=False):
    """Check each installation directory without following a symlink."""
    path = Path(path)
    if path.is_symlink():
        raise InstallError(f"Refusing a linked setup folder: {path.name}.")
    if create:
        path.mkdir(mode=0o700, exist_ok=True)
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        raise InstallError(f"Setup folder is not a directory owned by you: {path.name}.")


def existing_binary(path):
    """Return True only for this exact verified executable; never overwrite an unknown file."""
    if path.is_symlink():
        raise InstallError("Refusing a linked Pinggy executable.")
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except FileNotFoundError:
        return False
    except OSError as error:
        raise InstallError("Could not safely inspect the existing Pinggy executable.") from error
    digest = hashlib.sha256()
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
            raise InstallError("The existing Pinggy path is not an ordinary file owned by you.")
        if info.st_size > MAX_BYTES:
            raise InstallError("The existing Pinggy executable is unexpectedly large.")
        if source.read(8) != ARM64_MACHO:
            raise InstallError("The existing Pinggy executable is not Apple Silicon Mac code.")
        source.seek(0)
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
        if digest.hexdigest() != SHA256:
            raise InstallError("The existing Pinggy executable has a different checksum. Inspect it before replacing it.")
        os.fchmod(source.fileno(), 0o700)
    return True


def install(root, opener=None):
    if platform.system() != "Darwin" or platform.machine().lower() not in {"arm64", "aarch64"}:
        raise InstallError("This Pinggy installer supports Apple Silicon Macs only.")
    root = Path(root).absolute()
    safe_directory(root)
    runtime = root / ".runtime"
    safe_directory(runtime, create=True)
    directory = runtime / "pinggy"
    safe_directory(directory, create=True)
    directory.chmod(0o700)
    binary = directory / "pinggy"
    if existing_binary(binary):
        print("Verified Pinggy CLI is already installed; reusing it.")
        return binary

    print("Downloading the official Pinggy CLI for Apple Silicon (about 93 MiB, first use only)...", flush=True)
    opener = opener or urllib.request.build_opener(HttpsRedirectsOnly())
    request = urllib.request.Request(URL, headers={"User-Agent": "InfinityArmor-Pinggy-Setup/1"})
    temporary = None
    try:
        with opener.open(request, timeout=30) as response:
            if urlsplit(response.geturl()).scheme.lower() != "https":
                raise InstallError("The official download did not use HTTPS.")
            advertised = response.headers.get("Content-Length")
            if advertised is not None and int(advertised) > MAX_BYTES:
                raise InstallError("The Pinggy download is larger than the pinned release allows.")
            descriptor, name = tempfile.mkstemp(prefix=".pinggy-download-", dir=directory)
            temporary = Path(name)
            digest = hashlib.sha256()
            count = 0
            with os.fdopen(descriptor, "wb") as output:
                while chunk := response.read(1024 * 1024):
                    count += len(chunk)
                    if count > MAX_BYTES:
                        raise InstallError("The Pinggy download exceeded the safe size limit.")
                    output.write(chunk)
                    digest.update(chunk)
                output.flush()
                os.fsync(output.fileno())
        if digest.hexdigest() != SHA256:
            raise InstallError("The Pinggy download failed SHA-256 verification; nothing was installed.")
        with temporary.open("rb") as downloaded:
            if downloaded.read(8) != ARM64_MACHO:
                raise InstallError("The downloaded executable is not Apple Silicon Mac code.")
        os.chmod(temporary, 0o700)
        # Refuse a path introduced while the download was in progress.
        if existing_binary(binary):
            print("Verified Pinggy CLI was installed by another setup; reusing it.")
            return binary
        os.replace(temporary, binary)
        temporary = None
        print(f"Installed and verified Pinggy CLI {VERSION} for Apple Silicon.")
        return binary
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    try:
        install(Path(__file__).resolve().parent)
        return 0
    except InstallError as error:
        print(f"Pinggy setup stopped: {error}")
    except (OSError, ValueError, urllib.error.URLError):
        print("Pinggy setup could not finish the official HTTPS download. Check the connection and retry.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
