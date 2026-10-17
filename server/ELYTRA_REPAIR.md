# Ordinary elytra artwork repair for explore.39

Reproduction: connect on iPad with the original explore.39 crossplay pack, select an ordinary Minecraft elytra, then equip it. Its inventory icon and wing attachment use Infinity artwork, including an extra chest shell. Expected: ordinary elytra uses Minecraft's normal icon, wings, cape behavior and flight animations.

The cause is three global resources shipped in that pack: `attachables/elytra.json`, `textures/items/elytra.png` and `textures/items/broken_elytra.png`. Removing these overrides restores the client's own assets. Preparation also excludes possible native wing-texture overrides. Convergence Wings still use their native elytra representation on Bedrock, so their equipped appearance and actual inventory stack use normal elytra artwork too. Their literal name, original Creative catalogue preview and Java artwork remain custom. The owner selected this compatibility tradeoff to retain working flight.

This is a resource-only repair of **2.13.0-explore.39**, not a new mod release. The Java mod, Creative bridge, mappings, gameplay, flight and rocket code are unchanged. The pack and module versions advance from `[2,12,39]` to `[2,13,39]`, retaining their UUIDs so the repaired resources replace the cached old version. Later exploration releases use their actual release minor and revision. All other prepared resource entries retain their bytes.

Build only the repair without preparing or changing a mod:

```sh
python3 tools/crossplay_resources.py --input previous.mcpack --output repaired.mcpack --version 2.13.0-explore.39
```

The repair builder reads a bounded, validated resource archive and writes a deterministic new pack. Normal full-release preparation uses the same vanilla-equipment exclusion policy. It must never reintroduce global elytra artwork merely to customize Infinity wings.

The candidate passed 271 Python resource/launcher checks with warnings treated as errors and no skips. A fresh pinned Geyser run passed its Creative/equipment checks and local UDP ping. Refreshed supplemental native wire and persistence checks passed: 180 actual hotbar packets, 138 overflow refusals, 180 Survival refusals, 180 inbound inputs and 60 disk/rejoin item-count cases covering all 46 identities. A fresh disposable full-stack world passed two cold-hub/save/backup/restart/shutdown cycles. The Java mod and bridge hashes are unchanged from the tested explore.39 baseline; the full 484-test native suite was not rerun for this resource-only repair. No authenticated device rendering or flight test has yet been performed with the repaired pack.

An approved installation replaces only the tested Bedrock pack and the matching dependency lock checksum, after saving/stopping, verifying a fresh backup and retaining private settings. Keep the explore.39 mod and bridge byte-for-byte. The unchanged mod release/checksum prevents automatic terrain refresh, so this repair preserves terrain. The repair is not installed by building it or reading this guide.

After installation, reconnect and accept the updated pack. Check ordinary intact/broken elytra icons and equipped wings beside Convergence Wings, then launch, boost with rockets, glide and land. Check Aurora/Convergence armor, the solid chestplate, tool textures and Creative search again. Automated pack/startup checks do not prove the iPad's final rendering or touch/flight experience.
