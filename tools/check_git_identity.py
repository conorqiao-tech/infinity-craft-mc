#!/usr/bin/env python3
"""Check the owner's identity and rewritten history without contacting GitHub.

Project settings suppress automatic Claude attribution; installed Git hooks reject
coauthors, unexpected author/committer identities and pre-rewrite ancestry. Hooks
are local safeguards, not remote branch protection or an unbypassable permission.
"""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


OWNER_NAME = "InfinityHub Inc."
OWNER_EMAIL = "340781893+conorqiao-tech@users.noreply.github.com"
REWRITTEN_ROOT = "c1a34702a099e2066d241cf28949219f8224ae6f"
MANAGED_HOOKS = ("commit-msg", "pre-merge-commit", "pre-push")
FORBIDDEN_TRAILER = re.compile(r"(?im)^\s*(?:co-authored-by|claude-session)\s*:")
OID = re.compile(r"^[0-9a-f]{40}(?:[0-9a-f]{24})?$")


class PolicyError(RuntimeError):
    """A check refused an operation; no remote action has occurred."""


def git(*arguments: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(["git", *arguments], capture_output=True, text=True,
                            encoding="utf-8", errors="strict")
    if check and result.returncode:
        raise PolicyError(f"Git could not verify {arguments[0]}; operation refused.")
    return result


def has_head() -> bool:
    return git("rev-parse", "--verify", "HEAD", check=False).returncode == 0


def check_message(message: str) -> None:
    if FORBIDDEN_TRAILER.search(message):
        raise PolicyError("This repository credits only InfinityHub Inc.; remove co-author and Claude session trailers.")


def check_current_identity() -> None:
    for kind in ("AUTHOR", "COMMITTER"):
        identity = git("var", f"GIT_{kind}_IDENT").stdout.strip()
        matched = re.fullmatch(r"(.*?) <([^<>]+)> \d+ [+-]\d{4}", identity)
        if not matched or matched.groups() != (OWNER_NAME, OWNER_EMAIL):
            raise PolicyError(f"Unexpected Git {kind.lower()}; use the repository owner's identity.")


def check_history(revision: str, seen: set[str] | None = None) -> int:
    """Inspect full ancestry, including merged parents and peeled release tags."""
    commit = git("rev-parse", "--verify", f"{revision}^{{commit}}").stdout.strip()
    roots = set(git("rev-list", "--max-parents=0", commit).stdout.splitlines())
    if roots != {REWRITTEN_ROOT}:
        raise PolicyError("Pre-rewrite or unrelated history is not publishable. Use the current rewritten branch; a shallow clone needs its complete history.")
    fields = git("log", "-z", "--format=%H%x00%an%x00%ae%x00%cn%x00%ce%x00%B", commit).stdout.split("\0")
    if fields[-1:] != [""] or (len(fields) - 1) % 6:
        raise PolicyError("Cannot parse commit ancestry; operation refused.")
    visited = seen if seen is not None else set()
    count = 0
    for offset in range(0, len(fields) - 1, 6):
        sha, author, email, committer, committer_email, message = fields[offset:offset + 6]
        if sha in visited:
            continue
        if (author, email) != (OWNER_NAME, OWNER_EMAIL) or (committer, committer_email) != (OWNER_NAME, OWNER_EMAIL):
            raise PolicyError(f"Commit {sha[:12]} has another author or committer; publication refused.")
        check_message(message)
        visited.add(sha)
        count += 1
    return count


def check_merge() -> int:
    checked = check_history("HEAD") if has_head() else 0
    merge_file = Path(git("rev-parse", "--git-path", "MERGE_HEAD").stdout.strip())
    if merge_file.exists():
        for parent in merge_file.read_text(encoding="ascii").splitlines():
            if not OID.fullmatch(parent):
                raise PolicyError("Cannot verify the pending merge parent; commit refused.")
            checked += check_history(parent)
    return checked


def check_commit_message(path: Path) -> None:
    if path.stat().st_size > 1_048_576:
        raise PolicyError("Commit message exceeds the verification limit; commit refused.")
    check_message(path.read_text(encoding="utf-8"))
    check_current_identity()
    check_merge()


def check_push(lines) -> int:
    seen: set[str] = set()
    for line in lines:
        parts = line.split()
        if len(parts) != 4 or not OID.fullmatch(parts[1]) or not OID.fullmatch(parts[3]):
            raise PolicyError("Cannot verify proposed push refs; push refused.")
        _, local_sha, _, _ = parts
        if set(local_sha) == {"0"}:
            continue
        check_history(local_sha, seen)
    return len(seen)


def repository_paths() -> tuple[Path, Path]:
    if git("rev-parse", "--is-bare-repository").stdout.strip() != "false":
        raise PolicyError("Install hooks from a normal checkout, not a bare repository.")
    common = Path(git("rev-parse", "--path-format=absolute", "--git-common-dir").stdout.strip()).resolve()
    hooks = common / "hooks"
    # Do not silently change a shared/custom hook setup or worktree override.
    configured = git("config", "--get", "core.hooksPath", check=False)
    if configured.returncode == 0:
        raise PolicyError("An existing core.hooksPath is configured. Preserve it and arrange the owner check through that hook setup explicitly.")
    return common, hooks


def install_hooks(dry_run: bool = False) -> None:
    _, destination = repository_paths()
    source = Path(__file__).resolve().parents[1] / ".githooks"
    content: dict[str, bytes] = {}
    for name in MANAGED_HOOKS:
        data = (source / name).read_bytes()
        target = destination / name
        if target.is_symlink() or (target.exists() and target.read_bytes() != data):
            raise PolicyError(f"Existing {name} hook is unmanaged; it was preserved. Review and integrate the owner policy manually.")
        content[name] = data
    # Local identity is set only after every existing hook has been checked.
    for key, expected in (("user.name", OWNER_NAME), ("user.email", OWNER_EMAIL)):
        existing = git("config", "--local", "--get", key, check=False)
        if existing.returncode == 0 and existing.stdout.strip() != expected:
            raise PolicyError(f"Existing local {key} differs; it was preserved. Set the approved owner identity explicitly first.")
        effective = git("config", "--show-origin", "--get", key, check=False)
        if effective.returncode == 0 and "config.worktree\t" in effective.stdout and effective.stdout.split("\t", 1)[1].strip() != expected:
            raise PolicyError(f"A worktree {key} override differs; it was preserved.")
    check_merge()
    if dry_run:
        print("Would install three managed Git hooks and set this repository's owner identity. Existing custom hooks and global settings are preserved.")
        return
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in content.items():
        target = destination / name
        if target.exists():
            target.chmod(target.stat().st_mode | 0o111)
            continue
        # Exclusive publication refuses an existing hook that appeared since validation.
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o755)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
    git("config", "--local", "user.name", OWNER_NAME)
    git("config", "--local", "user.email", OWNER_EMAIL)
    check_current_identity()
    print("Installed managed owner-identity hooks; no global config or remote settings changed.")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    commit_parser = subcommands.add_parser("commit-message")
    commit_parser.add_argument("message", type=Path)
    subcommands.add_parser("merge")
    subcommands.add_parser("push")
    audit_parser = subcommands.add_parser("audit")
    audit_parser.add_argument("revisions", nargs="*", default=["HEAD"])
    install_parser = subcommands.add_parser("install")
    install_parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.command == "commit-message":
            check_commit_message(args.message)
        elif args.command == "merge":
            check_current_identity()
            check_merge()
        elif args.command == "push":
            check_push(sys.stdin)
        elif args.command == "audit":
            seen: set[str] = set()
            for revision in args.revisions:
                check_history(revision, seen)
            print(f"Verified {len(seen)} commits: owner identity, no coauthors, rewritten ancestry.")
        else:
            install_hooks(args.dry_run)
    except (PolicyError, OSError, UnicodeError, ValueError) as error:
        print(f"Owner contribution policy: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
