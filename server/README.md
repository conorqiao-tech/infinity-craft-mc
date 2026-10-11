# Infinity Armor Exploration Server — 2.12.0-explore.17

Multi-mode build based on Infinity Armor 2.3.0 and the crossplay preview. Updated October 4, 2026.

This server build lets Java and Bedrock players share **one Fabric server with shared game worlds** with Infinity Armor's Java powers. It is a crossplay preview based on your 2.3.0 source. Read VALIDATION.md for the checks and remaining play-test limits.

Select the named **Infinity Menu** recovery compass in your hotbar to open gear, powers, worlds and AI helpers. Every `/convergence` feature has a menu button; typing that command is optional. If the compass is in your main inventory, move it to the hotbar first. Tap or right-click an **INFINITY MENU** sign in the Main Hub or a mode lobby to reopen the menu and recover a missing compass. Clear one inventory slot if your inventory is full; the server never replaces your items to make room.

Choose **Minigames** or **Adventure maps**, then tap a course icon to play. `/guide` gives directions for your current location, `/best` shows personal times, and `/leaderboard <map>` shows saved minigame records. See [EXPLORATION.md](EXPLORATION.md) for navigation and scores.

## Lobby joining fix

This release fixes a saved-lobby chunk-loading loop that could freeze Minecraft on joining and disconnect Bedrock players with “End of stream” or “stream ended.” Decoration upgrades now wait until the chunk finishes loading, preserving existing blocks and edited signs.

## Admin health and stat editor

Huge health values now use a compact client health bar. Above 40 health capacity, the bar shows the remaining percentage on a 40-point display; displayed absorption stops at 40 and displayed hunger at 20. The actual server values, damage, modifiers, saved stats and exact numbers in the Admin editor remain unchanged. This prevents enormous edited values from asking Java or Bedrock to render an enormous heart bar.

Extremely large knockback keeps its saved attribute value, but each physical knockback impulse stops at 8. Native velocity and collision movement stop at 32 blocks per tick; invalid non-finite motion becomes stationary. These physics bounds prevent an enormous collision scan from freezing the server. Ordinary movement, a jump velocity of 15 and normal knockback remain unchanged. They are separate from the editable health, armor, damage and exact stat values.

Select **Infinity Menu → Admin editor • players and AI**. An Admin or OP4 can choose themselves, another online player or a loaded AI helper, select a stat, adjust the proposed value, then review and confirm it. Values do not change while browsing. The confirmation identifies the exact target and change; setting health to zero kills that target. A target that dies, disconnects, unloads or changes worlds must be selected again. AI behavior/profile changes also cancel an open review.

**To go above 20 health:** choose **Current health**, enter your number and confirm. When needed, the same review includes raising **Health capacity** first. Enter **Absorption** the same way; its zero default capacity no longer blocks direct entry. Capacity-only edits remain available and do not heal. For example, 100 health points equals 50 hearts.

Choose **My stats • quick edit** on the main menu to edit yourself directly. **Enter exact number** closes the chest for private chat input: type a value such as `5000`, then Review and Confirm. Type `cancel` to discard the input; it expires after 90 seconds. Plus/minus buttons update the same screen without reopening it.

The editor includes health, food, saturation, exhaustion, absorption, XP and every attribute supported by the player/helper. It preserves equipment and potion modifiers and shows the effective value. Admin-edited combat attributes have their normal caps removed: 5,000 health capacity or damage can take effect and save. Two health points equal one heart. Enter health or absorption directly: if needed, one confirmation also raises its effective capacity while preserving modifiers. Absorption retains its remaining amount across native save/reload. Armor above 30 affects native armor reduction, up to full armor protection; bypassing damage still applies. Movement, size, loot and interaction retain native engine/client bounds; XP fits native integer storage. Larger hunger reserves are one-time native edits, can be consumed or reset by gameplay, and may not display beyond the client's normal bar.

Attribute base changes persist across reconnects and respawns and apply in every game mode. **Reset original attribute** restores the base value from before this editor first changed it; resetting all attributes reviews the recorded changes first. Health, hunger and XP edits happen once and continue to follow normal damage, regeneration and the server's separate mode profiles. Normal gameplay is not frozen. The editor cannot run shell commands, read private keys or approve AI actions.

