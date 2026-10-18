# Bedrock worn-armor visibility — explore.43 resource repair r1

The owner installed explore.43 and supplied an actual third-person image: only
the patterned chest sleeves appeared. The head, chest torso, leggings and boots
were missing in third-person and in the inventory character. The earlier
skin-clearance checks and equipment-packet checks did not establish the actual
iPad rendering result. This candidate is not installed by preparing source.

## Focused repair

Every missing shell shared an extra `!context.is_first_person` condition; the
visible sleeves did not. The torso and sleeves even share one chest item,
material and atlas. This strongly localizes the remaining failure to that
visibility condition, but does not prove the device's actual context value.

The original worn controllers now use the entity's documented
[`query.is_first_person`](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/molangreference/examples/molangconcepts/queryfunctions/query_is_first_person?view=minecraft-bedrock-stable)
and explicitly allow
[`query.is_in_ui`](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/molangreference/examples/molangconcepts/queryfunctions/query_is_in_ui?view=minecraft-bedrock-stable).
An equipped non-arm shell is visible in third-person and in the inventory
character. During actual first-person gameplay it stays hidden to keep the
camera clear. Sleeves keep their existing visibility. All pieces still require
the matching item in its correct armor slot, including Creative prediction
aliases; holding an unequipped item does not activate its worn shell.

The changed pack has exactly three changed entries: its manifest and the two
original armor controllers. All other entries, including artwork, geometry,
attachables, literal names and ordinary Minecraft equipment exclusions, remain
byte-identical. Both generators reproduce the new predicates.

The mod remains **2.13.0-explore.43**, with its existing Java bytes, bridge,
dependencies and terrain identity. The pack/module UUIDs stay the same, while
their cache revision advances from `[2,13,43]` to `[2,13,43001]`. Starting with
explore.43, resource revisions reserve 1,000 values per exploration release;
the next full release uses `[2,13,44000]` and supersedes this repair.

## What validation can establish

The regression evaluates every authored visibility expression in source and
the prepared pack, for all forty armor identities and both backpack identities.
It covers third-person, first-person, UI, contradictory legacy context flags,
matching aliases, wrong slots, different armor families and held-only items.
It also reproduces the previous shoulders-only truth table. This is a bounded
expression evaluator with injected query values, not the Bedrock engine.

Native Geyser registry/translator/codec checks cover all four equipment fields
and their definitions. These synthetic equipment packets do not exercise the
complete real server/Via/local-player container path or prove client pixels.
Disposable Geyser and full-stack lifecycle checks are separate from an
authenticated iPad rendering check. The unchanged Java baseline's complete
native test suite does not need to be represented as a fresh run for this
resource-only repair.

## Safe installation and device acceptance

A separately approved installation saves/stops the server, verifies a fresh
complete world backup and private snapshots, then replaces only the Bedrock
pack, its dependency-lock checksum and this guide. Keep the mod, bridge,
settings, worlds and terrain-refresh policy unchanged. The bundle's PATCH.json
records exact before/after hashes; retain the old pack and lock for rollback.

Reconnect on iPad and accept the revised resource pack. Equip complete
Convergence solid armor, Aurora, Ember, Elemental and Infinity sets. Check the
head, torso, both thighs and feet in rear/front third-person and the inventory
character, then walk, crouch and turn the head. In first person, check that the
camera remains clear and sleeves follow the arms. Hold an unequipped armor item
to check for a duplicate worn shell. Check normal leather/netherite armor and
normal elytra. Actual device acceptance remains pending until the owner tests it.
