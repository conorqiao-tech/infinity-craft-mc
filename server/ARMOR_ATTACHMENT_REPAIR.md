# Armor attachment repair — explore.45 r1

The owner's iPad photos show a persistent black band at the waist and thighs,
with patterned sleeves sitting outside the player's arms. It also appears in
the paperdoll. Walking and crouching still produce flicker with empty hands.
These are failed rendering checks on the installed explore.45/44r1 models.

To reproduce, equip a complete Infinity set, empty both hands, and compare the
third-person character and inventory character while standing, walking and
crouching. The expected result is continuous armor over the torso, sleeves,
thighs and feet; the reported result is displaced armor and exposed skin.

## Correct the attachment contract

The original cuboids already cover the skin/clothing envelope. Enlarging them
or repainting transparent atlas cells would not address their attachment.
They were authored in upright player coordinates but used prefixed bone names
with explicit item `binding` roots. The source preview only rotated these
cuboids about their pivots; it did not model the client's attachment transform.
It therefore could not detect a displaced attachment.

This repair uses the **flat, same-name primary joints selected by Minecraft's
native armor**: `head`, `body`, `rightArm`, `leftArm`, `rightLeg`, `leftLeg`.
It removes the explicit binding field and renames each existing bone and its
render-controller key. All 41 original cube-bearing bones in the 21 active
models remain; no player geometry or extra ancestor bones are added.

The exact native reference is Mojang's pinned
[diamond chestplate attachable](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/attachables/diamond_chestplate.json),
which selects `geometry.humanoid.armor.chestplate` in
[mobs.json](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/models/mobs.json).
That geometry inherits through `geometry.zombie` in
[zombie.v1.0.geo.json](https://github.com/Mojang/bedrock-samples/blob/46ba6ea985fb5a92d79a9419198f10dda14c199d/resource_pack/models/entity/zombie.v1.0.geo.json)
to `geometry.humanoid`; the six primary armor joints are flat, with no binding
or parent fields. The separate legacy `player_armor.json` and modern player's
own skin hierarchy are different contracts and are not copied into this armor.

Microsoft's [geometry binding contract](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/geometry.v1.19.30?view=minecraft-bedrock-stable#binding)
distinguishes an explicit parent-root binding from same-name lookup when that
field is absent. Matching the selected native armor contract is the supported
repair; double root translation is a diagnosis consistent with the photos,
not a measured internal iPad matrix trace.

## Exact scope

- Five complete original suits and the backpack use native joint names.
- Cuboids, pivots, UVs, PNGs, geometry identifiers, materials, tint, glint,
  equipment identity checks and camera/UI expressions stay unchanged.
- Canonical and Creative catalogue attachables keep identical bytes.
- Ordinary leather, netherite and elytra assets stay native. The Convergence
  winged chest item keeps protection and native flight; this repair does not
  add a custom Bedrock torso to its native elytra rendering.
- The mod, dependencies, mappings, helpers, stats and worlds do not change.
  Pack/module UUIDs stay stable; cache revision becomes `[2,13,45001]`.

Source regressions compare the actual models with the pinned native contract,
check wide/Slim skin containment under independently evaluated owner walking,
crouching and root/body poses, and reject wrong attachments or doubled/missing
root transforms. Existing camera/UI truth tables cover all 42 canonical/alias
wearables. Package validation reverses only the allowed names/binding/key edits
and verifies every other pack byte against the immutable explore.45 package.
These are source, geometry and transport checks, **not an iPad renderer**.

## Safe installation and actual acceptance

The four-file resource repair ZIP contains the pack, dependency lock, this
guide and `PATCH.json`. It refuses a different baseline instead of overwriting
private resource edits. After separate owner approval, save/stop, verify a
fresh world backup and recovery copies, replace only its three payload files,
and restart the existing explore.45 mod. A resource-only repair must not refresh
terrain or alter its release marker.

Reconnect on iPad and accept the changed resource pack. With empty hands,
check each suit in third-person front/back views, the inventory character and
HUD paperdoll. Stand, walk, crouch, turn and jump. Thighs/forearms must stay
covered without displaced shells or flicker. Check mixed sets, first-person
arms/view clearance, held totem position, backpack and Convergence flight.
Device acceptance is required before calling the rendering problem fixed.