Registered AI golem helpers offer health, absorption and their supported combat/body attributes. Their edited bases and original-value reset records save together with the entity. Wild golems are not editable through this menu, and unloaded helpers are not force-loaded. Food and XP apply only to players. Existing AI follow/guard/profile controls remain in **AI Helpers**. Select a loaded helper and **Bring here** to recover it from another game world, preserve its health and stats, and resume Follow. Unloaded helpers require a visit to their saved area first.

For an exact value, an OP4 player can also use the optional command `/adminstats PlayerName set max_health 100`. This gives a base of 50 hearts; it does not also heal the player. `/adminstats PlayerName view` shows the stat IDs and values, and `/adminstats PlayerName reset max_health` restores the recorded original. Bedrock names include their prefix, such as `.PlayerName`. For a registered AI, use `/adminstats ai <entity UUID> view` or `/adminstats ai <entity UUID> set attack_damage 30`; the menu avoids needing its UUID.

## New blocks, tools, wardrobe and player trading

Six new building textures, two Creative building wands, two wearable cosmetic armor sets and three backpack looks join this release. Creative players receive weapons, tools, the full Infinity armor set, both complete cosmetic armor sets and a backpack directly in empty inventory slots. **Infinity Menu → Armor & tools → inventory** retries without replacing existing items. The complete **Convergence Set**, including blocks and ammo, is also available in two labeled shulker boxes. Place and open them to take the real items. **Infinity Menu → Convergence Set • inventory boxes** provides another set when two slots are free. They also receive an Infinity sword and can choose gear through **Infinity Menu → Weapons, tools and blocks**. **Building kit** supplies a compact set of blocks and both wands; `/convergence kit building` remains an optional shortcut. `/wardrobe` lists achievements for wearable looks, and `/backpack` opens personal 27-slot storage with a separate inventory in each mode. `/ptrade <player>` starts an item exchange requiring both players to review and confirm. Six minigames include Dropper, Red Light, Crystal Hunt, and Color Rush. See [EXPANSION.md](EXPANSION.md) and [PLAYER_TRADING.md](PLAYER_TRADING.md) for controls, unlocks and crossplay appearance limits.

## Helpers and operator controls

Operators at level 4 can select **Infinity Menu → AI Helpers • open your squad → Create your first helper**. Stand on clear solid ground: the menu closes and a named AI player avatar appears nearby, with a visible success or failure message. Helpers do not appear until you create them. Reopen **AI Helpers** to manage up to six named helpers, select six profiles and control the squad. Choose a helper to see **Status • profile, location, and health**, **Ask Codex** when connected, and **Back to your squad**. The squad screen has **Back to Infinity Menu**. The Ask button explains the selected helper's state and suggests a next step; `/agent ask <name> <question>` accepts your own question.

Ultimate Finals squads share targets, flank and predict short movements. On clear, loaded terrain they rotate a spear charge, a short native elytra glide and a mace switch before a falling smash. Low ceilings, walls or unsuitable landings use ordinary ground pursuit. Attacks retain reach, line of sight, target eligibility and cooldown checks. The gear uses vanilla weapon and gliding mechanics. Helpers now use native player avatars that show equipped weapons and elytra. Their server AI and physical collision remain golem-based; they are not authenticated Minecraft player accounts. Damage immunity remains; no totem bypass is promised.

In the stopped-world panel's **Owner: AI chat**, choose **Codex CLI · signed-in account** and enter the absolute path to an installed Codex executable already signed in with ChatGPT on this host. Enable AI chat and save, then start the world. No API key is required for this provider, and it always requires OP4. Codex uses the host account's usage allowance; this does not promise unlimited or free requests. When enabled, an OP4 player's `/ai <question>` and questions to **all six helper profiles** use Codex. `/ai help` stays local, ordinary players retain built-in help for known server topics, and `/ai status` shows the provider and request count to an OP4 player. The OpenAI API provider remains available with a private API key; a fresh download contains no key or login.

Questions and bounded server facts go to OpenAI. The integration requests text answers using those supplied facts, without giving a helper a server-command or environment-access interface. Its Codex replies are separate from this live Codex chat and cannot approve actions here. Server-changing proposals and exact player targets still need the OP4 owner's approval and a separate live Codex review before local-console dispatch. See [AGENTS_GUIDE.md](AGENTS_GUIDE.md) for setup, data sharing, limits and optional commands.

