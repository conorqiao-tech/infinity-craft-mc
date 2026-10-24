# Explore.50 candidate — Infinity Chestplate flight and helper batches

The existing Infinity Chestplate now has native gliding and an appearance that
combines its accepted Infinity armor shell with folding wings. Its item identity,
armor protection and full-set powers remain. Convergence Wings and ordinary
elytra retain their existing behavior. The same candidate prepares quantity-based
helper spawning up to fifty total per owner and server, with unloaded helpers
counted.

The live server is the separately approved explore.49. The owner confirmed helper
following and combos there, and identified Infinity Chestplate as the item that
still could not glide. This source candidate passed 673 native and 419
launcher/resource tests and is not installed
by preparation or merge. Live installation requires separate owner approval and
a verified stopped-world backup. Actual iPad flight/rendering remains a device
check. See the winged chestplate, helper and validation guides.

# Historical explore.49 candidate — combined chestplate and wings

This candidate adds a conditional Bedrock appearance to the existing Convergence
wing item: the accepted same-set torso and arms plus original folding wings in
one chest slot. The native elytra item, protection and flight behavior remain.
Ordinary elytra retains native rendering when the owned property is absent or
false. The iPad helper detail keeps its combo control visible with actionable
unavailable-state feedback. Native collision cannot assign a helper a target
outside its controller; ordinary golems and physical pushing retain their native
behavior. See [combined wings](../server/WINGED_CHESTPLATE.md)
and [validation](../server/VALIDATION.md).

The installed server remains explore.48. Its four-piece armor was confirmed
stable. The candidate has not been installed and its combined iPad
rendering/gliding remains unverified.
Installations require separate owner approval. Building does not change the
live world, helper roster, player equipment or approval queues.

# Historical explore.46 source candidate — helper combos

This candidate improves Ultimate Finals' native spear/glide/mace flow: ready
teammates receive aerial turns, moving targets get bounded checked landing
updates, and close targets receive cadenced mace follow-ups. Java and Bedrock
helper details show the same read-only phase and prerequisite feedback. See
`server/HELPER_COMBOS.md` and `server/VALIDATION.md` for use and evidence.

The live installation remains explore.45 plus its owner-accepted r1 armor repair.
No live world, helper roster or player data changes when building this source.
Installations require separate owner approval. Older candidate notes follow as
historical records.

> **Historical resource-only motion candidate (explore.44 r1):** The owner confirmed the
> missing pieces and held totem were fixed, but walking/crouching flicker remains.
> A separately tested lowercase limb-binding candidate is described in
> [the motion repair guide](../server/ARMOR_MOTION_REPAIR.md). It is not installed and does not change
> the mod, world or terrain marker. Actual iPad acceptance is still required.

# Explore.44 source candidate

This candidate carries the focused armor render-view repair and four original
Elemental relic redraws for Windstep, Firebrand, Tidecaller and Terra Gauntlet.
All four armor slots retain their identities, geometry, equipment bindings and
original artwork. The view predicates explicitly permit the inventory character
and third-person body while retaining first-person camera protection and the
matching worn-item guard. New relic sprites match between Java and Bedrock;
their names, recipes, powers and cooldowns are unchanged. Ordinary Minecraft
textures and the existing separate Convergence Wings/native flight remain.
The existing winged item already supplies armor and flight in one chest slot.
The reported floating held totem now uses normal Bedrock sprite placement with
its original custom artwork and activation routing; both bad-pose attachables
are excluded from preparation. Actual iPad grip still needs a device check.

The owner installed explore.43 and supplied an iPad image showing sleeves only;
the previous native packet/skin checks did not establish client rendering.
Explore.44 is not installed by building or merging this source. See
`server/ARMOR_VISIBILITY_REPAIR.md`, `server/RELIC_ART.md` and
`server/VALIDATION.md` for evidence, delivery stages and the required device
acceptance after a separately approved installation.

## Historical explore.43 source candidate

