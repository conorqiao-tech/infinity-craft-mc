package dev.convergence.mixin;

import dev.convergence.AdminAttribute;
import net.minecraft.entity.DamageUtil;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only an explicitly expanded armor instance can exceed vanilla's 80% ceiling. */
@Mixin(DamageUtil.class)
public abstract class AdminArmorMixin {
    @Redirect(method="getDamageLeft",at=@At(value="INVOKE",target="Lnet/minecraft/util/math/MathHelper;clamp(FFF)F",ordinal=0))
    private static float extendedArmor(float value,float minimum,float maximum,LivingEntity target,float damage,DamageSource source,float armor,float toughness) {
        var instance=target.getAttributeInstance(EntityAttributes.ARMOR);
        if(instance instanceof AdminAttribute expanded && expanded.infinity$expanded() && armor>30)
            return MathHelper.clamp(value,Math.min(minimum,25),25);
        return MathHelper.clamp(value,minimum,maximum);
    }
}
