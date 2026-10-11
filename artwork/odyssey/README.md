# Odyssey structure authoring

`blueprint.json` records the owner's circuit ruin using relative coordinates and the lower chest's exact item stacks. It has one chest and one always-active repeating command block containing `/ban aria`, explicitly requested by the owner. The upper chest, detached torches, absolute coordinates, private settings and unrelated command-block state are excluded.

The 21 theme entries replace the outer shell, redstone accents and lighting with native biome materials. The central repeater and chest retain their roles. Inventory tags preserve the original book's wording and item components.

Generate with `python3 tools/generate_odyssey_assets.py`, then verify with `python3 tools/generate_odyssey_assets.py --check`. The exporter targets Minecraft 26.3 palette keys (`id` and `properties`, DataVersion 5023). Native GameTests validate actual server codecs and a placed chest.