This candidate combines the explore.42 armor repair with three native Odyssey guardian variants, three original craftable tools, six advancement rewards, earnable Infini and reviewed cosmetic/staff applications. Real-money checkout is disabled; the owner cancelled the payment-provider integration. The live server remains explore.41. See `server/ASTRAL_WILDS.md`, `server/SUBSCRIPTIONS.md` and `server/ARMOR_REPAIR.md`.

All 577 native game tests and 307 Python checks pass; current crossplay, inventory persistence and lifecycle checks pass. Tested packages are prepared locally; this candidate is not installed. These source notes do not authorize live installation or claim iPad rendering success.

# Armor visibility, fresh pixel artwork and admin tools — explore.42 candidate

The current source candidate fixes player-skin layers covering all five custom armor sets, adds original matching worn textures and twenty inventory icons, and verifies native Geyser armor packets in all four equipment slots. Admin/OP4 Studio tools work in Survival; additional command shortcuts preserve native permissions. See `server/ARMOR_REPAIR.md` and `server/CREATIVE_STUDIO.md`. 528 native and 303 Python tests pass. This candidate requires a separately approved live installation; actual iPad rendering is not established by a source build.

# Elemental Forge and armor seam repair — explore.41 candidate

Adds one four-piece Elemental suit with saved Fire/Water/Earth/Air attunement, a separate ultimate Infinity suit, four usable relics and five original full-cube blocks. `ElementalArmor.java`, `ElementalRelics.java` and `ElementalForgeMenu.java` supply real server behavior. Seventeen ordinary crafting recipes and discovery advancements connect the content to Survival. All 63 catalogue identities must pass actual pinned-protocol capture, native persistence and rejoin checks before packaging. See `server/ELEMENTAL_FORGE.md` for use and `server/VALIDATION.md` for completed evidence and remaining device checks.

The Bedrock geometry generator separates previously coplanar boot/leggings, waist, neck and shoulder surfaces. No preexisting PNG changes are required. `tests/test_armor_seams.py` reproduces the old conflict and checks all five suits. New independent art comes from `artwork/elemental41/`; rendered previews are software inspections, not proof from a Minecraft client. Normal leather, netherite and elytra remain normal.

# Infinity Armor Exploration Server source — 2.13.0-explore.39 candidate

The explore.39 candidate adds 46 usable Infinity entries to the normal Bedrock Creative inventory and fixes Java Creative registration through Polymer. Convergence and Aurora names are consistent for search; literal registered item names now survive Minecraft 26.3's final component initializer. Bedrock armor/backpacks use thirteen independently authored geometry files with original textures and moving player-joint bindings. Java custom bow/crossbow, shield and spear models include use-state and held/inventory poses. Ordinary leather and netherite textures remain excluded from the pack. See `server/CREATIVE_INVENTORY.md` and `server/ORIGINAL_ARTWORK.md` for client requirements and the four remaining native Bedrock appearance exceptions.

Explore.38 adds a fourteenth independently authored Bedrock assembly for the wing chest shell. A narrow guard keeps queued owned catalogue requests outside Creative from reaching Geyser's unsafe Creative DROP path; ordinary requests retain native handling and metadata. New native disk/reconnect checks bind all 46 catalogue identities, names and component variants to actual player saves. The project now tracks attribution settings and owner-only commit/push checks for fresh clones; see `server/CONTRIBUTORS.md`. `server/HOLIDAY_UPDATES.md` records the owner's scheduled update-plan source and separates proposed themes from installed gameplay.

Explore.39 adds original held bow, crossbow, shield and totem models with hand/use poses. Its pinned adapter corrects only the four owned custom definitions, retaining ordinary weapon and arrow mappings. Sneaking Ward also starts native shield blocking. Actual iPad frame queries and positioning remain device checks.

The explore.38 installation was separately approved; these explore.39 source changes are not included in that installation. Candidate source and local packages do not install or restart it. The extension is tied to the exact pinned Geyser jar and refuses unsupported artifacts or stale catalogue readiness. The launcher regenerates the public catalogue from Fabric before starting Bedrock; catalogue aliases are converted to vanilla wire bases with trusted Polymer identity before being cached or sent. All ordinary item mappings, player saves, mode inventories and both AI approvals are preserved. Authentic Java/iPad rendering and touch pickup still need a device check after approved installation.

