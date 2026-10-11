package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Player-built golems refuse player targets; a helper's approved player order is the only exception. */
@Mixin(IronGolem.class)
abstract class AgentTargetMixin {
    @Inject(method="canAttack",at=@At("HEAD"),cancellable=true)
    private void approvedPlayer(LivingEntity target,CallbackInfoReturnable<Boolean> result) {
        if(AgentCompanions.approvedPlayerTarget((IronGolem)(Object)this,target))result.setReturnValue(true);
    }
}
