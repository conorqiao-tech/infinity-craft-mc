# Helper combos — explore.46 source candidate

Explore.46 improves Ultimate Finals' ground/aerial handoff and explains why a
helper waits or stays grounded. Fresh source checks are recorded in
[VALIDATION.md](VALIDATION.md); this is not a completed live installation or
iPad acceptance result.

## Try it in game

1. As an OP4 owner, open **Infinity Menu → AI Helpers**. Create a helper on clear,
   solid ground or select a saved helper. Choose **Ultimate Finals**; Regular
   remains ordinary hostile-mob defense.
2. Choose **Follow** or **Guard current area**. **Stay / pause** prevents combat.
   Keep the owner and helper nearby in the same loaded world.
3. Let an eligible hostile mob approach on open, level terrain. The helper
   approaches with a charging spear, uses a mace at close range, and attempts a
   short aerial attack when the route is suitable. No attack button or AI chat
   request is needed for ordinary hostile defense.
4. Select the helper to inspect its phase and reason. On Java use **Live status
   • combat, location and health**; on iPad/Bedrock use **Refresh live status**.
   Reopen or refresh after the action changes. Status reads do not command the
   helper or change its target, weapon, health or approvals.

## What to expect

| Status | Meaning |
| --- | --- |
| Charging spear / Ground mace | Ground combat chooses a weapon for actual native reach. Spear charging is preserved; a small return margin prevents rapid boundary swapping. |
| Approaching target | The reason identifies launch distance, terrain, sight or cooldown. Ground pursuit remains available when a safe aerial route is unavailable. |
| Waiting for squad | A ready teammate has the next safe turn, or a teammate is already airborne against that target. |
| Launching / Gliding / Mace descent | A checked aerial attempt is underway. Its landing may follow the same target only through freshly checked, bounded adjustments. |
| Follow-up / Recovering | Close targets use the mace; farther targets use native spear contacts. The helper then returns to eligible ground combat. |
| Waiting for hostile / Paused | No suitable target is present, or the displayed condition prevents combat. |

Only ready helpers with a clear route take aerial turns. An unavailable or
blocked teammate cannot hold the queue. Each owner has at most one helper
airborne against the same target; other helpers continue eligible ground work.
The selected teammate still finishes the native spear wind-up before launching.
If a moving target leads behind unsafe terrain, the helper retains its last
checked landing rather than chasing through a wall or into unloaded space.

## Limits and protections

A launch needs a target 4–7 blocks away on roughly level ground, a dry loaded
air corridor, a clear solid landing and the helper's movement leash. A successful
launch starts an 80-game-tick cooldown; these are simulation ticks, not a promise
of four wall-clock seconds under server lag. Low ceilings, water, obstacles and
steep ground can keep the fight grounded. Follow pauses beyond 48 blocks from
the owner; Guard stays within its 14-block area. Helpers do not automatically
teleport or load distant chunks. **Bring here** moves only the same loaded helper.

Native weapon reach, sight, damage immunity and spear contact cooldowns still
apply. Ordinary strikes retain their twenty-tick cadence. A phase does not
guarantee damage, a combo finish or totem bypass. The avatar is visible to Java
and Bedrock players, while server movement and collision remain golem-based.

Helpers never select a player automatically. An exact player-target order still
needs the owner's review and separate live Codex approval, and remains subject
to session expiry, PvP/team rules and target eligibility. An expired/ineligible
player order hides stale attack phases in status without approving or revoking
anything during that read. Pets and other ineligible targets stay protected.
Stay, ceasefire and eligibility changes retain their existing cancellation
behavior. No external AI runs per combat tick.

This update preserves saved helper identities, names, owners, stats and movement
settings. Native regression results are recorded in
[VALIDATION.md](VALIDATION.md). Rendered movement, touch reception
and actual multiplayer behavior need their stated checks after a separately
approved live installation.
