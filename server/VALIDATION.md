# Explore.51 r1 — wing appearance comparison candidate

The owner confirmed on iPad that installed explore.51 helpers hold separate
formation positions and Infinity Chestplate can glide. Its appearance failed:
the device showed ordinary gray elytra without the custom chest shell.

This resource-only candidate redirects both cosmetic equipment-slot queries to
the owning entity. The nested pack changes exactly the combined-elytra
attachable and manifest revision `[2,13,51001]`; the other 263 entries are
byte-identical, including every PNG, model, controller and animation. The exact
explore.51 mod, Geyser extension, Java runtime, dependencies and mappings remain
unchanged. No gameplay source or live server file changes for this candidate.

Fresh checks cover the complete 451-test Python suite with ResourceWarning as an
error, 192 native Geyser property/codec cases across protocols 1001/2168/2193,
255 actual hotbar inputs plus overflow/Survival refusals, and 85 saved/rejoined
item-count cases over three genuine reconnects. A separate disposable native
projection test also verifies both real winged items emit native elytra with
their exact original Polymer marker and preserve custom names/components.
The existing 722-test native baseline remains bound to all 784 unchanged Java
source/resource hashes; that full suite was not rerun for this resource repair.

Neither these checks nor the modeled Molang context test runs the actual iPad
renderer. The owning-player chest-slot mapping and final visual result remain
unverified until a separately approved installation and device comparison.
See [the repair scope and device checks](WING_APPEARANCE_REPAIR.md).

# Explore.51 — GPT squad plans, formations and explicit player groups

The following records the explore.51 source/package baseline. It was subsequently
installed after separate owner approval. Minecraft stays on the pinned
experimental **26.4 Snapshot 5** stack. The resource candidate above is separate
and is not installed by building, committing or merging it.

## Behavior and boundaries

- **Admin editor → player → stat → Edit matching players together** starts an
  empty named selection of living online players sharing the selected base
  value. Java and iPad forms show every selected target's normalized changes and
  any health/absorption capacity increases before one confirmation. Previewing
  leaves native save NBT unchanged, including uncached default attributes.
  Exact player/connection/world/mode/permission and attribute/history drift,
  replay, changed capacities and normalized zero-health groups are rejected.
  Saving uses ordinary per-player persistence, not a cross-file transaction.
- Registered helpers receive one-time finite defaults: max health **1000**,
  attack **100**, armor **30**, toughness **20**, knockback resistance **1**.
  Recorded manual overrides, non-default bases, modifiers and reset history
  remain intact. Existing current health stays unchanged; actual new spawns
  alone fill their resulting capacity. No operator permissions are granted.
- Idle FOLLOW helpers receive distinct **Escort**, mirrored **Wedge** or
  capacity-spaced **Ring** slots. Native navigation reaches exact supported
  slots, turns with the owner's yaw and climbs ordinary steps. Blocked slots
  hold; distant chunks are not loaded and following does not teleport. The
  per-tick cache releases disconnected owners and removed entities.
- **AI Helpers → Ask GPT for a squad plan** sends one explicit goal plus bounded
  squad counts/settings through the existing configured GPT service. It shares
  the normal durable request budget, pending limits and timeouts. Native
  movement/combat does not call GPT each tick. Strict model JSON can propose
  only movement, regular/Ultimate Finals profile and formation, or no action.
  Each exact live roster/plan needs owner consent and a separate live Codex
  console approval. Stale sessions, changed records, expiry, restart and replay
  cancel proposals. GPT has no commands, code, stat edits, player-target choice
  or approval authority. Planner transport tests use mocks. The post-install
  real-transport diagnostic stopped before a model turn: Codex inherited a
  global instructions file despite the current isolation configuration. The
  empty-source guard remains enforced; a real GPT response is not verified.

The post-install review also found that lowering grouped maximum health or
absorption omits its secondary current-value clamp from the preview. No live
player stat changes were used to test it. That separate preview defect is not
part of the wing resource candidate.

## Fresh candidate evidence

- **722 native tests in 39 groups**, zero failures/errors/skips, bind **784 Java
  source/resource hashes**, exact release/test JARs and dependency lock. This
  retains all earlier test methods and adds 49 native methods in seven classes.
- **434 Python launcher/resource tests** pass with ResourceWarning treated as
  an error, including 15 independent saved-helper migration verifier cases.
- **192 native wing-property cases** and **303 armor codec round trips** pass
  across protocols **1001, 2168 and 2193**, covering twenty armor identities and
  all five suits. All **424 tracked PNGs** match main. The prepared pack's 265
  entries differ from explore.50 only in the version manifest, now
  **[2, 13, 51000]**. These headless checks do not execute the iPad renderer or
  Molang, and do not prove a physical device's gliding/input behavior.
- Fresh Geyser startup covers **66 item identities** and three protocols. The
  Via/native wire proof passes **255 real hotbar inputs**, **198 overflow
  refusals** and **255 Survival refusals**. Persistence passes **85 disk/rejoin
  item-count cases** across all identities and **three genuine reconnects**.
- The final application hash sustains **90.096 seconds** of translated Play,
  decodes **186 chunks**, acknowledges **five teleports** and completes native
  respawn through the actual pinned Geyser runtime. All **four authentication
  negatives** fail before Play. The **21 codec cases** and **four native ACK
  cases plus the framework baseline** retain verified earlier evidence only
  because all five compatibility dependency hashes remain identical; the full
  application and copied-world exercises were rerun for this exact app hash.
- Copied-world conversion preserves the original **92-file** fixture, all
  **four offline player files** byte-for-byte, **nine mod-state files** and the
  saved helper's identity, current health, equipment, edited attributes and
  reset history. Its only derived migration is untouched attack **15 → 100**
  plus marker **1**. The verifier independently derives allowed changes from
  original native NBT and saved ownership, including sparse attribute rows;
  receipt claims cannot authorize other changes. No live world or terrain was
  modified, and no player logged into this conversion fixture.
- Two isolated full-stack cycles pass startup, loopback response, restart,
  backup and clean shutdown. AI provider configuration, accounts,
  credentials, permissions, production hosting and live resources are unchanged.

Release mod SHA256:
`c3e427b16776e9a38effa75e3b5350f0cbed8fbb277e05e3e3c0cb66c6a784ab`.
The canonical sanitized proof is `research/integration/snapshot5-integration.json`;
private evidence and raw saves are excluded from public archives.

---

# Explore.50 — Infinity Chestplate flight and instant helper batches

The candidate adds native gliding to the existing Infinity Chestplate while
preserving its item ID, armor components, unbreakable equipment, saved item data
and complete Infinity suit powers. Both winged chest items use native elytra on
the iPad connection; two batched player properties select their matching armor
shells and folding wings. All 424 tracked PNGs and accepted armor joints remain
unchanged. Ordinary and held elytra retain their native rendering path.

**AI Helpers → Create helpers in a batch** creates the exact chosen quantity and
profile in one activation. Both owner and server limits are fifty total, counting
saved unloaded helpers. Native tests create fifty from an empty roster, add
forty-nine beside an existing edited helper, and enforce shared server capacity.
The complete batch needs clear, loaded, dry solid ground within sixteen blocks.
Insufficient placement or ownership persistence cannot produce a smaller partial
batch. Existing helper identities, names, health, stats and profiles are retained.
Java and native iPad form tests cover quantity/profile selection, capacity-full
controls, capacity changes, cancellation, session changes and replay refusal.
These are functional capacity tests, not a multiplayer performance benchmark.

## Final candidate evidence

- **673 native tests in 32 isolated groups pass**, with zero failures, errors or
  skips. Provenance binds all **770 Java source/resource hashes**, the release
  mod, original test JAR, aggregate report and exact dependency lock.
- **419 Python launcher/resource tests pass**, with ResourceWarning treated as
  an error and zero failures, errors or skips.
- **192 native wing-property cases pass**, with sixty-four each on protocols
  **1001, 2168 and 2193**. They cover both real winged items, atomic paired flags,
  equipment/slot guards, observers, replacement, respawn and dimension changes.
  **303 armor codec round trips pass** across twenty identities and all five
  suits, including the actual native Infinity Chestplate elytra fallback.
- Fresh Geyser startup covers **66 identities across three protocols**. The full
  Via/native wire proof passes **255 hotbar inputs**, **198 overflow refusals**
  and **255 Survival refusals**. Persistence passes **85 disk/rejoin item-count
  cases**, all sixty-six identities and **three genuine same-UUID reconnects**,
  retaining renamed/damaged equipment, backpacks and observer state.
- The exact Snapshot 5 stack passes **21 codec tests** and **four targeted native
  ACK cases plus one framework baseline**. The actual Java 26.2 codec sustains
  **90.095 seconds** of translated application Play, decodes **180 chunks**,
  acknowledges **five teleports**, receives actual Infinity Chestplate and
  Convergence Wings packets, and completes a native respawn. All **four
  authentication negatives** are rejected before Play.
- **Two isolated full-stack cycles pass** startup, local response, backup,
  restart and clean shutdown. A separate **92-file** world copy converts
  DataVersion **5023 → 5125**, preserving four offline player records, nine mod
  records, saved helper identity/equipment/stats/28 attributes, difficulty and
  the original fixture. This is a bounded copy test, not a guarantee for all saves.

Failure-path tests retain the original ownership recovery copy after a native
insertion and rollback-write failure. Roster writes and helper behavior remain
frozen pending host recovery. Full validation also reproduced terminal guardian
removals whose entity section never emitted an unload callback; the ordinary tick
now uses existing retirement rules without freeing nonterminal unloaded claims.
The original fifty-helper and guardian lifecycle tests remain intact.

The final mod SHA256 is
`07d40f9a3f18284b30cda2092d963a64d2bffbab38f2fbe178a3b6460cbf25eb`.
Packaging rejects stale native, packet, persistence, Snapshot and wing-property
evidence. These checks use isolated research worlds and do not install the mod.

## Live installation and device limits

The live server remains the separately approved **explore.49**, running Minecraft
Java **26.4 Snapshot 5**. The owner confirmed helper following and combos there;
Infinity Chestplate flight was the remaining reported gap. Explore.50 requires
separate owner installation approval and a verified stopped-world backup.
Actual iPad combined appearance, touch gliding and the new batch form still need
device acceptance. Synthetic fresh-key probes do not establish an authenticated
Xbox login; codec checks do not execute client Molang or prove final pixels.
Live worlds, player data, private settings and AI approvals were not changed.

# Historical combined chestplate and wings — explore.49 source evidence

The candidate keeps the existing `convergence:chestplate` identity, armor
protection and native elytra flight. Its conditional iPad appearance adds the
accepted same-set Convergence torso and arms plus original folding wings in one
chest slot. An absent/false property and held items keep Minecraft's native
elytra renderer, materials, textures and animations. The accepted armor joints,
geometry and all 424 tracked PNGs remain unchanged. See
[Winged chestplate](WINGED_CHESTPLATE.md) for equipment and device checks.

**Elemental Forge → Get Convergence Wings** claims the real item into one empty
slot with the existing access checks and native save. It never auto-equips or
replaces owned gear. The iPad helper detail keeps **Enable combos and follow me**
visible, explains unavailable states and shows saved/live coordinates without
loading, teleporting or replacing a helper.

Full validation reproduced an existing native contact defect: `IronGolem.doPush`
can randomly assign a hostile target even after Stay removes AI goals. The
candidate suppresses only that native target setter for tagged helpers. Both
entities' physical pushing and ordinary golem acquisition remain; the helper
controller and both player-target approvals still govern combat. A deterministic
collision regression and the unchanged real fifty-helper lifecycle pass.

## Final candidate evidence

- **655 native tests in 30 isolated groups pass**, with zero failures or skips.
  Provenance binds all **767 Java source/resource hashes**, the exact release mod,
  original test jar, dependency lock and aggregate report.
- **405 Python launcher/resource tests pass**, with ResourceWarning treated as an
  error. New resource checks cover native defaults, guarded owner/chest context,
  wing-only animation channels, resolved controller states and numeric clearance.
- **84 offline native wing-property cases pass** on protocols **1001, 2168 and
  2193**. They exercise the registered schema, five original Java translator paths,
  the native Bedrock inventory guard, observers, replacement, removal and respawn.
  The proof binds the current extension sources/jar, prepared pack, lock and
  exported catalogue. It opens no listener and executes no client Molang.
- **303 native armor codec round trips pass**. Geyser starts with **66 identities
  across three protocols**. Supplemental Via/native checks pass 255 hotbar inputs,
  198 overflow refusals, 255 Survival refusals and 85 disk/rejoin count cases across
  all 66 identities and three genuine same-UUID reconnects.
- The final Snapshot 5 stack passes **21 codec tests** and **four targeted native
  ACK cases plus one framework baseline**. The actual Java 26.2 codec sustained
  **90.093 seconds** of translated application Play, decoding **172 chunks**,
  acknowledging **five teleports**, receiving the real gear and completing a
  native health-zero/client-requested respawn. All four authentication negatives
  were rejected before Play. The synthetic fresh-key identity is distinct from
  an Xbox-authenticated iPad login.
- **Two isolated full-stack launcher cycles pass** startup, local response,
  backup, restart and clean shutdown. A separate **92-file** world copy converts
  DataVersion **5023 → 5125**, preserving four offline player records, nine mod
  records, saved helper identity/equipment/stats/28 attributes and Normal
  difficulty. The original fixture remains unchanged. Native physics settled
  the helper before a controlled lab-only frozen save; no entity/NBT edits or
  terrain refresh were used. This bounded copy test does not guarantee all saves.

The final mod SHA256 is
`29e0d42d15e93b7309b13a5f13ed8baba2debad53e362223f2d2c6a9afd49b92`.
Packaging requires current native, inventory, snapshot and wing-property proofs;
stale evidence is rejected.

## Installation and device limits

The live server remains **explore.48**, running on Minecraft **26.4 Snapshot 5**.
The owner confirmed its four equipped armor pieces were stable. Explore.49 has
not been installed. Actual combined iPad armor/wings, native touch gliding,
ordinary elytra comparison, held-item behavior and helper menus/following/combat
still need device checks after a separately approved installation. The offline
property fixture explicitly flushes native property updates; it does not prove
client rendering or scheduled update timing. Building or merging does not grant
live installation approval. Worlds, player data and live AI approvals were not
changed by these checks.

# Historical Snapshot 5 migration — explore.48 source evidence

The final pinned application and dependency stack passes **648 native tests in
30 isolated groups**, with zero failed or skipped cases, against Minecraft Java
26.4 Snapshot 5, Loader 0.20.1, Fabric API 0.164.1 and the public-source compatibility
ports. The final provenance binds **767 Java source/resource hashes**, the release
mod, original test JAR, aggregate report, dependency lock and fork source inputs.
All **385 Python launcher/resource tests pass**, with ResourceWarning treated as
an error and zero errors or skipped cases.

The custom translator passes **21 codec tests**. The combined native login stack
passes **four targeted acknowledgment tests plus one framework baseline**.
An isolated connection using the actual Geyser-bundled Java 26.2 codec sustained
full-application Play for **90.0804 seconds**, decoding **49 chunks**, acknowledging
two teleports and completing a native respawn. Inventory, equipment, advancements
and registry packets were decoded through the exact Snapshot 5 protocol 1073742167
with the tested Infinity gear and no probe errors. The advancement and dense
biome-palette repairs were included in this final run.

All **four strict negative probes pass**: invalid and tampered Floodgate keys,
an identity outside the native whitelist and an unsupported ordinary older Java
login are refused. Exact-snapshot Java retains normal online authentication;
the translated path retains encrypted Floodgate identity verification and native
whitelist enforcement. Fresh-key actual Geyser startup and its local UDP response
also pass. The sustained Play client uses a synthetic fresh-key identity, so
these checks do **not** prove an Xbox-authenticated iPad login or device rendering.

Fresh Geyser catalogue checks cover **66 identities over three protocols**.
The final supplementary wire checks pass **255 inbound hotbar packets**,
**198 overflow refusals** and **255 Survival refusals**. Persistence checks pass
**85 saved-item count cases**, all **66 identities** and **three genuine same-UUID
reconnects**, retaining renamed/damaged gear, backpacks and observer state.
These checks execute the complete Java 26.2→26.3→Snapshot 5 translation chain with
the final Via artifacts; their receipts bind to the final application and sources.

Armor transport passes **303 native codec round trips**. All **424 tracked PNGs**
remain byte-identical to the explore.47 baseline `b6aec0c`; accepted armor geometry
and artwork have not been redesigned. **Two isolated full-stack launcher cycles**
pass startup, local response, backup, restart and clean shutdown. A local response
does not establish a public authenticated connection.

A separate private world copy converted from **DataVersion 5023 to 5125**, without
player logins or terrain refresh. Its 92 input files included four offline player
files, nine mod-state files and one saved helper. Offline player and mod-state
bytes, helper stable identity/equipment/stats and saved difficulty were preserved;
the original copy remained unchanged. This is a bounded conversion test, not a
claim that every save is safe. After native conversion, recovery requires the
complete preupgrade world and matching old executable stack; replacing JARs or
restoring only terrain is insufficient. See [World refresh](WORLD_REFRESH.md).

Complete corresponding source and connection limits are documented in
[Snapshot 5 compatibility](../compatibility/snapshot5/README.md). Packaging and
live installation are separate steps. **The live server remains explore.47 until
installation. Actual Xbox/iPad authenticated joining, menus, armor motion and
flight visuals still require a real device check.** The explore.47 evidence below
is historical and must not be reused as explore.48 release evidence.

# Historical following, Infinity loadouts and squad combat — explore.47 candidate

This candidate repairs player-sized following and repeated native combat, adds
original Infinity weapon loadouts, exact nearby-target selection and paginated
controls for fifty saved helpers. Select **AI Helpers → helper → Enable combos
and follow me** to set Ultimate Finals and Follow on that same entity. See
[HELPER_COMBOS.md](HELPER_COMBOS.md) for controls and practical limits.

## Reproduced behavior and repairs

| Reproduction | Previous behavior and cause | Corrected behavior |
| --- | --- | --- |
| Follow an owner into a one-wide, two-high passage. | A player-shaped avatar retained the much larger native golem body; navigation could not enter. | Only tagged helpers use player-sized default collision and eye height. Native scale applies once; ordinary golems remain unchanged. |
| Let a real-AI enemy pursue an Ultimate Finals helper into close range. | The close-mace branch returned before aerial planning, so an enemy that kept closing could prevent another combo. | A ready close target can trigger a checked short mace arc; the normal spear windup remains at suitable range. |
| Land or flank after shrinking the collision body. | The old fixed separation exceeded the smaller body's real melee reach. | Body-based separation stays outside collision and within native reach; no expanded hitbox or fake contact is added. |
| Move an acquired enemy outside the owner's small fresh-acquisition sphere. | Repeated sensing discarded an otherwise eligible moving fight. | Ultimate Finals retains the same eligible target within leash, sight and combat bounds; a fresh direct owner threat can take priority. |
| Give a teammate an incomplete spear windup or recent strike. | It could reserve the aerial turn before being ready to launch. | Only a ready teammate with a checked route receives a turn; one aerial lane per owner and target remains. |
| Open status after a mob or player order expires. | Native target getters and a Java menu icon could retire orders while displaying them. | Permission views and both menu status paths are pure reads. Combat still rejects expired permission; normal lifecycle controls retire invalid orders. |

## Verified native and crossplay behavior

The Java25 build and **648 native tests in 30 isolated groups pass**, with zero
failed or skipped cases. All **766 Java source/resource hashes**, release mod,
original test JAR and aggregate report match the final provenance.

