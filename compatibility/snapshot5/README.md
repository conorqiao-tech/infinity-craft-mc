# Experimental Snapshot 5 compatibility

This source port targets **Minecraft Java 26.4 Snapshot 5**, protocol
`1073742167`, with Fabric's normalized dependency version `26.4-alpha.5`.
It is a separate InfinityCraft candidate, **2.13.0-explore.48**. Source changes
and successful builds do not establish a completed live upgrade or iPad test.

The upstream Polymer, Floodgate, ViaVersion, ViaBackwards and ViaFabric commits
are pinned in [sources.json](sources.json). The adjacent patches contain the
complete local changes, including new source and mapping data. Original project
licenses are preserved in `licenses/`. These ports use public source; no private
supporter repository or paid download is required. They are local experimental
ports and are not official releases of those projects.

## Build

Use a complete Java 25 JDK. From the repository root:

```sh
python3 tools/build_snapshot5_dependencies.py --java-home /absolute/path/to/jdk25 --prepare-only
python3 tools/build_snapshot5_dependencies.py --java-home /absolute/path/to/jdk25 --stage
```

The first invocation fetches the exact public commits, applies the checked
patches, and verifies every changed input and original license. The second builds
into `research/snapshot5-rebuild`, publishes dependencies only into private file
repositories, and stages reviewed bytes under ignored `vendor/snapshot5`.
It never publishes upstream or installs a playable server. If a rebuilt artifact
differs from `artifacts.json`, staging stops and fresh review and validation are
required. A source match alone does not authorize replacing a tested binary.

Floodgate's upstream Architectury build generates a random, path-dependent
namespace for an unused injected class. A fresh build can therefore have a
different archive hash even when its runtime payloads match. The private build
receipt records actual output hashes; the script deliberately stops before
staging different bytes. This recipe provides complete corresponding source,
not a claim that all upstream build tools are bit-for-bit reproducible.

## Connection behavior

Native Java clients must use this exact snapshot. Their normal online login and
native compression/acknowledgment sequence are retained. Older ordinary Java
clients are refused before encryption because their authenticated login-options
translation has not been implemented.

The existing Geyser build speaks Java 26.2. The custom translator carries that
connection through 26.3 to the exact snapshot. Only translated connections disable
native compression; this avoids a change in when the snapshot activates it.
Floodgate still verifies encrypted identity data and the native server still
enforces its whitelist and bans. No synthetic acknowledgment or offline-mode
authentication bypass is used.

New snapshot content uses older-client fallbacks where Bedrock lacks an exact
representation. Native item identity and components must survive return traffic;
unsupported transfers with authentication properties fail instead of discarding
those properties. Unknown-effect suppression is a presentation fallback and does
not remove the server's native gameplay effect.

## Required validation

Before a live installation, rebuild the application and run the registered native
groups with the exact dependency set, resource/launcher tests, Creative inventory
wire and persistence checks, armor packet checks, and a disposable full-stack
start/save/restart cycle. Test an encrypted Floodgate connection through login,
configuration and Play, plus invalid-key and native-whitelist refusal. Convert a
private verified world copy and verify player inventory, progression, helpers and
saved settings before touching the live world.

A test connection using Geyser's codec and synthetic fresh-key identity is not an
Xbox-authenticated Bedrock client. Actual iPad joining, menus, armor motion and
flight require a real device check. Retain a fresh stopped-world backup before
conversion; never open a converted snapshot world with the earlier game version.

Official references: [Snapshot 5 release notes](https://feedback.minecraft.net/hc/en-us/articles/49759185020173-Minecraft-Java-Edition-26-4-Snapshot-5),
[Fabric version metadata](https://meta.fabricmc.net/v2/versions/loader/26.4-snapshot-5),
and [Geyser supported versions](https://geysermc.org/wiki/geyser/supported-versions/).
