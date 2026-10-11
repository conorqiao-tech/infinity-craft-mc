package dev.convergence.mixin;

import dev.convergence.PhysicsSafety;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
abstract class PhysicsSafetyMixin {
    @ModifyVariable(method="move", at=@At("HEAD"), argsOnly=true)
    private Vec3d infinity$boundedCollisionMovement(Vec3d movement) { return PhysicsSafety.motion(movement); }

    @ModifyVariable(method="setVelocity(Lnet/minecraft/util/math/Vec3d;)V", at=@At("HEAD"), argsOnly=true)
    private Vec3d infinity$boundedVelocity(Vec3d velocity) { return PhysicsSafety.motion(velocity); }
}
