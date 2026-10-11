# Direct stats, inventory gear, ranks and AI player avatars — 2.12.0-explore.14

Validated October 4, 2026 with Java 21, Minecraft 1.21.11 and the checksum-pinned Fabric, Polymer, Floodgate and Geyser dependencies. Test worlds are isolated from the owner's playable save.

## Completed checks

- **315 native Minecraft GameTests passed.** Direct health and absorption entry plans a capacity increase before applying the exact vital value, preserves all modifiers and has no preview mutation. Impossible modifier combinations and non-finite/out-of-storage values fail before changing either stat. Changed capacity invalidates both reviewed edits. Native save/reload preserves consumed extended absorption on players and helpers. Exact Java entry, plus buttons, Bedrock native confirmation, replay/session/OP4 guards and reset history remain tested.
- Expanded armor now affects native armor reduction beyond 30, up to full armor protection. Tests distinguish ordinary capped armor, an edited/reloaded instance, native damage through actual armor and armor-bypassing damage. Reset restores the original native behavior. High armor is not immunity to all damage types or weapon effects.
- New inventory/name checks verify actual starter gear and all eight Aurora/Ember armor pieces, including chestplates, in empty player slots; repeated grants avoid duplicates and full inventories preserve every existing item. Every registered custom item has a literal human label on server and native client wire stacks while anvil names survive. Native wearable assets round-trip without mutating the server equipment. Existing armor/crafting/menu checks remain.
- Helper avatars use native PLAYER spawn types, matching unlisted player-info UUIDs sent before spawning, bounded profile names, typed player metadata and current equipment. The player-info packet round-trips the real native codec; ordinary golems are unchanged and dismissal cleans profile viewers. A new autonomous combat check runs normal lifecycle ticks without calling control, attack or aerial methods manually: the helper acquires a hostile and lands a native weapon hit. Earlier actual spear charging, elytra glide, mace fall damage, close-range fallback, walls, ceilings, cooldowns, target side-effect guards and both approval gates passed.
- The ranks menu recognizes the actual permanent rank and separates it from Admin/OP roles. General AI facts and bounded helper data include the caller's actual badge and permanent rank; their display retains the data/privacy notice. Earlier mode, lobby, minigame, reward, trade, admin and answer-only Codex tests passed.
- **98 Python launcher tests passed**, including mappings/resource coverage, unchanged original PNG checksums, pack/module cache revisions, runtime, dashboard, backups, ports, providers and private state. All wearables are excluded from incompatible Bedrock custom item definitions; custom exported names are literal.
- **Geyser smoke passed:** 21 custom items (20 exported plus the bridge's built-in mapping) and 209 block overrides registered. Bedrock UDP ping answered and clean shutdown passed without ERROR entries. Two full-stack Fabric/Geyser cycles started isolated mode worlds, answered UDP, loaded the saved hub while command-responsive, backed up, restarted and stopped cleanly.

## Scope and review limits

Gemini 3.8 Flash High/high effort remained quota-blocked from the prior request and was not resent before its reset. A fresh focused Claude Opus 5.5/max request timed out after 600 seconds with no answer or model usage evidence. Neither model was substituted or automatically retried. No external consultant review was established; mapped dependency inspection and the checks above provide the evidence.

A real Java and iPad client must still verify the new appearance, touch controls and resource-pack reload. Native packets/tests establish server behavior, not graphical client rendering, an authenticated public join or public-server capacity. All Bedrock wearables now use complete native dyed-leather/netherite/elytra assets; pack-enabled Java keeps original custom artwork. Original PNGs are unchanged. The Infinity chestplate uses the native elytra form on Bedrock. Direct inventory gear works alongside full shulker gear boxes; custom gear does not enter Geyser's unsupported native Creative search catalogue.

Manual stat edits require the admin's own confirmation. Health/absorption can raise capacity in the same review; numeric storage, native physics and client/HUD bounds remain. Damage consumes health and absorption normally. Food reserves can be consumed/reset by gameplay. Armor-bypassing damage and native enchantment effects still apply.

Helpers look like players but retain the existing golem-based server AI, physical collision and saved ownership; they are not authenticated Minecraft player accounts. They defend against nearby eligible hostile mobs in combat profiles. Stay, passive Debug/CLI/API profiles, offline/dead/spectator owners, unloaded helpers and range/dimension checks pause combat. Player targets retain exact owner and live Codex approvals, PvP/team rules and five-minute expiry. Server actions and code changes also retain both reviews. Answer-only Codex sessions do not share this chat's memory or approve/execute their own proposals. No per-tick AI service requests drive fighting, and no totem bypass is guaranteed.

The fixed Dynu hostname still requires forwarding and an awake reachable host. Vercel hosts the public guide, not the Minecraft world or private dashboard. USD checkout stays off until a Tebex store and tested billing integration exist. Planned monthly prices remain Go $50, Plus $75, Pro $100 and Ultra $200; earned ranks are free and permanent, and Admin/OP are never sold. Passing these checks does not establish that every possible bug is gone.

## Reproduce

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```

Packaging requires fresh passing native, launcher, Geyser and full-stack evidence. Explicit archive lists exclude private worlds, accounts, settings, credentials, panel tokens, EULA acceptance and downloaded runtimes. Live installation separately saves and verifies a stopped-world backup and byte-checks private settings before restarting.
