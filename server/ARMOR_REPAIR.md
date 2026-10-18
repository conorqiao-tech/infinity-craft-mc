# Armor rendering repair — explore.42 candidate

This candidate has passed 528 native tests and 303 Python tests, with no skips. Its complete local package includes checked native armor packets and the fresh original art. Explore.41 remains the installed version;
the owner reported that only the chestplate is visible in third-person and in
the inventory character, across the armor sets. No live installation is implied
by source changes or a passing build.

## Confirmed defect

The earlier seam check compared custom armor surfaces only. It missed the
player's still-visible outer skin layers. All five authored helmets were inside
the native hat layer; all five leggings were inside the pants layer, with gaps
at the inner thigh. Aurora sleeves also intersected the outer sleeve layer.
Opaque skin clothing can therefore hide the original armor artwork.

The repaired original geometry encloses the union of the wide and Slim skin
layers with at least 0.1 model units of clearance. Full-width leg shells cover
the inner thigh. Leggings and boots overlap at different depths instead of
leaving an exposed ankle or two coplanar surfaces. The authored textures and
geometry remain independent of ordinary Minecraft armor.

The skin dimensions come from [Mojang's player models](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/models/mobs.json).
No vanilla texture, armor file or player skin image is used as artwork input.
The normal leather, netherite and elytra assets remain excluded from the server
pack's overrides.

## Fresh pixel artwork

All five sets use newly generated original pixel-art source, retained with its exact prompt in `artwork/armor42/`. Twenty matching 64×64 inventory icons and five 128×64 worn atlases use controlled palettes, clear highlights and distinct motifs. New assets have separate paths; historical PNGs and normal Minecraft textures are preserved. Source, crops, UV layouts and active references are checksummed and reproducible.

See [Creative Studio](CREATIVE_STUDIO.md) for Survival access for existing Admin/OP4 users and the additional native command shortcuts.

## What the checks establish

The regression reproduces the old hat, pants and sleeve failures, checks every
shell against opaque wide/Slim skin bounds, verifies covered ankle seams and
checks model-space containment under representative joint rotations. These are
asset contract checks, not a Minecraft screenshot or an iPad rendering test.

The installed explore.41 pack matched its tested checksum and revision. It had
no duplicate attachable, geometry or render-controller identifiers. Saved
equipment contained all four Elemental pieces; Geyser's configured equipment
slots matched the canonical items and Creative aliases. Those checks rule out
the inspected disk/definition mistakes, but do not prove client pack acceptance
or Bedrock Molang evaluation.

## Device acceptance after a separately approved installation

Reconnect, accept the newly revised resource pack, and equip each complete set:
Convergence solid armor, Aurora, Ember, Elemental and Infinity. Inspect the head,
torso, both thighs and both feet in third-person and in the inventory character.
Then walk, crouch and turn the head. Check first-person sleeves separately.

All four pieces should remain visible, follow their own joints and cover the
skin without flickering seams. Check an opaque outer-layer skin as well as the
usual skin. Verify normal leather/netherite armor and normal elytra still look
normal. Real Convergence wings remain separate from the solid chestplate.

The owner has not yet verified this candidate on an iPad. If pieces are still
missing, retain a screenshot and identify the view and exact piece; do not erase
inventories, dismiss helpers or reset terrain as a rendering workaround.
