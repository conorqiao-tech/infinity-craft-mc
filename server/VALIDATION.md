# Helper release validation — 2.12.0-explore.7

Validated October 1, 2026 against the `2.12.0-explore.7` Fabric JAR and pinned Fabric, Geyser and Floodgate dependencies. Automated checks used isolated worlds. The owner's stopped live world and private settings were backed up before installation.

## Completed checks

- **223 native Minecraft GameTests passed with release dependencies.** They cover the existing gear, Creative picker, inventories, maps, rewards and trading, plus six helper profiles, shared combat focus and flanking, limits including unloaded helpers, format-1 roster migration, OP4 revocation, squad controls, menu ownership/stale clicks/inventory safety, named chat routing, bounded local data history, shared API limits and stale-reply suppression. The code-request tests cover both review gates, original helper identity, logout, expiry, cancellation, full-commit recording and repeated cancellation-history reloads. Player-target tests cover both approval gates, exact connected identities, selector rejection, invalid saved records, restart cancellation, expiry and clock rollback, PvP/team restrictions, lifecycle changes, owner-only ceasefire, pursuit beyond the immediate combat radius, actual navigation around a wall without damage through it, guard bounds and flanking membership. API tests use local mock transports rather than paid requests.
- **92 Python launcher tests passed.** Verified downloads, dashboard actions/settings, backups, runtime, ports, command handling and Pinggy status/install safety remain covered. Twelve focused Dynu tests check bounded credential/response reads, private file permissions, exact owned-host updates, preserved DNS fields, refused redirects/proxies, public-IP parsing and explicit packaging exclusions.
- **The final Geyser localhost smoke check passed.** The bridge registered 24 custom items and 209 custom block overrides, answered a Bedrock UDP ping and shut down cleanly. This does not authenticate a Bedrock player or check iPad rendering.
- **Two full-stack cycles passed.** Fabric and Geyser started with the final build in an isolated server, answered a Bedrock ping, saved a stopped-world backup, restarted and shut down cleanly. No player logged in during these checks.
- **The live Mac installation started successfully after backup.** The new JAR is installed, the private panel reports `running`, and the launcher log confirms `convergence 2.12.0-explore.7` with no `ERROR` entry. This establishes startup, not a real player's gameplay experience.

`tools/package_crossplay.py` requires fresh native, Python, Geyser and full-stack evidence. It packages an explicit file list and excludes worlds, backups, accounts, owner settings, keys, panel tokens, EULA acceptance and downloaded runtimes.

## Remaining play tests and operational limits

The helper menu and other pickers use vanilla chest screens. A real Java client and iPad Bedrock client still need to test tapping controls, navigating profiles, dismissing a helper and managing a six-golem squad in narrow spaces. Combat uses deterministic server-controlled golems with ordinary stats and shared tactics for hostile mobs or an explicitly approved player. It is not a player-bot or learned PvP implementation. Profile, movement, status and ceasefire controls refresh the existing menu; page/identity changes get a fresh screen handler. Native checks establish inventory behavior, not iPad touch/rendering. Public-server load testing remains outstanding.

API-mode helpers sample bounded game facts locally. Only an explicit API ask sends the question and up to three recent snapshots externally; there are no automatic API requests or model command tools. Device testing and a real configured-provider response remain separate from the mocked integration checks. The private key remains outside public archives.

Both action approvals require an OP4 owner and a live Codex session. Minecraft cannot prove that a local-console operator is Codex. The code-request queue stores approved text and a reviewer-supplied Git commit; it does not itself edit/build/install source or verify Git and tests. Codex must inspect the exact request and resulting diff, run checks and use the stopped-world backup/upgrade procedure. Nothing progresses automatically while this chat is closed.

The Creative gear picker, all six minigames and two adventure maps, touch input, crafting, wardrobe rendering, backpacks, player trading and authenticated remote joining still need real device play tests. An earlier authenticated Bedrock login exposed a Creative catalogue error; custom Infinity gear remains supplied through the server picker instead. Geyser may hide custom-output recipes, so manual crafting needs a device test too.

Supporter checkout, renewal, expiry, cancellation, refund and chargeback remain unavailable until a Tebex store and tested billing integration exist. The planned monthly USD prices are Go $50, Plus $75, Pro $100 and Ultra $200. Earned ranks stay free and permanent; Admin and OP are never sold. Free Pinggy addresses are temporary. The optional Dynu companion maintains an already reserved hostname, requires its own account and router forwarding, and does not provide a tunnel. Its provider updates were tested with mocks; account setup, live DNS updating and external authenticated joining remain unverified. A local ping is not proof that a friend can authenticate from another network.

## Reproduce

Use Java 21, Python 3.9+ and the checksum-pinned dependencies:

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```
