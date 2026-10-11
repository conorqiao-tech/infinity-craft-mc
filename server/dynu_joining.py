"""Keep an existing, owner-created Dynu hostname pointed at this home's IPv4.

This is a DNS updater, not a tunnel. Router forwarding and an external Minecraft
connection test are separate. No DNS service is created or deleted here.
Protocol: https://www.dynu.com/Support/API and its published v2 OpenAPI schema.
"""
import argparse
from contextlib import contextmanager
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import threading
import time
import urllib.error
import urllib.request
import uuid

API = "https://api.dynu.com/v2"
CHECK_IP = "https://checkip.dynu.com"
TIMEOUT = 10
MAX_RESPONSE = 262144
WRITABLE = ("name", "group", "ipv4Address", "ipv6Address", "ttl", "ipv4",
            "ipv6", "ipv4WildcardAlias", "ipv6WildcardAlias", "allowZoneTransfer", "dnssec")


class SetupRequired(RuntimeError):
    """Only fixed local instructions, never raw provider responses or credentials."""


def hostname(value):
    if not isinstance(value, str) or not 3 <= len(value) <= 253 or value != value.lower():
        raise SetupRequired("Use a lowercase DNS hostname, without a URL or port.")
    labels = value.split(".")
    if len(labels) < 2 or any(not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
                              for label in labels):
        raise SetupRequired("Use a lowercase DNS hostname, without a URL or port.")
    if labels[-1] in ("local", "localhost", "internal", "invalid") or labels[-1].isdigit():
        raise SetupRequired("Use your public Dynu hostname, not a local name or IP address.")
    return value


def key_value(value):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9._~+/=-]{16,512}", value):
        raise SetupRequired("The private key file must contain only your Dynu API key.")
    return value


def _read_fd(fd, limit, private=True):
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or info.st_nlink != 1:
        raise SetupRequired("Local Dynu files must be regular files owned by your user.")
    if private and stat.S_IMODE(info.st_mode) not in (0o400, 0o600):
        raise SetupRequired("Protect the private Dynu key file with chmod 600 before setup.")
    if info.st_size > limit:
        raise SetupRequired("The local Dynu file is too large; it was preserved.")
    with os.fdopen(fd, "rb", closefd=False) as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise SetupRequired("The local Dynu file is too large; it was preserved.")
    return data


def _read_file(path, limit, private=True):
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            return _read_fd(fd, limit, private)
        finally:
            os.close(fd)
    except OSError:
        raise SetupRequired("The local Dynu file could not be read; symlinks are not allowed.") from None


@contextmanager
def _directory(root, create=False):
    root = Path(root).resolve()
    directory = root / ".dynu"
    try:
        if create:
            directory.mkdir(mode=0o700, exist_ok=True)
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            if os.fstat(fd).st_uid != os.getuid():
                raise SetupRequired("The private .dynu directory must belong to your user.")
            os.fchmod(fd, 0o700)
            yield fd
        finally:
            os.close(fd)
    except OSError:
        raise SetupRequired("Configure Dynu first in an owned local .dynu directory; symlinks are not allowed.") from None


def _read_at(directory, name, limit):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
    try:
        return _read_fd(fd, limit)
    finally:
        os.close(fd)


def _json(data):
    try:
        value = json.loads(data)
    except (ValueError, UnicodeError, RecursionError):
        raise SetupRequired("The Dynu data was not valid JSON; local files were preserved.") from None
    if not isinstance(value, dict):
        raise SetupRequired("The Dynu data did not have the expected format.")
    return value


def _write_at(directory, name, value):
    try:
        existing = os.stat(name, dir_fd=directory, follow_symlinks=False)
        if not stat.S_ISREG(existing.st_mode) or existing.st_uid != os.getuid() or existing.st_nlink != 1:
            raise SetupRequired("The existing Dynu file is not an owned regular file; it was preserved.")
    except FileNotFoundError:
        pass
    temporary = ".tmp-" + uuid.uuid4().hex
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=directory)
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(value, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, name, src_dir_fd=directory, dst_dir_fd=directory)
    finally:
        try:
            os.unlink(temporary, dir_fd=directory)
        except FileNotFoundError:
            pass


