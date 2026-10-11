# Infinity Armor Exploration Server — 2.12.0-explore.7

Multi-mode build based on Infinity Armor 2.3.0 and the crossplay preview. Updated October 1, 2026.

This server build lets Java and Bedrock players share **one Fabric server with shared game worlds** with Infinity Armor's Java powers. It is a crossplay preview based on your 2.3.0 source. Read VALIDATION.md for the checks and remaining play-test limits.

Start with `/guide` in Minecraft to find the Main Hub and the current mode. Tap or right-click the Minigames or Adventure entry sign in the hub to open a chest-like course menu. `/best` shows your personal times; `/leaderboard <map>` shows the fastest saved times for each minigame. See [EXPLORATION.md](EXPLORATION.md) for navigation and scores.

## New blocks, tools, wardrobe and player trading

Six new building textures, two Creative building wands, two wearable cosmetic armor sets and three backpack looks join this release. Creative players receive an Infinity sword and a compass that opens the gear picker automatically. `/convergence kit building` remains an optional compact building kit. `/wardrobe` lists achievements for wearable looks, and `/backpack` opens personal 27-slot storage with a separate inventory in each mode. `/ptrade <player>` starts an item exchange requiring both players to review and confirm. Six minigames include Dropper, Red Light, Crystal Hunt, and Color Rush. See [EXPANSION.md](EXPANSION.md) and [PLAYER_TRADING.md](PLAYER_TRADING.md) for controls, unlocks and crossplay appearance limits.

## Helpers and operator controls

Use `/ai <question>` for built-in server help. Optional OpenAI chat is configured in **Owner: AI chat** while the world is stopped; a fresh download contains no API key. Operators at level 4 can use `/agent menu` to manage up to six named helpers, select six AI profiles and control the squad. Create helpers with `/agent spawn <name>` and control them with `/agent follow`, `/agent guard`, `/agent stay`, `/agent dismiss`, and `/agent list`. Separately, OP4 owners can propose one of six fixed helper actions, such as setting daytime or saving the world. A proposal never runs automatically: the OP4 owner approves its ID, then a live Codex session must review it and dispatch the fixed command from the local console before it expires. See [AGENTS_GUIDE.md](AGENTS_GUIDE.md) for the steps and limits.

## Three steps to play

1. **Extract the ZIP** into a folder you will keep. On Windows, choose **Extract All** first.
2. **Open the launcher:** `Start-Mac.command` on Mac, `start.bat` on Windows 10/11, or `bash start.sh` on Linux.
3. In the browser panel, read the Minecraft EULA, check the agreement box if you agree, and click **Start shared world**. Wait for **World is running**.

The launcher downloads its own Python and, if needed, Java 21 into `.runtime` in this server folder. It verifies the downloads and does not need administrator access or change system settings. No manual Java, Python, Docker, or mod installation is required on the supported computers. You need an internet connection for the first setup. Keep the Terminal/launcher window open while playing; closing just the browser tab does not stop the world.

**The panel handles the rest:** copy the Java/Bedrock join addresses, change memory or ports in **Settings**, read setup progress, and click **Save & Stop** when finished. Your settings are remembered. Double-clicking the launcher again reopens the same panel. After saving, **Close launcher** shuts down the panel; open the same launcher next time to use the same world.

The first Python download appears in Terminal before the browser opens. Subsequent launches use the files already downloaded. The default gives the world a 2 GB Java heap and Geyser another 512 MB; leave additional memory for the OS and your Minecraft client. Choose a higher world memory limit in Settings only if your computer has enough available memory.

Automatic setup supports Mac Apple silicon/Intel, Windows x64, and Linux x64/arm64 with glibc, `curl`, and `tar`. Windows ARM is a manual-setup case. Runtime downloads are pinned in `runtime.lock.json`; server components are pinned in `dependencies.lock.json`. See THIRD_PARTY.md for publishers and licenses.

### If something does not work

