# Explore.51 r4 wing slot selector candidate

This resource-only candidate uses pack revision **2.13.51004**. It is a tested
resource candidate, not a confirmed iPad appearance fix or an installed update.

The owner's r2 screenshots show A/T/H/P while the clearer side cells show S and
the literal-false control Z absent. The slot-to-bone check is therefore the
leading blocker: the owner property is readable, but the existing selector does
not enable the combined armor pass. The direct context-slot value has not been
measured. The screenshot's E cell was obscured by the head, and neither picture
establishes a full empty-hand test.

R4 changes only the two combined-elytra `pre_animation` slot predicates and both
manifest revisions relative to the immutable normal r3 cleanup. It compares
`context.item_slot == 'torso'` before reading each guarded owner property.
Microsoft lists `torso` and `body` as distinct [equipment domains](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/entityreference/examples/filters/has_equipment?view=minecraft-bedrock-stable).
Using `torso` as this attachable's context value is an **inference requiring
device confirmation**, not a documented guarantee or a measured client result.

The positive predicate rejects unknown, empty, main-hand and offhand context in
the contract model. Both property-existence guards remain. Missing or false
wing properties retain the native elytra renderer, and the separate Infinity
property still selects the corresponding shell. Native animations, gliding,
ordinary-item fallback, original armor geometry and every accepted PNG are
unchanged. The confirmed shield sprite repair remains in the pack. No diagnostic
letters or UI labels are included.

Native/property/codec tests do not execute Bedrock Molang or rendered pixels.
Tests of the selector's logical contract do not establish the iPad's actual slot
string or runtime coercion. The package independently pins immutable original51,
r1 and r3 artifacts and preserves the tested mod, Java, Geyser/Via stack and
mappings. `PATCH.json` identifies the exact required r3 baseline and r4 result.

Before any live change, obtain separate approval for this exact candidate, save
and stop, verify a fresh complete backup, apply only its resource overlay and
restart. Preserve the current world, player/helper state, private settings and
terrain; neither an older save restore nor a terrain refresh is part of this
repair. Prior r2/r3 approval does not approve r4.

After approval and a new pack download, check on iPad:

1. Move one hotbar item into an empty inventory slot and select that empty hotbar
   slot. Remove the offhand item too. Wear Infinity Chestplate and inspect the
   front, back and inventory character while standing, walking and crouching.
   Check whether the blue-and-gold chest shell and custom wings appear together.
2. Check native gliding after stepping off a ledge and tapping Jump airborne.
3. Equip ordinary elytra and confirm its normal appearance and gliding.
4. With Infinity Chestplate worn, hold ordinary elytra in each hand separately.
   Confirm that held ordinary elytra does not add another torso model or custom
   wings. Recheck Infinity Shield in the offhand afterward.

If the chest still renders gray elytra, record that failure without declaring
the inferred slot value correct or removing the equipped-instance distinction.
