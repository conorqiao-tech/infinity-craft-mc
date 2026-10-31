# Explore.51 r5 temporary wing inventory-preview diagnostic

This resource-only package uses pack revision **2.13.51005** on top of the
owner-approved r4 slot repair (2.13.51004). It is a **temporary diagnostic, not
a repair**. It adds no new flight, armor or appearance fix, does not weaken the
r4 torso-slot selectors, and does not claim any client fix.

## What the owner already confirmed with r4

- Third person: the custom Infinity Chestplate armor and wings appear together.
- First person: the armored sleeves appear and are working.
- Inventory character preview: ordinary gray elytra still appears instead. This
  is the current unresolved bug. No supported production fix has been identified.

## What this diagnostic adds

R5 adds a small set of owned probe resources and appends probe lines to the
combined elytra attachable. Both production `pre_animation` selectors, every
existing branch, every accepted PNG and every original geometry file remain
byte-for-byte unchanged. The probe draws a temporary panel attached at the chest
that reports which UI, owner and slot signals the client exposes.

The probe panel is **also visible on ordinary elytra, on held elytra and in the
world**, not only in the inventory preview of Infinity Chestplate. Anyone on the
server may see it while r5 is installed. Read each panel only with the shipped
diagnostic legend; this note does not restate its cell meanings. The probe
includes independent literal-true and literal-false controls. Interpret cells
only when the permanent frame and orientation notch, A and T are visible and Z
is dark. Read columns from the notch side if the preview is mirrored. An off cell
must show its dark well and surrounding grid clearly; an obscured cell is not a
false reading. Without the controls, record that view as inconclusive.

T is assigned after the query probes and proves that an assignment reached this render. It does **not**
prove that pre-animation ran freshly for the inventory actor: UI cloning or
caching could carry earlier values. The pre-animation cells are snapshots
delivered to the renderer. V, I and E are direct render-controller reads. Do not
treat snapshots as measured raw slot strings or freshly evaluated UI queries.

For the first comparison, wear Infinity Chestplate, remove the helmet and empty
both hands by selecting an empty hotbar slot. Compare a **front** third-person
view with the inventory character facing forward. Keep the full panel, notch and
grid visible. The board is front-only; viewing the character from behind is not
a valid reading. Do not add held Elytra until this single-instance comparison is
recorded, because overlapping worn and held panels could mix different signals.

## What is not known

Packaging tests check the resource contract only. They do not execute Bedrock
Molang or render pixels. Physical iPad results are still unknown, including
whether the inventory preview evaluates any probe signal at all.

## Approval and cleanup

Packaging this diagnostic grants **no live installation approval**. Prior r4
approval does not approve r5. Before any live change, obtain separate owner
approval for this exact candidate, save and stop, verify a fresh complete backup,
apply only its resource overlay and restart. Preserve the current world,
player/helper state, private settings and terrain.

R5 is paired with the normal r6 cleanup (2.13.51006), which removes every probe
resource and restores the exact r4 pack members except a manifest cache revision.
Plan the r6 cleanup before installing r5, and install it after the readings are
recorded. `PATCH.json` binds the r4 baseline, the r5 result and the r6 cleanup
target.