Normal lifecycle tests use real-AI enemies, ordinary gravity and knockback, and
actual charge, glide and falling `MACE_SMASH` contacts. They do not manually call
combo stages, clear damage immunity or manufacture fall distance. The repeated
chase arena has supported terrain and perimeter walls; only initial finite
health is enlarged so the victim survives multiple Infinity hits. Small direct
mechanic fixtures remain separate from these lifecycle proofs.

Seven follow cases cover actual tunnel movement, narrow creation, safe floor and
overlap checks, owner movement packets, hovering owners, the follow leash and
native scale/stat/UUID preservation. Seven readiness cases include a real
fifty-helper lifecycle: native enemy AI acquisition, movement and damage,
at most one aerial lane, Stay, a reviewed player order and exact expiry. This
is functional capacity coverage, **not a multiplayer performance benchmark**.

Encoded Java/Bedrock menu tests select all fifty saved helpers, check page
navigation and stale/replayed forms, and preserve saved identities and edited
stats. Exact mob orders cannot spill damage into another animal, pet or helper.
Selecting a player still creates only a pending proposal; the owner's approval
and separate live Codex approval remain required.

Eight weapon cases verify full original Infinity components on native weapon
classes, continuous real spear use, real sword/mace damage and unchanged ordinary
golems. All three weapons pass the actual native codec for Java without the pack,
Java with the pack and Bedrock. Client stacks match the original catalogue item's
normal Polymer projection; server held/active objects, components and charge
remain unchanged. Client tooltip projection is distinct from server components.

Fresh Geyser startup covers **66 catalogue identities over three protocols** and
the offline armor transport proof covers **303 codec round trips**. Two isolated
Fabric/Geyser launcher cycles pass startup, local UDP response, backup, restart
and clean shutdown. No authenticated player login or real device rendering is
claimed. Native embedded-player tests retain the known Polymer packet-context
diagnostic, and fresh harness folders can log missing initial server.properties
before generating defaults. This is not a clean-log claim for the native harness;
the separate Geyser and full-stack startup logs contain no ERROR records.

All **424 tracked PNGs** match merged main `f75de4d` byte-for-byte. The prepared
Bedrock pack's **260 entries** match installed explore.46 except the manifest
revision `[2,13,46000]` → `[2,13,47000]`; UUIDs, armor models and artwork payloads
remain unchanged. The owner's accepted armor repair stays the visual baseline.
Actual iPad helper menus, equipment appearance and live multiplayer behavior
still need their device checks after separately approved installation.

Fresh supplementary checks pass **255 actual inbound hotbar packets**, **198
overflow refusals** and **255 Survival refusals**. Saved-item checks pass **85
disk/rejoin item-count cases**, all **66 identities** and **three genuine
same-UUID reconnects**, retaining renamed/damaged gear, backpack and observer
state. **381 Python launcher/resource tests pass**, with ResourceWarning treated
as an error and no skipped cases. All required receipts bind to the final source
and artifacts; the package verifier checks those bindings before writing archives.

Source preparation does not install or restart the playable
explore.46 server, refresh terrain or change its private settings or approvals.
Earlier sections below are historical records, not current installation receipts.

---

# Helper combo reliability — explore.46 historical source candidate

The current source improves ready-only squad aerial turns, checked landing
adjustments for a moving target, close-range mace selection and native spear
charge continuity. Both helper menus expose the shared observed phase/reason;
Java status also shows location and health without mutating combat state. See
[HELPER_COMBOS.md](HELPER_COMBOS.md) for setup, fallbacks and limits.

## Reproduced failures and corrections

| Reproduction | Previous behavior and cause | Corrected behavior |
| --- | --- | --- |
| Put an Ultimate Finals helper at point-blank range to a hostile. | Each control pass equipped a spear before selecting a mace, resetting use state and creating an unstable follow-up. | Inspect native spear reach before equipping; retain the close mace with a small return margin and ordinary hit cadence. |
| Give a squad teammate a cooldown, blocked route or unavailable launch position. | Clock rotation could reserve the aerial turn for that teammate. | Select the oldest ready, safely launchable teammate; keep one lane per owner/target. |
| Move the same target sideways during an aerial attempt. | The landing remained fixed at launch. | Recheck a bounded replacement every five ticks; unsafe replacements retain the checked route. |
| Move a hostile just outside a Guard anchor between control passes. | Native weapon contact checked the hostile type but omitted the anchor boundary. | Recheck both helper and victim leashes at native damage contact. |
| Open Java helper status with an expired reviewed player order. | The old command-style status path could clear the order while displaying it. | Both menu paths read the shared status without changing the order or combat state. |

## Fresh source verification

The Java25 build and **615 native game tests in 24 isolated groups passed** with
zero failures or skips. All 757 Java source/resource hashes, the release mod,
original test JAR and aggregate report match the final provenance. Seven new
recovery cases and four new status cases exercise the fixes below. The normal
lifecycle test observes real spear use, glide, descending mace and health loss
with Minecraft's actual `MACE_SMASH` damage source; it does not force a fall
distance, call the combo stages or invent damage. Close-range damage retains
the twenty-tick ordinary strike cadence.

**381 Python launcher/resource tests passed**, treating ResourceWarning as an
error, with no skipped tests. All **424 tracked PNGs** are byte-identical to main
`015372b`; the accepted armor attachment models and native equipment protections
remain. Fresh Geyser startup checks pass 66 catalogue identities across three
protocols, and the offline armor transport check passes **303 codec round trips**.
Actual supplemental wire checks pass 255 inbound hotbar cases, 198 overflow
refusals and 255 Survival refusals. Genuine persistence checks pass 85 item/count
disk and rejoin cases, all 66 identities and three same-UUID reconnects; renamed
and damaged gear, backpacks and observer state survive.

Two fresh isolated Fabric/Geyser launcher cycles pass catalogue readiness,
responsive cold-lobby loading, local UDP responses, backup, restart and clean
shutdown without ERROR entries. No authenticated player login is claimed.
The embedded GameTest worlds retain the existing Polymer missing packet-context
diagnostic for mock players; two first-use test folders also logged absent
server.properties before generating defaults. These are not clean-log claims
for the native harness. No such errors occurred in the separate full-stack or
Geyser startup checks. A preliminary focused run caught a simultaneous test-file
edit and was not used as release provenance; the final full suite verifies the
stable completed source.

The acceptance checks passed:

- A cooling-down, out-of-range or route-blocked teammate cannot consume the next
  aerial turn; only one helper per owner uses a given target's aerial lane.
- A moving eligible target gets bounded landing updates only through checked
  loaded terrain. Unsafe motion retains the last checked landing; obstruction,
  water, target invalidation and leash changes must recover without teleporting.
- Normal lifecycle combat preserves native spear use/charge, uses a mace inside
  spear minimum reach and returns to native spear contact at suitable range.
  Falling mace and follow-up retain reach, sight and native hit cadence.
- Java and native Bedrock summaries show phase plus reason. Reading status must
  not navigate, damage, switch a weapon or mutate orders/targets; pauses and an
  expired player order must hide cached attacking phases.
- Existing owner/live Codex approval gates, player/team/pet protections, saved
  helpers and stats remain intact. No damage-immunity reset or totem bypass is
  introduced.

The owner's accepted explore.45 armor repair remains the visual baseline; these
helper changes make no new armor/artwork claim. Source preparation does not
install or restart the live server, refresh terrain or change approval queues.
Actual iPad controls, rendered helper motion and multiplayer behavior remain
separate checks after owner-approved installation. Earlier sections below are
historical validation records, not the current live-installation receipt.

---

# Native armor attachment — explore.45 resource repair r1 (historical candidate record)
The installed explore.45 mod is running. The owner's latest iPad photos show
an unresolved waist/thigh gap, displaced sleeves and motion flicker. The earlier
lowercase-binding candidate failed this device acceptance check. This new,
separately packaged repair is **not installed** and does not claim a device pass.
Older live-state statements below are historical; private installation receipts
and this section distinguish installed artifacts from source candidates.

The 21 active original armor/backpack models now use the exact flat same-name
joint contract selected by Mojang's native armor. All 41 bones keep their original
pivots and 70 cuboids; only names and explicit binding fields change. The two
controllers change matching bone keys only. All 381 prior PNGs, attachables,
item identities, materials, camera/UI expressions and inactive flight geometry
remain unchanged. The prepared pack revision is `[2,13,45001]`, with stable UUIDs.
See [ARMOR_ATTACHMENT_REPAIR.md](ARMOR_ATTACHMENT_REPAIR.md) for the diagnosis,
pinned native references, reproduction and actual iPad acceptance steps.

**381/381 Python tests pass**, ResourceWarning treated as an error, zero skips.
The 24 new cases include six native-contract/pose regressions, seventeen scoped
package refusal cases and generator reproduction of all 21 active models.
The 400 posed skin-containment comparisons are conditional on documented
same-name owner transform lookup; they do not execute the Bedrock renderer or
prove the client's prior explicit-binding matrix formula. Exact CamelCase names,
missing/doubled root assumptions, bad pivots/offsets and unwanted hierarchy are
checked. Existing source/prepared camera/UI, held-only/wrong-slot, alias, seam,
original pixel provenance and vanilla-equipment protections pass.

Fresh native Geyser equipment checks pass all **303 codec round trips** for five
suits/twenty pieces across three protocols. Fresh bridge startup passes 66
catalogue identities, 255 hotbar selections, 510 cursor/drop requests, 594
Survival refusals and 5,427 response/resync packets. Held equipment retains 57
native cases/69 packets. The real supplemental wire check passes 255 framed
inputs with 198 overflow/255 Survival refusals. Fresh saved-item checks pass 85
item/count cases across three genuine same-UUID reconnects. Two isolated
full-stack startup/save/backup/restart/shutdown cycles pass; regular Geyser and
full-stack logs contain no ERROR/Exception records.

The Java mod, test JAR and all 755 Java source/resource hashes match the accepted
explore.45 baseline. Its **604/604 tests in 22 groups are retained evidence**,
not a freshly rerun complete native suite for this resource-only repair. The
baseline's documented embedded-player Polymer packet-context diagnostic remains;
no unrelated dependency or gameplay change is included.

The four-file repair ZIP is pinned to the immutable explore.45 server package.
It verifies 24 changed pack entries and 236 byte-identical entries, plus unchanged
mod/dependencies/runtime/mappings. ZIP SHA256:
`f9265b948e9d64d7bbf85a32127b0ab81f20a6e2923b459b447f9d3fb624c689`.
Prepared resource SHA256:
`d4662b56611e5469ddfe69b43db2e91acb443572c49b75a2ac487462192e3143`.
An initial Python run correctly rejected stale supplemental receipts; actual
wire/persistence checks were regenerated and final release checks rerun rather
than weakening their provenance requirements.

No live files, worlds, terrain marker, helpers, private settings or approval
queues change during this source work. Separate owner approval is required for
the resource-only installation/restart, followed by real iPad front/back,
paperdoll, inventory, walking/crouching, first-person and mixed-set comparisons.
Actual rendering, authenticated public joining and multiplayer load remain
unverified. No main merge, public release or website deployment is included.

# Explore.45 helper-controls candidate

The source adds native Bedrock helper/order forms, explicit creation/profile
selection, empty-roster feedback and read-only combo diagnostics. Existing combat
mechanics, attack speed, saved helpers/stats and both owner/live Codex approval
gates are retained. See [HELPER_CONTROLS.md](HELPER_CONTROLS.md) for use and exact
regression scenarios.

**604/604 native gameplay tests pass**, with zero skipped cases, across **22
separate disposable groups**, bound to all **755 Java source/resource files**.
That includes 27 additional regressions: encoded touch-form actions and real
helper lifecycle operations, exact session/ownership/replay/expiry checks,
transitioning player-target reviews, send-failure cleanup and accurate expired
combo status. Existing autonomous native spear charging, elytra glide and mace
attack tests pass. The preliminary run passed every case but was rejected by its
final source-stability guard; the accepted complete rerun uses the frozen inputs.

Fresh final Geyser checks cover all **66 catalogue identities over three
protocols**, 255 hotbar selections, 510 cursor/drop cases, 594 Survival refusals,
six Adventure/Spectator refusals, three mixed requests and 5,427 response/resync
codec packets. Held native equipment has 57 cases / 69 packets. The native armor
proof passes **303 wire round trips**. Two isolated full-stack startup, cold-lobby,
save, backup, restart and shutdown cycles pass. Their Geyser/full-stack logs have
no ERROR records. The two-case native wire proof passes 255 actual incoming selections,
198 overflow refusals and 255 Survival refusals. The separate two-case native
persistence proof passes 255 framed inputs, 85 saved item/count pairs, all 66
identities and three genuine saved reconnects. **357/357 Python tests pass**,
with ResourceWarning treated as an error and zero skips. The package verifier
revalidates every native/source/artifact/receipt binding before writing archives.

The explore.45 pack preserves all **259 content entries** of the installed
explore.44 r1 resources byte-for-byte; only its manifest revision advances to
`[2,13,45000]`. No fresh armor-art or motion claim is made by this menu repair.

**Known diagnostic:** the native suite retains an existing ERROR-level Polymer
packet-context diagnostic during chunk sending to embedded test players. The
same stack is present in preserved explore.43 evidence and the affected relic
fixture sources match explore.44. Required tests pass, but this is not a log-clean
native run and does not establish recipient-specific chunk projection for every
real client. Pinned dependencies were preserved rather than upgraded within this
menu repair. Actual iPad touch reception, moving/crouching armor appearance,
rendered helper animations and authenticated public joining remain device checks.

The playable server remains the separately installed explore.44 mod with
resource revision `[2,13,44001]`. Local Java/Bedrock startup checks passed after
its owner-approved resource-only repair; no terrain refresh occurred. This
explore.45 source candidate has not been installed, merged to main or published
as a release.

---

# Limb-motion hypothesis — explore.44 resource repair r1

The owner's actual explore.44 iPad check confirms the missing armor pieces and
held totem are restored, but armor still flickers during walking/crouching with
empty hands. A new screenshot shows black forearm and thigh patches cutting
through Infinity armor. This is a failed motion-rendering acceptance check.

The resource-only candidate follows Microsoft's lowercase limb-binding literal
convention. It changes exactly thirty literals across fifteen active models,
plus the pack/module cache revision `[2,13,44001]`. All cube dimensions, pivots,
bone names, UVs, PNGs, materials, controllers, item identities, attachables and
inactive flight geometry remain unchanged. Two independent source/pack reviews
verified the complete delta against the immutable explore.44 server ZIP.
The cause remains a runtime case-normalization hypothesis: published skeleton
JSON uses mixed-case names too, and Bedrock's actual lookup behavior has not been
captured. Offline checks cannot certify this repairs the owner's moving limbs.
See [ARMOR_MOTION_REPAIR.md](ARMOR_MOTION_REPAIR.md).

**357/357 Python tests pass**, with resource warnings treated as errors and no
skips, including eighteen new scoped repair-package cases and all twenty-six
older repair cases. The package contract reverses only the thirty exact binding
changes, then compares every other model field and all remaining pack entries.
Unsafe archives, private paths, changed artwork/UVs/pivots/controllers/UUIDs,
missing coverage and changed dependencies/runtime/mappings are refused. All
747 Java source/resource hashes and the exact production/test jars still match
the earlier passing **577-test/18-group explore.44 native run**; that full suite
is retained baseline evidence, not claimed as freshly rerun for this resource
change. No Java class or gameplay behavior was edited.

Fresh Geyser checks cover 66 identities across three protocols, 255 hotbar
selections, 510 cursor/drop cases, 594 stale Survival requests and 5,427 codec
response/resync packets. All 303 armor codec round trips pass. Fresh supplemental
native wire checks accept 255 real frames and refuse 198 overflow/255 Survival
frames. Fresh native persistence verifies 85 item/count pairs and three genuine
same-UUID reconnects. Both isolated full-stack startup/save/backup/restart/
shutdown cycles pass. Final regular Geyser and lifecycle logs contain no ERROR
records. Original relic/armor validators, static skin-clearance checks and
camera/UI predicates pass. The rigid same-joint fixture is explicitly conditional
on successful engine binding; it does not exercise the client's actual matrices.

The candidate pack SHA256 is
`0365154c6f2ef9c2b911cb10bff471b3d84c07877122c02c1ccbe2b58adcfb55`.
The explore.44 production mod remains
`d838aa51e3a0763e89406ab897ec2198b961938f63c335e9d1a71f0ad5a16929`.
The live server remains on the accepted explore.44 base pack during these
checks. No live resource, world, setting, helper, approval queue or terrain
marker has changed. A separate owner-approved resource installation/restart and
empty-hand walking/crouching iPad comparison are required before acceptance.

---

# Armor views, held totem and relic artwork — explore.44 candidate

This candidate uses the unchanged **Minecraft 26.3 / Java 25** Fabric/Geyser/Via
stack. A fresh full native run passed **577/577 unique GameTests across 18
isolated groups**, bound to **747 Java source/resource files** and the exact
production/test jars. The final complete Python run passed **339/339 tests**
with resource warnings treated as errors and no skips.

The armor repair replaces the shared non-arm perspective predicate while
preserving equipment-slot and item-identity guards. Regression checks cover
source and prepared assets, all forty armor identities and both backpacks,
first-person, third-person, inventory UI and held-item exclusions. The owner's
actual explore.43 screenshot reproduced the sleeves-only symptom; the repaired
client query values and rendered pixels have **not** yet been checked on iPad.
The linked [ARMOR_VISIBILITY_REPAIR.md](ARMOR_VISIBILITY_REPAIR.md) documents
the earlier focused explore.43r1 resource patch. Its resource-only installation
steps do not apply to this full explore.44 package.

The prepared pack excludes only the canonical and Creative-alias Infinity
Totem's custom held attachables. Its original sprite, atlas entry, identities,
offhand permission and native totem behavior remain. This removes the unverified
custom grip used by the affected floating item and uses
normal Bedrock sprite placement; the actual hand position and activation
appearance still require a device check. Bow, crossbow and shield assets remain.

Four independently generated original sprites replace the active Windstep,
Firebrand, Tidecaller and Terra Gauntlet references on both editions. Retained
source images, exact prompts, packing code, hashes and actual 16/64/128-pixel
previews are in `artwork/relic44/`. All 373 historical PNGs and 24 protected
texture-hash entries remain unchanged. All 301 production Java class files are
byte-identical to explore.43; version metadata and four texture references are
updated and four new textures are added. No gameplay capability was removed.

Fresh Geyser checks pass **66 catalogue identities across three protocols**, 255
hotbar selections, 510 cursor/drop cases, 594 stale Survival requests, six
Adventure/Spectator requests, three mixed ordinary batches and 5,427 response/
resync packets. Existing held controls pass 57 native cases and 69 wire packets.
Fresh synthetic armor transport passes **303 codec round trips** for all twenty
pieces across five sets and three protocols; this is transport, not rendering.
The supplemental native wire check accepts 255 real inbound hotbar frames and
refuses 198 overflow and 255 Survival frames. Separate native persistence checks
save/read **85 item/count pairs across all 66 identities**, receive 255 real
frames and complete **three genuine same-UUID reconnects**. Both isolated
full-stack startup/save/backup/restart/shutdown cycles pass. Final Geyser and
lifecycle logs contain no ERROR records; native fixtures retain intentional
refusal/error cases and dependency/context warnings.

An intermediate packaging check correctly refused a stale inventory-proof
receipt after the final Geyser capture changed. The wire and persistence
scenarios were genuinely rerun against the final pack and their hashes verified
before the final passing Python suite. No evidence gate was weakened.

Production mod SHA256: `d838aa51e3a0763e89406ab897ec2198b961938f63c335e9d1a71f0ad5a16929`.
Prepared pack SHA256: `6ca8313c70da565c2a4be88be26b1fa777fb611c3af12903a4883faefb005623`.
Pack/module revision: `[2,13,44000]`.

