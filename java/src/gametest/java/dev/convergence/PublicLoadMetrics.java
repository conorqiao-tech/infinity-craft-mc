package dev.convergence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;

/** Pure calculations for the opt-in public-load diagnostic; no world, server or static state. */
final class PublicLoadMetrics {
    private PublicLoadMetrics() {}

    static final long PACED_NANOS = 50_000_000L;

    /** Nearest-rank percentiles, matching tools/benchmark_helpers.py distribution(). */
    record Distribution(int samples, long min, long p50, long p95, long p99, long max, double mean) {}

    static Distribution distribution(long[] values, int count) {
        if (count <= 0 || count > values.length) throw new IllegalArgumentException("Distribution needs 1.." + values.length + " samples, got " + count);
        var sorted = Arrays.copyOf(values, count);
        double total = 0;
        for (long value : sorted) {
            if (value < 0) throw new IllegalArgumentException("Negative duration sample " + value);
            total += value;
        }
        Arrays.sort(sorted);
        return new Distribution(count, sorted[0], rank(sorted, .5), rank(sorted, .95), rank(sorted, .99), sorted[count - 1], total / count);
    }

    static long rank(long[] sorted, double fraction) {
        return sorted[Math.min(sorted.length - 1, Math.max(0, (int)Math.ceil(sorted.length * fraction) - 1))];
    }

    /**
     * System.nanoTime values may wrap; only differences are meaningful. Two's-complement
     * subtraction is exact for any true interval under 2^63 ns. A negative difference means
     * the clock was not monotonic for this pair and is rejected rather than clamped.
     */
    static long elapsed(long from, long to) {
        long difference = to - from;
        if (difference < 0) throw new IllegalArgumentException("Monotonic clock went backwards: " + from + " -> " + to);
        return difference;
    }

    static int countAbove(long[] values, int count, long threshold) {
        int above = 0;
        for (int i = 0; i < count; i++) if (values[i] > threshold) above++;
        return above;
    }

    /** Bounded triangle wave along one axis: amplitude 1.5 blocks, 0.15 blocks per tick, 40-tick period. */
    static double offset(int tick) {
        int u = Math.floorMod(tick, 40);
        return u < 20 ? -1.5 + .15 * u : 1.5 - .15 * (u - 20);
    }

    /** Pairs START/END tick callbacks on one thread; any ordering or clock problem is a recorded fault, never a guess. */
    static final class TickWindows {
        final LongSupplier clock;
        final long[] work, driver, period;
        int count, driverOutside;
        boolean recording;
        String fault;
        private boolean open, recordingThisTick, hasPreviousStart;
        private long started, previousStart, pendingPeriod, driverAccum;

        TickWindows(LongSupplier clock, int capacity) {
            this.clock = clock; work = new long[capacity]; driver = new long[capacity]; period = new long[capacity];
        }
        void start() {
            if (fault != null) return;
            long now = clock.getAsLong();
            if (open) { fault = "START_SERVER_TICK repeated before END_SERVER_TICK"; return; }
            recordingThisTick = recording;
            if (recordingThisTick && !hasPreviousStart) { fault = "Recorded tick has no preceding START for its period"; return; }
            try { pendingPeriod = hasPreviousStart ? elapsed(previousStart, now) : 0; }
            catch (IllegalArgumentException backwards) { fault = backwards.getMessage(); return; }
            previousStart = now; hasPreviousStart = true; started = now; open = true; driverAccum = 0;
        }
        /** Returns true when this END completed a recorded tick. */
        boolean end() {
            if (fault != null) return false;
            long now = clock.getAsLong();
            if (!open) { fault = "END_SERVER_TICK without a matching START"; return false; }
            open = false;
            long span;
            try { span = elapsed(started, now); }
            catch (IllegalArgumentException backwards) { fault = backwards.getMessage(); return false; }
            if (!recordingThisTick) return false;
            if (count == work.length) { fault = "More recorded ticks than the bounded capacity " + work.length; return false; }
            work[count] = span; driver[count] = driverAccum; period[count] = pendingPeriod; count++;
            return true;
        }
        void driver(long nanos, boolean measured) {
            if (nanos < 0) { fault = "Negative driver duration " + nanos; return; }
            if (open) driverAccum += nanos;
            else if (measured) driverOutside++;
        }
    }

