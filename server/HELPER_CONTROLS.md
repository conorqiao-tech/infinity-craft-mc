# Helper controls — explore.47 source candidate

Open **Infinity Menu → AI Helpers**. Create your first helper on clear solid
ground, or select a saved helper. Java and Bedrock show six helpers per page;
use Next/Previous to reach all fifty. Limits are fifty per owner and fifty
server-wide, including unloaded identities. Menu icons are controls, not loot.

## Resume the same helper

Choose **Enable combos and follow me** on a loaded helper in your world. This
explicitly sets Ultimate Finals and Follow, keeping the exact entity, UUID,
name, position, health and edited stats. It does not teleport, heal, create a
helper or approve an attack order. **Follow**, **Guard current area** and
**Stay / pause** remain separate controls. Choosing a profile alone preserves
movement; a passive profile or Stay does not fight.

**Bring here** moves the existing loaded helper to safe nearby ground and clears
squad target orders. Helpers use player-sized default clearance for walking, with native scale edits
still applied. A blocked path is reported in live status. Visit the saved area to load an unavailable helper; the
menu never replaces it or forces distant chunks to load. Dismissal asks for a
second confirmation and removes only that identity.

## Combat and targets

Regular and Primitive use Infinity Swords. Ultimate Finals combines the Infinity
Spear, a brief native glide and an Infinity Mace, including a checked close-range
mace leap. It retains an eligible target during pursuit instead of going idle
at the acquisition boundary. A nearby direct threat to its owner can take
priority. Live status shows phase, reason, held weapon, target, health and
location; viewing it does not advance or change combat.

**Orders → Choose any nearby target** opens a paginated living-entity picker on
Java and a native touch form on Bedrock. Review the exact entity before confirming.
Non-player choices can include animals or pets; ordinary automatic defense still
selects hostiles only. The temporary order lasts at most five minutes while the
same entity remains loaded, nearby and eligible. Ceasefire stops chosen mob and
player orders. The separate player picker and fixed server-action proposals
remain available.

Player choices continue through owner review and separate live Codex review of
the exact proposal ID. Permission, session, helper and target changes invalidate
old screens. Native touch replies are single-use, expire after ninety seconds
and execute on the server thread. A stale response cannot approve a replacement
player order or silently select another mob.

For setup, native mechanics, terrain limits and the effect of powerful Infinity
weapons on weak mobs, see [HELPER_COMBOS.md](HELPER_COMBOS.md). Current test results
are in [VALIDATION.md](VALIDATION.md); source preparation does not install a live
server update.

## Explore.45 menu repair background

The owner's reported failure was “menu or orders do nothing” and “can't combo.”
Read-only inspection found an empty current roster and no saved helper entities
in any dimension. The previous menu still displayed squad actions with no
helpers, giving feedback in chat beneath an open chest. Newly created helpers
defaulted to Regular, while the combo profile was a separate choice. These are
concrete discoverability/feedback problems. A broken iPad inventory transaction
has not been independently reproduced, and no existing helper was deleted or
recreated to test the fix.

The explore.45 native tests exercise encoded form replies and real helper lifecycle operations
in disposable worlds, including stale-session and approval refusals. The
existing autonomous combat tests exercise native spear charging, glide and mace
damage. A successful test does not establish iPad touch reception, public joining
or rendered animations. Those remain a device check after separately approved
installation. See [VALIDATION.md](VALIDATION.md) for the actual completed checks.

The new native regression scenarios also cover issues found during code review:

| Reproduction | Previous behavior | Required behavior |
| --- | --- | --- |
| Open an empty squad and choose Follow or a squad profile. | Zero helpers changed; chat feedback sat behind the open chest. | Offer explicit creation and explain the requirement. |
| Create a target proposal, begin changing that target's game mode, then open Pending orders. | Its exact target session was invalid before a form existed; the screen could disappear into chat feedback. | Show a native recovery screen, preserving the proposal and both approval gates. |
| Change a helper/order after opening its form, then close the form. | The stale-context recovery path could open another screen despite dismissal. | Close only that exact screen; never execute an action or reopen it. |
| Make the form sender throw, including a delayed delivery. | An active ticket could remain and an exception could escape the tick callback. | Retire only the failed ticket, preserve a replacement screen and report send failure. |
| Expire a reviewed player order while its old current/aerial target is still present, then read status before the combat tick. | Status could say both “sequence in progress” and “order expired.” | Explain that player combos are blocked, without changing targets, weapons, health or orders during the read. |

This combo update does not change item attack-speed attributes. A
displayed **−2.4 attack speed** is the existing additive modifier: an otherwise
default base of 4 gives **1.6 attacks per second**, before effects/admin edits.

The accepted explore.45 armor resource repair is separate from these helper
changes. The owner reported its appearance stable. The live installation is
recorded in the private server folder; this guide does not install explore.46.
Final native results and remaining device checks belong in
[VALIDATION.md](VALIDATION.md), with separate approval for any live update.