- **Nothing in the browser:** use the control-panel address printed in the launcher window. Opening the launcher again also reopens the panel.
- **Port already in use:** stop the other Minecraft server or choose different ports in Settings.
- **A friend on your Wi-Fi cannot connect:** allow Java through the private-network firewall and use the LAN address in the panel. Guest Wi-Fi may block device-to-device connections. A VPN or multiple network adapters may require using your Wi-Fi adapter's IPv4 address manually.
- **A friend outside your home cannot connect:** on an Apple Silicon Mac, keep the world running and open `Start-Pinggy-Mac.command`. Share the latest separate Java and Bedrock addresses shown in the panel. These free addresses expire, so see [PINGGY_JOINING.md](PINGGY_JOINING.md) before sharing them.
- **You want one fixed joining name:** use the optional [Dynu setup guide](DYNU_JOINING.md), reserve your own hostname, forward the two game ports, and open `Start-Dynu-Mac.command`. DNS updating requires an account and router setup; test both editions externally before sharing the name.
- **Setup download fails:** read the error, check the internet connection, then start again. Completed verified downloads are reused.
- **The launcher was forcibly closed:** make sure both Java processes have stopped before removing `launcher.lock`. A leftover `.runtime/bootstrap.lock` can be removed only after the Python setup process has stopped. Ordinary Save & Stop cleans up automatically.
- **The panel was closed:** the world keeps running. Open the launcher again to return to the panel and stop it safely.
- **Creative Infinity picker did not open:** switch to another hotbar slot and back to the named compass, or use `/convergence gear`. Tap an icon in the picker to equip that Infinity item. If the picker is unavailable, `/convergence hold sword` still equips the sword directly. The custom items remain hidden from Bedrock's built-in Creative catalogue because the current bridge cannot reliably move them into the hotbar from there.

### Optional terminal controls

The browser is optional. To use the console with the automatic runtime:

```sh
bash start.sh --console
```

Or use an existing Python 3.9+ directly:

```sh
python3 server.py --dashboard
python3 server.py --console --java /path/to/java --memory 4G
```

`--setup` downloads and verifies server files and prepares Java without starting a world or accepting the EULA. `--check` only checks installed files and Java. `--no-browser` prints the panel address. `--java-port`, `--bedrock-port`, and `--bind 127.0.0.1` customize console mode. The panel uses its saved Settings. Use `--accept-eula` only if you agree to https://www.minecraft.net/eula and want to skip the interactive agreement.

## Main Hub and mode lobbies

New players arrive at the Main Hub. Follow the bridges to five mode lobbies, then tap or right-click a hall's entry sign. The Minigames and Adventure signs open course menus; the other signs enter their worlds. `/hub`, `/lobbies`, `/lobby <mode>`, and `/play <mode>` remain available. The hub uses a separate empty inventory; mode inventories are saved and restored on each visit. Existing players stay where they were until they choose the hub. See **LOBBIES.md** for details.

## Game modes and memberships

In Minecraft, use the hub signs or **`/play`** for Survival, Creative, Hardcore, Minigames, and Adventure. `/play minigames` and `/play adventure` open vanilla chest menus so you can tap a course icon. Inventories and Ender Chests stay separate. Hardcore is one life per player on its own Overworld. Six minigames and two starter adventure maps generate automatically. Your chosen course is restored if you reconnect; an unfinished run starts over. See **MODES.md** for commands and rules.

Ranks are **Free, Go, Plus, Pro, Ultra, and Admin**. Every mode is available on Free. Go through Ultra unlock permanently through **all three** of the rank's achievements, or through its Survival item trade. The achievement route is:

- **Go:** get a stone pickaxe, get an iron ingot, and kill a hostile mob (Monster Hunter).
- **Plus:** get an iron pickaxe, get a diamond, and enchant an item.
- **Pro:** enter the Nether, get a blaze rod, and enter the End.
- **Ultra:** defeat the Ender Dragon, enter an End gateway, and find an End city.

The highest permanent rank wins; lower groups are not prerequisites. `/rank` shows progress and missing achievements for the next rank. The Java server tracks these advancements for Java and Bedrock players who join through Geyser. Items taken in Creative can trigger item milestones, so the badges are not proof of Survival-only play. Previously earned ranks remain unlocked. Existing owner-granted temporary badge overrides last until their recorded expiry. Admin is free and gives limited moderation tools. Fresh packages have Admin code redemption disabled; the owner can set a private code in the stopped-world host panel. Existing private code settings remain during upgrades. See **MEMBERSHIPS.md**.

## Earned powers, cosmetics, and trades

Use `/rewards` for **six power presets and eight particle cosmetics**. Rewards have their own unlocks, independent of ranks. **How Did We Get Here?** unlocks `/power hacks` for flight, Night Vision, and Resistance II. **Withering Heights** unlocks `/cosmetic wither`. The other presets cover fire protection, a timed movement boost, exploration, underwater movement, and Nether travel. One preset can be active at a time and powers work only in Survival. Use `/power off` or `/cosmetic off` to stop the selected effect. See [REWARDS.md](REWARDS.md) for every command and achievement.

`/trades` lists **ten permanent item trades**: four rank offers and one for each power. `/trade <id>` previews a cost without spending; `/trade <id> confirm` pays only in Survival, outside combat. Payment counts ordinary main-inventory/hotbar stacks. An insufficient or already unlocked offer takes no items. The earned ranks remain free and permanent. Optional USD supporter subscriptions are planned, with no checkout or paid grants active yet. See [TRADING.md](TRADING.md) for item costs and [SUBSCRIPTIONS.md](SUBSCRIPTIONS.md) for the proposed monthly prices.

