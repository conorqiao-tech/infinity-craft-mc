# Armor42 original pixel artwork

The built-in image generation tool authored `source-sheet.png` from a blank
palette, using the exact request recorded in `prompt.txt`. No vanilla texture,
existing armor image, or another armor's model was supplied to the generator.
The native source PNG is retained unchanged with its checksum in `manifest.json`.

Five rows contain Convergence, Aurora, Ember, Elemental and Infinity. Each row
has four inventory pieces and four matching flat armor panels. Their original
pearl/star, jade/crystal, copper/ember, four-element and indigo/gold designs share
strong pixel silhouettes, beveled clusters and small bright edge accents.

Run `python3 artwork/armor42/pack_art.py` with Pillow available. The packer uses
measured source rectangles, a shared 64-color palette per set, nearest-neighbor
scaling, mechanical removal of tiny detached alpha scraps and nearest-source
alpha-hole repair for opaque UV panels. It does not paint new decorative pixels.
The output is twenty 64×64 inventory sprites and five 128×64 equipment atlases,
mirrored byte-for-byte into Java and Bedrock resource locations.

Run this packer **after** older armor geometry generators if those regenerate
attachable descriptions. Its final step changes only the active armor texture
references, retaining geometry, bindings, controllers, scripts and equipment
settings. Convergence's solid chest piece is `cuirass`; the separate native-flight
`chestplate` and its wing artwork are intentionally unchanged. Backpack and all
ordinary Minecraft armor/elytra resources are untouched. Existing PNGs are kept
at their prior paths; every new playable bitmap is under an `armor42` directory.

`inventory-and-uv-preview.png` displays the actual packed icons and full UV
atlases. `uv-preview.png` enlarges all five atlases. These are resource inspection
previews, **not Minecraft client captures**; actual worn placement and animation
still require Java/iPad rendering checks.