def configure(root, name, key_file):
    name = hostname(name)
    try:
        key = key_value(_read_file(key_file, 1024).decode("ascii").strip())
    except UnicodeError:
        raise SetupRequired("The private key file must contain only your Dynu API key.") from None
    with _directory(root, create=True) as directory:
        _write_at(directory, ".private.dynu", {"format": 1, "hostname": name, "api_key": key})
    return status(root)


def _config(root):
    with _directory(root) as directory:
        result = _json(_read_at(directory, ".private.dynu", 4096))
    if type(result.get("format")) is not int or result["format"] != 1:
        raise SetupRequired("The private Dynu configuration format is unsupported; it was preserved.")
    return hostname(result.get("hostname")), key_value(result.get("api_key"))


def targets(root):
    file = Path(root).resolve() / "settings.json"
    if file.is_symlink():
        raise SetupRequired("Game settings must be a regular local file.")
    settings = _json(_read_file(file, 65536, private=False)) if file.exists() else {}
    result = []
    for edition, key, default in (("Java", "java_port", 25565), ("Bedrock", "bedrock_port", 19132)):
        port = settings.get(key, default)
        if isinstance(port, bool) or not isinstance(port, int) or not 1024 <= port <= 65535:
            raise SetupRequired("Invalid Minecraft port in settings.json.")
        result.append((edition, port))
    return result


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _response(request, limit):
    # No credential-bearing redirects or environment-selected proxy servers.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    try:
        with opener.open(request, timeout=TIMEOUT) as response:
            if response.geturl() != request.full_url:
                raise SetupRequired("Dynu redirected the request; it was refused.")
            data = response.read(limit + 1)
    except (urllib.error.URLError, OSError, ValueError):
        raise SetupRequired("Dynu could not be reached or did not authorize the request. Check your connection and private API key.") from None
    if len(data) > limit:
        raise SetupRequired("Dynu returned too much data; the update was refused.")
    return data


def api(key, route, payload=None):
    if route != "/dns" and not re.fullmatch(r"/dns/[1-9][0-9]{0,9}", route):
        raise SetupRequired("Only the existing Dynu DNS service may be read or updated.")
    if payload is not None and route == "/dns":
        raise SetupRequired("Creating DNS services is not supported by this updater.")
    request = urllib.request.Request(API + route, method="POST" if payload is not None else "GET",
        data=json.dumps(payload).encode("utf-8") if payload is not None else None,
        headers={"API-Key": key_value(key), "Accept": "application/json",
                 "Content-Type": "application/json", "User-Agent": "InfinityArmor-Dynu/1.0"})
    result = _json(_response(request, MAX_RESPONSE))
    if result.get("statusCode") != 200:
        raise SetupRequired("Dynu did not approve this request. Check the hostname and API key in your Dynu account.")
    return result


def public_ipv4():
    request = urllib.request.Request(CHECK_IP, headers={"User-Agent": "InfinityArmor-Dynu/1.0"})
    try:
        value = _response(request, 256).decode("ascii").strip()
        match = re.fullmatch(r"Current IP Address:\s*([0-9.]+)", value)
        address = ipaddress.IPv4Address(match.group(1)) if match else None
    except (UnicodeError, ValueError):
        address = None
    if address is None or not address.is_global:
        raise SetupRequired("Dynu did not report a public IPv4 address; no DNS update was sent.")
    return str(address)


def _identity(record, name, identifier=None):
    return (isinstance(record, dict) and record.get("name") == name
            and isinstance(record.get("id"), int) and not isinstance(record.get("id"), bool)
            and 0 < record["id"] <= 2147483647
            and (identifier is None or record["id"] == identifier))


