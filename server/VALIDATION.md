# Menu, Codex and lobby release validation — 2.12.0-explore.8

Validated October 2, 2026 against the `2.12.0-explore.8` Fabric JAR and pinned Fabric, Geyser and Floodgate dependencies. Automated game checks used isolated worlds. The owner's live world, operator records, private configuration and prior implementation were backed up before installation.

## Completed checks

- **262 native Minecraft GameTests passed with release dependencies.** Coverage includes all prior gear, modes, maps, rewards, trading and helper behavior, plus the Infinity Menu's ownership, stale clicks, real held-item restoration, locked controls and inventory preservation. Admin tests verify actual OP4 promotion, saved-role migration, prior permission restoration, independent OP preservation, storage failure handling, unlock overrides and real ability cooldown bypass. Tactics tests exercise physical golem motion, native melee damage and cadence, ceilings/walls, pursuit prediction, guard bounds, both player-target approval gates and immediate cancellation. Lobby checks cover themed accents, vanilla banner support, clear paths, two-sided glowing navigation and preservation of storage, edited signs and modified ground. Codex tests cover routing, limited context, provider labels, actual OP4 rechecks, stale/cancelled replies, shared usage accounting, bounded protocol data and refusal of tool/approval activity. Mock transports and fake processes do not contact a paid provider.
- **96 Python launcher tests passed.** Downloads, dashboard actions/settings, backups, runtime, ports, command handling, Pinggy and Dynu remain covered. New Codex configuration tests verify absolute executable paths, OP4-only access, provider switching without losing the saved API key, and recovery when an executable disappears. Dashboard JavaScript syntax also passed.
- **The final Geyser localhost smoke check passed.** The bridge registered 24 custom items and 209 custom block overrides, answered a Bedrock UDP ping and shut down cleanly. This does not authenticate a Bedrock player or verify iPad rendering.
- **Two full-stack cycles passed.** Fabric and Geyser started with the final build in an isolated server, answered a Bedrock ping, saved a stopped-world backup, restarted and shut down cleanly. No player logged in during these checks.
- **Actual Codex responses succeeded.** The production transport answered a small supplied-facts question through the host's existing ChatGPT login using native Codex CLI 0.155.1 and model `gpt-6-luna`. After installation, the running Minecraft server also returned a correctly labeled Codex answer to an explicit console question about opening AI Helpers. This exercised the real configured service and its prompt, rather than a mock. No world-changing action was proposed or approved by those answers. Account usage and availability still apply.
- **The live Mac installation started successfully after backup.** The panel reports `running`; its fresh launcher log confirms `convergence 2.12.0-explore.8` without an `ERROR` entry. Java TCP 25565 listens and Bedrock UDP 19132 answers a local ping. The existing helper roster, launcher settings, Admin code settings and operator records were preserved. Codex is enabled privately on this host; downloads include neither host credentials nor enabled private configuration.

The review caught and corrected missing sea-lantern-supported banners, ordinary built-in help being blocked when Codex was enabled, and flanking points outside the guard leash. Test fixtures were corrected to use actual melee reach, the existing hostile sensing range, owner-specific combat orders, and explicit test chunk tickets for real navigation; assertions for damage, movement and approval gates remain.

`tools/package_crossplay.py` requires fresh native, Python, Geyser and full-stack evidence. It packages an explicit file list and excludes worlds, backups, accounts, owner settings, keys, panel tokens, EULA acceptance and downloaded runtimes.

## Remaining play tests and operational limits

A real Java client and iPad Bedrock client still need to check this update's menu taps, hotbar restoration, gear appearance, lobby visuals and squad movement in crowded spaces. Native tests establish server inventory, block-placement, motion and damage behavior, not client touch/rendering. Public-server load testing remains outstanding. Custom Infinity gear uses the server catalogue because the Bedrock Creative catalogue previously rejected custom item movement; crafting and custom-output recipes still need device tests.

Helpers are server-controlled golems with deterministic pursuit and combat. They do not equip a spear, mace or elytra, perform an enchantment exploit, or guarantee a totem bypass. Codex answers explicit questions using supplied facts in separate answer-only sessions. The connection does not share this live chat's memory, automatically control combat, or approve its own suggestions. Native CLI versions other than audited 0.155.1 require a compatibility review before this transport will run.

AI actions, code changes and exact player targets retain the owner's approval and a separate live Codex review, including for Admin. Minecraft cannot prove that a local-console operator is Codex. The code-request queue records approved text and a reviewer-supplied Git commit; it does not itself edit/build/install source or verify Git and tests. No live review progresses automatically while this chat is closed. Admin provides OP4 and Infinity gameplay overrides, not unlimited host resources, helper slots or external account usage.

The fixed Dynu name was configured earlier, public DNS updating was checked, and the owner reported a successful external iPad/Bedrock join before this release. This release's connection checks were local; an authenticated remote Java join remains unverified. Dynu maintains DNS and requires router forwarding; it is not a tunnel or a promise of a hostname forever. Free Pinggy addresses remain temporary.

Supporter checkout, renewal, expiry, cancellation, refund and chargeback remain unavailable until a Tebex store and tested billing integration exist. Planned monthly USD prices remain Go $50, Plus $75, Pro $100 and Ultra $200. Earned ranks stay free and permanent; Admin and OP are never sold.

## Reproduce

Use Java 21, Python 3.9+ and the checksum-pinned dependencies:

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```
