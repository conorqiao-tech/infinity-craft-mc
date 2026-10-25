# Infinity chestplate and wings

Explore.50 adds native gliding to the existing **Infinity Chestplate**. Equip it
in the chest armor slot and keep the other three Infinity pieces equipped for
the complete set powers. Step off a ledge and press Jump while airborne; rockets
keep normal native behavior. The chestplate retains its item ID, armor14,
toughness4, unbreakable state and matching-set recognition. Existing saved items
inherit the new default without replacing their names, enchantments or item data;
a deliberately removed native glider component remains removed.

Open **Infinity Menu → Elemental Forge** or search **Infinity Chestplate** in the
Creative inventory. The existing recipe and access rules remain. No separate
wing item or second equipment slot is required for this chestplate.

**Convergence Wings** (`convergence:chestplate`) remains a separate combined armor
and flight item. The solid **Convergence Armor Chestplate** (`convergence:cuirass`)
and **Elemental Chestplate** remain non-gliders. Replacing an Infinity piece with
Convergence Wings still breaks the full matching Infinity suit.

## Crossplay appearance

The server keeps each original canonical item ID and armor components. Both
winged items use native elytra on the iPad connection. Two supported cosmetic
player properties select the accepted Infinity or Convergence torso/arms and
original folding wings for the actual equipped chest item. Accepted PNGs and
armor joints are unchanged; wing animations never scale the armor bones.

Absent properties, ordinary elytra and held or unknown slots use the normal
native elytra renderer, materials and animations. The guarded conditional overlay
never replaces ordinary elytra globally. Original inventory/equipment/respawn
translators handle their packets before the cosmetic state is updated in a
single native property batch. The cosmetic bridge grants no items or powers.

## Delivery and iPad check

Explore.51 is installed after separate owner approval. Its source baseline passed
722 native and 434 launcher/resource tests. The owner confirmed on iPad that
helpers hold separate formation positions and Infinity Chestplate can glide.
The appearance check failed: only ordinary gray elytra appears, without the
custom chest shell. Those results establish flight, not the combined appearance.

The [explore.51 r1 resource candidate](WING_APPEARANCE_REPAIR.md) redirects the
slot-mapping query to the owning player. Its visual result still needs a separate
installation and device comparison. Candidate builds and merges do not install it.

After separately approved installation, reconnect and accept the updated pack.
Check Infinity Chestplate with all four Infinity pieces in third-person and the
inventory character while standing, walking and crouching. Test gliding, rockets,
landing, reconnect, respawn and dimension changes. Compare Convergence Wings and
ordinary intact, broken and enchanted elytra. Hold ordinary elytra in either hand
while wearing each winged chest item to check for duplicate shells. Another
player should see the matching suit. Static/native protocol tests do not execute
iPad Molang or prove the final pixels, animation timing or touch gliding.