Upgrading an existing community server: Save & Stop, close the old launcher, and make a full private backup first. Preserve `fabric/world` (or your selected world), `fabric/config`, EULA, keys, and `settings.json`. Replace launcher files with this release and replace the old `Infinity-Armor-*.jar` with the new JAR in `fabric/mods`; do not leave both versions installed. Use the same folder to retain your world. The new mod creates the hub and its extra dimension on startup. Keep using this mod to load those dimensions and player profiles.

## Community features

This build adds the familiar survival-server features for both editions:

- **Welcome plaza:** an operator can run `/community buildspawn` in a clear 25×25 grassy area to create a lit plaza and set the shared spawn. Grass/dirt/snow are reshaped; other blocks and containers cause it to stop before building. Use `/community setspawn` for a manually built spawn.
- **Homes:** `/sethome [name]`, `/home [name]`, `/homes`, and `/delhome <name>`. Each player's UUID owns up to three named homes, saved with the world.
- **Shared destinations:** `/spawn` returns to the current game mode's spawn, while `/hub` opens the Main Hub. Use `/warps` and `/warp <name>` for operator-created destinations.
- **Visits with consent:** `/tpa <player>`, `/tpaccept`, and `/tpdeny`. Requests expire after 30 seconds. Teleports take 3 seconds, cancel on movement/damage, check for a safe destination, and have a 10-second cooldown. Damage also prevents teleporting for 10 seconds.
- **Rules and welcome messages:** `/rules` and `/serverhelp`. Edit the server name and rules in the panel; changes apply on the next start.
- **Staff tools:** the panel provides kick, ban/unban, public-chat mute/unmute, and allowlist actions. `/community broadcast <message>` sends an announcement. `/community mute <player> <minutes>` persists a timed public-chat mute across restarts. Direct messages remain available.
- **Host controls:** default 32 player slots, configurable name/difficulty/allowlist, live player list and tick time, and stopped-world backups. Slot limits are not a performance guarantee.

Every start of an existing world makes a backup before loading it. **Back up stopped world** makes another copy manually. Backups are kept under `backups/`; copy important ones to another disk and manage disk space. There is no automatic deletion or scheduled live backup.

For public access, 24/7 hosting, initial staff setup, spawn protection limits, and restoring backups, read **HOSTING.md**. The package is not already hosted on the Internet. It includes fixed item exchanges, but does not include land claims, anti-cheat, player shops, a currency economy, or large-network matchmaking.

## Join the same world

| Player | Address | Port |
|---|---|---|
| Java **1.21.11**, on the host | `localhost` | `25565` |
| Java **1.21.11**, another device | Host computer's LAN IP | `25565` |
| Bedrock **26.30–26.51** | Host computer's LAN IP | `19132` |

Java players can join using an ordinary Java 1.21.11 client; accept the offered server resource pack for Infinity textures. **Do not install this server-only JAR in a Java client.** Use a clean Java profile for the initial test. The original 2.3.0 Fabric client package remains a separate single-edition distribution.

On Bedrock mobile/Windows, choose **Servers → Add Server** and enter the host's IP and port. Accept the required Infinity resource pack. Bedrock players sign in with their Microsoft/Xbox account; Floodgate lets them join without a separate Java license. Do not activate the separate Infinity `.mcaddon` in this Java-hosted world; its behavior scripts do not run through Geyser.

