"""Copy a validated local server release into this public static site."""

import hashlib
from pathlib import Path
import shutil
import sys
import zipfile


VERSION = "2.12.0-explore.1"
ROOT = Path(__file__).resolve().parent
MAP = {
    "server.zip": f"dist/lobbies/Infinity_Armor_Lobbies_Server_v{VERSION}.zip",
    "source.zip": f"dist/lobbies/Infinity_Armor_Lobbies_Source_v{VERSION}.zip",
    "setup.md": "server/README.md",
    "hosting.md": "server/HOSTING.md",
    "lobbies.md": "server/LOBBIES.md",
    "memberships.md": "server/MEMBERSHIPS.md",
    "modes.md": "server/MODES.md",
    "rewards.md": "server/REWARDS.md",
    "trading.md": "server/TRADING.md",
    "exploration.md": "server/EXPLORATION.md",
    "player_trading.md": "server/PLAYER_TRADING.md",
    "pinggy_joining.md": "server/PINGGY_JOINING.md",
    "third_party.md": "server/THIRD_PARTY.md",
    "validation.md": "server/VALIDATION.md",
    "texture_credits.md": "server/TEXTURE_CREDITS.md",
    "start_here.txt": "server/START_HERE.txt",
}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def sync(source):
    source = Path(source).resolve()
    release = source / "dist/lobbies"
    for line in (release / "SHA256SUMS.txt").read_text().splitlines():
        expected, name = line.split("  ", 1)
        if digest(release / name) != expected:
            raise ValueError(f"Release checksum failed: {name}")
    for artifact in ("server.zip", "source.zip"):
        with zipfile.ZipFile(source / MAP[artifact]) as archive:
            if archive.testzip() is not None:
                raise ValueError(f"ZIP integrity failed: {artifact}")
            if not any(f"Infinity-Armor-{VERSION}.jar" in name for name in archive.namelist()):
                raise ValueError(f"Release version missing: {artifact}")
    target = ROOT / "dist/downloads"
    target.mkdir(parents=True, exist_ok=True)
    for name, relative in MAP.items():
        shutil.copy2(source / relative, target / name)
    (target / "SHA256SUMS.txt").write_text("\n".join(
        f"{digest(target / name)}  {name}" for name in sorted(MAP)
    ) + "\n")
    print(f"Published {VERSION} release files to the site source.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 sync_release.py /path/to/infinity-armor-crossplay")
    sync(sys.argv[1])