The Codex connection currently requires the audited native **CLI 0.155.1**; other versions stop pending compatibility review. Use its native executable rather than a Node-dependent launcher shim. Saving checks the executable path, while login/version readiness is checked when asking a question.

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
- **Infinity Menu did not open:** close other inventory screens, put away any item on the cursor, and select the named recovery compass from your hotbar. A lobby's **INFINITY MENU** sign or the optional `/menu` shortcut also opens it. If the compass is missing, leave one inventory slot free for its replacement. Choose **Weapons, tools and blocks**, then tap an icon to equip an Infinity item. Gear requires Creative or OP2; the built-in Bedrock Creative catalogue still hides custom items because the bridge cannot reliably move them into the hotbar from there.
- **I cannot see an AI helper:** creating and controlling helpers requires OP level 4. Open **Infinity Menu → AI Helpers • open your squad**, then tap the green **Create your first helper** button. Stand in an open area with solid ground and room for an iron golem. The menu closes so you can see the named golem or read why spawning failed. Existing helpers marked **Unloaded** need you to return nearby.

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

New players arrive at the Main Hub. Follow the bridges to five mode lobbies, then tap or right-click a hall's entry sign. The Minigames and Adventure signs open course menus; the other signs enter their worlds. `/hub`, `/lobbies`, `/lobby <mode>`, and `/play <mode>` remain available. The hub uses a separate inventory with its menu compass; mode inventories are saved and restored on each visit. Existing players stay where they were until they choose the hub. See **LOBBIES.md** for details.

Each lobby has a themed arch, colored glass, banners, lighted columns and planted accents made from vanilla blocks. The Main Hub adds a cyan glass and sea-lantern crown; Survival uses green and spruce, Creative purple and quartz, Hardcore red and blackstone, Minigames yellow and light blue, and Adventure orange and stone. Navigation and menu signs use glowing white text with **TAP TO OPEN** instructions.

Older hub saves receive these accents at startup or when a known lobby chunk loads. The upgrade adds blocks only in empty spaces above recognized original floors and preserves occupied blocks, containers and edited sign text. It does not rebuild terrain, move destinations or force-load distant chunks; modified or occupied areas may omit an accent.

## Game modes and memberships

In Minecraft, choose a world from **Infinity Menu**, use the hub signs, or use the optional **`/play`** command for Survival, Creative, Hardcore, Minigames, and Adventure. `/play minigames` and `/play adventure` open vanilla chest menus so you can tap a course icon. Inventories and Ender Chests stay separate. Hardcore is one life per player on its own Overworld. Six minigames and two starter adventure maps generate automatically. Your chosen course is restored if you reconnect; an unfinished run starts over. See **MODES.md** for commands and rules.

Ranks are **Free, Go, Plus, Pro, Ultra, and Admin**. Every mode is available on Free. Go through Ultra unlock permanently through **all three** of the rank's achievements, or through its Survival item trade. The achievement route is:

- **Go:** get a stone pickaxe, get an iron ingot, and kill a hostile mob (Monster Hunter).
- **Plus:** get an iron pickaxe, get a diamond, and enchant an item.
- **Pro:** enter the Nether, get a blaze rod, and enter the End.
- **Ultra:** defeat the Ender Dragon, enter an End gateway, and find an End city.

The highest permanent rank wins; lower groups are not prerequisites. `/rank` shows progress and missing achievements for the next rank. The Java server tracks these advancements for Java and Bedrock players who join through Geyser. Items taken in Creative can trigger item milestones, so the badges are not proof of Survival-only play. Previously earned ranks remain unlocked. Existing owner-granted temporary badge overrides last until their recorded expiry.

Admin is free and grants **full vanilla OP level 4**, gameplay unlocks and no Infinity weapon/power cooldowns. It includes vanilla commands such as `/give`, `/gamemode`, `/op` and `/stop`, helper control, instant mode/travel access and Hardcore return. The exact AI action/player-target approval and separate live Codex review remain required; mode inventories and confirmed trades are preserved. Fresh packages have Admin code redemption disabled; the owner can set a private code in the stopped-world host panel. Existing private code settings remain during upgrades, and existing saved Admin accounts receive OP4 on join. Use `membership revokeadmin PlayerName` to remove the role and its own OP promotion; independent OP grants need separate removal. See **MEMBERSHIPS.md**.

