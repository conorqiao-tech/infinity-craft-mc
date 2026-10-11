# Game worlds and built-in maps

Use **`/hub`** for the Main Hub, **`/lobbies`** for the five mode lobbies, or **`/play`** to enter a game world. See **LOBBIES.md** for navigation.

Use **`/play`** in Minecraft. Java has clickable choices; the same short commands work on Bedrock. Mode changes take three seconds. Stay still, leave vehicles, and let shoulder pets dismount. Taking damage cancels the change, and you must wait ten seconds after damage before trying again.

| Mode | Command | What you get |
|---|---|---|
| Survival | `/play survival` | Your existing main world, crafting, Infinity gear, homes, Nether and End. |
| Creative | `/play creative` | A separate flat building world with Creative flight and unlimited blocks. |
| Hardcore | `/play hardcore` | A separate natural Overworld, hard difficulty, one life per player. |
| Minigames | `/minigame parkour`, `sprint`, `dropper` or `redlight` | Sky Steps, Switchback Sprint, Dropper and Red Light Run. |
| Adventure | `/adventure ruins` or `/adventure maze` | The Five Seals exploration map and Lantern Labyrinth. |

These dimensions live inside the same Java world save. Java and Bedrock players share each area. The six built-in maps are small starter courses, generated automatically once; this is not a collection of large downloaded campaigns or a matchmaking network.

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

Follow the glowing sea-lantern checkpoints in order. Chat gives the next checkpoint coordinates. Reaching only the final checkpoint does not finish a run. Finishing records a personal best time. Maps run in Adventure mode with damage disabled. Falling below the course restarts the run. Leaving or disconnecting ends the current run; saved personal bests remain.

- `/retry parkour` — restart Sky Steps.
- `/retry sprint` — restart Switchback Sprint.
- `/retry dropper` — restart the fall through three holes into water.
- `/retry redlight` — restart the stop/go race.
- `/retry ruins` — restart The Five Seals.
- `/retry maze` — restart Lantern Labyrinth.
- `/play survival` — leave and restore your Survival inventory.

Dropper requires downward passage through all three holes and a water finish. Red Light uses text and colored status indicators: move on green, stop on red, with a four-tick transition grace period. `/backpack` is unavailable in maps and cannot introduce stored gear. New courses are generated incrementally on upgrade; existing marked courses remain intact.

The maps share physical space between players, while checkpoint progress and timers belong to each player. `/best` shows your saved times, and `/leaderboard <map>` shows the fastest persisted records for parkour, sprint, dropper, and redlight. There is no match queue, team round system, or competitive reward currency in this release.

The generation marker `fabric/world/infinity-built-in-maps.json` prevents automatic rebuilding over subsequent edits. Keep it with the world. Do not delete the marker in a used world to try to import a custom map: regeneration would replace the built-in map areas. Importing third-party maps requires a separate tested conversion/integration.
