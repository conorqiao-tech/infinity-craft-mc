"""Manage only this folder's two no-sign-in Pinggy game tunnels."""
import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import time
import urllib.request
from urllib.parse import urlparse

NAMES = (("Java", "infinity-java", "tcp", "java_port", 25565), ("Bedrock", "infinity-bedrock", "udp", "bedrock_port", 19132))


def paths(root):
    root=Path(root).resolve();private=root/".pinggy"
    return root,private,private/"configbase/pinggy",root/".runtime/pinggy/pinggy"


def prepare(root):
    root,private,config,binary=paths(root)
    for folder in (private,private/"configbase",config,config/"tunnels"):
        if folder.is_symlink():raise RuntimeError("Pinggy private directories must not be symlinks.")
        folder.mkdir(mode=0o700,exist_ok=True)
        if folder.stat().st_uid!=os.getuid():raise RuntimeError("Pinggy private files must belong to your user.")
        folder.chmod(0o700)
    for file in private.rglob("*"):
        if file.is_symlink():raise RuntimeError("Pinggy private files must not be symlinks.")
        if file.is_file():file.chmod(0o600)
    return root,private,config,binary


def targets(root):
    root=Path(root);file=root/"settings.json";settings=json.loads(file.read_text()) if file.is_file() else {}
    result=[]
    for edition,name,protocol,key,default in NAMES:
        port=settings.get(key,default)
        if isinstance(port,bool) or not isinstance(port,int) or not 1024<=port<=65535:raise RuntimeError("Invalid game port in settings.json.")
        result.append((edition,name,protocol,port))
    return result


def command(root,args):
    root,private,config,binary=prepare(root)
    if not binary.is_file() or not os.access(binary,os.X_OK):raise RuntimeError("The verified official Pinggy binary is not installed in .runtime/pinggy.")
    env=dict(os.environ);env["XDG_CONFIG_HOME"]=str(private/"configbase");env["PINGGY_LOG_FILE"]=str(private/"Pinggy.log")
    result=subprocess.run([str(binary),*args,"--logfile",str(private/"Pinggy.log")],cwd=root,env=env,
                          stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=60,umask=0o077)
    prepare(root)
    if result.returncode:raise RuntimeError("Pinggy could not complete the requested operation. Check the private Pinggy logs.")


def matches(config,name,protocol,port):
    forwarding = config.get("forwarding")
    if not isinstance(forwarding, list) or len(forwarding) != 1:
        return False
    target = forwarding[0]
    return (config.get("name")==name and config.get("autoReconnect") is False and not config.get("token")
            and target.get("type") == protocol and target.get("address") == f"127.0.0.1:{port}"
            and target.get("listenAddress") in (None, ""))


def saved(root,name):
    config=paths(root)[2]/"tunnels";found=[]
    for file in config.glob("*.json"):
        if file.is_symlink():raise RuntimeError("Saved Pinggy configuration must be a local file.")
        data=json.loads(file.read_text())
        if data.get("name")==name:found.append(data)
    if len(found)>1:raise RuntimeError("Duplicate game tunnel names were found; configurations were preserved.")
    return found[0] if found else None


def tunnels(root):
    from remote_joining import process_identity
    root,private,config,binary=paths(root);infofile=config/"daemon.json"
    if not infofile.exists():return []
    if infofile.is_symlink():raise RuntimeError("Pinggy daemon record must be a local file.")
    info=json.loads(infofile.read_text());identity=process_identity(info.get("pid"))
    if not identity:return []
    if Path(identity["exe"]).resolve()!=binary.resolve() or "--_daemon-child" not in identity["argv"]:raise RuntimeError("The Pinggy process could not be verified.")
    port=info.get("port")
    if isinstance(port,bool) or not isinstance(port,int) or not 1024<=port<=65535:raise RuntimeError("Invalid private daemon port.")
    def get(route):
        request=urllib.request.Request(f"http://127.0.0.1:{port}{route}",headers={"X-Pinggy-Origin":"cli"})
        # Local IPC must not follow a redirect or inherit an HTTP proxy.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs):return None
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
        with opener.open(request,timeout=3) as response:return json.load(response)
    ping=get("/ping")
    if ping.get("pid")!=info.get("pid") or ping.get("ipcVersion")!=1:raise RuntimeError("The local daemon API does not match the owned agent.")
    result=get("/tunnels")
    if not isinstance(result,list):raise RuntimeError("Pinggy did not return tunnel status.")
    return result


