import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
import remote_tunnels as remote


class RemoteTunnelsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / ".remote").mkdir(mode=0o700)
        (self.root / ".remote/playit.toml").write_text('secret_key = "abcdef123456"\n')

    def tunnel(self, kind="minecraft-java", protocol="tcp", port=25565):
        return {"tunnel_type": kind, "port_type": protocol, "port_count": 1,
                "origin": {"type": "agent", "data": {"agent_id": "own-agent",
                "local_ip": "127.0.0.1", "local_port": port}}, "proxy_protocol": None,
                "active": True, "alloc": {"status": "allocated", "data": {
                    "ip_hostname": "example.playit.gg", "port_start": 12345}}}

    def test_only_game_presets_created_and_retry_reuses(self):
        created = []
        tunnels = []
        def api(secret, route, body):
            self.assertEqual(secret, "abcdef123456")
            if route == "/agents/rundata": return {"agent_id": "own-agent"}
            if route == "/tunnels/list": return {"tunnels": tunnels[:]}
            self.assertEqual(route, "/tunnels/create")
            created.append(body)
            origin = body["origin"]["data"]
            self.assertEqual(origin["local_ip"], "127.0.0.1")
            self.assertEqual(origin["agent_id"], "own-agent")
            self.assertIsNone(body["proxy_protocol"])
            self.assertEqual(body["alloc"]["details"]["region"], "global")
            tunnels.append(self.tunnel(body["tunnel_type"], body["port_type"], origin["local_port"]))
            return {"id": "new"}
        with patch.object(remote, "api", side_effect=api):
            self.assertEqual(len(remote.configure(self.root, True)), 2)
            remote.configure(self.root, True)
        self.assertEqual([(b["tunnel_type"], b["port_type"], b["origin"]["data"]["local_port"])
                          for b in created], [("minecraft-java", "tcp", 25565),
                                             ("minecraft-bedrock", "udp", 19132)])
        record = (self.root / ".remote/addresses.json").read_text()
        self.assertNotIn("abcdef123456", record)
        self.assertNotIn("own-agent", record)
        self.assertEqual((self.root / ".remote/playit.toml").stat().st_mode & 0o777, 0o600)

    def test_status_does_not_create_or_report_unallocated(self):
        unallocated = self.tunnel()
        unallocated["alloc"] = {"status": "pending"}
        with patch.object(remote, "api", side_effect=[{"agent_id": "own-agent"},
                         {"tunnels": [unallocated]}]) as api:
            self.assertEqual(remote.configure(self.root), [])
        self.assertEqual(api.call_count, 2)

    def test_never_reuse_another_agent_or_nonloopback_target(self):
        tunnel = self.tunnel()
        tunnel["origin"]["data"]["agent_id"] = "someone-else"
        self.assertFalse(remote.matching(tunnel, "own-agent", "minecraft-java", "tcp", 25565))
        tunnel["origin"]["data"].update(agent_id="own-agent", local_ip="192.168.88.5")
        self.assertFalse(remote.matching(tunnel, "own-agent", "minecraft-java", "tcp", 25565))

    def test_invalid_port_fails_before_mutation(self):
        (self.root / "settings.json").write_text(json.dumps({"java_port": True}))
        with patch.object(remote, "api") as api:
            with self.assertRaises(RuntimeError): remote.configure(self.root, True)
        api.assert_not_called()

    def test_credential_symlink_rejected(self):
        path = self.root / ".remote/playit.toml"
        path.unlink()
        path.symlink_to(self.root / "other")
        with self.assertRaises(RuntimeError): remote.secret_in(self.root)

    def test_parent_symlink_rejected(self):
        directory = self.root / ".remote"
        directory.rename(self.root / "elsewhere")
        directory.symlink_to(self.root / "elsewhere")
        with self.assertRaises(RuntimeError): remote.secret_in(self.root)

    def test_pending_retry_does_not_create_duplicates(self):
        tunnels = [self.tunnel(), self.tunnel("minecraft-bedrock", "udp", 19132)]
        for tunnel in tunnels: tunnel["alloc"] = {"status": "pending"}
        with patch.object(remote, "api", side_effect=[{"agent_id": "own-agent"},
                                {"tunnels": tunnels}]) as api:
            self.assertEqual(remote.configure(self.root, True), [])
        self.assertEqual(api.call_count, 2)


if __name__ == "__main__": unittest.main()
