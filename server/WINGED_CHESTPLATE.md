# Convergence chestplate and wings

Explore.49 is a source candidate for one visibly armored Convergence wing item
on iPad. It is not installed by building or merging source. The installed
explore.48 armor was reported stable. Actual iPad gliding and the new combined
appearance remain unverified.

## Equip one combined item

Open **Infinity Menu → Elemental Forge → Get Convergence Wings • one empty
slot**. Creative, Admin or OP2 access is required for the free item. It is placed
in an empty inventory slot and saved; claiming it does not replace worn armor.
Move **Convergence Wings** into the chest armor slot. It provides Convergence
armor protection and native elytra flight together. Step off a ledge and press
Jump while airborne. Rockets retain their normal native behavior.

The same saved item ID, `convergence:chestplate`, and existing crafting recipe
are retained. The solid **Convergence Armor Chestplate** and **Infinity
Chestplate** are different items. A full Infinity suit needs its own matching
chestpiece; replacing it with Convergence Wings stops its matching-set powers.

## Appearance and compatibility

The candidate retains the native elytra item on the iPad connection. A separate
cosmetic property marks a player only when their cached, real chest item is the
canonical Convergence wing item. The resource pack uses the accepted Convergence
torso and arms, with original folding wings, only for that marked chest
attachment. The armor's existing joints, geometry and artwork are unchanged.
Wing animations never move or scale the armor bones.

An unmarked player, an absent property or a held/unknown equipment slot uses
Minecraft's native elytra renderer, textures, trim/glint and animation references.
Ordinary elytra retains its normal appearance. The old unconditional elytra
override remains excluded; this is a narrowly validated conditional overlay.
No native flight item is replaced with a custom Bedrock glider. Geyser documents
custom gliders as a client limitation in its
[current limitations](https://geysermc.org/wiki/geyser/current-limitations/).

The player property uses Geyser's supported registration/update APIs. Original
inventory, equipment and respawn translators still handle their packets before
the cosmetic state is updated. It does not grant items, alter protection, edit
player files or approve helper orders.

## Required iPad comparison

After a separately approved installation, reconnect and accept the new pack.
Check the combined chestplate in third-person and the inventory character while
standing, walking and crouching. Confirm the torso/arms remain stable, wings fold
at rest and spread while gliding, rockets work and landing restores the folded
pose. Remove/swap the chest item and reconnect; no armor shell should remain.

Compare ordinary intact, broken and enchanted elytra, including a cape if used.
Also hold ordinary elytra in each hand while wearing the combined Convergence
item; it must not render a duplicate chest shell. Check another player observing
these changes. Source/codec checks do not establish iPad Molang owner context,
chest-slot mapping, pixels, animation timing or actual touch gliding.
