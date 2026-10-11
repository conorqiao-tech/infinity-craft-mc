import ast
import io
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import dynu_joining as dynu


class DynuJoiningTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.key = "private-test-api-key-123456"
        self.name = "infinity-test.freeddns.org"
        self.key_file = self.root / "key.txt"
        self.key_file.write_text(self.key + "\n")
        self.key_file.chmod(0o600)

    def configure(self):
        return dynu.configure(self.root, self.name, self.key_file)

    def record(self, address="8.8.4.4"):
        return {"statusCode": 200, "id": 23, "name": self.name, "token": "provider-token",
                "state": "Complete", "group": "my-group", "ipv4Address": address,
                "ipv6Address": "2001:4860:4860::8888", "ttl": 600, "ipv4": True,
                "ipv6": True, "ipv4WildcardAlias": False, "ipv6WildcardAlias": True,
                "allowZoneTransfer": False, "dnssec": True}

    def test_private_configuration_and_read_only_status(self):
        (self.root / "settings.json").write_text(json.dumps({"java_port": 25575, "bedrock_port": 19142,
                                                          "private_api_key": "other-secret"}))
        with patch.object(dynu, "api") as api:
            result = self.configure()
            self.assertEqual(dynu.status(self.root), result)
        api.assert_not_called()
        self.assertEqual([a["port"] for a in result["addresses"]], [25575, 19142])
        self.assertFalse(result["external_connection_verified"])
        text = json.dumps(result)
        self.assertNotIn(self.key, text)
        self.assertNotIn("other-secret", text)
        self.assertEqual((self.root / ".dynu").stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.root / ".dynu/.private.dynu").stat().st_mode & 0o777, 0o600)

    def test_rejects_public_permissions_symlinks_and_oversized_credentials(self):
        self.key_file.chmod(0o644)
        with self.assertRaises(dynu.SetupRequired): self.configure()
        self.key_file.chmod(0o600)
        link = self.root / "link"
        link.symlink_to(self.key_file)
        with self.assertRaises(dynu.SetupRequired): dynu.configure(self.root, self.name, link)
        self.key_file.write_text("a" * 4097)
        with self.assertRaises(dynu.SetupRequired): self.configure()
        self.assertFalse((self.root / ".dynu").exists())

    def test_rejects_private_directory_and_saved_file_symlinks(self):
        other = self.root / "other"
        other.mkdir()
        directory = self.root / ".dynu"
        directory.symlink_to(other)
        with self.assertRaises(dynu.SetupRequired): self.configure()
        directory.unlink()
        self.configure()
        private = directory / ".private.dynu"
        private.unlink()
        private.symlink_to(self.key_file)
        with self.assertRaises(dynu.SetupRequired): dynu.status(self.root)

    def test_hostnames_and_invalid_ports_fail_before_network(self):
        for name in ("https://x.freeddns.org", "x.freeddns.org:25565", "127.0.0.1", "192.168.1.1",
                     "x.local", "Upper.freeddns.org", "-x.freeddns.org", "x..freeddns.org", "x/y.org"):
            with self.subTest(name=name), self.assertRaises(dynu.SetupRequired):
                dynu.configure(self.root, name, self.key_file)
        self.configure()
        (self.root / "settings.json").write_text('{"java_port":true}')
        with patch.object(dynu, "api") as api, self.assertRaises(dynu.SetupRequired): dynu.update(self.root)
        api.assert_not_called()

    def test_only_exact_owned_record_ip_changes_other_fields_preserved(self):
        self.configure()
        original = self.record()
        other = dict(original, id=24, name="another.freeddns.org")
        posted = []
        def api(key, route, payload=None):
            self.assertEqual(key, self.key)
            if route == "/dns": return {"domains": [other, original]}
            self.assertEqual(route, "/dns/23")
            if payload is not None:
                posted.append(payload)
                return {"statusCode": 200}
            return original if not posted else self.record("8.8.8.8")
        with patch.object(dynu, "api", side_effect=api), patch.object(dynu, "public_ipv4", return_value="8.8.8.8"):
            result = dynu.update(self.root)
        self.assertEqual(len(posted), 1)
        expected = {field: original[field] for field in dynu.WRITABLE if field in original}
        expected["ipv4Address"] = "8.8.8.8"
        self.assertEqual(posted[0], expected)
        self.assertNotIn("token", posted[0])
        self.assertIsNotNone(result["last_update"])
        self.assertFalse(result["external_connection_verified"])
        self.assertEqual(original["ipv4Address"], "8.8.4.4")

    def test_wrong_duplicate_or_disabled_owned_host_never_posts(self):
        self.configure()
        original = self.record()
        for listing in ([dict(original, name="other.freeddns.org")], [original, original]):
            with self.subTest(listing=len(listing)), patch.object(dynu, "api", return_value={"domains": listing}) as api:
                with self.assertRaises(dynu.SetupRequired): dynu.update(self.root)
                self.assertEqual(api.call_count, 1)
        for detail in (dict(original, id=24), dict(original, name="other.freeddns.org"),
                       dict(original, ipv4=False), dict(original, state="Expired")):
            with patch.object(dynu, "api", side_effect=[{"domains": [original]}, detail]) as api:
                with self.assertRaises(dynu.SetupRequired): dynu.update(self.root)
                self.assertEqual(api.call_count, 2)

    def test_unchanged_ip_does_not_post_and_failed_confirmation_has_no_success_stamp(self):
        self.configure()
        original = self.record()
        with patch.object(dynu, "api", side_effect=[{"domains": [original]}, original]) as api, \
                patch.object(dynu, "public_ipv4", return_value="8.8.4.4"):
            dynu.update(self.root)
        self.assertEqual(api.call_count, 2)
        (self.root / ".dynu/status.json").unlink()
        with patch.object(dynu, "api", side_effect=[{"domains": [original]}, original, {}, original]), \
                patch.object(dynu, "public_ipv4", return_value="8.8.8.8"):
            with self.assertRaises(dynu.SetupRequired): dynu.update(self.root)
        self.assertIsNone(dynu.status(self.root)["last_update"])
        with patch.object(dynu, "api", side_effect=[{"domains": [original]}, original, {},
                                                  dict(self.record("8.8.8.8"), ttl=90)]), \
                patch.object(dynu, "public_ipv4", return_value="8.8.8.8"):
            with self.assertRaises(dynu.SetupRequired): dynu.update(self.root)
        self.assertIsNone(dynu.status(self.root)["last_update"])

    def test_https_key_header_no_credentials_on_ip_probe_and_redirects_refused(self):
        class Response:
            def __init__(self, request, data): self.request, self.data = request, data
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def geturl(self): return self.request.full_url
            def read(self, limit): return self.data[:limit]
        class Opener:
            def open(_self, request, timeout):
                self.assertEqual(timeout, dynu.TIMEOUT)
                if request.full_url == dynu.CHECK_IP:
                    self.assertIsNone(request.get_header("Api-key"))
                    return Response(request, b"Current IP Address: 8.8.8.8\n")
                self.assertEqual(request.full_url, "https://api.dynu.com/v2/dns/23")
                self.assertEqual(request.get_header("Api-key"), self.key)
                self.assertNotIn(self.key, request.full_url)
                self.assertNotIn(self.key, request.data.decode())
                return Response(request, b'{"statusCode":200}')
        with patch.object(urllib.request, "build_opener", return_value=Opener()) as builder:
            self.assertEqual(dynu.public_ipv4(), "8.8.8.8")
            dynu.api(self.key, "/dns/23", {"ipv4Address": "8.8.8.8"})
            self.assertEqual(builder.call_args[0][0].proxies, {})
            self.assertIsInstance(builder.call_args[0][1], dynu._NoRedirect)
        request = urllib.request.Request(dynu.API + "/dns")
        self.assertIsNone(dynu._NoRedirect().redirect_request(request, None, 302, "", {}, "https://elsewhere.invalid"))
        for route, payload in (("/dns", {}), ("/dns/23/record", {}), ("/dns/-1", None)):
            with self.assertRaises(dynu.SetupRequired): dynu.api(self.key, route, payload)

    def test_provider_errors_and_cli_do_not_leak_keys_or_response_details(self):
        malicious = self.key + " account details"
        with patch.object(dynu, "_response", return_value=json.dumps({"statusCode": 401, "message": malicious}).encode()):
            with self.assertRaises(dynu.SetupRequired) as error: dynu.api(self.key, "/dns")
        self.assertNotIn(self.key, str(error.exception))
        self.configure()
        with patch.object(dynu, "api", side_effect=dynu.SetupRequired("Fixed safe error")), \
                patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(dynu.main(["--root", str(self.root), "update"]), 1)
        self.assertNotIn(self.key, output.getvalue())
        with patch.object(dynu, "_response", return_value=b"Current IP Address: 192.168.1.1"):
            with self.assertRaises(dynu.SetupRequired): dynu.public_ipv4()
        class BadOpener:
            def open(self, *_args, **_kwargs):
                raise urllib.error.URLError(malicious)
        with patch.object(urllib.request, "build_opener", return_value=BadOpener()):
            with self.assertRaises(dynu.SetupRequired) as error: dynu.api(self.key, "/dns")
        self.assertNotIn(self.key, str(error.exception))

    def test_foreground_watch_waits_and_stops_without_exposing_credentials(self):
        self.configure()
        class Event:
            stopped = False
            def is_set(self): return self.stopped
            def set(self): self.stopped = True
            def wait(self, interval):
                self.interval = interval
                self.set()
        event = Event()
        with patch.object(dynu.threading, "Event", return_value=event), \
                patch.object(dynu.signal, "signal") as signals, \
                patch.object(dynu, "update", return_value=dynu.status(self.root)) as update, \
                patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(dynu.main(["--root", str(self.root), "update", "--watch"]), 0)
        update.assert_called_once_with(self.root)
        self.assertEqual(event.interval, 300)
        self.assertEqual(signals.call_count, 2)
        self.assertNotIn(self.key, output.getvalue())
        self.assertIn("External joining is unverified", output.getvalue())

    def test_mac_launcher_requires_bootstrap_and_config_before_watch(self):
        source_root = Path(__file__).resolve().parents[1]
        folder = self.root / "server folder"
        folder.mkdir()
        launcher = folder / "Start-Dynu-Mac.command"
        shutil.copy2(source_root / "server/Start-Dynu-Mac.command", launcher)
        missing = subprocess.run(["bash", str(launcher)], input="\n", text=True, capture_output=True)
        self.assertEqual(missing.returncode, 1)
        self.assertIn("Start-Mac.command", missing.stdout)
        runtime = folder / ".runtime/python/bin"
        runtime.mkdir(parents=True)
        python = runtime / "python3"
        python.write_text("#!/usr/bin/env python3\nimport json, os, sys\n"
                          "with open(os.environ['DYNU_TEST_LOG'], 'a') as f: f.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                          "sys.exit(int(os.environ.get('DYNU_TEST_STATUS_RC', '0')) if sys.argv[-1] == 'status' else 0)\n")
        python.chmod(0o755)
        log = self.root / "launcher-arguments.jsonl"
        env = dict(os.environ, DYNU_TEST_LOG=str(log), DYNU_TEST_STATUS_RC="1")
        refusal = subprocess.run(["bash", str(launcher)], input="\n", text=True, capture_output=True, env=env)
        self.assertEqual(refusal.returncode, 1)
        self.assertIn("DYNU_JOINING.md", refusal.stdout)
        self.assertEqual([json.loads(line) for line in log.read_text().splitlines()],
                         [["dynu_joining.py", "--root", str(folder), "status"]])
        log.unlink()
        env["DYNU_TEST_STATUS_RC"] = "0"
        ready = subprocess.run(["bash", str(launcher)], input="\n", text=True, capture_output=True, env=env)
        self.assertEqual(ready.returncode, 0)
        self.assertEqual([json.loads(line) for line in log.read_text().splitlines()],
                         [["dynu_joining.py", "--root", str(folder), "status"],
                          ["dynu_joining.py", "--root", str(folder), "update", "--watch"]])

    def test_release_whitelist_includes_code_and_guide_but_no_private_state(self):
        source_root = Path(__file__).resolve().parents[1]
        packager = source_root / "tools/package_crossplay.py"
        tree = ast.parse(packager.read_text())
        assignment = next(node for node in ast.walk(tree) if isinstance(node, ast.Assign)
                          and any(isinstance(target, ast.Name) and target.id == "entries" for target in node.targets))
        whitelist = [node.value for node in assignment.value.generators[0].iter.elts]
        public = ("Start-Dynu-Mac.command", "DYNU_JOINING.md", "dynu_joining.py")
        for name in public: self.assertIn(name, whitelist)
        self.assertFalse(any(".dynu" in name or name == "dynu-api-key.txt" for name in whitelist))
        sys.path.insert(0, str(source_root / "tools"))
        self.addCleanup(lambda: sys.path.remove(str(source_root / "tools")))
        spec = importlib.util.spec_from_file_location("dynu_test_packager", packager)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        artifact = self.root / "public-fixture.zip"
        module.archive(artifact, {name: (source_root / "server" / name).read_bytes() for name in public}, "Server")
        with zipfile.ZipFile(artifact) as archive:
            self.assertEqual(set(archive.namelist()), {"Server/" + name for name in public})
            self.assertEqual(archive.getinfo("Server/Start-Dynu-Mac.command").external_attr >> 16, 0o755)
            self.assertIsNone(archive.testzip())


if __name__ == "__main__":
    unittest.main()
