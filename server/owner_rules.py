"""Private, exact-account owner join rules. No shared default bans."""
import json
import re

POLICY = ".owner-join-rules.json"
LOGIN = re.compile(r"^\[\d{2}:\d{2}:\d{2}\] \[Server thread/INFO\](?:: | \([^\)\r\n]{1,64}\) )"
                   r"(\.?[A-Za-z0-9_]{1,16})\[(?:/[^\r\n]{1,100}?|local)\] "
                   r"logged in with entity id \d+ at \(")


class JoinRules:
    def __init__(self, names=()):
        self.names = frozenset(name.casefold() for name in names)
        self.requested = set()

    def command_for(self, line):
        match = LOGIN.match(line)
        if match is None:
            return None
        account = match[1]
        canonical = account.removeprefix(".").casefold()
        if canonical not in self.names or account.casefold() in self.requested:
            return None
        return "ban " + account + " Blocked by the server owner"

    def mark_sent(self, command):
        self.requested.add(command.split()[1].casefold())


def load(root):
    path = root / POLICY
    if not path.exists():
        return JoinRules()
    if path.is_symlink():
        raise ValueError("Owner join rules cannot be a symbolic link")
    data = json.loads(path.read_text())
    if not isinstance(data, dict) or data.get("format") != 1 or not isinstance(data.get("blocked_names"), list):
        raise ValueError("Invalid private owner join rules")
    names = data["blocked_names"]
    if any(not isinstance(n, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,16}", n) for n in names):
        raise ValueError("Owner join rules require exact Minecraft account names")
    return JoinRules(names)
