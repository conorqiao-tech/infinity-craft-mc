# Infinity Craft MC

Infinity Armor for one shared Minecraft Java + Bedrock world. The Java Fabric server runs the game; Geyser and Floodgate let Bedrock players join it. The public guide and downloads are at [Infinity Armor Hub](https://infinity-armor-hub.vercel.app/).

## Start playing

1. Download the **server ZIP** from the [GitHub releases page](https://github.com/qiaokan/infinity-craft-mc/releases) or the website and extract it to a folder you will keep. Choose the newest exploration preview if you want the new minigames and menus. The source folders in this repository are for development; they are not a ready-to-run server on their own.
2. On Mac, open `Start-Mac.command` in the extracted folder. On Windows 10/11, run `start.bat`; on Linux, run `bash start.sh`.
3. The local browser panel guides you through agreeing to the [Minecraft EULA](https://www.minecraft.net/eula), starting the world, and copying the current join addresses. The first launch downloads checksum-pinned Python/Java components and needs an Internet connection. You do not need Minecraft installed on the host computer.

Java players use **Minecraft Java 1.21.11** and the Java address and port shown in the panel. Bedrock players, including iPad players, use **Add Server** with the separate Bedrock address and port shown there. Accept the offered server resource pack. Players on the same local network can use the host's LAN address. For friends outside your network, follow [`server/PINGGY_JOINING.md`](server/PINGGY_JOINING.md) for temporary, sign-in-free addresses on an Apple Silicon Mac, or [`server/HOSTING.md`](server/HOSTING.md) for other hosting options. These public addresses change; the website cannot display a permanent server IP. The host Mac must remain on and the server launcher must stay open while people play.

The local owner panel controls the server; it is not a public website. Back up a stopped world before upgrading, and keep your world, `fabric/config`, and `settings.json` private. See [`server/README.md`](server/README.md) for full setup and upgrade instructions.

## Explore the world

The Main Hub leads to Survival, Creative, Hardcore, Minigames, and Adventure. Use `/guide` for directions where you are, `/play` to choose a mode, and `/hub` to return. The Minigames and Adventure hall signs open course menus; `/play minigames` and `/play adventure` open them too. Six timed minigames have `/best` and `/leaderboard <map>` commands; two starter adventure maps are included. Inventories are separated between modes.

Infinity gear includes custom blocks and building tools, armor looks, and backpacks with personal storage. `/ptrade <player>` opens a trade that both players review and confirm. Free, Go, Plus, Pro, and Ultra are earned through achievements or Survival item trades; Admin is owner-controlled. Powers and cosmetics can be earned separately. Optional supporter subscriptions are **planned at $10–$25 USD per month**, but checkout is not open. The earned ranks remain free and permanent. Read [`server/SUBSCRIPTIONS.md`](server/SUBSCRIPTIONS.md) for prices and status, and [`server/MODES.md`](server/MODES.md), [`server/EXPANSION.md`](server/EXPANSION.md), and [`server/REWARDS.md`](server/REWARDS.md) for play and unlocks.

Entering Creative gives you an Infinity sword and a named compass that opens a chest-style Infinity gear picker. Tap an icon to equip a weapon or tool, or switch away from and back to the compass to reopen it. `/convergence gear` and `/convergence hold sword` remain fallback commands. The server-only Java mod does not add a client Creative tab, and the current Bedrock bridge cannot reliably move custom Infinity items from the built-in Creative catalogue into the hotbar. The new picker still needs a live Java and iPad playtest.

OP4 owners can create in-world helpers and propose six fixed server actions, such as changing the time or saving the world. A proposed action waits for the owner's in-game approval and a separate live Codex review through the host's local console; it never runs from AI chat alone. See [`server/AGENTS_GUIDE.md`](server/AGENTS_GUIDE.md).

## Source layout

| Path | Contents |
| --- | --- |
| [`java/`](java/) | Fabric 1.21.11 mod source, resources, GameTests, and Gradle wrapper |
| [`bedrock/resource_pack/`](bedrock/resource_pack/) | Bedrock textures and client resource definitions used by the bridge |
| [`server/`](server/) | Local launchers, dashboard, pinned dependency manifests, and setup guides |
| [`tools/`](tools/) and [`tests/`](tests/) | Build, validation, packaging, and launcher tests |
| [`website/`](website/) | Static public guide source and original artwork |

To build the Java mod from source, install Java 21 and run:

```sh
cd java
./gradlew build
```

The complete release packager additionally needs the verified server dependencies, Fabric API binary, resource-pack generation, and successful integration reports described in [`docs/CROSSPLAY_SOURCE.md`](docs/CROSSPLAY_SOURCE.md). Downloading the tested server ZIP is the easiest way to host. This repository includes the Gradle wrapper needed for Java builds, but excludes bundled Minecraft server dependencies and generated server archives. It also excludes live worlds, account and admin settings, private keys, API keys, logs, and runtime downloads.

The crossplay build is a preview. Automated tests cover server behavior and bridge startup, but an authenticated Bedrock device play-test is still needed for every touch-control and visual interaction. See [`server/VALIDATION.md`](server/VALIDATION.md) for the exact limits. This project is independent and is not approved by or associated with Mojang, Microsoft, CurseForge, or GeyserMC.
