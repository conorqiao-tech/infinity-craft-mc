# Synthetic public-load diagnostic (opt-in, disposable)

This is a **synthetic local measurement**. It is not a capacity test. A
`passed` result means only that the specified workload ran, was sampled on every
measured tick, and was cleaned up. It does **not** establish 32-player
capability, production TPS or tick latency, network behavior, authentication,
Geyser/Bedrock/iPad support, real player behavior or public readiness. No
results are recorded here; this tool alone cannot establish a player cap.

`tools/benchmark_capacity.py` compares armor packet counts for two connections
on unpaced ticks. This diagnostic adds eight concurrent fake players on paced
ticks. It still cannot justify any `max-players` setting by itself.

## What runs

- `PublicLoadDiagnostic` (GameTest source) and `PublicLoadMetrics` (pure
  calculations) are listed only in `custom.infinity-opt-in-gametests` in the
  test manifest. They never appear in the regular `fabric-gametest`
  entrypoints, so `tools/check_crossplay.py` and its expected native count are
  unchanged.
- `tools/benchmark_public_load.py` copies the verified cached runtime (no world,
  config or credentials) into a temporary folder. It writes a temporary test jar
  whose only entrypoint is the diagnostic, then launches one bounded GameTest
  server. Afterwards it deletes that folder, including the generated world. It
  refuses any runtime or output path inside `server/`, and any output folder
  that already exists.
- Eight `NativeSessionFixture.Joined` players join in the same server tick
  inside the `convergence_tests:combat_arena` structure. Each one is a Netty
  `EmbeddedChannel` with native outbound play codecs, ticked through the real
  connection list. Because the fixture skips configuration, it explicitly seeds
  the test-only Fabric packet context for Polymer; this does not verify a real
  client's handshake or authentication.
- Each player, on every tick: one `handleMovePlayer(Pos)` along a triangle wave
  (±1.5 blocks, 0.15 blocks/tick, 40-tick period), then
  `handleClientTickEnd`. Every 40 ticks it also selects the next *empty* hotbar
  slot. Keep-alive, teleport and chunk-batch acknowledgements are sent. The
  only world edits are stone strips inside the test structure.
- **Inputs are direct handler calls on the server thread.** The workload does
  not decode inbound bytes or exercise real network clients. Server startup is
  ordinary Minecraft startup and the runner does not enforce network isolation;
  use an independently network-denied environment for an offline experiment.
  Arm swing/punch is omitted from the declared workload.

## What it measures

| Field | Meaning |
|---|---|
| `start_to_end_callback_span_nanos` | `System.nanoTime` from this mod's Fabric `START_SERVER_TICK` listener to its `END_SERVER_TICK` listener. It is **not the whole tick**: earlier START listeners, the scheduler wait, the pacing sleep and any work outside `tickServer` are excluded. Reports p50/p95/p99/max/mean using the nearest-rank method, with the sample count. |
| `driver_nanos_inside_span`, `callback_span_minus_driver_nanos` | The synthetic driver's own cost. The subtracted series is reported only if every driver step ran inside a span. |
| `start_to_start_period_nanos` | Includes the **test-only** pacing: `GameTestPacingMixin` parks the thread for 50 ms *after* each tick's work. This is not production's deadline-based 20 TPS. A period of at least 50 ms only shows the sleep ran; it is a lower bound, not evidence of adequate TPS. |
| `concurrency` | Checked on each measured tick, after the timestamp. *Connected* means the player is listed, its connection is open and it has not disconnected. *Active* means it is connected and its `tickCount` advanced. Also counts disconnects since join and ticks with a menu open. |
| `heap` | `MemoryMXBean` samples every 20 ticks, plus GC count/time deltas. These are point-in-time readings, not leak evidence. |

The receipt also counts behind-schedule, movement-too-fast and packet-delivery
warnings; inspect `server.log` to determine their timing and cause. They are not
proof of capacity or of a fault in a real client.

The run fails, rather than reporting numbers, if any of these occur:
- a sampler ordering fault or a backwards clock;
- a missing or non-consecutive tick;
- a period under 50 ms;
- any count other than 8 connected, active and listed players;
- any disconnect or open menu;
- any rejected movement;
- incomplete cleanup;
- a non-zero exit code, a failed or missing GameTest case, or any `ERROR`/`FATAL`
  log line (an earlier experiment logged a Polymer packet-context error).

The in-game watchdog runs on the server thread, so it cannot catch a tick that
never ends. The runner's subprocess timeout kills such a run and records
`failure.json`. A `receipt.json` is linked into place only after it has been
fully written, flushed and closed, and only after every check has passed. It
never replaces an existing file.

## Running it (Codex, disposable only)

Build the mod and test jars first, then use the same verified cached runtime as
`tools/benchmark_helpers.py`:

```
python3 tools/benchmark_public_load.py --java <java25> \
  --mod java/build/libs/Infinity-Armor-<version>.jar \
  --tests java/build/libs/Infinity-Armor-<version>-gametest.jar \
  [--warmup 100] [--samples 600]
```

Output goes to a new `research/public-load/runs/<ns>/`, which contains:
- `raw.json` (in-game result);
- `report.xml`;
- `server.log`;
- either `receipt.json` or `failure.json`.

Offline checks: `python3 -m unittest tests.test_public_load_benchmark`.

Unverified until Codex builds and runs it: compilation against snapshot 5
(including `handleSetCarriedItem` and `ServerboundSetCarriedItemPacket`), where
the GameTest ticker runs relative to the Fabric tick callbacks, whether Polymer
logs errors for direct handler calls, and all runtime values.
