import contextlib
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"server"))
import remote_joining as remote
import remote_tunnels


class RemoteJoiningTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.p=remote.paths(self.root)

    def tearDown(self):self.temp.cleanup()

    def record(self,pid=8123):
        remote.prepare(self.root)
        record={"format":1,"pid":pid,"birth":"same birth","command":remote.command(self.p)}
        self.p["record"].write_text(json.dumps(record));return record

    def identity(self):return {"exe":str(self.p["exe"]),"argv":remote.command(self.p),"birth":"same birth"}

    def test_private_permissions_preserve_existing_credentials_and_no_empty_secret(self):
        remote.prepare(self.root)
        self.assertFalse(self.p["config"].exists())
        self.p["config"].write_text('secret_key = "PRIVATE_KEY"\n');self.p["config"].chmod(0o644)
        remote.prepare(self.root)
        self.assertEqual(self.p["config"].read_text(),'secret_key = "PRIVATE_KEY"\n')
        self.assertEqual(stat.S_IMODE(self.p["private"].stat().st_mode),0o700)
        self.assertEqual(stat.S_IMODE(self.p["config"].stat().st_mode),0o600)

    def test_symlink_directory_and_credentials_are_refused(self):
        other=self.root/"other";other.mkdir();self.p["private"].symlink_to(other,target_is_directory=True)
        with self.assertRaises(RuntimeError):remote.prepare(self.root)
        self.p["private"].unlink();remote.prepare(self.root)
        target=other/"credential";target.write_text("do not change");self.p["config"].symlink_to(target)
        with self.assertRaises(RuntimeError):remote.prepare(self.root)
        self.assertEqual(target.read_text(),"do not change")

    def test_real_process_identity_retains_arguments_with_spaces(self):
        args=[str(Path(sys.executable).resolve()),"-c","import time; time.sleep(10)","folder with spaces"]
        process=subprocess.Popen(args)
        try:
            identity=remote.process_identity(process.pid)
            self.assertIsNotNone(identity)
            self.assertEqual(identity["argv"],args)
            self.assertEqual(Path(identity["exe"]).resolve(),Path(args[0]).resolve())
            self.assertTrue(identity["birth"])
        finally:process.terminate();process.wait(timeout=3)

    def test_process_match_requires_executable_config_socket_log_and_birth(self):
        record=self.record();identity=self.identity()
        self.assertTrue(remote.matches(self.p,record,identity))
        for field,value in [("exe","/bin/sleep"),("argv",[str(self.p["exe"]),"--secret-path","/different/config"]),("birth","reused PID")]:
            foreign=dict(identity);foreign[field]=value;self.assertFalse(remote.matches(self.p,record,foreign))

    def test_stop_refuses_reused_pid_and_never_signals_foreign_process(self):
        self.record();identity=self.identity();identity["birth"]="different birth"
        with patch.object(remote,"process_identity",return_value=identity),patch.object(remote,"ipc") as ipc,patch.object(remote.os,"kill") as kill:
            with self.assertRaisesRegex(RuntimeError,"different process"):remote.stop(self.root)
            ipc.assert_not_called();kill.assert_not_called()
        self.assertTrue(self.p["record"].exists())

    def test_dead_record_cleanup_preserves_key_and_logs(self):
        self.record();self.p["config"].write_text("credential remains");self.p["log"].write_text("log remains")
        with patch.object(remote,"process_identity",return_value=None),patch.object(remote.os,"kill") as kill,contextlib.redirect_stdout(io.StringIO()):remote.stop(self.root);kill.assert_not_called()
        self.assertFalse(self.p["record"].exists());self.assertEqual(self.p["config"].read_text(),"credential remains");self.assertEqual(self.p["log"].read_text(),"log remains")

    def test_start_is_detached_private_and_uses_only_source_verified_flags(self):
        self.p["exe"].parent.mkdir(parents=True);self.p["exe"].write_text("test executable");self.p["exe"].chmod(0o755)
        process=Mock(pid=8123);identity=self.identity()
        with patch.object(remote.subprocess,"Popen",return_value=process) as spawn,patch.object(remote,"process_identity",return_value=identity),patch.object(remote,"ipc",return_value={"type":"status","data":{"pid":8123}}):
            remote.start(self.root)
        self.assertEqual(spawn.call_args.args[0],remote.command(self.p));self.assertTrue(spawn.call_args.kwargs["start_new_session"]);self.assertEqual(spawn.call_args.kwargs["umask"],0o077)
        self.assertNotIn("--secret",spawn.call_args.args[0]);self.assertFalse(self.p["config"].exists())
        for key in ("record","log","console"):self.assertEqual(stat.S_IMODE(self.p[key].stat().st_mode),0o600)

    def test_status_whitelists_fields_and_does_not_print_secret_session_or_errors(self):
        self.record();state={"pid":8123,"phase":"running","has_secret":True,"last_error":{"message":"PRIVATE_KEY"},"login_link":"https://playit.gg/login/guest-account/SESSION"}
        output=io.StringIO()
        with patch.object(remote,"process_identity",return_value=self.identity()),patch.object(remote,"ipc",return_value={"type":"status","data":state}),patch.object(remote_tunnels,"configure",return_value=[]) as configure,contextlib.redirect_stdout(output):remote.status(self.root)
        configure.assert_called_once_with(self.root,create=False)
        self.assertIn("running",output.getvalue());self.assertNotIn("PRIVATE_KEY",output.getvalue());self.assertNotIn("SESSION",output.getvalue())

    def test_setup_only_prints_official_claim_url_and_filters_guest_session(self):
        remote.prepare(self.root);self.p["cli"].parent.mkdir(parents=True);self.p["cli"].write_text("test executable");self.p["cli"].chmod(0o755)
        process=Mock(stdout=io.StringIO("secret_key = PRIVATE_KEY\nhttps://playit.gg/claim/0123456789\nGuest login:\nhttps://playit.gg/login/guest-account/SESSION\n"));process.wait.return_value=0
        output=io.StringIO()
        with patch.object(remote,"start"),patch.object(remote,"local_status",return_value=False),patch.object(remote.subprocess,"Popen",return_value=process),patch.object(remote_tunnels,"configure",return_value=[]) as configure,contextlib.redirect_stdout(output):remote.setup(self.root)
        configure.assert_called_once_with(self.root,create=True)
        self.assertIn("https://playit.gg/claim/0123456789",output.getvalue());self.assertNotIn("PRIVATE_KEY",output.getvalue());self.assertNotIn("SESSION",output.getvalue());self.assertNotIn("guest-account",output.getvalue())

    def test_status_when_absent_creates_no_private_files(self):
        with contextlib.redirect_stdout(io.StringIO()):remote.status(self.root)
        self.assertFalse(self.p["private"].exists())


if __name__=="__main__":unittest.main()
