import contextlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"server"))
import pinggy_joining as pinggy
import remote_joining


class PinggyJoiningTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name).resolve()
        pinggy.prepare(self.root)

    def tearDown(self):self.temp.cleanup()

    def config(self,name,protocol,port):
        return {"name":name,"autoReconnect":False,"token":"","forwarding":[{"type":protocol,"address":f"127.0.0.1:{port}"}]}

    def listing(self):
        return [{"tunnelconfig":self.config(name,protocol,port),"status":{"state":"running"},"remoteurls":[f"{protocol}://public-{protocol}.example:30001"],"secret":"do not expose"}
                for edition,name,protocol,port in pinggy.targets(self.root)]

    def save_profiles(self):
        for edition,name,protocol,port in pinggy.targets(self.root):
            (pinggy.paths(self.root)[2]/"tunnels"/f"{name}_test.json").write_text(json.dumps({"name":name,"tunnelConfig":self.config(name,protocol,port)}))

    def test_two_loopback_game_ports_and_settings_validation(self):
        self.assertEqual(pinggy.targets(self.root),[("Java","infinity-java","tcp",25565),("Bedrock","infinity-bedrock","udp",19132)])
        (self.root/"settings.json").write_text('{"java_port":25566,"bedrock_port":19133}')
        self.assertEqual([target[3] for target in pinggy.targets(self.root)],[25566,19133])
        (self.root/"settings.json").write_text('{"java_port":true}')
        with self.assertRaises(RuntimeError):pinggy.targets(self.root)

    def test_actual_sdk_empty_listen_address_matches_but_different_targets_do_not(self):
        config=self.config("infinity-java","tcp",25565)
        config["forwarding"][0]["listenAddress"]=""
        self.assertTrue(pinggy.matches(config,"infinity-java","tcp",25565))
        for field,value in [("listenAddress","0.0.0.0:25565"),("address","192.168.1.20:25565"),("type","udp")]:
            changed=json.loads(json.dumps(config));changed["forwarding"][0][field]=value
            self.assertFalse(pinggy.matches(changed,"infinity-java","tcp",25565))
        changed=json.loads(json.dumps(config));changed["forwarding"].append({"type":"tcp","address":"127.0.0.1:8080"})
        self.assertFalse(pinggy.matches(changed,"infinity-java","tcp",25565))

    def test_cli_command_dispatch_precedes_logfile_and_private_namespace(self):
        binary=pinggy.paths(self.root)[3];binary.parent.mkdir(parents=True);binary.write_text("test");binary.chmod(0o700)
        with patch.object(pinggy.subprocess,"run",return_value=Mock(returncode=0)) as run:
            pinggy.command(self.root,["start","infinity-java","--b"])
        args,kwargs=run.call_args
        self.assertEqual(args[0],[str(binary),"start","infinity-java","--b","--logfile",str(self.root/".pinggy/Pinggy.log")])
        self.assertEqual(kwargs["env"]["XDG_CONFIG_HOME"],str(self.root/".pinggy/configbase"))
        self.assertEqual(kwargs["umask"],0o077)
        self.assertEqual(kwargs["stdout"],pinggy.subprocess.PIPE)

    def test_existing_profiles_and_running_tunnels_are_reused(self):
        self.save_profiles();before={p:p.read_bytes() for p in (pinggy.paths(self.root)[2]/"tunnels").glob("*.json")}
        with patch.object(pinggy,"tunnels",return_value=self.listing()),patch.object(pinggy,"command") as command,contextlib.redirect_stdout(io.StringIO()):
            result=pinggy.start(self.root)
        command.assert_not_called();self.assertTrue(all(item["ready"] for item in result))
        self.assertEqual(before,{p:p.read_bytes() for p in before})

    def test_changed_existing_target_is_preserved_and_refused(self):
        file=pinggy.paths(self.root)[2]/"tunnels/infinity-java_existing.json"
        file.write_text(json.dumps({"name":"infinity-java","tunnelConfig":self.config("infinity-java","tcp",25566)}));before=file.read_bytes()
        with patch.object(pinggy,"command") as command:
            with self.assertRaises(RuntimeError):pinggy.start(self.root)
        command.assert_not_called();self.assertEqual(file.read_bytes(),before)

    def test_one_failed_edition_never_claims_both_ready_or_exposes_raw_fields(self):
        listing=self.listing();listing[1]["status"]["state"]="error";listing[1]["remoteurls"]=[]
        output=io.StringIO()
        with patch.object(pinggy,"tunnels",return_value=listing),contextlib.redirect_stdout(output):result=pinggy.status(self.root)
        self.assertTrue(result[0]["ready"]);self.assertFalse(result[1]["ready"])
        record=self.root/".pinggy/addresses.json";stored=json.loads(record.read_text())
        self.assertFalse(stored["all_ready"]);self.assertNotIn("secret",record.read_text());self.assertNotIn("do not expose",output.getvalue())
        self.assertFalse(result[0]["verified"])
        self.assertEqual(stat.S_IMODE(record.stat().st_mode),0o600)
        with patch.object(pinggy,"start",return_value=result):self.assertEqual(pinggy.main(["start","--root",str(self.root)]),1)

    def test_expired_records_do_not_hide_new_running_tunnels(self):
        running=self.listing()
        expired=[{"tunnelconfig":item["tunnelconfig"],"status":{"state":"exited"},"remoteurls":[]} for item in running]
        for listing in (expired+running,running+expired):
            result=pinggy.public_status(self.root,listing)
            self.assertEqual([item["state"] for item in result],["running","running"])
            self.assertTrue(all(item["ready"] for item in result))
            self.assertEqual([item["address"]["host"] for item in result],["public-tcp.example","public-udp.example"])

    def test_stop_only_matching_owned_names(self):
        listing=self.listing();listing.append({"tunnelconfig":self.config("another-app","tcp",25565)})
        with patch.object(pinggy,"tunnels",return_value=listing),patch.object(pinggy,"command") as command,contextlib.redirect_stdout(io.StringIO()):pinggy.stop(self.root)
        self.assertEqual([call.args[1] for call in command.call_args_list],[["stop","infinity-java"],["stop","infinity-bedrock"]])

    def test_private_permissions_and_symlink_safety(self):
        private=pinggy.paths(self.root)[1];file=private/"credentials.json";file.write_text("preserve");file.chmod(0o644)
        pinggy.prepare(self.root)
        self.assertEqual(stat.S_IMODE(private.stat().st_mode),0o700);self.assertEqual(stat.S_IMODE(file.stat().st_mode),0o600);self.assertEqual(file.read_text(),"preserve")
        foreign=self.root/"outside";foreign.write_text("outside unchanged");(private/"link").symlink_to(foreign)
        with self.assertRaises(RuntimeError):pinggy.prepare(self.root)
        self.assertEqual(foreign.read_text(),"outside unchanged")

    def test_foreign_daemon_is_rejected_before_local_api_query(self):
        (pinggy.paths(self.root)[2]/"daemon.json").write_text('{"pid":1234,"port":12345}')
        with patch.object(remote_joining,"process_identity",return_value={"exe":"/usr/bin/python3","argv":["--_daemon-child"]}),patch.object(pinggy.urllib.request,"build_opener") as request:
            with self.assertRaises(RuntimeError):pinggy.tunnels(self.root)
        request.assert_not_called()


if __name__=="__main__":unittest.main()
