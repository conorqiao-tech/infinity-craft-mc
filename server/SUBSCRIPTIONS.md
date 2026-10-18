# Infini and supporter ranks

This describes the **explore.43 source candidate**, not the currently installed server. Open **Infinity Menu → Infini** for your saved balance, exact purchase review, cosmetic selection and pending ADMIN application. The equivalent optional command is `/infini` or `/subscribe`.

**Public USD checkout is not connected.** Prices are configured, but no game/menu button can charge a card. The owner cancelled the payment-provider integration; a future real store needs separate setup. Keep payment credentials out of Minecraft chat.

## Confirmed prices

| Plan | Monthly / 30-day pass | Permanent | Infini pass / permanent |
| --- | ---: | ---: | ---: |
| Free | $0 | $0 | 0 / 0 |
| Go | $15 USD | $25 USD | 7.5 / 12.5 |
| Plus | $30 USD | $50 USD | 15 / 25 |
| Pro | $50 USD | $100 USD | 25 / 50 |
| Ultra | $100 USD | $200 USD | 50 / 100 |
| ADMIN application | $250 USD | $1,000 USD | 125 / 500 |

One Infini costs **$2 USD** when real checkout is connected. Infini can also be earned in game. It has no cash-out, transfer to other servers, Minecoin connection or promise of redeemable real-world value. Fractional amounts are stored exactly: 100 ledger units equal one Infini.

The current passes last **30 days** and renew only when another pass is explicitly reviewed and bought with Infini. They never charge a card or automatically renew. A same-tier renewal adds time after its existing paid expiry. Permanent supporter styles have no expiry. The game and website source use one price catalogue: `assets/convergence/infini_catalogue.json` inside the mod.

## What supporter ranks include

These are separate cosmetic supporter entitlements. Your free permanent achievement/item-trade badge remains intact, and every game mode, craftable item, power, Astral guardian and earnable cosmetic remains available without paying.

| Supporter | Original visible style | Title |
| --- | --- | --- |
| Go | Copper Comet: small amber sparks | Go Supporter |
| Plus | Aurora Helix: rising crystal spiral | Plus Supporter |
| Pro | Rift Halo: a portal ring | Pro Supporter |
| Ultra | Starlight Crown: alternating star and gold points | Ultra Supporter |

Higher active tiers include lower styles. Choose one style or hide both its particles and supporter title. Your earned Commons title can remain visible alongside it. Paid cosmetics change no health, damage, movement, flight, inventory capacity, AI authority or game access. Small native particle compositions avoid extra entities, armor overlays and ordinary texture replacements.

## Earn Infini through Astral Wilds

Open **Infinity Menu → Astral Wilds** for the six exact milestones, recipes and durable credit status. First contact awards 1 Infini; defeating each of the three guardians awards 2; crafting all three tools awards 3; completing all three kills and successfully using all three tools awards 5. The full constellation earns **15 Infini** and the free Astral Orbit cosmetic.

Each milestone pays once per player UUID. If storage fails, it stays marked pending and can retry safely after reconnecting or through the journal. Creative tests and repeated callbacks cannot duplicate earned credits.

## ADMIN requires an independent owner decision

Buying an ADMIN application spends the reviewed Infini and records **PENDING**. It grants no operator rights. Only the **actual local server console** can separately approve the exact request ID and buyer UUID. Player commands, command blocks, scheduled functions, RCON, AI replies and another operator's in-game actions cannot approve it.

The 30-day ADMIN term starts after owner approval, not while an application waits. A denial or revocation returns its exact Infini cost after the purchased role has been safely removed. Expiry ends only the purchased term. Existing owner, free-code, manual ADMIN and independent native OP grants have separate provenance and must survive purchased-role expiry. A native owner `/deop` retires existing role sources so a later tick does not undo that decision. A permanent staff application still requires approval and can be revoked by the owner.

Full OP4 is trusted administrator authority: that person can make independent permission grants, which a paid expiry intentionally preserves. Approval must therefore be a staffing decision, not a promise of tamperproof rented power. These roles never approve AI server actions. The existing separate human and live Codex approvals remain mandatory.

## Owner delivery, refunds and production setup

The local ledger records UUID-bound credit, purchase, application, refund and return receipts atomically. A repeated external delivery ID with the same payload is acknowledged without another credit; a changed amount or buyer is rejected. Offline credits remain available after reconnecting. A refunded credit removes its unspent original funds; already-spent refunded funds create a spending/cosmetic hold until repaid. Earned ranks are preserved.

See [INFINI_ECONOMY.md](INFINI_ECONOMY.md) for console syntax and recovery rules. Never edit the JSON balance by hand or use the old timed `/membership grant` as a payment integration.

Before enabling real checkout, the owner must configure a real payment-provider account, authenticated delivery, payment history, refund/cancellation terms, and verified production webhook handling for delayed payments or recurring billing. Test duplicate/out-of-order events, offline delivery, cancellations, refunds, chargebacks, source-role expiry and restart recovery in a separate world. No live key, webhook secret or payment file belongs in GitHub, a public ZIP, website JavaScript or a world download.

Server monetization reference: [Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines).
