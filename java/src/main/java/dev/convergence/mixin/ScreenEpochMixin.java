package dev.convergence.mixin;

import dev.convergence.ScreenEpochAccess;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla calls this counter for ordinary, horse and nautilus container screens. */
@Mixin(ServerPlayer.class)
abstract class ScreenEpochMixin implements ScreenEpochAccess {
    @Unique private long infinity$screenEpoch;

    @Inject(method = "nextContainerCounter", at = @At("HEAD"))
    private void infinity$recordScreenOpen(CallbackInfo callback) { infinity$screenEpoch++; }

    @Override public long infinity$screenEpoch() { return infinity$screenEpoch; }
}
