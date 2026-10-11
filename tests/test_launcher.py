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
        expected = [2, 12, int(lock["version"].rsplit(".", 1)[-1])]
        self.assertEqual(manifest["header"]["version"], expected)
        self.assertTrue(all(module["version"] == expected for module in manifest["modules"]))

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

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

    def test_bridge_requires_exports_and_key(self):
        (self.root / "geyser/custom_mappings").mkdir(parents=True)
        with self.assertRaisesRegex(RuntimeError, "did not export"):
            launcher.copy_bridge_data(self.root)
        source = self.root / "fabric/crossplay-export"
        source.mkdir(parents=True)
        for name in ["infinity-items.json", "infinity-blocks.json"]:
            (source / name).write_text("{}")
        with self.assertRaisesRegex(RuntimeError, "generate its key"):
            launcher.copy_bridge_data(self.root)
        key = self.root / "fabric/config/floodgate/key.pem"
        key.parent.mkdir(parents=True)
        key.write_bytes(b"test key, not a credential")
        launcher.copy_bridge_data(self.root)
        self.assertEqual((self.root / "geyser/key.pem").read_bytes(), key.read_bytes())

    def test_current_catalog_and_pack_cover_all_mapped_textures(self):
        import zipfile
        root = Path(__file__).resolve().parents[1]
        exports = root / "research/integration/crossplay-export"
        if not all((exports / name).is_file() for name in ("infinity-items.json", "infinity-blocks.json")):
            self.skipTest("Generated crossplay mapping exports are unavailable in this source-only checkout")
        mappings = json.loads((exports / "infinity-items.json").read_text())
        definitions = [entry for entries in mappings["items"].values() for entry in entries]
        self.assertEqual(len(definitions), 20)
        self.assertEqual(len({entry["bedrock_identifier"] for entry in definitions}), 20)
        self.assertTrue(all(entry["type"] == "definition" for entry in definitions))
        native_wearables={"helmet","chestplate","leggings","boots","backpack",*(outfit+"_"+piece for outfit in ("aurora","ember") for piece in ("helmet","chestplate","leggings","boots"))}
        self.assertTrue(native_wearables.isdisjoint({entry["bedrock_identifier"].split(":")[-1] for entry in definitions}), "Native equipment assets must not be replaced by broken custom wearable definitions")
        self.assertTrue(all(not entry["display_name"].startswith(("item.","block.")) for entry in definitions), "Custom mappings use literal human names")
        blocks = json.loads((exports / "infinity-blocks.json").read_text())
        self.assertEqual(len(blocks["blocks"]["minecraft:note_block"]["state_overrides"]), 10)
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
