# Elemental relic pixel art — explore.44

Windstep, Firebrand, Tidecaller and Terra Gauntlet now have independently
generated original inventory sprites with matching Java and Bedrock pixels.
Windstep has a broad ivory/lavender feather, gold ring and mint wind curls;
Firebrand has a flame-crystal wand; Tidecaller has a turquoise/gold shell and
pearl; Terra Gauntlet has slate armor plates and an emerald rune.

The four silhouettes use hard pixel edges, binary transparency and at most
32 colors each. The actual icons are 64×64, with transparent margins. Original
images, exact prompts, mechanical packing steps, checksums and previews at
16×16, 64×64 and 128×128 are retained in `artwork/relic44/`. These previews are
asset pixels, not Minecraft screenshots. Earlier artwork stays at its old paths;
ordinary Minecraft item, armor and elytra textures are unchanged.

Open **Infinity Menu → Elemental Forge**, or search the real Creative inventory
for **Windstep**, **Firebrand**, **Tidecaller** or **Terra Gauntlet**. Existing
recipes, item identities, names, interactions, cooldowns and progression are
unchanged. Hold a relic and use it, or choose **Infinity Menu → Use held power**.
No new command is needed. See [Elemental Forge](ELEMENTAL_FORGE.md) for crafting
and exact effects.

**Convergence Wings still provide armor protection and native flight together.**
They remain the existing winged chest-slot item and still activate the matching
set powers. The separate solid chestplate is an alternative without flight.
On iPad the winged item keeps normal native elytra artwork so ordinary elytras
also remain normal. This update does not replace the working flight item with a
custom static armor identifier or reintroduce a global wing-texture override.

## Floating held totem

The owner also supplied an iPad image with the Infinity Totem floating well away
from the hand. The original custom held attachable applies independently
authored geometry and first/third-person transforms. Those transforms have no
verified native totem grip; changing another offset by guesswork would not
establish correct placement.

The prepared pack now excludes both the canonical and Creative-alias totem
attachables. Its original blue-and-gold sprite, atlas entry, item identities,
offhand permission and native totem activation routing are unchanged. Bedrock
uses its ordinary custom-item sprite placement, matching the
[official basic item example](https://learn.microsoft.com/en-us/minecraft/creator/documents/addcustomitems?view=minecraft-bedrock-stable#goo).
The legacy generator outputs remain in source for reproducibility; preparation
always excludes those two activators. Bow, crossbow and shield models remain.

After installation, check the totem in each hand in first-person and both
third-person views, then walk and crouch. A resource/packet check cannot prove
the client's actual grip or activation appearance.

Explore.44 also includes the [worn-armor visibility repair](ARMOR_VISIBILITY_REPAIR.md).
That guide describes the earlier focused explore.43r1 patch; its resource-only
installation steps are not the instructions for this full explore.44 package.
It uses pack/module revision `[2,13,44000]`. Building or merging source does not
install it; the playable server remains explore.43 until a separately approved
backup, installation and restart. The owner still needs to check actual iPad
armor rendering and the new item artwork after accepting the revised pack.