    /** Per-sample concurrency extremes over the measured ticks; extremes are meaningful only when samples > 0. */
    static final class Concurrency {
        int samples, disconnects, menusOpen;
        int minConnected, maxConnected, minActive, maxActive, minServerPlayers, maxServerPlayers;
        void observe(int connected, int active, int serverPlayers) {
            if (samples == 0) { minConnected = connected; minActive = active; minServerPlayers = serverPlayers; }
            samples++;
            minConnected = Math.min(minConnected, connected); maxConnected = Math.max(maxConnected, connected);
            minActive = Math.min(minActive, active); maxActive = Math.max(maxActive, active);
            minServerPlayers = Math.min(minServerPlayers, serverPlayers); maxServerPlayers = Math.max(maxServerPlayers, serverPlayers);
        }
    }

    /**
     * Every integrity condition required before numbers are reported. Passing means the
     * synthetic run happened as specified and was sampled completely; it is not a capacity result.
     */
    static List<String> violations(int players, int samples, long minPeriodNanos, TickWindows windows,
                                   Concurrency concurrency, long sentMoves, long acceptedMoves) {
        var result = new ArrayList<String>();
        if (windows.fault != null) result.add("Tick sampling fault: " + windows.fault);
        if (windows.count != samples) result.add("Recorded " + windows.count + " tick windows, expected " + samples);
        if (concurrency.samples != samples) result.add("Recorded " + concurrency.samples + " concurrency samples, expected " + samples);
        if (concurrency.samples > 0) {
            if (concurrency.minConnected != players || concurrency.maxConnected != players)
                result.add("Connected fake players ranged " + concurrency.minConnected + ".." + concurrency.maxConnected + ", expected " + players);
            if (concurrency.minActive != players || concurrency.maxActive != players)
                result.add("Actively ticking fake players ranged " + concurrency.minActive + ".." + concurrency.maxActive + ", expected " + players);
            if (concurrency.minServerPlayers != players || concurrency.maxServerPlayers != players)
                result.add("Server player list ranged " + concurrency.minServerPlayers + ".." + concurrency.maxServerPlayers + ", expected exactly " + players);
        }
        if (concurrency.disconnects != 0) result.add(concurrency.disconnects + " fake player disconnects since join");
        if (concurrency.menusOpen != 0) result.add(concurrency.menusOpen + " player-ticks had a container menu open; the declared workload excludes menus");
        for (int i = 0; i < windows.count; i++) {
            if (windows.work[i] <= 0) { result.add("Non-positive callback span at sample " + i); break; }
            // A lower bound only proves the test-only pacing sleep ran; it says nothing about meeting 20 TPS.
            if (windows.period[i] < minPeriodNanos) { result.add("Tick period " + windows.period[i] + " ns at sample " + i + " is below the test pacing sleep " + minPeriodNanos + " ns"); break; }
        }
        if (sentMoves != (long)players * samples) result.add("Sent " + sentMoves + " measured moves, expected " + (long)players * samples);
        if (acceptedMoves != sentMoves) result.add("Server accepted " + acceptedMoves + " of " + sentMoves + " measured moves");
        return result;
    }

    /** Wrap-safe deadline comparison. Runs on the server thread, so it cannot detect a tick that never ends. */
    static boolean overdue(int serverTick, int deadlineTick, long nowNanos, long wallDeadlineNanos) {
        return serverTick > deadlineTick || nowNanos - wallDeadlineNanos > 0;
    }

