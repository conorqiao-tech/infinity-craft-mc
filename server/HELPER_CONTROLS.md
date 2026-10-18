# Helper touch controls — explore.45 candidate

This source candidate replaces the helper and order chests on iPad/Bedrock with
native touch forms. Java keeps its existing chest controls. Opening the menu
does not create a helper, approve an order, or ask an external AI.

## Create and control a helper

Open **Infinity Menu → AI Helpers**. An empty roster offers **Create your first
helper**. Choose **Regular** for ordinary follow/guard defense, or **Ultimate
Finals** for the existing native spear, elytra and mace tactics. Choosing a
profile on the creation screen is the explicit spawn action. Stand on clear
solid ground; a successful spawn closes the screen so the nearby named player
avatar is visible. Helpers still use golem-based server movement and collision.

Select an existing helper to see its current location, health, held weapon,
movement and combo status. Follow, Guard and Stay use the existing owner-only
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

Only **Ultimate Finals** uses the existing spear → brief elytra glide → falling
mace sequence. Regular is ordinary mob defense. Stay, passive profiles, missing
helpers, unsuitable targets, owner/session checks and unloaded areas can prevent
combat. The form shows the current state rather than claiming a combo succeeded.

An aerial attempt also needs the existing distance, cooldown, loaded airspace
and solid landing checks. Obstructed terrain keeps ordinary ground pursuit.
Actual hits still need native reach, sight and target eligibility. The helper
cannot guarantee a hit or bypass a totem. No external model runs per-tick combat.

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

## Reported failures and verification boundary

The owner's reported failure was “menu or orders do nothing” and “can't combo.”
Read-only inspection found an empty current roster and no saved helper entities
in any dimension. The previous menu still displayed squad actions with no
helpers, giving feedback in chat beneath an open chest. Newly created helpers
defaulted to Regular, while the combo profile was a separate choice. These are
concrete discoverability/feedback problems. A broken iPad inventory transaction
has not been independently reproduced, and no existing helper was deleted or
recreated to test the fix.

Native tests exercise encoded form replies and real helper lifecycle operations
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

This candidate does not change weapon attack speed or combat balance. A
displayed **−2.4 attack speed** is the existing additive modifier: an otherwise
default base of 4 gives **1.6 attacks per second**, before effects/admin edits.

The live installation is recorded separately in the private server folder. The
explore.44 r1 resource-only repair was installed with its existing mod and terrain
unchanged; its walking/crouching appearance still requires the owner's iPad
comparison. Preparing this explore.45 source does not install it.
