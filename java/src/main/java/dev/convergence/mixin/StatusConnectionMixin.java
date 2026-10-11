package dev.convergence.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** STATUS has response and pong packets, but no disconnect packet. */
@Mixin(Connection.class)
abstract class StatusConnectionMixin {
    @Shadow private Channel channel;
    @Unique private volatile ConnectionProtocol infinity$outboundProtocol;

    @Inject(method="setupOutboundProtocol", at=@At("RETURN"))
    private void infinity$rememberOutboundProtocol(ProtocolInfo<?> protocol, CallbackInfo ci) {
        // Native setup waits for its pipeline task. The inbound listener can
        // still describe the previous phase while this encoder is installed.
        infinity$outboundProtocol = protocol.id();
    }

    @Redirect(method="exceptionCaught", at=@At(value="INVOKE", target="Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V"))
    private void infinity$closeFailedStatus(Connection connection, Packet<?> packet,
                                           ChannelFutureListener completion) throws Exception {
        if (infinity$outboundProtocol == ConnectionProtocol.STATUS) {
            // Keep native fault diagnostics and its original disconnection
            // details. Its completion closes directly, without a STATUS send.
            completion.operationComplete(channel.newSucceededFuture());
        } else {
            connection.send(packet, completion);
        }
    }
}
