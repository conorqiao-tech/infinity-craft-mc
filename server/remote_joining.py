"""Optional private Playit companion. It does not start or change Minecraft."""
import argparse
import ctypes
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import struct
import subprocess
import sys
import time
from remote_tunnels import SetupRequired

CLAIM = re.compile(r"https://playit\.gg/claim/[0-9a-f]{10}(?=\s|$)")
PHASES = {"waiting_for_secret", "has_invalid_secret", "disabled_over_limit", "starting", "running", "stopping", "error"}


def paths(root):
    root = Path(root).resolve()
    private = root / ".remote"
    return {"root": root, "private": private, "exe": root / ".runtime/playit/playitd",
            "cli": root / ".runtime/playit/playit-cli", "config": private / "playit.toml",
            "socket": private / "playitd.sock", "log": private / "playitd.log",
            "console": private / "launcher.log", "record": private / "agent.json"}


def secure_file(path):
    if not path.exists() and not path.is_symlink():
        return
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        raise RuntimeError("Private remote-joining files must be regular local files owned by your user.")
    path.chmod(0o600)


def prepare(root, create=True):
    p = paths(root)
    if not p["private"].exists() and not p["private"].is_symlink():
        if not create:
            return p
        p["private"].mkdir(mode=0o700)
    info = p["private"].lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
        raise RuntimeError("The .remote folder must be a local directory owned by your user.")
    p["private"].chmod(0o700)
    for name in ("config", "log", "console", "record"):
        secure_file(p[name])
    return p


def command(p):
    return [str(p["exe"].resolve()), "--secret-path", str(p["config"]),
            "--socket-path", str(p["socket"]), "--log-path", str(p["log"])]


def process_identity(pid):
    """Read real executable and NUL-delimited argv, including paths with spaces."""
    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 1:
        return None
    try:
        if sys.platform == "darwin":
            library = ctypes.CDLL("/usr/lib/libproc.dylib")
            executable = ctypes.create_string_buffer(4096)
            if library.proc_pidpath(pid, executable, len(executable)) <= 0:
                return None
            libc = ctypes.CDLL(None, use_errno=True)
            data = ctypes.create_string_buffer(1024 * 1024)
            size = ctypes.c_size_t(len(data))
            mib = (ctypes.c_int * 3)(1, 49, pid)  # CTL_KERN, KERN_PROCARGS2
            if libc.sysctl(mib, 3, data, ctypes.byref(size), None, 0) != 0:
                return None
            raw = data.raw[:size.value]
            count = struct.unpack_from("=i", raw)[0]
            offset = raw.index(b"\0", 4) + 1
            while raw[offset] == 0:
                offset += 1
            argv = []
            for _ in range(count):
                end = raw.index(b"\0", offset)
                argv.append(os.fsdecode(raw[offset:end])); offset = end + 1
            birth = subprocess.check_output(["/bin/ps", "-p", str(pid), "-o", "lstart="], text=True, stderr=subprocess.DEVNULL).strip()
            return {"exe": os.fsdecode(executable.value), "argv": argv, "birth": birth}
        if sys.platform.startswith("linux"):
            proc = Path("/proc") / str(pid)
            raw = (proc / "stat").read_text()
            fields = raw[raw.rfind(")") + 2:].split()
            if fields[0] == "Z":
                return None
            return {"exe": str((proc / "exe").resolve()),
                    "argv": [os.fsdecode(v) for v in (proc / "cmdline").read_bytes().split(b"\0") if v], "birth": fields[19]}
    except (OSError, ValueError, IndexError, struct.error, subprocess.SubprocessError):
        return None
    return None


def read_record(p):
    if not p["record"].exists():
        return None
    try:
        record = json.loads(p["record"].read_text())
        if (record.get("format") != 1 or isinstance(record.get("pid"), bool) or not isinstance(record.get("pid"), int)
                or record["pid"] <= 1 or record.get("command") != command(p)
                or not isinstance(record.get("birth"), str) or not record["birth"]):
            raise ValueError()
        return record
    except (ValueError, KeyError, TypeError):
        raise RuntimeError("The remote process record could not be verified. No process was changed.") from None


