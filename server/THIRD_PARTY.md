# Components and source links

Infinity Armor Crossplay uses these third-party projects. Official downloads remain
unmodified. Snapshot 5 includes explicitly modified local source ports, with exact
binary hashes in `dependencies.lock.json` and complete corresponding source
patches, pinned public commits, original licenses and rebuild instructions under
`compatibility/snapshot5/`. The local ports are experimental InfinityCraft builds,
not official releases of those projects.

| Component | Version | Source / license information |
|---|---|---|
| Fabric Loader | 0.20.1 | https://github.com/FabricMC/fabric-loader — Apache-2.0 |
| Fabric server launcher | installer 1.1.2 | https://github.com/FabricMC/fabric-installer — Apache-2.0 |
| Fabric API | 0.164.1+26.4 | https://github.com/FabricMC/fabric — Apache-2.0; original supplied JAR and embedded notices retained |
| Polymer bundled | 0.18.5-infinity-snapshot5+26.4-snapshot-5 | https://github.com/Patbox/polymer — local LGPL-3.0 source port; original notices retained |
| ViaFabric | 0.4.22-infinity-snapshot5.1 | https://github.com/ViaVersion/ViaFabric — local GPL-3.0 source port |
| ViaVersion common (embedded) | 5.13.0-infinity-snapshot5.2 | https://github.com/ViaVersion/ViaVersion — local GPL-3.0 source port; embedded notices retained |
| ViaBackwards | 5.13.0-infinity-snapshot5.2 | https://github.com/ViaVersion/ViaBackwards — local GPL-3.0 source port |
| Floodgate Fabric | 2.2.7-infinity-snapshot5 | https://github.com/GeyserMC/Floodgate-Modded — local MIT source port |
| Geyser Standalone | 2.11.3-b1248 | https://github.com/GeyserMC/Geyser — MIT |

Minecraft itself is downloaded by Fabric on first launch and is not redistributed in this ZIP. Its EULA applies: https://www.minecraft.net/eula.

Infinity artwork attribution is retained in `TEXTURE_CREDITS.md`. The source distribution contains the original Bedrock artwork; the crossplay pack uses a separate resource-pack UUID and omits the standalone add-on's behavior pack and vanilla elytra override.

## Private runtimes for Easy Setup

The launcher downloads these official project builds without changing their files or removing their bundled licenses. They are not redistributed in this ZIP. URLs and publisher SHA-256 digests are pinned in `runtime.lock.json`.

- **CPython 3.13.15**, python-build-standalone release **20260924**: https://github.com/astral-sh/python-build-standalone/releases/tag/20260924. CPython uses the PSF license; the build includes third-party libraries and their notices. Build documentation: https://gregoryszorc.com/docs/python-build-standalone/main/.
- **Eclipse Temurin JRE 25.0.4.1+1**: https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1. OpenJDK is GPLv2 with the Classpath Exception; retain the runtime's `legal` directory. Publisher: https://adoptium.net/.
