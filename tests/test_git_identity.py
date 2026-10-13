"""Exercise managed contribution hooks through real disposable Git repositories.

The fixture substitutes its newly created root into a private copy of the checker;
production has no environment variable or CLI option that overrides the policy.
No real project hooks, settings, remote repositories or AI services are modified.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
OWNER_NAME = "InfinityHub Inc."
OWNER_EMAIL = "340781893+conorqiao-tech@users.noreply.github.com"
ANCHOR = "c1a34702a099e2066d241cf28949219f8224ae6f"


class GitIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="infinity-git-policy-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.repo = self.base / "source checkout"
        self.repo.mkdir()
        self.global_config = self.base / "empty-global-config"
        self.global_config.write_text("", encoding="utf-8")
        self.env = dict(os.environ)
        for key in tuple(self.env):
            if key.startswith("GIT_"):
                del self.env[key]
        self.env.update(GIT_CONFIG_GLOBAL=str(self.global_config), GIT_CONFIG_NOSYSTEM="1")
        self.git("init", "-b", "main")
        self.git("config", "user.name", OWNER_NAME)
        self.git("config", "user.email", OWNER_EMAIL)
        (self.repo / "initial.txt").write_text("owner initial tree\n", encoding="utf-8")
        self.git("add", "initial.txt")
        self.git("commit", "-m", "Owner initial history")
        self.anchor = self.head()
        self.checker = self.repo / "tools/check_git_identity.py"
        self.checker.parent.mkdir()
        original = (ROOT / "tools/check_git_identity.py").read_text(encoding="utf-8")
        self.assertEqual(original.count(f'REWRITTEN_ROOT = "{ANCHOR}"'), 1)
        self.checker.write_text(original.replace(f'REWRITTEN_ROOT = "{ANCHOR}"',
                                                f'REWRITTEN_ROOT = "{self.anchor}"'),
                                encoding="utf-8")
        shutil.copytree(ROOT / ".githooks", self.repo / ".githooks")
        (self.repo / ".claude").mkdir()
        shutil.copyfile(ROOT / ".claude/settings.json", self.repo / ".claude/settings.json")

    def git(self, *arguments, expected=0, cwd=None, env=None, input=None):
        result = subprocess.run(["git", *arguments], cwd=cwd or self.repo,
                                env=self.env | (env or {}), input=input,
                                capture_output=True, text=True)
        if expected is not None:
            self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def policy(self, *arguments, expected=0, cwd=None, input=None):
        result = subprocess.run([sys.executable, str(self.checker), *arguments],
                                cwd=cwd or self.repo, env=self.env, input=input,
                                capture_output=True, text=True)
        if expected is not None:
            self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def head(self):
        return self.git("rev-parse", "HEAD").stdout.strip()

    def install(self):
        self.policy("install")
        self.git("add", "tools", ".githooks", ".claude")
        self.git("commit", "-m", "Install owner-only policy")

    def change(self, text="new content\n"):
        (self.repo / "initial.txt").write_text(text, encoding="utf-8")
        self.git("add", "initial.txt")

    def remote(self):
        remote = self.base / "disposable remote.git"
        self.git("init", "--bare", str(remote))
        self.git("remote", "add", "origin", str(remote))
        return remote

    def test_tracked_settings_disable_supported_attribution_fields_only(self):
        settings = json.loads((self.repo / ".claude/settings.json").read_text())
        self.assertEqual(settings, {"attribution": {"commit": "", "pr": "", "sessionUrl": False}})

    def test_install_and_owner_commit_work_with_spaces_in_checkout_path(self):
        self.install()
        self.change()
        self.git("commit", "-m", "Owner authored improvement")
        self.assertIn("Verified 3 commits", self.policy("audit").stdout)
        for hook in ("commit-msg", "pre-merge-commit", "pre-push"):
            self.assertTrue(os.access(self.repo / ".git/hooks" / hook, os.X_OK))

    def test_commit_msg_refuses_claude_trailer_without_creating_commit(self):
        self.install()
        before = self.head()
        self.change()
        result = self.git("commit", "-m", "Change\n\nCo-authored-by: Claude <noreply@anthropic.com>", expected=1)
        self.assertIn("remove co-author", result.stderr)
        self.assertEqual(self.head(), before)

    def test_commit_msg_refuses_case_and_spacing_variant(self):
        self.install()
        self.change()
        self.git("commit", "-m", "Change\n\n cO-aUtHoReD-bY : Other <other@example.test>", expected=1)

    def test_commit_msg_refuses_session_trailer(self):
        self.install()
        self.change()
        self.git("commit", "-m", "Change\n\nClaude-Session: example-session", expected=1)

    def test_commit_msg_refuses_author_override(self):
        self.install()
        self.change()
        result = self.git("commit", "-m", "Change", expected=1,
                          env={"GIT_AUTHOR_NAME": "Other", "GIT_AUTHOR_EMAIL": "other@example.test"})
        self.assertIn("Unexpected Git author", result.stderr)

    def test_commit_msg_refuses_committer_override(self):
        self.install()
        self.change()
        result = self.git("commit", "-m", "Change", expected=1,
                          env={"GIT_COMMITTER_NAME": "Other", "GIT_COMMITTER_EMAIL": "other@example.test"})
        self.assertIn("Unexpected Git committer", result.stderr)

    def test_commit_msg_refuses_amend_with_coauthor(self):
        self.install()
        before = self.head()
        self.git("commit", "--amend", "-m", "Change\n\nCo-authored-by: Claude <noreply@anthropic.com>", expected=1)
        self.assertEqual(self.head(), before)

    def test_actual_push_accepts_owner_branch_and_annotated_release_tag(self):
        self.install()
        remote = self.remote()
        self.git("tag", "-a", "owner-test", "-m", "Owner release")
        self.git("push", "origin", "main", "refs/tags/owner-test")
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/main").stdout.strip(), self.head())

    def test_actual_push_inspects_bad_ancestor_behind_good_tip(self):
        self.install()
        remote = self.remote()
        accepted = self.head()
        self.git("push", "origin", "main")
        self.change("bypassed local commit\n")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-m", "Old attribution\n\nCo-authored-by: Claude <noreply@anthropic.com>")
        self.change("owner tip after bypass\n")
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-m", "Owner tip")
        result = self.git("push", "origin", "main", expected=1)
        self.assertIn("remove co-author", result.stderr)
        self.assertEqual(self.git("--git-dir", str(remote), "rev-parse", "refs/heads/main").stdout.strip(), accepted)

    def test_push_refuses_foreign_committer_ancestor(self):
        self.install()
        self.change()
        self.git("-c", "core.hooksPath=/dev/null", "commit", "-m", "Bad committer",
                 env={"GIT_COMMITTER_NAME": "Other", "GIT_COMMITTER_EMAIL": "other@example.test"})
        result = self.policy("push", input=f"refs/heads/main {self.head()} refs/heads/main {'0'*40}\n", expected=1)
        self.assertIn("another author or committer", result.stderr)

    def test_pre_merge_refuses_second_root_even_with_owner_identities(self):
        self.install()
        before = self.head()
        tree = self.git("rev-parse", "HEAD^{tree}").stdout.strip()
        unrelated = self.git("commit-tree", tree, "-m", "Retained pre-rewrite history").stdout.strip()
        self.git("branch", "old-history", unrelated)
        result = self.git("merge", "--no-ff", "--allow-unrelated-histories", "old-history",
                          "-m", "Would restore old history", expected=1)
        self.assertIn("Pre-rewrite or unrelated history", result.stderr)
        self.assertEqual(self.head(), before)

    def test_push_refuses_unrelated_parent_after_bypassed_merge(self):
        self.install()
        tree = self.git("rev-parse", "HEAD^{tree}").stdout.strip()
        unrelated = self.git("commit-tree", tree, "-m", "Owner identity on wrong ancestry").stdout.strip()
        merge = self.git("commit-tree", tree, "-p", self.head(), "-p", unrelated, "-m", "Bypassed merge").stdout.strip()
        result = self.policy("push", input=f"refs/heads/main {merge} refs/heads/main {'0'*40}\n", expected=1)
        self.assertIn("Pre-rewrite or unrelated history", result.stderr)

    def test_installer_preserves_unmanaged_hook_and_other_config(self):
        target = self.repo / ".git/hooks/pre-push"
        original = b"#!/bin/sh\nexit 7\n"
        target.write_bytes(original)
        target.chmod(0o755)
        config_before = (self.repo / ".git/config").read_bytes()
        self.policy("install", expected=1)
        self.assertEqual(target.read_bytes(), original)
        self.assertFalse((self.repo / ".git/hooks/commit-msg").exists())
        self.assertEqual((self.repo / ".git/config").read_bytes(), config_before)

    def test_installer_preserves_existing_custom_hook_directory(self):
        custom = self.base / "custom-hooks"
        custom.mkdir()
        self.git("config", "core.hooksPath", str(custom))
        self.policy("install", expected=1)
        self.assertEqual(list(custom.iterdir()), [])
        self.assertEqual(self.git("config", "core.hooksPath").stdout.strip(), str(custom))

    def test_installer_preserves_different_local_identity(self):
        self.git("config", "user.name", "Other")
        self.policy("install", expected=1)
        self.assertFalse((self.repo / ".git/hooks/commit-msg").exists())
        self.assertEqual(self.git("config", "user.name").stdout.strip(), "Other")

    def test_fresh_clone_identity_is_repo_local_and_global_file_is_unchanged(self):
        self.git("config", "--unset", "user.name")
        self.git("config", "--unset", "user.email")
        self.global_config.write_text('[user]\n  name = Other\n  email = other@example.test\n', encoding="utf-8")
        before = self.global_config.read_bytes()
        self.policy("install")
        self.assertEqual(self.git("config", "--local", "user.name").stdout.strip(), OWNER_NAME)
        self.assertEqual(self.global_config.read_bytes(), before)

    def test_dry_run_does_not_write_hooks_or_identity(self):
        self.git("config", "--unset", "user.name")
        self.git("config", "--unset", "user.email")
        before = (self.repo / ".git/config").read_bytes()
        self.policy("install", "--dry-run")
        self.assertFalse((self.repo / ".git/hooks/commit-msg").exists())
        self.assertEqual((self.repo / ".git/config").read_bytes(), before)

    def test_second_install_keeps_exact_managed_hooks(self):
        self.install()
        before = {p.name: p.read_bytes() for p in (self.repo / ".git/hooks").iterdir()}
        self.policy("install")
        self.assertEqual(before, {p.name: p.read_bytes() for p in (self.repo / ".git/hooks").iterdir()})

    def test_worktree_uses_shared_checker_and_hooks(self):
        self.install()
        worktree = self.base / "other worktree"
        self.git("worktree", "add", "-b", "worktree-check", str(worktree))
        (worktree / "initial.txt").write_text("owner worktree change\n", encoding="utf-8")
        self.git("add", "initial.txt", cwd=worktree)
        self.git("commit", "-m", "Owner worktree change", cwd=worktree)
        (worktree / "initial.txt").write_text("blocked worktree change\n", encoding="utf-8")
        self.git("add", "initial.txt", cwd=worktree)
        result = self.git("commit", "-m", "Change\n\nCo-authored-by: Claude <noreply@anthropic.com>", cwd=worktree, expected=1)
        self.assertIn("remove co-author", result.stderr)

    def test_malformed_push_is_refused_and_ref_deletion_is_allowed(self):
        for value in ("not a push line\n", "ref bogus ref " + "0"*40 + "\n"):
            with self.subTest(value=value):
                self.policy("push", expected=1, input=value)
        self.policy("push", input=f"(delete) {'0'*40} refs/heads/old {self.anchor}\n")


if __name__ == "__main__":
    unittest.main()
