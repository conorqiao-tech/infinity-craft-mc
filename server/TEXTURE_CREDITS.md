# Infinity Armor - texture credits

All armour, weapon, wing and inventory artwork in this pack is original work
created for Infinity Armor. Nothing is derived from, traced from, or copied
out of another mod or resource pack.

Earlier builds (v2.0.0 - v2.0.2) shipped artwork extracted from the Avaritia
project by SpitefulFox and TTFTCUTS. Those PNGs have been removed entirely and
replaced with the set described below, so this pack no longer redistributes
third-party artwork and the `avaritia_visor` texture has been renamed to
`infinity_visor`.

## The set

Palette: a void-black to indigo plate ramp, a violet rim light, a cyan energy
accent and a starlight white highlight, plus bronze/gold for weapon hafts.
Every piece uses one palette and one light source (top-left).

* Eight 16x16 inventory icons - helmet, chestplate, leggings, boots, sword,
  mace, spear, ingot. Previously these were split between 16x16 armour icons
  and 32x32 weapon icons in two unrelated colour schemes.
* Two 64x32 armour layers drawn against the vanilla humanoid UV boxes
  (head 0,0 8x8x8 - body 16,16 8x12x4 - arm 40,16 4x12x4 - leg 0,16 4x12x4).
* One 64x32 elytra sheet drawn against the vanilla elytra box
  (uv 22,0 size 10x20x2, so only x22-46 / y0-22 carries artwork). The previous
  wings texture filled the whole 64x32 canvas with diagonal stripes and
  rendered as a striped rectangle.
* One 64x32 visor overlay covering the helmet's front face only.

## v2.2.0 powered tools

The description above applies to the supplied custom PNG artwork, all retained unchanged. The four new tools reference Minecraft's built-in netherite tool models/textures with enchanted glint. Those assets belong to Mojang/Microsoft and are not copied into these archives. Bedrock aliases use the texture paths in [Mojang's vanilla atlas reference](https://github.com/Mojang/bedrock-samples/blob/main/resource_pack/textures/item_texture.json).

## v2.3.0 artwork

The four 16×16 Infinity block tiles and seven 32×32 icons for the totem, shield, bow, crossbow and arrow types were generated specifically for this expansion and cleaned for Minecraft's pixel scale. They are separate from the 24 original user-supplied PNGs, whose bytes remain unchanged. No Minecraft or third-party mod textures were copied for these new assets.

## v2.11 shared-server expansion

The six new building tiles, Creative wand icons, Aurora and Ember cosmetic armor icons and worn layers, and backpack satchel icon/worn back panel are original code-authored pixel art. Java uses the supplied equipment layers. Bedrock cosmetic armor and the backpack use dyed vanilla leather equipment to preserve native wearable controls; no third-party armor artwork is copied. The backpack is a flat textured back panel on Java and dyed leather clothing on Bedrock, rather than a separate 3D attachment.


## explore.15 helper robot skin

The new 64 by 64 classic helper skin was made with the built-in image generation tool and packed into Minecraft UV rectangles. Its chest includes pixel approximations of the ChatGPT knot and Codex cloud; those names and symbols belong to OpenAI. This is a community robot appearance, not an official OpenAI avatar or endorsement. All earlier user-supplied PNGs remain byte-for-byte unchanged. The public signed Minecraft texture property is bundled without skin-service credentials. See `java/src/main/resources/assets/convergence/helpers/README.md` for the final prompt and asset details.

## explore.19 refreshed armor and gear

Convergence and Aurora each have a new original 128×64 humanoid/leggings atlas, packed from artwork made with the built-in image generation tool. Twenty-four generated inventory designs provide 25 matching 64×64 icons, including both wand variants. The original 24 user-supplied PNGs and their checksums are retained unchanged; new sibling assets are selected through equipment layers, Java item models and the Bedrock item atlas. The four powered tools now use custom artwork instead of vanilla netherite model references. Full custom worn artwork is used on pack-enabled Java. Bedrock keeps the existing native netherite, dyed-leather and elytra equipment controls; mapped tools and wands use the new icons. Native wearables/bow/crossbow/shield/totem/arrows retain native appearances on Bedrock. See `artwork/refresh19/README.md` in the source archive for generation prompts and UV packing details.
