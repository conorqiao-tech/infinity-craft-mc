# Explore.51 shield grip candidate and diagnostic cleanup

This resource-only candidate uses pack revision **2.13.51003**. It removes the
two custom held-shield attachables so the owned Infinity Shield uses its original
atlas sprite with Bedrock's native item placement. The former authored 3D held
model is no longer active. The ordinary Minecraft shield is not overridden.

Relative to installed explore.51 r1, the normal pack changes only its header and
module revisions and removes `attachables/held/shield.json` and
`attachables/held/catalog_shield.json`. All remaining resource entries, including
the accepted PNGs, armor models and wing selectors, retain their exact bytes.
The Java mod, native shield blocking, Ward powers, offhand mappings, Geyser,
Via translators and runtime stay unchanged. The iPad hand placement and blocking
animation still require a device check; native packet tests cannot establish
their rendered appearance.

The paired r2 wing diagnostic is temporary. Installing this cleanup after it
restores the exact separately validated normal pack, removes every diagnostic
badge, label and resource, and advances both manifest versions. The package's
`PATCH.json` binds its expected old r2 pack/lock and exact new normal pack/lock;
`PAIR.json`, beside the diagnostic ZIP, binds both package hashes. Do not apply
an archive to a different installed baseline or infer approval from packaging.

Both packages are tested candidates, not installed updates. Separate owner
approval is required for a live change. After approval, save and stop the server,
verify a fresh complete backup and its recovery files, apply only the validated
resource overlay, and restart. Preserve the mod, worlds, players, helpers,
private settings and terrain; this repair does not authorize a terrain refresh.
Rejoin on iPad and accept the new pack. With the other hand empty, inspect the
Infinity Shield in each hand while standing, walking and crouch-blocking. Then
check a mixed-hand loadout and an ordinary shield. Report the actual hand and
item if any model remains detached.
