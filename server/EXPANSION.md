# Infinity Armor expansion — 2.12.0-explore.8

Use the existing launcher and world. Your iPad can join the same server using the host panel's Bedrock address and accept its resource pack. Reconnect after an upgrade to refresh textures. No Minecraft installation is needed on the Mac host.

## Blocks and Creative tools

Six new original building styles join the four Infinity blocks: Aurora Tiles, Obsidian Lattice, Copper Circuit, Moonstone, Sunstone Lamp and Verdant Mosaic. The lamp emits light level 15. Each has its own Java texture and exported Geyser block state.

The **Builder Wand** places a 3×3 plane using the block held in your offhand. The **Sculptor Wand** clears a 3×3 plane with no drops. Both require actual Creative mode; ordinary players use the Creative world, and OP4 can use them elsewhere while in Creative. They protect containers, unbreakable blocks and occupied placement cells. Normal Infinity tools retain their powers in Creative.

On Java or iPad, choose **Infinity Menu → Play Creative**, use its lobby sign, or type `/play creative`. The server gives you an Infinity sword and opens a chest-like gear picker. Tap a vanilla icon for the wand, weapon, or tool you want; the server equips the real Infinity item. Selecting the **Infinity Menu** recovery compass opens the full menu; choose **Weapons, tools and blocks** to reopen the catalogue. **Building kit** gives Creative players or OP4 the blocks and wands, **Use held power** activates a held tool, and **Swap main hand and offhand** moves a selected building block to the offhand. Hold the intended item before selecting the compass, then check the menu's held-item label. All `/convergence` controls remain optional shortcuts. The Bedrock built-in Creative catalogue cannot reliably move custom items into a hotbar on the current bridge; the server-only Java mod does not add a client Creative tab. The menus still need real Java and iPad playtests.

Admin is a free staff role that now grants actual vanilla OP4 through the private code or an owner grant. It receives all operator gameplay unlocks plus no Infinity weapon/power cooldowns; valid equipment and the building wands' Creative requirement remain. Full vanilla command access does not waive both approvals for an AI action or a named-player attack. See [MEMBERSHIPS.md](MEMBERSHIPS.md).

## Themed lobbies

The Main Hub and five mode lobbies now have vanilla-block arches, colored glass, lighted columns, banners and planted accents. Each mode has its own palette, and the Main Hub has a cyan glass/sea-lantern crown. Navigation and **INFINITY MENU** signs glow with **TAP TO OPEN** instructions. Existing saves gain accents at startup or when a known lobby chunk loads: only empty spaces above recognized original floors are filled, occupied blocks and edited sign text are preserved, and the existing terrain and destinations are not rebuilt. An occupied or modified area can omit an accent.

## Earnable wardrobe and backpacks

`/wardrobe` lists your unlocks. These appearances are independent of ranks and power bundles:

| Command | Unlock | Appearance |
| --- | --- | --- |
| `/backpack claim trail` | Acquire Hardware: smelt iron | Teal Trail Backpack |
| `/backpack claim emerald` | What a Deal!: villager trade | Green Emerald Backpack |
| `/backpack claim dragon` | Free the End: defeat dragon | Purple Dragon Backpack |
| `/wardrobe aurora` | Enchanter | Four Aurora armor pieces |
| `/wardrobe ember` | Into Fire | Four Ember armor pieces |

Equip the backpack in your chest armor slot, or equip costume armor in normal armor slots. They replace the equipment in those slots and grant **no armor attributes, flight, or rank powers**. Java uses custom worn artwork; iPad Bedrock uses dyed vanilla leather armor to preserve reliable native equipment controls. The backpack is a textured back panel on Java and dyed leather clothing on Bedrock; it is not a protruding 3D bag. OP4 and players in the Creative world can claim every appearance.

`/backpack` opens a vanilla 27-slot chest. All styles access the same personal backpack **within one mode**. Survival, Hardcore and Creative have separate storage. You cannot open it in the hub, minigames, adventure maps or spectator mode. Close containers before switching modes or trading. Storage survives death and is saved in the same vanilla player file as your inventory. An accessory contains no items: copying, dropping or trading it never copies storage or transfers your storage to someone else. A recipient needs their own achievement before opening personal storage. Keep backups of the entire world and player data together.

## Player trading

Use `/ptrade <player>` and have the other player `/ptrade accept`. Both must be nearby and playing Survival. Offers stay in your inventory until both players confirm the same revision. The review chest cannot be used as storage. See [PLAYER_TRADING.md](PLAYER_TRADING.md) for slots, confirmation and crash recovery. Existing `/trade` commands still buy rank or power unlocks from the server using items.

## Six minigames

Tap or right-click the Minigames entry sign in the hub, or use `/play minigames`, to open the chest-like course menu. Tap a vanilla icon to start. A **SELECT COURSE** sign at each course start reopens the menu; the direct commands below remain available. Your selected map is restored when you reconnect, while an unfinished run starts over. The menu and sign controls still need a real iPad playtest.

- `/minigame parkour` — Sky Steps checkpoint parkour.
- `/minigame sprint` — Switchback Sprint checkpoint race.
- `/minigame dropper` — steer through obstacles into the landing water.
- `/minigame redlight` — advance on green, stop on red. Text labels accompany colors; a short transition grace period accommodates touch controls.
- `/minigame crystalhunt` — touch five glowing amethyst pads once each, in any order.
- `/minigame colorrush` — reach the color named in the action bar within five seconds for each of five pulses.

`/retry <map>` restarts a run. Existing adventure maps remain `/adventure ruins` and `/adventure maze`. Games have independent run state and saved personal scores, with separate mode inventories. Adding the new courses retains old maps and progress.

## Validation limits

The release checks native server behavior, inventory persistence, trade save/recovery, block mappings, launcher lifecycle and the Bedrock UDP listener. A graphical iPad login and visual inspection of the new artwork/menus on a real device remain a separate playtest. This is a local shared server, not a measured large public network.
