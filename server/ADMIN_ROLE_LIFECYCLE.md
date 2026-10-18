# Purchased ADMIN source lifecycle (explore.43 candidate; not installed)

The Infini ledger owns the funded application, exact owner-console approval,
original cost, expiry and eventual financial refund. Memberships owns native
permission effects and a separate durable source receipt. A PENDING debit cannot
create an Admin source. The application can only be approved by the actual local
server console for its exact request ID and buyer UUID; OP4, command blocks and
remote/AI commands cannot replace this approval. Existing owner + live Codex AI
approval gates are unchanged.

An existing saved Admin is migrated as a free/manual source. Private-code and
owner grants create/renew that free/manual source independently of any purchased
receipt. Each account has a monotonic mutation generation; every purchased
receipt retains its exact original source generation, original approved-at and
expires-at values. Suspending, expiring or refunding one paid receipt cannot
remove another independent entitlement. Native OP4 present before the role is
never claimed. Role-owned promotion captures prior OP level and player-limit
bypass, persists first, then writes and verifies the native ops file.

The adapter verifies sources before JOIN gameplay and once per second while the
server runs; it also checks offline source deadlines during reconciliation. An
approved offline player receives a desired-role receipt, but the native OP
promotion waits until their verified JOIN. An expired offline player is revoked
before they can act after reconnecting. A ledger that is missing, corrupt or on a
refund hold suspends purchased sources; a valid restored approval may resume a
suspended receipt. This does not affect free/manual Admin or independent OP.

The pinned Fabric JOIN event occurs before vanilla publishes the player in its
UUID lookup. Reconciliation therefore uses a short-lived target scoped to the
exact joining player, current connection and server, and refreshes that player's
native permissions explicitly. Foreign/replaced same-UUID sessions and nested
scopes are refused; the target is cleared in `finally`. This fixes the observed
offline-approved JOIN failure without granting privileges to an offline profile.

REVOKING is a durable two-file handoff, not an optimistic refund. The adapter
first persists retirement and safely removes only the owned paid source. Only
then may the ledger atomically complete its terminal state and return the exact
original Infini cost, if a refund was requested. A crash between those steps is
retried without double refunding. Expiry does not automatically return a consumed
term's cost. No USD checkout or recurring charge is connected.

The native `op` / `deop` hook distinguishes a later independent owner decision
from a role-owned OP entry even after process restart. It journals the exact
native target level, bypass and name before mutation, explicitly verifies the
native ops file before acknowledging it, and replays an interrupted owner intent
before role reconciliation. Memberships' own scoped writes bypass this hook so
they cannot make paid privileges look independent. Failed provenance persistence
blocks the native mutation; failed completion remains pending for recovery.

Explicit owner `deop` or native lowering below OP4 retires the current role
sources, so periodic synchronization cannot undo that decision. It does not
imply a financial refund. The financially approved application remains in the
ledger, but its existing source is permanently retired and will not silently
reactivate. An owner can explicitly grant free Admin or revoke/refund the exact
application through the local console. A later independent raw OP4 is retained
when a purchased role expires, and does not itself fabricate a free Admin badge.

Integration: `AdminOperatorProvenanceMixin` is registered in infinity.mixins.json;
`PurchasedAdminRolesGameTests` is an **exclusive** native GameTest entrypoint.
InfiniEconomy registers before Memberships; Memberships owns lifecycle registration.
The native fixtures use private test-only registry reflection, restore captured
instances in cleanup, and never write real player data or the playable server.

Known limit: no source-only adapter can detect a host directly editing ops.json
out of process while the server is running. Owner decisions made through the
native op/deop methods and supported Admin grant commands are journaled. Direct
file edits require stopping and reconciling the server deliberately.

Full OP4 is trusted administrator authority. An approved administrator can make
independent native OP or manual Admin grants which this design intentionally
preserves. A paid expiry is not a tamperproof rental against that administrator's
own further permission changes. Approve only a trusted staff member; payment
alone remains insufficient to obtain any authority.
