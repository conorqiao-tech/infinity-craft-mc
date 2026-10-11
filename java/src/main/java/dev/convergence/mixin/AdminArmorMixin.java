package dev.convergence.mixin;

import dev.convergence.AdminAttribute;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only an explicitly expanded armor instance can exceed vanilla's 80% ceiling. */
@Mixin(CombatRules.class)
public abstract class AdminArmorMixin {
    @Redirect(method="getDamageAfterAbsorb",at=@At(value="INVOKE",target="Lnet/minecraft/util/Mth;clamp(FFF)F",ordinal=0))
    private static float extendedArmor(float value,float minimum,float maximum,LivingEntity target,float damage,DamageSource source,float armor,float toughness) {
        var instance=target.getAttribute(Attributes.ARMOR);
        if(instance instanceof AdminAttribute expanded && expanded.infinity$expanded() && armor>30)
            return Mth.clamp(value,Math.min(minimum,25),25);
        return Mth.clamp(value,minimum,maximum);
    }
}
