# Wing appearance candidate: explore.51 r1

The owner's iPad test of installed explore.51 confirms that helpers hold separate
formation positions and **Infinity Chestplate** can glide. Its chest armor and
custom wings do not appear: the device shows only ordinary gray elytra.

This resource-only candidate changes the two cosmetic selectors to resolve the
attachment slot on `context.owning_entity`, the player who owns the equipment,
rather than on the attachable itself. The selectors still require that the slot
maps to the player's body and that the server's corresponding cosmetic property
exists and is true. Unknown slots, held items and absent properties retain the
ordinary elytra path.

The documented Molang arrow operator runs a query on the referenced entity, and
`item_slot_to_bone_name` reads that entity's equipment-slot mapping. This explains
the candidate's scope; it does not establish the iPad engine's actual mapping or
rendering. See [Molang fundamentals](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/molangreference/examples/molangconcepts/molangintroduction?view=minecraft-bedrock-stable)
and [Using attachables](https://learn.microsoft.com/en-us/minecraft/creator/documents/attachables?view=minecraft-bedrock-stable).

Only the combined-elytra attachable and pack header/module cache revisions change
inside the resource pack. The revision is `[2,13,51001]`. Accepted armor joints,
models, textures, wing animations, ordinary elytra resources and the native
glider item stay intact. The Java mod, Geyser extension, dependencies, helper
behavior, saved data and terrain policy are unchanged. Installing this repair
does not trigger a terrain refresh.

## Device comparison still required

This is a candidate for an owner-approved comparison, not a claim that client
rendering is fixed. Static checks and Geyser packet tests do not execute iPad
Molang or render pixels. If the custom pass remains absent, investigate the
actual client slot/property context rather than replacing the accepted artwork.

After separate installation approval, reconnect and accept the resource pack.
With **Infinity Chestplate** equipped and both hands empty, inspect the front,
back and inventory character while standing, walking and crouching. Check that
the matching chest shell and original wings appear together, then repeat the
confirmed Jump-while-airborne glide. Compare **Convergence Wings** and ordinary
elytra, including holding ordinary elytra in each hand while wearing a custom
winged chest item. Check for duplicate shells, flicker or a changed ordinary
elytra appearance. A second player should see the same equipped suit.
