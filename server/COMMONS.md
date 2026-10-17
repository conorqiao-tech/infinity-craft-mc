# Circuit Keepers' Commons — explore.40

Builder Exchange and Harvest Together turn two owner-selected holiday proposals into permanent, playable activities. **Expedition Atlas** and **Pack Organizer** are additional permanent features. They have no paid entry, daily streak, forced end date or scheduled console commands. Source implementation, a validated package and installation on the playable server are separate stages.

## Builder Exchange

Open **Infinity Menu → Commons → Builder Exchange**. Join Creative first. Aim at clear ground and select **Review Exchange blueprint**, then confirm through the existing Studio preview. The small copper-circuit pavilion uses original Infinity blocks, a cyan lattice roof and sunstone corner lanterns. Placement preserves occupied cells and living entities, and Studio Undo can restore the edit. A successfully completed Exchange pavilion registers its workshop origin automatically. You can instead register the location where you stand to use a workshop you built yourself; this changes no blocks.

The menu lists exact coordinates relative to that origin. Build a redstone block, two powered dust segments and a lamp on a solid floor. Put a downward, enabled hopper above an empty barrel three blocks to the side. Five free inventory slots are needed for the optional Creative material kit.

**Verify powered circuit** reads the actual native dust and lamp state. **Run live hopper delivery trial** places one uniquely tagged paper test parcel into the empty hopper. Normal Minecraft hopper ticks must move it into the barrel while the circuit stays lit. Stay in Creative near the same loaded workshop for up to ten seconds. Switching sessions/worlds, leaving range, disabling the hopper or breaking the circuit stops the attempt. The attempt is temporary; already completed milestones persist. Cleanup removes only that attempt's tagged parcel from its two loaded containers, never ordinary stored items. A parcel moved elsewhere is harmless paper with no trade value or completion authority.

A completed prototype unlocks a one-time circuit banner, named diamond pickaxe and sixteen redstone, requiring three free inventory slots. It also unlocks the **DevDay Builder** title and lantern motes. There is no automatic judging of arbitrary inventions or unimplemented community voting system.

## Harvest Together

Open **Infinity Menu → Commons → Harvest Together** in Survival or Hardcore. Grow and cook your own supplies, then choose a food to review. The exact quantity is shown before confirmation. The review binds the player, connection, world, mode, prior contribution and all 36 main inventory slots. Changed inventory refuses the delivery without consuming anything. Only ordinary matching food is accepted; named, modified and owned custom items are preserved.

| Food | Personal contribution cap / village target |
| --- | ---: |
| Wheat | 32 |
| Carrots | 16 |
| Potatoes | 16 |
| Beetroot | 16 |
| Bread | 8 |
| Baked potatoes | 8 |
| Cookies | 8 |
| Pumpkin pie | 4 |

Players contribute toward one village pantry. Personal receipts and consumed inventory share the player's native save. A separate native saved-data index aggregates each UUID's capped contributions monotonically, so repeat synchronization cannot credit them twice. It contains no inventories and does not authorize personal reward claims. A saved personal receipt can repopulate a lagging index at the next join. This does not promise a transaction across a catastrophic disk/storage failure or independently restored world/player files.

After sixteen donated items, claim a field hoe, sixteen bone meal and a personal **Seedlight Jar**. Use the jar to release a brief emerald-and-gold firefly spiral; it has no combat effect and cannot be activated by another account. Ordinary glass bottles keep ordinary behavior.

After at least sixty-four personally donated items **and** all village categories are full, claim the original golden-harvest banner, named diamond hoe and sixteen golden carrots. This unlocks the **Harvest Guardian** title and garden motes. Each complete gift needs three free slots. A full inventory leaves it unclaimed; replaying a claim grants nothing.

The optional **lantern garden blueprint** is a Creative Studio build with original green mosaics, moonstone supports, warm lights, four real crop plots and center irrigation. Aim at clear ground, review and confirm. It does not grant free food in Survival or silently replace existing farms.

## Expedition Atlas

