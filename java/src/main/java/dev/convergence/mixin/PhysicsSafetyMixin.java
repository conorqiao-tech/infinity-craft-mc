package dev.convergence.mixin;

import dev.convergence.PhysicsSafety;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
abstract class PhysicsSafetyMixin {
    @ModifyVariable(method="move", at=@At("HEAD"), argsOnly=true)
    private Vec3 infinity$boundedCollisionMovement(Vec3 movement) { return PhysicsSafety.motion(movement); }

    @ModifyVariable(method="setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at=@At("HEAD"), argsOnly=true)
    private Vec3 infinity$boundedVelocity(Vec3 velocity) { return PhysicsSafety.motion(velocity); }
}
