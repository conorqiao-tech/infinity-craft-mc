# Game worlds and built-in maps

Use **`/hub`** for the Main Hub, **`/lobbies`** for the five mode lobbies, or **`/play`** to enter a game world. See **LOBBIES.md** for navigation.

Use **`/play`** in Minecraft. Java has clickable choices; the same short commands work on Bedrock. Mode changes take three seconds. Stay still, leave vehicles, and let shoulder pets dismount. Taking damage cancels the change, and you must wait ten seconds after damage before trying again.

| Mode | Command | What you get |
|---|---|---|
| Survival | `/play survival` | Your existing main world, crafting, Infinity gear, homes, Nether and End. |
| Creative | `/play creative` | A separate flat building world with Creative flight, unlimited blocks, and an Infinity gear picker. |
| Hardcore | `/play hardcore` | A separate natural Overworld, hard difficulty, one life per player. |
| Minigames | `/play minigames` | A chest-like menu with Sky Steps, Switchback Sprint, Prism Dropper, Red Light Run, Crystal Hunt and Color Rush. |
| Adventure | `/play adventure` | A chest-like menu with The Five Seals and Lantern Labyrinth. |

These dimensions live inside the same Java world save. Java and Bedrock players share each area. The eight built-in maps are small starter courses, generated automatically once; this is not a collection of large downloaded campaigns or a matchmaking network. In the Main Hub, tap or right-click the Minigames or Adventure entry sign to open the same course menu. At a course start, use the **SELECT COURSE** sign to switch maps. Direct `/minigame <map>` and `/adventure <map>` commands remain available.

When you enter Creative through its lobby or `/play creative`, the server puts an Infinity sword in your hand, places a named compass in your hotbar, and opens a chest-like Infinity gear picker. Tap a familiar vanilla icon to equip the actual Infinity weapon, tool, or building wand. Switch away from and back to the compass slot to reopen the picker. No operator access or extra gear command is required. If the menu does not open, use `/convergence gear`; `/convergence hold sword` (or `mace`, `spear`, `pickaxe`, `axe`, `shovel`, `hoe`, `builder_wand`, or `sculptor_wand`) still equips one item directly. `/convergence kit` gives the full set. Bedrock players should use the server picker instead of trying to drag custom items from the built-in Creative catalogue. Infinity gear obtained in Creative stays in that mode's inventory when you switch worlds. The picker still needs a real Java and iPad playtest.

Inventories, equipped armor/offhand, Ender Chests, personal backpack storage, experience, health, hunger, potion effects, bed spawns, and return positions are stored separately for each mode. Active state and inactive profiles are saved together in the player's vanilla data file. Players keep their own progress when returning. The three named home slots are shared across modes, so use different names (for example, `survival_base` and `creative_build`). Homes, warps, teleport requests, portals, and loose items cannot bypass mode isolation. Vanilla Nether and End travel remains available inside Survival. The Hardcore world has no Nether or End in this release.

## Operator access

Level-4 operators can switch game worlds immediately, including during combat, and are not forced back to a prescribed game mode by the mod. Operator teleporting across game worlds updates the matching mode profile. Operators can enter Hardcore after elimination; ordinary players keep the one-life rule. See [AGENTS_GUIDE.md](AGENTS_GUIDE.md) for operator controls.

## Hardcore

Death marks that player's Hardcore life as ended. Respawning allows spectating the Hardcore world; **`/play survival`** lets them continue in other modes. They cannot rejoin Hardcore as a living player. The entire server does not shut down or ban them. Vanilla and Infinity totems can still prevent an eligible death. Ranks do not grant extra lives, gear, or Hardcore resets.

World backups include player profiles and elimination flags. Restore whole matching world backups; mixing newer player files with older world data can undo progress or duplicate items, as with ordinary Minecraft backups.

## Powers and item trades by mode

The earned presets in [REWARDS.md](REWARDS.md) work only in Survival, including its Nether and End. They turn off before entering the hub or another mode. They do not grant Hardcore lives or advantages in a timed map. Rank badges and earned particle cosmetics remain separate from gameplay powers.

The item exchanges in [TRADING.md](TRADING.md) can be previewed anywhere, but confirmed only while alive in Survival game mode, outside combat and without a pending mode change. Payment comes from that Survival inventory. A permanent receipt belongs to the player's UUID and remains available when switching modes; it does not transfer Creative items into Survival.

## Play the maps

Choose a course from the menu opened by `/play minigames`, `/play adventure`, or the corresponding hub entry sign. Tap its vanilla icon to start. Each course start has a **SELECT COURSE** sign; its glowing checkpoint lanterns also reopen the menu. The six minigames and two adventure maps share their physical spaces between players, but each player's run and timer are separate. Maps run in Adventure mode with damage disabled. Leaving or disconnecting ends an unfinished run; the selected map and saved personal best remain, and reconnecting starts a fresh run on that selected map. A real iPad device still needs to verify the menu and sign interactions.

Sky Steps, Switchback Sprint, Prism Dropper, Red Light Run, The Five Seals and Lantern Labyrinth use ordered checkpoints. Chat gives the next coordinates; reaching only the final checkpoint does not finish a run. Falling off a course restarts the run.

- `/retry parkour` — restart Sky Steps.
- `/retry sprint` — restart Switchback Sprint.
- `/retry dropper` — restart the fall through three holes into water.
- `/retry redlight` — restart the stop/go race.
- `/retry crystalhunt` — collect five glowing amethyst pads in any order, once each.
- `/retry colorrush` — reach the named color before each five-second pulse expires, five times.
- `/retry ruins` — restart The Five Seals.
- `/retry maze` — restart Lantern Labyrinth.
- `/play survival` — leave and restore your Survival inventory.

Prism Dropper requires downward passage through all three holes and a water finish. Red Light uses text and colored status indicators: move on green, stop on red, with a four-tick transition grace period. Crystal Hunt counts each of five amethyst pads once in any order. Color Rush names a color in the action bar; reach that pad within five seconds to advance through five pulses. Both new courses restart after leaving their arena or missing the timer. `/backpack` is unavailable in maps and cannot introduce stored gear. New courses are generated incrementally on upgrade; existing marked courses remain intact.

`/best` shows your saved times, and `/leaderboard <map>` shows the fastest persisted records for parkour, sprint, dropper, redlight, crystalhunt, and colorrush. There is no match queue, team round system, or competitive reward currency in this release.

The generation marker `fabric/world/infinity-built-in-maps.json` prevents automatic rebuilding over subsequent edits. Keep it with the world. Do not delete the marker in a used world to try to import a custom map: regeneration would replace the built-in map areas. Importing third-party maps requires a separate tested conversion/integration.
