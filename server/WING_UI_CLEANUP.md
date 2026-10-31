# Explore.51 r6 wing inventory-preview diagnostic cleanup

This resource-only package uses pack revision **2.13.51006**. It is the normal
cleanup paired with the temporary r5 inventory-preview diagnostic (2.13.51005).

R6 removes every r5 probe resource and the appended probe lines. Every pack
member is restored to the exact owner-approved r4 bytes except `manifest.json`,
whose header and module versions advance only so clients refresh their cached
pack. Because the manifest revision differs, the r6 pack intentionally does not
have the original r4 pack SHA-256. `PATCH.json` records the r4 baseline pack
hash and the exact r5 baseline this cleanup replaces.

R6 is not a new fix. It returns to the r4 appearance the owner confirmed: custom
Infinity Chestplate armor and wings in third person and armored sleeves in first
person. The inventory preview showing ordinary gray elytra remains the current
unresolved bug. The temporary probe panels, including those on ordinary elytra,
held elytra and in the world, disappear after r6 is installed and downloaded.

The mod, Java runtime, Geyser/Via stack, mappings, accepted artwork and the
production version 2.13.0-explore.51 / Minecraft 26.4-snapshot-5 are unchanged.
Packaging tests do not execute Molang or render pixels; physical iPad results
remain unknown.

Packaging this cleanup grants **no live installation approval**. Before any live
change, obtain owner approval for this exact candidate, save and stop, verify a
fresh complete backup, apply only its resource overlay and restart. Preserve the
current world, player/helper state, private settings and terrain.
