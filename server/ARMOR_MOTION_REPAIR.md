# Bedrock limb-motion candidate — explore.44 resource repair r1

The owner confirmed that explore.44 restored the missing armor pieces and the
Infinity Totem's hand position on iPad. Equipped armor still flickers mainly
while walking or crouching, including with empty hands. The supplied screenshot
shows large black forearm and thigh regions cutting through the original
Infinity pattern. This is an unresolved rendering failure, not full acceptance.

## Narrow change and evidence

The five original armor sets use independent, explicitly bound head, body, arm
and leg bones. Their prefixed names do not themselves match player bones.
Head/body binding literals are lowercase; the limb literals were mixed case,
such as `'rightArm'` and `'leftLeg'`.

[Molang string literals preserve case](https://learn.microsoft.com/en-us/minecraft/creator/documents/molang/syntax-guide?view=minecraft-bedrock-stable).
Microsoft's [custom wearable example](https://learn.microsoft.com/en-us/minecraft/creator/documents/addcustomitems?view=minecraft-bedrock-stable)
uses lowercase limb targets. The [binding contract](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/geometry.v1.19.30?view=minecraft-bedrock-stable#binding)
selects the owning skeleton's bone transform, which should carry walking and
crouching motion. Static containment checks assumed that lookup succeeded;
they did not test the client's actual binding matrices.

This candidate normalizes only thirty active limb-binding literals in fifteen
geometry files to `'rightarm'`, `'leftarm'`, `'rightleg'` and `'leftleg'`.
It changes no model coordinates, pivots, cube dimensions, bone names, UVs,
textures, materials, visibility predicates, attachables, item names or gameplay.
The accepted camera/UI repair and totem fallback remain. The inactive legacy
flight harness is not changed. Ordinary Minecraft equipment stays normal.

**This is a targeted runtime lookup hypothesis, not proven engine causation.**
Published skeleton JSON also uses mixed-case bone names; its runtime lookup
case rules are not documented. A real iPad walking/crouching comparison must
establish whether this candidate fixes the observed motion problem. The source
and package checks enforce the intended lowercase contract and exact scope;
they cannot stand in for that device result.

The investigation also measured closely spaced hip and inner-leg surfaces.
Those do not explain the full forearm/thigh holes by themselves. They are not
reshaped in this candidate. An equipped/held-copy rendering ambiguity was also
found, but the owner's empty-hand result does not support it as this failure's
cause. No speculative model enlargement, animation duplication, player-model
override or material change is included.

## Package and safe application

The four-file patch contains the Bedrock pack, dependency lock, this guide and
`PATCH.json`. It is pinned to the exact explore.44 server ZIP and its expected
old pack/lock hashes. The validator reverses only the thirty binding-literal
changes and compares every other geometry field. All other pack entries remain
byte-identical except the manifest's header/module cache revision, which becomes
`[2,13,44001]`. Pack/module UUIDs stay unchanged. Dependencies, mappings, Java
runtime and the explore.44 mod remain exact baseline bytes.

After separate owner approval, save/stop, verify a fresh full-world backup and
private snapshots, verify every expected baseline hash, and replace only this
patch's three payload files. Retain the old pack/lock/guide and rollback record.
Restart with the existing explore.44 mod and terrain marker: this resource-only
repair must not regenerate terrain or force the refresh flag. Never install a
patch over a different or privately edited pack by bypassing its hash checks.

## Device comparison

Reconnect on iPad and accept the revised pack. With both hands empty, equip each
complete original set and inspect third-person front/back views and the
inventory character. Stand still, walk, crouch, turn and repeat at several camera
distances. The forearms and thighs should remain covered without black skin
patches. Check that first-person arms still move and the view stays clear.

Then verify the repaired totem grip in each hand and the four relic icons.
Convergence's existing winged chest item keeps armor protection and real flight;
its Bedrock artwork remains native elytra, with no new custom torso-plus-wings
visual claim. If the same black patches persist, record that failure rather than
calling the repair complete. Actual iPad acceptance is pending.
