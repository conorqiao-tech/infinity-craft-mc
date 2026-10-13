package dev.infinityhub.geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerSlotType;
import org.cloudburstmc.protocol.bedrock.data.inventory.FullContainerName;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.CreativeItemData;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequest;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.ItemStackRequestSlotData;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftCreativeAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.CraftResultsDeprecatedAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DestroyAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.DropAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.ItemStackRequestAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.request.action.PlaceAction;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponse;
import org.cloudburstmc.protocol.bedrock.data.inventory.itemstack.response.ItemStackResponseStatus;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackRequestPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemStackResponsePacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.inventory.PlayerInventory;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.registry.type.ItemMapping;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;

/** Optional isolated startup proof: invokes Geyser's actual request translator and MCProtocolLib wire codec.
 * This is a transport capture, not a Bedrock client, ViaVersion test or visual/device test.
 */
final class CreativeProtocolProof {
    static JsonObject run(Catalogue catalogue, Map<String, Integer> aliasIds) throws Exception {
        JsonObject result = new JsonObject(); JsonArray encoded = new JsonArray(); JsonArray oversized = new JsonArray();
        int selections = 0, occupied = 0, replaced = 0;
        for (Int2ObjectMap.Entry<ItemMappings> version : Registries.ITEMS.get().int2ObjectEntrySet()) {
            ItemMappings mappings = version.getValue();
            try (CaptureSession session = new CaptureSession(mappings, version.getIntKey())) {
                PlayerInventory inventory = session.getPlayerInventory();
                for (Catalogue.Entry entry : catalogue.items) {
                    List<CreativeItemData> aliases = mappings.getCreativeItems(); int index = -1;
                    for (int i = 0; i < aliases.size(); i++) if (aliases.get(i).getItem().getDefinition().getIdentifier().equals(entry.alias())) {
                        require(index == -1, "Duplicate actual Creative entry"); index = i;
                    }
                    require(index >= 0, "Missing actual Creative entry"); ItemData data = aliases.get(index).getItem();
                    require(mappings.getMapping(data).getJavaItem() instanceof CatalogueItem, "Reverse mapping did not use the alias wrapper");
                    int baseId = mappings.getMapping(entry.base()).getJavaItem().javaId();
                    for (int count : entry.maxStack() == 1 ? new int[]{1} : new int[]{1, entry.maxStack()}) {
                        inventory.setItem(36, GeyserItemStack.EMPTY, session); inventory.setCursor(GeyserItemStack.EMPTY, session); session.packets.clear();
                        ItemStackResponse response = dispatchOne(session, request(index, data, count,
                                new PlaceAction(count, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.HOTBAR, 0))));
                        require(response.getResult() == ItemStackResponseStatus.OK, "Actual Creative hotbar request was rejected: " + entry.path());
                        check(inventory.getItem(36).getItemStack(), entry, baseId, count);
                        require(inventory.getItem(36).getJavaId() != aliasIds.get(entry.path()), "Synthetic alias leaked into the slot cache");
                        require(session.packets.size() == 1 && session.packets.getFirst() instanceof ServerboundSetCreativeModeSlotPacket, "Missing actual Creative slot packet");
                        ServerboundSetCreativeModeSlotPacket packet = (ServerboundSetCreativeModeSlotPacket) session.packets.getFirst();
                        require(packet.getSlot() == 36, "Incorrect Creative hotbar slot"); check(packet.getClickedItem(), entry, baseId, count);
                        JsonObject wire = encodeAndCheck(packet, entry, baseId, count); wire.addProperty("bedrock_protocol", version.getIntKey()); encoded.add(wire); selections++;

                        inventory.setItem(36, GeyserItemStack.EMPTY, session); session.packets.clear();
                        response = dispatchOne(session, request(index, data, count,
                                new PlaceAction(count, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.CURSOR, 0))));
                        require(response.getResult() == ItemStackResponseStatus.OK, "Actual Creative cursor request was rejected");
                        check(inventory.getCursor().getItemStack(), entry, baseId, count);
                        require(inventory.getCursor().getJavaId() != aliasIds.get(entry.path()) && session.packets.isEmpty(), "Alias/cursor packet leak");
                        inventory.setCursor(GeyserItemStack.EMPTY, session);

                        response = dispatchOne(session, request(index, data, count,
                                new DropAction(count, slot(ContainerSlotType.CREATED_OUTPUT, 50), false)));
                        require(response.getResult() == ItemStackResponseStatus.OK, "Actual Creative drop request was rejected");
                        require(session.packets.size() == 1 && session.packets.getFirst() instanceof ServerboundSetCreativeModeSlotPacket, "Missing Creative drop packet");
                        packet = (ServerboundSetCreativeModeSlotPacket) session.packets.getFirst();
                        require(packet.getSlot() == -1, "Incorrect Creative drop slot"); check(packet.getClickedItem(), entry, baseId, count);
                    }
                    // Untrusted alias tags must never replace the exact canonical Polymer identity.
                    ItemData forged = data.toBuilder().tag(NbtMap.builder().putString("forged", "ignored").build()).build();
                    check(ItemTranslator.translateToJava(session, forged), entry, baseId, 1);
                    session.mode = GameMode.SURVIVAL;
                    ItemStack survival = ItemTranslator.translateToJava(session, data);
                    require(survival == null || survival.getAmount() == 0, "Catalogue alias granted an item in Survival");
                    session.mode = GameMode.CREATIVE;
                    // The normal replacement sequence explicitly destroys an occupied slot first.
                    ItemMapping diamondMapping = mappings.getMapping("minecraft:diamond");
                    inventory.setItem(36, GeyserItemStack.from(session, ItemTranslator.translateToJava(session,
                            ItemData.builder().definition(diamondMapping.getBedrockDefinition()).count(1).build())), session);
                    inventory.setCursor(GeyserItemStack.EMPTY, session); session.packets.clear();
                    ItemStackRequest replacement = new ItemStackRequest(1, new ItemStackRequestAction[]{
                            new CraftCreativeAction(index + 1, 1), new CraftResultsDeprecatedAction(new ItemData[]{data}, 1),
                            new DestroyAction(1, slot(ContainerSlotType.HOTBAR, 0)),
                            new PlaceAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.HOTBAR, 0))}, new String[0]);
                    require(dispatchOne(session, replacement).getResult() == ItemStackResponseStatus.OK,
                            "Actual occupied-slot replacement sequence was rejected");
                    check(inventory.getItem(36).getItemStack(), entry, baseId, 1); replaced++;
                    if (entry.maxStack() > 1) {
                        inventory.setItem(36, GeyserItemStack.from(session, ItemTranslator.translateToJava(session, data)), session);
                        inventory.setCursor(GeyserItemStack.EMPTY, session); session.packets.clear();
                        ItemStackResponse response = dispatchOne(session, request(index, data, 1,
                                new PlaceAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.HOTBAR, 0))));
                        require(response.getResult() == ItemStackResponseStatus.OK, "Matching occupied hotbar request failed");
                        check(inventory.getItem(36).getItemStack(), entry, baseId, 2); occupied++;
                        inventory.setCursor(GeyserItemStack.from(session, ItemTranslator.translateToJava(session, data)), session);
                        response = dispatchOne(session, request(index, data, 1,
                                new PlaceAction(1, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.CURSOR, 0))));
                        require(response.getResult() == ItemStackResponseStatus.OK, "Matching occupied cursor request failed");
                        check(inventory.getCursor().getItemStack(), entry, baseId, 2); occupied++;
                    }
                    // Geyser's native translator adjusts count after alias translation. Preserve its behavior,
                    // but export the actual oversized packet for the authoritative Fabric rejection test.
                    inventory.setItem(36, GeyserItemStack.EMPTY, session); inventory.setCursor(GeyserItemStack.EMPTY, session); session.packets.clear();
                    int overflow = entry.maxStack() + 1;
                    ItemStackResponse response = dispatchOne(session, request(index, data, overflow,
                            new PlaceAction(overflow, slot(ContainerSlotType.CREATED_OUTPUT, 50), slot(ContainerSlotType.HOTBAR, 0))));
                    require(response.getResult() == ItemStackResponseStatus.OK && session.packets.size() == 1
                            && session.packets.getFirst() instanceof ServerboundSetCreativeModeSlotPacket, "Unexpected native overflow handling");
                    JsonObject wire = encodeAndCheck((ServerboundSetCreativeModeSlotPacket) session.packets.getFirst(), entry, baseId, overflow);
                    wire.addProperty("bedrock_protocol", version.getIntKey()); oversized.add(wire);
                    inventory.setItem(36, GeyserItemStack.EMPTY, session);
                }
                // A representative ordinary item retains its vanilla reverse mapping and has no Polymer identity.
                ItemMapping ordinary = mappings.getMapping("minecraft:diamond");
                ItemStack diamond = ItemTranslator.translateToJava(session, ItemData.builder().definition(ordinary.getBedrockDefinition()).count(1).build());
                require(diamond.getId() == ordinary.getJavaItem().javaId(), "Vanilla diamond reverse mapping changed");
                require(diamond.getDataComponentsPatch() == null || diamond.getDataComponentsPatch().get(DataComponentTypes.CUSTOM_DATA) == null, "Vanilla item received custom identity");
            }
        }
        result.addProperty("actual_creative_hotbar_selections", selections);
        result.addProperty("cursor_and_drop_cases", selections * 2);
        result.addProperty("matching_occupied_slot_and_cursor_cases", occupied);
        result.addProperty("occupied_different_item_replacement_cases", replaced);
        result.addProperty("wire_codec", "MCProtocolLib ServerboundSetCreativeModeSlotPacket 26.2; Via and Fabric tested separately");
        result.addProperty("overflow_behavior", "Native Geyser adds request count after translation; these packets require authoritative Fabric rejection");
        result.add("stale_request_guard", CreativeGuardProof.run(catalogue));
        result.addProperty("request_dispatch", "Installed PacketTranslatorRegistry guard → pinned BedrockItemStackRequestTranslator → native InventoryHolder");
        result.add("encoded_hotbar_packets", encoded); result.add("encoded_oversized_packets", oversized); return result;
    }

    private static ItemStackRequest request(int index, ItemData data, int count, ItemStackRequestAction action) {
        return new ItemStackRequest(1, new ItemStackRequestAction[]{new CraftCreativeAction(index + 1, 1),
                new CraftResultsDeprecatedAction(new ItemData[]{data.toBuilder().count(count).build()}, 1), action}, new String[0]);
    }
    private static ItemStackRequestSlotData slot(ContainerSlotType type, int slot) {
        return new ItemStackRequestSlotData(type, slot, 0, new FullContainerName(type, null));
    }
    private static void check(ItemStack item, Catalogue.Entry entry, int baseId, int count) {
        require(item != null && item.getId() == baseId && item.getAmount() == count, "Canonical base/count mismatch: " + entry.path());
        DataComponents patch = item.getDataComponentsPatch(); require(patch != null, "Missing canonical component patch");
        NbtMap custom = patch.get(DataComponentTypes.CUSTOM_DATA);
        require(custom != null && custom.getCompound("$polymer:stack").getString("id").equals(entry.canonical())
                && custom.getCompound("$polymer:stack").getInt("count") == 1 && custom.size() == 1, "Canonical Polymer identity mismatch");
        require(patch.get(DataComponentTypes.ITEM_MODEL).asString().equals(entry.nativeAppearance() ? entry.base() : entry.canonical()), "Canonical model mismatch");
        require(Component.text(entry.name()).equals(patch.get(DataComponentTypes.ITEM_NAME)), "Literal canonical wire name mismatch");
        require(patch.getDataComponents().size() == 3, "Alias definition components leaked onto the Java wire");
    }
    private static JsonObject encodeAndCheck(ServerboundSetCreativeModeSlotPacket packet, Catalogue.Entry entry, int baseId, int count) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            packet.serialize(buffer); byte[] bytes = new byte[buffer.readableBytes()]; buffer.getBytes(buffer.readerIndex(), bytes);
            ServerboundSetCreativeModeSlotPacket decoded = new ServerboundSetCreativeModeSlotPacket(buffer);
            require(!buffer.isReadable() && decoded.getSlot() == packet.getSlot(), "Creative packet codec did not consume the exact payload");
            check(decoded.getClickedItem(), entry, baseId, count);
            JsonObject result = new JsonObject(); result.addProperty("path", entry.path()); result.addProperty("vanilla_java_id", baseId);
            result.addProperty("count", count); result.addProperty("payload_base64", Base64.getEncoder().encodeToString(bytes)); return result;
        } finally { buffer.release(); }
    }
    private static void require(boolean passed, String message) { if (!passed) throw new IllegalStateException(message); }
    static List<ItemStackResponse> dispatch(CaptureSession session, List<ItemStackRequest> requests) {
        session.upstreamPackets.clear();
        ItemStackRequestPacket packet = new ItemStackRequestPacket(); packet.getRequests().addAll(requests);
        ByteBuf wire = Unpooled.buffer();
        try {
            var codec = session.getUpstream().getSession().getCodec();
            var helper = session.getUpstream().getCodecHelper();
            session.clientCodec.tryEncode(session.clientCodecHelper, wire, packet);
            packet = (ItemStackRequestPacket) codec.tryDecode(helper, wire,
                    codec.getPacketDefinition(ItemStackRequestPacket.class).getId(), PacketRecipient.SERVER);
            require(!wire.isReadable(), "The actual Bedrock request codec did not consume the exact payload");
        } finally { wire.release(); }
        require(Registries.BEDROCK_PACKET_TRANSLATORS.get(ItemStackRequestPacket.class) instanceof CreativeRequestGuard,
                "The actual installed packet dispatcher must contain the request guard");
        require(Registries.BEDROCK_PACKET_TRANSLATORS.translate(ItemStackRequestPacket.class, packet, session, true),
                "The actual registry rejected the open transport fixture");
        List<ItemStackResponsePacket> responses = session.upstreamPackets.stream()
                .filter(ItemStackResponsePacket.class::isInstance).map(ItemStackResponsePacket.class::cast).toList();
        require(responses.size() == 1 && responses.getFirst().getEntries().size() == requests.size(),
                "Native dispatcher did not produce the complete request response");
        List<ItemStackResponse> entries = responses.getFirst().getEntries();
        for (int i = 0; i < requests.size(); i++) require(entries.get(i).getRequestId() == requests.get(i).getRequestId(),
                "Native response ID/order differs from the incoming request batch");
        return entries;
    }
    private static ItemStackResponse dispatchOne(CaptureSession session, ItemStackRequest request) {
        return dispatch(session, List.of(request)).getFirst();
    }
    static final class CaptureSession extends GeyserSession implements AutoCloseable {
        final List<Packet> packets = new ArrayList<>();
        final List<BedrockPacket> upstreamPackets = new ArrayList<>();
        final EmbeddedChannel channel;
        final BedrockCodec clientCodec;
        final BedrockCodecHelper clientCodecHelper;
        GameMode mode = GameMode.CREATIVE;
        CaptureSession(ItemMappings mappings, int protocol) { this(mappings, transport(protocol)); }
        private CaptureSession(ItemMappings mappings, Transport transport) {
            super(GeyserImpl.getInstance(), transport.bedrock(), transport.channel().eventLoop());
            channel = transport.channel(); setItemMappings(mappings);
            setBlockMappings(BlockRegistries.BLOCKS.get(protocolVersion()));
            getUpstream().getCodecHelper().setItemDefinitions(mappings);
            getUpstream().getCodecHelper().setBlockDefinitions(getBlockMappings());
            // Geyser's processed codec rejects inventory packets sent by a client. Decode
            // its server output with the matching original Cloudburst client codec instead;
            // the installed server codec and its server-auth protections remain untouched.
            clientCodec = switch (protocolVersion()) {
                case 1001 -> Bedrock_v1001.CODEC;
                case 2168 -> Bedrock_v2168_hotfix4.CODEC;
                case 2193 -> Bedrock_v2193.CODEC;
                default -> throw new IllegalStateException("No pinned native client codec for protocol " + protocolVersion());
            };
            require(clientCodec.getProtocolVersion() == protocolVersion(), "Client/server protocol fixture mismatch");
            clientCodecHelper = clientCodec.createHelper();
            clientCodecHelper.setItemDefinitions(mappings);
            clientCodecHelper.setBlockDefinitions(getBlockMappings());
            clientCodecHelper.setEncodingSettings(EncodingSettings.CLIENT);
            // Use the holder and PlayerInventoryTranslator constructed by the actual Geyser session.
            setInventoryHolder(getPlayerInventoryHolder());
            require(getUpstream().getProtocolVersion() == protocolVersion() && !getUpstream().isClosed(),
                    "The transport must expose the actual configured Bedrock protocol and an open peer");
        }
        private static Transport transport(int protocol) {
            EmbeddedChannel channel = new EmbeddedChannel(new BedrockPacketCodec_v3());
            BedrockPeer peer = new BedrockPeer(channel, BedrockServerSession::new);
            BedrockServerSession bedrock = new BedrockServerSession(peer, 0);
            bedrock.setCodec(GameProtocol.getBedrockCodec(protocol));
            require(bedrock.getCodec().getProtocolVersion() == protocol, "No real codec for the requested protocol");
            return new Transport(channel, bedrock);
        }
        @Override public GameMode getGameMode() { return mode; }
        @Override public void sendDownstreamGamePacket(Packet packet) { packets.add(packet); }
        @Override public void sendUpstreamPacket(BedrockPacket packet) { upstreamPackets.add(packet); }
        @Override public void sendUpstreamPacketImmediately(BedrockPacket packet) { upstreamPackets.add(packet); }
        @Override public void close() { channel.finishAndReleaseAll(); }
        private record Transport(EmbeddedChannel channel, BedrockServerSession bedrock) { }
    }
}