## Retained session and travel protections

The retained source binds private stat prompts, native forms, menu cleanup, player trades, helper combat orders, API observation history and pending AI requests to their exact participants or native connection. Old same-UUID callers cannot erase fresh state or confirm a replacement player’s trade. Genuine departure still clears its own state; both AI approvals and durable trade journals remain.

It also prepares homes, warps, accepted player visits and command spawns across native game ticks. Exact saved addresses and Survival spawn orientation remain fixed; Creative/Hardcore default search keeps its origin and bounded nearest-ring order. Exact player/host sessions bind requests and consent, so replacement, disconnect or world/mode changes cannot revive old jobs. Ordinary countdown/combat/movement/cooldown rules remain; OP exact-location travel keeps its existing bypass and immediate FULL destination behavior. Missing terrain still needs technical preparation. Native death respawn retains its separate synchronous contract. Explore.36 introduced these travel protections; it is the currently installed version.

The candidate also retains explore.34's prepared same-mode Hub arrivals, bounded fall-rescue retries and eliminated-Hardcore exits; explore.33's prepared cross-mode/first-visit destinations and completed-only Odyssey discovery; and explore.32's connection-local suppression of unchanged capacity refreshes. Explore.31's lazy packet copies and native player-state cleanup remain. The original Infinity artwork, separate solid Convergence chestplate, existing wings, inventory collections and both AI approvals are preserved. The preserved explore.37 baseline passed 480 unique native tests, Python launcher/resource checks, 540 actual Geyser Creative requests, supplementary pinned Via/native packet checks and two disposable full-stack restart cycles. See `server/VALIDATION.md` for exact bindings, counts and remaining authentic Java/iPad checks; these results do not establish live TPS or client visuals.

Explore.30 contains failed-save recovery for automatic helper-order revocations, one exact RC-plane collision query per safe candidate, immediate deduplicated backpack serialization with codec/overstack validation, direct helper identity checks, and single-scan picker/single-copy navigator ownership checks. All server-changing AI actions retain both approvals and required synchronous consumption saves. These retained changes use the existing Minecraft 26.3 / Java 25 stack.

Explore.29 adds safe mode-transfer rejection, a non-consuming native death-respawn preflight, correct STATUS fault closure and lazy per-control helper focus lookup. Its normal native suite excludes an explicitly declared opt-in helper benchmark; the source includes an offline cached-runtime runner for that comparison. Explore.28's cached dashboard observations, safe launcher child/logging cleanup, bounded public roster validation and off-thread coalesced telemetry remain. Native per-player saves preserve player/stats/advancement persistence for local actions and both trade participants. Minigame choices persist after slower runs, unchanged-best retries avoid redundant saves, and failed leaderboard writes retry. Gear delivery recognizes equipped/offhand ownership, and backpacks retain dyeable layers. See `server/RELIABILITY.md` and the current source-bound `server/VALIDATION.md`. No live installation is included in this development change.

Explore.27 adds original custom collection artwork, item-specific static Bedrock armor, a separate solid Convergence chestplate without changing the flight ID, collection filters and five real labeled inventory boxes. Only complete grants record delivery receipts. See `server/ORIGINAL_ARTWORK.md` and `artwork/refresh27/README.md` for controls, reproduction and exact client limits. That historical development stage preceded the approved explore.36 installation.

Explore.26 adds bounded loaded-structure scans and native owned compasses in `OdysseyCompass.java`, course instructions/records/retry controls in `CourseSelector.java`, and three persistent roster-wide combat-profile controls. The course and helper screens now retain their actual menu/world session. `GearCrates.java` supplies real vanilla Admin Blocks toolboxes to OP4 Creative players; `CreativeGearPicker.java` exposes recovery, and the modded Java Creative tab includes the native blocks when authorized. `ShortCommands.java` redirects aliases to native nodes, preserves original names/permissions/suggestions and provides four gamemode presets. The existing `/ai` command remains unchanged. See `server/EXPERIENCE_POLISH.md` for controls and client limits.

