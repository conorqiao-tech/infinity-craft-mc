"""Offline checks for the pinned first-use Mac CLI installer and launcher order."""

import hashlib
import io
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import pinggy_install as installer


ARM64_SAMPLE = installer.ARM64_MACHO + b"fixture-Mac-arm64-binary\n"


class Response(io.BytesIO):
    def __init__(self, contents=ARM64_SAMPLE, url=installer.URL, advertised=None):
        super().__init__(contents)
        self.url = url
        self.headers = {"Content-Length": str(len(contents) if advertised is None else advertised)}

    def geturl(self):
        return self.url


class Opener:
    def __init__(self, factory=lambda: Response()):
        self.factory = factory
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request.full_url, timeout))
        return self.factory()


class PinggyInstallTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.patch_system = patch.object(installer.platform, "system", return_value="Darwin")
        self.patch_machine = patch.object(installer.platform, "machine", return_value="arm64")
        self.patch_hash = patch.object(installer, "SHA256", hashlib.sha256(ARM64_SAMPLE).hexdigest())
        for p in [self.patch_system, self.patch_machine, self.patch_hash]:
            p.start()
            self.addCleanup(p.stop)
        self.binary = self.root / ".runtime/pinggy/pinggy"

    def test_first_install_checks_pinned_https_release_and_installs_atomically(self):
        opener = Opener()
        self.assertEqual(installer.install(self.root, opener=opener), self.binary)
        self.assertEqual(opener.calls, [(installer.URL, 30)])
        self.assertEqual(self.binary.read_bytes(), ARM64_SAMPLE)
        self.assertEqual(stat.S_IMODE(self.binary.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(self.binary.parent.stat().st_mode), 0o700)
        self.assertEqual(list(self.binary.parent.glob(".pinggy-download-*")), [])

    def test_correct_copy_is_reused_without_network_and_permissions_fixed(self):
        installer.install(self.root, opener=Opener())
        self.binary.chmod(0o600)
        opener = Opener()
        self.assertEqual(installer.install(self.root, opener=opener), self.binary)
        self.assertEqual(opener.calls, [])
        self.assertEqual(stat.S_IMODE(self.binary.stat().st_mode), 0o700)

    def test_checksum_failure_leaves_no_executable_or_partial_download(self):
        with self.assertRaisesRegex(installer.InstallError, "SHA-256"):
            installer.install(self.root, opener=Opener(lambda: Response(contents=installer.ARM64_MACHO + b"corrupt")))
        self.assertFalse(self.binary.exists())
        self.assertEqual(list(self.binary.parent.iterdir()), [])
        installer.install(self.root, opener=Opener())
        self.assertEqual(self.binary.read_bytes(), ARM64_SAMPLE)

    def test_existing_wrong_file_is_preserved_without_network(self):
        self.binary.parent.mkdir(parents=True)
        self.binary.write_bytes(installer.ARM64_MACHO + b"unknown")
        opener = Opener()
        with self.assertRaisesRegex(installer.InstallError, "different checksum"):
            installer.install(self.root, opener=opener)
        self.assertEqual(self.binary.read_bytes(), installer.ARM64_MACHO + b"unknown")
        self.assertEqual(opener.calls, [])

    def test_wrong_architecture_is_rejected_before_network(self):
        with patch.object(installer.platform, "machine", return_value="x86_64"):
            with self.assertRaisesRegex(installer.InstallError, "Apple Silicon"):
                installer.install(self.root, opener=Opener())
        self.assertFalse((self.root / ".runtime").exists())
        wrong = b"\xcf\xfa\xed\xfe\x07\x00\x00\x01" + b"fixture-x64"
        with patch.object(installer, "SHA256", hashlib.sha256(wrong).hexdigest()):
            with self.assertRaisesRegex(installer.InstallError, "not Apple Silicon"):
                installer.install(self.root, opener=Opener(lambda: Response(contents=wrong)))
        self.assertFalse(self.binary.exists())

    def test_linked_binary_or_parent_is_refused_without_touching_target(self):
        outside = self.root / "outside"
        outside.write_bytes(b"preserve")
        self.binary.parent.mkdir(parents=True)
        self.binary.symlink_to(outside)
        with self.assertRaisesRegex(installer.InstallError, "linked"):
            installer.install(self.root, opener=Opener())
        self.assertEqual(outside.read_bytes(), b"preserve")
        self.binary.unlink()
        self.binary.parent.rmdir()
        (self.root / ".runtime/pinggy").symlink_to(outside)
        with self.assertRaisesRegex(installer.InstallError, "linked"):
            installer.install(self.root, opener=Opener())
        self.assertEqual(outside.read_bytes(), b"preserve")

    def test_https_redirect_and_final_response_are_required(self):
        handler = installer.HttpsRedirectsOnly()
        with self.assertRaisesRegex(installer.InstallError, "HTTPS"):
            handler.redirect_request(None, None, 302, "", {}, "http://example.test/pinggy")
        with self.assertRaisesRegex(installer.InstallError, "HTTPS"):
            installer.install(self.root, opener=Opener(lambda: Response(url="http://example.test/pinggy")))
        self.assertFalse(self.binary.exists())

    def test_large_header_or_stream_is_rejected_and_stage_removed(self):
        with patch.object(installer, "MAX_BYTES", len(ARM64_SAMPLE) - 1):
            with self.assertRaisesRegex(installer.InstallError, "larger"):
                installer.install(self.root, opener=Opener())
        self.assertFalse(self.binary.exists())
        with patch.object(installer, "MAX_BYTES", len(ARM64_SAMPLE) - 1):
            with self.assertRaisesRegex(installer.InstallError, "size limit"):
                installer.install(self.root, opener=Opener(lambda: Response(advertised=1)))
        self.assertFalse(self.binary.exists())
        self.assertEqual(list(self.binary.parent.iterdir()), [])

    def test_launcher_installs_before_tunnels_and_stops_after_install_failure(self):
        launcher = Path(__file__).resolve().parents[1] / "server/Start-Pinggy-Mac.command"
        shutil.copy2(launcher, self.root / launcher.name)
        fake = self.root / ".runtime/python/bin/python3"
        fake.parent.mkdir(parents=True)
        fake.write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> "$MOCK_LOG"\n[ "$MOCK_FAIL" != 1 ]\n')
        fake.chmod(0o700)
        log = self.root / "calls"
        env = dict(os.environ, MOCK_LOG=str(log), MOCK_FAIL="0")
        result = subprocess.run(["bash", str(self.root / launcher.name)], input="\n", text=True, env=env, capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(log.read_text().splitlines(), ["pinggy_install.py", "pinggy_joining.py"])
        log.unlink()
        env["MOCK_FAIL"] = "1"
        result = subprocess.run(["bash", str(self.root / launcher.name)], input="\n", text=True, env=env, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(log.read_text().splitlines(), ["pinggy_install.py"])


if __name__ == "__main__":
    unittest.main()
