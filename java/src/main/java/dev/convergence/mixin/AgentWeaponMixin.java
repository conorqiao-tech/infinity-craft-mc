package dev.convergence.mixin;

import dev.convergence.AgentCompanions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Iron golem's own punch override skips Item damage. Use its native Mob superclass instead. */
@Mixin(IronGolemEntity.class)
public abstract class AgentWeaponMixin extends GolemEntity {
    protected AgentWeaponMixin(EntityType<? extends GolemEntity> type,World world) {super(type,world);}
    @Inject(method="tryAttack",at=@At("HEAD"),cancellable=true)
    private void weaponAttack(ServerWorld world,Entity target,CallbackInfoReturnable<Boolean> result) {
        if(AgentCompanions.weaponCombat(this))
            result.setReturnValue(AgentCompanions.weaponTarget(this,target) && super.tryAttack(world,target));
    }
}
