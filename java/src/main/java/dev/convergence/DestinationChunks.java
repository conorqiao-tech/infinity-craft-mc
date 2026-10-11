package dev.convergence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Server-thread-only, shared FULL-chunk leases for destination preparation. */
final class DestinationChunks {
    private static final int STARTS_PER_TICK = 4, CLEANUP_RETRY_TICKS = 20;
    // Mode/teleport jobs time out after 600 ticks. This nonpersistent ticket
    // remains valid for twice that window, including native generation delays.
    private static final TicketType TICKET = new TicketType(1200,
        TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    private static final Map<MinecraftServer, Pool> POOLS = new IdentityHashMap<>();

    static {
        // TicketType is a (timeout, flags) record, rather than an identity token.
        // Never remove a lease using an equal built-in native ticket type.
        for (var nativeType : List.of(TicketType.PLAYER_SPAWN, TicketType.SPAWN_SEARCH,
            TicketType.DRAGON, TicketType.PLAYER_LOADING, TicketType.PLAYER_SIMULATION,
            TicketType.FORCED, TicketType.PORTAL, TicketType.ENDER_PEARL, TicketType.UNKNOWN))
            if (TICKET.equals(nativeType)) throw new ExceptionInInitializerError("Destination ticket equals a native ticket type");
    }

    private static void onServerThread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Destination preparation requires the server thread");
    }

    static Lease acquire(ServerLevel world, ChunkPos pos) {
        var server = world.getServer(); onServerThread(server);
        var pool = POOLS.computeIfAbsent(server, ignored -> new Pool());
        var chunks = pool.worlds.computeIfAbsent(world, ignored -> new HashMap<>());
        var entry = chunks.get(pos);
        if (entry == null) {
            entry = new Entry(pool, world, new ChunkPos(pos.x(), pos.z()));
            chunks.put(entry.pos, entry);
            entry.references = 1;
            if (world.getChunkSource().getChunkNow(pos.x(), pos.z()) != null) holdLoaded(entry);
            else pool.queued.addLast(entry);
        } else {
            if (entry.failure != null) return new Lease(entry, entry.failure, false);
            if (entry.ticketed) try {
                // The native duplicate insertion resets ticksLeft. Overlapping
                // short jobs can share one entry for longer than its timeout.
                world.getChunkSource().addTicketWithRadius(TICKET, entry.pos, 0);
            } catch (RuntimeException failure) {
                // A rejected renewal cannot poison older users of the already
                // retained ticket, or claim a reference for the new caller.
                return new Lease(entry, describe(entry, "could not renew the retained chunk: " + failure), false);
            }
            entry.references++;
        }
        return new Lease(entry);
    }

    static final class Lease implements AutoCloseable {
        private final Entry entry;
        private final String acquisitionFailure;
        private final boolean ownsReference;
        private boolean closed;
        private Lease(Entry entry) { this(entry, null, true); }
        private Lease(Entry entry, String acquisitionFailure, boolean ownsReference) {
            this.entry = entry; this.acquisitionFailure = acquisitionFailure; this.ownsReference = ownsReference;
        }
        boolean ready() {
            onServerThread(entry.world.getServer());
            if (closed || entry.pool.cleared || acquisitionFailure != null) return false;
            settle(entry);
            return entry.failure == null && entry.ready
                && entry.world.getChunkSource().getChunkNow(entry.pos.x(), entry.pos.z()) != null;
        }
        String failure() {
            onServerThread(entry.world.getServer());
            if (closed || entry.pool.cleared) return "Destination preparation is closed";
            if (acquisitionFailure != null) return acquisitionFailure;
            settle(entry); return entry.failure;
        }
        @Override public void close() {
            onServerThread(entry.world.getServer());
            if (closed) return;
            closed = true;
            if (!ownsReference || entry.pool.cleared || --entry.references > 0) return;
            entry.pool.active.remove(entry);
            entry.future = null; // Native futures are shared generation work; never cancel them.
            if (releaseTicket(entry)) forget(entry);
            else { entry.retiring = true; entry.pool.retiring.add(entry); }
        }
    }

    private static final class Pool {
        final Map<ServerLevel, Map<ChunkPos, Entry>> worlds = new IdentityHashMap<>();
        final ArrayDeque<Entry> queued = new ArrayDeque<>();
        final List<Entry> active = new ArrayList<>(), retiring = new ArrayList<>();
        int startsTick = Integer.MIN_VALUE, starts;
        boolean cleared;
    }
    private static final class Entry {
        final Pool pool;
        final ServerLevel world;
        final ChunkPos pos;
        int references, lastCleanupTick = Integer.MIN_VALUE;
        CompletableFuture<?> future;
        String failure;
        boolean ticketed, ready, nativeCompleted, retiring, cleanupReported;
        Entry(Pool pool, ServerLevel world, ChunkPos pos) { this.pool = pool; this.world = world; this.pos = pos; }
    }

