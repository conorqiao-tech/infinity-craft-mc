"""Community server settings and stopped-world backups."""
from datetime import datetime, timezone
import json
import hashlib
import os
from pathlib import Path
import re
import uuid
import zipfile

DEFAULT_RULES = ["Respect other players.", "Do not grief, steal, or cheat.", "Follow staff instructions and report bugs."]


def configure_admin_code(root, enabled, code):
    if type(enabled) is not bool or not isinstance(code, str):
        raise ValueError("Choose the Admin code settings.")
    if code and (not 8 <= len(code) <= 128 or any(ord(c) < 32 for c in code)):
        raise ValueError("Use 8–128 characters without control characters for the private code.")
    path = root / "fabric/config/infinity-memberships.json"
    values = {"adminCodeEnabled": False, "adminCodeSha256": hashlib.sha256(b"").hexdigest()}
    if path.exists():
        try:
            values = json.loads(path.read_text())
            if not isinstance(values, dict) or not re.fullmatch(r"[a-f0-9]{64}", values.get("adminCodeSha256", "")):
                raise ValueError("Invalid private Admin configuration; original file kept.")
        except (json.JSONDecodeError, TypeError):
            raise ValueError("Invalid private Admin configuration; original file kept.") from None
    if code:
        values["adminCodeSha256"] = hashlib.sha256(code.encode()).hexdigest()
    if enabled and values["adminCodeSha256"] == hashlib.sha256(b"").hexdigest():
        raise ValueError("Enter a private Admin code before enabling redemption.")
    values["adminCodeEnabled"] = enabled
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(json.dumps(values, indent=2) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


AI_DEFAULTS = {"enabled": False, "provider": "openai", "codexExecutable": "", "model": "gpt-6-luna", "ownerOnly": True, "dailyRequestLimit": 50}


def ai_values(data):
    if not isinstance(data, dict):
        raise ValueError("Choose your AI chat settings.")
    values = {key: data.get(key, default) for key, default in AI_DEFAULTS.items()}
    if type(values["enabled"]) is not bool or type(values["ownerOnly"]) is not bool:
        raise ValueError("Choose who can use AI chat and whether it is enabled.")
    if not isinstance(values["provider"], str) or values["provider"] not in ("openai", "codex"):
        raise ValueError("Choose OpenAI API or Codex CLI as the AI provider.")
    executable = values["codexExecutable"]
    if not isinstance(executable, str) or len(executable) > 4096 or any(ord(c) < 32 or ord(c) == 127 for c in executable):
        raise ValueError("Enter one absolute Codex executable path without control characters or command arguments.")
    if executable and not Path(executable).is_absolute():
        raise ValueError("The Codex executable must be an absolute file path, without command arguments.")
    if values["provider"] == "codex" and not values["ownerOnly"]:
        raise ValueError("Codex uses the host's signed-in account and must be restricted to operators with level 4.")
    model = values["model"]
    if not isinstance(model, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,99}", model):
        raise ValueError("Enter a valid AI model name.")
    limit = values["dailyRequestLimit"]
    if type(limit) is not int or not 0 <= limit <= 1000:
        raise ValueError("Daily AI requests must be a whole number from 0 to 1000.")
    return values


def ai_status(root):
    config = root / "fabric/config/infinity-ai.json"
    key = root / "fabric/config/infinity-ai-key.txt"
    try:
        values = ai_values(json.loads(config.read_text())) if config.exists() else dict(AI_DEFAULTS)
    except (OSError, ValueError, TypeError):
        values = dict(AI_DEFAULTS)
    return {**values, "hasKey": key.is_file() and key.stat().st_size > 0}


def private_write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            stream.write(content)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def configure_ai(root, data):
    values = ai_values(data)
    secret = data.get("apiKey", "")
    if not isinstance(secret, str) or (secret and (not 16 <= len(secret) <= 4096 or any(not 33 <= ord(c) <= 126 for c in secret))):
        raise ValueError("Enter a valid API key without spaces or control characters.")
    if values["provider"] == "codex" and secret:
        raise ValueError("Codex uses its existing CLI login. Leave the API key blank; your saved API key will be kept.")
    config = root / "fabric/config/infinity-ai.json"
    key = root / "fabric/config/infinity-ai-key.txt"
    existing = {}
    if config.exists():
        try:
            existing = json.loads(config.read_text())
            ai_values(existing)
        except (ValueError, TypeError):
            raise ValueError("Invalid private AI configuration; original file kept.") from None
    if values["enabled"]:
        if values["provider"] == "codex":
            executable = Path(values["codexExecutable"])
            if not values["codexExecutable"] or not executable.is_file() or not os.access(executable, os.X_OK):
                raise ValueError("Choose an existing executable Codex file by its absolute path before enabling Codex chat. Do not include command arguments.")
        elif not secret and not (key.is_file() and key.stat().st_size > 0):
            raise ValueError("Enter an API key before enabling OpenAI API chat.")
    if secret:
        private_write(key, secret)
    private_write(config, json.dumps({**existing, **values}, indent=2) + "\n")


def settings(data):
    name = data.get("server_name", "Infinity Armor")
    if not isinstance(name, str) or not name.strip() or len(name) > 60 or any(ord(c) < 32 or c == "\\" for c in name):
        raise ValueError("Server name must be 1–60 characters without control characters or backslashes.")
    limit = data.get("max_players", 32)
    if type(limit) is not int or not 2 <= limit <= 200:
        raise ValueError("Player slots must be between 2 and 200. Slots are not a performance guarantee.")
    whitelist = data.get("whitelist", False)
    if type(whitelist) is not bool:
        raise ValueError("Choose whether to use the allowlist.")
    difficulty = data.get("difficulty", "normal")
    if difficulty not in ("peaceful", "easy", "normal", "hard"):
        raise ValueError("Choose a valid difficulty.")
    rules = data.get("rules", DEFAULT_RULES)
    if isinstance(rules, str):
        rules = [line.strip() for line in rules.splitlines() if line.strip()]
    if not isinstance(rules, list) or not 1 <= len(rules) <= 8 or any(not isinstance(r, str) or not r.strip() or len(r) > 160 or any(ord(c) < 32 for c in r) for r in rules):
        raise ValueError("Enter 1–8 rules, up to 160 characters each, one per line.")
    return {"server_name": name.strip(), "max_players": limit, "whitelist": whitelist,
            "difficulty": difficulty, "rules": rules}


def properties_text(value):
    return json.dumps(value, ensure_ascii=True)[1:-1]


def configure(root, props, data):
    values = settings(data)
    props.update({"motd": properties_text(values["server_name"] + " | Java + Bedrock | 5 Game Modes"),
                  "max-players": str(values["max_players"]), "white-list": str(values["whitelist"]).lower(),
                  "enforce-whitelist": "true", "difficulty": values["difficulty"]})
    props.setdefault("spawn-protection", "32")
    props.setdefault("player-idle-timeout", "20")
    props.setdefault("entity-broadcast-range-percentage", "75")
    config = root / "fabric/config/infinity-community.json"
    config.parent.mkdir(parents=True, exist_ok=True)
    config.write_text(json.dumps({"name": values["server_name"], "rules": values["rules"]}, indent=2) + "\n")
    return values


def backups(root):
    folder = root / "backups"
    return [{"name": p.name, "bytes": p.stat().st_size} for p in sorted(folder.glob("Infinity-World-*.zip"), reverse=True)[:10]]


def backup(root, props, locked=False):
    lockfile = root / "launcher.lock"
    fd = None
    if not locked:
        try:
            fd = os.open(lockfile, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError as error:
            raise ValueError("Save & Stop the server before backing up the world.") from error
    temporary = None
    try:
        fabric = (root / "fabric").resolve()
        world = (fabric / props.get("level-name", "world")).resolve()
        if world == fabric or not world.is_relative_to(fabric):
            raise ValueError("The world folder must be inside this server's fabric folder.")
        if not (world / "level.dat").is_file():
            return None
        folder = root / "backups"
        folder.mkdir(exist_ok=True)
        stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d_%H-%M-%S")
        destination = folder / ("Infinity-World-" + stamp + "-" + uuid.uuid4().hex[:6] + ".zip")
        temporary = destination.with_suffix(".partial")
        files = list(world.rglob("*"))
        files += [fabric / name for name in ("server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json")]
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as output:
            for file in files:
                if file.is_symlink():
                    raise ValueError("A symbolic link was found in the world. Back it up manually before continuing.")
                if file.is_file() and file.name != "session.lock":
                    output.write(file, file.relative_to(fabric).as_posix())
            output.writestr("BACKUP_INFO.json", json.dumps({"format": 1, "created_utc": stamp, "world": str(world.relative_to(fabric))}))
        temporary.chmod(0o600)
        temporary.replace(destination)
        return destination.name
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
        if fd is not None:
            os.close(fd)
            lockfile.unlink(missing_ok=True)


def roster(root, running):
    if not running:
        return {"players": [], "tick_ms": None, "available": False}
    try:
        import time
        data = json.loads((root / "fabric/community-status.json").read_text())
        if abs(time.time() * 1000 - data["updated"]) > 30_000:
            raise ValueError("Stale status")
        return {"players": data["players"], "tick_ms": data["tick_ms"], "available": True}
    except (OSError, ValueError, KeyError):
        return {"players": [], "tick_ms": None, "available": False}


def moderation_command(action, name):
    if not isinstance(name, str) or not re.fullmatch(r"\.?[A-Za-z0-9_]{1,32}", name):
        raise ValueError("Enter the exact player name, including the leading dot for Bedrock.")
    templates = {"kick": "kick {} Removed by staff", "ban": "ban {} Banned by staff", "unban": "pardon {}",
                 "mute": "community mute {} 10", "unmute": "community unmute {}", "allow": "whitelist add {}",
                 "remove_allow": "whitelist remove {}"}
    if action not in templates:
        raise ValueError("Choose a valid moderation action.")
    return templates[action].format(name)


def membership_command(action, name, tier=None, days=None):
    if not isinstance(name, str) or not re.fullmatch(r"\.?[A-Za-z0-9_]{1,32}", name):
        raise ValueError("Choose an online player's exact name, including the dot for Bedrock.")
    if action in ("revoke", "revokeadmin"):
        return "membership " + action + " " + name
    if action != "grant" or tier not in ("go", "plus", "pro", "ultra"):
        raise ValueError("Choose Go, Plus, Pro, or Ultra. Admin uses the private code.")
    if type(days) is not int or not 1 <= days <= 3660:
        raise ValueError("Membership duration must be 1–3660 whole days.")
    return "membership grant " + name + " " + tier + " " + str(days)
