# Owner-only Git attribution

The owner is **conorqiao-tech**, displayed as **InfinityHub Inc.**. Published
commits use `340781893+conorqiao-tech@users.noreply.github.com` as both author and
committer. Claude, qiaokan and other accounts must not receive co-author trailers.
Authentication to GitHub and the email embedded in a commit are separate things.

The tracked `.claude/settings.json` disables Claude's automatic commit/PR
attribution and session-link attribution for fresh clones. `CLAUDE.md` records
the same policy for assistants. The existing private `.claude/settings.local.json`
continues to hold local permissions and is not replaced or published.

For a fresh full-history clone, run these from the repository:

```
python3 tools/check_git_identity.py audit
python3 tools/check_git_identity.py install --dry-run
python3 tools/check_git_identity.py install
```

Installation checks existing hooks first. It refuses to replace an unmanaged
hook, a configured custom hook directory, a different local identity, or a
conflicting worktree identity. It does not change global Git configuration or
GitHub repository settings. Existing hook files, local permissions, worlds and
Claude worktree files are preserved. If installation refuses an existing hook,
review that hook with the owner and integrate this check into the existing setup;
do not remove it or use `--no-verify` to work around the refusal.

The source-controlled hooks check both author and committer, reject co-author and
Claude session trailers, and require every published commit to descend only from
the rewritten root `c1a34702a099e2066d241cf28949219f8224ae6f`. The push check examines
the complete ancestry of each proposed branch or release tag, including merged
parents, even if the remote already contains a bad commit. Deleting a ref does
not publish commits. A shallow clone cannot prove the root; obtain its full
history before using these checks.

Old retained Claude worktrees still contain pre-rewrite branches. Keep their
files intact. Review and transfer intended file changes to the current rewritten
branch; never merge their old history back. The hooks resolve the checker from
the shared checkout so worktrees use the same policy.

These are local safeguards. Git hooks do not run for commits made through the
GitHub website or an API, and a user can deliberately bypass local hooks. This
update does not add remote branch rules or claim to prevent every possible
bypass. Existing GitHub contributor views can remain cached after the public
history changes; changing a view's cache is separate from fixing commit history.
