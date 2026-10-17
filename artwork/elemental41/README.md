# Elemental Forge artwork

`generate.py` authors the new Elemental and Infinity gear from blank RGBA pixel
canvases. It does not load an existing armor model or source image. The two
128×64 armor atlases follow the equipment UV contract; independent small
Bedrock cuboids bind to the appropriate animated player joints. All eight pieces
have canonical and Creative catalogue attachables. Twelve inventory sprites and
five full-cube building tiles share the four-facet/prismatic visual language.

Run `python3 artwork/elemental41/generate.py` and then
`python3 artwork/elemental41/validate.py` from the repository. The second check
verifies resource references, hashes, full opaque UV coverage, joint coverage,
identity guards and the absence of positive-area coplanar surfaces in each new
complete suit's upright geometry. It also checks the private pre-task PNG
baseline when that receipt is available.

`asset-preview.png` shows the original pixels. `geometry-preview.png` uses the
actual new cuboids and per-face UVs in a small software rasterizer. It shows
Elemental standing/walking-pivot poses, then Infinity standing/walking-pivot
poses. These are asset inspection views, **not Minecraft client captures** and
do not prove the game's attachment/animation integration.

`inspect_existing.py` reads the existing Convergence, Aurora and Ember resources
only to inspect their repaired seams. It does not modify the input PNGs or use
them to create new armor. Its `existing-armor-seams-preview.png` has the same
standing/walking-pivot pair per collection, in that order. This is likewise a
software preview, not a game rendering result.

The building material called Tide Glass is a solid full-cube decorative tile;
the name does not imply unsupported transparency or collision behavior.