Open **Infinity Menu → Expedition Atlas & Pack Organizer**. Visit each of eight biome families in Survival or Hardcore and press **Stamp the biome I am actually in**. The server reads your loaded biome and saves its identifier and coordinates. Repeating a family does not count again; Creative cannot stamp progress.

Explore plains/meadows, forests/jungles, deserts/badlands, snow/ice, oceans/rivers, deep caves, the Nether and the End. All eight unlock a one-time compass, original circuit banner and spyglass, plus the **Wayfinder** title and star motes. The compass remains Minecraft's native spawn compass; it does not invent a route or load unexplored terrain. Three empty inventory slots are required for the gift.

## Pack Organizer

Choose **Pack Organizer** from the Commons or Atlas. Review the proposed arrangement, then confirm. It sorts only the 27 inventory slots below your hotbar and merges only stacks with identical native components. It preserves names, damage, dyed items, owned controls and nested container/backpack contents. Your hotbar, armor, offhand and ender chest remain in place. A changed reviewed slot, connection, world or mode cancels the operation. It never drops items into the world or transfers them to someone else.

## Menus, appearance and persistence

Java uses native chest controls with untakeable icons. Bedrock uses native Floodgate button forms, with delayed delivery after closing a chest, expiry and replay/session checks. Both interfaces use the same action definitions. New banners are original compositions of native patterns; new buildings use existing original Infinity block artwork. No vanilla armor, elytra or tool texture is overridden. The ordinary-elytra repair is retained.

Earned titles appear in the player list and add a small cosmetic particle effect. They never change rank/combat teams, OP, permissions or AI approvals. **Hide Commons title and motes** removes the effect. Player progress and one-time claims survive ordinary saves/reconnects/restarts and mode inventory changes. In-progress workshop trials and unconfirmed menus are intentionally canceled when their session ends.

## Safe acceptance checks

Use disposable Java worlds with the pinned Minecraft 26.3 / Java 25 / Fabric/Geyser stack. Check real lamp/dust state and native hopper ticks; two distinct pantry contributors; exact consumption, replay and full-inventory refusals; native save/reload; actual encoded Bedrock form responses; named/filled inventory sorting; original blueprint placement/undo; plain bottle behavior and personal jar ownership. Authenticated iPad/Java rendering, touch input and multiplayer load still require real devices after a separately approved installation. See VALIDATION.md for actual results; this guide is not evidence that tests passed or the update is installed.

## Regressions found during development

| Reproduction | Expected / actual before correction | Root cause and correction | Native regression |
| --- | --- | --- | --- |
| Open two identical Bedrock delivery reviews in one server tick, then answer the older form. | The old answer must do nothing; it could consume the current offer. | Structurally equal form tickets shared the same fields. Every ticket now includes a fresh UUID. | `bedrockConfirmRejectsOldFormsForeignSessionsAndChangedInventory` |
| Review an organizer arrangement with long names and click its preview. | All proposed items must remain visible and untakeable; a text-only guide could truncate the arrangement and share positions with action buttons. | Java now displays the exact 27-item arrangement in slots 9–35, with independent confirmation buttons at 45 onward. | `organizerPreviewIsUntakeableAndNeedsSeparateConfirm` |
| Stamp a cherry grove in the Atlas. | It should count as forest; the generic word “grove” selected the snow family. | The specific cherry-biome rule precedes the generic snow/grove rule. | `atlasRecordsActualBiomeOnceAndCreativeCannotSpoofIt` |
| Change an existing team prefix while wearing a personal title. | The title display should refresh without replacing the combat team. | Badge refresh compares the current native display value and sends an update only when it changes; external teams are retained. | `earnedTitleRefreshesTeamPrefixWithoutChangingCombatTeam` |

Early native fixtures also needed the real lamp placement context, normal screen closure before a button action and three free reward slots after Creative's automatic toolbox delivery. Those fixture corrections are not presented as game fixes. Rejected runs are kept separately from accepted evidence. The final persistence checks read actual native player files for delivered inventory, donation receipts, Atlas stamps and organizer contents, rather than relying only on an in-memory round trip.
