import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import sys
import socket
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
spec = importlib.util.spec_from_file_location("launcher", Path(__file__).resolve().parents[1] / "server/server.py")
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class LauncherTests(unittest.TestCase):
    def test_custom_java_models_and_equipment_resolve_to_real_assets(self):
        root = Path(__file__).resolve().parents[1] / "java/src/main/resources/assets"
        def inspect(value, source):
            if isinstance(value, dict):
                for key, child in value.items():
                    if key in ("model", "parent") and isinstance(child, str) and child.startswith("convergence:"):
                        self.assertTrue((root / "convergence/models" / (child.split(":", 1)[1] + ".json")).is_file(), source + ": " + child)
                    if key == "textures" and isinstance(child, dict):
                        for texture in child.values():
                            if isinstance(texture, str) and texture.startswith("convergence:"):
                                self.assertTrue((root / "convergence/textures" / (texture.split(":", 1)[1] + ".png")).is_file(), source + ": " + texture)
                    inspect(child, source)
            elif isinstance(value, list):
                for child in value:
                    inspect(child, source)
        for path in (root / "convergence").rglob("*.json"):
            inspect(json.loads(path.read_text()), str(path.relative_to(root)))
        for path in (root / "convergence/equipment").glob("*.json"):
            for layer, entries in json.loads(path.read_text())["layers"].items():
                for entry in entries:
                    namespace, texture = entry["texture"].split(":", 1)
                    self.assertTrue((root / namespace / "textures/entity/equipment" / layer / (texture + ".png")).is_file(), str(path))

    def test_bedrock_pack_revision_invalidates_cached_exploration_resources(self):
        import zipfile
        root = Path(__file__).resolve().parents[1]
        lock = json.loads((root / "server/dependencies.lock.json").read_text())
        pack = root / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack"
        if not pack.is_file():
            self.skipTest("Prepared Bedrock pack unavailable in source-only checkout")
        with zipfile.ZipFile(pack) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        resource_spec = importlib.util.spec_from_file_location("resource_revision", root / "tools/crossplay_resources.py")
        resources = importlib.util.module_from_spec(resource_spec)
        resource_spec.loader.exec_module(resources)
        expected = resources.resource_version(lock["version"])
        self.assertEqual(manifest["header"]["version"], expected)
        self.assertTrue(all(module["version"] == expected for module in manifest["modules"]))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_setup_removes_superseded_pinned_mods_but_keeps_owner_mods(self):
        import hashlib
        mods = self.root / "fabric/mods"
        mods.mkdir(parents=True)
        current = {"fabric/mods/polymer-bundled-0.18.2+26.3.jar": b"new polymer",
                   "fabric/mods/Infinity-Armor-2.13.0.jar": b"new mod"}
        for path, data in current.items():
            (self.root / path).write_bytes(data)
        entries = [{"path": path, "sha256": hashlib.sha256(data).hexdigest()} for path, data in current.items()]
        (self.root / "dependencies.lock.json").write_text(json.dumps({
            "downloads": [dict(entries[0], url="https://example.invalid/polymer.jar")], "bundled": entries[1:]}))
        stale = ["polymer-bundled-0.15.2+1.21.11.jar", "Infinity-Armor-2.12.0-explore.16.jar",
                 "fabric-api-0.141.6+1.21.11.jar", "ViaVersion-5.12.0.jar",
                 "Floodgate-Fabric-2.2.7-b69.jar", "floodgate-fabric-2.2.7-old.jar"]
        for name in stale + ["owner-extra-mod.jar"]:
            (mods / name).write_bytes(b"old")
        with patch.object(launcher, "emit"):
            launcher.setup(self.root)
        self.assertEqual(sorted(p.name for p in mods.glob("*.jar")),
                         sorted(["polymer-bundled-0.18.2+26.3.jar", "Infinity-Armor-2.13.0.jar", "owner-extra-mod.jar"]))

    def test_port_check_rejects_live_tcp_listener_even_with_reuse(self):
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            args = launcher.parse_args(['--bind', '127.0.0.1', '--java-port', str(listener.getsockname()[1])])
            with self.assertRaisesRegex(RuntimeError, 'Java port'):
                launcher.check_ports(args)

    def test_port_check_rejects_live_bedrock_listener(self):
        with socket.socket() as tcp, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            tcp.bind(('127.0.0.1', 0))
            java_port = tcp.getsockname()[1]
            udp.bind(('127.0.0.1', 0))
            args = launcher.parse_args(['--bind', '127.0.0.1', '--java-port', str(java_port), '--bedrock-port', str(udp.getsockname()[1])])
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            udp.bind(('127.0.0.1', args.bedrock_port))
            with self.assertRaisesRegex(RuntimeError, 'Bedrock port'):
                launcher.check_ports(args)

    def test_port_check_accepts_recently_closed_tcp_connections(self):
        with socket.socket() as listener, socket.socket() as client, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
            listener.listen(1)
            client.connect(('127.0.0.1', port))
            connection, _ = listener.accept()
            connection.close()
            self.assertEqual(client.recv(1), b'')
            udp.bind(('127.0.0.1', 0))
            bedrock_port = udp.getsockname()[1]
        launcher.check_ports(launcher.parse_args(['--bind', '127.0.0.1', '--java-port', str(port), '--bedrock-port', str(bedrock_port)]))

    @unittest.skipUnless(launcher.os.name == 'posix', 'Automatic stale-lock recovery uses POSIX file locking')
    def test_crashed_launcher_lock_is_recovered_with_free_ports(self):
        lock = self.root / 'launcher.lock'
        lock.write_text('999999999')
        stale_inode = lock.stat().st_ino
        args = launcher.parse_args(['--bind', '127.0.0.1'])
        with patch.object(launcher.os, 'kill', side_effect=ProcessLookupError), \
             patch.object(launcher, 'check_ports') as ports:
            descriptor = launcher.acquire_launcher_lock(args, self.root)
        try:
            self.assertNotEqual(lock.stat().st_ino, stale_inode)
            self.assertEqual(lock.read_text(), '')
            ports.assert_called_once_with(args)
        finally:
            launcher.os.close(descriptor)
            lock.unlink()

    @unittest.skipUnless(launcher.os.name == 'posix', 'Automatic stale-lock recovery uses POSIX file locking')
    def test_active_or_unverifiable_launcher_lock_is_preserved(self):
        lock = self.root / 'launcher.lock'
        lock.write_text(str(launcher.os.getpid()))
        with patch.object(launcher, 'check_ports') as ports:
            with self.assertRaisesRegex(RuntimeError, 'may still be running'):
                launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
            ports.assert_not_called()
        self.assertEqual(lock.read_text(), str(launcher.os.getpid()))
        lock.write_text('not a process ID')
        with self.assertRaisesRegex(RuntimeError, 'valid process ID'):
            launcher.acquire_launcher_lock(launcher.parse_args([]), self.root)
        self.assertEqual(lock.read_text(), 'not a process ID')

    @unittest.skipUnless(launcher.os.name == 'posix', 'Automatic stale-lock recovery uses POSIX file locking')
    def test_crashed_lock_is_preserved_while_game_port_is_busy(self):
        lock = self.root / 'launcher.lock'
        lock.write_text('999999999')
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(1)
            args = launcher.parse_args(['--bind', '127.0.0.1', '--java-port', str(listener.getsockname()[1])])
            with patch.object(launcher.os, 'kill', side_effect=ProcessLookupError):
                with self.assertRaisesRegex(RuntimeError, 'Java port'):
                    launcher.acquire_launcher_lock(args, self.root)
        self.assertEqual(lock.read_text(), '999999999')

    def test_download_rejects_corruption_without_replacing_old_file(self):
        file = self.root / "library.jar"
        file.write_bytes(b"old file")
        entry = {"path": "library.jar", "url": "https://example.invalid/library.jar", "sha256": "0" * 64}
        with patch.object(launcher.urllib.request, "urlopen", return_value=io.BytesIO(b"bad download")):
            with self.assertRaisesRegex(RuntimeError, "Checksum mismatch"):
                launcher.fetch(entry, self.root)
        self.assertEqual(file.read_bytes(), b"old file")
        self.assertFalse((self.root / "library.jar.download").exists())

    def test_download_rejects_path_traversal(self):
        with self.assertRaisesRegex(RuntimeError, "escapes"):
            launcher.fetch({"path": "../escape.jar"}, self.root)

    def test_noninteractive_does_not_accept_eula(self):
        with patch.object(launcher.sys, "stdin", io.StringIO()):
            with self.assertRaisesRegex(RuntimeError, "--accept-eula"):
                launcher.accept_eula(False, self.root)
        self.assertFalse((self.root / "fabric/eula.txt").exists())

    def test_configuration_retains_world_and_security(self):
        fabric = self.root / "fabric"
        fabric.mkdir()
        (fabric / "server.properties").write_text("level-name=my-old-world\ndifficulty=hard\nonline-mode=false\n")
        args = launcher.parse_args(["--java-port", "25599", "--bedrock-port", "19199", "--bind", "127.0.0.1"])
        launcher.configure(args, self.root)
        props = launcher.properties(fabric / "server.properties")
        self.assertEqual(props["level-name"], "my-old-world")
        self.assertEqual(props["difficulty"], "hard")
        self.assertEqual(props["online-mode"], "true")
        self.assertEqual(props["server-port"], "25599")
        self.assertEqual(props["server-ip"], "127.0.0.1")
        self.assertIn("auth-type: floodgate", (self.root / "geyser/config.yml").read_text())
        self.assertFalse((fabric / "eula.txt").exists())

    def test_properties_follow_java_syntax(self):
        path = self.root / "server.properties"
        path.write_bytes(b"# comment ending in a backslash \\\n"
                         b"! bang comment\n"
                         b"   \t\f\n"
                         b"level-name=world\\u0032\n"
                         b"  motd : Hello \\\n      World\n"
                         b"a\\ b = c\n"
                         b"colon:value\n"
                         b"space value\n"
                         b"tabbed\t\t=\t value\n"
                         b"eq==x\n"
                         b"keyonly\n"
                         b"escapes=\\t\\n\\\\\\:\\=\\#x\n"
                         b"crlf=one\r\n"
                         b"cr=two\r"
                         b"dup=first\n"
                         b"dup=second\n"
                         b"latin=caf\xe9\n"
                         b"emoji=\\ud83d\\uDE00\n"
                         b"trailing=keep  \n"
                         b"\\#notcomment=1\n"
                         b"cont=a\\\n#still value\n"
                         b"even=a\\\\\n"
                         b"nel=a\x85b\n"
                         b"unknown\\qescape=\\q\n")
        expected = {"level-name": "world2", "motd": "Hello World", "a b": "c", "colon": "value",
                    "space": "value", "tabbed": "value", "eq": "=x", "keyonly": "",
                    "escapes": "\t\n\\:=#x", "crlf": "one", "cr": "two", "dup": "second",
                    "latin": "caf\u00e9", "emoji": "\U0001F600", "trailing": "keep  ",
                    "#notcomment": "1", "cont": "a#still value", "even": "a\\", "nel": "a\x85b",
                    "unknownqescape": "q"}
        self.assertEqual(launcher.properties(path), expected)
        copy = self.root / "copy.properties"
        launcher.write_properties(copy, expected)
        copy.read_bytes().decode("ascii")
        self.assertEqual(launcher.properties(copy), expected)
        path.write_bytes(b"# caf\xc3\xa9\nlevel-name=world\n")
        self.assertEqual(launcher.properties(path), {"level-name": "world"})

    def test_properties_decode_utf8_first_then_whole_file_iso_8859_1(self):
        path = self.root / "server.properties"
        utf8 = {"level-name": "wörld 世界", "motd": "Café \U0001F600 \\:", "été key": "v"}
        path.write_bytes("level-name=wörld 世界\nmotd=Café \U0001F600 \\\\\\:\nété\\ key=v\n".encode())
        self.assertEqual(launcher.properties(path), utf8)
        # One invalid UTF-8 byte makes Minecraft read the whole file as ISO-8859-1.
        path.write_bytes(b"motd=\xc3\xa9\nlevel-name=caf\xe9\n")
        self.assertEqual(launcher.properties(path), {"motd": "Ã©", "level-name": "café"})
        # Overlong and encoded-surrogate sequences are not strict UTF-8 either.
        path.write_bytes(b"level-name=a\xc0\xafb\n")
        self.assertEqual(launcher.properties(path), {"level-name": "aÀ¯b"})
        path.write_bytes(b"level-name=\xed\xa0\x80\n")
        self.assertEqual(launcher.properties(path), {"level-name": chr(0xED) + chr(0xA0) + chr(0x80)})
        # Strict UTF-8 keeps a byte-order mark as part of the first key, as Java does.
        path.write_bytes(b"\xef\xbb\xbflevel-name=x\n")
        self.assertEqual(launcher.properties(path), {chr(0xFEFF) + "level-name": "x"})
        for values in (utf8, {"level-name": "café", "motd": "Ã©"}):
            with self.subTest(values=values):
                launcher.write_properties(path, values)
                path.read_bytes().decode("ascii")
                self.assertEqual(launcher.properties(path), values)

    def test_unicode_world_directory_is_backed_up_and_kept_by_configuration(self):
        import unicodedata
        import zipfile
        fabric = self.root / "fabric"
        name = "wörld 世界 \U0001F600"
        (fabric / name).mkdir(parents=True)
        (fabric / name / "level.dat").write_bytes(b"unicode world")
        propfile = fabric / "server.properties"
        propfile.write_bytes(("level-name=" + name + "\nmotd=Café\n").encode())
        args = launcher.parse_args(["--bind", "127.0.0.1"])
        with patch.object(launcher, "check_ports"), \
             patch.object(launcher, "java_command", side_effect=InterruptedError), \
             patch.object(launcher, "emit"):
            launcher.run_server(args, root=self.root, console=False)
        archives = list((self.root / "backups").glob("Infinity-World-*.zip"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            entries = {unicodedata.normalize("NFC", entry): entry for entry in archive.namelist()}
            self.assertEqual(archive.read(entries[unicodedata.normalize("NFC", name + "/level.dat")]), b"unicode world")
        launcher.configure(args, self.root)
        props = launcher.properties(propfile)
        self.assertEqual(props["level-name"], name)
        self.assertEqual(props["motd"], "Café")
        launcher.configure(args, self.root)
        self.assertEqual(launcher.properties(propfile), props)

    def test_iso_8859_1_fallback_selects_the_same_world_as_minecraft(self):
        import zipfile
        fabric = self.root / "fabric"
        (fabric / "café").mkdir(parents=True)
        (fabric / "café/level.dat").write_bytes(b"latin world")
        (fabric / "server.properties").write_bytes(b"level-name=caf\xe9\n")
        with patch.object(launcher, "check_ports"), \
             patch.object(launcher, "java_command", side_effect=InterruptedError), \
             patch.object(launcher, "emit"):
            launcher.run_server(launcher.parse_args(["--bind", "127.0.0.1"]), root=self.root, console=False)
        archive = next((self.root / "backups").glob("Infinity-World-*.zip"))
        with zipfile.ZipFile(archive) as output:
            self.assertEqual(len([n for n in output.namelist() if n.endswith("/level.dat")]), 1)
        launcher.configure(launcher.parse_args([]), self.root)
        self.assertEqual(launcher.properties(fabric / "server.properties")["level-name"], "café")

    def test_properties_serialization_is_exact_java_text(self):
        self.assertEqual(launcher.render_properties({"level-name": "world2", " k:=": " v #!\\", "\u00e9": "\U0001F600\n"}),
                         "level-name=world2\n\\ k\\:\\==\\ v \\#\\!\\\\\n\\u00E9=\\uD83D\\uDE00\\n\n")
        values = {"": "empty key", "\t lead": " both ends \t", "nul": "\x00\x7f\x85",
                  "ctrl\r\n": "\f", "slashes\\\\": "\\u0032 is literal", "level-name": "w\u00f6rld \u4e16\u754c"}
        self.assertEqual(launcher.parse_properties(launcher.render_properties(values)), values)

    def test_malformed_properties_fail_before_changing_files(self):
        fabric = self.root / "fabric"
        fabric.mkdir()
        propfile = fabric / "server.properties"
        args = launcher.parse_args(["--bind", "127.0.0.1"])
        cases = {"bad hex": rb"level-name=world\u00G1" + b"\n", "short escape": rb"level-name=world\u12" + b"\n",
                 "continuation at end": b"level-name=world\\",
                 "UTF-8 file with bad escape": "level-name=wörld\n".encode() + rb"motd=\u00e" + b"\n",
                 "ISO-8859-1 file with bad escape": b"level-name=caf\xe9" + rb"\uZZZZ" + b"\n",
                 "unpaired surrogate": rb"level-name=\ud800" + b"\n",
                 "reversed surrogates": rb"level-name=\ude00\ud83d" + b"\n"}
        for case, content in cases.items():
            with self.subTest(case=case):
                propfile.write_bytes(content)
                with self.assertRaises(launcher.PropertiesError):
                    launcher.configure(args, self.root)
                self.assertEqual(propfile.read_bytes(), content)
                self.assertFalse((self.root / "geyser").exists())
                self.assertFalse((fabric / "config").exists())
                with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
                     patch.object(launcher.world_refresh, "recover") as recover, \
                     patch.object(launcher.community, "backup") as backup:
                    with self.assertRaises(launcher.PropertiesError):
                        launcher.run_server(args, root=self.root, console=False)
                recover.assert_not_called()
                backup.assert_not_called()
                self.assertEqual(propfile.read_bytes(), content)
                self.assertFalse((self.root / "launcher.lock").exists())
                self.assertFalse((self.root / "backups").exists())

    def test_symlinked_server_properties_is_refused_and_left_untouched(self):
        import os
        import stat
        fabric = self.root / "fabric"
        (fabric / "world").mkdir(parents=True)
        (fabric / "world/level.dat").write_bytes(b"linked world")
        link = fabric / "server.properties"
        args = launcher.parse_args(["--bind", "127.0.0.1"])
        targets = {"relative target inside fabric": (fabric / "shared.properties", "shared.properties"),
                   "absolute target outside the server": (self.root / "elsewhere.properties", None)}
        for case, (target, link_text) in targets.items():
            with self.subTest(case=case):
                target.write_bytes(b"level-name=world\nmotd=Linked\n")
                target.chmod(0o640)
                link.symlink_to(link_text or target)

                def snapshot():
                    info = target.stat()
                    return (os.readlink(link), link.lstat().st_ino, target.read_bytes(),
                            stat.S_IMODE(info.st_mode), info.st_ino, info.st_mtime_ns)

                before = snapshot()
                with self.assertRaisesRegex(launcher.PropertiesError, "symbolic link"):
                    launcher.configure(args, self.root)
                with self.assertRaisesRegex(launcher.PropertiesError, "symbolic link"):
                    launcher.write_properties(link, {"level-name": "world"})
                with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
                     patch.object(launcher.world_refresh, "recover") as recover, \
                     patch.object(launcher.community, "backup") as backup, \
                     patch.object(launcher, "java_command") as java, patch.object(launcher, "setup") as setup:
                    with self.assertRaisesRegex(launcher.PropertiesError, "symbolic link"):
                        launcher.run_server(args, root=self.root, console=False)
                for step in (recover, backup, java, setup):
                    step.assert_not_called()
                self.assertTrue(link.is_symlink())
                self.assertEqual(snapshot(), before)
                self.assertFalse((self.root / "launcher.lock").exists())
                self.assertFalse((self.root / "backups").exists())
                self.assertFalse((self.root / "geyser").exists())
                self.assertFalse((fabric / "config").exists())
                self.assertEqual([p.name for p in fabric.glob("server.properties.*")], [])
                link.unlink()

    def test_escaped_world_traversal_and_control_characters_are_refused(self):
        fabric = self.root / "fabric"
        fabric.mkdir()
        propfile = fabric / "server.properties"
        args = launcher.parse_args(["--bind", "127.0.0.1"])
        for content, message in ((rb"level-name=..\u002F..\u002Fescape" + b"\n", "inside"),
                                 (rb"level-name=\u002E" + b"\n", "inside"),
                                 (rb"level-name=world\u0000" + b"\n", "control characters")):
            with self.subTest(content=content):
                propfile.write_bytes(content)
                with self.assertRaisesRegex(ValueError, message):
                    launcher.configure(args, self.root)
                with patch.object(launcher, "check_ports"), patch.object(launcher, "emit"), \
                     patch.object(launcher.world_refresh, "recover") as recover:
                    with self.assertRaisesRegex(ValueError, message):
                        launcher.run_server(args, root=self.root, console=False)
                recover.assert_not_called()
                self.assertEqual(propfile.read_bytes(), content)
                self.assertFalse((self.root / "backups").exists())

    def test_startup_backs_up_the_escaped_world_java_selects(self):
        import zipfile
        fabric = self.root / "fabric"
        (fabric / "world2").mkdir(parents=True)
        (fabric / "world2/level.dat").write_bytes(b"escaped world")
        (fabric / "server.properties").write_bytes(rb"level-name=world\u0032" + b"\n")
        args = launcher.parse_args(["--bind", "127.0.0.1"])
        with patch.object(launcher, "check_ports"), \
             patch.object(launcher, "java_command", side_effect=InterruptedError), \
             patch.object(launcher, "emit") as emit:
            launcher.run_server(args, root=self.root, console=False)
        archives = list((self.root / "backups").glob("Infinity-World-*.zip"))
        self.assertEqual(len(archives), 1)
        with zipfile.ZipFile(archives[0]) as archive:
            self.assertEqual(archive.read("world2/level.dat"), b"escaped world")
        emit.assert_any_call("World backed up before startup: backups/" + archives[0].name)

    def test_configuration_roundtrip_keeps_escaped_world_and_java_values(self):
        fabric = self.root / "fabric"
        fabric.mkdir()
        propfile = fabric / "server.properties"
        propfile.write_bytes(rb"level-name=world\u0032" + b"\n" + rb"motd=Caf\u00e9 \\ \:ok" + b"\n"
                             b"level-seed = a\\=b\ncustom\\ key:keep  \nonline-mode=false\n")
        args = launcher.parse_args(["--java-port", "25599", "--bind", "127.0.0.1"])
        launcher.configure(args, self.root)
        written = propfile.read_bytes()
        written.decode("ascii")
        self.assertIn(b"level-name=world2\n", written)
        props = launcher.properties(propfile)
        self.assertEqual(props["level-name"], "world2")
        self.assertEqual(props["motd"], "Caf\u00e9 \\ :ok")
        self.assertEqual(props["level-seed"], "a=b")
        self.assertEqual(props["custom key"], "keep  ")
        self.assertEqual(props["online-mode"], "true")
        self.assertEqual(props["server-port"], "25599")
        self.assertEqual(props["gamemode"], "survival")
        launcher.configure(args, self.root)
        self.assertEqual(propfile.read_bytes(), written)

    def test_fresh_world_without_level_dat_starts_unbacked_and_keeps_its_name(self):
        fabric = self.root / "fabric"
        fabric.mkdir()
        (fabric / "server.properties").write_bytes(rb"level-name=brand\u002Dnew" + b"\n")
        args = launcher.parse_args(["--bind", "127.0.0.1"])
        with patch.object(launcher, "check_ports"), \
             patch.object(launcher, "java_command", side_effect=InterruptedError) as java, \
             patch.object(launcher, "emit"):
            launcher.run_server(args, root=self.root, console=False)
        java.assert_called_once()
        self.assertFalse((self.root / "backups").exists())
        launcher.configure(args, self.root)
        self.assertEqual(launcher.properties(fabric / "server.properties")["level-name"], "brand-new")
        self.assertFalse((fabric / "brand-new").exists())

    def test_eula_uses_java_properties_values(self):
        eula = self.root / "fabric/eula.txt"
        eula.parent.mkdir()
        with patch.object(launcher.sys, "stdin", io.StringIO()):
            for accepted in (b"eula = TRUE\n", rb"eula=\u0074rue" + b"\n", b"#note\neula:true\n"):
                with self.subTest(accepted=accepted):
                    eula.write_bytes(accepted)
                    launcher.accept_eula(False, self.root)
                    self.assertEqual(eula.read_bytes(), accepted)
            for declined in (b"eula=true \n", b"eula=false\n", b"eula=yes\n"):
                with self.subTest(declined=declined):
                    eula.write_bytes(declined)
                    with self.assertRaisesRegex(RuntimeError, "--accept-eula"):
                        launcher.accept_eula(False, self.root)
                    self.assertEqual(eula.read_bytes(), declined)
            eula.write_bytes(rb"eula=\u00" + b"\n")
            with self.assertRaises(launcher.PropertiesError):
                launcher.accept_eula(True, self.root)
            self.assertEqual(eula.read_bytes(), rb"eula=\u00" + b"\n")
        eula.write_bytes(b"eula=false\n")
        launcher.accept_eula(True, self.root)
        self.assertEqual(launcher.properties(eula)["eula"], "true")

    def test_bridge_requires_exports_and_key(self):
        (self.root / "geyser/custom_mappings").mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, "did not export"):
            launcher.copy_bridge_data(self.root)
        source = self.root / "fabric/crossplay-export"
        source.mkdir(parents=True)
        for name in ["infinity-items.json", "infinity-blocks.json", "infinity-catalogue.json"]:
            (source / name).write_text("{}")
        with self.assertRaisesRegex(RuntimeError, "generate its key"):
            launcher.copy_bridge_data(self.root)
        key = self.root / "fabric/config/floodgate/key.pem"
        key.parent.mkdir(parents=True)
        key.write_bytes(b"test key, not a credential")
        launcher.copy_bridge_data(self.root)
        self.assertEqual((self.root / "geyser/key.pem").read_bytes(), key.read_bytes())
        self.assertEqual((self.root / "geyser/infinity-catalogue.json").read_bytes(),
                         (source / "infinity-catalogue.json").read_bytes())

    def test_creative_readiness_rejects_missing_stale_and_failed_receipts(self):
        import hashlib
        folder = self.root / "geyser/extensions/infinitycreativebridge"
        folder.mkdir(parents=True)
        catalogue = self.root / "geyser/infinity-catalogue.json"
        catalogue.write_text('{"items":[{"path":"helmet"}]}')
        (self.root / "dependencies.lock.json").write_text(json.dumps({"creative_bridge":{"geyser_sha256":"test-pin"}}))
        receipt = {"format_version":1,"geyser_sha256":"test-pin",
                   "catalogue_sha256":hashlib.sha256(catalogue.read_bytes()).hexdigest(),
                   "items":1,"protocols":{"test-protocol":1},"unowned_primary_and_java_items_unchanged":True}
        with self.assertRaisesRegex(RuntimeError, "did not become ready"):
            launcher.verify_creative_ready(self.root)
        ready = folder / "ready.json"
        ready.write_text(json.dumps(receipt))
        self.assertEqual(launcher.verify_creative_ready(self.root),receipt)
        ready.write_text(json.dumps(dict(receipt,catalogue_sha256="stale")))
        with self.assertRaisesRegex(RuntimeError,"does not match"):
            launcher.verify_creative_ready(self.root)
        ready.write_text(json.dumps(receipt))
        (folder / "failed.json").write_text('{}')
        with self.assertRaisesRegex(RuntimeError,"did not become ready"):
            launcher.verify_creative_ready(self.root)
        launcher.clear_creative_receipt(self.root)
        self.assertFalse(ready.exists())
        self.assertFalse((folder / "failed.json").exists())

    def test_stop_during_creative_startup_is_a_clean_cancellation(self):
        import threading
        from types import SimpleNamespace
        stop = threading.Event()
        stop.set()
        service = SimpleNamespace(process=None)
        with self.assertRaisesRegex(InterruptedError,"Startup cancelled"):
            launcher.wait_creative_ready(service,self.root,stop)

    def ready_creative_fixture(self):
        import hashlib
        from types import SimpleNamespace
        from unittest.mock import Mock
        folder = self.root / "geyser/extensions/infinitycreativebridge"
        folder.mkdir(parents=True)
        catalogue = self.root / "geyser/infinity-catalogue.json"
        catalogue.write_text('{"items":[{"path":"helmet"}]}')
        (self.root / "dependencies.lock.json").write_text(json.dumps({"creative_bridge":{"geyser_sha256":"test-pin"}}))
        receipt = {"format_version":1,"geyser_sha256":"test-pin",
                   "catalogue_sha256":hashlib.sha256(catalogue.read_bytes()).hexdigest(),
                   "items":1,"protocols":{"test-protocol":1},"unowned_primary_and_java_items_unchanged":True}
        (folder / "ready.json").write_text(json.dumps(receipt))
        return SimpleNamespace(process=Mock(poll=Mock(return_value=None))), receipt

    def test_existing_creative_ready_receipt_does_not_override_owner_cancellation(self):
        import threading
        service, _ = self.ready_creative_fixture()
        stop = threading.Event(); stop.set()
        with self.assertRaisesRegex(InterruptedError,"Startup cancelled"):
            launcher.wait_creative_ready(service,self.root,stop)

    def test_existing_creative_ready_receipt_does_not_override_child_exit(self):
        service, _ = self.ready_creative_fixture()
        service.process.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError,"startup did not finish"):
            launcher.wait_creative_ready(service,self.root)

    def test_exit_during_actual_creative_receipt_validation_is_not_reported_ready(self):
        service, _ = self.ready_creative_fixture()
        verify = launcher.verify_creative_ready
        def finish_then_exit(root):
            receipt = verify(root)
            service.process.poll.return_value = 1
            return receipt
        with patch.object(launcher,"verify_creative_ready",side_effect=finish_then_exit):
            with self.assertRaisesRegex(RuntimeError,"startup did not finish"):
                launcher.wait_creative_ready(service,self.root)

    def test_live_uncancelled_child_with_matching_creative_receipt_is_ready(self):
        service, receipt = self.ready_creative_fixture()
        self.assertEqual(launcher.wait_creative_ready(service,self.root),receipt)

    def test_cancellation_during_actual_creative_receipt_validation_stays_cancelled(self):
        import threading
        service, _ = self.ready_creative_fixture()
        stop = threading.Event()
        verify = launcher.verify_creative_ready
        def finish_then_cancel(root):
            receipt = verify(root); stop.set(); return receipt
        with patch.object(launcher,"verify_creative_ready",side_effect=finish_then_cancel):
            with self.assertRaisesRegex(InterruptedError,"Startup cancelled"):
                launcher.wait_creative_ready(service,self.root,stop)


    def test_bedrock_pack_preserves_vanilla_armor_and_native_flight_artwork(self):
        import zipfile
        root = Path(__file__).resolve().parents[1]
        with zipfile.ZipFile(root / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
            for material in ("netherite", "leather"):
                for layer in (1, 2):
                    self.assertNotIn(f"textures/models/armor/{material}_{layer}.png",pack.namelist())
                for slot in ("helmet", "chestplate", "leggings", "boots"):
                    self.assertNotIn(f"textures/items/{material}_{slot}.png",pack.namelist())
            for name in ("elytra", "broken_elytra"):
                self.assertNotIn(f"textures/items/{name}.png", pack.namelist())
            self.assertNotIn("attachables/elytra.json", pack.namelist())
            self.assertNotIn("textures/models/armor/elytra.png", pack.namelist())
            self.assertNotIn("textures/entity/elytra.png", pack.namelist())
            atlas = json.loads(pack.read("textures/item_texture.json"))["texture_data"]
            self.assertEqual(pack.read(atlas["convergence_chestplate"]["textures"] + ".png"),
                (root / "java/src/main/resources/assets/convergence/textures/item/refresh27/chestplate.png").read_bytes(),
                "The Infinity Creative preview remains original without overriding the native flight item")

    def test_current_catalog_and_pack_cover_all_mapped_textures(self):
        import zipfile
        root = Path(__file__).resolve().parents[1]
        exports = root / "research/integration/crossplay-export"
        if not all((exports / name).is_file() for name in ("infinity-items.json", "infinity-blocks.json")):
            self.skipTest("Generated crossplay mapping exports are unavailable in this source-only checkout")
        mappings = json.loads((exports / "infinity-items.json").read_text())
        definitions = [entry for entries in mappings["items"].values() for entry in entries]
        catalogue = json.loads((exports / "infinity-catalogue.json").read_text())
        native_fallbacks = {"chestplate", "infinity_chestplate", "infinity_arrow", "void_arrow", "starfire_arrow"}
        expected_mapped = {entry["path"] for entry in catalogue["items"]} - native_fallbacks
        self.assertEqual(len(definitions), len(expected_mapped))
        self.assertEqual({entry["bedrock_identifier"].split(":")[-1] for entry in definitions}, expected_mapped)
        self.assertTrue({"starshard_lantern", "riftspool", "keepers_trowel"}.issubset(expected_mapped),
            "All three new original Astral tools must be in the actual native catalogue")
        self.assertTrue(all(entry["type"] == "definition" for entry in definitions))
        static_wearables={"helmet","cuirass","leggings","boots","backpack",*(outfit+"_"+piece for outfit in ("aurora","ember","elemental","infinity") for piece in ("helmet","chestplate","leggings","boots"))}
        static_wearables.remove("infinity_chestplate")
        infinity = next(entry for entry in catalogue["items"] if entry["path"] == "infinity_chestplate")
        self.assertEqual((infinity["base"], infinity["native_appearance"], infinity["slot"], infinity["protection"]),
                         ("minecraft:elytra", True, "chest", 14), "Infinity retains its canonical armor identity while using native flight")
        mapped={entry["bedrock_identifier"].split(":")[-1]:entry for entry in definitions}
        self.assertNotIn("chestplate",mapped,"Keep the existing glider on native elytra behavior")
        self.assertNotIn("infinity_chestplate",mapped,"The Infinity glider must also keep native elytra behavior")
        self.assertTrue({"bow", "crossbow", "shield", "totem"}.issubset(mapped), "Original held items have their own canonical model mappings")
        self.assertTrue({"infinity_arrow", "void_arrow", "starfire_arrow"}.isdisjoint(mapped), "Native arrow identities stay compatible with ordinary ranged weapons")
        self.assertTrue(static_wearables.issubset(mapped))
        for path in static_wearables:
            self.assertEqual(mapped[path]["components"]["minecraft:max_stack_size"],1,"Bedrock custom wearables require max stack one")
            self.assertIn("slot",mapped[path]["components"]["minecraft:equippable"])
        self.assertTrue(all(not entry["display_name"].startswith(("item.","block.")) for entry in definitions), "Custom mappings use literal human names")
        blocks = json.loads((exports / "infinity-blocks.json").read_text())
        self.assertEqual(len(blocks["blocks"]["minecraft:note_block"]["state_overrides"]), 15)
        with zipfile.ZipFile(root / "server/geyser/packs/Infinity_Armor_Crossplay.mcpack") as pack:
            atlas = json.loads(pack.read("textures/item_texture.json"))["texture_data"]
            for definition in definitions:
                icon = definition["bedrock_options"]["icon"]
                texture = atlas[icon]["textures"]
                if not texture.startswith("textures/items/netherite_"):
                    self.assertIn(texture + ".png", pack.namelist())
            terrain = json.loads(pack.read("textures/terrain_texture.json"))["texture_data"]
            for override in blocks["blocks"]["minecraft:note_block"]["state_overrides"].values():
                for material in override["material_instances"].values():
                    texture_id = material["texture"]
                    texture = terrain[texture_id]["textures"]
                    self.assertIn(texture + ".png", pack.namelist())
            self.assertNotIn("attachables/elytra.json", pack.namelist())
            manifest = json.loads(pack.read("manifest.json"))
            self.assertEqual([m["type"] for m in manifest["modules"]], ["resources"])


if __name__ == "__main__":
    unittest.main()