def public_status(root,listing):
    results=[]
    for edition,name,protocol,port in targets(root):
        found=None;address=None;best=-1
        for item in listing:
            if not matches(item.get("tunnelconfig",{}),name,protocol,port):continue
            state=item.get("status",{}).get("state","stopped")
            candidate=None
            if state=="running":
                for value in item.get("remoteurls",[]):
                    try:
                        parsed=urlparse(value)
                        if parsed.scheme==protocol and parsed.hostname and parsed.port:
                            candidate={"host":parsed.hostname,"port":parsed.port};break
                    except (TypeError,ValueError):
                        continue
            priority=3 if candidate else 2 if state=="starting" else 1 if state=="running" else 0
            if priority>=best:found=item;address=candidate;best=priority
        state=(found or {}).get("status",{}).get("state","stopped")
        results.append({"edition":edition,"state":state if state in {"running","starting","stopped","error"} else "unavailable","ready":address is not None,"address":address,"verified":False})
    return results


def status(root):
    prepare(root);result=public_status(root,tunnels(root))
    private=paths(root)[1];destination=private/"addresses.json"
    if destination.is_symlink():raise RuntimeError("Address record must be a local file.")
    descriptor=os.open(destination,os.O_WRONLY|os.O_CREAT|os.O_TRUNC|getattr(os,"O_NOFOLLOW",0),0o600)
    with os.fdopen(descriptor,"w") as output:json.dump({"tunnels":result,"all_ready":all(t["ready"] for t in result)},output,indent=2);output.write("\n")
    for item in result:
        address=item["address"]
        print(f"{item['edition']}: "+(f"{address['host']}:{address['port']} (allocated; outside-network test still needed)" if address else f"{item['state']} — no public address ready"))
    return result


def start(root):
    prepare(root)
    for edition,name,protocol,port in targets(root):
        existing=saved(root,name)
        if existing is not None:
            if not matches(existing.get("tunnelConfig",{}),name,protocol,port):raise RuntimeError("An existing game tunnel has different settings. Its configuration was preserved.")
        else:
            command(root,["config","save",name,"--type",protocol,"-l",f"127.0.0.1:{port}","--no-autoreconnect"])
            existing=saved(root,name)
            if not existing or not matches(existing.get("tunnelConfig",{}),name,protocol,port):raise RuntimeError("The saved tunnel did not match the requested game target.")
    listing=tunnels(root)
    for edition,name,protocol,port in targets(root):
        running=next((item for item in listing if matches(item.get("tunnelconfig",{}),name,protocol,port) and item.get("status",{}).get("state") in {"running","starting"}),None)
        if not running:command(root,["start",name,"--b"])
    result=[]
    for _ in range(12):
        result=public_status(root,tunnels(root))
        if all(item["ready"] for item in result):break
        time.sleep(1)
    return status(root)


def stop(root):
    prepare(root);listing=tunnels(root)
    for edition,name,protocol,port in targets(root):
        if any(matches(item.get("tunnelconfig",{}),name,protocol,port) for item in listing):command(root,["stop",name])
    print("Stopped this folder's game tunnels. Minecraft and other Pinggy tunnels are unchanged.")


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("action",choices=["start","status","stop"],nargs="?",default="start");parser.add_argument("--root",type=Path,default=Path(__file__).resolve().parent);args=parser.parse_args(argv)
    try:
        result={"start":start,"status":status,"stop":stop}[args.action](args.root)
        return 1 if args.action=="start" and not all(item["ready"] for item in result) else 0
    except (RuntimeError,OSError,ValueError,subprocess.SubprocessError):
        print("Pinggy joining could not complete. Check PINGGY_JOINING.md and the private logs.");return 1


if __name__=="__main__":raise SystemExit(main())
