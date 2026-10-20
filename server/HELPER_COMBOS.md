# Helper combat — explore.47 source candidate

This source repairs repeated combat against moving enemies and adds a fifty-helper
squad, explicit target selection and Infinity weapon loadouts. Preparing source
is separate from installing it. Fresh checks belong in [VALIDATION.md](VALIDATION.md).

## Start fighting

1. Open **Infinity Menu → AI Helpers** on clear solid ground. Create a helper if
   your roster is empty. Only a living, connected OP4 owner can manage helpers.
2. Select the existing helper and choose **Enable combos and follow me**. This
   explicitly sets Ultimate Finals and Follow on the same entity; it preserves
   its name, health, edited stats and location. It does not create another helper,
   teleport it or approve a player target.
3. Let an eligible hostile approach. Ultimate Finals uses an Infinity Spear when
   there is room for native charging. Close enemies can trigger a checked short
   leap with an Infinity Mace instead of trapping the helper in ground attacks.
4. Reopen the helper's status for its observed phase and reason. Unsafe jumps
   fall back to ordinary native ground attacks. Standing still can also mean
   Stay, an unloaded helper, a passive profile, an unavailable owner or no target.

Both Java and Bedrock roster menus show six helpers per page, with Previous and
Next controls. Up to **50 helpers per owner and 50 across the whole server** are
allowed, including unloaded helpers. Saved identities continue to occupy slots.
Bring here explicitly moves the same loaded helper; it never replaces one or
loads distant chunks. Passive Debug, CLI and API profiles remain passive.

## Choose a specific target

Open **AI Helpers → Orders → Choose any nearby target**, choose a living target,
and review its name, type, UUID, world and selected position before confirming.
The picker includes nearby animals, pets, hostile mobs and players, with pages
for a crowded area. Registered helpers are excluded. It does not target items,
blocks or every entity in an area.

A confirmed non-player order lets active Primitive and Ultimate Finals helpers
pursue only that exact selected entity. Animals and pets are never acquired
automatically; incidental spear/mace side effects remain gated. The target must
stay loaded, alive, in the same world and within 48 blocks of the owner. Orders
last at most five minutes and end on expiry, session/permission/world changes,
target death/unload or restart. **Ceasefire** clears both mob and player orders;
Stay pauses that helper and Bring here clears squad target orders.

Choosing a **player** creates the existing proposal. The owner's approval and
separate live Codex approval are both still required. PvP, team, session,
expiry, range and line-of-sight checks remain. A status screen never approves
an order, changes weapons, advances combat or mutates an expired order.

## Weapons and decisions

Regular and Primitive combat helpers use Infinity Swords. Ultimate Finals uses
Infinity Spears and Infinity Maces. Helpers copy the original Infinity gear's
name, model, attributes and unbreakable components onto native weapon items so
Minecraft's spear charge and falling mace behavior actually run. The catalogue,
ordinary player items, accepted armor artwork and resource mappings are retained.
Helper equipment does not drop as free loot.

These are powerful weapons: a weak mob can die before a multi-hit sequence is
possible. Damage is never delayed or faked just to display a combo. Native reach,
sight, damage immunity and spear contact cooldowns still apply. Ordinary strikes
retain the twenty-tick cadence; no guaranteed totem bypass is added.

An existing eligible Ultimate Finals target is retained beyond the owner's small
fresh-acquisition area while still inside the movement leash and 24-block combat
range with clear sight. A newly detected direct threat to the owner can take
priority. This prevents harmless idle resets during ordinary knockback/pursuit
without acquiring every distant mob.

Only a ready helper with a checked route reserves an aerial turn. A teammate
recovering from a recent strike or still winding up its spear cannot starve a
ready teammate. One helper per owner/target uses the aerial lane at a time.
Successful launches have an 80-game-tick cooldown. Those are simulation ticks,
not a guarantee of four wall-clock seconds under load.

Aerial attempts need dry loaded ground, roughly level targets, clear full-height
airspace and a solid landing within the helper's leash. Moving landings are
rechecked every five ticks, with bounded adjustments; unsafe replacements keep
the last validated landing. Water, walls, low ceilings and steep ground keep the
fight grounded. Follow pauses beyond 48 blocks from the owner; Guard stays
inside its 14-block anchor area. Helpers never teleport or load chunks by
ordinary combat. Registered helpers have player-sized default collision and eye height, so native
walking fits one-wide, two-high passages. Native scale edits still apply; ordinary
golems keep their usual bodies. Helpers retain golem-based AI and native physics
and are not authenticated player accounts. No external AI runs per combat tick.

Native regression results and their limits are recorded in [VALIDATION.md](VALIDATION.md).
Rendered motion, actual iPad controls and live multiplayer behavior still need
their device checks after a separately approved installation. Fifty-entity
functional tests do not establish a multiplayer performance guarantee.
