# Exploration release validation — 2.12.0-explore.3

Validated September 28, 2026 against the `2.12.0-explore.3` Fabric JAR and pinned bundled dependencies. The build and automated worlds were separate from the owner's live world. The release was then installed in the live world after a stopped-world backup.

## Results

- **156 native Minecraft GameTests passed**, including the framework baseline. They cover Infinity gear, crossplay mappings, community commands, separate inventories and modes, lobbies, ranks, achievement rewards, item trades, helpers, backpacks, two-party player trading, Creative tools, physical minigames, place-specific `/guide` instructions, persisted personal bests, and the public minigame leaderboard. The new test checks that optional USD supporter prices are informational, checkout is unavailable, and item-trade costs remain distinct.
- **80 Python launcher tests passed.** They cover downloads and checksums, private dashboard control, owner settings, backups, Pinggy tunnel status (including expired records beside a new running tunnel) and first-use installer safety, command handling, port checks, and runtime behavior. The source-only GitHub checkout skips one mapping-texture test when its generated exports are absent; the full source ran it.
- **Geyser mapping and localhost Bedrock UDP checks passed.** The generated resource pack has the referenced custom item and block textures. The bridge shut down cleanly.
- **Two isolated full-stack starts passed** with Fabric and Geyser, including all mode dimensions, Bedrock UDP response, restart, stopped-world backup, and clean shutdown. This check used the owner's already accepted EULA; it did not accept an agreement.
- **The live Mac server was upgraded to this release.** The existing world and owner settings were preserved, and the server restarted successfully. Both Pinggy tunnels reported ready. Java status handshakes and Bedrock RakNet pings succeeded through their temporary public addresses as well as locally. These network checks do not establish authenticated player joining. The planned Tebex subscriptions have no active checkout or payment integration in this release.

`tools/package_crossplay.py` checks the exact native and Python test counts, report freshness, smoke-test markers, bundled SHA-256 values, and ZIP integrity before making the release. It packages explicit files only. Worlds, backups, account data, owner settings, private keys, local panel tokens, EULA acceptance, and downloaded runtimes are excluded.

## Remaining play tests

An earlier 2.9.0 server recorded an authenticated Bedrock login on this Mac. That does **not** establish authenticated iPad play for this release. Two-player Java and Bedrock login, touch controls, wardrobe rendering, backpack and trading menus, new block textures, helper behavior, flight input, and performance with a large public player count still need device testing. A Bedrock UDP response only verifies the network listener. Subscription purchase, renewal, cancellation, refund, and chargeback cannot be tested until the owner creates a Tebex store and connects it.

Free Pinggy addresses are temporary and allocated separately for Java and Bedrock. Public status and ping responses verify transport through the tunnels, but do not prove that an outside-network player can join. Test authenticated joining on both editions from another network before inviting friends.

## Reproduce

Use Java 21, Python 3.9+, and the checksum-pinned dependencies described in the source guide:

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```
