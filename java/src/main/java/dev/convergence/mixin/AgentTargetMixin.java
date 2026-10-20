package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native target vetoes are bypassed only for this helper's exact eligible reviewed target. */
@Mixin(IronGolem.class)
abstract class AgentTargetMixin {
    @Inject(method="canAttack",at=@At("HEAD"),cancellable=true)
    private void approvedPlayer(LivingEntity target,CallbackInfoReturnable<Boolean> result) {
        var helper=(IronGolem)(Object)this;
        if(AgentCompanions.approvedPlayerTarget(helper,target) || AgentCompanions.approvedEntityTarget(helper,target))result.setReturnValue(true);
    }
}
