# InfinityCraft contribution policy

This repository belongs to `conorqiao-tech`. All published commits must use
`InfinityHub Inc. <340781893+conorqiao-tech@users.noreply.github.com>` as both
author and committer. Do not append `Co-authored-by` or `Claude-Session` trailers,
attribute pull requests to Claude, or override the project's attribution settings.

Read [the contribution policy](server/CONTRIBUTORS.md) before repository work.
Use the rewritten history rooted at
`c1a34702a099e2066d241cf28949219f8224ae6f`. Older retained Claude worktrees contain
pre-rewrite history: preserve their files, but do not merge or push those branches.
Review and transfer intended file changes onto the current rewritten branch instead.

Run `python3 tools/check_git_identity.py audit` before committing or pushing.
On a fresh clone, install the managed Git hooks as described in the policy. Never
bypass, disable, or replace contribution checks to make an operation succeed.
Do not change repository access, history, worlds, credentials, or the live server
without the user's applicable authorization.
