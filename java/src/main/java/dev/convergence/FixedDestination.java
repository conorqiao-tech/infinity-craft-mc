package dev.convergence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

/** A frozen exact destination, retained and polled without fallback or chunk waits. */
final class FixedDestination implements AutoCloseable {
    enum Status { PENDING, READY, FAILED }
    private static final double COLLISION_EPSILON = 1.0E-7;
    private final ServerLevel world;
    private final CommunityServer.Place target;
    private final boolean requireSafe;
    private final Map<ChunkPos, DestinationChunks.Lease> leases = new LinkedHashMap<>();
    private Status status = Status.PENDING;
    private GameModes.DestinationUnavailable failure;
    private boolean retained, closed;

    FixedDestination(ServerLevel world, CommunityServer.Place target, boolean requireSafe) {
        this.world = Objects.requireNonNull(world);
        onServerThread();
        this.target = target;
        this.requireSafe = requireSafe;
    }

    private void onServerThread() {
        if (!world.getServer().isSameThread())
            throw new IllegalStateException("Fixed destinations require the server thread");
    }

    /** READY is revalidated on each poll until the caller finishes and closes. */
    Status poll() {
        onServerThread();
        if (closed) throw new IllegalStateException("Fixed destination is closed");
        if (status == Status.FAILED) return status;
        if (target == null || !target.valid()
            || !world.dimension().identifier().equals(Identifier.tryParse(target.dimension())))
            return fail("That destination is invalid or belongs to a different world.", null);
        // Privileged callers still require a valid exact address and real FULL
        // terrain, but retain their existing floor, border and height bypass.
        if (requireSafe && !physicalBounds())
            return fail("That destination is outside the world bounds or border.", null);
        try {
            if (!retained) retainFootprint();
            boolean ready = true;
            for (var lease : leases.values()) {
                var problem = lease.failure();
                if (problem != null) return fail("The destination could not be prepared. " + problem, null);
                if (!lease.ready()) ready = false;
            }
            if (!ready) { status = Status.PENDING; return status; }
            // Every chunk touched by feet/head/floor and BlockCollisions is
            // now a retained native FULL chunk. This existing check cannot
            // synchronously request missing collision terrain.
            if (requireSafe && !CommunityServer.safe(world, target))
                return fail("That destination is unsafe or blocked. Ask its owner to reset it.", null);
            status = Status.READY;
            return status;
        } catch (RuntimeException problem) {
            return fail("The destination could not be prepared: " + problem, problem);
        }
    }

    private boolean physicalBounds() {
        var feet = BlockPos.containing(target.x(), target.y(), target.z());
        return world.isInWorldBounds(feet) && world.isInWorldBounds(feet.above())
            && world.getWorldBorder().isWithinBounds(feet);
    }

    private void retainFootprint() {
        // Match ModeDestinations and the pinned native BlockCollisions cursor:
        // epsilon plus one block covers collision shapes extending outwards.
        int minX = (Mth.floor(target.x() - .3 - COLLISION_EPSILON) - 1) >> 4;
        int maxX = (Mth.floor(target.x() + .3 + COLLISION_EPSILON) + 1) >> 4;
        int minZ = (Mth.floor(target.z() - .3 - COLLISION_EPSILON) - 1) >> 4;
        int maxZ = (Mth.floor(target.z() + .3 + COLLISION_EPSILON) + 1) >> 4;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            var position = new ChunkPos(x, z);
            leases.put(position, DestinationChunks.acquire(world, position));
        }
        retained = true;
    }

    private Status fail(String message, RuntimeException cause) {
        failure = new GameModes.DestinationUnavailable(message);
        if (cause != null) failure.initCause(cause);
        status = Status.FAILED;
        return status;
    }

    Status status() { onServerThread(); return status; }
    CommunityServer.Place place() { onServerThread(); return !closed && status == Status.READY ? target : null; }
    GameModes.DestinationUnavailable failure() { onServerThread(); return failure; }

    @Override public void close() {
        onServerThread();
        if (closed) return;
        closed = true;
        if (failure == null) failure = new GameModes.DestinationUnavailable("Destination preparation is closed");
        status = Status.FAILED;
        Throwable problem = null;
        for (var lease : leases.values()) try {
            lease.close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (problem == null) problem = cleanupFailure;
            else problem.addSuppressed(cleanupFailure);
        }
        leases.clear();
        if (problem instanceof RuntimeException runtime) throw runtime;
        if (problem instanceof Error error) throw error;
    }
}