`CreativeStudio.java` adds native menu-controlled brushes, atomic item kits, conflict-aware session undo, exact reviewed blueprints, teleport/effect gadgets and trimmed native outfits. `RcPlanes.java` manages bounded native falling-block assemblies with collision checks and cleanup; its mixins prevent plane parts from placing blocks or dropping items. `BlastSafety.java` queues primed TNT globally and bounds excessive single-explosion radii. These controls and the restored ordinary Bedrock armor textures are documented in `server/CREATIVE_STUDIO.md`. All five gadget IDs are actual registered items with original explore.27 sprites; the Storm Staff has a real crafting recipe.

This is a server-only Java and Bedrock crossplay build derived from the user-supplied Infinity Armor v2.3.0 source. The original Java and Bedrock downloads remain unchanged. Start with `server/README.md` for installation, menus, optional commands, and current limitations.

The Java Fabric mod runs the shared world. `CrossplaySupport.java` exposes the original custom items and blocks through Polymer, generates the Java texture pack, and exports Geyser mappings. Floodgate handles Bedrock identities. The original Java powers remain server authoritative. The launcher runs Fabric and Geyser together; it does not load the original Bedrock behavior pack.

`OdysseyStructure.java` registers native sparse world generation and persistent biome-themed structure pieces for the Overworld, Nether and End. `artwork/odyssey/blueprint.json` and `tools/generate_odyssey_assets.py` preserve the owner's relative block layout and exact lower-chest contents in 21 templates. `OdysseyProphecy.java` delivers the starting written book without replacing inventory items. `OdysseyJournal.java` discovers actual registered pieces in loaded terrain, persists realm progress and one-time gift receipts in native player state, and provides a protected journal menu. Four earned gifts connect the three-realm journey to the new Memory Circuit and Laser Gate Dash trials; original shrine loot is unchanged. The requested `/ban aria` repeater has a narrowly scoped native permission exception that requires the host's private opt-in; see `server/ODYSSEY.md`. Preparation and packaging verify that generated templates match the blueprint.

`ServerMenu.java` provides an owner-marked **Infinity Menu** recovery compass, a vanilla chest-style menu for every `/convergence` feature, and shortcuts to worlds, courses, backpacks and AI helpers. Selecting the compass restores the preceding held tool before opening its power controls. Menu clicks recheck permissions, equipment and player state; icons cannot be collected. Missing compasses use an empty inventory slot rather than replacing items. `LobbyServer.java` installs **INFINITY MENU** signs in the Main Hub and five mode lobbies without replacing occupied blocks. `AgentMenu.java` makes first-helper creation explicit: OP4 players choose **AI Helpers • open your squad → Create your first helper**, the menu closes, and a named iron golem spawns on suitable nearby ground or a visible failure is reported. Server-changing proposals and named-player targets still require owner approval and a separate live Codex console review. The original commands remain available.

`ServerAssistant.java` supports the existing OpenAI API route and a configurable Codex CLI provider. `server/community.py` and the private dashboard validate an absolute executable path and enforce OP4-only Codex access before saving enabled settings. Codex uses the host's existing ChatGPT login rather than the server's API-key file; account usage and model availability still apply. The connection currently accepts the audited native CLI 0.155.1 only; other versions stop pending compatibility review. Use the native executable instead of a Node-dependent launcher shim. Saving checks the path, with login/version readiness checked when a question is sent. Enabled Codex routes an OP4 player's `/ai` questions (except local help/status) and `AgentChat.java` questions in all six profiles to external text answers; ordinary players retain built-in help for known server topics. `AgentMenu.java` exposes **Ask Codex**, helper status, and back navigation. Helper requests include bounded Minecraft observations. The answer integration is separate from this live Codex conversation and the local-console approval queue; replies cannot approve or dispatch commands. See `server/AGENTS_GUIDE.md` for the data-sharing and configuration contract. No live-provider or real-device result is implied by these source descriptions; consult `server/VALIDATION.md` for actual checks.