## Earned powers, cosmetics, and trades

Use `/rewards` for **six power presets and eight particle cosmetics**. Rewards have their own unlocks, independent of earned ranks. **How Did We Get Here?** unlocks `/power hacks` for flight, Night Vision, and Resistance II. **Withering Heights** unlocks `/cosmetic wither`. The other presets cover fire protection, a timed movement boost, exploration, underwater movement, and Nether travel. One preset can be active at a time. Ordinary players use powers in Survival; OP4, including Admin, bypasses the unlock and mode restriction. Use `/power off` or `/cosmetic off` to stop the selected effect. See [REWARDS.md](REWARDS.md) for every command and achievement.

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

Select **Infinity Menu → Play Creative** or use its lobby entry sign. Creative supplies an Infinity sword and still opens the earlier gear picker on entry. For all gear and controls afterward, select the **Infinity Menu** recovery compass. **Weapons, tools and blocks** opens a chest-like catalogue: tap a familiar vanilla icon to equip the actual Infinity item, preserving your previous held item. These controls require no extra client mod. Accept the server resource pack for Infinity textures.

| Infinity Menu button | Optional command | Access |
| --- | --- | --- |
| Weapons, tools and blocks | `/convergence gear` or `/convergence hold <item>` | Creative or OP2 |
| Full Infinity kit | `/convergence kit` | Creative or OP2 |
| Building kit | `/convergence kit building` | Creative or OP4 |
| Use held power | `/convergence power` | Hold the matching Infinity weapon or tool |
| Use alternate power | `/convergence altpower` | Hold the matching gear; includes the shield's Ward |
| Swap main hand and offhand | `/convergence swap` | Ordinary equipment rules |
| How weapons and tools work | `/convergence help` | Everyone |
| Server and joining information | `/convergence server` | Everyone |

To use a power, hold the weapon or tool first, then select **Infinity Menu**. The menu restores your last held hotbar item and shows it at the top; check that it is the intended tool. Tap **Use held power** or **Use alternate power**. The screen closes before the power runs. These buttons keep the targeting and equipment checks; ordinary players keep the original cooldowns, while Admin bypasses Infinity ability cooldowns. **Swap main hand and offhand** works with the equipment shown when the menu opened. If you change equipment while a screen is open, reopen the menu before acting.

For the assisted spear/mace combo, hold the spear, open **Infinity Menu**, and choose **Swap main hand and offhand**. Hold the mace, open the menu again, and choose **Use held power** to arm it. The original airborne targeting and timing checks still apply. Java right-click and sneak + right-click controls remain available.

Survival players can craft gear using the original recipes. The menu does not grant Creative equipment privileges or operator status. The host panel's **Setup progress & host commands** section accepts `op YourJavaName`; Bedrock names normally have a leading period, such as `.BedrockName` (spaces become underscores). Grant operator access only to trusted players. AI helper control requires OP4, even when another player can see the menu's AI Helpers button.

The server-only Java mod does not add a client Creative tab, and Bedrock's built-in Creative catalogue cannot reliably supply these custom items through the current bridge. Real Java and iPad playtests are still needed for the new menu, Creative inventory handling and weapon controls; automated server tests cannot establish the on-screen experience.

## Crossplay differences

The **Java implementation** runs the world and controls everyone's gear, crafting, damage, cooldowns, inventory and terrain changes. Its custom totem activates on eligible lethal damage; the shield has native blocking. Bedrock's separate Sanctuary and scripted bow/crossbow implementations are not used in this world.

All 40 custom item/block IDs are retained on the server. Twenty item appearances and ten placed block appearances have explicit Bedrock mappings. Bedrock uses native appearances for every wearable, the chestplate's elytra form, shield, totem, bow, crossbow and three arrows to retain native flight, blocking, death-protection and ranged controls. Cosmetic armor and backpacks also use dyed native leather equipment on Bedrock. Their Infinity names and server-side powers remain. Java receives the original item textures through the generated resource pack. The original 24 supplied texture files are unchanged. Bedrock's custom Infinity entries are omitted from its built-in Creative catalogue until the bridge can handle picking them up; the server's Infinity gear picker uses familiar vanilla icons to equip the actual items. `/convergence gear`, `/convergence hold <item>`, and `/convergence kit` remain available. Geyser also ties custom-output recipe-book entries to that Creative category, so some Infinity recipes may be hidden from Bedrock's recipe book; authenticated Bedrock crafting still needs a device test.

