package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import java.util.function.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.MaceItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MaceItem.class)
public abstract class AgentMaceMixin {
    @Inject(method="getKnockbackPredicate",at=@At("RETURN"),cancellable=true)
    private static void permittedSplash(Entity attacker,Entity target,CallbackInfoReturnable<Predicate<LivingEntity>> result) {
        if(AgentCompanions.registeredHelper(attacker))result.setReturnValue(result.getReturnValue().and(victim->AgentCompanions.weaponTarget(attacker,victim)));
    }
}
