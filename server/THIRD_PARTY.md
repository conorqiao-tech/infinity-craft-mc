# Components and source links

Infinity Armor Crossplay uses these third-party projects. Downloaded JARs remain unmodified. Exact download URLs and SHA-256 hashes are in `dependencies.lock.json`.

| Component | Version | Source / license information |
|---|---|---|
| Fabric Loader | 0.18.4 | https://github.com/FabricMC/fabric-loader — Apache-2.0 |
| Fabric server launcher | installer 1.1.1 | https://github.com/FabricMC/fabric-installer — Apache-2.0 |
| Fabric API | 0.141.6+1.21.11 | https://github.com/FabricMC/fabric — Apache-2.0; original supplied JAR and embedded notices retained |
| Polymer bundled | 0.15.2+1.21.11 | https://github.com/Patbox/polymer — LGPL-3.0; https://maven.nucleoid.xyz/eu/pb4/ for published sources |
| ViaFabric | 0.4.21+181-1.14-1.21 | https://github.com/ViaVersion/ViaFabric — GPL-3.0; embedded notices retained; overridden by the stable ViaVersion below |
| ViaVersion | 5.12.0 | https://github.com/ViaVersion/ViaVersion — GPL-3.0 |
| Floodgate Fabric | 2.2.6-b60 | https://github.com/GeyserMC/Floodgate-Modded — MIT |
| Geyser Standalone | 2.11.3-b1245 | https://github.com/GeyserMC/Geyser/tree/2808f7d21358a13019727fdf8737a5f978b23af4 — MIT |

Minecraft itself is downloaded by Fabric on first launch and is not redistributed in this ZIP. Its EULA applies: https://www.minecraft.net/eula.

Infinity artwork attribution is retained in `TEXTURE_CREDITS.md`. The source distribution contains the original Bedrock artwork; the crossplay pack uses a separate resource-pack UUID and omits the standalone add-on's behavior pack and vanilla elytra override.

## Private runtimes for Easy Setup

The launcher downloads these official project builds without changing their files or removing their bundled licenses. They are not redistributed in this ZIP. URLs and publisher SHA-256 digests are pinned in `runtime.lock.json`.

- **CPython 3.13.15**, python-build-standalone release **20260924**: https://github.com/astral-sh/python-build-standalone/releases/tag/20260924. CPython uses the PSF license; the build includes third-party libraries and their notices. Build documentation: https://gregoryszorc.com/docs/python-build-standalone/main/.
- **Eclipse Temurin JRE 21.0.12.1+1**: https://github.com/adoptium/temurin21-binaries/releases/tag/jdk-21.0.12.1%2B1. OpenJDK is GPLv2 with the Classpath Exception; retain the runtime's `legal` directory. Publisher: https://adoptium.net/.