    /** Runs every action; the first failure is thrown with later ones suppressed. */
    static void runAll(List<Runnable> actions) {
        Throwable problem = null;
        for (var action : actions) try { action.run(); } catch (RuntimeException | Error failure) {
            if (problem == null) problem = failure; else problem.addSuppressed(failure);
        }
        if (problem instanceof RuntimeException failure) throw failure;
        if (problem instanceof Error failure) throw failure;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException("Self-check failed: " + message); }
    private static boolean throwsIllegal(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    private static boolean mentions(List<String> list, String fragment) { return list.stream().anyMatch(v -> v.contains(fragment)); }

    /** Exercises calculations and failure detection with synthetic inputs only. */
    static void selfCheck() {
        var hundred = new long[100];
        for (int i = 0; i < 100; i++) hundred[i] = 100 - i;
        var before = hundred.clone();
        var d = distribution(hundred, 100);
        check(d.samples() == 100 && d.min() == 1 && d.p50() == 50 && d.p95() == 95 && d.p99() == 99 && d.max() == 100, "nearest-rank percentiles of 1..100: " + d);
        check(d.mean() == 50.5, "mean of 1..100");
        check(Arrays.equals(before, hundred), "distribution must not reorder its input");
        var one = distribution(new long[]{7}, 1);
        check(one.p50() == 7 && one.p99() == 7 && one.max() == 7, "single sample");
        var prefix = distribution(new long[]{3, 1, 2, 1_000_000}, 3);
        check(prefix.max() == 3 && prefix.samples() == 3, "only the recorded prefix is summarized");
        var ten = distribution(new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 10);
        check(ten.p50() == 5 && ten.p95() == 10 && ten.p99() == 10, "nearest-rank rounds up for small samples");
        check(throwsIllegal(() -> distribution(new long[0], 0)), "empty samples are rejected");
        check(throwsIllegal(() -> distribution(new long[]{1, -1}, 2)), "negative samples are rejected");
        check(throwsIllegal(() -> distribution(new long[]{1}, 2)), "count beyond recorded samples is rejected");
        check(countAbove(new long[]{PACED_NANOS, PACED_NANOS + 1, 1, 99}, 3, PACED_NANOS) == 1, "spans above 50 ms count only recorded samples");

        check(elapsed(Long.MAX_VALUE - 5, Long.MIN_VALUE + 4) == 10, "elapsed time is exact across nanoTime wraparound");
        check(elapsed(-20, -5) == 15 && elapsed(42, 42) == 0, "negative nanoTime readings are ordinary values, not sentinels");
        check(throwsIllegal(() -> elapsed(10, 5)), "a backwards clock is rejected");
        check(throwsIllegal(() -> elapsed(Long.MIN_VALUE + 4, Long.MAX_VALUE - 5)), "a backwards jump across wraparound is rejected");

        for (int t = -80; t < 200; t++) {
            check(Math.abs(offset(t)) <= 1.5 + 1e-9, "movement stays within 1.5 blocks");
            check(Math.abs(offset(t + 1) - offset(t)) <= .15 + 1e-9, "movement step stays within 0.15 blocks per tick");
            check(offset(t) == offset(t + 40), "movement repeats every 40 ticks");
        }
        check(offset(0) == -1.5 && Math.abs(offset(20) - 1.5) < 1e-9, "movement endpoints");

        long[] now = {0};
        var w = new TickWindows(() -> now[0], 2);
        w.start(); now[0] = 10; w.driver(4, false); check(!w.end(), "warmup tick is not recorded");
        w.recording = true; now[0] = 60; w.start(); now[0] = 70; w.driver(5, true); now[0] = 75;
        check(w.end() && w.count == 1 && w.work[0] == 15 && w.period[0] == 60 && w.driver[0] == 5, "recorded tick span, period and driver time");
        w.driver(3, true);
        check(w.driverOutside == 1, "measured driver work outside a tick window is flagged");
        now[0] = 120; w.start(); now[0] = 130; check(w.end() && w.count == 2, "second recorded tick");
        now[0] = 180; w.start(); now[0] = 190;
        check(!w.end() && w.fault != null && w.fault.contains("capacity"), "overflow is a fault, not a dropped sample");
        var unpaired = new TickWindows(() -> now[0], 4);
        check(!unpaired.end() && unpaired.fault != null, "END without START is a fault");
        unpaired.recording = true; unpaired.start(); unpaired.end();
        check(unpaired.count == 0 && unpaired.fault.contains("without a matching START"), "a faulted sampler records nothing more");
        var doubled = new TickWindows(() -> now[0], 4);
        doubled.start(); doubled.start(); check(doubled.fault != null, "repeated START is a fault");
        var cold = new TickWindows(() -> now[0], 4);
        cold.recording = true; cold.start(); check(cold.fault != null, "a first recorded tick without a period is a fault");

        long[] wrapping = {Long.MAX_VALUE - 60_000_000L};
        var wrapped = new TickWindows(() -> wrapping[0], 1);
        wrapped.start(); wrapping[0] += 1_000_000; wrapped.end(); wrapped.recording = true;
        wrapping[0] += 55_000_000; wrapped.start(); wrapping[0] += 7_000_000;
        check(wrapping[0] < 0 && wrapped.end() && wrapped.work[0] == 7_000_000 && wrapped.period[0] == 56_000_000,
            "callback spans and periods are exact when nanoTime wraps negative");
        long[] backwards = {1_000};
        var reversed = new TickWindows(() -> backwards[0], 1);
        reversed.start(); backwards[0] = 900;
        check(!reversed.end() && reversed.fault != null && reversed.fault.contains("backwards"), "a backwards END is a fault, not a negative sample");
        var reversedStart = new TickWindows(() -> backwards[0], 1);
        backwards[0] = 2_000; reversedStart.start(); reversedStart.end(); backwards[0] = 1_500; reversedStart.start();
        check(reversedStart.fault != null && reversedStart.fault.contains("backwards"), "a backwards period is a fault");
        var negativeDriver = new TickWindows(() -> 0, 1);
        negativeDriver.driver(-1, true); check(negativeDriver.fault != null, "a negative driver duration is a fault");

        long[] clock = {0};
        var good = new TickWindows(() -> clock[0], 2);
        good.start(); clock[0] += 1;
        good.end(); good.recording = true;
        var k = new Concurrency();
        for (int i = 0; i < 2; i++) { clock[0] += PACED_NANOS; good.start(); clock[0] += 2_000_000; good.end(); k.observe(8, 8, 8); }
        check(violations(8, 2, PACED_NANOS, good, k, 16, 16).isEmpty(), "a complete paced run passes: " + violations(8, 2, PACED_NANOS, good, k, 16, 16));
        check(mentions(violations(8, 3, PACED_NANOS, good, k, 24, 24), "Recorded 2 tick windows"), "missing samples fail");
        check(mentions(violations(8, 2, 60_000_000, good, k, 16, 16), "below the test pacing"), "unpaced ticks fail");
        check(mentions(violations(8, 2, PACED_NANOS, good, k, 16, 15), "accepted 15 of 16"), "rejected movement fails");
        check(mentions(violations(8, 2, PACED_NANOS, good, k, 15, 15), "Sent 15"), "missing movement fails");
        check(mentions(violations(9, 2, PACED_NANOS, good, k, 18, 18), "Connected fake players"), "wrong concurrency fails");
        var inactive = new Concurrency(); inactive.observe(8, 8, 8); inactive.observe(8, 7, 8);
        check(mentions(violations(8, 2, PACED_NANOS, good, inactive, 16, 16), "Actively ticking"), "a stalled player fails");
        var foreign = new Concurrency(); foreign.observe(8, 8, 8); foreign.observe(8, 8, 9);
        check(mentions(violations(8, 2, PACED_NANOS, good, foreign, 16, 16), "Server player list"), "a foreign player fails");
        var dropped = new Concurrency(); dropped.observe(8, 8, 8); dropped.observe(7, 7, 7); dropped.disconnects = 1;
        var droppedViolations = violations(8, 2, PACED_NANOS, good, dropped, 16, 16);
        check(mentions(droppedViolations, "disconnects") && mentions(droppedViolations, "Connected fake players"), "a disconnected fake player fails");
        var menu = new Concurrency(); menu.observe(8, 8, 8); menu.observe(8, 8, 8); menu.menusOpen = 1;
        check(mentions(violations(8, 2, PACED_NANOS, good, menu, 16, 16), "container menu"), "an undeclared open menu fails");
        var empty = new Concurrency();
        check(mentions(violations(8, 2, PACED_NANOS, good, empty, 16, 16), "0 concurrency samples"), "no concurrency evidence fails");
        good.fault = "synthetic";
        check(mentions(violations(8, 2, PACED_NANOS, good, k, 16, 16), "Tick sampling fault"), "a sampler fault fails");

        check(overdue(11, 10, 0, 100) && overdue(0, 10, 101, 100) && !overdue(10, 10, 100, 100), "watchdog bounds");
        check(overdue(0, 10, Long.MIN_VALUE + 1, Long.MAX_VALUE) && !overdue(0, 10, Long.MAX_VALUE, Long.MIN_VALUE + 1),
            "watchdog deadline comparison is wrap-safe");

        int[] ran = {0};
        RuntimeException thrown = null;
        try {
            runAll(List.of(() -> ran[0]++, () -> { ran[0]++; throw new UnsupportedOperationException("first"); },
                () -> ran[0]++, () -> { ran[0]++; throw new UnsupportedOperationException("second"); }));
        } catch (UnsupportedOperationException failure) { thrown = failure; }
        check(thrown != null, "cleanup failures are rethrown");
        check("first".equals(thrown.getMessage()) && thrown.getSuppressed().length == 1 && ran[0] == 4,
            "cleanup runs every action and keeps later failures as suppressed");
    }
}