`AgentCompanions.java` gives Ultimate Finals squads shared focus, flanking and target prediction capped at two blocks. Eligible helpers rotate a short physical leap-and-dive approach when loaded airspace and a solid landing are clear, with an 80-tick per-helper leap cooldown and at most one aerial sequence per owner/target. Ultimate Finals equips real vanilla netherite spears, maces and elytras. Native spear use ticks produce kinetic charging contacts; helpers wind up before rushing and switch to a mace inside the spear’s minimum reach. The aerial arc briefly enters native glide, stops gliding and attacks through Minecraft’s native mob weapon path with the mace fall bonus. Discrete melee attacks remain at least 20 ticks apart, and kinetic contacts retain native cooldowns. Reach, sight, gravity, collision and target guards remain active. Blocked routes use ground pursuit. Stay, ceasefire, changed profiles, invalid target orders or owner OP4 loss cancel the sequence. Native player avatars now show equipped weapons and elytra. The underlying server AI and physical collision remain golem-based; these are not authenticated player accounts. No per-tick AI calls, custom player burst damage or totem bypass are added. Player targets retain both approval gates, PvP/team rules, expiry and movement bounds.

`CommunityServer.java` adds homes, shared warps, teleport requests, rules, moderation, and safe server backups. `GameModes.java` keeps Survival, Creative, Hardcore, Minigames, and Adventure player profiles separate, with built-in minigames and adventure maps in `ModeMaps.java`. `Memberships.java` implements Free, Go, Plus, Pro, Ultra, and free code-gated Admin. Each permanent cosmetic rank requires all three achievements in its group: Go needs a stone pickaxe, an iron ingot, and Monster Hunter; Plus needs an iron pickaxe, a diamond, and an enchanted item; Pro needs entry to the Nether, a blaze rod, and entry to the End; Ultra needs the Ender Dragon defeated, an End gateway entered, and an End city found. These are server-tracked advancements for Java and Bedrock players; the highest completed group wins without requiring lower groups, an earlier earned rank persists, and existing temporary owner grants remain until they expire. Creative inventory items can trigger item milestones. Survival item trades offer an alternative permanent rank unlock. `AchievementRewards.java` adds six rank-independent power presets and eight earnable particle cosmetics. `RewardTrades.java` stores each paid receipt alongside debited inventory in the same vanilla player save; it never copies paid entitlements into the separate rewards JSON. See `server/REWARDS.md` and `server/TRADING.md`.

Verified private-code redemption and trusted `membership grantadmin` now persist Admin and promote the player to actual vanilla OP4; existing saved Admin roles migrate on join. Admin receives full vanilla commands, operator gameplay unlocks and Infinity ability cooldown bypass. Role-created promotions record the prior operator state so `membership revokeadmin` can restore it while retaining a pre-existing OP4 grant or a separately recognized manual grant. Persistence failures stop the permission change. Active Admin sync can restore OP4 after `deop`, so remove the Admin role first. Mode inventories, backpack separation, confirmed trades and both AI approval gates remain. Admin does not create unlimited helper slots, model usage, machine resources or external account access. See `server/MEMBERSHIPS.md` for code setup and private backup details.

`LobbyServer.java` builds one dedicated hub dimension with a Main Hub and five connected mode lobbies. New players start there. `/hub`, `/lobbies`, `/lobby <mode>`, and `/play <mode>` work on Java and Bedrock. The hub uses a separate temporary inventory with its menu compass, while each gameplay mode's inventory, Ender Chest, health, and progress are saved independently. `server/LOBBIES.md` explains navigation and the generation marker.

Lobby visuals use themed vanilla-block arches, colored glass, banners, lighted columns and planted accents, with a cyan glass/sea-lantern crown over the Main Hub. Original navigation and menu signs receive glowing white text. Startup and a bounded hook for known lobby chunks upgrade older saves without rebuilding them: accents fill only air above recognized original floors, occupied blocks and edited sign text are preserved, and no distant chunks are force-loaded. Modified areas may omit decorations; existing bridges, arrival paths and destinations remain.

