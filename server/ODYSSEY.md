# The Odyssey ruins and starting prophecy

Version: **2.13.0-explore.25**, for the combined Minecraft Java **26.3** server and its existing Bedrock bridge.

The ruin follows the owner's compact circuit structure: the same 46-block arrangement, glowing center, lower repeating command block and **one single chest**. The upper chest and two detached torches are excluded. The owner's existing build is never edited.

## Natural generation

Ruins generate in **new terrain** across the Overworld, Nether and End. All native biomes are eligible, including underground caves. Native random-spread placement considers one candidate per **40 × 40 chunks** (a 640 × 640 block region), with 16-chunk separation. Terrain can reject candidates, so this is a density rather than a promised distance. Nether candidates use dry caverns below the bedrock roof; End candidates require a solid island. Empty void does not receive floating ruins. Existing chunks and built lobby/game dimensions are not retroactively filled.

Twenty-one material palettes retain the same shape: plains, forests/taiga, deserts/beaches, badlands, snow/ice, oceans, jungles, swamps, mushroom fields, cherry groves, pale gardens, dark forests, savannas, mountains, underground caves, Nether wastes, crimson forests, warped forests, soul-sand valleys, basalt deltas and the End. These use native blocks and the existing server pack.

## Original loot and repeating block

Each chest contains the lower chest's original **27 item stacks, in the same slots and counts**. This includes the original “The Odessy ” book and authored text, the totem, turtle helmet, elytra, netherite spear, mace, trident, redstone, command block, wind charges, fishing rod, end crystals, obsidian, TNT, flint and steel, all four Aurora pieces, and the listed Infinity weapons and tools. The upper chest's gear boxes and rare-block stacks are excluded. Loot is fixed rather than randomized, as requested.

The repeating block retains **`/ban aria`** and **Always Active**, as explicitly selected by the owner. Other command blocks become the biome's building material. Native command execution follows the server's `minecraft:command_blocks_work` gamerule; automatic behavior requires that rule to be true. The mod does not force it back on after an admin changes it. Generated repeaters disable retained output while keeping native scheduling. On a server with the opt-in below and command blocks enabled, the ban runs against that Minecraft username.

Vanilla command blocks have OP2 permission, while `/ban` needs OP3, so **by default the repeater cannot ban anyone**. A public install never raises its permission. To keep the owner's ban on your own server, create `fabric/config/convergence-odyssey.json` containing `{"trusted_ruin_command": "/ban aria"}` and restart. The mod then supplies OP3 only for that exact `/ban <name>` text, executed by the original repeating block at its rotated position in a registered, generated Odyssey ruin. Any other command, a second target, selectors or extra text in the setting are ignored; other commands and manually placed copies keep normal permissions. Removing the file, editing or removing the block, or disabling command blocks, remains effective.

## Starting prophecy

Players receive **The Odyssey: Prophecy**, a four-page native written book, after their mode inventory loads. It tells the chosen-traveler story, describes the changing biome palettes, points to all three realms and explains the single cache. The original loot book remains unchanged.

Delivery uses an empty slot, saves a receipt with native player data and retries a full inventory. Each separate mode inventory receives its own starting copy once. Recover a lost copy with **Infinity Menu → The Odyssey • journal & prophecy → Receive or recover your prophecy**. Recovery preserves existing items and avoids duplicating a book already carried. No operator rank or typed command is needed.

## Circuit Keepers journal — explore.25 addition

The new journal adds progression to the established prophecy without changing its four pages, the original loot book, ruin layout or one-chest rule. Open **Infinity Menu → The Odyssey • journal & prophecy** to see discoveries and claim gifts. The journal records your position and biome palette when you physically enter a registered generated Odyssey ruin in Survival or Hardcore. Merely holding the prophecy, walking near a ruin, or building a copy does not count. Creative and the lobby/game dimensions do not count.

This added quest is called the **Circuit Keepers journey**. Rediscover a circuit in each of the Overworld, Nether and End, then prove memory and timing in the two new minigames. Discoveries and claimed gifts save with your native player data across mode changes and reconnects. Hardcore has only an Overworld; continue the Nether and End discoveries in Survival.

| Gift | Requirement | Reward |
| --- | --- | --- |
| First circuit | Discover a ruin in one realm | Eight echo shards |
| Twin circuits | Discover ruins in two different realms | Complete four-piece Tidewarden armor with copper Tide trim |
| Three circuits | Discover ruins in all three realms | Odyssey Circuit Trident |
| Keeper's trials | Three realms plus a finish in Memory Circuit and Laser Gate Dash | Odyssey Memory Mace |

Each gift can be claimed once. Return to Survival or Hardcore, leave the cursor empty and keep enough empty inventory slots before claiming. A full inventory retains the unclaimed gift and never drops or replaces items. The trident and mace are named native weapons using normal Minecraft combat. The Tidewarden outfit uses native diamond equipment; it does not inherit Infinity powers. You can also obtain the outfit in Creative Studio, separately from earned progress.

Ruins remain rare in newly generated terrain. The journal records places you discover; it is not a locator for undiscovered ruins. Existing terrain follows the host's explicit refresh policy during upgrades. The protected player/helper areas remain preserved by that policy.

## Authoring and checks

The relative-coordinate blueprint and original loot are in `artwork/odyssey/blueprint.json`. `python3 tools/generate_odyssey_assets.py` produces the 21 native templates; `--check` verifies reproducibility. Generation uses a registered native structure and persistent piece rather than a chunk-load callback.

Native regressions cover all palettes, one chest, every original stack and book component, actual container loading, the requested repeating command, all native biome tags, sparse placement, Nether floor selection, full-inventory retries and menu recovery. Results are recorded in `VALIDATION.md`. Real Java/iPad gameplay and appearance still need a client check after installation.