The existing Convergence winged chest item still combines armor protection,
native flight and set powers. Java retains its armor and wing layers. On iPad,
the item keeps native elytra artwork to preserve real flight and normal ordinary
elytras; a custom visible armor torso plus wings on that same Bedrock item is
**not** provided by this repair. See [RELIC_ART.md](RELIC_ART.md).

The live server remains **explore.43** during this validation. Its world, player
files, private settings and terrain have not been changed. A separately approved
backup/install/restart and resource-pack acceptance must precede checking all
four armor pieces, the held totem in both hands/views, new relic art and winged
chestplate flight on the owner's iPad. No authenticated client rendering,
public multiplayer load or glitch-free result is claimed.

---

# Bedrock render views — explore.43 resource repair r1

Explore.43 is installed and running under the owner's earlier approval. Its
actual iPad check failed: only the patterned sleeves appeared, including a
missing chest torso. The new resource-only candidate replaces the shared
non-arm perspective condition with explicit entity-render and UI queries;
matching-item and equipment-slot guards remain. See
[ARMOR_VISIBILITY_REPAIR.md](ARMOR_VISIBILITY_REPAIR.md).

**338/338 Python tests pass**, with resource warnings treated as errors and no
skips. The new bounded-expression regression checks source and prepared pack,
all forty armor identities plus both backpacks, held-item exclusions and
first-person/third-person/UI truth tables. Twenty-six repair-package fixtures
reject unsafe archives, changed dependencies, mappings, artwork, geometry,
identity guards or unrelated pack fields. The real repair bundle has exactly
three changed resource entries; 1,017 protected Java/artwork/model/attachable,
dependency and immutable-release inputs remain byte-identical.

Fresh Geyser checks pass 66 catalogue identities across three protocols, 255
hotbar selections, 510 cursor/drop cases, 594 stale Survival requests and 5,427
codec response/resync packets. Fresh synthetic armor transport passes 303 codec
round trips. The real supplementary inventory persistence check passes 255
inbound frames, 85 saved item/count cases and three same-UUID reconnects. Two
fresh isolated full-stack startup/save/backup/restart/shutdown cycles pass.
The first Python attempt correctly rejected an older persistence receipt bound
to the previous launcher-test bytes; a genuine fresh run replaced that stale
receipt before the complete passing rerun.

The Java mod is unchanged (`7c8180df518ea357e9627a1d1771c7e2a8d9e91e17199945a76ae069fbd1fbb3`);
the full 577-test native suite is retained baseline evidence, not claimed as a
fresh run. The repair's pack/module revision is `[2,13,43001]`. No live files,
worlds, private settings or terrain policy were changed. The device's actual
query values and rendered pixels remain unverified; source predicates and
packet round trips do not establish an iPad rendering pass. Separate approval
is required before installing this resource-only repair.

---

# Astral Wilds, Infini and armor visibility — explore.43 candidate

Validated with the unchanged **Minecraft 26.3 / Java 25** Fabric/Geyser/Via stack. **577/577 unique native GameTests** pass across **18 disposable groups**, bound to **743 Java source/resource files** and the exact production/test jars. **307/307 Python checks** pass with resource warnings treated as errors, zero skips. The playable Downloads server remains running explore.41 with its unchanged mod hash; no installation, live restart, terrain refresh, public release, main merge or website deployment occurred during this validation.

This candidate includes the original armor visibility/pixel-art repair below, three native Odyssey guardian variants, three genuinely craftable/useable Astral tools, six durable advancements, 15 earnable Infini and the Astral Orbit. Cosmetic supporter passes/permanent grants share the exact configured USD/Infini catalogue with website source. Actual checkout is disconnected; no payment service, webhook or card billing is included. ADMIN purchases only create pending applications; exact owner-console approval, durable source ownership and safe native role removal remain separate from financial receipts and both AI approval gates.

## Failures reproduced and corrected

- The earlier iPad symptom has a real asset cause: helmets/leggings/Aurora sleeves were occluded by native skin clothing. All five original suit envelopes now clear wide/Slim outer skin. Twenty new icons and five worn atlases are original; ordinary leather, netherite and elytra stay unchanged. Packet transport proves equipment delivery, not engine rendering.
- Native `/gamemode` changes previously left logical mode snapshots unchanged. Exact shop reviews and Riftspool anchors now also capture/recheck native game type. Stale sessions, worlds, cursors and changed wallets refuse safely.
- Known terminal guardian removals could reserve a world's encounter allowance forever. Real native discard/unload tests now distinguish ended tombstones from living unloaded guardians; terminal removals release capacity without respawning a spent ruin.
- Fabric's actual JOIN event fires before vanilla publishes its UUID lookup. An approved offline staff member therefore missed the initial native promotion. A verified, finally-cleared joining-player scope now applies and delivers permissions to the exact current connection; foreign/replaced sessions remain refused.
- A deleted initialized wallet primary could look like a fresh economy. A forced/read-back initialized previous-state marker now makes ambiguous missing history fail closed. Exact receipt conservation, source-traced chargebacks and crash recovery are tested. Event and byte reservations preserve room for existing full refunds/staff returns; normal new-growth refusals do not pause intact wallets. No history is pruned.
- Failed immediate form transport now clears both spending/journal authority and delayed delivery state, with no debit or server tick failure.
- Test-only failures were also preserved and corrected: canonical macOS temporary paths, native head rotation in the crop ray fixture, serialized shared guardian-cap assertions, and shutdown pacing that unnecessarily delayed native saves after halt. The old hardcoded item-count assertion now verifies the complete canonical identity set. Final full reruns retain the actual behavioral checks and require clean exits; incomplete bootstrap/terminated probes were not counted as successes.

## Crossplay and persistence evidence

Fresh Geyser checks cover **66 catalogue identities** across **three protocols**: **255 hotbar selections**, **510 cursor/drop cases**, **594 stale Survival requests**, six Adventure/Spectator requests, three mixed ordinary batches and **5,427 response/resync codec packets**. Held controls retain **57 native ranged/shield/totem cases and 69 wire packets**. Armor transport independently passes all five suits/twenty pieces with **303 codec round trips**. A fresh supplementary Via/Fabric check accepts all 255 actual frames and refuses 198 oversized/255 Survival frames. Its separate receive-hook persistence scenario verifies **85 item/count pairs**, all 66 identities and **three genuine same-UUID saved reconnects**, preserving named/damaged gear, backpacks/storage and the observer's saved file. Supplementary scenarios each include their framework baseline and are not added to the 577 unique game-test total.

Both fresh isolated full-stack startup/save/backup/restart/shutdown cycles pass. Final regular Geyser/lifecycle logs contain no ERROR records; native fixtures retain intentional refusal/error cases, dependency warnings and deprecation messages. Original armor42/Astral43 artwork validators and all historical texture hashes pass. Source and server ZIP integrity/checksum audits are required before delivery.

Production mod SHA256: `7c8180df518ea357e9627a1d1771c7e2a8d9e91e17199945a76ae069fbd1fbb3`. Original native test jar: `8dfb3a0968fc72421ef4cbaff107d1802f4828bf588dab051a7aa7eb89c3de77`. Aggregate native report: `4940f70d05aa6b304e9d23c3a2ef469361c39f0a251321909ec996c70d22014d`.

## Player acceptance and known limits

Actual iPad/graphical Java rendering, touch feel and public multiplayer load remain unverified. After a separately approved installation and resource-pack acceptance, check all four armor slots in third-person/inventory while walking/crouching; try **Infinity Menu → Astral Wilds** and **Infini**. These guides are complete: [ARMOR_REPAIR.md](ARMOR_REPAIR.md), [ASTRAL_WILDS.md](ASTRAL_WILDS.md), [SUBSCRIPTIONS.md](SUBSCRIPTIONS.md), [INFINI_ECONOMY.md](INFINI_ECONOMY.md), [ADMIN_ROLE_LIFECYCLE.md](ADMIN_ROLE_LIFECYCLE.md).

The world encounter/history and numeric/client safety budgets remain. Missing externally deleted guardian data is not inferred from unloaded chunks. Deletion of every wallet/marker/backup cannot be detected as different from a genuinely new world. Full OP4 is trusted staff authority, not a tamperproof rental; independent later owner/OP grants survive paid expiry. No lag-proof, unlimited-engine or glitch-free claim is made.

---

# Armor visibility, original pixel art and admin tools — explore.42 candidate

## Reproduction and confirmed root cause

On the owner’s iPad, armor pieces were missing in third-person and in the inventory character; only the chestplate was visible. The installed explore.41 pack and equipment-slot definitions matched their expected bytes, and all four Elemental pieces existed in the saved equipment snapshot. A new offline native Geyser check sends all four actual equipment fields and decodes their wire representations; it cannot prove client rendering.

The confirmed asset defect was omitted player skin: all five helmets were inside the visible hat layer and leggings were inside pants, with incomplete inner-thigh coverage. Aurora sleeves also intersected outer skin. The repaired original cuboids cover wide/Slim skin envelopes with at least 0.1 model units of clearance. Boot/leg seams overlap at different depths. Regression tests reproduce the old occluded surfaces and verify the full new envelopes and representative rigid joint poses. These are model-space checks, not Minecraft screenshots.

Twenty new original icons and five coordinated worn atlases use imagegen source retained with its exact prompt and a reproducible mechanical packer. Historical PNGs and ordinary leather/netherite/elytra textures remain unchanged. Existing Admin/OP4 users can use Studio tools in Survival while build, loaded-chunk, container, collision, stale-session and undo checks remain. Eleven additional aliases reuse native argument trees and permissions, including `/stats` and `/st`.

## Verification stage

528/528 native GameTests passed across fifteen disposable groups with exact release dependencies, including the new Survival admin and native building-command cases. The new offline armor proof passes all five suits, twenty pieces and three pinned protocols: 303 codec round trips, canonical and actual Creative-derived definitions, complete suits, independent slot changes and clearing. The new art and skin-clearance checks pass. Two isolated full-stack startup/save/backup/restart/shutdown cycles and Geyser startup/protocol checks pass. Supplementary Via/Fabric inputs, native disk/rejoin checks and all 303 Python tests pass without skips. The checked server and source ZIPs are built locally. No live server files or player data have been changed.

The earlier Python attempt was intentionally rerun after resolving its actual failure: the model-only generator still emitted old texture references after the new artwork became active. The generator now reproduces active references. Packaging-dependent tests require final native provenance and the current supplementary receipt. The complete suite was rerun after that evidence was available and the freshly executed, byte-identical explore.42 persistence receipt replaced the earlier explore.41 receipt pointer. Missing or stale evidence was not counted as a pass.

Actual iPad/Java rendering remains unverified. In particular, the geometry defect is real but is not proof that every cause of the reported missing pieces has been fixed. Device acceptance requires all four pieces in third-person and inventory, then head turns, walking and crouching after a separately approved installation. See [ARMOR_REPAIR.md](ARMOR_REPAIR.md).

---

# Elemental Forge and armor seam repair — 2.13.0-explore.41 candidate

Validated with Minecraft **26.3 / Java 25** and the unchanged pinned Fabric/Geyser/Via stack. **523/523 unique native GameTests** pass across **15 disposable groups**, bound to all **678** Java source/resource files and the exact production/test jars. **289/289 Python launcher, artwork, packaging and owner-identity tests** pass with resource warnings treated as errors, zero skips. During source validation the playable Downloads server was not stopped or changed. The owner subsequently approved installation of the exact tested candidate; the follow-up below records its verified state.

The twenty-one new native cases cover all eight registered armor pieces, literal names, distinct equipment assets, native durability/enchanting/slot modifiers, actual item and player-file saves, all four selected elements, Infinity powers, mixed/wrong-slot/spoofed armor, admin bases and unrelated modifiers, mode guards, Earth cleanup on death, and real connected-player effect expiry. Forge tests exercise atomic kit claims, native disk reloads, permission loss, stale/foreign Java screens, actual encoded Bedrock form callbacks, replay/expiry and occupied cursors. Relic tests exercise real hostile queries, range/cone/obstruction/friend/player exclusions, six-target limits, native damage/velocity/healing, stronger potion preservation, real 99+1 tick shared recharge and unchanged inventory/equipment. The actual recipe manager crafts all seventeen new recipes; block checks verify real registry entries, pickaxe tags, light and loot-table drops.

Fresh Geyser transport checks cover all **63 identities** in all **three pinned protocols**: **246** actual hotbar selections, **492** cursor/drop cases, **567** stale Survival requests, six Adventure/Spectator cases, three mixed ordinary batches and **5,184** native response/resync packets. Existing held-equipment controls remain **57 native ranged/shield/totem cases and 69 Bedrock wire packets**. A supplementary Via/Fabric scenario accepts all 246 actual hotbar frames and refuses 189 oversized requests plus 246 Survival requests. A separate genuine receive-hook/persistence scenario executes **333 framed inputs**, saves and reads all **82 item/count pairs**, covers every one of the 63 identities and performs **three genuine same-UUID saved reconnects**, including the partial final batch. Named/damaged gear, three backpack styles, personal storage and the observer's pre-cleanup saved file remain intact. Each supplementary report includes one scenario plus the framework baseline. Old proof bytes remain in a private baseline archive; current evidence was promoted only after checksum, source, dependency, report and completeness verification.

Both isolated full-stack startup/save/backup/restart/shutdown cycles pass. Their final lifecycle and regular Geyser logs contain no ERROR records. Native tests still produce dependency/context warnings and intentional refusal/error fixtures; these are not described as an error-free game. No authenticated iPad or graphical Java login was performed.

## Owner-approved local installation follow-up

The exact tested server ZIP was installed on October 17, 2026. A fresh stopped-world ZIP was verified against all 95 saved files. All 83 package files matched and all 41 private snapshots stayed byte-identical during overlay. The protected-area refresh committed once for the exact explore.41 mod hash, preserving all 26 compact record files. Fifteen personal files remain byte-identical; the course-selector registry retains identical JSON with native key ordering. Private credentials/settings, helpers, stats and both AI approval gates remain. Fabric/Geyser startup has zero ERROR/Exception records in the inspected logs; all 63 catalogue entries load across three protocols. Local Java TCP25565 reports 26.3/protocol777 and Bedrock UDP19132 responds. Public authenticated joining and actual device rendering remain unverified. The package archives are immutable snapshots of the earlier validation stage; this follow-up changes documentation only. Main remains unchanged while PR #4 is a draft.

## Rendering finding and scope

The owner reports worn armor pieces overlapping/flickering. Inspection found positive-area coplanar surfaces at the leggings/boots, waist, neck and shoulders of the existing Bedrock models. The repaired original geometry separates those surfaces. `tests/test_armor_seams.py` reproduces the old boot conflict and checks all five complete suits. New independent assets also pass full UV, joint, identity-guard, hash and complete-suit surface validation. All **286 pre-task PNG files remain byte-identical**. Prepared resources use revision **[2,13,41]**; ordinary leather, netherite and elytra overrides remain absent.

Software previews show generated cuboids/UVs in standing and walking-pivot poses. They are **not Minecraft client captures**. Before installation, the owner said the in-game appearance differed from the preview; explore.40 was installed at that time and no screenshot of that actual rendering had been supplied. The exact client symptom and the effectiveness of this candidate on the owner's device remain unverified. After the approved installation, the owner was asked to reconnect, accept the pack, equip each set and inspect front/back, walking, crouching and head turns. Do not call the reported visual issue fixed in the live game until that check succeeds.

## Other failures found and corrected

- Expanding the automatic direct starter grant filled native inventory capacity and prevented a normal picker weapon swap. The starter remains below capacity; new suits use separately selected atomic Forge kits. The original failing native picker regression passes again.
- Initial recipe-discovery JSON used the older singular `recipe` condition. Minecraft 26.3's actual native recipe predicate requires `recipes`. All seventeen discoveries now load; an explicit resource regression checks the native schema.
- The old proof assumed 46 identities, 60 count pairs and two batches. Completeness now derives independently from the canonical catalogue and pinned protocols; omitted or substituted captures fail. The actual expanded capture and all three native save/rejoin batches pass.
- The source archive's resource-version helper is now included so `prepare_crossplay.py` retains its local import dependency.
- New native fixtures were corrected for float precision, the sixteen-character Java profile contract and real connected-player ticking. Armor/relic network scenarios run in separate disposable groups instead of inheriting legacy mock players from unrelated cases.

Production mod SHA256: `812b169f0c7b379e33ae6548cf9f7796bd11b6a9e5e8fc9c0954dce0a48a86da`. Native test jar: `14a157c84b9c3d5c6bb5887a4cf604d11fb2eb7bfa5ba7a3623690cb3d938803`. Aggregate native report: `b8e2870c57fce9b9c478801fadaba10c7487dbf99bbef15fef01e5c548a646e9`. Crossplay pack: `43e00b0c87cbba281b09c04eaf80fac215fb2bfa21d657fd6c9de2cd3af1c495`. Creative bridge remains unchanged at `0f55a1e84106d53c14f90b698d9ddb14e63a576e5b2c1ca8cbe6638348346b87`.

The automated validation did not modify the live server. After explicit owner approval, explore.41 was installed with a verified fresh backup and protected-area refresh; no website deployment or public release was performed. [ELEMENTAL_FORGE.md](ELEMENTAL_FORGE.md) provides exact menu paths, powers, costs and recipes.

---

# Circuit Keepers' Commons — 2.13.0-explore.40 candidate

Validated on Minecraft **26.3 / Java 25** with the unchanged pinned Fabric/Geyser/Via stack. **502/502 unique native GameTests** passed across thirteen disposable groups, bound to all **566** Java source/resource files and the exact release/test jars. This includes eighteen new cases for Builder Exchange, Harvest Together, Expedition Atlas, the Seedlight Jar, original blueprints and the reviewed Pack Organizer. No playable world was used.

New native checks cover real powered dust/lamp state, a disabled hopper refusal followed by normal hopper item movement, exact reviewed food consumption, two contributors and idempotent shared pantry indexing, full-inventory/replayed gift refusal, actual encoded native Bedrock form callbacks, expiry/failed send, old/foreign screens, exact-component item sorting and untakeable preview stacks. Actual native player files are read back for donation receipts, one-time reward flags, the personal jar, Atlas stamps and every sorted inventory slot including nested storage. Earned titles refresh when team prefixes change while preserving an existing combat team. [COMMONS.md](COMMONS.md) records reproduction, root cause and regression details.

Fresh Geyser checks pass all three pinned protocols: 180 Creative hotbar selections, 360 cursor/drop cases, 414 stale Survival requests, six Adventure/Spectator cases, three mixed ordinary batches and 3,807 native response/resync packets. The retained held-equipment checks pass 57 ranged/shield/totem controls and 69 Bedrock wire packets. Both isolated full-stack cold-hub/start/save/backup/restart/shutdown cycles pass; their final regular Geyser/lifecycle logs contain no ERROR records. The resource comparison against the installed repair shows only a manifest revision to `[2,13,40]`; every other pack entry remains byte-identical, with ordinary elytra overrides absent.

**271/271 Python launcher/resource/packaging/owner-identity tests** pass with resource warnings treated as errors and zero skips. Both supplemental Via/Fabric wire tests and both persistence tests pass: 180 actual hotbar inputs, 138 overflow refusals, 180 Survival refusals, then 180 real inbound inputs and 60 native disk/rejoin item-count cases spanning all 46 identities. The new versioned persistence proof was promoted byte-for-byte only after its source/jar bindings and complete evidence were verified; earlier receipts remain in private backups. The native Commons pantry file was also inspected with the bounded saved-NBT reader after shutdown: its six test contributors and capped wheat/carrot totals were written by the actual saved-data system.

