package dev.convergence.menuperf;

import dev.convergence.MenuTrackingPerformanceProbe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CustomData.class)
abstract class MenuCustomDataProbeMixin {
    @Inject(method="copyTag",at=@At("HEAD"))
    private void copy(CallbackInfoReturnable<CompoundTag> cir) { MenuTrackingPerformanceProbe.copy((CustomData)(Object)this); }
}
