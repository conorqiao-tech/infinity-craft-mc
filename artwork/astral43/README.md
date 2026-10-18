# Astral Wilds original item art — resource payload

The built-in image generation tool created `source-sheet.png` from a blank
palette, with the exact request in `prompt.txt`. No vanilla art, existing game
texture, model, or earlier armor image was used as an input.

The three source cells are **Starshard Lantern**, **Riftspool**, and **Keeper's
Trowel**. Mechanical cropping, alpha cleanup, palette reduction and nearest-
neighbor scaling produce matching Java and Bedrock 64×64 inventory sprites.
`preview.png` displays those actual output pixels. It is not a client capture.

Run `python3 artwork/astral43/pack_art.py` with Pillow available to reproduce
resources from the retained image. Run `python3 artwork/astral43/validate.py`
with ordinary Python to check source/prompt/packer/output hashes, complete icon
silhouettes, matching edition pixels, and resource references.

`bedrock-atlas-entries.json` is a standalone merge payload. It does not replace
the main item atlas. The resource payload does not register items or implement
any interactions by itself; native mechanics, names, catalogue entries, recipe
integration, validation, packaging and live installation are separate stages.
