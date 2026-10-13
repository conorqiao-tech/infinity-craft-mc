package dev.convergence.mixin;

import dev.convergence.GameModes;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ModeRespawnMixin {
    @Shadow public ServerPlayer player;

    // Ordinal 1 is death respawn, after native thread, credits and health checks.
    // Ordinal 0 is the End credits return and keeps its native behavior.
    @Inject(method="handleClientCommand",at=@At(value="INVOKE",target="Lnet/minecraft/server/players/PlayerList;respawn(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;",ordinal=1),cancellable=true)
    private void preflight(ServerboundClientCommandPacket packet,CallbackInfo ci) {
        if(!GameModes.preflightRespawn(player))ci.cancel();
    }
}
