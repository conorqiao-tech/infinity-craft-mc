package dev.convergence.mixin;

import dev.convergence.PhysicsSafety;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
abstract class KnockbackSafetyMixin {
    @ModifyVariable(method="takeKnockback", at=@At("HEAD"), argsOnly=true, ordinal=0)
    private double infinity$boundedKnockbackImpulse(double strength) { return PhysicsSafety.knockback(strength); }
}