def matches(p, record, identity):
    return bool(identity and Path(identity["exe"]).resolve() == p["exe"].resolve()
                and identity["argv"] == command(p) and identity["birth"] == record["birth"])


def owned(p, record):
    identity = process_identity(record["pid"])
    if identity is not None and not matches(p, record, identity):
        raise RuntimeError("The stored PID belongs to a different process. No process was changed.")
    return identity is not None


def ipc(p, request):
    info = p["socket"].lstat()
    if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
        raise RuntimeError("The private Playit socket could not be verified.")
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(2)
        connection.connect(str(p["socket"]))
        with connection.makefile("rwb") as stream:
            hello = json.loads(stream.readline(262144))
            if hello.get("message_kind") != "hello" or hello.get("data", {}).get("protocol", {}).get("ipc_version") != 2:
                raise RuntimeError("Unsupported Playit IPC version.")
            stream.write((json.dumps({"ipc_version": 2, "request_id": 1, "request": {"type": request}}) + "\n").encode()); stream.flush()
            for _ in range(8):
                envelope = json.loads(stream.readline(262144))
                if envelope.get("message_kind") == "response":
                    response = envelope.get("data", {})
                    if response.get("ipc_version") != 2 or response.get("request_id") != 1:
                        raise RuntimeError("Invalid Playit IPC response.")
                    value = response.get("response", {})
                    if value.get("type") == "error":
                        raise RuntimeError("Playit did not accept the local request.")
                    return value
    raise RuntimeError("Playit did not return a local response.")


def environment():
    result = dict(os.environ)
    result.pop("API_BASE", None)  # Use the official API, including during claim setup.
    result["RUST_LOG"] = "warn"
    return result


def private_append(path):
    flags = os.O_WRONLY | os.O_CREAT | os.O_APPEND | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    os.fchmod(descriptor, 0o600)
    return os.fdopen(descriptor, "ab", buffering=0)