Production mod SHA256: `a2ac4b1c63529053dd15e18ea6160e9b4f65d807c1911953e825ba479b767124`. Native test jar: `392bf52ff9d6051551b3cddf9a2294cf499c997764cefcfa5a68c372e1e2c36e`. Aggregate native report: `4d93511ed9baaf5895d62b7e2a7a433a01da79084f585daa954ee5c51b44b2ab`. Crossplay pack: `e94698988942c154be0eb0b41f5137aec3760f0d5dd3ee3e97ea1b7d913db866`. The unchanged Creative bridge remains `0f55a1e84106d53c14f90b698d9ddb14e63a576e5b2c1ca8cbe6638348346b87`.

Native/deprecation/context warnings and the test suite's intentionally rejected fixture operations are not described as error-free. No authenticated Java/iPad login, rendered visuals/touch interaction or multiplayer load check has been performed for this candidate. No glitch-free, lag-proof or catastrophic-storage-failure guarantee is made.

**Installation:** explore.40 is source-only until separately approved. The inspected playable folder contains the explore.39 mod and ordinary-elytra repair; its panel is offline, so running state is unknown. No live files, worlds, player data, terrain policy, credentials, AI approval queues, public release or website deployment were changed. The existing owner/live Codex approvals remain required for AI server actions. Current code is on a feature branch based on the pending ordinary-elytra repair; merge/push/package stages are reported separately.

## Historical explore.39 validation — original held equipment and native shield behavior

Validated October 12, 2026 (Mac local time), on unchanged **Minecraft 26.3 / Java 25** and the exact pinned Fabric/Geyser/Via stack. **484/484 unique native GameTests** passed across thirteen disposable groups, bound to all **561** Java source/resource files. **267/267 Python resource, launcher, packaging and owner-identity tests** passed with resource warnings treated as errors and **zero skips**. All **21** one-chest Odyssey templates reproduce exactly. A fresh isolated full-stack world completed both cold-hub/start/save/backup/restart/shutdown cycles. Final ordinary Geyser and lifecycle smoke logs contain no ERROR records. The running playable world was not used by these checks.

Exact production mod SHA256: `73fe12981c8f612570fe09de0d2624b5913d7d580a64f7f3ffff0e78d0305dd5`. Original test jar: `acb1711db231bfce4bd25fd1f03e8e2a9d928dde7dc74a4b678a5369abf04b38`. Aggregate native report: `2e62e9d6bbfb558648133a89c9f77fce1f0cd5de5cedb93b62d21cd2264ee043`. Creative bridge: `0f55a1e84106d53c14f90b698d9ddb14e63a576e5b2c1ca8cbe6638348346b87`. Bedrock pack: `491ea3e070a0412b39afb11b7e0ef047d0b4502765c55c189daee9b5cac0822a`. Current receipts are under `research/integration/creative-bridge/`; accepted copies are preserved under `research/creative39/accepted/` after packaging. Earlier accepted packages and evidence remain separate.

- **Independent original tools:** four held styles cover Infinity Bow, Crossbow, Shield and Totem through eight canonical/catalogue attachables, twelve project-authored geometries and eleven first/third-person draw/block animations. The generator reads no vanilla artwork or other armor files; all 298 existing project PNGs remain unchanged. Unrecognized animation frames choose the neutral pose. Static armor, the separate solid Convergence chestplate and original flight shell remain. Ordinary leather/netherite appearance is preserved. Actual flight and three arrow identities retain the four documented native appearance exceptions, including shared ordinary-elytra artwork.
- **Native shield regression:** crouching Ward powers previously returned without starting native blocking. They now retain native shield use in either hand, including during the power cooldown. New native tests exercise the actual blocking delay, frontal damage and durability, cooldown, both mixed ordinary/custom hand arrangements and a positive unblocked damage control. The embedded fixture advances the real player-use tick; resistance and absorption are removed before checking shield defense. Decorated Wards retain literal names across all sixteen colors, real banner patterns, native save/load and Polymer projection/restoration; genuine styled custom names and ordinary translated shield names remain. The actual v2 export omits only Ward's unsupported offhand wearable field, retaining its native canonical equipment and defense, Bedrock offhand access and all other armor equipment components.
- **Bedrock definitions and controls:** Geyser registers **89 custom items**, **209 block overrides**, two block-item overrides and eight custom blocks. The adapter changes only four owned canonical definitions in each of three pinned protocol registries. It adds Creative arrow ammunition for the original ranged items and offhand fireworks for Crossbow while retaining complete unrelated components, original IDs and ordinary primary/custom mappings. Mandatory native login packet checks compare complete components against an independently encoded untouched registry baseline; only the pinned codec's native missing-map-to-empty-map form is accepted. Optional real translators and codecs pass **57** ranged/shield/totem control cases and **69** encoded/decoded Bedrock packets, including three untouched baseline packets. Charged projectiles, mixed-shield hand priority, native arrow identity, inventory components and network IDs are checked. Transport cannot prove authenticated client input or rendered attachment fit.
- **Creative and persistence:** the retained guard passes 540 valid Creative requests, 414 stale Survival requests, six Adventure/Spectator requests, three mixed ordinary-drop batches and 3,807 native response/resynchronization packets. Both supplemental native cases for Via/Fabric wire acceptance pass, followed by both real receive/save/reconnect cases: 180 captured inputs, 60 disk/rejoin item/count cases covering all 46 identities, actual named/damaged/dyed gear and personal backpack contents. The unrelated observer file remains identical within the fixture's documented pre-cleanup scope. Packaging binds the new verification helper and both owned bridge source files to actual compiled/executed evidence; the source download includes the helper.

Rejected attempts remain under `research/creative39/`: native Ward fixture timing, absent native enchantable data, unsupported shield mapping, literal-name serialization, native component-map wire normalization and stale Python evidence were investigated rather than counted as passes. The persistence packaging fixtures correctly rejected the old current-check receipt; the exact passed versioned explore.39 receipt was then promoted byte-for-byte, preserving the earlier receipt. The remaining old 38-definition assertion now checks all 42 canonical mappings and explicitly retains the three native arrow fallbacks. No successful proof was fabricated or native assertion removed. Existing native/deprecation/context warnings and the supplemental fixture's initial properties fallback remain; only the final ordinary Geyser/lifecycle logs are described as ERROR-free. Owned installation rollback is source-reviewed and fails closed; it has not been independently fault-injected.

**Delivery and limits:** explore.38 was merged to main as `643440657b38a72c98821aa087ba0c6b4701fb9e`, pushed through `conorqiao-tech`, and installed after the owner's exact approval. The installed mod remains `adf372f526eeb4a191307f79cfc87269c24f4c37926fd07be72b735c0643552f`; both local protocols and running state were verified. Explore.39 is a separately tested source/package candidate and is **not installed**. No explore.39 live restart, terrain refresh, public release or website deployment occurred. Player/world data, private settings, menus, stats, helpers and both AI approvals remain. Authenticated Java/iPad rendering, touch input, ordinary/custom mixed-shield visuals, frame selection, public login and multiplayer load remain unverified. No glitch-free, totem-bypass, lag-proof or unlimited-capacity claim is made.

The owner-only history and local Claude attribution controls remain. GitHub's API and full contributor graph list only the owner, but the real Chrome Code-page sidebar still displays Claude and qiaokan after a hard refresh. The actual history rewrite was pushed October 11 at 14:51:05 UTC, beyond GitHub's documented approximate 24-hour refresh window. This display issue is **not fixed**; the official Support form was reached, but the Mac locked before a draft could be confirmed. No support request was sent and no further history reset was performed. See [CONTRIBUTORS.md](CONTRIBUTORS.md) for the distinct checks and next step.

After a separately approved installation, reconnect and accept the revised resource pack. Search Creative for Aurora and Convergence, equip the solid chestplate and separate Wings, and inspect ordinary leather/netherite. Draw both ranged weapons, load arrows/fireworks, crouch-block with Ward in each hand and beside an ordinary shield, hold the Totem, apply a banner/custom name, then save and reconnect. Actual totem activation and unsafe combat should use expendable items in a disposable world. Edition-specific inventory requirements and appearance exceptions are in [CREATIVE_INVENTORY.md](CREATIVE_INVENTORY.md) and [ORIGINAL_ARTWORK.md](ORIGINAL_ARTWORK.md).

## Historical explore.38 validation — Creative safety, original flight shell and ownership

Validated October 12, 2026 (Mac local time), retaining **Minecraft 26.3 / Java 25** and the exact pinned Fabric/Geyser/Via stack. **480/480 unique native GameTests** passed across thirteen disposable groups, bound to all **561** Java source/resource files. **259/259 Python resource, launcher, packaging and owner-identity tests** passed with resource warnings treated as errors and **zero skips**. All **21** one-chest Odyssey templates still reproduce exactly. Two isolated full-stack cold-hub/start/save/backup/restart/shutdown cycles passed; Geyser answered local Bedrock UDP. The final ordinary Geyser and lifecycle smoke logs contain no ERROR records. No playable world was used.

Exact production mod SHA256: `adf372f526eeb4a191307f79cfc87269c24f4c37926fd07be72b735c0643552f`. Original test jar: `30537836cc1884af8df8e1b8886639a36c386047d6ec13decc96989f51388616`. Aggregate native report: `1628be33df3cb2062fe9721eb37d6f5c31591b522a767a4e04a8fb650b55299f`. Creative bridge: `d6cb814fe7db831d7076aaae1b7c678027b9db06e84c00b4a11dff1fb7255d86`. Bedrock pack: `1bcb4d71ba92d5505cba43db9b2f0106bcdb36e4f2cf457045587f2762c24973`. Current receipts are under `research/integration/creative-bridge/`; accepted snapshots are preserved under `research/creative38/accepted/` after packaging. Prior explore.36 and explore.37 packages/evidence remain separate.

- **Creative-to-Survival inventory glitch:** an actual pre-fix pinned Geyser reproduction sent an owned catalogue request after leaving Creative. DESTROY cleared a cached owned diamond; DROP dereferenced the correctly refused alias before sending any response. The new guard intercepts only first-action Creative requests for this adapter's exact owned alias IDs, preserves request ID/filter/text origin/order, and delegates rejection/resynchronization to Geyser's native translator. It does not replace ordinary inventory handling. Actual configured codecs and the installed packet dispatcher now pass **540 valid Creative requests**, **414 stale Survival requests**, **6 Adventure/Spectator requests**, and **3 mixed ordinary-drop batches** over all three supported protocols. **3,807** actual native response/cursor/inventory/armor/offhand/crafting packets encode and decode through their correct server/client codecs. Full decoded identity, count, component maps, nested data and native network IDs are checked; legitimate drops change only their requested count. The rejected pre-fix evidence is retained under `research/creative38/rejected-geyser/`.
- **Names, components and persistence:** the retained final-initializer name fix and 46-entry catalogue remain. A new two-case supplemental native run sends **180** actual captured Geyser inputs through pinned Via conversion and the real framed Fabric/Polymer receive hooks, without explicitly invoking restoration or the Creative listener. It checks **60** distinct item/count pairs spanning **all 46 identities** through actual UUID player-file writes, native detached loads and **two genuine saved same-UUID fixture reconnects**. Its **245** framed inputs include renamed/damaged weapons, three dyed/named backpack styles and actual personal-backpack menu contents. An unrelated observer's file remains byte-identical **before fixture cleanup and ordinary server shutdown saves**. The existing separate two-case wire proof also passes 180 valid inputs, 138 oversized refusals, 180 Via Survival refusals and 180 authoritative native Survival refusals. These are simulated native transports, not authenticated device logins.
- **Original armor:** Convergence Wings now use their own independently authored `geometry.convergence.flight_shell_original` chest and short shoulder plates. This fourteenth original Bedrock assembly is reproducible without importing another armor file; all thirteen original static armor/backpack models and original PNG checksums remain. The separate solid chestplate, actual native wings/animations, Java weapon poses and restored ordinary leather/netherite appearance are retained. Java still uses the engine humanoid armor mesh. The eight native Bedrock appearance exceptions remain documented in [ORIGINAL_ARTWORK.md](ORIGINAL_ARTWORK.md), including shared ordinary-elytra artwork.
- **Startup race:** a valid receipt no longer overrides an already canceled start or a child that has exited, including cancellation/exit during actual receipt validation. Five focused regressions and the real full-stack cycles passed. Translation readiness and process liveness are checked separately.
- **Evidence and ownership:** sixteen persistence packaging regressions reject missing/stale sources, changed provenance, malformed format/Via bindings, unsafe runtime paths, failed/skipped reports, incomplete guarantees and altered executed capture bytes. Packaging binds exact executed runtime and nested Via bytes and includes the exact persistence-native catalogue, fixture jar, reports and raw log. Twenty-one real disposable Git tests cover commits, merges, pushes, worktrees and preservation of unmanaged hooks/config. Tracked Claude attribution settings suppress automatic commit/PR/session attribution; shared local hooks reject wrong author/committer, co-author trailers and pre-rewrite ancestry. GitHub's current contributors API lists only `conorqiao-tech`; rendered contribution graphs can remain cached. Local checks can be deliberately bypassed and do not govern GitHub website/API commits or alter remote access settings.

Rejected attempts were preserved, not counted as passes. New codec fixtures initially used the server-only packet decoder for clientbound inventory packets; the proof now uses Geyser's unchanged processed server codec and each matching raw Cloudburst client codec. Native ClickPlan clones component maps, so complete decoded component equality replaces irrelevant map-iteration byte order while keeping wire and network-ID checks. The first Python negative fixtures omitted their real wire-test source; the fixture now copies and verifies those bound source bytes. The final full Python rerun passed all 259 cases. Supplemental native logs retain initial missing-properties fallback and native/deprecation warnings; their passing XML does not make the entire supplemental log error-free.

**Delivery and limits:** the tested explore.37 baseline was merged to main as `c513926d4367851a17ae1afe1f7195ebcbce6b8a` and pushed through `conorqiao-tech`. Explore.38 is a separately tested source/package candidate; no live installation, restart, terrain refresh, public release or website deployment is included. The last confirmed installed playable mod remains **explore.36**. The private dashboard was offline during this check, so current running status is unknown. Authentic iPad/Java inventory search, touch pickup, equipped fit/animation, shooting/flight, public login and multiplayer performance remain device checks after an approved installation. No glitch-free or lag-proof claim is made. Both AI approvals and private/player/world data remain.

