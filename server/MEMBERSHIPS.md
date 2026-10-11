# Permanent free ranks — 2.9.0-rewards.1

Every player starts at **Free** and can enter every game mode. Go, Plus, Pro, and Ultra are permanent cosmetic ranks, earned through Minecraft advancements or Survival item trades. **Complete all three achievements listed for a rank**, or confirm that rank's item trade, to unlock it. Ranks change only the badge and color beside a player's name. Powers and particle cosmetics have their own unlocks in [REWARDS.md](REWARDS.md). There are no payments or subscriptions.

| Rank | Complete all three achievements | Badge color |
|---|---|---|
| Free | None | Gray |
| Go | Get a stone pickaxe; get an iron ingot; kill a hostile mob (**Monster Hunter**) | Green |
| Plus | Get an iron pickaxe; get a diamond; enchant an item | Aqua |
| Pro | Enter the Nether; get a blaze rod; enter the End | Purple |
| Ultra | Defeat the Ender Dragon; enter an End gateway; find an End city | Gold |
| Admin | Enter the private Admin code | Red; limited moderation |

The highest permanent rank from achievements or trades is shown. You do **not** need to finish the lower rank groups before earning a higher rank. An earned rank does not expire, and a rank already earned before this update stays unlocked. The Java server checks the advancements saved for each player, including Bedrock players who join through Geyser. Bedrock's separate platform achievements are not used for these ranks. Java and Bedrock accounts have separate authenticated UUIDs unless they are linked through supported account linking; matching names alone do not share progress.

These are vanilla Java advancements. An item milestone triggers when the item enters your inventory; it does not verify how you obtained it. Items taken in Creative can trigger item milestones. These are cosmetic progress badges, not proof of Survival-only play.

Use **`/ranks`** to see every group of three achievements. **`/rank`** shows your current rank, highest permanent rank, progress toward the next rank, and the achievements still missing from that group. **`/trades`** lists the alternative item costs; preview with `/trade rank-go` and pay only with `/trade rank-go confirm`. See [TRADING.md](TRADING.md) for all four rank offers and the payment rules. Trading for a same or lower permanent rank takes no items.

Badges use Minecraft scoreboard team prefixes/colors and update automatically. The server leaves teams from another system alone, so those players may not see an Infinity badge until that team's assignment changes.

## Existing timed grants and owner controls

If this world already has a timed Go, Plus, Pro, or Ultra grant, that temporary badge override remains valid until its original expiry. The player displays whichever is higher: their permanent rank or the active override. When the override expires, the permanent rank remains. An owner can use the host panel's **Temporary rank overrides** control for an online player's cosmetic badge; it does not erase achievement progress or trade receipts. A new grant sets its expiry from now rather than extending the old expiry.

Equivalent host-console commands (no leading slash in the panel):

```text
membership grant PlayerName go 30
membership grant PlayerName plus 30
membership grant PlayerName pro 30
membership grant PlayerName ultra 30
membership revoke PlayerName
membership revokeadmin PlayerName
```

Bedrock names usually start with a period, such as `.BedrockName`. These commands require the host console or operator level 4. `membership revoke` removes a temporary override, not a permanent rank. `membership revokeadmin` removes the Admin role. Achievement rank and Admin records are stored with the world in `infinity-memberships.json`; traded ranks use receipts in the player's vanilla data file. Both are included in stopped-world backups. A `.previous` membership copy is retained on updates.

## Private Admin setup

Fresh public packages start with **Admin code redemption disabled** and contain no configured shared secret. To enable it, Save & Stop the world, open **Owner: private Admin code** in the local host panel, enter a private code of 8–128 characters, check **Enable private-code Admin redemption**, and save. The change applies on the next start. The panel stores a SHA-256 hash and does not print the code in its log.

An existing server's private `fabric/config/infinity-memberships.json` is preserved during an upgrade. In the panel, leaving the new-code field blank keeps that existing code; enabling redemption for a fresh server requires entering one first. Preserve this private config when replacing launcher files.

Players enter **`/admincode <your private code>`** in Minecraft command input. Anyone who knows the configured code can obtain the limited role, so share it only with trusted staff. Admin is free, independent of achievements and item trades, and is not a payment tier.

Admin can use:

```text
/staff kick PlayerName
/staff mute PlayerName
/staff unmute PlayerName
```

Mutes cover public chat for ten minutes. Other Admin accounts and operators are protected from these commands. Admin does **not** grant `/op`, `/give`, world editing, rank overrides, or access to the owner's control panel. Failed code attempts have a ten-second delay; three failed attempts lock that UUID for fifteen minutes. The lock persists across reconnects and restarts. Admin remains independent of achievement progress.

Use the stopped-world panel to disable new redemptions or replace the private code. Disabling redemption does not remove existing Admin roles; revoke those separately. Private configuration is deliberately omitted from release ZIPs. Keep a separate private backup of it; the normal world backup includes membership roles and trade receipts, but excludes private configuration and bridge keys.

## Full operators

A level-4 operator receives an **OP** badge and unrestricted access to the mod's powers, cosmetics, mode switching, and in-world helpers. This display does not permanently award achievement ranks or trade receipts. Removing OP returns the account to its actual earned rank or separate Admin role. Code redemption stays limited to staff tools. See [AGENTS_GUIDE.md](AGENTS_GUIDE.md).
