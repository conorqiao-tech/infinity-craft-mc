# Achievement powers and cosmetics — 2.10.0-agents.1

Powers and cosmetics are separate from ranks. Free players can earn every reward, and a higher rank does not grant a power. The Java server checks its saved advancements for both Java and Bedrock players; Bedrock platform achievements are not used. An unlocked reward stays unlocked after reconnecting or restarting. A missing or revoked advancement does not erase an existing unlock.

Use `/rewards` for the full list, `/powers` for powers, and `/cosmetics` for cosmetics. Each listing shows whether that reward is locked. Plain commands work on both editions. Powers can also be unlocked through the Survival item trades in [TRADING.md](TRADING.md); cosmetics are earned from the achievements below.

## Operator access

Level-4 operators can activate any power or cosmetic without its achievements or trade receipt. Operator powers are available across game worlds and bypass combat waits and Windstep cooldowns. Ordinary-player earning and Survival rules still apply. Only one power preset is active at a time; vanilla `/effect`, `/give`, and `/gamemode` commands remain available to operators.

## Six power presets

Only one preset can be active at a time. Use `/power <id>` to activate an unlocked preset, and `/power off` to stop it. Selecting the same continuous preset again turns it off. Windstep is a timed cast rather than a toggle.

| ID and command | Earn by completing this Java advancement | Benefit |
|---|---|---|
| `/power hacks` | **How Did We Get Here?** — `minecraft:nether/all_effects` | Flight, Night Vision, and Resistance II. |
| `/power fireguard` | **Into Fire** — `minecraft:nether/obtain_blaze_rod` | Fire Resistance. |
| `/power windstep` | **Take Aim** — `minecraft:adventure/shoot_arrow` | Speed II and Jump Boost II for 15 seconds; 45-second cooldown. |
| `/power explorer` | **Adventuring Time** — `minecraft:adventure/adventuring_time` | Speed II, Jump Boost II, and Night Vision. |
| `/power aquatic` | **Tactical Fishing** — `minecraft:husbandry/tactical_fishing` | Water Breathing and Dolphin's Grace. |
| `/power nether` | **Hot Tourist Destinations** — `minecraft:nether/explore_nether` | Fire Resistance, Resistance I, and Night Vision. |

Earned powers work only while alive and playing **Survival** in Survival game mode, including Survival's Nether and End. They are disabled in Creative, Hardcore, Hub, Minigames, Adventure, and Spectator. Activate them again after leaving Survival, dying, or reconnecting. Unlocks remain permanent; active powers are temporary. This system uses ordinary server-controlled Minecraft abilities, not client cheats or operator permissions.

The movement presets **hacks, windstep, explorer, and aquatic** stop after damage and cannot be activated during the ten-second combat wait. Fireguard and Nether do not use that movement restriction. Windstep's cooldown survives reconnects and restarts. Its effect duration uses game ticks, so server lag can make the real-time effect longer; its cooldown also has a saved wall-clock expiry.

Flight uses Minecraft's normal flight input: double-tap Jump or the corresponding Bedrock flight control. It does not give blocks or Creative mode. Turning off flight while airborne removes the flight ability; land before turning it off.

Potions and Infinity armor still work. A preset does not replace a status effect already provided by another source. The server tracks the effects it grants and removes those layers when the preset stops, before mode profiles are captured, and during logout, death, and shutdown. Pre-existing effects and longer or stronger potion and armor layers applied afterward are preserved. Minecraft may discard a new, shorter effect of the same strength while a longer reward effect is active; the preset cannot restore an effect the game discarded. Turn the preset off first if you want to use that shorter buff. After an unclean stop, recorded temporary power layers are cleaned up when the player rejoins.

## Eight cosmetics

Use `/cosmetic <id>` to select one unlocked cosmetic and `/cosmetic off` to hide it. The selection is saved with the world and returns on reconnect. Selecting a cosmetic grants no damage, defense, flight, gear, or rank.

| ID and command | Earn by completing this Java advancement | Appearance |
|---|---|---|
| `/cosmetic wither` | **Withering Heights** — `minecraft:nether/summon_wither` | Wither aura. |
| `/cosmetic diamond` | **Diamonds!** — `minecraft:story/mine_diamond` | Diamond sparkle. |
| `/cosmetic dragon` | **Free the End** — `minecraft:end/kill_dragon` | Dragon aura. |
| `/cosmetic explorer` | **Adventuring Time** — `minecraft:adventure/adventuring_time` | Explorer trail while moving. |
| `/cosmetic totem` | **Postmortal** — `minecraft:adventure/totem_of_undying` | Totem halo. |
| `/cosmetic emerald` | **What a Deal!** — `minecraft:adventure/trade` | Emerald sparkles. |
| `/cosmetic ocean` | **Tactical Fishing** — `minecraft:husbandry/tactical_fishing` | Ocean bubbles. |
| `/cosmetic nether` | **Into Fire** — `minecraft:nether/obtain_blaze_rod` | Nether embers. |

Cosmetics use vanilla particles for crossplay. They are emitted only while alive and outside Spectator, twice per second, to nearby players in the same dimension. Each emission is limited to viewers within 24 blocks and at most 32 recipients. Particle appearance still needs an authenticated Bedrock device check; server-side tests do not establish how every client renders an aura.

The IDs `explorer` and `nether` each name both a power and a cosmetic. Use the `/power` or `/cosmetic` command to choose which one you mean.

## Progress and backups

Achievement unlocks and cosmetic selections are stored by authenticated UUID in the world's `infinity-rewards.json`, with a `.previous` copy after updates. Trade receipts are stored in each player's vanilla data file. Keep the whole matching world backup, including player data, rewards, memberships, and the game-mode profiles. Java and Bedrock accounts do not share progress merely because their names match.

Advancement checks run automatically every five seconds and when using reward commands. Existing completed advancements are backfilled. The original Infinity gear controls remain under `/convergence`; these commands activate the separate earned presets.
