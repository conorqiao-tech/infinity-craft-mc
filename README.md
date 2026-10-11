# Infinity Craft MC

Infinity Armor for one shared Minecraft Java + Bedrock world. The Java Fabric server runs the game; Geyser and Floodgate let Bedrock players join it. The public guide and downloads are at [Infinity Armor Hub](https://infinity-armor-hub.vercel.app/).

## Join Infinity Craft

| Edition | Server address | Port |
| --- | --- | --- |
| Java 1.21.11 | `infinity-craft.remotewire.net:25565` | Included in address |
| Bedrock / iPad | `infinity-craft.remotewire.net` | `19132` |

See [JOIN.md](JOIN.md) for connection steps. The owner confirmed a successful iPad/Bedrock join, and the Java TCP port passed an external check. A full Java player join still needs verification. You only need Minecraft on the device you play on. The host Mac must be awake and the Minecraft server running; the public website provides the guide and downloads.

## Host your own world

1. Download the **server ZIP** from the [GitHub releases page](https://github.com/qiaokan/infinity-craft-mc/releases) or the website and extract it to a folder you will keep. Choose the newest exploration preview if you want the new minigames and menus. The source folders in this repository are for development; they are not a ready-to-run server on their own.
2. On Mac, open `Start-Mac.command` in the extracted folder. On Windows 10/11, run `start.bat`; on Linux, run `bash start.sh`.
3. The local browser panel guides you through agreeing to the [Minecraft EULA](https://www.minecraft.net/eula), starting the world, and copying the current join addresses. The first launch downloads checksum-pinned Python/Java components and needs an Internet connection. You do not need Minecraft installed on the host computer.

Java players use **Minecraft Java 1.21.11** and the Java address and port shown in the panel. Bedrock players, including iPad players, use **Add Server** with the separate Bedrock address and port shown there. Accept the offered server resource pack. Players on the same local network can use the host's LAN address. For friends outside your network, follow [`server/PINGGY_JOINING.md`](server/PINGGY_JOINING.md) for temporary, sign-in-free addresses on an Apple Silicon Mac, [`server/DYNU_JOINING.md`](server/DYNU_JOINING.md) for a fixed hostname with router forwarding, or [`server/HOSTING.md`](server/HOSTING.md) for other hosting options. Free Pinggy tunnel addresses are temporary. A fixed DNS hostname requires its own setup. The host Mac must remain on and the server launcher must stay open while people play.

The local owner panel controls the server; it is not a public website. Back up a stopped world before upgrading, and keep your world, `fabric/config`, and `settings.json` private. See [`server/README.md`](server/README.md) for full setup and upgrade instructions.

## Explore the world

The Main Hub leads to Survival, Creative, Hardcore, Minigames, and Adventure. Select the **Infinity Menu** compass to choose modes and use gear, powers, cosmetics, backpacks and helpers. Lobby signs open it too. `/menu`, `/guide`, `/play` and `/hub` remain optional shortcuts. The hub and five mode lobbies have themed arches, glass lighting, flags and planted corners; existing builds are preserved during the upgrade. The Minigames and Adventure hall signs open course menus; `/play minigames` and `/play adventure` open them too. Six timed minigames have `/best` and `/leaderboard <map>` commands; two starter adventure maps are included. Inventories are separated between modes.

Infinity gear includes custom blocks and building tools, armor looks, and backpacks with personal storage. `/ptrade <player>` opens a trade that both players review and confirm. Free, Go, Plus, Pro, and Ultra are earned through achievements or Survival item trades; Admin is owner-controlled. Powers and cosmetics can be earned separately. Optional supporter subscriptions are **planned at $50–$200 USD per month**, but checkout is not open. The earned ranks remain free and permanent. Read [`server/SUBSCRIPTIONS.md`](server/SUBSCRIPTIONS.md) for prices and status, and [`server/MODES.md`](server/MODES.md), [`server/EXPANSION.md`](server/EXPANSION.md), and [`server/REWARDS.md`](server/REWARDS.md) for play and unlocks.

The Infinity Menu exposes every `/convergence` feature through chest-style controls, including the gear catalogue, tool activation, powers and kits. Selecting the compass restores your previous held item so menu actions can use the weapon or tool. Permission checks still apply to ordinary players; Creative and OP4 players can equip Infinity gear. The menu uses vanilla icons on Java and Bedrock and preserves full inventories. Commands remain available as optional shortcuts. Native inventory tests pass; this update still needs a live Java and iPad UI playtest.

Admin is a free, private full-OP4 role. It grants Minecraft's highest operator level, gameplay unlock overrides and no Infinity ability cooldowns. Existing Admin roles migrate on join; revoking the role restores the previous OP level only when the role owns that promotion. An independently granted OP4 remains. Keep the private code with trusted administrators. This does not remove host/account capacity limits or the two AI reviews.

Use **Infinity Menu → AI Helpers** to create or manage up to six named golems. The first-helper button and status report make their presence visible. Choose Primitive, Regular, Ultimate Finals, Debug, CLI or API. Ultimate Finals shares targets, predicts pursuit and rotates clear-air leap/dive attacks with native melee follow-ups. It uses ordinary golem damage; it does not equip player weapons or reproduce a totem bypass.

The host can connect the installed, signed-in Codex CLI in the stopped-world panel. **Ask Codex** and questions in all six profiles then receive actual Codex answers with limited game facts. No API key is needed for that provider; the host account's usage limits still apply. Questions are separate sessions, not this live Codex conversation, and cannot execute or approve changes. Proposed server actions, code changes and exact player targets retain the owner's approval plus a separate live Codex review. See [`server/AGENTS_GUIDE.md`](server/AGENTS_GUIDE.md).

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
