# Infinity Armor Exploration Server source — 2.12.0-explore.1

This is a server-only Java and Bedrock crossplay build derived from the user-supplied Infinity Armor v2.3.0 source. The original Java and Bedrock downloads remain unchanged. Start with `server/README.md` for installation, commands, and current limitations.

The Java Fabric mod runs the shared world. `CrossplaySupport.java` exposes the original custom items and blocks through Polymer, generates the Java texture pack, and exports Geyser mappings. Floodgate handles Bedrock identities. The original Java powers remain server authoritative. The launcher runs Fabric and Geyser together; it does not load the original Bedrock behavior pack.

`CommunityServer.java` adds homes, shared warps, teleport requests, rules, moderation, and safe server backups. `GameModes.java` keeps Survival, Creative, Hardcore, Minigames, and Adventure player profiles separate, with built-in minigames and adventure maps in `ModeMaps.java`. `Memberships.java` implements Free, Go, Plus, Pro, Ultra, and free code-gated Admin. Each permanent cosmetic rank requires all three achievements in its group: Go needs a stone pickaxe, an iron ingot, and Monster Hunter; Plus needs an iron pickaxe, a diamond, and an enchanted item; Pro needs entry to the Nether, a blaze rod, and entry to the End; Ultra needs the Ender Dragon defeated, an End gateway entered, and an End city found. These are server-tracked advancements for Java and Bedrock players; the highest completed group wins without requiring lower groups, an earlier earned rank persists, and existing temporary owner grants remain until they expire. Creative inventory items can trigger item milestones. Survival item trades offer an alternative permanent rank unlock. `AchievementRewards.java` adds six rank-independent power presets and eight earnable particle cosmetics. `RewardTrades.java` stores each paid receipt alongside debited inventory in the same vanilla player save; it never copies paid entitlements into the separate rewards JSON. See `server/REWARDS.md` and `server/TRADING.md`.

`LobbyServer.java` builds one dedicated hub dimension with a Main Hub and five connected mode lobbies. New players start there. `/hub`, `/lobbies`, `/lobby <mode>`, and `/play <mode>` work on Java and Bedrock. The hub uses a temporary empty inventory, while each gameplay mode's inventory, Ender Chest, health, and progress are saved independently. `server/LOBBIES.md` explains navigation and the generation marker.

`/guide` gives place-specific directions in the Hub, lobbies, maps, and game modes. `/best` and `/leaderboard <map>` expose persisted minigame times. The private dashboard shows current Java and Bedrock join details separately. On Apple Silicon Macs, `server/Start-Pinggy-Mac.command` can prepare the pinned official Pinggy client on first use and request two temporary tunnels; see `server/PINGGY_JOINING.md`. It is optional, and live addresses are never part of the public site or release archives.

## Build and validate

Use Java 21, Python 3.9+, and network access for checksum-pinned dependencies. The project pins Gradle 9.5.0 and Loom 1.17.21. `JAVA_HOME` must point to Java 21 when invoking Gradle directly.

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 tools/smoke_geyser.py --java /path/to/java
python3 tools/smoke_fullstack.py --java /path/to/java
python3 tools/package_crossplay.py
```

The native GameTest driver builds the mod, checks dimension fixture drift, and starts a disposable Minecraft world with release dependencies. The Python suite checks the launcher and packaging behavior. Geyser and full-stack smoke checks verify startup, Bedrock UDP response, shutdown, and a stopped-world backup. The full-stack check requires EULA acceptance already present in the local host folder; it never accepts the EULA itself. Neither smoke check establishes authenticated player login. See `server/VALIDATION.md` for the exact coverage and remaining device play-test work.

The packager writes server and source ZIPs under `dist/lobbies`. It includes explicit release files and reports, excluding worlds, keys, EULA files, account data, runtime downloads, and local panel tokens. Do not archive the whole `server` or `research` directory after running a world. The original powers guide is included as `ORIGINAL_GUIDE_2.3.0.md`.
