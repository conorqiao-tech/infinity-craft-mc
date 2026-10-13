# Original collection artwork — refresh27

Three source images were made with the built-in image generation tool in new-generation mode, without vanilla textures or reference images. Armor and icons use transparent backgrounds; block tiles use an opaque background. Supplied original PNGs and refresh19 assets remain unchanged.

Generation directions:

- Armor: two-by-two original Convergence (pearl/navy/cyan/amber star), Aurora (jade/mint/lavender), Ember (charcoal/copper/orange) and expedition-backpack panels. Generated faces are remapped to the exact native 64×32 logical UV layout, stored at 128×64.
- Icons: transparent seven-column/six-row pixel-art atlas of armor, solid and winged chestpieces, weapons, tools, arrows, backpack and gadgets. Six supplemental Studio motifs are retained as art, not new gameplay features or replacement native outfit implementations.
- Blocks: ten original square tiles: Infinity Block, Infinity Bricks, Polished Infinity, Radiant Infinity, Aurora Tiles, Obsidian Lattice, Copper Circuit, Moonstone, Sunstone Lamp and Verdant Mosaic.

`pack_art.py` mechanically crops and resizes generated pixels. It remaps face rectangles, samples the closest opaque pixel of the same face for alpha holes, and leaves unused UV cells transparent. Connected sprite bounds exclude neighboring-row scraps while preserving paired boots/legs. Nearest-neighbor resizing makes 64×64 icons and 32×32 tiles. It updates custom equipment/models/atlases and the established shared native elytra resource; ordinary leather/netherite resources never enter the pack.

Reproduce with Python plus Pillow:

```sh
python3 artwork/refresh27/pack_art.py
```

`uv-manifest.json` records source hashes, crop/UV rectangles and generated texture hashes. Inspect `inventory-preview.png`, `armor-uv-preview.png` and `block-tiling-preview.png`. These are artwork contact sheets, not Minecraft screenshots. Static armor uses native humanoid geometry; wing geometry and animations are retained. No new 3D armor model is claimed.

Resource tests cover hashes, face opacity, unused transparency, model references, equipped slots/max-stack components, pack revisions, unique attachables and isolation from ordinary armor. Native tests cover real equipment, set powers, inventory/box placement, permissions, sessions and saving. Actual Java/iPad visuals need a device check after an approved installation. See `server/ORIGINAL_ARTWORK.md` for controls and crossplay limits.
