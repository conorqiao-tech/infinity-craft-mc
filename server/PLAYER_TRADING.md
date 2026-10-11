# Player-to-player trades

Players can exchange items in Minecraft's ordinary chest interface. Both players must confirm the **same current offers** before any item leaves either inventory. This is separate from `/trade`, which unlocks server ranks and powers.

1. Stand within eight blocks of each other in the same Survival world, outside combat. Close backpacks and other storage, and put away any item held on the cursor.
2. Send `/ptrade PlayerName`. The other player uses `/ptrade accept`.
3. Select items with `/ptrade offer <slot> <count>`. Slots **1–9** are the hotbar from left to right; **10–36** are the main inventory in row order. For example, `/ptrade offer 1 4` offers four items from the first hotbar slot. Each player may offer up to 18 different slots. `/ptrade remove <slot>` removes that slot from the offer.
4. Review the chest: rows 1–2 show your offer; rows 4–5 show your partner's offer. The displayed items are copies for review. They cannot be picked up, dragged, shift-clicked, dropped, or swapped.
5. Each player clicks the green **Confirm offer revision** button, or types the exact `/ptrade confirm <revision>` shown in chat. The exchange happens only after both confirm that revision. Changing an offer clears both confirmations and opens a fresh review window.

The red button or `/ptrade cancel` cancels. Closing the review also cancels. Until both confirmations, items remain in their owners' main inventories. You may exchange named, enchanted, damaged, and custom items; their components are preserved. Armor, offhand items, Ender Chest contents, and backpack contents must first be moved into the main inventory.

Both players must stay in the Survival profile **and** native Survival game mode, including operators. Creative inventories cannot enter this trade route. Combat, a pending mode change, death, disconnection, moving too far away, entering another dimension, or changing an offered source stack cancels the trade. Each invitation or review expires after two minutes.

The server simulates both resulting inventories before exchanging items. Outgoing items may free space for incoming items. If either result will not fit, nothing moves and both confirmations reset. Excess items are never dropped on the ground.

## Saving and recovery

The inventories are saved with receipts in their ordinary player NBT files. A local journal under `fabric/world/infinity-player-trades` (or the selected world) records matching before and after player snapshots, including other saved player state. This lets a confirmed exchange recover consistently if only one player save completes before a crash.

The server processes unfinished journals before players join. It completes a prepared exchange or finishes its recorded rollback. A receipt already saved for that exchange prevents an old journal from overwriting newer player progress. Normal cancellations create no journal and move no items.

If storage fails during confirmation, the server restores the original inventories when it can record a safe rollback. If recovery cannot be recorded or verified, both players are disconnected and kept out until a server restart completes recovery. An invalid or unsafe journal stops startup with an owner-facing error. **Keep the journal and player files intact.** Fix disk space/permissions or restore one matching complete world backup; deleting the journal can lose the information needed to repair a partial exchange.

The interface uses vanilla chest packets, so Java and Bedrock players connected through Geyser can use the same review. Native tests cover inventory and confirmation rules; authenticated Java–Bedrock trading, touch controls, screen updates, and reconnect behavior still require a real device play-test.