def start(root):
    p = prepare(root)
    record = read_record(p)
    if record:
        if owned(p, record):
            return record
        raise RuntimeError("A previous remote process record remains. Run remote_joining.py stop before starting again.")
    if not p["exe"].is_file() or not os.access(p["exe"], os.X_OK):
        raise RuntimeError("The official Playit daemon is not installed in .runtime/playit. Complete the supplied Mac setup first.")
    if len(os.fsencode(p["socket"])) >= (104 if sys.platform == "darwin" else 108):
        raise RuntimeError("This folder path is too long for the private agent socket. Move the server to a shorter path.")
    if p["socket"].exists() or p["socket"].is_symlink():
        raise RuntimeError("A private socket already exists without a verified process record. No process was changed.")
    # Never create an empty TOML: playitd waits for IPC provisioning when it is absent.
    with private_append(p["console"]) as output, private_append(p["log"]):
        process = subprocess.Popen(command(p), cwd=p["root"], stdin=subprocess.DEVNULL, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True, close_fds=True,
                                   umask=0o077, env=environment())
    identity = None
    for _ in range(40):
        identity = process_identity(process.pid)
        if identity and identity["argv"] == command(p):
            break
        if process.poll() is not None:
            raise RuntimeError("Playit did not start. Its local logs are private in .remote.")
        time.sleep(.05)
    if not identity or identity["argv"] != command(p):
        process.terminate()
        raise RuntimeError("The new daemon process could not be verified.")
    record = {"format": 1, "pid": process.pid, "birth": identity["birth"], "command": command(p)}
    descriptor = os.open(p["record"], os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(record, output); output.write("\n")
    for _ in range(40):
        try:
            state = ipc(p, "get_status").get("data", {})
            if state.get("pid") == record["pid"]:
                return record
        except (OSError, ValueError, RuntimeError):
            pass
        time.sleep(.05)
    raise RuntimeError("The daemon started, but its private socket is not ready. Run status again shortly.")


def local_status(root):
    p = prepare(root, create=False)
    record = read_record(p)
    if not record or not owned(p, record):
        print("Remote agent: stopped."); return False
    state = ipc(p, "get_status").get("data", {})
    if state.get("pid") != record["pid"]:
        raise RuntimeError("The private socket does not match the owned daemon. No process was changed.")
    phase = state.get("phase")
    print("Remote agent: " + (phase.replace("_", " ") if phase in PHASES else "unknown") + ".")
    print("Account connected: " + ("yes" if state.get("has_secret") is True else "pending claim") + ".")
    return state.get("has_secret") is True


def status(root):
    connected = local_status(root)
    if connected:
        from remote_tunnels import configure, display
        display(configure(root, create=False))
    else:
        print("Public joining is pending account claim and tunnel setup.")


def setup(root):
    start(root); p = prepare(root)
    if not local_status(root):
        if not p["cli"].is_file() or not os.access(p["cli"], os.X_OK):
            raise RuntimeError("The official Playit CLI is not installed in .runtime/playit.")
        print("Waiting for the Playit account claim. Open the official claim link when it appears.", flush=True)
        process = subprocess.Popen([str(p["cli"].resolve()), "--socket-path", str(p["socket"]), "--stdout", "setup"],
                                   cwd=p["root"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, errors="replace", bufsize=1, umask=0o077, env=environment())
        try:
            # Official setup can also print a guest session login URL. Only forward
            # the intended claim URL; never print raw CLI output or credential links.
            for line in iter(lambda: process.stdout.readline(32768), ""):
                for link in CLAIM.findall(line):
                    print(link, flush=True)
            if process.wait() != 0:
                raise RuntimeError("Playit account setup did not finish. Rerun setup to try again.")
        except BaseException:
            process.terminate()
            try: process.wait(timeout=3)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
            raise
        finally:
            process.stdout.close()
    from remote_tunnels import configure, display
    display(configure(root, create=True))
    print("Keep the Mac awake and start the shared Minecraft world with Start-Mac.command.")


def stop(root):
    p = prepare(root, create=False); record = read_record(p)
    if not record:
        print("Remote agent: already stopped."); return
    if owned(p, record):
        try:
            state = ipc(p, "get_status").get("data", {})
            if state.get("pid") != record["pid"]:
                raise RuntimeError("The private socket does not match the owned daemon. No process was changed.")
            response = ipc(p, "stop")
            if response.get("data", {}).get("accepted") is not True:
                raise RuntimeError("The local daemon did not accept shutdown.")
        except (OSError, ValueError):
            # Recheck process identity immediately before a graceful SIGINT fallback.
            if owned(p, record): os.kill(record["pid"], signal.SIGINT)
        for _ in range(50):
            if not owned(p, record): break
            time.sleep(.1)
        else: raise RuntimeError("The owned daemon is still stopping. No other process was changed.")
    if p["socket"].exists():
        info = p["socket"].lstat()
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != os.getuid():
            raise RuntimeError("The leftover socket could not be verified; it was preserved.")
        p["socket"].unlink()
    p["record"].unlink()
    print("Remote agent stopped. Minecraft and LAN joining are unchanged.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("setup", "start", "status", "stop"), nargs="?", default="setup")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args(argv)
    try:
        {"setup": setup, "start": lambda root: (start(root), local_status(root)), "status": status, "stop": stop}[args.command](args.root)
        return 0
    except KeyboardInterrupt:
        print("Setup interrupted. The private agent can be stopped with remote_joining.py stop."); return 130
    except SetupRequired as error:
        print(str(error), file=sys.stderr)
        return 1
    except (RuntimeError, OSError, ValueError):
        # Deliberately avoid arbitrary API/IPC/CLI exception payloads with credentials.
        print("Remote joining could not complete. Check REMOTE_JOINING.md and the private .remote logs.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
