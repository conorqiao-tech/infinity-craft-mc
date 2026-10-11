package dev.convergence.modeperf;

import dev.convergence.ModeDestinationHeightmapProbe;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkAccess.class)
abstract class ModeDestinationHeightmapMixin {
    @Inject(method="getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",at=@At("HEAD"))
    private void height(Heightmap.Types type,int x,int z,CallbackInfoReturnable<Integer> callback){
        ModeDestinationHeightmapProbe.read((ChunkAccess)(Object)this,type,x,z);
    }
}
