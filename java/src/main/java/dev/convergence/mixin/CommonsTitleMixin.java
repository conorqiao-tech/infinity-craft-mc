package dev.convergence.mixin;

import dev.convergence.CommonsEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Per-player display titles never change rank teams, combat teams or permission levels. */
@Mixin(ServerPlayer.class)
abstract class CommonsTitleMixin {
    @Inject(method="getTabListDisplayName",at=@At("RETURN"),cancellable=true)
    private void infinity$commonsTitle(CallbackInfoReturnable<Component> cir){
        cir.setReturnValue(CommonsEvents.tabName((ServerPlayer)(Object)this,cir.getReturnValue()));
    }
}