Xbox, PlayStation and Switch do not expose the same Add Server UI; see [Geyser's console connection instructions](https://geysermc.org/wiki/geyser/using-geyser-with-consoles/). For players outside your home network, use the optional [Pinggy joining helper](PINGGY_JOINING.md) on an Apple Silicon Mac, or forward **TCP 25565** and **UDP 19132** to the host (or the ports you selected) and allow them through its firewall. The optional [Dynu companion](DYNU_JOINING.md) keeps an owner-reserved hostname pointed at your home's public IPv4. Port forwarding is not performed by either launcher.

The launcher uses Geyser **2.11.3 build 1245**, reporting support for Bedrock 26.30–26.51 when checked on September 22, 2026. Bedrock updates may require a new bridge build; dependencies deliberately do not silently upgrade your world. See [Geyser supported versions](https://geysermc.org/wiki/geyser/supported-versions/).

## Get and use Infinity gear

Enter the shared Creative world through its lobby or `/play creative`. The server puts an Infinity sword in your hand, adds a named compass to the hotbar, and opens an Infinity gear picker. Tap a familiar vanilla icon in that chest-like menu to equip the real Infinity weapon, tool, or building wand. To reopen it, switch to another hotbar slot and back to the compass. This requires no operator access or extra gear command; real Java and Bedrock client playtests are still needed. The server-only Java mod does not add a client Creative tab, and Bedrock's built-in Creative catalogue cannot reliably supply these custom items through the current Geyser bridge.

If the picker does not open, use `/convergence gear`. `/convergence hold sword` still equips one item directly; replace `sword` with `mace`, `spear`, `pickaxe`, `axe`, `shovel`, `hoe`, `builder_wand`, or `sculptor_wand`. `/convergence kit` gives the full set in Creative or to an operator with level 2 or higher. Survival players can craft the gear using the original recipes. Accept the server resource pack to see Infinity textures. Grant operator access only to trusted players; the host panel's **Setup progress & host commands** section accepts `op YourJavaName`, and Bedrock names normally have a leading period, such as `.BedrockName` (spaces become underscores).

These commands work for ordinary players holding the corresponding gear; they do not give items or bypass cooldowns:

- `/convergence power` — the held Infinity weapon/tool's normal Use power.
- `/convergence altpower` — its crouch + Use power; also casts the Infinity Shield's Ward.
- `/convergence swap` — swap main hand and offhand, including putting the spear in the offhand for the combo.
- `/convergence server` — show connection defaults and these controls.
- `/convergence help` — show the original gear controls.

The power commands are useful on touch devices whose controls do not show a Use button for a custom item. Java right-click controls remain available. For the assisted spear/mace combo, put the spear in the offhand, hold the mace, and use `/convergence power` to arm; the original airborne targeting/timing checks still apply. A real iPad and Java client still need to verify the picker, Creative inventory handling, and weapon controls after this change; automated server tests cannot prove the on-screen experience.

## Crossplay differences

The **Java implementation** runs the world and controls everyone's gear, crafting, damage, cooldowns, inventory and terrain changes. Its custom totem activates on eligible lethal damage; the shield has native blocking. Bedrock's separate Sanctuary and scripted bow/crossbow implementations are not used in this world.

All 40 custom item/block IDs are retained on the server. Twenty-three item appearances and ten placed block appearances have explicit Bedrock mappings. Bedrock uses native appearances for the chestplate's elytra form, shield, totem, bow, crossbow and three arrows to retain native flight, blocking, death-protection and ranged controls. Cosmetic armor and backpacks also use dyed native leather equipment on Bedrock. Their Infinity names and server-side powers remain. Java receives the original item textures through the generated resource pack. The original 24 supplied texture files are unchanged. Bedrock's custom Infinity entries are omitted from its built-in Creative catalogue until the bridge can handle picking them up; the server's Infinity gear picker uses familiar vanilla icons to equip the actual items. `/convergence gear`, `/convergence hold <item>`, and `/convergence kit` remain available. Geyser also ties custom-output recipe-book entries to that Creative category, so some Infinity recipes may be hidden from Bedrock's recipe book; authenticated Bedrock crafting still needs a device test.

This is a preview, not a claim that every original visual and control behaves identically between editions. Actual Bedrock flight, shield blocking, shooting/reloading, offhand interaction, crafting, inventory movement, and multiplayer combat need an authenticated device play-test. A Java GameTest or Bedrock server ping does not establish those behaviors. See VALIDATION.md for exactly what was tested.

## Stop, save, and back up

Click **Save & Stop** and wait for **Ready to start**, then **Close launcher**. In console mode, type `stop` or press Ctrl+C. The launcher stops Geyser and asks Fabric to save the world and shut down. Run the same launcher to return to the same world.

World data lives under `fabric/world` by default. Back up the **entire `fabric` folder while stopped** before changing mods or trying an existing world. For an existing 2.3.0 Java world, copy a backup into a separate test server folder first. Bedrock worlds are not automatically converted. Keep using this server build to load a world containing its custom blocks and items.

Do not share `.dashboard.json`, `fabric/config/floodgate/key.pem`, `geyser/key.pem`, or runtime account files. The distributable ZIP contains none of these; the launcher copies the locally generated key between its own two services.

The panel is only reachable on this host (127.0.0.1) and protects its actions with a session token. The launcher manages its Geyser config, authentication mode and listener ports. Community settings selected in the panel also manage the name, slot limit, difficulty and allowlist on the next start. Edit other gameplay options in `fabric/server.properties` while stopped. Keep `online-mode=true`; Floodgate handles Bedrock authentication. `enforce-secure-profile=false` permits Bedrock's unsigned chat.

If startup fails, read `fabric/launcher.log` and `geyser/launcher.log`. If the launcher was forcibly killed, first ensure both Java processes are stopped before removing `launcher.lock`. Add other server mods only after the base pack works; if additional mods change Polymer's block allocations, the launcher regenerates and copies the mappings before starting Geyser.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
