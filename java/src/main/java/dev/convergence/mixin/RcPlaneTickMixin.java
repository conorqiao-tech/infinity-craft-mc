package dev.convergence.mixin;

import dev.convergence.RcPlanes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FallingBlockEntity.class)
public abstract class RcPlaneTickMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void infinity$planeTick(CallbackInfo ci){
        if(RcPlanes.tickPart((FallingBlockEntity)(Object)this))ci.cancel();
    }
}
