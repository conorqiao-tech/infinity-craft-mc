package dev.convergence.perf;

import dev.convergence.HelperPerformanceProbe;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.sensing.Sensing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Sensing.class)
abstract class HelperSightProbeMixin {
    @Inject(method="hasLineOfSight",at=@At("HEAD")) private void sight(CallbackInfoReturnable<Boolean> ci){if(HelperPerformanceProbe.measuringControl())HelperPerformanceProbe.active.sightCalls++;}
    @Redirect(method="hasLineOfSight",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Mob;hasLineOfSight(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean ray(Mob mob,Entity target){if(HelperPerformanceProbe.measuringControl())HelperPerformanceProbe.active.sightRays++;return mob.hasLineOfSight(target);}
}
