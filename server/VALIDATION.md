# Exploration release validation — 2.12.0-explore.5

Validated September 29, 2026 against the `2.12.0-explore.5` Fabric JAR and pinned Fabric, Geyser, and Floodgate dependencies. Automated checks used separate worlds. The owner's live server was backed up before this JAR was installed.

## Completed checks

- **176 native Minecraft GameTests passed with release dependencies.** They exercise Infinity gear and Creative inventory behavior, mode isolation, the Creative gear picker, course menus and six minigames, selected-map persistence, helpers, and the fixed-action approval queue, alongside earlier server features.
- **80 Python launcher tests passed.** They cover verified downloads, private dashboard control, owner settings, backups, Pinggy status and installation safety, command handling, port checks, and runtime behavior.
- **The Geyser localhost smoke check passed with the final JAR.** Geyser registered 24 custom items and 209 custom block overrides, answered a Bedrock UDP ping, and shut down cleanly. This does not authenticate a Bedrock player or verify an item held on an iPad.
- **Two full-stack cycles passed.** Fabric and Geyser started together in an isolated server, answered a Bedrock UDP ping, saved a stopped-world backup, restarted, and shut down cleanly. No player logged in during these checks.
- **An isolated copy of the live `2.12.0-explore.4` world upgraded successfully.** Its map-generation marker advanced to version 3 and recorded the six prior maps, two new minigames, and eight course-selector signs. This checks existing-world migration without changing the owner's original world during the rehearsal.
- **The live server upgrade completed after backups.** The `2.12.0-explore.5` JAR is installed, the server is running, and its world records eight maps and eight selector signs. The checked launcher log has no `ERROR` entry. This is a startup and world-state check, not an authenticated player join or gameplay check.

`tools/package_crossplay.py` requires fresh native, Python, Geyser, and full-stack evidence before making the release archive. It packages an explicit file list and excludes worlds, backups, account data, owner settings, private keys, local panel tokens, EULA acceptance, and downloaded runtimes.

## Remaining play tests

The new Creative Infinity gear picker and course menus use vanilla chest-style screens and icons, but they still need a real Java client and iPad Bedrock client test. Verify opening the picker, tapping an icon, holding and using the resulting weapon/tool, reopening it with the compass, and full-inventory behavior. For minigames, verify both hub signs and course start signs, all six playable courses, touch controls, timers, and reconnect behavior. Automated tests cannot prove the on-screen experience.

An authenticated iPad login on an earlier `2.12.0-explore.3` build exposed a Geyser Creative-inventory error. Custom items therefore remain out of Bedrock's built-in Creative catalogue; the server picker is the intended route. Geyser may hide custom-output recipes from Bedrock's recipe book, so manual crafting also needs an iPad test. Wardrobe rendering, backpack and player-trading menus, new block textures, helper behavior, flight input, and performance with a large public player count still need device testing.

The fixed AI action queue has automated tests, but a real OP4 owner and a live Codex session must separately review each exact proposal before the local console dispatches it. The server does not watch this chat, and no background approval runs while the chat is closed. Test that workflow with a harmless fixed action before relying on it. Optional model chat does not grant command execution.

Subscription checkout, renewal, cancellation, refund, and chargeback cannot be tested until the owner creates a Tebex store and connects it. Free Pinggy addresses are temporary and separate for Java and Bedrock; a local listener response does not establish that a friend can join from another network. Test authenticated joining on both editions from another network before inviting players.

## Reproduce

Use Java 21, Python 3.9+, and the checksum-pinned dependencies described in the source guide:

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```
