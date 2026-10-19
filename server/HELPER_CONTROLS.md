# Helper controls — explore.46 source candidate

The explore.45 native helper/order forms remain on iPad/Bedrock; Java keeps its
chest controls. This source candidate improves Ultimate Finals combo readiness,
moving landings and combat feedback. Opening the menu does not create a helper,
approve an order, ask an external AI or advance combat. Fresh explore.46 checks
are in [VALIDATION.md](VALIDATION.md); preparing source does not install it.

## Create and control a helper

Open **Infinity Menu → AI Helpers**. An empty roster offers **Create your first
helper**. Choose **Regular** for ordinary follow/guard defense, or **Ultimate
Finals** for the existing native spear, elytra and mace tactics. Choosing a
profile on the creation screen is the explicit spawn action. Stand on clear
solid ground; a successful spawn closes the screen so the nearby named player
avatar is visible. Helpers still use golem-based server movement and collision.

Select an existing helper to see its current location, health, held weapon,
movement and combo status. Java's **Live status • combat, location and health**
closes the chest to show its answer; Bedrock's **Refresh live status** refreshes
the native form. Both use the same phase/reason summary. Follow, Guard and Stay use the existing owner-only
controls. **Bring here** moves the same loaded entity, preserving its health and
edited stats. An unloaded helper must first be loaded by visiting its area; the
menu never replaces one or force-loads its chunks. Dismissal asks for a second
confirmation. Squad controls apply to your own helpers and report the result.

An empty roster no longer offers silent Follow/Guard/profile/recall actions.
Java marks those controls **Create a helper first**; Bedrock offers creation
before squad controls. Permission, session or helper changes invalidate old
screens. Touch replies are single-use, expire after 90 seconds and run on the
server thread. Changes to a selected helper or order require a fresh review.

## Understand combos

Only **Ultimate Finals** uses spear → brief elytra glide → falling mace tactics.
Regular is ordinary mob defense. Stay, passive profiles, missing
helpers, unsuitable targets, owner/session checks and unloaded areas can prevent
combat. The form shows the current state rather than claiming a combo succeeded.

Only ready helpers with a checked route take squad aerial turns. A blocked or
cooling-down teammate no longer holds the next turn. Landing adjustments follow
the same moving target only after fresh terrain/leash checks. Close targets use
a mace rather than repeated spear attacks inside its minimum reach; a farther
spear keeps native charging contacts and cooldowns. Status reports phases such
as **Charging spear**, **Waiting for squad**, **Gliding** and **Mace descent**,
with the reason for a ground fallback or pause. A phase is an observed control
decision, not proof of a hit. Actual hits still need native reach, sight and
target eligibility. No damage-immunity reset or guaranteed totem bypass is
added. No external model runs per-tick combat. See [Helper combos](HELPER_COMBOS.md)
for the short setup and terrain limits.

## Review player targets and server actions

**Orders** opens a player picker, the fixed action proposals and pending-order
reviews. Select a live eligible player, review the exact identity and proposal
ID, then approve as owner. That remains only the first approval: ask Codex in
this chat to review the exact ID before expiry. The menu cannot supply Codex
approval. Fixed server actions follow the same two gates.

The review checks the same proposal object, state, description, owner and expiry
again. Target sessions are bound to the actual connected player and world. A
stale touch reply cannot approve a replacement order or attack another player.
Ceasefire remains immediately available for your own squad.

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
