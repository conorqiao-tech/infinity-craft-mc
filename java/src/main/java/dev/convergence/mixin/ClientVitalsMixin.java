package dev.convergence.mixin;

import dev.convergence.ClientVitals;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ClientVitalsMixin {
    @ModifyVariable(method="send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at=@At("HEAD"), argsOnly=true)
    private Packet<?> infinity$compactVitals(Packet<?> packet) {
        return ClientVitals.rewrite(packet, (Object)this instanceof ServerGamePacketListenerImpl play ? play.player : null);
    }
}
