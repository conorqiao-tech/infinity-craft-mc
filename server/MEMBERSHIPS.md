# Permanent free ranks

Every player starts at **Free** and can enter every game mode. Go, Plus, Pro, and Ultra are permanent cosmetic ranks, earned through Minecraft advancements or Survival item trades. **Complete all three achievements listed for a rank**, or confirm that rank's item trade, to unlock it. These earned ranks change only the badge and color beside a player's name. Powers and particle cosmetics have their own unlocks in [REWARDS.md](REWARDS.md). **Admin is a separate, free staff role that grants full vanilla OP level 4.** No purchase is required. Optional supporter subscriptions with the same tier names and USD monthly prices are planned, but checkout and paid grants are not active; see [SUBSCRIPTIONS.md](SUBSCRIPTIONS.md).

| Rank | Complete all three achievements | Badge color |
|---|---|---|
| Free | None | Gray |
| Go | Get a stone pickaxe; get an iron ingot; kill a hostile mob (**Monster Hunter**) | Green |
| Plus | Get an iron pickaxe; get a diamond; enchant an item | Aqua |
| Pro | Enter the Nether; get a blaze rod; enter the End | Purple |
| Ultra | Defeat the Ender Dragon; enter an End gateway; find an End city | Gold |
| Admin | Enter the private Admin code, or receive a trusted owner grant | Red; full OP4 and gameplay unlocks |

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
membership grantadmin PlayerName
membership revokeadmin PlayerName
```

Bedrock names usually start with a period, such as `.BedrockName`. These commands require the host console or operator level 4. `membership revoke` removes a temporary override, not a permanent rank. `membership grantadmin` grants Admin and OP4 to the selected online player. `membership revokeadmin` removes the role and restores the operator state that its own promotion replaced; a pre-existing OP4 grant or a separately recognized manual operator grant remains. Achievement rank, Admin records and operator-promotion records are stored with the world in `infinity-memberships.json`; traded ranks use receipts in the player's vanilla data file. Vanilla operator permissions are also saved in `ops.json` beside the world. Keep these permission files together when making a private server backup. A `.previous` membership copy is retained on updates.

## Private Admin setup

Fresh public packages start with **Admin code redemption disabled** and contain no configured shared secret. To enable it, Save & Stop the world, open **Owner: private Admin code** in the local host panel, enter a private code of 8–128 characters, check **Enable private-code Admin redemption**, and save. The change applies on the next start. The panel stores a SHA-256 hash and does not print the code in its log.

An existing server's private `fabric/config/infinity-memberships.json` is preserved during an upgrade. In the panel, leaving the new-code field blank keeps that existing code; enabling redemption for a fresh server requires entering one first. Preserve this private config when replacing launcher files.

Players enter **`/admincode <your private code>`** in Minecraft command input. Successful redemption grants **Admin and actual vanilla OP level 4**, including vanilla commands such as `/give`, `/gamemode`, `/tp`, `/op` and `/stop`. Anyone who knows the configured code can obtain this access, so share it only with trusted staff. Admin is free, independent of achievements and item trades, and is not a payment tier. Existing saved Admin accounts are promoted when they join and checked again during play; a failed permissions save is reported rather than silently granting access.

Admin also retains the staff shortcuts:

```text
/staff kick PlayerName
/staff mute PlayerName
/staff unmute PlayerName
```

Mutes cover public chat for ten minutes. Other Admin accounts and operators are protected from these staff shortcuts. Admin bypasses the mod's achievement/item unlocks for powers, cosmetics, backpacks and gear, plus Infinity weapon and power cooldowns, teleport waits, home-slot limits, and the Hardcore elimination restriction. Mode inventories and backpacks stay separate, trades still require both players' confirmation, and valid equipment, living-player and clear-space checks still apply.

AI server actions, code changes and exact player-target orders still require **the proposing OP4 player's approval and a separate live Codex review**. In-game AI answers cannot replace that review. Admin does not create unlimited AI usage, helper slots or host resources, or grant a browser account or private host-panel login. Failed code attempts have a ten-second delay; three failed attempts lock that UUID for fifteen minutes. The lock persists across reconnects and restarts.

Use the stopped-world panel to disable new redemptions or replace the private code. Disabling redemption does not remove existing Admin roles; revoke those separately. De-opping an account while leaving its Admin role active allows the role to restore OP4, so use `membership revokeadmin` to remove the role first. If the account also has an independent operator grant, remove that separately with `deop`. Change or disable the code to prevent redemption again. Private configuration is deliberately omitted from release ZIPs. Keep a separate private backup of it; the normal world backup includes membership roles and trade receipts, but excludes private configuration and bridge keys.

## Full operators

A level-4 operator without the Admin role receives an **OP** badge; an Admin keeps the **ADMIN** badge. Both have full vanilla level-4 command access and the mod's operator gameplay privileges. Admin also removes Infinity weapon ability cooldowns. This access does not permanently award achievement ranks or trade receipts. After Admin and any independent OP grant are removed, the account returns to its earned unlocks. See [AGENTS_GUIDE.md](AGENTS_GUIDE.md) for helper controls and the two AI approval gates.

Admin/OP4 can open **Infinity Menu → Admin editor • players and AI** to edit themselves, another online player or a registered loaded AI helper. Changes require a review and confirmation. Attribute bases (health capacity, speed, damage, armor, size and other supported attributes) persist without removing equipment or potion modifiers and can be reset to their recorded originals. Current health, hunger and XP are one-time changes; normal gameplay and mode profiles continue. See [README.md](README.md) for controls and exact-value commands.
