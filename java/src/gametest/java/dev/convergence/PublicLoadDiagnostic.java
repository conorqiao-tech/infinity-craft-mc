package dev.convergence;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;

/**
 * Synthetic eight-player measurement in a disposable GameTest world. Opt-in only: it is declared in
 * infinity-opt-in-gametests and registered solely by tools/benchmark_public_load.py in a temporary
 * test jar. See server/PUBLIC_LOAD_DIAGNOSTIC.md. A pass means measurement and cleanup integrity only.
 */
public class PublicLoadDiagnostic {
    static final int PLAYERS = 8, SPACING = 5, HOTBAR_EVERY = 40, HEAP_EVERY = 20, DEADLINE_MARGIN = 80, MAX_TICKS = 1600;
    static final String STATUS_MEANING = "passed = the specified synthetic workload ran, was sampled completely and was cleaned up. "
        + "It is not a capacity, TPS, latency or public-readiness result.";
    private static volatile Run active;
    private static boolean listening;

    /** Fabric events cannot be unregistered; these delegate only to the single active run and are inert otherwise. */
    private static synchronized void listen() {
        if (listening) return;
        listening = true;
        ServerTickEvents.START_SERVER_TICK.register(server -> { var run = active; if (run != null && run.server == server) run.tickStarted(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> { var run = active; if (run != null && run.server == server) run.tickEnded(); });
    }

    private static final class Run {
        final GameTestHelper c;
        final MinecraftServer server;
        final int warmup, samples;
        final Path output;
        final PublicLoadMetrics.TickWindows windows;
        final PublicLoadMetrics.Concurrency concurrency = new PublicLoadMetrics.Concurrency();
        final List<NativeSessionFixture.Joined> players = new ArrayList<>();
        final double[] homeX = new double[PLAYERS], homeY = new double[PLAYERS], homeZ = new double[PLAYERS];
        final int[] lastPlayerTick = new int[PLAYERS];
        final boolean[] wasConnected = new boolean[PLAYERS];
        final Map<String, Object> cleanup = new LinkedHashMap<>();
        int step, joinTick, deadlineTick, firstRecordedTick, lastRecordedTick, heapSamples;
        boolean joined, recordedAny, heapStarted, heapEnded;
        // The fixture can become active between START and END of its setup tick.
        boolean awaitingFirstStart = true;
        long wallDeadline, sentMoves, acceptedMoves, hotbarChanges, hotbarSkipped;
        long heapStart, heapEnd, heapMin, heapMax, heapCommittedMax, heapLimit;
        long gcCountStart, gcMillisStart, gcCountEnd, gcMillisEnd;
        String failure;
        boolean closed, written;

        Run(GameTestHelper c, int warmup, int samples, Path output) {
            this.c = c; server = c.getLevel().getServer(); this.warmup = warmup; this.samples = samples; this.output = output;
            windows = new PublicLoadMetrics.TickWindows(System::nanoTime, samples);
        }

        void begin() {
            for (int i = 0; i < PLAYERS; i++) {
                int x = 4 + SPACING * i;
                // Movement strip inside the disposable combat_arena structure only.
                for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 1; dz++) for (int y = 1; y <= 3; y++)
                    c.getLevel().setBlockAndUpdate(c.absolutePos(new BlockPos(x + dx, y, 2 + dz)),
                        (y == 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            }
            joinTick = server.getTickCount();
            for (int i = 0; i < PLAYERS; i++) {
                int x = 4 + SPACING * i;
                var joinedPlayer = new NativeSessionFixture.Joined(c, "load" + i, x);
                players.add(joinedPlayer);
                // This fixture skips the real configuration handshake. Seed the test
                // connection's context explicitly so Polymer can encode chunk data;
                // this does not test or imply real-client authentication or setup.
                var context = joinedPlayer.player.connection.getPacketContext();
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.SERVER_INSTANCE, server);
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.REGISTRY_ACCESS, c.getLevel().registryAccess());
                context.set(net.fabricmc.fabric.impl.networking.context.PacketContextImpl.GAME_PROFILE, joinedPlayer.player.getGameProfile());
                c.assertTrue(context.get(net.fabricmc.fabric.api.networking.v1.context.PacketContext.REGISTRY_ACCESS) != null,
                    "Synthetic play context has explicit registry access");
                var feet = c.absolutePos(new BlockPos(x, 2, 2));
                homeX[i] = feet.getX() + .5; homeY[i] = feet.getY(); homeZ[i] = feet.getZ() + .5;
                lastPlayerTick[i] = players.get(i).player.tickCount; wasConnected[i] = true;
            }
            joined = true;
            c.assertValueEqual(server.getTickCount(), joinTick, "All fake players joined within one server tick");
            c.assertValueEqual(server.getPlayerList().getPlayerCount(), PLAYERS, "Only this diagnostic's fake players are online");
            deadlineTick = joinTick + warmup + samples + DEADLINE_MARGIN;
            wallDeadline = System.nanoTime() + 60_000_000_000L + 250_000_000L * (warmup + samples + DEADLINE_MARGIN);
        }

        boolean connected(int i) {
            var joined = players.get(i); var player = joined.player;
            return server.getPlayerList().getPlayer(player.getUUID()) == player && joined.wire.connection.isConnected() && !player.hasDisconnected();
        }

        void tickStarted() {
            try { windows.start(); awaitingFirstStart = false; } catch (RuntimeException | Error failure) { stop("START_SERVER_TICK sampler: " + describe(failure)); }
        }

        /** The span ends first; concurrency, heap and watchdog checks run after that timestamp. */
        void tickEnded() {
            // No START was observed for the partial tick that activated this run.
            if (awaitingFirstStart) return;
            try {
                boolean recorded = windows.end();
                int connected = 0, ticking = 0;
                for (int i = 0; i < players.size(); i++) {
                    boolean now = connected(i);
                    if (wasConnected[i] && !now) concurrency.disconnects++;
                    wasConnected[i] = now;
                    var player = players.get(i).player;
                    int tick = player.tickCount;
                    if (now) { connected++; if (tick > lastPlayerTick[i]) ticking++; }
                    lastPlayerTick[i] = tick;
                    if (player.containerMenu != player.inventoryMenu) concurrency.menusOpen++;
                }
                if (recorded) {
                    concurrency.observe(connected, ticking, server.getPlayerList().getPlayerCount());
                    if (!recordedAny) { firstRecordedTick = server.getTickCount(); recordedAny = true; }
                    lastRecordedTick = server.getTickCount();
                    if (windows.count % HEAP_EVERY == 1) heap();
                }
                // Detects slow progress only. A tick that never returns is the external runner's timeout.
                if (joined && PublicLoadMetrics.overdue(server.getTickCount(), deadlineTick, System.nanoTime(), wallDeadline))
                    stop("Watchdog: exceeded server tick " + deadlineTick + " or the wall-clock deadline");
            } catch (RuntimeException | Error failure) { stop("END_SERVER_TICK sampler: " + describe(failure)); }
        }

        long heap() {
            var usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long used = usage.getUsed();
            if (heapSamples == 0) { heapMin = used; heapMax = used; heapCommittedMax = usage.getCommitted(); }
            heapSamples++; heapMin = Math.min(heapMin, used); heapMax = Math.max(heapMax, used);
            heapCommittedMax = Math.max(heapCommittedMax, usage.getCommitted()); heapLimit = usage.getMax();
            return used;
        }
        static long[] gc() {
            long count = 0, millis = 0;
            for (var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean.getCollectionCount() > 0) count += bean.getCollectionCount();
                if (bean.getCollectionTime() > 0) millis += bean.getCollectionTime();
            }
            return new long[]{count, millis};
        }

        void schedule() { c.runAfterDelay(1, this::driverStep); }

        void driverStep() {
            try {
                if (failure != null) throw new IllegalStateException(failure);
                step++;
                boolean measured = step > warmup && step <= warmup + samples;
                long started = System.nanoTime();
                for (int i = 0; i < players.size(); i++) drive(i, measured);
                windows.driver(PublicLoadMetrics.elapsed(started, System.nanoTime()), measured);
                if (step == warmup) {
                    heapStart = heap(); heapStarted = true; var gc = gc(); gcCountStart = gc[0]; gcMillisStart = gc[1];
                    windows.recording = true;
                } else if (step == warmup + samples) windows.recording = false;
                // One extra callback lets the last recorded tick reach END_SERVER_TICK first.
                if (step > warmup + samples) finish(); else schedule();
            } catch (RuntimeException | Error failure) {
                stop(describe(failure));
                throw failure;
            }
        }

        /**
         * Packet objects are passed directly to the player's ServerGamePacketListenerImpl on the server
         * thread. No inbound bytes are decoded and no network or Netty inbound path is exercised.
         */
        void drive(int i, boolean measured) {
            var joined = players.get(i); var player = joined.player; joined.drain();
            int phase = step + SPACING * i;
            double x = homeX[i] + PublicLoadMetrics.offset(phase);
            player.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(x, homeY[i], homeZ[i], true, false));
            if (measured) {
                sentMoves++;
                if (Math.abs(player.getX() - x) < 1e-6 && Math.abs(player.getY() - homeY[i]) < 1e-6 && Math.abs(player.getZ() - homeZ[i]) < 1e-6) acceptedMoves++;
            }
            if (phase % HOTBAR_EVERY == 0) {
                // Only empty slots: selecting an owned menu control would open ServerMenu, which this workload excludes.
                var inventory = player.getInventory(); int current = inventory.getSelectedSlot(), next = -1;
                for (int offset = 1; offset < 9 && next < 0; offset++) if (inventory.getItem((current + offset) % 9).isEmpty()) next = (current + offset) % 9;
                if (next >= 0) { player.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(next)); if (measured) hotbarChanges++; }
                else if (measured) hotbarSkipped++;
            }
            // Snapshot 5 rejects a further position packet until the client's tick ends.
            player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
        }

        void finish() {
            if (failure != null) throw new IllegalStateException(failure);
            heapEnd = heap(); heapEnded = true; var gc = gc(); gcCountEnd = gc[0]; gcMillisEnd = gc[1];
            var problems = new ArrayList<>(PublicLoadMetrics.violations(PLAYERS, samples, PublicLoadMetrics.PACED_NANOS, windows, concurrency, sentMoves, acceptedMoves));
            if (!recordedAny || lastRecordedTick - firstRecordedTick + 1 != samples)
                problems.add("Recorded server ticks are not " + samples + " consecutive ticks");
            var result = result();
            close();
            problems.addAll(verifyCleanup());
            result.put("cleanup", cleanup);
            if (!problems.isEmpty()) {
                failure = String.join("; ", problems);
                write("failed", result);
                throw new IllegalStateException("Public-load diagnostic failed: " + failure);
            }
            // The receipt is durable and closed before anything reports success.
            write("passed", result);
            System.out.println("INFINITY_PUBLIC_LOAD_RESULT passed " + output);
            c.succeed();
        }

        Map<String, Object> result() {
            var r = new LinkedHashMap<String, Object>();
            r.put("schema", 2);
            r.put("status_meaning", STATUS_MEANING);
            r.put("scope", "Synthetic local measurement only. In-process fake players with direct packet-handler calls, not real-client sockets or inbound decoding; "
                + "authentication, encryption, compression, Geyser/Bedrock/iPad clients or real users. Does not establish 32-player capability, "
                + "production TPS, latency or public readiness.");
            var workload = new LinkedHashMap<String, Object>();
            workload.put("fake_players", PLAYERS);
            workload.put("connection", "NativePlayConnection: Netty EmbeddedChannel with native play codecs for outbound packets, registered in the server connection list so ServerGamePacketListenerImpl ticks each player; the skipped configuration handshake is replaced by an explicitly seeded test-only packet context");
            workload.put("inbound", "Direct ServerGamePacketListenerImpl handler invocation on the server thread; not inbound packet decoding or real network");
            workload.put("world", "Disposable GameTest world, structure convergence_tests:combat_arena; 5x3 stone strip per player built inside the structure");
            workload.put("joined_in_one_tick", joinTick);
            workload.put("movement", "handleMovePlayer(Pos) once per player per tick: triangle wave along x, amplitude 1.5 blocks, 0.15 blocks/tick, 40-tick period, phase offset 5 ticks per player");
            workload.put("client_tick_end", "handleClientTickEnd once per player per tick, after that player's inputs");
            workload.put("hotbar", "handleSetCarriedItem to the next empty slot every " + HOTBAR_EVERY + " ticks per player; skipped if no empty slot");
            workload.put("client_acknowledgements", "keep-alive, teleport confirmation, chunk batch received (NativeSessionFixture.Joined)");
            workload.put("excluded", "Arm swing/punch, block placing/breaking, chat, commands, combat, inventories/menus, helpers, mode transfers, dimension changes, external services");
            workload.put("player_state", "No gravity, invulnerable, empty inventory unless production join logic adds items");
            r.put("workload", workload);
            var limits = new LinkedHashMap<String, Object>();
            limits.put("warmup_ticks", warmup); limits.put("sample_ticks", samples);
            limits.put("watchdog_server_tick", deadlineTick); limits.put("watchdog_wall_seconds", 60 + .25 * (warmup + samples + DEADLINE_MARGIN));
            limits.put("watchdog_note", "Runs on the server thread; it cannot detect a blocked tick. The external runner's subprocess timeout covers that.");
            limits.put("gametest_max_ticks", MAX_TICKS);
            r.put("limits", limits);
            var sampling = new LinkedHashMap<String, Object>();
            sampling.put("clock", "System.nanoTime differences (monotonic, wrap-safe), server thread");
            sampling.put("start_to_end_callback_span", "From this test mod's Fabric START_SERVER_TICK listener to its END_SERVER_TICK listener on each measured tick. "
                + "This is not the whole server tick: earlier-registered START listeners, the scheduler wait, the pacing sleep and any work outside "
                + "MinecraftServer.tickServer are excluded. It includes the synthetic driver when GameTest callbacks run inside it; see driver_* fields.");
            sampling.put("pacing", "Test-only GameTestPacingMixin (-Dinfinity.gametest.paced=true) parks the server thread for 50 ms after waitUntilNextTick returns, "
                + "i.e. after each tick's work. It is not production's deadline-based 50 ms schedule. A START-to-START period >= 50 ms only shows the sleep ran; "
                + "it is a lower bound and not evidence of adequate TPS.");
            sampling.put("concurrency", "After the END timestamp on every measured tick: connected = in player list, connection open, not disconnected; active = connected and ServerPlayer.tickCount advanced since the previous tick");
            sampling.put("heap", "MemoryMXBean heap usage after the END timestamp every " + HEAP_EVERY + " measured ticks plus start/end; point-in-time, GC dependent, not retained-size or leak evidence");
            r.put("sampling", sampling);
            var m = new LinkedHashMap<String, Object>();
            var ticks = new LinkedHashMap<String, Object>();
            ticks.put("first", recordedAny ? firstRecordedTick : null); ticks.put("last", recordedAny ? lastRecordedTick : null); ticks.put("recorded", windows.count);
            m.put("server_ticks", ticks);
            m.put("start_to_end_callback_span_nanos", windows.count > 0 ? summary(windows.work, windows.count) : null);
            m.put("callback_spans_above_50ms", windows.count > 0 ? PublicLoadMetrics.countAbove(windows.work, windows.count, PublicLoadMetrics.PACED_NANOS) : null);
            m.put("start_to_start_period_nanos", windows.count > 0 ? summary(windows.period, windows.count) : null);
            m.put("driver_nanos_inside_span", windows.count > 0 ? summary(windows.driver, windows.count) : null);
            m.put("driver_steps_outside_span", windows.driverOutside);
            Object net = null;
            if (windows.count > 0 && windows.driverOutside == 0) {
                var values = new long[windows.count]; boolean valid = true;
                for (int i = 0; i < values.length; i++) { values[i] = windows.work[i] - windows.driver[i]; valid &= values[i] >= 0; }
                if (valid) net = summary(values, values.length);
            }
            m.put("callback_span_minus_driver_nanos", net);
            m.put("sampler_fault", windows.fault);
            var k = new LinkedHashMap<String, Object>();
            boolean seen = concurrency.samples > 0;
            k.put("expected", PLAYERS); k.put("samples", concurrency.samples);
            k.put("connected_min", seen ? concurrency.minConnected : null); k.put("connected_max", seen ? concurrency.maxConnected : null);
            k.put("active_min", seen ? concurrency.minActive : null); k.put("active_max", seen ? concurrency.maxActive : null);
            k.put("server_players_min", seen ? concurrency.minServerPlayers : null); k.put("server_players_max", seen ? concurrency.maxServerPlayers : null);
            k.put("disconnects_since_join", concurrency.disconnects); k.put("menu_open_player_ticks", concurrency.menusOpen);
            m.put("concurrency", k);
            var actions = new LinkedHashMap<String, Object>();
            actions.put("moves_sent", sentMoves); actions.put("moves_accepted", acceptedMoves);
            actions.put("hotbar_changes", hotbarChanges); actions.put("hotbar_skipped", hotbarSkipped);
            m.put("actions", actions);
            var h = new LinkedHashMap<String, Object>();
            h.put("unit", "bytes"); h.put("samples", heapSamples);
            h.put("used_at_start", heapStarted ? heapStart : null); h.put("used_at_end", heapEnded ? heapEnd : null);
            h.put("used_min_sampled", heapSamples > 0 ? heapMin : null); h.put("used_max_sampled", heapSamples > 0 ? heapMax : null);
            h.put("committed_max_sampled", heapSamples > 0 ? heapCommittedMax : null); h.put("max", heapSamples > 0 ? heapLimit : null);
            h.put("gc_collections_during_window", heapStarted && heapEnded ? gcCountEnd - gcCountStart : null);
            h.put("gc_millis_during_window", heapStarted && heapEnded ? gcMillisEnd - gcMillisStart : null);
            m.put("heap", h);
            r.put("measurements", m);
            return r;
        }

        static Map<String, Object> summary(long[] values, int count) {
            var d = PublicLoadMetrics.distribution(values, count);
            var s = new LinkedHashMap<String, Object>();
            s.put("unit", "nanoseconds"); s.put("method", "nearest-rank"); s.put("samples", d.samples());
            s.put("min", d.min()); s.put("p50", d.p50()); s.put("p95", d.p95()); s.put("p99", d.p99()); s.put("max", d.max()); s.put("mean", d.mean());
            return s;
        }

        /** Idempotent; deactivates the static sampler before releasing any fixture. */
        void close() {
            if (closed) return;
            closed = true;
            if (active == this) active = null;
            windows.recording = false;
            var actions = new ArrayList<Runnable>();
            for (var joined : players) actions.add(joined::close);
            try { PublicLoadMetrics.runAll(actions); }
            catch (RuntimeException | Error problem) { cleanup.put("close_error", describe(problem)); }
            cleanup.put("players_closed", players.size());
            cleanup.put("sampler_cleared", active != this);
        }

        List<String> verifyCleanup() {
            var problems = new ArrayList<String>();
            int listed = 0, registered = 0, open = 0;
            for (var joined : players) {
                if (server.getPlayerList().getPlayer(joined.player.getUUID()) != null) listed++;
                if (server.getConnection().getConnections().contains(joined.wire.connection)) registered++;
                if (joined.wire.channel.isOpen()) open++;
            }
            cleanup.put("players_still_listed", listed); cleanup.put("connections_still_registered", registered); cleanup.put("channels_still_open", open);
            if (listed + registered + open > 0) problems.add("Cleanup left " + listed + " listed players, " + registered + " registered connections, " + open + " open channels");
            if (active == this) problems.add("Static tick sampler is still active");
            if (cleanup.containsKey("close_error")) problems.add("Fixture close failed: " + cleanup.get("close_error"));
            return problems;
        }

        /** First failure wins; cleanup always runs and a failed receipt is attempted once nothing was written. */
        void stop(String reason) {
            if (failure == null) failure = reason;
            Map<String, Object> result;
            try { result = result(); } catch (RuntimeException | Error problem) { result = new LinkedHashMap<>(); result.put("result_error", describe(problem)); }
            if (!closed) try { close(); verifyCleanup(); } catch (RuntimeException | Error problem) { cleanup.put("cleanup_error", describe(problem)); }
            result.put("cleanup", cleanup);
            if (written) return;
            try { write("failed", result); }
            catch (RuntimeException | Error problem) { System.out.println("INFINITY_PUBLIC_LOAD_RESULT failed; receipt not written: " + describe(problem)); }
        }

        /**
         * Writes a temporary sibling, forces and closes it, then creates the final link only if absent.
         * Unsupported filesystems or a pre-existing target fail closed.
         */
        void write(String status, Map<String, Object> result) {
            if (written) throw new IllegalStateException("Receipt already written");
            var ordered = new LinkedHashMap<String, Object>();
            ordered.put("status", status); ordered.put("failure", failure); ordered.putAll(result);
            var bytes = (CommunityServer.GSON.toJson(ordered) + "\n").getBytes(StandardCharsets.UTF_8);
            try {
                writeCompleteNew(output, bytes, () -> {});
                written = true;
            } catch (Exception problem) {
                throw new IllegalStateException("Could not write public-load result " + output, problem);
            }
        }
    }

    /** A same-directory hard link fails if the target appears, including after the force. */
    static void writeCompleteNew(Path output, byte[] bytes, Runnable beforeLink) throws java.io.IOException {
        if (Files.exists(output)) throw new IllegalStateException("Refusing to replace existing result " + output);
        Path temporary = Files.createTempFile(output.getParent(), ".public-load-", ".partial");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            beforeLink.run();
            Files.createLink(output, temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String describe(Throwable failure) { return failure.getClass().getSimpleName() + ": " + failure.getMessage(); }

    /** Requires the paced runner, a fresh absolute result path and bounded sizes before touching the world. */
    @GameTest(structure = "convergence_tests:combat_arena", maxTicks = MAX_TICKS)
    public void eightNativeFakePlayersPacedSyntheticBaseline(GameTestHelper c) {
        c.assertTrue(Boolean.getBoolean("infinity.gametest.paced"), "Run with -Dinfinity.gametest.paced=true; the test-only pacing sleep is required");
        int warmup = Integer.getInteger("infinity.public-load.warmup", 100), samples = Integer.getInteger("infinity.public-load.samples", 600);
        c.assertTrue(warmup >= 20 && warmup <= 200 && samples >= 100 && samples <= 1200, "Warmup 20..200 and samples 100..1200 keep the run inside its bounds");
        var property = System.getProperty("infinity.public-load.output");
        c.assertTrue(property != null && !property.isBlank(), "Set -Dinfinity.public-load.output to a new absolute result path");
        var output = Path.of(property);
        c.assertTrue(output.isAbsolute() && output.getParent() != null && Files.isDirectory(output.getParent()) && !Files.exists(output),
            "The result path must be absolute, in an existing folder and not yet exist");
        c.assertTrue(active == null, "Only one public-load diagnostic may run at a time");
        listen();
        var run = new Run(c, warmup, samples, output);
        active = run;
        try { run.begin(); }
        catch (RuntimeException | Error failure) { run.stop("Setup: " + describe(failure)); throw failure; }
        run.schedule();
    }

    @GameTest(maxTicks = 20)
    public void publicLoadMetricsAndFailureHandlingSelfCheck(GameTestHelper c) throws Exception {
        PublicLoadMetrics.selfCheck();
        var parent = Path.of(System.getProperty("infinity.public-load.output")).getParent();
        var dir = Files.createTempDirectory(parent, ".receipt-selfcheck-");
        var target = dir.resolve("result.json");
        try {
            byte[] old = "existing".getBytes(StandardCharsets.UTF_8), replacement = "replacement".getBytes(StandardCharsets.UTF_8);
            boolean refused = false;
            try {
                // Simulate another writer placing a file after our bytes were forced.
                writeCompleteNew(target, replacement, () -> {
                    try { Files.write(target, old, StandardOpenOption.CREATE_NEW); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                });
            } catch (java.nio.file.FileAlreadyExistsException expected) { refused = true; }
            c.assertTrue(refused && java.util.Arrays.equals(Files.readAllBytes(target), old),
                "A target created after force is preserved rather than overwritten");
            Files.delete(target);
            writeCompleteNew(target, replacement, () -> {});
            c.assertTrue(java.util.Arrays.equals(Files.readAllBytes(target), replacement), "A complete result is linked when the target is absent");
            try (var entries = Files.list(dir)) { c.assertValueEqual(entries.count(), 1L, "No partial receipt remains"); }
        } finally {
            Files.deleteIfExists(target); Files.deleteIfExists(dir);
        }
        c.succeed();
    }
}