    private static String describe(Entry entry, String message) {
        return "Destination chunk " + entry.world.dimension().identifier() + " " + entry.pos + ": " + message;
    }
    private static void fail(Entry entry, String message) { entry.failure = describe(entry, message); entry.ready = false; }

    private static void holdLoaded(Entry entry) {
        try {
            // Set this before the native call so partial failures still attempt cleanup.
            entry.ticketed = true;
            entry.world.getChunkSource().addTicketWithRadius(TICKET, entry.pos, 0);
            entry.ready = true;
        } catch (RuntimeException failure) { fail(entry, "could not retain the loaded chunk: " + failure); }
    }

    private static void start(Entry entry) {
        try {
            entry.ticketed = true;
            // Unlike getChunkFuture on the server thread, this native API does
            // not managedBlock or join. Radius zero requests this FULL center.
            entry.future = entry.world.getChunkSource().addTicketAndLoadWithRadius(TICKET, entry.pos, 0);
            entry.pool.active.add(entry); settle(entry);
        } catch (RuntimeException failure) { fail(entry, "could not start loading: " + failure); }
    }

    private static void settle(Entry entry) {
        if (entry.failure != null || entry.ready || entry.future == null) return;
        try {
            if (!entry.nativeCompleted) {
                if (!entry.future.isDone()) return;
                var result = entry.future.getNow(null);
                if (!(result instanceof ChunkResult<?> chunks)) { fail(entry, "native load returned no chunk result"); return; }
                if (!chunks.isSuccess()) { fail(entry, "native load failed: " + chunks.getError()); return; }
                entry.nativeCompleted = true;
            }
            // A completed range future must also publish an actual FULL chunk
            // before callers may read blocks, heightmaps or collision shapes.
            if (entry.world.getChunkSource().getChunkNow(entry.pos.x(), entry.pos.z()) != null) {
                entry.ready = true; entry.future = null;
            }
        } catch (RuntimeException failure) { fail(entry, "loading failed: " + failure); }
    }

    static void tick(MinecraftServer server) {
        onServerThread(server); var pool = POOLS.get(server); if (pool == null) return;
        int tick = server.getTickCount();
        if (pool.startsTick != tick) { pool.startsTick = tick; pool.starts = 0; }
        var active = pool.active.iterator();
        while (active.hasNext()) {
            var entry = active.next(); settle(entry);
            if (entry.references == 0 || entry.ready || entry.failure != null) active.remove();
        }
        while (pool.starts < STARTS_PER_TICK && !pool.queued.isEmpty()) {
            var entry = pool.queued.removeFirst();
            if (entry.references == 0) continue;
            if (entry.world.getChunkSource().getChunkNow(entry.pos.x(), entry.pos.z()) != null) holdLoaded(entry);
            else { pool.starts++; start(entry); }
        }
        for (int i = pool.retiring.size() - 1; i >= 0; i--) {
            var entry = pool.retiring.get(i);
            if (entry.lastCleanupTick != Integer.MIN_VALUE && tick >= entry.lastCleanupTick
                && tick - entry.lastCleanupTick < CLEANUP_RETRY_TICKS) continue;
            if (releaseTicket(entry)) { pool.retiring.remove(i); entry.retiring = false; forget(entry); }
        }
    }

    private static boolean releaseTicket(Entry entry) {
        if (!entry.ticketed) return true;
        entry.lastCleanupTick = entry.world.getServer().getTickCount();
        try {
            entry.world.getChunkSource().removeTicketWithRadius(TICKET, entry.pos, 0);
            entry.ticketed = false; return true;
        } catch (RuntimeException failure) {
            if (!entry.cleanupReported) {
                entry.cleanupReported = true;
                System.err.println("[Infinity] " + describe(entry, "ticket cleanup failed: " + failure));
            }
            if (entry.failure == null) fail(entry, "ticket cleanup failed");
            return false;
        }
    }

    private static void forget(Entry entry) {
        var chunks = entry.pool.worlds.get(entry.world);
        if (chunks == null) return;
        chunks.remove(entry.pos, entry);
        if (chunks.isEmpty()) entry.pool.worlds.remove(entry.world);
    }

    static void clear(MinecraftServer server) {
        onServerThread(server); var pool = POOLS.remove(server); if (pool == null) return;
        pool.cleared = true;
        for (var chunks : pool.worlds.values()) for (var entry : chunks.values()) {
            entry.references = 0; entry.future = null; releaseTicket(entry);
        }
        pool.worlds.clear(); pool.queued.clear(); pool.active.clear(); pool.retiring.clear();
    }
}