`/guide` gives place-specific directions in the Hub, lobbies, maps, and game modes. `/best` and `/leaderboard <map>` expose persisted minigame times. The private dashboard shows current Java and Bedrock join details separately. On Apple Silicon Macs, `server/Start-Pinggy-Mac.command` can prepare the pinned official Pinggy client on first use and request two temporary tunnels; see `server/PINGGY_JOINING.md`. It is optional, and live addresses are never part of the public site or release archives.

## Build and validate

Use Java 25, Python 3.9+, and network access for checksum-pinned dependencies. The project pins Gradle 9.7.1 and Loom 1.18.2 for unobfuscated Minecraft 26.4 Snapshot 5 with the pinned public-source ports in [snapshot compatibility](../compatibility/snapshot5/README.md). `JAVA_HOME` must point to Java 25 when invoking Gradle directly.

```sh
python3 tools/generate_odyssey_assets.py --check
python3 tools/build_creative_bridge.py --javac /path/to/javac --receipt research/integration/creative-bridge/build.json
python3 tools/check_crossplay.py --java /path/to/java
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_creative_wire.py --java /path/to/java
python3 tools/smoke_creative_persistence.py --java /path/to/java --receipt research/integration/creative-bridge/persistence-proof.json
python3 tools/verify_armor_equipment.py --java /path/to/java --output research/armor-equipment/2.13.0-explore.49.json
python3 tools/check_winged_chest.py --java /path/to/java --output research/winged-chest/2.13.0-explore.49.json
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 -W error::ResourceWarning -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/package_crossplay.py
```

The winged-chest check exercises the real registered Geyser property schema,
native inventory/equipment paths and packet codecs in a disposable offline
fixture. Its proof binds the exact extension sources, prepared bridge, pack,
lock and catalogue. It does not execute a Bedrock renderer or prove touch flight.
The packager also requires current Snapshot 5 full-application Play,
authentication refusals and isolated-world conversion evidence, described in the
snapshot compatibility guide. Archive existing proof receipts before validating
a newer candidate; the wing/armor checkers refuse to overwrite them.

The bridge builder needs the exact pinned `server/geyser/Geyser-Standalone.jar`, supplied by the checksum-managed server setup. The source package includes the already prepared server artifacts; a repository-only checkout also needs the documented vendor/server setup before preparation. Run each command only after the previous one succeeds. The redirected logs are required packaging evidence; inspect them if a command fails. The native GameTest driver builds the mod, checks dimension fixture drift, and starts disposable Minecraft worlds with release dependencies. The supplementary wire check compiles an external test into a private copy of the tested jar; it requires the preceding normal native and Geyser captures. It sends real Creative/Survival abilities through the pinned Via handler, translates captured Geyser bytes, and verifies native item reconstruction, persistence and refusal. Packaging requires its current source, dependency, capture and report hashes. The new persistence check sends real framed packets through Fabric/Polymer receive hooks, then verifies the complete current catalogue through native UUID player-file writes and every required full or partial 30-slot batch/reconnect; it also preserves renamed/damaged gear and personal backpack contents. Use a new receipt path for an exploratory run; the packager requires the exact fixed current receipt path shown above, so preserve any existing successful receipt before replacing it for a new version. The Python suite checks the launcher and packaging behavior without skipped evidence checks. Geyser and full-stack smoke checks verify startup, Bedrock UDP response, shutdown, and a stopped-world backup. The full-stack check requires EULA acceptance already present in the local host folder; it never accepts the EULA itself. These checks do not establish authenticated player login or client rendering. See `server/VALIDATION.md` for exact coverage and remaining device play-test work.

The packager writes server and source ZIPs under `dist/lobbies`. It includes explicit release files and reports, excluding worlds, keys, EULA files, account data, runtime downloads, and local panel tokens. Do not archive the whole `server` or `research` directory after running a world. The original powers guide is included as `ORIGINAL_GUIDE_2.3.0.md`.

