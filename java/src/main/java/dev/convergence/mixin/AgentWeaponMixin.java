package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.AbstractGolem;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iron golem's own punch override skips Item damage. Use its native Mob superclass instead. */
@Mixin(IronGolem.class)
public abstract class AgentWeaponMixin extends AbstractGolem {
    protected AgentWeaponMixin(EntityType<? extends AbstractGolem> type,Level world) {super(type,world);}
    @Inject(method="doHurtTarget",at=@At("HEAD"),cancellable=true)
    private void weaponAttack(ServerLevel world,Entity target,CallbackInfoReturnable<Boolean> result) {
        if(AgentCompanions.weaponCombat(this))
            result.setReturnValue(AgentCompanions.weaponTarget(this,target) && super.doHurtTarget(world,target));
    }
}
