package dev.convergence.destinationperf;

import dev.convergence.DestinationChunksProbe;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe actual pinned native calls; never replace their results. */
@Mixin(ServerChunkCache.class)
abstract class DestinationChunksProbeMixin {
    @Inject(method="addTicketAndLoadWithRadius", at=@At("HEAD"))
    private void loading(TicketType type, ChunkPos position, int radius, CallbackInfoReturnable<?> callback) {
        DestinationChunksProbe.loading((ServerChunkCache)(Object)this, type, position, radius);
    }
    @Inject(method="addTicketAndLoadWithRadius", at=@At("RETURN"))
    private void loadedFuture(TicketType type, ChunkPos position, int radius, CallbackInfoReturnable<?> callback) {
        DestinationChunksProbe.loadedFuture((ServerChunkCache)(Object)this, type, position, (CompletableFuture<?>)callback.getReturnValue());
    }
    @Inject(method="getChunkFuture", at=@At("HEAD"))
    private void blockingFuture(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<?> callback) {
        DestinationChunksProbe.blockingFuture((ServerChunkCache)(Object)this);
    }
}
