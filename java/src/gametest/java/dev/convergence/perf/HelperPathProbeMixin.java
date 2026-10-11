package dev.convergence.perf;

import dev.convergence.HelperPerformanceProbe;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathFinder.class)
abstract class HelperPathProbeMixin {
    @Inject(method="findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",at=@At("HEAD"))
    private void pathStart(CallbackInfoReturnable<?> ci){HelperPerformanceProbe.pathStart();}
    @Inject(method="findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;",at=@At("RETURN"))
    private void pathEnd(CallbackInfoReturnable<?> ci){HelperPerformanceProbe.pathEnd();}
}
