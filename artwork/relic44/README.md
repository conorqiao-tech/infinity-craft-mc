# Four original relic sprites

The built-in `image_gen.imagegen` tool creates each item independently from its
exact request in `exact-prompts.json`: Windstep, Firebrand, Tidecaller, and Terra
Gauntlet. Retain the untouched outputs in `sources/{item_id}.png`. The source
images are original transparent sprites; no vanilla texture, earlier item icon,
or existing game artwork is an image input.

`pack_art.py` performs mechanical processing only: alpha threshold 128, crop to
the opaque silhouette, reduce to at most 32 RGB colors without dithering, then
nearest-neighbor fit inside 56×56 on a transparent 64×64 canvas. It does not paint,
inpaint, invent edge pixels, or remove disconnected parts. Java and Bedrock
receive byte-identical new PNGs under `relic44/`. Historical PNGs are preserved.

Running the packer explicitly writes the new sprite pairs, provenance, and three
previews, then changes only the existing four Java model `textures.layer0`
values and four Bedrock item-atlas `textures` values. Item identifiers, display
names, native item definitions, recipes, controls, and gameplay remain unchanged.
All other JSON fields in those shared references are fingerprinted before the
changes. The manifest hashes every source, exact prompts, packer, validator,
README, output and resource reference, plus the preserved native and historical
inputs. The manifest itself is not self-hashed.

Use a Python environment with Pillow to run:

```sh
python3 artwork/relic44/pack_art.py
```

Use ordinary Python, without Pillow, to validate:

```sh
python3 artwork/relic44/validate.py
```

Inspect `preview-64.png`, `preview-128.png`, and `preview-16.png` at their native
sizes. Each shows the actual packed icon pixels in this order: Windstep,
Firebrand, Tidecaller, Terra Gauntlet. Scaling uses nearest-neighbor and the
background is a plain dark neutral color. These previews are not Minecraft
captures and do not establish in-game rendering. The validator checks their
pixels against the actual icons, source alpha silhouettes, binary transparency,
four-pixel minimum margins, palette limits, matching edition bytes, active
references, original display names, and preservation hashes. It does not impose
an arbitrary minimum palette size. Visual quality still requires review.

Resource-pack preparation, versioning, packaging, publication, and installation
remain separate explicit steps; neither script starts or changes a server.
