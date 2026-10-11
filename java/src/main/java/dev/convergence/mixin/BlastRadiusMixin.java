package dev.convergence.mixin;

import dev.convergence.BlastSafety;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerExplosion.class)
public abstract class BlastRadiusMixin {
    @Shadow @Final @Mutable private float radius;
    @Inject(method="<init>",at=@At("RETURN"))
    private void infinity$boundBlast(CallbackInfo ci){radius=BlastSafety.radius(radius);}
}
