package dev.convergence;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.GameProtocols;

/** Native play codecs and successful outbound write receipts, entirely in process. */
final class NativePlayConnection {
    final Connection connection = new Connection(PacketFlow.SERVERBOUND);
    final EmbeddedChannel channel;
    private final ConcurrentLinkedQueue<Packet<?>> delivered = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

    NativePlayConnection(RegistryAccess registries) {
        channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel ch) {
                Connection.configureSerialization(ch.pipeline(), PacketFlow.SERVERBOUND, false, null);
                // A completed native handshake leaves an inbound configuration handler.
                // placeNewPlayer supplies the actual play listener and inbound codec next.
                ch.pipeline().replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
                connection.configurePacketHandler(ch.pipeline());
                ch.pipeline().addBefore("packet_handler", "delivered_packets", new ChannelOutboundHandlerAdapter() {
                    @Override public void write(ChannelHandlerContext ctx, Object value, ChannelPromise promise) {
                        if (value instanceof Packet<?> packet) {
                            var observed = promise.unvoid();
                            observed.addListener(result -> {
                                if (result.isSuccess()) delivered.add(packet);
                                else failures.add(result.cause());
                            });
                            ctx.write(value, observed);
                        } else ctx.write(value, promise);
                    }
                });
            }
        });
        connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries)));
        channel.runPendingTasks(); channel.checkException();
    }

    List<Packet<?>> drain() {
        channel.runPendingTasks(); connection.flushChannel(); channel.runPendingTasks(); channel.flush();
        channel.checkException();
        Object encoded;
        while ((encoded = channel.readOutbound()) != null) {
            if (!(encoded instanceof ByteBuf bytes)) throw new AssertionError("Native play encoder left unencoded output: " + encoded.getClass().getName());
            bytes.release();
        }
        var failure = failures.poll();
        if (failure != null) throw new AssertionError("Native play packet write failed", failure);
        var result = new ArrayList<Packet<?>>();
        Packet<?> packet;
        while ((packet = delivered.poll()) != null) result.add(packet);
        return result;
    }
}
