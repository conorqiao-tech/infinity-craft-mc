# Inventory and menu improvements — explore.26

These controls are implemented in the next source/package version. The owner's live Mac server remains **explore.25** until a separately approved installation. Java needs Minecraft **26.3**; Bedrock continues through the pinned Geyser bridge.

## Admin Blocks in Creative inventory

An online **OP4** player in Creative receives a labeled red **Admin Blocks • Creative Toolbox** in an empty inventory slot. Place it and open it as a normal shulker box. It contains command, repeating and chain command blocks, a command-block minecart, structure block, structure void, jigsaw, barrier, light and debug stick, each using its native stack size. The delivery receipt saves per mode inventory to avoid automatic repeats; it is not a new operator grant. Full inventories retain their items and can retry through the menu.

Recover a box with **Infinity Menu → Weapons, tools and blocks → Get Admin Blocks** while in the Creative world with OP4. The native Java and Bedrock chest menu delivers a real inventory container; it does not depend on Geyser's custom Creative search catalogue. Java clients with the Fabric mod also see these native items in its Creative tab when the client has operator permissions.

Minecraft's normal permissions still control placing/editing command and structure blocks. A box already owned after losing OP is not removed, and possessing a block does not grant operator access. Native Java debug-stick controls have limited Bedrock support; the box cannot add unsupported client interactions.

## Shorter commands

`/shortcuts` lists the aliases you may use. Arguments, native selectors, suggestions and permissions follow the original commands. Existing command names win collisions, so a different installed command is never replaced. `/ai` keeps its existing server-help/Codex-chat behavior.

| Short form | Original |
| --- | --- |
| `/gm` | `/gamemode` |
| `/gr` | `/gamerule` |
| `/fx` | `/effect` |
| `/ench` | `/enchant` |
| `/loc` | `/locate` |
| `/diff` | `/difficulty` |
| `/attr` | `/attribute` |
| `/i` | `/give` |
| `/wx` | `/weather` |
| `/tm` | `/time` |
| `/ag` | `/agent` |
| `/ic` | `/convergence` |
| `/mg` | `/minigame` |
| `/adv` | `/adventure` |
| `/lb` | `/leaderboard` |

The presets `/gmc`, `/gms`, `/gma` and `/gmsp` set Creative, Survival, Adventure and Spectator respectively. Append a player name or native selector to target someone, for example `/gmc @s`. These change native abilities in the current world; **Play Creative** in Infinity Menu enters the separate Creative world with its own inventory. The presets retain vanilla gamemode permission checks.

## Find real Odyssey ruins

**Infinity Menu → The Odyssey → Scan for a nearby circuit** searches already completed loaded chunks within 96 horizontal blocks. A real registered piece supplies a dimension-aware native compass and coordinates. No signal means explore further; the scan never generates chunks or awards discovery. Rescanning updates your carried or offhand compass and preserves its anvil name. Enter the actual ruin in Survival or Hardcore to advance the journal. See [ODYSSEY.md](ODYSSEY.md).

## Play again and manage the squad

Course icons show actual game instructions and saved personal bests. **View saved records** opens a read-only page with the minigame top five; **Retry** resets the selected run while preserving scores. Old, foreign, busy-cursor or wrong-world menu clicks cannot trigger a mode change. Eliminated Hardcore spectators have a working **Return to Main Hub** route.

The helper roster's three **Set squad** buttons apply Primitive, Regular or Ultimate Finals to your entire saved roster, including unloaded helpers. Existing stats, movement orders and locations remain. Follow resumes a helper on Stay. Both approvals for player targeting and AI actions remain required; selecting an aggressive profile alone never grants a player target.

## Player checks after installation

1. Join with Java and Bedrock, enter Creative as OP4 and open/place the red toolbox. Verify all ten contents, then reconnect and recover it from the menu.
2. Try `/gms @s`, `/gmc @s`, `/gm survival` and `/fx give @s minecraft:night_vision 30`. Confirm tab suggestions on Java and denied native operator commands on a non-OP account.
3. Use a course's instructions, finish a run, open records and Retry. Confirm the old best survives reconnecting.
4. Near an actual generated Odyssey ruin, scan and follow the pointer. Try again far away and with a full inventory. The scan itself must not increase discovery progress.
5. Select a squad profile with loaded and unloaded helpers. Check saved stats and Stay, then Follow to resume ordinary mob defense. Player targets must still wait for both reviews.

Native tests and bridge smoke checks do not replace an authenticated Java/iPad rendering and touch-input playtest. See [VALIDATION.md](VALIDATION.md) for actual evidence.
