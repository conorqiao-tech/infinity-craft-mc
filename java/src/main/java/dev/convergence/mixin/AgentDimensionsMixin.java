package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Match the helper's player avatar; vanilla applies its existing scale afterward. */
@Mixin(LivingEntity.class)
abstract class AgentDimensionsMixin {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void helperBody(Pose pose, CallbackInfoReturnable<EntityDimensions> result) {
        if (AgentCompanions.registeredHelper((LivingEntity)(Object)this))
            result.setReturnValue(AgentCompanions.HELPER_DIMENSIONS);
    }
}
