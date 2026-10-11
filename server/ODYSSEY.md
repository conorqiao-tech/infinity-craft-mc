# The Odyssey ruins and starting prophecy

Version: **2.13.0-explore.22**, for the combined Minecraft Java **26.3** server and its existing Bedrock bridge.

The ruin follows the owner's compact circuit structure: the same 46-block arrangement, glowing center, lower repeating command block and **one single chest**. The upper chest and two detached torches are excluded. The owner's existing build is never edited.

## Natural generation

Ruins generate in **new terrain** across the Overworld, Nether and End. All native biomes are eligible, including underground caves. Native random-spread placement considers one candidate per **40 × 40 chunks** (a 640 × 640 block region), with 16-chunk separation. Terrain can reject candidates, so this is a density rather than a promised distance. Nether candidates use dry caverns below the bedrock roof; End candidates require a solid island. Empty void does not receive floating ruins. Existing chunks and built lobby/game dimensions are not retroactively filled.

Twenty-one material palettes retain the same shape: plains, forests/taiga, deserts/beaches, badlands, snow/ice, oceans, jungles, swamps, mushroom fields, cherry groves, pale gardens, dark forests, savannas, mountains, underground caves, Nether wastes, crimson forests, warped forests, soul-sand valleys, basalt deltas and the End. These use native blocks and the existing server pack.

## Original loot and repeating block

Each chest contains the lower chest's original **27 item stacks, in the same slots and counts**. This includes the original “The Odessy ” book and authored text, the totem, turtle helmet, elytra, netherite spear, mace, trident, redstone, command block, wind charges, fishing rod, end crystals, obsidian, TNT, flint and steel, all four Aurora pieces, and the listed Infinity weapons and tools. The upper chest's gear boxes and rare-block stacks are excluded. Loot is fixed rather than randomized, as requested.

The repeating block retains **`/ban aria`** and **Always Active**, as explicitly selected by the owner. Other command blocks become the biome's building material. Native command execution follows the server's `minecraft:command_blocks_work` gamerule; automatic behavior requires that rule to be true. The mod does not force it back on after an admin changes it. Generated repeaters disable retained output while keeping native scheduling. Loading a copy with command blocks enabled runs the ban against that Minecraft username.

Vanilla command blocks have OP2 permission, while `/ban` needs OP3. The mod supplies that permission only for the exact `/ban aria` text, executed by the original repeating block at its rotated position in a registered, generated Odyssey ruin. Other targets, other commands and manually placed copies keep normal permissions. Removing or editing the block, or disabling command blocks, remains effective.

## Starting prophecy

Players receive **The Odyssey: Prophecy**, a four-page native written book, after their mode inventory loads. It tells the chosen-traveler story, describes the changing biome palettes, points to all three realms and explains the single cache. The original loot book remains unchanged.

Delivery uses an empty slot, saves a receipt with native player data and retries a full inventory. Each separate mode inventory receives its own starting copy once. Recover a lost copy with **Infinity Menu → The Odyssey • your prophecy**. Recovery preserves existing items and avoids duplicating a book already carried. No operator rank or typed command is needed.

## Authoring and checks

The relative-coordinate blueprint and original loot are in `artwork/odyssey/blueprint.json`. `python3 tools/generate_odyssey_assets.py` produces the 21 native templates; `--check` verifies reproducibility. Generation uses a registered native structure and persistent piece rather than a chunk-load callback.

Native regressions cover all palettes, one chest, every original stack and book component, actual container loading, the requested repeating command, all native biome tags, sparse placement, Nether floor selection, full-inventory retries and menu recovery. Results are recorded in `VALIDATION.md`. Real Java/iPad gameplay and appearance still need a client check after installation.
