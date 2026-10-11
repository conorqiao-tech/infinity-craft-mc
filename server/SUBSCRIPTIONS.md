# Planned USD supporter subscriptions

The server is free to play. **There is no checkout, payment collection, paid rank grant, or active recurring subscription yet.** The prices below are the owner's proposed monthly USD prices. Do not send anyone payment or card details in Minecraft chat or direct messages.

| Plan | Proposed price | Current status |
| --- | ---: | --- |
| Free | $0 | Available now; every game mode is open |
| Go supporter | $10 USD/month | Planned; cannot purchase yet |
| Plus supporter | $15 USD/month | Planned; cannot purchase yet |
| Pro supporter | $20 USD/month | Planned; cannot purchase yet |
| Ultra supporter | $25 USD/month | Planned; cannot purchase yet |
| Admin | $0 | Private owner-controlled staff role; never sold |

**Go, Plus, Pro, and Ultra also name free, permanent achievement/item-trade badges.** Those badges remain available without paying, never expire, and are not subscriptions. The proposed paid plans would be optional support for the server with cosmetic recognition while active; a purchase must never erase or replace an earned badge. Every game mode, power bundle, item trade, and earnable cosmetic remains available on Free. No plan grants gameplay power, items, operator access, or the Admin code. See [MEMBERSHIPS.md](MEMBERSHIPS.md) for the existing earned badges.

## Owner setup before accepting payments

1. Create a [Tebex store](https://www.tebex.io/) for **Minecraft: Java Edition** and set the store currency to USD. Keep its integration secret out of this repository, public downloads, screenshots, and chat.
2. Create four **separate monthly recurring packages** at the prices above. Tebex's Minecraft store types do not support its tiered-category feature, so do not present these as an automatic upgrade ladder. Clearly describe that they are optional cosmetic support and that the corresponding badge is also earnable free and permanently.
3. Check that the official [Tebex Fabric plugin](https://docs.tebex.io/creators/tebex-control-panel/game-servers/minecraft-java-edition) works with this server's exact Minecraft/Fabric versions. Set up initial, renewal, expiry, refund, and chargeback commands only after testing the full lifecycle on a **separate test world**. The current `/membership grant` command is a manual, timed badge override for an **online** player; it is not a tested Tebex billing integration. Do not point a live store at it without an implementation and tests for offline delivery, duplicate events, cancellation, and preserved earned badges.
4. Test a Java account and a Bedrock account separately. Bedrock players who enter through Floodgate can have different account names and UUIDs; decide whether purchases are per account or require supported account linking, and disclose that before checkout.
5. Publish the actual Tebex checkout URL only after package delivery and expiry work, then update this guide and `/subscribe`. Show the final price, billing interval, taxes/fees if applicable, contents, renewal and cancellation terms, and a purchase-history/cancellation link before players pay. Use Tebex's test/manual-payment flow to verify delivery without charging a player.

Tebex documents [recurring packages](https://docs.tebex.io/creators/tebex-control-panel/how-to-create-packages/how-to-create-a-package), [command types](https://docs.tebex.io/creators/command-management/an-introduction-to-commands), and [subscription management](https://docs.tebex.io/creators/tebex-control-panel/payments-overview/recurring-payments). Review the [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines) before launch. Until the tests and checkout are complete, the website and `/subscribe` show **planned prices only** and take no payments.
