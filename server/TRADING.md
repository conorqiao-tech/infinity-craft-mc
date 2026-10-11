# Permanent Survival item trades — 2.9.0-rewards.1

Ranks and powers can be earned through achievements or unlocked with the item trades below. These trades spend Minecraft items and use no real money or subscription. A traded rank is a permanent cosmetic badge. A traded power is permanently available to activate in Survival and does not require a rank. Cosmetics use the achievement route in [REWARDS.md](REWARDS.md).

## Preview, then confirm

`/trades` lists all ten offers. `/trade <id>` previews one offer and takes no items. To pay, type the exact command **`/trade <id> confirm`**. For example:

```text
/trade rank-go
/trade rank-go confirm
/trade aquatic
/trade aquatic confirm
```

| Trade ID | Permanent unlock | Exact cost |
|---|---|---|
| `rank-go` | Go rank | 16 iron ingots + 8 emeralds |
| `rank-plus` | Plus rank | 8 diamonds + 16 emeralds |
| `rank-pro` | Pro rank | 16 ender pearls + 8 blaze rods + 32 emeralds |
| `rank-ultra` | Ultra rank | 1 nether star + 16 diamonds + 64 emeralds |
| `hacks` | Flight, Night Vision, and Resistance II | 1 nether star + 1 dragon breath + 64 diamonds |
| `explorer` | Explorer power | 32 emeralds + 4 ender pearls |
| `aquatic` | Aquatic power | 16 prismarine shards + 8 emeralds |
| `nether` | Nether power | 8 blaze rods + 16 gold ingots + 16 emeralds |
| `fireguard` | Fireguard power | 4 blaze rods + 8 emeralds |
| `windstep` | Windstep power | 32 arrows + 8 emeralds |

A successful power trade unlocks the preset without activating it. Use `/power <id>` afterward. A higher rank does not include lower-rank trades or powers, and lower ranks are not prerequisites. The highest permanent rank from either route is displayed. Existing temporary owner overrides can still show a higher badge until they expire.

## Which items count

Confirm trades while alive in the Survival world and in Survival game mode. Wait ten seconds after taking damage, and finish or cancel a pending mode change first. Browsing the list and previewing an offer does not spend items.

Payment uses only ordinary vanilla stacks in your **main inventory and hotbar**. Stacks can be split across slots. Named items or items with modified components do not count. Armor slots, offhand, a cursor stack, Ender Chest, and containers are not payment sources. The dragon-breath offer takes one ordinary Dragon's Breath item; the trade does not return an empty bottle.

If any ingredient is missing, no ingredients are taken. An already unlocked power is not charged again. A rank at or below your highest permanent rank is also not charged again, whether you earned it through achievements or an earlier trade. Admin is not a trade.

## Saving a trade

The debit and permanent receipt are written together in the player's vanilla data file. The server checks the saved receipt, inventory, and mode state before reporting success. If verification fails, it restores the live inventory and state and asks the owner to check storage; do not keep retrying against a failing disk. A saved debit and receipt remain paired if the server stops between saving and its final message.

Trade receipts are under `InfinityModes.tradeReceipts` in player data. Rank and power checks read those receipts directly, so they do not depend on a second rewards or membership write succeeding. Receipts follow the authenticated player's UUID and survive death, mode changes, reconnects, and restarts. Restore whole matching world backups to retain both payment and receipts.

The server accepts only the ten fixed trade IDs above and their `confirm` subcommand. There is no command to supply an arbitrary price, item ID, rank, or reward. Java and Bedrock use the same server commands and inventory checks.
