package dev.convergence.odysseyperf;

import dev.convergence.OdysseyJournalChunkProbe;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
abstract class ServerChunkReadProbeMixin {
    @Inject(method="getChunkNow",at=@At("HEAD"))
    private void completedLookup(int x,int z,CallbackInfoReturnable<LevelChunk> cir){
        OdysseyJournalChunkProbe.completedLookup((ServerChunkCache)(Object)this,x,z);
    }
    @Inject(method="getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",at=@At("HEAD"))
    private void synchronousLookup(int x,int z,ChunkStatus status,boolean create,CallbackInfoReturnable<ChunkAccess> cir){
        OdysseyJournalChunkProbe.synchronousLookup((ServerChunkCache)(Object)this,status);
    }
}
