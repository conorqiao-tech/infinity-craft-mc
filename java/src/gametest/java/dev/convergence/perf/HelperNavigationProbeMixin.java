package dev.convergence.perf;

import dev.convergence.HelperPerformanceProbe;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathNavigation.class)
abstract class HelperNavigationProbeMixin {
    @Inject(method={"moveTo(DDDD)Z","moveTo(Lnet/minecraft/world/entity/Entity;D)Z"},at=@At("HEAD"))
    private void move(CallbackInfoReturnable<Boolean> ci){if(HelperPerformanceProbe.measuringControl())HelperPerformanceProbe.active.moveRequests++;}
}
