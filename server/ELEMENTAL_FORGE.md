# Elemental Forge

Explore.41 was installed with owner approval on October 17, 2026 and subsequently merged into main. The owner's iPad check reported missing armor pieces in third-person and the inventory character. [ARMOR_REPAIR.md](ARMOR_REPAIR.md) describes the explore.42 source repair and fresh artwork; that candidate is not installed by merging or building source.

Open **Infinity Menu → Elemental Forge**, **Armor sets → Elemental & Infinity Armor**, or **Creative Gear Picker → Elemental Forge**. Bedrock uses native touch buttons; Java uses a chest menu. No command is required. In the real Creative inventory, search **Elemental**, **Infinity**, or the exact item name. The new sets have four solid armor pieces each; Convergence wings remain separate and ordinary elytra artwork stays normal.

## One suit, four elements

Wear all four matching Elemental pieces and choose Fire, Water, Earth or Air in the Forge. Your selection is saved with your character and survives reconnecting. Trading armor never changes the recipient's element. Elemental armor has normal diamond durability and protection. Mixed sets and pieces in the wrong slots do not activate set powers.

| Element | Full-set powers |
| --- | --- |
| Fire | Fire Resistance I, Strength II |
| Water | Water Breathing I, Dolphin’s Grace I, Night Vision I |
| Earth | Resistance II, Haste II, an additional 0.6 native knockback resistance |
| Air | Speed II, Jump Boost II, Slow Falling I |

Switching or removing armor stops renewal. Existing buffs fade within four seconds, or fifteen seconds for Night Vision. Only the suit's own Earth knockback bonus is removed immediately. Stronger potions, admin base attributes and unrelated modifiers are preserved. Ordinary Minecraft attribute caps still apply unless already expanded by the owner's stat editor.

The new **Infinity Armor** is a separate unbreakable ultimate suit. A complete set grants Fire Resistance I, Water Breathing I, Night Vision I, Speed III, Haste V, Strength V, Resistance IV and Regeneration III. Its armor components total 40; the ordinary native armor cap remains 30. It does not change health or absorption bases and does not replace Convergence's established powers.

## Four usable relics

Hold a relic and use it, or select **Infinity Menu → Use held power**. Relics need no rank and no armor set. They share a five-second recharge, so changing hotbar slots does not bypass it. Admin's existing cooldown bypass still applies. They never edit terrain or target other players.

| Relic | Action |
| --- | --- |
| Firebrand | Forward cone reaching eight blocks; up to six visible eligible hostile mobs take 8 damage and burn for four seconds |
| Tidecaller | Extinguishes you, heals up to 4 health, and grants ten seconds of Water Breathing and Dolphin’s Grace |
| Terra Gauntlet | Five seconds of Resistance II and Haste II; pushes up to six visible eligible hostile mobs within five blocks |
| Windstep | Short native forward impulse and five seconds of Slow Falling; walls use ordinary collision |

## Crafting and building

Use an ordinary crafting table. The armor patterns below use **D = diamond**, **C = amethyst shard** for Elemental, or **D = netherite ingot**, **C = Nether Star** for Infinity. A dot means empty. Each makes one piece.

| Piece | Rows |
| --- | --- |
| Helmet | DDD / DCD |
| Chestplate | D.D / DCD / DDD |
| Leggings | DDD / DCD / D.D |
| Boots | D.D / DCD |

Relics use one centered vertical column: **top / middle / bottom**.

| Relic | Top / middle / bottom |
| --- | --- |
| Firebrand | Blaze Powder / Amethyst Shard / Blaze Rod |
| Tidecaller | Prismarine Crystals / Heart of the Sea / Prismarine Shard |
| Terra Gauntlet | Obsidian / Echo Shard / Iron Ingot |
| Windstep | Feather / Amethyst Shard / Breeze Rod |

Surround one core with eight matching blocks to make eight original full-cube building blocks:

| New block | Surround / core | Light |
| --- | --- | --- |
| Ember Crystal | Polished Blackstone / Blaze Powder | 15 |
| Tide Glass | Prismarine / Prismarine Crystals | 7 |
| Earth Rune | Stone Bricks / Moss Block | 0 |
| Air Lattice | Quartz Block / Feather | 5 |
| Infinity Prism | Obsidian / Amethyst Shard | 12 |

Tide Glass is an opaque original patterned tile, rather than a transparent pane. All five blocks drop themselves when mined with a suitable pickaxe. Explosion survival is checked normally. Finding the core ingredient unlocks the matching recipes in the Java recipe book; all recipes also work directly at the crafting table.

Creative, Admin or OP2 can claim armor and relic kits into four empty inventory slots, or the block kit into five. Claims are atomic: a full inventory leaves all owned items unchanged. The confirmation is saved immediately. Standard source-owned session, game-mode, cursor and permission checks reject stale/replayed controls. Survival players without those privileges craft the items.

## Rendering repair and regression

Reproduction: wear a complete Convergence, Aurora or Ember set on Bedrock and inspect the boot/leggings seam, waist, neck and shoulders. Explore.40 put separately bound surfaces on identical planes with a positive-area overlap. These competed for the same pixels, causing flickering as the camera moved.

Explore.41 separates the boots from leggings, makes leggings an inner layer, leaves a narrow center seam, shortens the torso below the helmet and separates arm-shell depth. Original PNG images and normal Minecraft textures remain unchanged. A geometric regression reproduces the old boot conflict and rejects coplanar overlaps across all five suits. Software previews inspect the actual authored cuboids and UVs; they do not establish engine rendering on an iPad. Reconnect, accept the revised pack, inspect front/back, walk, crouch and turn your head for the final device check.
