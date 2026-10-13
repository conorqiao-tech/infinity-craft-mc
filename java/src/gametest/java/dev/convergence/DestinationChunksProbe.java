package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Native-call observations installed only by the exclusive test mod. */
public final class DestinationChunksProbe {
    public static final class Start {
        public final int tick, radius;
        public final ChunkPos position;
        public CompletableFuture<?> future;
        Start(int tick, ChunkPos position, int radius) {
            this.tick = tick; this.position = position; this.radius = radius;
        }
    }
    public static final class Sample {
        final MinecraftServer server;
        final ServerChunkCache source;
        final TicketType type;
        final Set<ChunkPos> watched;
        public final List<Start> starts = new ArrayList<>();
        public int blockingFutureCalls;
        int measurementDepth;
        Sample(MinecraftServer server, ServerChunkCache source, TicketType type, Set<ChunkPos> watched) {
            this.server = server; this.source = source; this.type = type; this.watched = watched;
        }
        <T> T measure(Supplier<T> action) {
            measurementDepth++;
            try { return action.get(); }
            finally { measurementDepth--; }
        }
    }
    private static Sample active;
    private DestinationChunksProbe() {}
    static Sample begin(MinecraftServer server, ServerChunkCache source, TicketType type, Set<ChunkPos> watched) {
        if (active != null) throw new IllegalStateException("A destination probe is already active");
        return active = new Sample(server, source, type, watched);
    }
    static void end(Sample sample) {
        if (active == sample) active = null;
    }
    public static void loading(ServerChunkCache source, TicketType type, ChunkPos position, int radius) {
        var sample = active;
        if (sample != null && sample.source == source && sample.type.equals(type) && sample.watched.contains(position))
            sample.starts.add(new Start(sample.server.getTickCount(), position, radius));
    }
    public static void loadedFuture(ServerChunkCache source, TicketType type, ChunkPos position, CompletableFuture<?> future) {
        var sample = active;
        if (sample == null || sample.source != source || !sample.type.equals(type)) return;
        for (int i = sample.starts.size() - 1; i >= 0; i--) {
            var start = sample.starts.get(i);
            if (start.position.equals(position) && start.future == null) { start.future = future; return; }
        }
    }
    public static void blockingFuture(ServerChunkCache source) {
        var sample = active;
        if (sample != null && sample.source == source && sample.measurementDepth > 0) sample.blockingFutureCalls++;
    }
}
