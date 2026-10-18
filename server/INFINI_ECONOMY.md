# Infini and owner-reviewed staff applications

This document accompanies the staged Infini expansion. A successful build and isolated native tests are required before it is installed. It is not evidence of a live payment store or a live installation.

Infini is local server currency. You can earn it from the expansion's first-time progression milestones and spend it through **Infinity Menu → Infini**. Native Java and iPad forms show an exact review before any spend. Existing achievement and item-trade ranks remain free and permanent; purchased supporter styles are a separate entitlement. Supporter purchases give titles and visual styles, not combat stats, items, priority progression or operator access.

The owner's configured USD prices are Go $15 / Plus $30 / Pro $50 / Ultra $100 for a 30-day pass, and $25 / $50 / $100 / $200 permanent. The configured currency price is **1 Infini = $2 USD**. Those prices correspond to 7.5 / 15 / 25 / 50 Infini passes, and 12.5 / 25 / 50 / 100 permanent styles. Passes do not automatically renew or bill. The canonical, machine-readable price source is `java/src/main/resources/assets/convergence/infini_catalogue.json`.

**Checkout is not connected. No game/menu button can charge a card or take USD.** A future owner-configured payment integration must authenticate and verify deliveries before invoking the trusted console credit operation. This implementation makes no HTTP payment request and has no public webhook. Infini cannot be cashed out or transferred to another server.

## Staff applications

ADMIN costs 125 Infini for a 30-day purchased role or 500 Infini for a permanent purchased role, subject to **separate owner approval of that exact request and buyer UUID**. The USD prices are $250 and $1000, but USD checkout is unavailable.

A confirmed Infini purchase records a **PENDING** application and its debit together. It does not grant Admin, modify `ops.json`, approve AI actions or promise that the owner will accept the application. The owner reviews the actual request ID and authenticated UUID from the local server console. For a pass, its 30-day term starts at owner approval, not while it waits in the queue. Actual permissions require the native purchased-role adapter to persist and verify its own grant provenance, including offline recovery.

The buyer can cancel an unapproved application. The owner can deny or revoke it. A full Infini return is committed only after the native role adapter has safely removed this purchase's own permissions. Free/manual Admin roles and independent operator grants remain separate. Expiry removes only the purchased role and does not automatically refund a completed term. The human and live Codex approval gates for AI changes remain in force even for staff.

## Trusted local deliveries

These operations accept the **actual local Minecraft server console** only. In-game OP, command blocks, scheduled functions, remote/RCON and suppressed-output sources cannot mint Infini or approve applications. Use authenticated UUIDs, never an unverified display name. Values accept at most two decimal places.

```text
infini-console credit <authenticated-uuid> <unique-delivery-id> 7.5
infini-console refund <authenticated-uuid> <unique-refund-id> <original-delivery-id> 7.5
infini-console staff-list
infini-console staff-approve <exact-request-id> <exact-buyer-uuid>
infini-console staff-deny <exact-request-id> <exact-buyer-uuid>
infini-console staff-revoke <exact-request-id> <exact-buyer-uuid>
```

Keep a delivery ID stable when retrying. An identical delivery succeeds without crediting again; changing its buyer, amount or operation under the same ID is rejected. Credits can target offline UUIDs. Currency is held in exact integer hundredths: **100 stored units = 1 Infini**, without floating-point arithmetic.

Refunds reference the original console credit. Its remaining unspent units are removed, including unspent units returned by a cancelled staff application. Purchases and staff returns retain their exact funding sources; if replacement credits repaid an earlier refund hold, later returns retain those replacement sources too. Already-spent refunded units create a debt/hold; unrelated lots remain intact, new credits settle the hold first, and purchases/purchased benefits pause until it is repaid. The system never silently uses a negative spendable balance or removes free earned ranks.

## Persistence and recovery

The authoritative state is the world-root `infinity-economy.json`. Balance, funding sources, purchased entitlements, staff applications and replay receipts commit in one forced temporary file and atomic replacement, followed by readback. Before publishing any primary commit, the server also saves and verifies the previous validated state as `infinity-economy.json.previous`. This happens on the **first** commit too: the initial prior state marks that this economy has been initialized before the first primary file is written. Wallets do not live inside any Survival/Creative mode inventory snapshot. Terrain refresh and world backups must preserve both ledger files along with player/private state.

Unreadable, oversized, symbolic-link, externally changed or unsuccessfully saved storage fails closed. A missing primary with an existing `.previous` file also fails closed, including an interruption between the first initialized marker and first primary write. The server preserves the recovery evidence and refuses new or replayed deliveries instead of silently creating empty balances and forgetting event history. File access failures are not treated as proof that a file is absent.

After an interrupted acknowledgement, the owner must review and reopen the authoritative state before retrying the **same** delivery ID. Its saved receipt determines whether the first operation committed. Back up the ledger with the rest of the matching world. Do not casually restore an older `.previous` file, even an empty initial marker: it may predate an acknowledged credit or purchase. Preserve all originals and reconcile matching backups and local delivery records before an owner-authorized recovery.

Ledger history is bounded to 32,000 events and 16 MiB, including capacity reserved for existing obligations. Each external credit that is not fully refunded keeps a future full-refund receipt seat. Each unfinished staff application keeps a return receipt seat. The ledger also reserves conservative JSON byte space for those receipts, the buyer's possible funding sources and the approval/revocation journal. Incoming credits recompute that budget, including replacement funding.

New rewards, credits, purchases and partial refunds are refused before any disk write when they would use reserved capacity. An ordinary capacity refusal leaves the existing wallet available and its funds unchanged. A full remaining credit refund or completed staff return exchanges its reservation for the actual receipt; an expired application releases an unused return reservation. These safeguards cover the ledger's own limits; real disk failures, permission errors or damaged storage still require owner recovery. History is never automatically pruned to make an operation fit. An experimental pre-candidate format-2 ledger that lacks the required reservation headroom is refused rather than silently migrated; preserve it and reconcile it in an isolated owner-reviewed recovery before use. This currency has not been installed on the live server.

If every primary, initialized marker and matching backup is deleted, local files cannot distinguish that loss from a genuinely new world or reconstruct the missing history. Keep a separate matching backup and delivery records; these protections do not make deleted history recoverable without them.
