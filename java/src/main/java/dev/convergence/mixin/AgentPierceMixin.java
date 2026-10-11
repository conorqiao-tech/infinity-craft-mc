package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reject incidental spear knockback/dismount as well as damage before vanilla applies it. */
@Mixin(LivingEntity.class)
public abstract class AgentPierceMixin {
    @Inject(method="updateUsingItem",at=@At("HEAD"))
    private void aimBeforeNativeCharge(ItemStack stack,CallbackInfo info) {
        AgentCompanions.aimWeapon((LivingEntity)(Object)this);
    }
    @Inject(method="stabAttack",at=@At("HEAD"),cancellable=true)
    private void permittedPierce(EquipmentSlot slot,Entity target,float damage,boolean hurt,boolean knockback,boolean dismount,CallbackInfoReturnable<Boolean> result) {
        Entity self=(Entity)(Object)this;
        if(AgentCompanions.registeredHelper(self) && !AgentCompanions.weaponTarget(self,target))result.setReturnValue(false);
    }
}