This is a preview, not a claim that every original visual and control behaves identically between editions. Actual Bedrock flight, shield blocking, shooting/reloading, offhand interaction, crafting, inventory movement, and multiplayer combat need an authenticated device play-test. A Java GameTest or Bedrock server ping does not establish those behaviors. See VALIDATION.md for exactly what was tested.

## Stop, save, and back up

Click **Save & Stop** and wait for **Ready to start**, then **Close launcher**. In console mode, type `stop` or press Ctrl+C. The launcher stops Geyser and asks Fabric to save the world and shut down. Run the same launcher to return to the same world.

World data lives under `fabric/world` by default. Back up the **entire `fabric` folder while stopped** before changing mods or trying an existing world. For an existing 2.3.0 Java world, copy a backup into a separate test server folder first. Bedrock worlds are not automatically converted. Keep using this server build to load a world containing its custom blocks and items.

Do not share `.dashboard.json`, `fabric/config/floodgate/key.pem`, `geyser/key.pem`, or runtime account files. The distributable ZIP contains none of these; the launcher copies the locally generated key between its own two services.

The panel is only reachable on this host (127.0.0.1) and protects its actions with a session token. The launcher manages its Geyser config, authentication mode and listener ports. Community settings selected in the panel also manage the name, slot limit, difficulty and allowlist on the next start. Edit other gameplay options in `fabric/server.properties` while stopped. Keep `online-mode=true`; Floodgate handles Bedrock authentication. `enforce-secure-profile=false` permits Bedrock's unsigned chat.

If startup fails, read `fabric/launcher.log` and `geyser/launcher.log`. If the launcher was forcibly killed, first ensure both Java processes are stopped before removing `launcher.lock`. Add other server mods only after the base pack works; if additional mods change Polymer's block allocations, the launcher regenerates and copies the mappings before starting Geyser.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

## Direct stats, gear names and AI player avatars in explore.14

**Infinity Menu → My stats** opens a native Bedrock/iPad button list. Select **Current health** or **Absorption**, type the number, submit, then tap **Confirm changes**. Any required capacity increase appears in that same confirmation. Manual edits require your own OP4 confirmation, without a separate Codex approval. Java retains its chest editor and private exact-number chat prompt. Absolute vitals edits tolerate natural health/hunger changes; changed attribute bases, ranges, permissions, sessions and reset originals still invalidate review.

**Infinity Menu → Armor sets** offers full Aurora and Ember sets including helmet, chestplate, leggings and boots. Leave four inventory slots free. Admin access bypasses achievement unlocks; other players earn Enchanter for Aurora or Into Fire for Ember. Bedrock wearables always use native dyed-leather models and icons. The Infinity winged chestplate uses a native elytra. Reconnect after the update and accept the refreshed server pack.

**Ranks & subscriptions** in Infinity Menu shows your actual badge, separate permanent rank, each rank’s three milestones and item-trade alternative. Admin/OP access does not turn your earned rank into Ultra. Planned USD subscriptions remain disabled. AI guidance receives your actual badge and permanent rank, rather than guessing from permission level.

All gear now has a literal human-readable item name on the server and client wire stack, preserving custom anvil names. Bedrock Infinity, Aurora and Ember wearable pieces use complete native equipment assets and icons. Java with the pack keeps original custom artwork. Accept the updated resource pack after joining; rendering on a real iPad and Java client still needs a device check.


## Helper robot skin in explore.15

Helpers share a charcoal and cyan robot skin with a silver face. The chest carries a small white ChatGPT knot and the blue-purple Codex cloud containing a tiny terminal prompt. Both are pixel approximations within Minecraft’s 8 by 12 pixel chest, rather than official OpenAI avatars. Native signed Minecraft texture data is sent to Java clients and Geyser; clients download the skin from Minecraft’s texture host. The server does not need a MineSkin account or API key at runtime. Reconnect after the update to refresh tracked helper profiles. Your saved helpers, owners, names, stats, equipment and orders are preserved. A chestplate can cover the chest emblems while equipped.