def update(root):
    name, key = _config(root)
    targets(root)  # Fail before any provider mutation when local ports are invalid.
    listing = api(key, "/dns").get("domains")
    if not isinstance(listing, list):
        raise SetupRequired("Dynu did not return its expected owned-hostname list.")
    matches = [record for record in listing if _identity(record, name)]
    if len(matches) != 1:
        raise SetupRequired("Create this exact hostname in your own Dynu account first. No DNS service was changed.")
    identifier = matches[0]["id"]
    route = "/dns/" + str(identifier)
    record = api(key, route)
    if (not _identity(record, name, identifier) or record.get("ipv4") is not True
            or not isinstance(record.get("state"), str) or record["state"].lower() != "complete"):
        raise SetupRequired("The owned hostname must be active and already have IPv4 enabled. Existing DNS settings were preserved.")
    address = public_ipv4()
    changed = record.get("ipv4Address") != address
    if changed:
        # Preserve every writable field from the owner's current service, including
        # IPv6, DNSSEC and wildcard choices. No record, TTL or unrelated host changes.
        payload = {field: record[field] for field in WRITABLE if field in record}
        payload["ipv4Address"] = address
        api(key, route, payload)
        confirmed = api(key, route)
        if (not _identity(confirmed, name, identifier)
                or any(confirmed.get(field) != value for field, value in payload.items())):
            raise SetupRequired("Dynu has not confirmed the requested IP update. Check the hostname in its control panel.")
    with _directory(root) as directory:
        _write_at(directory, "status.json", {"format": 1, "hostname": name,
                  "last_update": int(time.time()), "changed": changed})
    return status(root)


def status(root):
    name, _ = _config(root)
    result = {"hostname": name, "addresses": [{"edition": edition, "host": name,
              "port": port, "verified": False} for edition, port in targets(root)],
              "last_update": None, "external_connection_verified": False}
    with _directory(root) as directory:
        try:
            saved = _json(_read_at(directory, "status.json", 4096))
        except FileNotFoundError:
            saved = {}
    timestamp = saved.get("last_update")
    if (saved.get("format") == 1 and saved.get("hostname") == name
            and isinstance(timestamp, int) and not isinstance(timestamp, bool) and timestamp > 0):
        result["last_update"] = timestamp
    return result


def display(result):
    for address in result["addresses"]:
        print(f"{address['edition']}: {address['host']}:{address['port']}")
    print("Saved hostname" + ("; Dynu IP checked successfully" if result["last_update"] else "; Dynu IP update not checked yet"))
    print("External joining is unverified. Forward the two game ports on your router, keep the server on, and test from another Internet connection.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent)
    commands = parser.add_subparsers(dest="command", required=True)
    setup = commands.add_parser("configure", help="Store an existing hostname and a private key file locally")
    setup.add_argument("--hostname", required=True)
    setup.add_argument("--key-file", required=True, type=Path)
    updater = commands.add_parser("update", help="Update only the configured owned hostname's IPv4 address")
    updater.add_argument("--watch", action="store_true", help="Keep checking until Ctrl-C or a stop signal")
    updater.add_argument("--interval", type=int, default=300)
    commands.add_parser("status", help="Show local addresses without contacting Dynu")
    args = parser.parse_args(argv)
    if args.command == "update" and not 60 <= args.interval <= 86400:
        parser.error("--interval must be between 60 and 86400 seconds")
    try:
        if args.command == "configure":
            display(configure(args.root, args.hostname, args.key_file))
        elif args.command == "status":
            display(status(args.root))
        elif not args.watch:
            display(update(args.root))
        else:
            stop = threading.Event()
            signal.signal(signal.SIGTERM, lambda *_: stop.set())
            signal.signal(signal.SIGINT, lambda *_: stop.set())
            print("Dynu IP updater running. Keep this window open; Ctrl-C stops it.")
            while not stop.is_set():
                try:
                    display(update(args.root))
                except SetupRequired as error:
                    print(str(error))
                stop.wait(args.interval)
        return 0
    except SetupRequired as error:
        print(str(error))
        return 1
    except OSError:
        # Never include an arbitrary response, filename, exception or key in output.
        print("Dynu setup/update could not finish. Check your private key file, owned hostname, and Internet connection.")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