The owner-selected [Update Holiday Schedule](chatgpt-conversation://6ac98567-9ea8-83e8-9aec-06aed050c77f) is now followed daily at **9:10 a.m. local time** for future update ideas. [HOLIDAY_UPDATES.md](HOLIDAY_UPDATES.md) records proposed themes; those proposals are not installed rewards or scheduled native commands. The original planner was not edited.

After approved installation, reconnect and accept the revised resource pack. In Creative search **Aurora** and **Convergence**, select into empty/occupied slots and equip each piece. Check the solid chestplate and separate Wings, ordinary leather/netherite, custom weapon use and saved names/backpacks after reconnecting. Use the matching optional Java Polymer/Fabric client support for the custom tab; unmodified Java retains the menu fallback. Test game-mode changes with expendable inventory in a disposable world and verify that rejected catalogue selections do not consume owned items.

## Historical explore.37 validation — independent armor and Creative inventory

Validated October 11, 2026 (Mac local time) on unchanged **Minecraft 26.3 / Java 25** and the pinned Fabric/Geyser stack. The normal suite passed **480/480 unique native GameTests**, covering thirteen disposable groups with no failed, errored or skipped cases. Source provenance binds all **561** Java source/resource files, the exact release/original test jars and aggregate report. **215/215 Python launcher/resource tests** passed with resource warnings treated as errors, including the final packaging regressions. All **21** reproducible one-chest Odyssey templates still match the original blueprint. **Two fresh isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed. The ordinary Geyser/full-stack smoke logs contain no ERROR records. No playable world was used.

The exact tested mod SHA256 is `147985050d9856723978c0b27108d8fe8e4b54900ccb68f3e14790536d3dfe76`; original test jar SHA256 is `66f725d759a0e5502c4a05410b07f32c7e7b72abd359f9cf4ae89219faa4539d`; aggregate report SHA256 is `017103b36fc3e6cde9b5ac2b25bfc103e4b1398cb8aaaef7815d212d71a8fb9d`. Native evidence is preserved under `research/creative37/accepted-native/`. Prior immutable packages and explore.36 installation evidence remain separate.

Concrete fixes and checks:

- **Names:** Minecraft 26.3's final item initializer overwrote earlier registered `ITEM_NAME` values. A namespace-scoped final initializer hook now preserves literal Infinity labels while leaving ordinary items, custom renames and item models unchanged. Registered native components, Polymer reconstruction, both exported languages and all 46 catalogue labels are checked. Convergence armor and its separate solid chestplate/wings have consistent search names.
- **Normal Creative inventory:** the Java tab now uses Polymer's server tab registration. Matching optional Fabric/Polymer client support receives its display/search entries and permission-dependent admin blocks. Bedrock's pinned adapter adds **46** visible entries to existing Creative categories; it converts temporary aliases to real canonical identities before Geyser's cache and wire output. This fixes the former hidden/unusable custom Creative entries. An ordinary Java resource pack alone cannot create a new server tab; the menu fallback remains.
- **Independent armor and tools:** thirteen Bedrock armor/backpack geometries are authored in the project's own generator, without importing another armor model/texture. Twenty-six canonical/alias attachables reference those original models, controller and original rasters. Player joints animate the assemblies. Ordinary leather/netherite overrides are absent; Java retains the engine humanoid armor mesh with independent equipment textures. Bow/crossbow, blocking shield and spear held/inventory contexts have distinct Java model poses using the original sprites. Path, UV, model-branch, checksum and pack-integrity checks passed. Native Bedrock appearance exceptions are documented in [ORIGINAL_ARTWORK.md](ORIGINAL_ARTWORK.md).
- **Actual Geyser requests:** the exact pinned Geyser 2.11.3 build 1248 processes **540** actual Creative requests across protocols 1001, 2168 and 2193: 180 hotbar and 360 cursor/drop requests. Cases include matching occupied slots/cursors and 138 distinct occupied-item replacements. Actual serialized MCProtocolLib bytes retain only the trusted identity, item model and literal name. All ordinary primary mappings remain unchanged; Survival aliases and client-supplied identity data cannot grant custom gear. Startup registers 85 custom definitions including aliases and Geyser's built-in item, plus 209 block overrides, two block-item overrides and eight custom blocks, and responds to local Bedrock UDP.
- **Pinned Via and authoritative native handling:** a separate two-case disposable GameTest uses those captured byte packets, the exact ViaBackwards 5.12.0/ViaVersion snapshot and Minecraft 26.3 native codec. Real native player abilities pass through Via's clientbound handler before conversion. All **180** valid hotbar packets decode, reconstruct through the real Polymer utility, and reach the native Creative handler with canonical identity, count and defaults. All **138** oversized stacks are refused. After real Survival abilities, **180** attempts are canceled by Via; independently replaying the already decoded Creative packets into the native Survival handler refuses all **180**. Inventory remains unchanged on refusal. The final selected item survives a native NBT save/load round-trip; this is not a disk-save/reconnect test of every catalogue item or a full authenticated network login.
- **Startup and packaging:** fresh catalogue export and extension readiness must match their exact current hashes before Bedrock becomes ready. Missing/stale/failed receipts and canceled starts are tested. Packages require current bridge source/jar bindings and the supplemental wire source, executed production mod, captured catalogue, pinned dependencies, XML and raw evidence hashes. The public captured/native catalogues, supplemental test jar/report/log and reproduction sources are included.

Preliminary failures were investigated and retained. The first name cases exposed the actual final-initializer overwrite. Adapter development exposed early registry timing, native offhand-equipment and incomplete component serialization issues; those paths were corrected before the final request proof. One supplementary fixture compared randomly ordered legacy catalogue arrays by byte hash; it now binds the original captured bytes and separately compares every entry field by canonical path, rejecting duplicates. Another fixture lacked the actual native abilities event, so Via correctly canceled Creative selection; sending the real abilities packet now preserves both Via's gate and its separately tested Survival refusal. Independent review also found the executed runtime mod needed an explicit native-provenance binding; both the runner and packager now require it. These failed runs are not counted as passes. The supplemental fixture log retains its harmless initial missing-properties fallback ERROR and native/deprecation warnings; the successful native XML is authoritative, and the entire supplemental log is not described as error-free.

**Delivery and limits:** explore.37 is a tested source/package candidate. The playable Mac server remains **explore.36**, unchanged; no installation, restart, terrain refresh, main merge, public release or website deployment occurred in this development stage. Both AI approvals, private settings, inventories, player data and earlier protections are retained. Authentic Java/iPad Creative search/touch pickup, equipped model fit/motion, native shooting/flight, public login and multiplayer performance remain unverified. No glitch-free or lag-proof claim is made.

After approved installation, reconnect and accept the new resource pack. On iPad, enter Creative and search the normal inventory for **Aurora** and **Convergence**; select into empty and occupied slots, equip each piece and reconnect. Inspect ordinary leather/netherite beside them. On Java, use the matching optional client support for the **Infinity Armor** tab, or keep the menu fallback. Draw both ranged weapons, block with the shield and hold/use the spear. The full edition-specific limits and controls are in [CREATIVE_INVENTORY.md](CREATIVE_INVENTORY.md) and [ORIGINAL_ARTWORK.md](ORIGINAL_ARTWORK.md).

## Historical explore.36 validation — exact-session menus, trades and AI lifecycle

Validated October 11, 2026 on unchanged **Minecraft 26.3 / Java 25** and the pinned Fabric/Geyser stack: **474/474 unique native GameTests**, **196/196 Python launcher/resource tests** with resource warnings treated as errors, and **21** reproducible one-chest Odyssey templates. No final native case failed, errored or skipped. Thirteen fresh disposable groups ran 439, 13, 4, 4, 2, 2, 2, 5, 3, 4, 3, 2, 3 cases respectively; every registered class is covered and the repeated vanilla baseline is counted once. Provenance binds all **548** Java source/resource files, release and original test jars, selected group jars, reports and pacing settings. Fresh Geyser registered **38 exported custom item definitions plus its built-in item**, **209 block overrides**, **two block-item overrides** and **eight custom blocks**, and answered a local Bedrock 26.51 UDP ping. **Two fresh isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed. Neither smoke log contains ERROR records. The playable world was not used.

The exact tested mod SHA256 is `6b133fb366883ac5fd5598ca998fbfbbe1bb4efe289f742f9bb493695dc31587`; original test jar SHA256 is `7aee5a7b36fa0cec7e7bf29e8ff157b533f9f2634fb4219b699586c4a633d9ba`; aggregate report SHA256 is `66d52c2c22551fa5e1d7a5dffa453a2158d57aede6f55108bb11557591f759d4`. Final native, launcher, template and smoke evidence is preserved under `research/efficiency36-final-native/`, with immutable tested jars under `research/efficiency36-immutable-jars/`. Shared check paths now belong to explore.36. Older immutable packages and evidence remain preserved; explore.35's server ZIP checksum was reverified unchanged before this work.

Seven new cases establish these concrete boundaries:

- **Private stat input and forms:** genuine saved same-UUID reconnects retain fresh Java prompts, Cumulus tickets and staged deliveries after old registered disconnect callbacks. Old actor text is consumed without deleting the new prompt. Old form callbacks cannot consume fresh review; actual current chest and Cumulus review confirmations still edit health/damage. Genuine current departure clears populated prompt/form/delivery state.
- **Queued private input intent:** a real worker queues a numeric entry, then the current player replaces its prompt before execution. FIFO server markers followed by actual GameTest callbacks verify both a distinct equal-valued same-stat prompt and a different stat prompt survive; only the fresh armor value reaches review and confirmation. No signed Minecraft chat message or public client chat transport is simulated.
- **Menu recovery:** fresh navigator readiness, prior-tool/full-inventory/selected-control state, Creative delayed opening and picker suppression survive an old disconnect. Separate genuine departures first establish each relevant production-created scalar, then verify cleanup. Actual native menu entry and upkeep remain usable.
- **Trades:** actual native saved reconnects and deliberately matching old/new revisions reject obsolete offer/remove/confirm/review/cancel entrypoints, stale review clicks and registered disconnect/death callbacks. Full inventory/mode state, both menus, consent and actual participant/observer files remain unchanged during these attempts. The exact current confirmations exchange once through production native pair saves and a durable isolated journal, preserving named components, receipts and unrelated state. A genuine departure cancels a separate unescrowed trade safely.
- **Helper combat reviews:** real owner and target reconnects require fresh owner plus separate local-console Codex approval. Detached approve/cancel/ceasefire calls and old lifecycle callbacks cannot retire fresh proposals/orders; stale reads cannot stop a replacement owner. Current OP revocation/ceasefire and genuine logout still stop active combat state. Existing native tactics tests remain included.
- **API history and assistant cancellation:** a captured old cache is deliberately reinserted to verify history filtering before pruning; fresh connection-bound samples survive delayed old callbacks. Production sampling is invoked directly with test tick values, so this does not measure cadence. Local fake assistant futures/cooldowns preserve fresh work against old disconnect/completion and still cancel on genuine current logout. No AI provider request or CLI invocation was made.

One preliminary run was rejected and preserved under `research/efficiency36-rejected-native-1/`: completing the queued-input case from a generic server task left its native XML result missing even though the console claimed all four passed. The test now observes a FIFO marker and completes within an actual GameTest callback. Production code and validation strictness were unchanged by this fixture repair. The fresh full thirteen-group rerun passed all 474 cases. Independent source/evidence reviews checked exact actors/listeners, both approvals, durable trade boundaries and nonvacuous cleanup preconditions.

**Delivery and limits:** this source/package candidate preserves the original Infinity artwork, separate solid Convergence chestplate, existing wings and inventory collections, exact native stats, client protections, all newer travel preparation and both AI approvals. The live server still runs **explore.26**, with its installed mod checksum unchanged. No live installation, restart, terrain refresh, main merge, public release or website deployment occurred. Authentic Java/iPad render checks, touch-form transport, signed chat, provider connectivity, public login and multiplayer load remain unverified. Native context/movement/closed-channel and build deprecation warnings remain unsuppressed; fresh bridge/lifecycle logs are clean. No new throughput or lag-proof claim is made.

After an approved installation and reconnect, use **Infinity Menu → My stats** to enter a number, review and confirm; cancel/reopen a prompt before sending a different stat value. Trade with a nearby Survival friend and confirm separately. Open **AI Helpers → Orders** and check both approvals remain required. Use a disposable world for forced stale callbacks or disconnect tests. Gear/device checks remain in [RELIABILITY.md](RELIABILITY.md); the solid chestplate and separate wings are retained choices.

## Historical explore.35 validation — command travel and session-bound consent

Validated October 11, 2026 on unchanged **Minecraft 26.3 / Java 25** and the pinned Fabric/Geyser stack: **467/467 unique native GameTests**, **196/196 Python launcher/resource tests** with resource warnings treated as errors, and **21** reproducible one-chest Odyssey templates. No final native case failed, errored or skipped. Ten fresh disposable runtime groups ran 439, 13, 2, 2, 5, 3, 4, 3, 2 and 3 cases respectively. Every registered class is covered; the repeated vanilla baseline is verified in each group and counted once. Provenance binds all Java source/resources, the exact release/original test jars, selected group jars, reports and pacing settings. Fresh Geyser registered **38 exported custom item definitions plus its built-in item**, **209 block overrides**, **two block-item overrides** and **eight custom blocks**, and answered a local Bedrock 26.51 UDP ping. **Two fresh isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed. Neither smoke log contains ERROR records. The playable save was not used.

The exact tested mod SHA256 is `a2bc0f9ee5eaf1f0f5728f619513db2713c4714ad718e619179247c9811c47d8`; original test jar SHA256 is `7d3b08ff8afc0689e7434c5c7b9f1035e8269bd0e56257248dc9481a6a1832bc`; aggregate native report SHA256 is `f3bf07653e467a394654076d4fe8abd9a38bc6d72e5b7ac89061054bcdeeaff1`. Final native evidence is preserved under `research/efficiency35-final-native/` and immutable tested jars under `research/efficiency35-immutable-jars/`. Shared packaging check paths now belong to explore.35. Earlier tested jars, reports, smoke worlds and packages remain preserved separately; both explore.34 ZIP checksums were reverified unchanged.

Two new serialized phased cases use real registered full-codec native connections, delivered teleport/chunk/player-loaded/keep-alive acknowledgements, actual player ticks and genuinely published/unloaded terrain:

- `/home`, `/warp`, configured Survival `/spawn` and native Survival `/spawn` each start with an actually unloaded exact collision halo. Accepted commands keep the full ordinary 60-tick countdown, exact coordinates and yaw/pitch. The player keeps ticking, and active mode/profile state, inventory, cursor, open menu and native player files remain unchanged before and after same-mode completion. Successful ordinary travel installs its cooldown; only owned loading tickets are released. Real inbound movement and positive native damage cancel later requests, and later actual ticks cannot revive them.
- Actual player consent freezes the host's accepted location while that host moves within its world. Real host disconnect, native same-UUID login preparation, fresh consent and a delayed old disconnect callback verify that a replacement session receives only its own job. A real host mode/world change cancels the visitor; later ticks cannot revive that consent.
- A genuinely cold OP cross-world route cancels after de-op and preserves state, then a fresh privileged request completes through the real native inventory hooks. Separate Survival/Creative inventories and returned cursor items remain conserved, while an unrelated observer's saved bytes stay unchanged. Separately verified FULL destinations preserve immediate OP travel despite ordinary combat/cooldown timers. Fixture-only warming occurs outside the measured command boundary.
- A real cold Creative default search refuses combat before starting native loads, preserves the original nearest-ring order, reaches the real safe column without repairing flooded terrain, and does not choose an inactive saved home. A separate loaded command observes exactly 64 native heightmap reads at acceptance and one on the next poll; technical readiness cannot skip the ordinary countdown. Ordinary Minigames and Adventure `/spawn` both refuse before cold map work or state/file changes. Living Hardcore `/spawn` also prepares its genuinely unloaded default while preserving current state.

Measured command acceptance/polling scopes make zero calls to the explicitly instrumented native `getChunkFuture` API; this is not instrumentation of every possible native managed-blocking path. The older lease/FIFO, exact destination, lobby, cross-mode, respawn, persistence, protocol, armor, helper, inventory and Odyssey checks all remain included. The frozen-course selector regression now also withdraws an already-ready course: its next poll fails terminally, and restoring availability cannot revive that request. Shutdown cleanup, command timeout/selector failure, native command teleport refusal, sender reconnect, death during these command jobs and simultaneous command jobs are source-reviewed rather than separately fault-injected by the two new cases; existing tests cover their own narrower lifecycle/selector paths.

Two preliminary full runs were rejected and preserved. The first new fixture assumed a remote halo remained FULL after cross-world departure and owned-ticket release; it now explicitly loads and verifies every collision chunk before each separate warm-OP check. The second compared state before existing lazy Creative Studio settings initialization; setup now calls the actual initializer before taking the full baseline. No state equality, inventory, file, cold-readiness or immediate-travel assertion was removed, and production code was not changed for either fixture error. Rejected evidence remains under `research/efficiency35-rejected-native-1/` and `research/efficiency35-rejected-native-2/`. The final full ten-group rerun passed all 467 cases. Independent source and documentation reviews verified identity-bound cleanup, retained commit leases, consent lifetime, default-cache scope and the distinct Hub/OP behavior.

**Delivery and limits:** source/package candidate explore.35 preserves original Infinity artwork, the separate solid Convergence chestplate, existing wings, inventory/menu entries, client protections and both AI approvals. Read-only status reports the live server running **explore.26**; its installed mod checksum remains unchanged. Roster/TPS data are unavailable, so this does not establish player count or performance. No live installation, restart, terrain refresh, main merge, public release or website deployment was performed. Authentic Java/iPad rendered gear, touch controls, flight, public joining and multiplayer load remain device checks after an approved installation. Legacy native context/movement/closed-channel and deprecation warnings remain unsuppressed; fresh Geyser/full-stack logs are clean.

Native death respawn keeps its separate synchronous terrain contract. Four-start and 64-column budgets bound this module's requests/scans, not total generation, memory, latency or server capacity. The preserved explore.32 packet comparison is historical evidence, not an explore.35 timing benchmark. No lag-proof or glitch-free result is claimed. After an approved installation, use [RELIABILITY.md](RELIABILITY.md) for gear/device checks; try a saved home and warp, verify that `/spawn` returns to the correct realm default, and accept a friend's visit while moving within the same world. Use a disposable world for unsafe destinations, de-op, combat and reconnect checks.

# Previous same-mode lobby preparation and Hardcore exits — 2.13.0-explore.34

Validated October 11, 2026 on unchanged **Minecraft 26.3 / Java 25** and the pinned Fabric/Geyser stack: **465/465 unique native GameTests**, **196/196 Python launcher/resource tests** with resource warnings treated as errors, and **21** reproducible one-chest Odyssey templates. No final native case failed, errored or skipped. Nine fresh disposable runtime groups ran 439, 13, 2, 2, 5, 3, 4, 3 and 2 cases respectively; their common vanilla baseline is verified in each and counted once. Provenance binds all Java source/resources, the exact release and original test jars, selected group jars, reports and pacing settings. Fresh Geyser registered **38 exported custom item definitions plus its built-in item**, **209 block overrides**, **two block-item overrides** and **eight custom blocks**, and answered a local Bedrock 26.51 UDP ping. **Two fresh isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed. Neither smoke log contains ERROR records. The playable save was not used.

The exact tested mod SHA256 is `033dbfc4a246d4da6e9b6e71b126ce1a38349c9b8f8d9c682c0e1a20b8cf272a`; original test jar SHA256 is `f66cb7d85d9a32b865ea33d1c0f7ffffbfe23f43a4f28aec5efad4573baea389`; aggregate native report SHA256 is `7ecd0ecbee4ec1b1d0bd58a9be5b23ee8a5eda10f2601db327eb62229cda09e0`. Final native evidence and its Python/Geyser/lifecycle logs are preserved under `research/efficiency34-final-native/`, with immutable tested jars under `research/efficiency34-immutable-jars/`. Shared packaging paths now belong to explore.35. Explore.33 jars, reports, smoke worlds and packages remain preserved separately.

Six added native cases cover these production boundaries:

- Three warm-destination Hardcore cases use actual fatal and nonfatal damage, the native respawn listener and registered full-codec player connections. An eliminated spectator completes the ordinary 60-tick exit while the fatal hit's 200-tick combat deadline is still active. Reentry remains rejected after the deadline expires; living Hardcore/Survival players still obey combat rejection and actual new damage cancels an ordinary pending transfer. Mode inventories, cursor contents, elimination state and native saved player data survive reload; an unrelated observer's file remains unchanged. These are warm destination checks, not independent cold loading measurements.
- Two exact-destination cases retain the actual four-chunk collision halo, share native leases and release only their own final references while preserving an unrelated native ticket. Ready ordinary locations are revalidated for floor and headspace; refusal remains terminal without a fallback, and a fresh request recovers after repair. Invalid coordinates/dimensions fail before leases, and ordinary world-height/border restrictions remain. OPs retain the same-mode physical-safety bypass. The fixtures use warm published FULL chunks and measure zero new native loading starts or calls to the explicitly instrumented `getChunkFuture` API; they do not independently instrument every native managed-blocking path.
- One phased lobby case physically unloads the known hall footprints, invokes the real ordinary command, replaces it through the registered sign routing method and advances actual connected-player ticks while the exact Adventure hall remains pending. Repeated requests keep their deadline; falling recovery cannot replace an explicit choice. Completion preserves inventory, cursor, profile snapshots and player files, changes only the selected hall field and delivers its welcome once. A blocked automatic rescue retries at 20-tick intervals with one repeated-problem message; repairing the actual block permits recovery. Cold OP permission loss, recovery above the fall threshold, OP grant, cross-mode departure, death cleanup, actual disconnect and native same-UUID reconnect cancel only their matching job. Stale requests and disconnect hooks cannot cancel the new session; late FULL publication cannot revive canceled arrivals. The death cleanup phase sets health to zero and calls the registered cleanup method directly; it does not claim a separate fatal-event-dispatch check. The sign phase calls its registered routing method, not an authentic client's block-use packet.

The lobby case passed in 5.551 seconds. Its registered connections acknowledge delivered teleport/chunk/player-loaded packets, but this fixture does not answer native keep-alive packets; substantially longer paced runs could require that harness support. The current successful case completed before the native 15-second keep-alive interval. Server-stop arrival cleanup and native teleport refusal/backoff are source-reviewed rather than separately fault-injected. Existing exclusive checks retain test-only 50ms minimum pacing and actual native task execution; no FULL completion or player state is fabricated.

Preliminary results were rejected and preserved. A test compilation error used removed `ChunkPos.toLong()`; the fixture now uses the pinned 26.3 `ChunkPos.pack(x, z)` API. The next full run passed its first eight groups, then the lobby fixture called the nonexistent `/lobby main` shortcut. It now uses the registered `/lobby` command. The final full nine-group rerun passed all 465 cases without dropping assertions or tests. Rejected evidence remains under `research/efficiency34-rejected-compile.log` and `research/efficiency34-rejected-native-1/`. Independent read-only reviews also led to dimension validation, OP-grant rescue cancellation and retaining leases/retry state through the native teleport result.

**Delivery and limits:** source/package candidate explore.34 preserves original Infinity artwork, the separate solid Convergence chestplate, existing wings, inventory/menu entries, client protections and both AI approvals. The live server remains **explore.26**, running and untouched. No live installation, restart, terrain refresh, main merge, public release or website deployment was performed. Authentic Java/iPad rendered gear, touch controls, flight, public joining and multiplayer load remain device checks after approved installation. Legacy native harness warnings remain unsuppressed; fresh Geyser/full-stack logs are clean.

Cross-mode, first-visit and same-mode Hub routes prepare their missing destination terrain across ticks. Homes, default-spawn commands outside the Hub and native respawn fallbacks retain synchronous paths. Four-start and 64-column budgets bound this module's work, not total native generation, memory, whole-server latency or capacity. The preserved explore.32 packet comparison is historical evidence, not an explore.34 timing measurement. No lag-proof or glitch-free result is claimed. After an approved installation, use [RELIABILITY.md](RELIABILITY.md) for inventory/device checks, try two Hub halls, and verify that an eliminated ordinary Hardcore player can return to Survival without waiting out the fatal-hit cooldown; use a disposable test world for death and blocked-platform checks.

# Previous destination preparation and completed-only discovery — 2.13.0-explore.33

Validated October 11, 2026 on unchanged **Minecraft 26.3 / Java 25** and the pinned Fabric/Geyser stack: **459/459 unique native GameTests**, **196/196 Python launcher/resource tests** with resource warnings treated as errors, and **21** reproducible one-chest Odyssey templates. No final native case failed, errored or skipped. Six separate disposable runtime groups ran 439, 13, 2, 2, 5 and 3 cases respectively; the same vanilla baseline was verified in each and counted once in the aggregate. Every registered class is covered. Provenance binds all Java source/resources, the exact release and original test jars, all selected jars, group reports and pacing settings. Fresh Geyser registered **38 exported custom item definitions plus its built-in item**, **209 block overrides**, **two block-item overrides** and **eight custom blocks**, and answered a local Bedrock 26.51 UDP ping. **Two fresh isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed with no ERROR records. The playable save was not used.

The exact tested mod SHA256 is `fa504bfdcb9d1ccf19cd305071f8b9b237d467021c4130f8791f91040b103e9a`; aggregate native report SHA256 is `82edce220cf057b9d977bdc151b41a9bfc6ede6bebaa8d6f5aa8b5586c66709f`. Current checks are under `research/integration/`, preserved under `research/efficiency33-final-native/`. Python, Geyser and lifecycle logs use the standard current-check paths.

Nine added native cases exercise actual production boundaries:

- Shared chunk leases use real native tickets and actual range futures. A warm FULL chunk is immediately ready; duplicate ownership renews the same ticket; only the final close removes it. Six genuinely cold centers verify FIFO order and no more than four native starts in any actual tick, including repeated calls during that tick. Readiness requires successful native results and published FULL chunks. Queued cancellation, completed/shared cleanup, an explicitly still-incomplete native future at cleanup, unrelated ticket preservation and fresh manager reuse all pass. The measured lease operations make zero calls to the native blocking `getChunkFuture`; native futures are never canceled by the lease manager. Runtime renewal/removal failure branches remain source-reviewed rather than fault-injected.
- A full-codec registered native player fixture prepares a remote Hardcore destination, physically unloads its FULL chunk, and then uses the real ordinary `/play` command. The cold saved position stays preferred. Before actual completion and the 60-tick countdown, current mode/world, profiles, cursor and native player files remain unchanged. Inventory changes made while waiting are captured only at commit, the old cursor is conserved, and an unrelated observer's file is unchanged. A real menu click and inbound movement cancel a later request. Actual fatal damage/native respawn while a request is pending, real disconnect, genuine native login preparation and same-UUID rejoin preserve the committed state. A stale player request and delayed old disconnect hook cannot cancel a fresh request. The distinct-new-request-before-old-end-sweep race is source-reviewed, not independently exercised by this phased case.
- Four selector cases verify a genuinely ready requested Hub hall, then refusal after border/floor/headspace changes; exactly 64 native heightmap reads in one poll plus 17 in the next on an all-loaded flooded grid; the original nearest-ring order and actual floor-to-feet height; and real retained collision footprints of 100 origin chunks, or 104 with a distant saved position. A frozen course that becomes unavailable before polling cannot substitute another available course's footprint or revive when the original availability returns. A fresh request recovers to the exact restored course. Player state and owned terrain are preserved by these fixture scopes.
- Two Odyssey cases prove completed-only local/reference lookup. The reference fixture uses the registered structure generator and its real generated piece metadata, temporarily holds its native FULL future incomplete, verifies no blocking request or progress, then restores that exact native future and discovers the piece once. It also checks the existing Creative/bounds/duplicate guards. This establishes journal handling of genuine generated metadata, not autonomous natural terrain placement or rendered-client discovery.
- A real native removed-player teleport refusal leaves the existing course, run, food, saturation, position and saved file unchanged.

Preliminary runs were rejected and preserved. Four test compilation mistakes used pre-26.3 method names and were corrected against the pinned native API. Legacy immediate-transfer fixtures needed explicitly completed destination and return collision halos; the independent cold-flow fixture still physically unloads its destination. One actual-death regression exposed a new combat check applied to the trusted internal immediate route; that route now retains its prior behavior without granting ordinary requests a bypass. Independent review found the frozen-course fallback race and the new native case reproduces its exact availability window. The native headless server initially consumed 240 ticks in about 0.08 seconds while all six generation futures remained incomplete. The exclusive cold fixtures now retain normal native task execution with **50ms minimum tick pacing** from a test-only mixin. They still require actual generation, ticket and FULL publication results; no completion, state or counter is manufactured. Default and native-play groups remain unpaced. This pacing is not a live TPS benchmark.

**Delivery and limits:** source/package candidate explore.33 preserves the original artwork, separate solid Convergence chestplate, existing wings, prior client protections and both AI approvals. The live server remains **explore.26**, running and untouched. No live installation, restart, terrain refresh, main merge, public release or website deployment was performed. Authentic Java/iPad rendering, touch menus, flight, public joining and multiplayer load remain device checks after approved installation. Some legacy native fixtures log Polymer/context, mock-player movement and closed-channel cleanup warnings; no warning suppression was added. Fresh Geyser/full-stack logs are clean.

Cross-mode preparation and first-visit routing now avoid requesting unavailable safety terrain synchronously. Same-mode Hub arrivals, home/default-spawn commands and native respawn fallbacks keep their existing synchronous paths. The inherited ordinary eliminated-Hardcore cooldown inconsistency remains: an ordinary request may initially be accepted, then canceled during polling while its previous combat cooldown is still active; retrying after that cooldown is the existing workaround. This was reported separately rather than expanding the current change. Four-start and 64-column budgets bound this module's requests/scans, not total native generation, all tick work, memory, latency or whole-server capacity. Shutdown ticket cleanup is best-effort after a native removal exception. The preserved explore.32 packet comparison below has not been rerun as an explore.33 timing measurement. No lag-proof or glitch-free claim is made.

# Previous native capacity refresh history — 2.13.0-explore.32

Validated October 11, 2026 on unchanged **Minecraft 26.3 / Java 25** and the pinned Fabric/Geyser stack: **450/450 unique native GameTests**, **195/195 Python launcher/resource tests** with resource warnings treated as errors, and **21** reproducible one-chest Odyssey templates. All native reports have no failures, errors or skips. The default disposable world ran 438 cases; a separate full-codec connection world ran 13, including the same vanilla baseline. The aggregate includes that baseline once. Every registered class remains covered. Final provenance binds the Java source/resources, exact release mod, original test jar, both selected test jars and reports. Fresh Geyser checks registered **38 exported custom item definitions plus its built-in item**, **209 block overrides**, **two block-item overrides** and **eight custom blocks**, and received a local Bedrock 26.51 UDP response. **Two isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed. Both smoke logs contain no ERROR records. No playable save was used.

The exact tested mod SHA256 is `bf700673eb33aec291ae9a7ba519947d22a99496e81c04a41595df8f40b4c265`; aggregate native report SHA256 is `c19aec9ac10439d4be8183135a41b4f0ccd6ee80457edf71a8330bf91c78ab6f`. Its immutable native evidence remains under `research/efficiency32-final-native/`. Shared current-check paths now belong to explore.33.

Eleven new full-codec connection cases cover repeated and individually known capacity fields; actual double-capacity changes above the wire bound, reductions and changed vitals; ordinary HUD and valid unrelated metadata; hunger/saturation; independent observers; nested lifecycle packet order; actual death/replacement-player respawn and its next native HUD tick; real disconnect/same-UUID rejoin; native visible helper tracking; bounded 256-target FIFO eviction; and permanent stateless fallback after asynchronous relevant sends. Their actual native encoders must complete successfully; failed writes cannot be counted. Native state/attribute/equipment NBT remains unchanged. The old stateless projection/copy tests still run.

## Controlled registered armor comparison

The separate compatible fixture compares the immutable explore.31 mod (`950c2e3d1a46d407ae4ea10d657706769a4eb0c3454402b6e426595d5db54cdd`) with explore.32. Four fresh worlds run two baseline/optimized pairs in alternating order. Each passes both report cases, warms for 40 actual ticks, then observes 120 server ticks and exactly 120 native ticks for each player through registered connections. The wearer uses the actual full Convergence armor and registered server upkeep; native Health Boost and Absorption each renew three times. The native ChunkMap must track the wearer for the observer throughout; no manual attribute sends or effect renewals are used.

| Delivery count in each 120-tick run | Explore.31 owner | Explore.32 owner | Explore.31 observer | Explore.32 observer |
| --- | --- | --- | --- | --- |
| Native capacity attribute packets | 3 | 3 | 3 | 3 |
| Synthetic health/absorption refresh groups | 3 | 0 | 3 | 0 |
| Total health packets during this stable window | 3 | 0 | 0 | 0 |
| Total wearer metadata packets during this stable window | 3 | 0 | 3 | 0 |

Both repeated pairs produced these same counts. True maximum health/absorption, current health/absorption, attribute bases, native saved attributes/equipment and all equipped item components stayed unchanged. These are successful native connection write counts on unpaced GameTest ticks; they do not establish live TPS, latency, client frame rate, overall memory use or multiplayer capacity. Tests for actual capacity/vitals changes separately require the refreshes.

`research/capacity-performance/2.13.0-explore.32/` contains raw JSON/XML/logs, summary and provenance. The runner binds input jars and all pinned runtime jars; completed provenance hashes every evidence file. Packaging verifies those hashes, the exact native-tested test jar/source/mod, nonempty complete pairs, sample/raw agreement and passing XML. New Python regressions reject replaced/missing test jars, empty/invalid repeat counts, malformed packet counts, absent tracking/renewals, incomplete player ticks and changed native state. The source archive includes the reproduction runner.

Preliminary fixture failures were investigated rather than reported as performance results. Bare channels did not encode native protocol tasks, and long legacy test profile names cannot pass the actual player-info codec. The new full-codec group runs separately without omitting old cases. Native tracking required delivered chunk acknowledgements; effect timers required actual connection-list membership and native player ticks. The fixture now acknowledges only delivered teleport/chunk packets, verifies those native ticks, and cleans up native connections/tickets. The initial quiet window failed its renewal assertion and was rejected. Test guards retain the original jar hash across both groups. Independent review also identified stale-test-jar and empty-measurement packaging gaps; both were fixed and regression-tested.

**Delivery and limits:** prepared source/package candidate explore.32 retains original artwork, separate solid Convergence chestplate, existing wings, prior reliability improvements and both AI approvals. The live server remains **explore.26**, running and unchanged. No installation, restart, terrain refresh, main merge, public release or website deployment was performed. Authentic Java/iPad joining, rendered gear, touch controls, flight, reconnect HUD and real multiplayer load remain device checks after an approved installation. Native fixtures still log legacy mock-player Polymer/context and closed-channel cleanup warnings; no suppression was added. Full-stack/Geyser logs are clean. Cold spawn search can still request up to 81 chunks synchronously; storage failures can still require owner repair. No lag-proof or glitch-free claim is made.

## Previous client-update copies and player-state cleanup — 2.13.0-explore.31

Validated October 11, 2026 with the unchanged **Minecraft 26.3 / Java 25** toolchain: **438/438 native GameTests** (no failures, errors or skips), **188/188 Python launcher/resource tests** with resource warnings treated as errors, and **21** reproducible one-chest Odyssey templates. Final native provenance matches every current Java source/resource file and the exact mod/report bytes. Fresh Geyser checks registered **38 exported custom item definitions plus its built-in item**, **209 block overrides**, **two block-item overrides** and **eight custom blocks**, and received a local Bedrock 26.51 UDP response. **Two isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles** passed. Both smoke logs are fresh for this mod and contain no ERROR entries. All game checks used disposable worlds; the playable save was not used.

The exact tested mod SHA256 is `950c2e3d1a46d407ae4ea10d657706769a4eb0c3454402b6e426595d5db54cdd`; native report SHA256 is `b9e8159d4a00dba8ec08272e8f36e7bfe2ac4e8603f09f2553f397a8af26456a`. Its immutable packages contain the check records; native evidence is preserved in `research/efficiency31-final-native/{results.xml,provenance.json,integration.log}`. Shared current-check paths now belong to explore.32.

Five client-vitals tests and four joined-player lifecycle tests were added. Test-mod-only counters observe constructors/copy sites in the actual production rewrite method: a random-access bundle containing 100 unchanged metadata children and 100 non-capacity attribute children returns its original identity with **zero copied output lists, attribute packets or metadata values**. A late metadata change copies exactly its metadata list and enclosing bundle list, recreates only two changed entries and preserves source/prefix identities. Normal capacity updates still refresh the HUD twice while creating no replacement attributes; a positive control with two expanded capacities creates exactly one attribute copy and preserves ordinary prefix/tail snapshots and true server state.

Other new regressions cover the actual outbound handler flattening an unchanged nested bundle, unchanged/changed one-shot iterables with exactly one input traversal and reusable output, signed-zero/NaN equality and native serializer correction. Native player join, actual death, disconnect and same-UUID rejoin verify state pruning, selections, cooldowns and existing age-reset behavior. The first 438-test run passed; independent review then added the positive attribute-copy control, and the final full 438-test run passed again. Both review passes were read-only; the root performed builds/tests.

**Delivery and limits:** source/package candidate explore.31 retains original Infinity artwork, separate solid Convergence armor and existing wings. The live server remains **explore.26**, unchanged by these checks. No installation, restart, terrain refresh, main merge, public release or website deployment was performed. Authenticated Java/iPad rendering, touch controls, flight, public joining and multiplayer performance remain device checks. Copy-site counters do not measure total JVM allocation, live TPS or client frame rate. The historical helper benchmark below is not a fresh explore.31 performance measurement. Capacity-refresh packet suppression remains deferred until tracking/respawn/removal invalidation has separate evidence. Existing mock-player Polymer warnings and native/Gradle deprecation warnings remain; no warning suppression or lag-proof claim was added.

## Previous tick maintenance and immediate storage — 2.13.0-explore.30

Validated October 11, 2026 on the unchanged **Minecraft 26.3 / Java 25** stack: **429/429 native GameTests**, **188/188 Python launcher/resource tests** with resource warnings treated as errors, **21** reproducible one-chest Odyssey templates, Geyser registration of **38 exported custom definitions plus its built-in item**, **209** block overrides and local Bedrock UDP response, and **two isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles**. The final native report has no failures, errors or skipped tests. Exact provenance binds the current Java source/resources, mod bytes and report; bridge/lifecycle logs are fresh and contain no ERROR entries. The playable world was not used.

The exact tested mod SHA256 is `470f56cc6b3330b798aa1138731fe3739361fae4d55413a9af66556a02fc1eb5`; native report SHA256 is `66507e80a87bf496c136477c5ca258d3a20ae8ed53064b60f893741e1d70cee2`. The immutable explore.30 server/source archives retain packaged check records. Its native evidence is preserved in `research/efficiency30-final-native/{results.xml,provenance.json,integration.log}`; shared current-check paths now belong to explore.32.

Fifteen added native cases plus a strengthened real backpack transfer case verify the changes:

- Four real filesystem obstruction cases cover automatic expiry, lifecycle target cancellation, five-second retry/recovery, a dirty-only final shutdown save, unchanged clean storage and required synchronous dispatch failure. Revocations remain terminal in memory and remove the exact live target session. Startup still rejects a stale target when its required cancellation cannot be saved; a new command still needs both exact approvals and durable consumption before execution.
- Five RC-plane cases observe one real living-entity query per clear candidate, then all nine exact part intersections. They preserve empty union corners, rotated wings, alive/spectator exclusions, early block/border/loaded refusal with no chunk request, immediate obstruction notice with ten-tick refresh, clearance reset, recall and permission loss.
- Two backpack cases and the transfer regression observe one full encoding per changed transfer, none for identical notifications/close, immediate component persistence, exact native player serialization, conserved item counts, mode-transition refusal, native codec failure at count100 and codec-valid/read-invalid overstack refusal at count65. Failed output never replaces saved NBT or advances the comparison snapshot. Restoring the exact saved contents skips encoding; a later legal component change saves once.
- Four helper/menu cases observe 24 direct helper-ID lookups without roster-entry traversal for 24 trackers, same-object/owner/name/current-OP4 validation, replaced/recreated/profile-change rejection, one copied navigator tag with native numeric-marker compatibility, and one 36-slot scan for a full missing-picker inventory. Real direct slot replacement still recovers the picker within one tick.

Preliminary native runs were not passes: new probes initially declared the whole game package as a mixin namespace; dedicated test-only namespaces repaired the loader conflict. A static test invoker could not access the package-private menu class; the test now invokes the actual private boundary through reflection. Three entity fixtures initially used an unavailable hostile in Peaceful Creative, then queried newly added cows before native section tracking. They now use actual cows, independent temporary simulation tickets, native-tick waits and balanced cleanup. Finally, a nonoperator spectator was correctly restored to Creative by existing mode rules; the fixture now grants actual OP4 and asserts its real modes before testing exclusions. Failed startup/XML/log evidence remains in the ignored research folder; only the final complete passing report is packaged. Production behavior was not bypassed to make fixtures pass.

**Delivery:** prepared local source/package explore.30, retaining explore.27 artwork/inventory, explore.28 reliability and explore.29 spawn/protocol/helper fixes. The immutable explore.29 server/source ZIP checksums were reverified unchanged. The live server remains **explore.26**, reported running and unmodified; its roster snapshot is unavailable, which does not establish an online player count. Neither installation, terrain refresh, restart, main merge, public release nor website deployment was performed. The separate solid Convergence chestplate and existing winged item remain available.

**Unverified/remaining:** authentic Java/iPad rendering, inventory/touch menus, crafting, flight, public joining and multiplayer TPS need player checks after an approved installation. Operation counters establish reduced repeated work, not a measured whole-server speedup. The earlier Regular-helper benchmark below is historical explore.29 evidence, not a new explore.30 timing comparison. Unchanged-capacity armor packets remain a separate optimization candidate. Cold spawn search can still request up to 81 chunks synchronously; persistent storage failure still needs owner repair, and native durability APIs can log some failures without throwing. No lag-proof or glitch-free claim is made.

## Previous spawn safety and helper work — 2.13.0-explore.29

Validated October 11, 2026 on **Minecraft 26.3 / Java 25** with the unchanged pinned stack: **414/414 native GameTests**, **188/188 Python launcher/resource tests** with resource warnings treated as errors, **21** reproducible one-chest Odyssey templates, Geyser registration of **38 exported custom definitions plus its built-in item**, **209** block overrides and local Bedrock UDP response, and **two isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles**. The final report has no failures, errors or skipped tests. Exact provenance binds the current Java source/resources, mod bytes and native report. Geyser and full-stack logs have no ERROR entries. The playable world was not used.

New native checks reproduce unavailable or blocked mode destinations without changing inventory, worn/offhand equipment, cursor items, profiles or terrain. A refused death respawn retains the original player and uses native disconnect cleanup; Minecraft 26.3's actual login preparation reloads its dead Hardcore realm and inventory, and a repaired destination permits native respawn. Real charged-anchor preflight and death consume exactly one charge overall. Alive requests and End credits retain native behavior. Review caught a redundant Survival spawn search; an early return and native lookup-counter regression keep that search solely in actual respawn.

Eleven in-process EmbeddedChannel/codec tests verify STATUS response/pong and fault closure, original fault details, outbound phase changes, native disconnect encoding in LOGIN/CONFIGURATION/PLAY, timeout, skipped packets and repeated/closed faults. The pinned native STATUS codec has no disconnect packet; the fix closes through native completion instead. The precise trigger of the earlier empty-server live fault remains unclassified. No external protocol probes or authenticated client login were performed.

Three native helper regressions verify six independent controls/queries, unchanged candidate/sight work, lazy focus peer scans, live earlier-helper targets, owner-threat priority, paused/passive/dead targets, walls, range and player-damage refusal. The expensive opt-in Regular-helper comparison is excluded from the normal suite explicitly. Three Python tests verify the runner's offline pinned-cache refusal, selective disposable copy and temporary-only benchmark registration. Controlled measurements are recorded below separately from gameplay correctness.

### Controlled Regular-helper comparison

One baseline/optimized pair used six registered helpers, 20 warmup batches and 100 measured batches at each population, with normal native helper movement/combat between batches. Each subject passed both native report cases and wrote all 400 raw samples. Input mod/test jars and 58 cached runtime jar hashes were unchanged. The explore.28 baseline mod was `8793c3e9f4c0ee95ae7374ab4c4d880aef611b0aced3f5ad52004689b9e979fe`; the explore.29 subject was `c685f239226d48eeaf8786b3bef610ef0821b15bfac485b5d0e9c5d31a8c8c72`.

| Eligible hostiles | Native queries per six-helper batch, both | Uncached sight rays, both | Baseline focus peer visits, mean (range) | Explore.29 focus peer visits |
| --- | --- | --- | --- | --- |
| 0 | 6 | 0 | 0 | 0 |
| 1 | 6 | 6 | 0 | 0 |
| 4 | 6 | 24 | 148.12 (127–163) | 36 |
| 128 | 6 | 768 | 5,978.37 (5,623–6,702) | 36 |

Dense instrumented control-batch p50/p95/p99/max were **0.835/0.963/1.051/1.094 ms** for the baseline and **0.424/0.600/0.641/4.364 ms** for explore.29. These are unpaced, control-only GameTest measurements. The counter itself runs more often in the baseline; JVM/physics/order noise and one comparison pair prevent an isolated speedup or TPS claim. The optimized maximum was higher. Both variants made six dense navigation requests per batch; actual native path searches varied (148 versus 218 total), so no navigation reduction is claimed. The verified result is fewer repeated focus peer visits with the same query/sight workload. Ultimate Finals and whole-server load were not measured.

Local raw samples, XML reports, logs, summary and provenance are retained in `research/helper-performance/comparison-20261011/`. The corrected short check also passed both subjects before this longer run. Reproduce with the source runner and prebuilt validated jars; no owner world or private settings are required or copied.

Preliminary native checks failed before the final passing run: a test-only initialized shadow overwrote the native server field; it is now an uninitialized shadow. A flooded-column fixture initially misread unloaded terrain and later attempted a same-state WATER write, which correctly returned false. The corrected owned column verifies native bounds and heightmap and restores its blocks. A refused-respawn fixture initially completed disconnect cleanup before its assertion and bypassed the real login NBT loader; it now gates native completion and uses `PrepareSpawnTask`. A closed-channel protocol fixture now invokes the original native handler context after pipeline removal. The first opt-in measurement marked success outside the framework's reporting tick, producing an incomplete XML report; its runner rejected the result before running the optimized subject. Completion now runs inside a scheduled framework tick, and the final normal suite was rebuilt and passed again. These failures were investigated and repaired; their failed reports are not passes.

**Delivery:** local source/package explore.29; the live server remains **explore.26**, running and unchanged. Installation, terrain refresh, restart, public release and website deployment were not performed. Explore.27 artwork/inventory and explore.28 reliability changes are retained. Both the separate solid Convergence chestplate and existing winged item remain available.

**Unverified/remaining:** real Java/iPad rendering, touch movement, crafting, flight, public joining and multiplayer TPS need client checks. The controlled Regular profile does not establish Ultimate Finals or whole-server performance. A cold spawn search can still synchronously request up to 81 chunks. Native durability APIs can log storage errors without throwing; trade verification remains intact. The existing mock-player Polymer warnings and native/Gradle deprecations remain. No lag-proof or glitch-free claim is made.

## Previous reliability and efficiency — 2.13.0-explore.28

Validated October 11, 2026 on the unchanged **Minecraft 26.3 / Java 25** stack: **391/391 native GameTests**, **182/182 Python launcher/resource tests** with resource warnings treated as errors, all **21** reproducible one-chest Odyssey templates, Geyser registration of **38 exported custom definitions plus its built-in item**, **209** block overrides and local UDP response, and **two isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles**. Final native provenance matches all current Java source/resource bytes, the tested mod and the report. Geyser and full-stack logs contain no ERROR entries. The real playable world was not used for these checks.

RELIABILITY.md records each observed failure, expected behavior, fix and regression. Blocked dashboard reads and failed logging sinks are tested with controlled gates and real child processes. Native-file checks preserve participant inventory/equipment/backpack styles, changed course choice and best times while leaving unrelated observers' files unchanged. A real filesystem obstruction verifies an unchanged leaderboard score retries after storage recovery. A blocked status writer allows three game ticks to advance, coalesces 1,000 submissions to one pending snapshot, recovers from failure and cannot overwrite replacement-server status after closing. The crafted solid chestplate is checked through the actual recipe manager, six armor tags and native chest enchantment eligibility. Direct grants preserve equipped counts and custom names with full main inventory.

The first combined native run passed 390 tests. Independent review then identified that skipping a slower finish could omit persistence of a newly chosen course. The fix saves changed selections immediately (without duplicating a mode-transfer save), and the final expanded 391-test run covers an actual file reload of that case. There are no failed or skipped tests in the final report.

**Delivery:** source/package explore.28; live server explore.26 remains running, unchanged by these checks. Installation, terrain refresh, restart, new public release and website deployment were not performed. The original artwork and inventory expansion from explore.27 is retained.

**Unverified/remaining:** authenticated Java/iPad rendering, glint/dye/equipment slots, crafting UI, flight, touch inventory movement, public remote joining and real multiplayer performance remain device checks. No helper mob-farm benchmark or guaranteed lag elimination is claimed. The existing native harness Polymer mock-player packet-context warnings and Gradle/native API deprecation warnings remain; the full-stack bridge check is clean. The live explore.26 log also contains a disconnect encoder error for an unclassified connection while the server was empty; no player login accompanied it, and its cause has not been established. An old host roster is expected after vanilla's 60-second empty-server pause and is correctly reported unavailable rather than treated as a fresh empty list. Native save APIs still log some storage failures rather than throwing; trade verification/rollback remains intact.

## Previous original collection artwork validation — 2.13.0-explore.27

Validated October 11, 2026 on the unchanged **Minecraft 26.3 / Java 25** stack: **381/381 native GameTests**, **151/151 Python launcher/resource tests**, all **21** reproducible one-chest Odyssey templates, Geyser registration of **38 exported custom item definitions plus its built-in mapping**, **209** block overrides and local UDP response, and **two isolated full-stack startup/cold-lobby/save/backup/restart/clean-shutdown cycles**. Full-stack and Geyser logs contain no ERROR entries. Exact native provenance matches every current Java source and the packaged mod bytes. The owner's playable world was not used for these development checks.

Original artwork now covers four humanoid armor/backpack atlases, 36 registered nonblock gear sprites and ten Infinity block tiles. The source image also contains six supplemental Studio motifs without new gameplay implementations. Thirteen static Bedrock wearables have item-specific attachables and nonstackable slot components. Ordinary leather/netherite remain normal. The new solid Convergence chestplate completes the existing armor powers; the old winged item ID, native flight, original recipe and saved items remain intact. A distinct netherite-chestplate upgrade recipe and native armor/enchantment tags support the solid piece. The Aurora and Convergence filters deliver real items, and five real named shulker boxes cover every registered item exactly once.

## Glitches found and repaired

- With a full inventory, normal tick delivery formerly set the direct-gear completion flag even though items could not be delivered. It now records completion only after all pieces are present. A real server-tick regression fills every slot, checks no false receipt or overwrite, clears room and retries through the actual grant path.
- Alphabetically mixed generic Convergence boxes hid the Aurora set. Separate Convergence, Aurora and Ember boxes now contain complete named sets; native save/reload and real box placement retain the actual contents. Collection menu transfers, permission loss, preserved previous equipment and pagination pass native checks.
- Simple equal-height source-atlas crops included small fragments of neighboring icons. Connected sprite bounds now preserve the correct object and paired boots/legs without those fragments. The finished artwork/UV/tiling contact sheets were visually inspected; they are not Minecraft render screenshots.
- Preparation replaced the newly selected block-atlas references with older root-level PNGs. It now retains explicit refreshed references; the regression reads the actual prepared pack and verifies both item and terrain atlases.
- Shared native leather/netherite textures cannot distinguish Infinity armor from ordinary armor. Static pieces now select custom identifiers and complete UV faces, without overriding native armor. Geyser accepts all definitions; a real client must still validate appearance.

The first new equipment fixture checked armor before a native player tick installed equipment modifiers; the corrected fixture runs the actual lifecycle tick. Its full-set powers and both chest choices then passed. The initial test compilation also used a collection method on a native stream; that test was corrected. Failed preliminary runs are superseded by the complete passing source-bound report, not reported as passes.

## Limits and delivery state

**Local source/package:** explore.27. **Live server:** explore.26, running after its separately approved installation. This development did not stop, install on, reset or replace the playable world. No new public release or website deployment has been made. See ORIGINAL_ARTWORK.md for the player controls and later installation/device checks.

Actual Java/iPad rendered armor, touch input, authenticated crafting/flight, multiplayer desync and performance under load remain unverified. Native tests and UDP probes do not establish client rendering or joining. The existing native wing item still shares elytra artwork on Bedrock; native shield/totem/bow/crossbow/arrows retain their ordinary Bedrock images and behavior. Java without its resource pack retains native wearable fallback. Tidewarden and other native Studio outfits remain their established equipment. Backpack skins are flat equipment layers, not new 3D bags.

The previously documented Polymer mock-player packet-context warning remains in the native harness; no suppression or dependency upgrade was applied. The normal full-stack and Geyser smoke logs are clean. No claim of a glitch-free game, unlimited capacity or verified public login is made. Installing requires the owner's approval, a fresh verified stopped-world backup and the existing protected terrain policy; saves, inventory, private settings, helpers and both AI approvals must be preserved.

Reproduce with the existing commands below. Rebuild generated art only when needed with Python/Pillow and artwork/refresh27/pack_art.py, before rebuilding and rerunning source-bound checks.

# Inventory, navigation and menu improvements — 2.13.0-explore.26

Validated October 11, 2026 on the unchanged Minecraft **26.3 / Java 25** stack: **377/377 native Minecraft GameTests**, **146/146 Python launcher tests**, all **21** reproducible one-chest Odyssey templates, Geyser mapping/resource validation and local Bedrock UDP response, and **two isolated full-stack startup, cold-lobby, save, backup, restart and clean-shutdown cycles**. Geyser and full-stack logs have no ERROR records. Final native provenance matches every current Java source and the exact prepared mod. All checks used disposable worlds; the owner's running save was not used for these tests.

Twelve added native regressions cover real registered structure scans, owned/offhand compasses, dimension-aware pointers and native inventory persistence; no fabricated discovery, no distant chunk generation, full inventories and busy cursors; real course instructions, personal/public records and Retry; foreign/malformed/transition/wrong-world course clicks; the eliminated-Hardcore exit; saved squad profile changes with unchanged health, attributes, Stay and both player-target gates; wrong-world helper controls; native alias arguments, suggestions, existing `/ai`, permissions and command-name collisions; toolbox permission/cursor/full-inventory guards, menu recovery and native save/reload; placing the real box with all ten actual contents; and normal server-tick delivery without repeated automatic gifts. Existing stats, equipment, native combat, resource/name, mode inventory, trading and AI approval regressions passed.

## Glitches fixed in this update

- Course and helper screens could remain valid across a world change. Both now capture the original world, connection and exact current menu; delayed old clicks cannot move or reconfigure a player/helper in a later session.
- A course menu's ordinary Back button would not open Infinity Menu for an eliminated Hardcore spectator. That player now receives an explicit Main Hub transfer through the existing mode-change path.
- Locked, already claimed and Creative-mode journal rewards returned silently. The journal now explains the missing requirement, claimed receipt or required Survival/Hardcore abilities without changing rewards.
- Short-command registration preserves existing names, including the original `/ai` chat command. `/ag` opens helper controls; aliases do not replace another command or relax its permission checks.

## Unresolved and unverified

Native runs log Polymer's **"chunk update packet, without the packet context present"** warning from `PlayerChunkSender` while using embedded mock player connections. A separate fresh disposable control with the exact prior explore.25 mod (SHA-256 `845035c4b37d1021f1279485b1d8c2b047732924305c77e9027410dff38a897a`) reproduced the same stack and passed all **365/365** baseline tests. The warning remains unresolved; this comparison does not prove it is harmless for real clients. The normal full-stack and Geyser smoke logs did not show it. No dependency upgrade or warning suppression was applied.

Authenticated Java/iPad joining, rendered visuals, touch input, multiplayer play under load and public capacity still need a real-client check. Native menu and item-use tests do not establish client rendering. The toolbox uses native items and a real shulker inventory on both editions; a custom Java Creative tab requires the Fabric client, and native debug-stick controls have limited Bedrock support. The compass searches only completed loaded chunks within 96 horizontal blocks; it never generates distant chunks or awards progress. Existing armor textures, helper skin, original loot and separate AI approvals remain unchanged.

**Delivery state:** the source and built package are explore.26. The live Mac server remains **explore.25** and is running. Its separately requested October 11 chunk refresh committed after a verified stopped-world backup; unprotected terrain regenerates while the small protected player/helper/pet/hub/End areas remain. Nothing in these development checks installed explore.26 or changed that private policy. Installing the new mod requires the owner's approval, a fresh verified stopped-world backup and the existing protected refresh workflow.

Try the new controls after installation using [EXPERIENCE_POLISH.md](EXPERIENCE_POLISH.md). Reproduce with the same build/native/launcher/Geyser/full-stack/package commands below. The packaged checks and source provenance are the evidence; these results do not mean every possible glitch has been removed.

# Circuit Keepers and playground expansion — 2.13.0-explore.25

Validated October 10, 2026 with Minecraft Java **26.3**, **Java 25** and the pinned Fabric/Geyser release dependencies. Fresh checks passed **365/365 native Minecraft GameTests**, **146/146 Python launcher tests**, all **21** reproducible Odyssey templates, Geyser mapping/resource validation and Bedrock UDP response, and **two isolated full-stack startup, cold-lobby, save, backup, restart and clean-shutdown cycles** without ERROR entries. The final native report and provenance bind the exact source and packaged mod bytes. These checks used disposable test worlds, not the owner's running save.

Ten new native tests cover real gadget power dispatch and permission loss, exact safe anchor movement and refusal of blocked/distant destinations, all four equipped Tidewarden slots and atomic delivery, discovery inside an actual registered Odyssey piece, native journal and formation save/reload, full-inventory retries and duplicate receipts, foreign/stale/cursor menu clicks, physical wrong/correct Memory Circuit pad paths, red/green Laser Gate Dash checkpoint paths, safe distinct helper formation positions and unchanged player-target gates, and old/new public leaderboard records after reload. Existing lifecycle combat, weapons, stats, texture, inventory-isolation, menu, and AI review regressions passed too.

## Glitches caught and fixed

- Adding a seventh course made the previous selector reject the menu because it had six slots. It now lays out all eight courses in two rows, with native route checks for every icon.
- The public leaderboard index omitted the new map IDs, so their records would not have appeared after a finish. Both IDs now participate in commands, player-score sync and the persisted index; a save/reload regression also preserves the old parkour record.
- Fixed gate pillars alone could not show each runner's changing light. Native green/red particles and text now use that runner's timer and are sent only to that runner, leaving shared course blocks intact. Graphical appearance still needs a device check.
- Updated built-in help and guides to include both games, the journal, new native gadgets, Tidewarden and saved formations. The root guide now records the actual installed release separately from the historical validation entries.

**Delivery state:** the owner approved the live installation on October 10 after reviewing the tested package. The Mac now runs **Minecraft 26.3 / explore.25**. A fresh stopped-world ZIP was verified against all 81 world files before installation. After the protected-area terrain refresh and startup, all 37 preserved settings, credentials and personal files still matched their pre-install checksums. The recovery manifest committed successfully, its full backup checksum verified, and the actual live generation marker lists both new courses along with all eight existing maps. The native mod/pack bytes match the tested dependencies. Java 26.3 status and Bedrock RakNet both responded locally, startup ERROR filters were empty, and the measured idle tick was about 0.6 ms; authenticated joining and client rendering remain untested. Old terrain, the original installed mod and the pre-install public/private files are retained privately for recovery. Its four earned gifts add to the original prophecy and never change the original one-chest ruin or exact loot. The new outfit and gadgets use native equipment/item icons; existing Java artwork and ordinary Bedrock leather/netherite textures are preserved. No fresh texture files, helper identities or private settings were replaced.

**Still unverified:** actual Java/iPad rendering, touch input, authenticated public joining and multiplayer play under load. The two smoke cycles check authenticated-mode configuration, not an authenticated player login. Native paths prove the tested server behavior; they do not establish that all possible glitches are gone. An installation requires a fresh verified stopped-world backup and the owner's approval. The existing private terrain policy refreshes unprotected terrain on a changed mod fingerprint while preserving its small protected player/helper/pet areas and End arena.

After installing: use **Infinity Menu → Creative Studio** for Gust Glove, Anchor Charm, Set anchor and Tidewarden; **The Odyssey • journal & prophecy** for discovery progress and gifts; **Minigames** for Memory Circuit and Laser Gate Dash; and **AI Helpers → Follow formation** to cycle Escort/Wedge/Ring. Claim Odyssey gifts back in Survival or Hardcore with an empty cursor and enough free slots. A new native save retains discoveries, gift receipts and formation preferences.

# Historical: private terrain refresh and exact-account join rules — launcher patch

The destructive refresh described below was tested with an earlier launcher. Current ordinary starts preserve existing terrain; the `--terrain-refresh` smoke option now checks preservation rather than reproducing that older refresh.

The owner can opt in privately to regenerate unprotected terrain after the installed Infinity mod version/checksum changes. Public packages retain terrain by default; ordinary restarts of the same mod retain new builds. Old terrain/POI/entities, stale chunk tickets are archived; generation markers and the bounded built-in hub footprint remain together; exact native chunks around saved players, alternate-mode positions, personal locations, helpers/tamed pets and the End arena/gateways remain. Player files, inventories/backpacks, ranks and global settings are not rewritten. See [WORLD_REFRESH.md](WORLD_REFRESH.md) for the destructive scope and recovery instructions.

**146 Python tests passed** on October 10. New regressions cover player/inventory preservation, exact chunk-record compaction, owned pet/helper retention, UUID normalization, excluded projectiles/items, alternate-mode positions, both ends of a gateway, external chunk files, malformed data and memory limits. They also cover unchanged-release and package-only update retention, explicit one-time refresh, stale/corrupt backups, legacy layouts, nested and specially named dimensions, unsafe paths, interrupted moves/protected writes with orphan temporary files, missing recovery data and committed interruption. Recovery runs before the startup backup, preserves an owner's disable decision and finalizes its manifest. Directory updates and macOS journal/policy writes are flushed. A real subprocess received the exact Bedrock account ban; IPv6/logger forms match, failed sends remain retryable and a failed hook does not stop output draining. Chat and partial names were ignored.

**Three isolated Fabric 26.3/Geyser cycles passed** with the protected strategy: the first run placed native blocks and a helper/pet, the update refresh erased an unprotected block, retained the complete hub and reconciled partial courses, and the next same-release restart retained a new block. Native player inventory NBT/stats remained byte-identical; the helper, owned wolf, their supporting floor and an End portal survived. Java saved all eight dimensions, Geyser answered a local UDP ping, cold lobby loading remained responsive, and shutdown was clean with no ERROR records. The former destructive scenario is historical. The current `python3 tools/smoke_fullstack.py --terrain-refresh --java /path/to/java` checks world preservation in a fresh disposable save. Earlier preliminary unprotected checks were superseded by the strategy recorded here.

This patch changes the launcher only. The previously passing 355 native GameTests and explore.24 JAR/resource packs are unchanged. These checks do not establish an authenticated Aria login, public reachability, a dragon kill/respawn cycle or an actual Java/iPad client playtest. Already unsafe saved positions and old structure starts inside protected chunks remain. The live installation and verified stopped-world backup are reported separately. Build a separate local package with `python3 tools/package_crossplay.py --launcher-patch`; it preserves the original release archives.

The owner's authorized live refresh completed on October 10 after a fresh 225-file world ZIP was verified byte-for-byte. The retained-hub marker failure described below was repaired from that backup, without repeating the reset or replacing personal files. The corrected launcher started Minecraft 26.3/explore.24 successfully; Java and Bedrock both answered local protocol probes. All 27 pre-install protected configuration/personal files, including four player files, still matched their original checksums. The existing helper roster was empty and stayed empty. Original terrain, the full ZIP, the pre-install launcher and the hub repair snapshot remain recoverable. No authenticated client join was tested during this installation.

Claude reviewed the supplied launcher code twice, read-only. Concrete recovery, IPv6, nested-dimension, version-trigger, output-draining and recovery-instruction findings were corrected. After its world-safety findings, the owner chose protected areas; keeping their native records retains supporting terrain and the End arena while clearing unprotected entities. The second review found orphan temporary-file recovery, broad owner-field matching, UUID normalization and gateway dual-position issues; each was fixed with regressions. Early failed fixtures were repaired rather than reported as passes: a test path needed macOS canonicalization, and a freshly summoned helper needed to become entity-ticking before its tag selector could see it. A live startup exposed a retained hub fragment whose marker had been archived; native safety checks correctly refused to rebuild over it. The fix keeps all hub footprint chunks and generation provenance together, with explicit saved-mode positions in the real restart fixture. Public defaults remain off.

# Creative Studio and TNT safeguards — 2.13.0-explore.24

Fresh checks on October 9 passed: **355/355 native Minecraft GameTests**, **103/103 Python launcher tests**, reproducibility of all **21 Odyssey templates**, Geyser mapping/texture validation and local UDP response, and **two isolated Fabric 26.3/Geyser startup, cold-lobby, save, restart and shutdown cycles**. Tests used Java 25 and the pinned release dependencies. The live world was not used for these checks.

The sixteen new native tests exercise real chest clicks, foreign/stale packets, permission changes, full-inventory atomic delivery, actual terrain edits and conflict-aware undo, exact blueprint confirmation, player-data serialization, Creative inventory separation, all four native outfit slots, registered gadget dispatch, plane movement/hover/collision/recall, orphan plane cleanup, a five-charge TNT queue, native explosion radius bounds and helper identity/health preservation.

| Reproduction | Expected and observed before repair | Root cause and retest |
| --- | --- | --- |
| Create two identical blueprint previews in one tick, then submit the older object. | The current preview should survive; the stale submission removed it. | Record equality made conditional map removal accept an equal but different preview. Identity validation now preserves the exact current preview; native regression passes. |
| Build the Java pack with the new gadget IDs. | Every registered item should have a rendered model; the five new IDs had no pack models. | Minecraft 26.3 finalizes each item's model after its copied component chain. Explicit item definitions now use existing native geometry, and the real resource-pack generation test passes for all 45 IDs. |
| Dispatch a Studio gadget from held-power controls after leaving Creative. | The rejected action should return failure; generic dispatch reported success without an effect. | Dispatch now checks live Studio access and returns the actual gadget result. Creative, Survival and Spectator native regressions pass. |
| Equip ordinary leather/netherite while using the earlier Bedrock pack. | Normal Minecraft armor was requested; shared native paths showed custom armor patterns. | Preparation excludes those native texture/icon overrides while preserving source PNGs and the custom wing controller. The regression inspects the actual prepared pack; original artwork checksums are retained. |

The plane and TNT lifecycle fixtures explicitly load and ticket their disposable chunks. A mock player's direct test teleport does not provide the real client's chunk tickets; without those tickets, plane parts correctly unload and queued TNT cannot tick. These fixture repairs do not make production planes force-load terrain. Failed early test reports were superseded by the final complete passing report.

Primed TNT is delayed rather than deleted: at most two explosions per tick across all worlds, with the second deferred after an eight-millisecond work window. Ordinary radii are unchanged; excessive radii stop at eight. This is a bounded workload safeguard, **not a promise of zero lag**. Large entity counts, TNT minecarts, other repeated explosions and client hardware remain limits. The reported frozen iPad app recovered after fully closing and reopening Minecraft; no server crash was established from that report.

**Delivery state:** explore.24 is a local tested package, not installed on the live server by these checks. Claude separately installed **Minecraft 26.3 / explore.23** on October 9. The converted world must not be loaded with 1.21.11. Java/iPad visual rendering, aircraft smoothness, touch interactions and authenticated crafting still need a real client playtest after the new package is installed. Bedrock Aurora/Ember/backpack clothing keeps native dyed-leather shapes after restoring ordinary armor textures; dedicated Java artwork and native elytra artwork remain. Existing gear-name, mode, stat, helper combat and both AI approval regressions passed with the combined code.

# Legacy gear name repair — 2.13.0-explore.23

The October 8 review reproduced an older saved item whose `CUSTOM_NAME` contains an `item.convergence.*` or `block.convergence.*` translation key. That component takes precedence over the readable `ITEM_NAME`. The old assertion checked `getString()` on the server, where the custom language file is present, so it missed the raw translation key sent to clients without that file. The new raw-component regression failed on the original code.

The outgoing Polymer copy now resolves only known Infinity item/block translation nodes in custom names. Literal player-written names, other translation keys/fallbacks, nested arguments, styling and sibling text survive. Authoritative items are not rewritten. The regressions cover every registered gear item, native codec save/reload and Creative round-trip preservation. The fix applies to the native item representation used by Java without the pack and by Geyser; a real iPad client still needs to confirm the displayed result.

Fresh release checks passed: **338 native Minecraft GameTests**, **103 Python launcher tests**, Geyser mapping/UDP startup and clean shutdown, and **two isolated full-stack Fabric 26.3/Geyser cycles**. Claude reviewed the name projection and regressions read-only and found no concrete regression in that scope. This does not establish that all name or graphical glitches are fixed.

At the time these name checks were recorded, the playable Mac server was on **Minecraft 1.21.11 / explore.20**. Claude subsequently repaired its private installer and installed **Minecraft 26.3 / explore.23** on October 9 under separate authorization; that installer is not bundled. The combined source includes Claude's finished host opt-in for the ruin ban, documented in ODYSSEY.md; public packages do not enable it by default.

# The Odyssey ruins and prophecy — 2.13.0-explore.22

Validated October 6, 2026 with the combined Java 26.3 / Java 25 toolchain. This build retains the refreshed Java and native Bedrock gear, client protections, menus, stats and helpers.

- **336 native Minecraft GameTests passed** with release dependencies. Ten Odyssey regressions cover all 21 templates, the lower chest's exact 27 decoded stacks and original book, actual chest loading, all native biome tags, native sparse generation, Nether cavern selection, inventory-safe prophecy delivery and menu recovery, scoped command permission, persistent piece rotation and initial native repeater scheduling.
- **103 Python launcher tests passed**, and Geyser loaded its item/block mappings, answered the loopback Bedrock UDP ping and shut down cleanly.
- **Two isolated full-stack cycles passed** with real Fabric + Geyser, all five mode dimensions, responsive cold lobby loading, restart, stopped-world backup and clean shutdown without ERROR lines.
- A fresh, isolated normal world with seed `26100622` located and generated an Odyssey ruin in each of the Overworld, Nether and End. Reading the saved native chunks verified one ruin chest, all 27 original stacks and the exact always-active repeater in each dimension. With command blocks enabled, a generated repeater successfully executed the native ban against a seeded, disposable cached test profile; the real owner's ban list was untouched.
- Preparation and packaging verify deterministic templates against the sanitized blueprint. The exporter uses 26.3's `id`/`properties` native block-state format; the placed-container regression catches a template that silently loads as air.

The owner's playable world remains on 1.21.11 / explore.20 pending installation approval. The combined update requires a stopped-world backup and a one-way world conversion to 26.3. No live world, player inventory, helpers or private settings were edited for these checks. Java/iPad client gameplay and the new structure's appearance still require a device check after installation.

## Retained native Bedrock armor appearance

Explore.19 refreshed Java armor and mapped Bedrock tool sprites but deliberately left native Bedrock worn armor unchanged. This fix routes the artwork through the native texture paths actually selected by the iPad client. Native netherite layers now use Convergence, and native leather layers use Aurora with native dye coloring. Their inventory images are refreshed too. Normal netherite/leather armor shares the changed patterns while this resource pack is active; Ember/backpack leather shares Aurora's pattern with its original dye tint.

The native elytra attachable is now included by preparation. It uses the existing wings with native geometry and animations, plus a Convergence chestplate render layer. The old dormant custom-property condition is removed. No wearable IDs, Geyser component mappings, equip slots, flight mechanics, authoritative stats, saved inventories or helper state are changed. Original PNGs remain unchanged.

A new launcher regression inspects the actual prepared pack: both native worn layers, every native inventory icon, retained native elytra animations, chest texture/controller presence, and absence of the unset-property gate. Fresh explore.20 checks passed: 325 native Minecraft GameTests, 102 Python launcher tests (including the new prepared-pack native armor regression), Geyser mapping/UDP startup and shutdown, and two isolated full-stack server cycles with backup/restart. The actual prepared pack contains resource revision [2,12,20], both native netherite/leather layers and their inventory images, and the native elytra plus Convergence chest render controller. All original PNG checksums still match. Graphical Java/iPad rendering, dye appearance and flight controls still need an actual client check.

## Retained behavior and earlier fixes

Validated October 6, 2026 with Java 25, Minecraft 26.3, Fabric Loader 0.19.5, Fabric API 0.161.0, Polymer 0.18.2, Floodgate 2.2.7-b69, ViaFabric 0.4.22, ViaBackwards 5.12.0 and Geyser 2.11.3-b1248. Test worlds are isolated from the owner's playable save.

## Minecraft 26.3 port

- **326 native Minecraft GameTests passed on 26.3** in three consecutive runs with the release dependencies (earlier builds of this port passed ten consecutive runs). Helper recall waits for the arrival chunk to become entity-ticking, which can take several ticks since 26.1, before checking that exactly one live helper exists.
- **103 Python launcher tests passed**, including a check that setup removes superseded pinned mod JARs while keeping owner-added mods.
- **Geyser smoke passed** with 21 custom items and 209 block overrides; the Bedrock ping advertises 26.51.
- **Full-stack smoke passed** twice: real Fabric 26.3 + Geyser, all five mode dimensions, cold lobby chunk loading while responsive, restart, stopped-world backup and clean shutdown with no ERROR lines.
- **A copy of the owner's latest stopped-world backup converted cleanly**: all five mode dimensions and their region files migrated and loaded, every Infinity data file was byte-identical after loading, the player's saved Infinity state survived, and the log had no errors. Vanilla 26.x moves player saves to `players/data`; the first-visit check now reads that location (it previously would have sent every returning Survival player to the hub).
- **Protocol bridge:** on that converted copy, a Java 26.2-protocol client (what Geyser speaks) and a 26.3 client both received a status reply and completed login into the configuration phase through ViaBackwards.
- Fixed 26.x behavior changes found by the tests: item components now bind after registration (gear inherits its vanilla base at bind time), Polymer 0.18 item hooks gained a registry parameter, golem targeting now filters players (only a helper's approved order passes, and removal clears it), the hurt cooldown moved to `damageCooldownTime`, and the GameTest server uses the `flat_all_dimensions` preset.
- A Codex review of the port (gpt-6-astra) found one issue, stale helper target approvals after removal, which is fixed and tested.

Not covered: a real Java 26.3 or Bedrock/iPad player joining and playing. The protocol test stops at configuration, and authenticated device play still needs a person.

## Earlier 1.21.11 checks

## Completed checks

The code review found two coverage gaps. Other clients still received unbounded health and absorption capacity for edited players and helpers, even though the owner's HUD was protected. The outgoing projection now applies to visible server players and registered helpers, preserves the shared input packets and saved stats, refreshes observed health when capacity changes, and never sends another entity's health into the viewer's own HUD. Ordinary mobs retain native packets. Two new native network regressions cover observer updates, capacity reset, huge registered helper health and exact saved-state preservation.

The dashboard previously refused every existing launcher lock, preventing the console launcher's safe crash recovery from running. Dashboard preflight now checks the existing lock without changing it or the settings. Real startup repeats PID, ownership, file identity and port checks before replacing only a verified stale lock. Active/unverifiable locks and busy game ports remain protected. Three new launcher regressions cover this complete path and rejected starts without settings changes. The stat editor now explains the bounded physical knockback without implying the saved attack-knockback value is reset.

The later disconnect was a server watchdog shutdown after a 60-second tick. Its native stack was in a zombie's movement collision scan. The preceding admin audit recorded 1e20 attack knockback, and the owner confirmed setting it. The new runtime protection bounds physical knockback impulses at 8 and velocity/collision movement at 32 blocks per tick without overwriting saved attributes. Non-finite vectors become stationary. Ordinary movement and jump velocity 15 remain unchanged. Native zombie movement and a real player-hit regression cover this path; a restarted live server still needs the owner's gameplay test.

The owner earlier reported an iPad app exit just after joining. Both authenticated joins reached Fabric; Geyser later recorded client timeouts while the server stayed healthy. The saved player had approximately 1e21 current/max health. This was a suspected client HUD overload, not an iOS crash-report diagnosis. The outgoing packet projection leaves authoritative stats intact, bounds the client's health capacity to 40 with proportional current health, bounds displayed absorption to 40 and hunger/saturation to 20, and refreshes the display on capacity-only edits. The owner confirmed the world/HUD stayed open after that patch, and the server recorded a sustained authenticated session before the separate movement freeze.

- The classic 64×64 robot skin carries pixel versions of the ChatGPT knot and Codex cloud on the front chest. Minecraft’s hosted image matches all uploaded RGBA pixels. Its texture-property signature was verified against Minecraft Services’ current public profile-property keys. The bundled property is public skin data; no MineSkin login, API key or runtime signing request is needed. The updated native avatar check verifies the shared signed texture, separate helper UUIDs, exact preservation through the player-info wire codec, opaque base faces and transparent unused overlays.

- **325 native Minecraft GameTests passed.** New tests exercise a native player hit with 1e20 knockback, actual zombie ticks, collision movement with finite double extremes, non-finite impulses, unchanged ordinary movement and jump velocity 15. Real outbound network tests cover approximately 1e21 health/absorption, native health codec round-trip, unchanged full saved player state, capacity-only edits and returning to ordinary health, effective modifiers, shared packets and flattened bundles. Direct health and absorption entry plans a capacity increase before applying the exact vital value, preserves all modifiers and has no preview mutation. Impossible modifier combinations and non-finite/out-of-storage values fail before changing either stat. Changed capacity invalidates both reviewed edits. Native save/reload preserves consumed extended absorption on players and helpers. Exact Java entry, plus buttons, Bedrock native confirmation, replay/session/OP4 guards and reset history remain tested.
- Expanded armor now affects native armor reduction beyond 30, up to full armor protection. Tests distinguish ordinary capped armor, an edited/reloaded instance, native damage through actual armor and armor-bypassing damage. Reset restores the original native behavior. High armor is not immunity to all damage types or weapon effects.
- New inventory/name checks verify actual starter gear and all eight Aurora/Ember armor pieces, including chestplates, in empty player slots; repeated grants avoid duplicates and full inventories preserve every existing item. Every registered custom item has a literal human label on server and native client wire stacks while anvil names survive. Native wearable assets round-trip without mutating the server equipment. Existing armor/crafting/menu checks remain.
- Helper avatars use native PLAYER spawn types, matching unlisted player-info UUIDs sent before spawning, bounded profile names, typed player metadata and current equipment. The player-info packet round-trips the real native codec; ordinary golems are unchanged and dismissal cleans profile viewers. A new autonomous combat check runs normal lifecycle ticks without calling control, attack or aerial methods manually: the helper acquires a hostile and lands a native weapon hit. Earlier actual spear charging, elytra glide, mace fall damage, close-range fallback, walls, ceilings, cooldowns, target side-effect guards and both approval gates passed.
- The ranks menu recognizes the actual permanent rank and separates it from Admin/OP roles. General AI facts and bounded helper data include the caller's actual badge and permanent rank; their display retains the data/privacy notice. Earlier mode, lobby, minigame, reward, trade, admin and answer-only Codex tests passed.
- **102 Python launcher tests passed**, including mappings/resource coverage, unchanged original PNG checksums, pack/module cache revisions, runtime, dashboard, backups, ports, providers and private state. All wearables are excluded from incompatible Bedrock custom item definitions; custom exported names are literal.
- **Geyser smoke passed:** 21 custom items (20 exported plus the bridge's built-in mapping) and 209 block overrides registered. Bedrock UDP ping answered and clean shutdown passed without ERROR entries. Two full-stack Fabric/Geyser cycles started isolated mode worlds, answered UDP, loaded the saved hub while command-responsive, backed up, restarted and stopped cleanly.

## Scope and review limits

This update uses dependency inspection and native network regression checks. No fresh Gemini or Claude consultation was performed. The earlier signed-texture verification remains valid; the helper skin and original texture assets are unchanged.

A real Java and iPad client must still verify the new appearance, touch controls and resource-pack reload. Native packets/tests establish server behavior, not graphical client rendering, an authenticated public join or public-server capacity. All Bedrock wearables keep complete native dyed-leather/netherite/elytra equipment assets with refreshed shared native textures; pack-enabled Java uses the refreshed custom artwork. Original PNGs are unchanged. The Infinity chestplate uses the native elytra form on Bedrock. Direct inventory gear works alongside full shulker gear boxes; custom gear does not enter Geyser's unsupported native Creative search catalogue.

Manual stat edits require the admin's own confirmation. Health/absorption can raise capacity in the same review; numeric storage, native physics and client/HUD bounds remain. Damage consumes health and absorption normally. Food reserves can be consumed/reset by gameplay. Armor-bypassing damage and native enchantment effects still apply.

Helpers use the shared robot player skin but retain the existing golem-based server AI, physical collision and saved ownership; they are not authenticated Minecraft player accounts. They defend against nearby eligible hostile mobs in combat profiles. Stay, passive Debug/CLI/API profiles, offline/dead/spectator owners, unloaded helpers and range/dimension checks pause combat. Player targets retain exact owner and live Codex approvals, PvP/team rules and five-minute expiry. Server actions and code changes also retain both reviews. Answer-only Codex sessions do not share this chat's memory or approve/execute their own proposals. No per-tick AI service requests drive fighting, and no totem bypass is guaranteed.

The fixed Dynu hostname still requires forwarding and an awake reachable host. Vercel hosts the public guide, not the Minecraft world or private dashboard. USD checkout stays off until a Tebex store and tested billing integration exist. Planned monthly prices remain Go $50, Plus $75, Pro $100 and Ultra $200; earned ranks are free and permanent, and Admin/OP are never sold. Passing these checks does not establish that every possible bug is gone.

## Reproduce

```sh
python3 tools/generate_odyssey_assets.py --check
python3 tools/check_crossplay.py --java /path/to/java
python3 -m unittest discover -s tests -p 'test_*.py' -v > research/launcher-tests.log 2>&1
python3 tools/smoke_geyser.py --java /path/to/java > research/geyser-smoke.log 2>&1
python3 tools/smoke_fullstack.py --java /path/to/java > research/fullstack-smoke.log 2>&1
python3 tools/package_crossplay.py
```

Packaging requires fresh passing native, launcher, Geyser and full-stack evidence. Explicit archive lists exclude private worlds, accounts, settings, credentials, panel tokens, EULA acceptance and downloaded runtimes. Live installation separately saves and verifies a stopped-world backup and byte-checks private settings before restarting.
