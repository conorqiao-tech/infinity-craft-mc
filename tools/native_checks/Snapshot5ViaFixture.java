package dev.convergence;

import com.google.gson.JsonObject;
import com.viaversion.viabackwards.protocol.v26_3to26_2.Protocol26_3To26_2;
import com.viaversion.viabackwards.protocol.v26_4to26_3.Protocol26_4To26_3;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import net.minecraft.SharedConstants;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Executes the complete pinned Geyser Java 26.2 to native Snapshot 5 path. */
final class Snapshot5ViaFixture {
    final UserConnectionImpl user;
    final Protocol26_3To26_2 previous;
    final ProtocolPipelineImpl pipeline;

    Snapshot5ViaFixture(GameTestHelper c, UserConnectionImpl user) {
        this.user = user;
        c.assertValueEqual(SharedConstants.getCurrentVersion().id(), "26.4-snapshot-5", "The proof boots the exact native snapshot");
        c.assertValueEqual(SharedConstants.getCurrentVersion().protocolVersion(), 1073742167, "Native protocol is exact Snapshot 5");
        c.assertValueEqual(ProtocolVersion.v26_4.getOriginalVersion(), 1073742167, "Via targets the actual native snapshot protocol");
        var manager = Via.getManager().getProtocolManager();
        manager.completeMappingDataLoading(Protocol26_3To26_2.class);
        manager.completeMappingDataLoading(Protocol26_4To26_3.class);
        previous = manager.getProtocol(Protocol26_3To26_2.class);
        var snapshot = manager.getProtocol(Protocol26_4To26_3.class);
        c.assertTrue(previous != null && snapshot != null, "Both actual backwards translation stages are registered");
        var info = user.getProtocolInfo();
        info.setProtocolVersion(ProtocolVersion.v26_2);
        info.setServerProtocolVersion(ProtocolVersion.v26_4);
        info.setClientState(State.PLAY); info.setServerState(State.PLAY);
        pipeline = new ProtocolPipelineImpl(user);
        // Via runs this list client->server and its reverse server->client.
        pipeline.add(previous); pipeline.add(snapshot);
    }

    void transform(Direction direction, State state, PacketWrapper packet) throws Exception {
        pipeline.transform(direction, state, packet);
    }

    boolean canInstaBuild() { return previous.getEntityRewriter().tracker(user).canInstaBuild(); }

    static int nativeCreativePacketId(ServerPlayer player) {
        var bytes = Unpooled.buffer();
        try {
            GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(player.registryAccess()), player.connection)
                .codec().encode(bytes, new ServerboundSetCreativeModeSlotPacket((short)36, ItemStack.EMPTY));
            return VarInt.read(bytes);
        } finally { bytes.release(); }
    }

    static PacketWrapper nativeAbilities(ServerPlayer player, UserConnectionImpl user, ByteBuf bytes) {
        GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(player.registryAccess())).codec()
            .encode(bytes, new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        return PacketWrapper.create(VarInt.read(bytes), bytes, user);
    }

    static void dependencies(JsonObject report) throws Exception {
        String fabric = required("infinity.creative.viafabric");
        String backwards = required("infinity.creative.viabackwards");
        String nested = required("infinity.creative.viaversion");
        if (!fabric.matches("ViaFabric-[A-Za-z0-9.+_-]+\\.jar") || !backwards.matches("ViaBackwards-[A-Za-z0-9.+_-]+\\.jar")
                || !nested.matches("META-INF/jars/viaversion-common-[A-Za-z0-9.+_-]+\\.jar"))
            throw new IllegalArgumentException("Invalid exact executed Via artifact path");
        report.addProperty("viafabric_sha256", sha(Files.readAllBytes(Path.of("mods", fabric))));
        report.addProperty("viabackwards_sha256", sha(Files.readAllBytes(Path.of("mods", backwards))));
        try (var jar = new java.util.zip.ZipFile(Path.of("mods", fabric).toFile());
             var input = jar.getInputStream(java.util.Objects.requireNonNull(jar.getEntry(nested)))) {
            report.addProperty("viaversion_nested_sha256", sha(input.readAllBytes()));
        }
        report.addProperty("native_minecraft", SharedConstants.getCurrentVersion().id());
        report.addProperty("native_protocol", SharedConstants.getCurrentVersion().protocolVersion());
        report.add("translation_stages", new com.google.gson.Gson().toJsonTree(java.util.List.of(
            Protocol26_3To26_2.class.getName(), Protocol26_4To26_3.class.getName())));
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing exact Via input property: " + key);
        return value;
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
