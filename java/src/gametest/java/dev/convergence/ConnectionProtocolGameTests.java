package dev.convergence;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.SkipPacketDecoderException;
import net.minecraft.network.SkipPacketEncoderException;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.VarInt;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.network.ServerStatusPacketListenerImpl;

/** Native serialization and fault handling; every channel stays in process. */
public class ConnectionProtocolGameTests {
    static ServerStatus sampleStatus() {
        return new ServerStatus(Component.literal("in-process status"), Optional.empty(),
            Optional.of(ServerStatus.Version.current()), Optional.empty(), false, Optional.empty());
    }

    static final class Fixture implements AutoCloseable {
        final Connection connection = new Connection(PacketFlow.SERVERBOUND);
        final List<Packet<?>> attempted = new ArrayList<>();
        final List<Throwable> failedWrites = new ArrayList<>();
        final EmbeddedChannel channel;
        ProtocolInfo<?> outbound;
        int closes;

        Fixture() {
            channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
                @Override protected void initChannel(Channel ch) {
                    Connection.configureSerialization(ch.pipeline(), PacketFlow.SERVERBOUND, false, null);
                    // Native terminal handshake decoding makes this replacement.
                    // Model that transition without sending or decoding any input.
                    ch.pipeline().replace("decoder", "inbound_config", new UnconfiguredPipelineHandler.Inbound());
                    connection.configurePacketHandler(ch.pipeline());
                    ch.pipeline().addBefore("packet_handler", "observe_writes", new ChannelOutboundHandlerAdapter() {
                        @Override public void write(ChannelHandlerContext ctx, Object value, ChannelPromise promise) throws Exception {
                            if (value instanceof Packet<?> packet) {
                                attempted.add(packet);
                                // Native sends without a completion use a void
                                // promise, which cannot accept test listeners.
                                var observed = promise.unvoid();
                                observed.addListener(result -> { if (!result.isSuccess()) failedWrites.add(result.cause()); });
                                ctx.write(value, observed);
                                return;
                            }
                            ctx.write(value, promise);
                        }
                    });
                    ch.pipeline().addBefore("packet_handler", "observe_close", new ChannelInboundHandlerAdapter() {
                        @Override public void channelInactive(ChannelHandlerContext ctx) {
                            closes++;
                            ctx.fireChannelInactive();
                        }
                    });
                }
            });
        }

        void bind(ProtocolInfo<?> protocol) {
            if (channel.pipeline().get("encoder") != null) {
                // Represent another native protocol transition before binding.
                channel.pipeline().replace("encoder", "outbound_config", new UnconfiguredPipelineHandler.Outbound());
            }
            connection.setupOutboundProtocol(protocol);
            outbound = protocol;
        }

        ServerStatusPacketListenerImpl status(ServerStatus status) {
            bind(StatusProtocols.CLIENTBOUND);
            var listener = new ServerStatusPacketListenerImpl(status, connection);
            connection.setupInboundProtocol(StatusProtocols.SERVERBOUND, listener);
            return listener;
        }

        void fault(Throwable cause) {
            channel.pipeline().fireExceptionCaught(cause);
            channel.runPendingTasks();
            channel.checkException();
        }

        List<Packet<?>> decode(GameTestHelper c) {
            channel.runPendingTasks();
            channel.checkException();
            var packets = new ArrayList<Packet<?>>();
            Object value;
            while ((value = channel.readOutbound()) != null) {
                c.assertTrue(value instanceof ByteBuf, "Native serialization produces byte buffers");
                var bytes = (ByteBuf)value;
                try {
                    c.assertValueEqual(VarInt.read(bytes), bytes.readableBytes(), "Native frame length matches encoded packet");
                    packets.add(outbound.codec().decode(bytes));
                    c.assertFalse(bytes.isReadable(), "Registered protocol codec consumes the entire packet");
                } finally { bytes.release(); }
            }
            return packets;
        }

        void closedWithoutPacket(GameTestHelper c) {
            c.assertFalse(channel.isOpen(), "Failed STATUS channel closes");
            c.assertValueEqual(closes, 1, "Channel closes once");
            c.assertTrue(attempted.isEmpty(), "STATUS never attempts an unregistered disconnect packet");
            c.assertTrue(failedWrites.isEmpty(), "No secondary encoder failure");
            c.assertTrue(decode(c).isEmpty(), "Direct closure emits no bytes");
        }

        @Override public void close() { channel.finishAndReleaseAll(); }
    }

    @GameTest public void statusFaultClosesWithoutInvalidPacketAndPreservesOriginalDetails(GameTestHelper c) {
        try (var f = new Fixture()) {
            f.bind(StatusProtocols.CLIENTBOUND);
            var cause = new IllegalStateException("synthetic status failure");
            var calls = new int[1];
            var details = new DisconnectionDetails(Component.literal("original status fault"),
                Optional.of(Path.of("in-process-status-report")), Optional.empty());
            var listener = new ServerStatusPacketListenerImpl(sampleStatus(), f.connection) {
                @Override public DisconnectionDetails createDisconnectionInfo(Component reason, Throwable received) {
                    calls[0]++;
                    c.assertTrue(received == cause, "Original exception reaches native listener diagnostics");
                    c.assertTrue(reason.getString().contains("synthetic status failure"), "Native reason retains the original exception");
                    return details;
                }
            };
            f.connection.setupInboundProtocol(StatusProtocols.SERVERBOUND, listener);
            f.fault(cause);
            f.closedWithoutPacket(c);
            c.assertValueEqual(calls[0], 1, "Native disconnection details are created once");
            c.assertTrue(f.connection.getDisconnectionDetails() == details, "Direct close retains native reason and report metadata");
        }
        c.succeed();
    }

    @GameTest public void genuineStatusResponseAndPongUseTheirRegisteredCodecs(GameTestHelper c) {
        for (boolean secureChat : new boolean[]{false, true})
        for (var contact : List.of(Optional.<String>empty(), Optional.of("https://example.invalid/contact"))) {
            try (var f = new Fixture()) {
                var base = sampleStatus();
                var status = new ServerStatus(base.description(), base.players(), base.version(), base.favicon(), secureChat, contact);
                var listener = f.status(status);
                listener.handleStatusRequest(ServerboundStatusRequestPacket.INSTANCE);
                var response = f.decode(c);
                c.assertValueEqual(response.size(), 1, "Exactly one native status response");
                c.assertTrue(response.getFirst() instanceof ClientboundStatusResponsePacket packet && packet.status().equals(status),
                    "Real STATUS codec preserves secure-chat state and optional contact details");
                c.assertTrue(f.channel.isOpen(), "Status response waits for ping");
                listener.handlePingRequest(new ServerboundPingRequestPacket(123456789L));
                var pong = f.decode(c);
                c.assertValueEqual(pong.size(), 1, "Exactly one native pong response");
                c.assertTrue(pong.getFirst() instanceof ClientboundPongResponsePacket packet && packet.time() == 123456789L,
                    "Real STATUS codec preserves the ping value");
                c.assertFalse(f.channel.isOpen(), "Native ping completion closes normally");
                c.assertValueEqual(f.closes, 1, "Successful status exchange closes once");
                c.assertValueEqual(f.attempted.size(), 2, "Only status response and pong are sent");
                c.assertTrue(f.failedWrites.isEmpty(), "Both genuine packets encode successfully");
            }
        }
        c.succeed();
    }

    @GameTest public void outboundStatusFaultClosesBeforeAnyStatusListenerIsInstalled(GameTestHelper c) {
        try (var f = new Fixture()) {
            f.bind(StatusProtocols.CLIENTBOUND);
            c.assertTrue(f.connection.getPacketListener() == null, "Incoming phase has no STATUS listener yet");
            f.fault(new IllegalStateException("synthetic outbound status fault"));
            f.closedWithoutPacket(c);
            c.assertTrue(f.connection.getDisconnectionDetails().reason().getString().contains("synthetic outbound status fault"),
                "Outbound phase guard preserves the original failure reason");
        }
        c.succeed();
    }

    void registeredDisconnect(GameTestHelper c, ProtocolInfo<?> protocol, boolean login) {
        try (var f = new Fixture()) {
            f.bind(protocol);
            f.fault(new IllegalStateException("synthetic registered protocol fault"));
            var packets = f.decode(c);
            c.assertValueEqual(packets.size(), 1, "Native fault still sends one registered disconnect");
            var packet = packets.getFirst();
            c.assertTrue(login ? packet instanceof ClientboundLoginDisconnectPacket : packet instanceof ClientboundDisconnectPacket,
                "Disconnect type matches the real outbound protocol");
            var reason = packet instanceof ClientboundLoginDisconnectPacket p ? p.reason() : ((ClientboundDisconnectPacket)packet).reason();
            c.assertTrue(reason.getString().contains("synthetic registered protocol fault"), "Registered codec retains the fault reason");
            c.assertValueEqual(f.attempted.size(), 1, "Native send is preserved");
            c.assertTrue(f.failedWrites.isEmpty(), "The genuine disconnect encodes successfully");
            c.assertFalse(f.channel.isOpen(), "Native send completion closes the channel");
            c.assertValueEqual(f.closes, 1, "Native disconnect closes once");
        }
        c.succeed();
    }

    @GameTest public void loginFaultKeepsItsRealLoginDisconnect(GameTestHelper c) {
        registeredDisconnect(c, LoginProtocols.CLIENTBOUND, true);
    }

    @GameTest public void configurationFaultKeepsItsRealCommonDisconnect(GameTestHelper c) {
        registeredDisconnect(c, ConfigurationProtocols.CLIENTBOUND, false);
    }

    @GameTest public void playFaultKeepsItsRealCommonDisconnect(GameTestHelper c) {
        registeredDisconnect(c, GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(c.getLevel().registryAccess())), false);
    }

    @GameTest public void changingOutboundStatusToLoginRestoresLoginDisconnectDespiteStaleStatusListener(GameTestHelper c) {
        try (var f = new Fixture()) {
            f.status(sampleStatus());
            f.bind(LoginProtocols.CLIENTBOUND);
            f.fault(new IllegalStateException("synthetic transition fault"));
            var packets = f.decode(c);
            c.assertValueEqual(packets.size(), 1, "New LOGIN binding sends one disconnect");
            c.assertTrue(packets.getFirst() instanceof ClientboundLoginDisconnectPacket, "Actual outbound LOGIN overrides stale STATUS listener");
            c.assertValueEqual(f.attempted.size(), 1, "New protocol uses native send");
            c.assertTrue(f.failedWrites.isEmpty(), "New LOGIN codec encodes without errors");
            c.assertFalse(f.channel.isOpen(), "Native LOGIN completion closes the channel");
        }
        c.succeed();
    }

    @GameTest public void statusTimeoutKeepsNativeDirectCloseAndReason(GameTestHelper c) {
        try (var f = new Fixture()) {
            f.status(sampleStatus());
            f.fault(ReadTimeoutException.INSTANCE);
            f.closedWithoutPacket(c);
            c.assertValueEqual(f.connection.getDisconnectionDetails().reason(), Component.translatable("disconnect.timeout"),
                "Timeout keeps its native reason");
        }
        c.succeed();
    }

    @GameTest public void skippedStatusPacketsKeepTheChannelUsable(GameTestHelper c) {
        try (var f = new Fixture()) {
            var listener = f.status(sampleStatus());
            f.fault(new SkipPacketDecoderException(new IllegalStateException("synthetic skipped decode")));
            f.fault(new SkipPacketEncoderException(new IllegalStateException("synthetic skipped encode")));
            c.assertTrue(f.channel.isOpen(), "Native skip-packet handling does not close STATUS");
            c.assertTrue(f.attempted.isEmpty() && f.failedWrites.isEmpty(), "Skip-packet handling emits no disconnect");
            listener.handleStatusRequest(ServerboundStatusRequestPacket.INSTANCE);
            c.assertTrue(f.decode(c).getFirst() instanceof ClientboundStatusResponsePacket, "STATUS remains usable after skipped packets");
            listener.handlePingRequest(new ServerboundPingRequestPacket(7L));
            c.assertTrue(f.decode(c).getFirst() instanceof ClientboundPongResponsePacket, "Normal pong still completes the exchange");
            c.assertTrue(f.failedWrites.isEmpty(), "Skipped exceptions do not damage the genuine codec");
        }
        c.succeed();
    }

    @GameTest public void repeatedStatusFaultAfterClosureDoesNotSendOrReplaceTheFirstReason(GameTestHelper c) {
        try (var f = new Fixture()) {
            f.status(sampleStatus());
            var context = f.channel.pipeline().context(f.connection);
            c.assertTrue(context != null, "Native connection handler is installed before closure");
            f.fault(new IllegalStateException("synthetic first fault"));
            var original = f.connection.getDisconnectionDetails();
            // Netty removes handlers after closing an EmbeddedChannel. A new
            // pipeline event would reach its tail rather than Connection.
            f.connection.exceptionCaught(context, new IllegalStateException("synthetic repeated fault"));
            f.channel.runPendingTasks();
            f.channel.checkException();
            f.closedWithoutPacket(c);
            c.assertTrue(f.connection.getDisconnectionDetails() == original, "Repeated fault preserves first native disconnection details");
        }
        c.succeed();
    }

    @GameTest public void cleanStatusChannelInactiveKeepsNativePacketFreeClosure(GameTestHelper c) {
        try (var f = new Fixture()) {
            f.status(sampleStatus());
            f.channel.close();
            f.channel.runPendingTasks();
            f.closedWithoutPacket(c);
        }
        c.succeed();
    }
}