The Admin editor is implemented in `AdminStats.java` and `AdminStatsMenu.java`. It enumerates supported player and AI golem attributes, changes finite values within native ranges, preserves modifiers, and records original base values in the existing player `InfinityModes` data for reset. Registered helpers save their originals in the same entity NBT through `AdminStatGolemMixin`; no separate reset database is used. OP4, actor/target identity, helper registration, connection and menu context are rechecked on mutation. There is no background stat pinning or AI approval shortcut.

Lobby decoration CHUNK_LOAD events enqueue work without reading world blocks. END_WORLD_TICK processes only the same completed chunk instance through the nonblocking chunk lookup; unload/shutdown clears queued work. Native lifecycle regressions cover deferred mutation and stale events. The full-stack smoke test now loads the saved hub after each startup and checks console responsiveness before cleanup, covering the cold-load path missed by startup-only checks.

The explore.12 menu adds direct self-stat access, exact private numeric entry and in-place +/- updates. Extended combat attributes are scoped to admin-edited instances, retain modifiers, co-save their expansion flag with reset metadata, restore high health after native loading, and disable the override on reset. Movement/size/reach retain native client physics bounds. Creative inventory gear boxes use native shulker storage, with atomic empty-slot checks. Armor and icons fall back to complete native Java assets when the resource pack is unavailable; Bedrock pack revisions invalidate old cached resources. Helper target/action menus preserve exact-session checks and both approval gates; they do not turn golems into weapon-equipped player bots.

The Bedrock Admin stat editor uses Floodgate’s bundled Cumulus native forms: select a stat, enter its exact numeric value, and confirm the shown target/value. Java retains its chest editor and private chat input. One-use session tickets recheck OP4, life, mode, target identity and numeric bounds; ordinary health/food drift does not cancel an explicitly reviewed absolute vital value. The main Infinity Menu accepts Bedrock touch-transfer clicks without moving icons. **Armor sets** grants all four Aurora or Ember pieces, including the chestplate, through existing unlock/inventory checks. Bedrock always receives native wearable equipment assets and icons for its native cosmetic mappings; Java uses custom artwork when its pack is present.

The explore.14 patch adds a shared stat change plan for direct health/absorption entry, stale two-change validation, reload-safe remaining absorption and scoped extended armor reduction. Gear labels are literal on server and client wire stacks, preserve anvil names, and use the existing language catalogue. All Bedrock wearables use complete native assets; original artwork is unchanged for pack-enabled Java clients. Direct inventory grants preserve occupied slots and avoid duplicates. The ranks menu separates earned/item-trade ranks from Admin/OP roles; answer-only AI facts include both. Helper avatars use Polymer PLAYER spawn types, an unlisted native profile packet before spawning, compatible common metadata and native player defaults, viewer cleanup and existing server equipment. No ordinary golem is disguised. Autonomous native combat is tested through normal server ticks, without manually calling control/attack.


## Helper robot skin in explore.15

Helpers share a charcoal and cyan robot skin with a silver face. The chest carries a small white ChatGPT knot and the blue-purple Codex cloud containing a tiny terminal prompt. Both are pixel approximations within Minecraft’s 8 by 12 pixel chest, rather than official OpenAI avatars. Native signed Minecraft texture data is sent to Java clients and Geyser; clients download the skin from Minecraft’s texture host. The server does not need a MineSkin account or API key at runtime. Reconnect after the update to refresh tracked helper profiles. Your saved helpers, owners, names, stats, equipment and orders are preserved. A chestplate can cover the chest emblems while equipped.

The explore.25 playground adds native-tagged Gust Glove and Anchor Charm controls to `CreativeStudio.java`, a complete native Tidewarden outfit, saved idle helper formations in `AgentCompanions.java`, and two real courses in `ModeMaps.java`. `CourseSelector.java` lays out all eight minigames in two rows, and `MinigameRecords.java` includes both new records in its persisted public index. Gadgets use native item icons and authority on the server; existing registered gear and refreshed artwork are retained. `PlaygroundGameTests.java` covers inventory/cursor safety, native progress and formation persistence, safe destinations, both physical course paths and public-record reload. See the current validation record for actual passing checks and the outstanding client playtest.
