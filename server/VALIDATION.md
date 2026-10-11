# Refreshed armor and gear — 2.12.0-explore.19

The Convergence and Aurora sets use new complete 128×64 armor atlases. Generated artwork was cropped into every native head, torso, arm and leg UV rectangle, with unused pixels transparent. The legacy visor overlay is omitted from the new Convergence layer because the refreshed visor is already painted into the new helmet. Original PNG bytes are preserved. Twenty-four distinct inventory designs produce 25 new icons, including both wand variants. The four powered mining tools now resolve custom Java models. Bedrock item-atlas entries point at matching sprites for mapped gear. The Creative asset generator preserves the new Aurora/wand model references when run again. No stat, helper, combat, menu or item-power behavior changes are included.

Bedrock wearables and native ranged/offhand items retain existing complete native assets and touch controls. This update does not replace ordinary Bedrock netherite/leather/elytra textures or enable unsupported custom glider mappings. Thus full custom worn armor is a Java resource-pack feature; Bedrock mapped weapons, mining tools and wands get the new sprite artwork.

## Fresh explore.19 checks

- 325 native Minecraft GameTests passed with the release dependencies.
- 101 Python launcher tests passed, including all Java model/equipment references and mapped Bedrock resource coverage.
- Geyser registered 21 custom items and 209 block overrides, answered local UDP and shut down cleanly.
- Two isolated full-stack cycles passed startup, mode worlds, command response, backup, restart and clean shutdown.
- All four new worn layers have fully opaque UV faces and transparent unused regions. All 25 inventory icons have clear margins and no clipped sprites or neighboring-row contamination.
- Clean-room repacking from the included raw artwork reproduces every one of the 56 Java/Bedrock production PNGs exactly. The built JAR and Bedrock pack match those hashes; Bedrock cache revision is 19. All original PNG hashes remain unchanged.
- Isolated Creative asset regeneration preserves the refreshed Aurora and wand references.

A real Java/iPad client must still verify resource-pack reload and graphical appearance. These checks do not prove every possible bug is gone.

## Retained behavior and earlier fixes

Validated October 5, 2026 with Java 21, Minecraft 1.21.11 and the checksum-pinned Fabric, Polymer, Floodgate and Geyser dependencies. Test worlds are isolated from the owner's playable save.

## Completed checks

The code review found two coverage gaps. Other clients still received unbounded health and absorption capacity for edited players and helpers, even though the owner's HUD was protected. The outgoing projection now applies to visible server players and registered helpers, preserves the shared input packets and saved stats, refreshes observed health when capacity changes, and never sends another entity's health into the viewer's own HUD. Ordinary mobs retain native packets. Two new native network regressions cover observer updates, capacity reset, huge registered helper health and exact saved-state preservation.

The dashboard previously refused every existing launcher lock, preventing the console launcher's safe crash recovery from running. Dashboard preflight now checks the existing lock without changing it or the settings. Real startup repeats PID, ownership, file identity and port checks before replacing only a verified stale lock. Active/unverifiable locks and busy game ports remain protected. Three new launcher regressions cover this complete path and rejected starts without settings changes. The stat editor now explains the bounded physical knockback without implying the saved attack-knockback value is reset.

The later disconnect was a server watchdog shutdown after a 60-second tick. Its native stack was in a zombie's movement collision scan. The preceding admin audit recorded 1e20 attack knockback, and the owner confirmed setting it. The new runtime protection bounds physical knockback impulses at 8 and velocity/collision movement at 32 blocks per tick without overwriting saved attributes. Non-finite vectors become stationary. Ordinary movement and jump velocity 15 remain unchanged. Native zombie movement and a real player-hit regression cover this path; a restarted live server still needs the owner's gameplay test.

The owner earlier reported an iPad app exit just after joining. Both authenticated joins reached Fabric; Geyser later recorded client timeouts while the server stayed healthy. The saved player had approximately 1e21 current/max health. This was a suspected client HUD overload, not an iOS crash-report diagnosis. The outgoing packet projection leaves authoritative stats intact, bounds the client's health capacity to 40 with proportional current health, bounds displayed absorption to 40 and hunger/saturation to 20, and refreshes the display on capacity-only edits. The owner confirmed the world/HUD stayed open after that patch, and the server recorded a sustained authenticated session before the separate movement freeze.

- The classic 64×64 robot skin carries pixel versions of the ChatGPT knot and Codex cloud on the front chest. Minecraft’s hosted image matches all uploaded RGBA pixels. Its texture-property signature was verified against Minecraft Services’ current public profile-property keys. The bundled property is public skin data; no MineSkin login, API key or runtime signing request is needed. The updated native avatar check verifies the shared signed texture, separate helper UUIDs, exact preservation through the player-info wire codec, opaque base faces and transparent unused overlays.

