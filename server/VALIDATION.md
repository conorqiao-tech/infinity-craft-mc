# Bedrock stat forms, wearable armor and native helper weapons — 2.12.0-explore.13

Validated October 3, 2026 against the final Fabric JAR and checksum-pinned release dependencies. Native checks run in isolated worlds, not the owner's playable save.

## Completed checks

- **305 native Minecraft GameTests passed.** Four new checks exercise the real Cumulus form response codec: exact values, confirm/cancel, one-use tickets, replay and newer-form refusal, OP4 loss, changed player/AI sessions, changed capacity, non-finite input and independent attribute edits. A reviewed absolute health value remains valid after normal damage; attribute and reset reviews retain their comparison guards. A touch-transfer menu click claims all four Aurora pieces, including the chestplate. Native wearable equipment/assets and icons are selected for Bedrock even when a Java-pack state is present.
- Four new combat checks exercise actual native mace fall bonus, real spear use ticks and kinetic charging damage without a substitute melee strike, a close-range mace fallback with attack cadence, and spear/mace side-effect guards. Forced spear calls cannot hit distant targets or pierce walls. Unapproved players cannot take damage, knockback or dismount, including incidental mace splash. The existing aerial check now verifies real elytra equipment and native LivingEntity glide state during physical motion, followed by native attack and landing. Prior ceilings, walls, pursuit, role rotation, prediction, cooldowns, player approvals and permission-revocation checks remain.
- A deterministic new dismissal regression constructs a TreeMap node with two children and verifies the exact selected helper is discarded while its peer and another owner's helper remain loaded and saved. A native removal stack identified the old bug: reading the live map entry's key after removal could select its successor and discard the wrong entity. Production now captures the exact UUID before changing the map. This explains the previously unresolved intermittent helper-pursuit removal failure. Temporary diagnostic instrumentation was removed from the final build.
- Native combat fixtures now declare their full arena bounds and use distinct temporary simulation tickets with native idle reset. Existing cross-world recall checks retain UUID, health, effective attributes, reset history, profile and world tracking after transfer. All earlier mode, map, lobby, reward, trading, menu, Admin, helper and answer-only Codex checks also passed. No paid provider is contacted by these tests.
- **98 Python launcher tests passed.** Asset checks resolve the original Java item/equipment artwork, verify revision-matched Bedrock pack/module versions, and cover the dashboard, runtime, backups, downloads, ports, commands, Pinggy, Dynu and provider configuration. Original texture checksums are preserved.

- **Final Geyser smoke passed:** 24 custom items and 209 custom block overrides registered, Bedrock UDP ping answered, and the bridge shut down cleanly. This does not authenticate a player or verify rendering.
- **Two full-stack cycles passed:** final Fabric/Geyser build started in isolated mode worlds, answered UDP ping, loaded the saved hub while remaining command-responsive, saved a stopped-world backup, restarted and shut down without fresh ERROR entries. No player logged in.
- **Live installation completed:** a stopped-world ZIP passed its CRC check, and the prior mod, private configuration and launcher settings were backed up before installing `2.12.0-explore.13`. The server restarted, reports the new mod version and has no fresh startup ERROR entries. Private configuration and settings were byte-checked as preserved. No player health, capacity or other stat was changed by installation.

## Review and client limits

The requested Gemini–Claude–Git consultation used the focused source and checks without credentials. Gemini 3.8 Flash High/high effort reached its quota; Claude Opus 5.5/max effort timed out without advice. Neither request was automatically retried or substituted. No external model review or effective model/effort verification was established. Local mapped Minecraft/Floodgate code review and the checks above are the available evidence.

A real iPad and Java client still need to check the new stat forms, touch controls, worn armor and combat appearance. Native tests establish server behavior, not client rendering or public-server load capacity. The Bedrock stat UI now has its own number field and Confirm button; Java retains private exact-number entry and chest review. Manual OP4 stat edits do not wait for Codex approval. Current health is bounded by Health capacity: increase capacity before filling more health. Edited combat attributes can exceed their normal caps while native physics, numeric storage and client limits remain.

**Armor sets** provides all four Aurora/Ember pieces through existing unlock and empty-inventory-slot checks. Bedrock receives native dyed-leather/elytra equipment assets and icons; Java with the resource pack keeps custom equipment artwork. The Infinity winged chestplate intentionally uses an elytra on Bedrock. Custom Creative gear remains available in two placeable inventory shulker boxes because the pinned Geyser Creative catalogue does not support dragging its custom entries back to Java items. Device opening/taking and crafting remain play tests.

Ultimate Finals helpers are server-controlled golems that equip real native netherite spears, maces and elytras. Spear contacts use Minecraft's piercing ray and contact cooldowns; discrete melee attacks retain their cadence. A clear aerial route briefly enters native glide, stops gliding and switches to a falling mace hit with its native bonus. Golems retain their normal body renderer, which does not display held weapons or wings. These mechanics do not reset damage immunity or guarantee a totem bypass. Status reports show the actual held item/glide state. No per-tick AI requests control combat.

Player-target orders, server actions and code changes retain the owner's approval and a separate live Codex review. The in-game Codex answer connection receives limited supplied facts in separate answer-only sessions; it does not share this live chat's memory, approve its own proposals or run commands. Prior release evidence includes successful actual Codex responses using the configured native CLI 0.155.1 and gpt-6-luna. Compatibility review is required before other native CLI versions run. Host-console operators cannot be independently identified as Codex by Minecraft.

The owner previously confirmed an external Bedrock join through the fixed Dynu hostname. This patch does not establish a fresh remote authenticated Java or Bedrock join. Dynu maintains DNS and requires router forwarding; it is not a tunnel or a promise of a hostname forever. The public Vercel guide site does not host the world or expose the private dashboard. Free Pinggy addresses remain temporary. Supporter checkout stays off until a Tebex store and tested billing integration exist; planned monthly USD prices remain Go $50, Plus $75, Pro $100 and Ultra $200. Earned ranks remain free and permanent; Admin and OP are never sold.

## Reproduce

Use Java 21, Python 3.9+ and the checksum-pinned dependencies:

```sh
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```

Packaging requires fresh native, Python, Geyser and full-stack evidence. Explicit file lists exclude worlds, backups, accounts, owner settings, keys, panel tokens, EULA acceptance and downloaded runtimes.
