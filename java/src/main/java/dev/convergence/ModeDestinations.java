package dev.convergence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;

/** Ordered, server-thread-polled destinations, without synchronous chunk loading. */
final class ModeDestinations implements AutoCloseable {
    enum Status { PENDING, READY, FAILED }
    private enum Phase { SAVED, FALLBACK, CACHE, SEARCH, DONE }
    private enum Kind { SAVED, FIXED, CACHED, COLUMN }
    private static final int COLUMNS_PER_POLL = 64;
    private static final double COLLISION_EPSILON = 1.0E-7;

    private final MinecraftServer server;
    private final GameModes.Mode mode;
    private final String map;
    private final CommunityServer.Place savedPlace, cachedDefault;
    // The 1,089 origin columns plus the native collision halo occupy at most
    // 100 chunks. A saved profile outside that area adds at most four more.
    // Each acquired chunk stays retained until this selector is closed.
    private final Map<ChunkKey, DestinationChunks.Lease> leases = new LinkedHashMap<>();
    private Phase phase;
    private int columnIndex;
    private Candidate candidate;
    private Status status = Status.PENDING;
    private CommunityServer.Place selected;
    private GameModes.DestinationUnavailable failure;
    private boolean defaultSearchResult, closed;

    private record ChunkKey(ServerLevel world, int x, int z) {}
    private record Candidate(ServerLevel world, CommunityServer.Place place,
                             BlockPos column, Kind kind, List<DestinationChunks.Lease> footprint) {}

    ModeDestinations(MinecraftServer server, GameModes.Mode mode, String map,
                     CommunityServer.Place savedPlace, CommunityServer.Place cachedDefault) {
        this.server = Objects.requireNonNull(server);
        this.mode = Objects.requireNonNull(mode);
        onServerThread();
        this.map = map;
        this.savedPlace = savedPlace;
        this.cachedDefault = cachedDefault;
        phase = mode == GameModes.Mode.HUB || mode == GameModes.Mode.MINIGAMES
            || mode == GameModes.Mode.ADVENTURE ? Phase.FALLBACK : Phase.SAVED;
    }

    private void onServerThread() {
        if (!server.isSameThread()) throw new IllegalStateException("Mode destinations require the server thread");
    }