- **325 native Minecraft GameTests passed.** New tests exercise a native player hit with 1e20 knockback, actual zombie ticks, collision movement with finite double extremes, non-finite impulses, unchanged ordinary movement and jump velocity 15. Real outbound network tests cover approximately 1e21 health/absorption, native health codec round-trip, unchanged full saved player state, capacity-only edits and returning to ordinary health, effective modifiers, shared packets and flattened bundles. Direct health and absorption entry plans a capacity increase before applying the exact vital value, preserves all modifiers and has no preview mutation. Impossible modifier combinations and non-finite/out-of-storage values fail before changing either stat. Changed capacity invalidates both reviewed edits. Native save/reload preserves consumed extended absorption on players and helpers. Exact Java entry, plus buttons, Bedrock native confirmation, replay/session/OP4 guards and reset history remain tested.
- Expanded armor now affects native armor reduction beyond 30, up to full armor protection. Tests distinguish ordinary capped armor, an edited/reloaded instance, native damage through actual armor and armor-bypassing damage. Reset restores the original native behavior. High armor is not immunity to all damage types or weapon effects.
- New inventory/name checks verify actual starter gear and all eight Aurora/Ember armor pieces, including chestplates, in empty player slots; repeated grants avoid duplicates and full inventories preserve every existing item. Every registered custom item has a literal human label on server and native client wire stacks while anvil names survive. Native wearable assets round-trip without mutating the server equipment. Existing armor/crafting/menu checks remain.
- Helper avatars use native PLAYER spawn types, matching unlisted player-info UUIDs sent before spawning, bounded profile names, typed player metadata and current equipment. The player-info packet round-trips the real native codec; ordinary golems are unchanged and dismissal cleans profile viewers. A new autonomous combat check runs normal lifecycle ticks without calling control, attack or aerial methods manually: the helper acquires a hostile and lands a native weapon hit. Earlier actual spear charging, elytra glide, mace fall damage, close-range fallback, walls, ceilings, cooldowns, target side-effect guards and both approval gates passed.
- The ranks menu recognizes the actual permanent rank and separates it from Admin/OP roles. General AI facts and bounded helper data include the caller's actual badge and permanent rank; their display retains the data/privacy notice. Earlier mode, lobby, minigame, reward, trade, admin and answer-only Codex tests passed.
- **101 Python launcher tests passed**, including mappings/resource coverage, unchanged original PNG checksums, pack/module cache revisions, runtime, dashboard, backups, ports, providers and private state. All wearables are excluded from incompatible Bedrock custom item definitions; custom exported names are literal.
- **Geyser smoke passed:** 21 custom items (20 exported plus the bridge's built-in mapping) and 209 block overrides registered. Bedrock UDP ping answered and clean shutdown passed without ERROR entries. Two full-stack Fabric/Geyser cycles started isolated mode worlds, answered UDP, loaded the saved hub while command-responsive, backed up, restarted and stopped cleanly.

## Scope and review limits

This update uses dependency inspection and native network regression checks. No fresh Gemini or Claude consultation was performed. The earlier signed-texture verification remains valid; the helper skin and original texture assets are unchanged.

A real Java and iPad client must still verify the new appearance, touch controls and resource-pack reload. Native packets/tests establish server behavior, not graphical client rendering, an authenticated public join or public-server capacity. All Bedrock wearables now use complete native dyed-leather/netherite/elytra assets; pack-enabled Java uses the refreshed custom artwork. Original PNGs are unchanged. The Infinity chestplate uses the native elytra form on Bedrock. Direct inventory gear works alongside full shulker gear boxes; custom gear does not enter Geyser's unsupported native Creative search catalogue.

Manual stat edits require the admin's own confirmation. Health/absorption can raise capacity in the same review; numeric storage, native physics and client/HUD bounds remain. Damage consumes health and absorption normally. Food reserves can be consumed/reset by gameplay. Armor-bypassing damage and native enchantment effects still apply.

Helpers use the shared robot player skin but retain the existing golem-based server AI, physical collision and saved ownership; they are not authenticated Minecraft player accounts. They defend against nearby eligible hostile mobs in combat profiles. Stay, passive Debug/CLI/API profiles, offline/dead/spectator owners, unloaded helpers and range/dimension checks pause combat. Player targets retain exact owner and live Codex approvals, PvP/team rules and five-minute expiry. Server actions and code changes also retain both reviews. Answer-only Codex sessions do not share this chat's memory or approve/execute their own proposals. No per-tick AI service requests drive fighting, and no totem bypass is guaranteed.

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