    /** READY is revalidated on every poll, including while a switch warms up. */
    Status poll() {
        onServerThread();
        if (closed) throw new IllegalStateException("Mode destinations are closed");
        if (status == Status.FAILED) return status;
        int columns = 0;
        while (true) {
            if (candidate == null) {
                if (phase == Phase.SEARCH && columns == COLUMNS_PER_POLL) return pending();
                try {
                    candidate = nextCandidate();
                } catch (GameModes.DestinationUnavailable unavailable) {
                    return fail(unavailable);
                }
                if (candidate == null) continue;
            }
            var current = candidate;
            var place = current.place();
            double x = place == null ? current.column().getX() + .5 : place.x();
            double z = place == null ? current.column().getZ() + .5 : place.z();
            // A moving border can invalidate a cold request before it finishes.
            if (!horizontalBounds(current.world(), x, z)
                || place != null && !placeBounds(current.world(), place)) {
                if (current.kind() == Kind.FIXED) return noSafeDestination();
                candidate = null;
                pending();
                continue;
            }
            boolean ready = true;
            for (var lease : current.footprint()) {
                var problem = lease.failure();
                if (problem != null) return fail(new GameModes.DestinationUnavailable(
                    "The " + mode + " destination could not be prepared. " + problem + ". Ask the host to check the server log."));
                if (!lease.ready()) ready = false;
            }
            if (!ready) return pending();
            if (current.kind() == Kind.COLUMN) {
                if (columns == COLUMNS_PER_POLL) return pending();
                columns++;
                var column = current.column();
                var chunk = current.world().getChunkSource().getChunkNow(column.getX() >> 4, column.getZ() >> 4);
                if (chunk == null) return pending();
                // Native Level.getHeight is LevelChunk.getHeight + 1. The
                // latter returns the top occupied block, rather than the feet Y.
                int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    column.getX() & 15, column.getZ() & 15) + 1;
                place = new CommunityServer.Place(current.world().dimension().identifier().toString(), x, y, z, 0, 0);
            }
            // FULL leases cover the native BlockCollisions one-block halo as
            // well as feet/head/floor, so the existing safety check cannot wait
            // for a missing collision chunk. No terrain or player state changes.
            if (placeBounds(current.world(), place) && CommunityServer.safe(current.world(), place)) {
                selected = place;
                defaultSearchResult = current.kind() == Kind.CACHED || current.kind() == Kind.COLUMN;
                status = Status.READY;
                return status;
            }
            if (current.kind() == Kind.FIXED) return noSafeDestination();
            candidate = null;
            pending();
        }
    }

    private Candidate nextCandidate() {
        while (true) {
            switch (phase) {
                case SAVED -> {
                    phase = Phase.FALLBACK;
                    var world = resolve(savedPlace);
                    if (world != null && GameModes.of(world) == mode && placeBounds(world, savedPlace))
                        return fixed(world, savedPlace, Kind.SAVED);
                }
                case FALLBACK -> {
                    var world = GameModes.world(server, mode);
                    if (world == null) throw new GameModes.DestinationUnavailable(
                        "The " + mode + " world is unavailable. Ask the host to check the server data pack.");
                    if (mode == GameModes.Mode.CREATIVE || mode == GameModes.Mode.HARDCORE) {
                        phase = Phase.CACHE;
                        continue;
                    }
                    CommunityServer.Place place;
                    if (mode == GameModes.Mode.HUB) {
                        String hall = map == null ? "main" : map;
                        if (!LobbyServer.LOBBIES.containsKey(hall)) throw new GameModes.DestinationUnavailable(
                            "The requested Hub hall is unavailable. Ask the host to check the server lobby.");
                        place = LobbyServer.place(server, hall);
                    } else if (mode == GameModes.Mode.MINIGAMES || mode == GameModes.Mode.ADVENTURE) {
                        var spec = ModeMaps.MAPS.get(map);
                        if (spec == null || spec.mode() != mode || !ModeMaps.available(map))
                            throw new GameModes.DestinationUnavailable(
                                "The selected course is unavailable in " + mode + ". Ask the host to check its map.");
                        place = GameModes.mapPlace(server, mode, map);
                    } else {
                        place = CommunityServer.get(server).data.spawn;
                        var configuredWorld = resolve(place);
                        if (configuredWorld == null || GameModes.of(configuredWorld) != GameModes.Mode.SURVIVAL) {
                            var respawn = server.getRespawnData();
                            var pos = respawn.pos();
                            place = new CommunityServer.Place(respawn.dimension().identifier().toString(),
                                pos.getX() + .5, pos.getY(), pos.getZ() + .5, 0, 0);
                        }
                        world = resolve(place);
                    }
                    phase = Phase.DONE;
                    if (world == null || GameModes.of(world) != mode || !placeBounds(world, place))
                        throw unavailable();
                    return fixed(world, place, Kind.FIXED);
                }
                case CACHE -> {
                    phase = Phase.SEARCH;
                    var world = GameModes.world(server, mode);
                    // Only origin-search results belong in this cache. In
                    // particular, never reinterpret a saved home as a default.
                    if (world != null && defaultGridPlace(world, cachedDefault) && placeBounds(world, cachedDefault))
                        return fixed(world, cachedDefault, Kind.CACHED);
                }
                case SEARCH -> {
                    var world = GameModes.world(server, mode);
                    if (world == null) throw unavailable();
                    while (columnIndex < GameModes.DEFAULT_SPAWN_CANDIDATES.size()) {
                        var column = GameModes.DEFAULT_SPAWN_CANDIDATES.get(columnIndex++);
                        double x = column.getX() + .5, z = column.getZ() + .5;
                        if (!horizontalBounds(world, x, z)) continue;
                        return new Candidate(world, null, column, Kind.COLUMN, retain(world, x, z));
                    }
                    throw new GameModes.DestinationUnavailable("No dry, safe spawn is available in " + mode
                        + " within 64 blocks of its origin. Ask the host to choose a dry spawn inside its world border.");
                }
                case DONE -> throw unavailable();
            }
        }
    }

    private Candidate fixed(ServerLevel world, CommunityServer.Place place, Kind kind) {
        return new Candidate(world, place, null, kind, retain(world, place.x(), place.z()));
    }

    private List<DestinationChunks.Lease> retain(ServerLevel world, double x, double z) {
        // Match the native BlockCollisions cursor, including its epsilon and
        // one-block margin used for collision shapes extending out of a block.
        int minX = (Mth.floor(x - .3 - COLLISION_EPSILON) - 1) >> 4;
        int maxX = (Mth.floor(x + .3 + COLLISION_EPSILON) + 1) >> 4;
        int minZ = (Mth.floor(z - .3 - COLLISION_EPSILON) - 1) >> 4;
        int maxZ = (Mth.floor(z + .3 + COLLISION_EPSILON) + 1) >> 4;
        var footprint = new ArrayList<DestinationChunks.Lease>(4);
        for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
            var key = new ChunkKey(world, cx, cz);
            var lease = leases.get(key);
            if (lease == null) {
                lease = DestinationChunks.acquire(world, new ChunkPos(cx, cz));
                leases.put(key, lease);
            }
            footprint.add(lease);
        }
        return List.copyOf(footprint);
    }

    private ServerLevel resolve(CommunityServer.Place place) {
        return place == null || !place.valid() ? null : server.getLevel(ResourceKey.create(
            Registries.DIMENSION, Identifier.parse(place.dimension())));
    }

    private static boolean horizontalBounds(ServerLevel world, double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z) || Math.abs(x) >= 30_000_000 || Math.abs(z) >= 30_000_000)
            return false;
        return world.getWorldBorder().isWithinBounds(BlockPos.containing(x, world.getMinY(), z));
    }

    private static boolean placeBounds(ServerLevel world, CommunityServer.Place place) {
        if (place == null || !place.valid() || !horizontalBounds(world, place.x(), place.z())) return false;
        var feet = BlockPos.containing(place.x(), place.y(), place.z());
        return world.isInWorldBounds(feet) && world.isInWorldBounds(feet.above());
    }

    private static boolean defaultGridPlace(ServerLevel world, CommunityServer.Place place) {
        if (place == null || !place.valid() || !place.dimension().equals(world.dimension().identifier().toString())) return false;
        int x = Mth.floor(place.x()), z = Mth.floor(place.z());
        return x >= -64 && x <= 64 && z >= -64 && z <= 64 && x % 4 == 0 && z % 4 == 0
            && place.x() == x + .5 && place.z() == z + .5;
    }

    private GameModes.DestinationUnavailable unavailable() {
        return new GameModes.DestinationUnavailable("No safe destination is available in " + mode
            + ". Ask the host to check its spawn, course and world border.");
    }

    private Status noSafeDestination() { return fail(unavailable()); }
    private Status pending() {
        selected = null;
        defaultSearchResult = false;
        status = Status.PENDING;
        return status;
    }
    private Status fail(GameModes.DestinationUnavailable unavailable) {
        selected = null;
        defaultSearchResult = false;
        failure = unavailable;
        status = Status.FAILED;
        return status;
    }

    CommunityServer.Place selected() { onServerThread(); return selected; }
    GameModes.DestinationUnavailable failure() { onServerThread(); return failure; }
    boolean defaultSearchResult() { onServerThread(); return defaultSearchResult; }

    @Override public void close() {
        onServerThread();
        if (closed) return;
        closed = true;
        // Close each acquired reference exactly once, even if cleanup itself
        // encounters an actual error. Such errors remain visible to the caller.
        Throwable problem = null;
        for (var lease : leases.values()) try {
            lease.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (problem == null) problem = cleanupFailure;
            else problem.addSuppressed(cleanupFailure);
        }
        leases.clear();
        candidate = null;
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
    }
}
