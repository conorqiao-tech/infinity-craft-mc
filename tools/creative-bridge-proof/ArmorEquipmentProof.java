package dev.infinityhub.proof;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v1001.Bedrock_v1001;
import org.cloudburstmc.protocol.bedrock.codec.v2168.Bedrock_v2168_hotfix4;
import org.cloudburstmc.protocol.bedrock.codec.v2193.Bedrock_v2193;
import org.cloudburstmc.protocol.bedrock.data.EncodingSettings;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.MobArmorEquipmentPacket;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomBlocksEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCustomItemsEvent;
import org.geysermc.geyser.configuration.GeyserConfig;
import org.geysermc.geyser.entity.VanillaEntities;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.network.bedrock.GameProtocol;
import org.geysermc.geyser.platform.standalone.GeyserStandaloneBootstrap;
import org.geysermc.geyser.registry.BlockRegistries;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.mappings.BuiltInMappings;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.cache.RegistryCache;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.geyser.translator.protocol.java.entity.JavaSetEquipmentTranslator;
import org.geysermc.geyser.translator.text.MessageTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.Equipment;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket;

/** Runs only local registries, embedded sessions and real translators/codecs. No listener,
 * authentication, asset fetch, live-world access or Bedrock rendering is started here. */
public final class ArmorEquipmentProof {
    private static final List<Integer> PROTOCOLS = List.of(1001, 2168, 2193);
    private static final List<String> SLOTS = List.of("head", "chest", "legs", "feet");
    private static final List<EquipmentSlot> JAVA_SLOTS = List.of(EquipmentSlot.HELMET,
            EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS);
    private static final List<List<String>> SUITS = List.of(
            List.of("helmet", "cuirass", "leggings", "boots"),
            List.of("aurora_helmet", "aurora_chestplate", "aurora_leggings", "aurora_boots"),
            List.of("ember_helmet", "ember_chestplate", "ember_leggings", "ember_boots"),
            List.of("elemental_helmet", "elemental_chestplate", "elemental_leggings", "elemental_boots"),
            List.of("infinity_helmet", "infinity_chestplate", "infinity_leggings", "infinity_boots"));

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Expected isolated working directory and output receipt");
        Path work = Path.of(args[0]).toAbsolutePath();
        OfflineBootstrap bootstrap = new OfflineBootstrap(work);
        org.geysermc.geyser.text.GeyserLocale.init(bootstrap);
        GeyserImpl geyser = GeyserImpl.load(bootstrap);
        // This is the registry segment of pinned GeyserImpl.initialize, deliberately
        // excluding its asset-cache fetch, authentication and network startup.
        geyser.eventBus().subscribe(geyser, GeyserDefineCustomBlocksEvent.class, BuiltInMappings::registerBlocks);
        geyser.eventBus().subscribe(geyser, GeyserDefineCustomItemsEvent.class, BuiltInMappings::registerItems);
        Registries.load(); BlockRegistries.populate(); Registries.populate();
        RegistryCache.init(); VanillaEntities.init(); MessageTranslator.init();
        Registries.RESOURCE_PACKS.load(); Registries.WAYPOINT_STYLE_MAPPINGS.load();
        geyser.eventBus().fire(new GeyserPostInitializeEvent(geyser.extensionManager(), geyser.eventBus()));
        Path failed = work.resolve("extensions/infinitycreativebridge/failed.json");
        require(!Files.exists(failed), "Actual bridge failed initialization: " + (Files.exists(failed) ? Files.readString(failed) : ""));
        require(Files.exists(work.resolve("extensions/infinitycreativebridge/ready.json")), "Actual bridge did not register and finish");
        require(Registries.ITEMS.get().keySet().equals(new java.util.HashSet<>(PROTOCOLS)), "Unexpected pinned protocol set");
        Map<String, JsonObject> entries = new HashMap<>();
        for (var element : JsonParser.parseString(Files.readString(work.resolve("infinity-catalogue.json"))).getAsJsonObject().getAsJsonArray("items")) {
            JsonObject entry = element.getAsJsonObject(); entries.put(entry.get("path").getAsString(), entry);
        }
        JsonArray observations = new JsonArray(); int definitionCases = 0, fullSuitCases = 0, singleSlotCases = 0, clearCases = 0, componentPackets = 0;
        for (int protocol : PROTOCOLS) {
            ItemMappings mappings = Registries.ITEMS.get().get(protocol);
            try (CaptureSession session = new CaptureSession(mappings, protocol)) {
                session.getPlayerEntity().setEntityId(71); session.getPlayerEntity().setValid(true);
                require(session.getEntityCache().cacheEntity(session.getPlayerEntity()), "Native player entity cache refused test identity");
                ItemComponentPacket component = new ItemComponentPacket(); component.getItems().addAll(mappings.getItemDefinitions().values());
                ItemComponentPacket decodedDefinitions = (ItemComponentPacket) wire(session, component); componentPackets++;
                Map<String, ItemDefinition> definitions = new HashMap<>();
                for (ItemDefinition d : decodedDefinitions.getItems()) require(definitions.put(d.getIdentifier(), d) == null, "Duplicate published definition");
                for (List<String> suit : SUITS) {
                    for (int slot = 0; slot < 4; slot++) {
                        JsonObject entry = entry(entries, suit.get(slot));
                        require(entry.get("slot").getAsString().equals(SLOTS.get(slot)), "Catalogue armor slot mismatch");
                        for (String namespace : List.of("convergence:", "infinity_catalog:")) {
                            String identifier = namespace + suit.get(slot);
                            verifyDefinition(definitions.get(identifier), SLOTS.get(slot), entry); definitionCases++;
                        }
                    }
                    for (boolean fromCreative : List.of(false, true)) {
                        Equipment[] all = new Equipment[4];
                        for (int slot = 0; slot < 4; slot++) all[slot] = new Equipment(JAVA_SLOTS.get(slot), stack(session, entry(entries, suit.get(slot)), fromCreative));
                        MobArmorEquipmentPacket actual = dispatch(session, all);
                        JsonObject observed = new JsonObject(); observed.addProperty("protocol", protocol); observed.addProperty("source", fromCreative ? "native_creative_reverse_translation" : "canonical_model_stack");
                        JsonObject emitted = new JsonObject();
                        for (int slot = 0; slot < 4; slot++) {
                            String id = "convergence:" + suit.get(slot); ItemData item = armor(actual, slot);
                            require(item.getCount() == 1 && item.getDefinition().getIdentifier().equals(id), "Missing/wrong full armor slot: " + protocol + "/" + SLOTS.get(slot) + "/" + id);
                            require(definitions.get(id).getRuntimeId() == item.getDefinition().getRuntimeId(), "Armor references an unpublished runtime definition");
                            emitted.addProperty(SLOTS.get(slot), item.getDefinition().getIdentifier());
                        }
                        observed.add("equipment", emitted);
                        observed.addProperty("encoded_armor_payload", java.util.Base64.getEncoder().encodeToString(session.lastPayload));
                        observations.add(observed); fullSuitCases++;
                        // Independent per-slot transitions catch a missing head/legs/feet setter,
                        // cross-slot swaps and stale previous armor concealed by full-suit checks.
                        for (int slot = 0; slot < 4; slot++) {
                            dispatch(session, emptyEquipment());
                            actual = dispatch(session, new Equipment[]{all[slot]});
                            for (int other = 0; other < 4; other++) {
                                ItemData item = armor(actual, other);
                                require(other == slot ? item.getCount() == 1 && item.getDefinition().getIdentifier().equals("convergence:" + suit.get(slot)) : item.getCount() == 0,
                                        "Independent armor slot or clear mismatch: " + protocol + "/" + slot + "/" + other);
                            }
                            singleSlotCases++;
                        }
                        actual = dispatch(session, emptyEquipment());
                        for (int slot = 0; slot < 4; slot++) require(armor(actual, slot).getCount() == 0, "Unequipped armor retained on wire");
                        clearCases++;
                    }
                }
            }
        }
        require(definitionCases == 120 && fullSuitCases == 30 && singleSlotCases == 120 && clearCases == 30 && componentPackets == 3, "Incomplete armor proof coverage");
        JsonObject receipt = new JsonObject(); receipt.addProperty("format_version", 1);
        receipt.add("protocols", new com.google.gson.Gson().toJsonTree(PROTOCOLS)); receipt.addProperty("suits", 5);
        receipt.addProperty("armor_identities", 20); receipt.addProperty("canonical_and_alias_definition_cases", definitionCases);
        receipt.addProperty("native_login_component_packets", componentPackets); receipt.addProperty("native_full_suit_packets", fullSuitCases);
        receipt.addProperty("native_independent_slot_packets", singleSlotCases); receipt.addProperty("native_clear_packets", clearCases);
        receipt.addProperty("native_intermediate_clear_packets", singleSlotCases);
        receipt.addProperty("wire_roundtrips", componentPackets + fullSuitCases + singleSlotCases * 2 + clearCases);
        receipt.addProperty("device_rendering_verified", false); receipt.addProperty("network_listener_started", false);
        receipt.addProperty("scope", "Actual pinned Geyser Java equipment translator, canonical/Creative reverse translation, wearable definitions and native Bedrock armor codecs. No client rendering/Molang/skin proof.");
        receipt.add("full_suit_observations", observations);
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(receipt) + "\n");
        System.out.println("ARMOR_EQUIPMENT_PROOF_PASSED");
        // Native registry initialization may create shared daemon/resource executors;
        // no Geyser network lifecycle was started or disabled by this fixture.
        System.exit(0);
    }

    private static JsonObject entry(Map<String, JsonObject> entries, String path) { JsonObject result = entries.get(path); require(result != null, "Missing expected armor: " + path); return result; }
    private static Equipment[] emptyEquipment() { return JAVA_SLOTS.stream().map(s -> new Equipment(s, null)).toArray(Equipment[]::new); }
    private static ItemStack stack(CaptureSession session, JsonObject entry, boolean fromCreative) {
        String path = entry.get("path").getAsString(), base = entry.get("base").getAsString();
        if (fromCreative) {
            ItemData alias = session.getItemMappings().getCreativeItems().stream().map(c -> c.getItem()).filter(i -> i.getDefinition().getIdentifier().equals("infinity_catalog:" + path)).findFirst().orElseThrow();
            ItemStack actual = ItemTranslator.translateToJava(session, alias);
            require(actual != null && actual.getId() == session.getItemMappings().getMapping(base).getJavaItem().javaId(), "Actual Creative alias did not canonicalize to its real base");
            return actual;
        }
        DataComponents patch = new DataComponents(new HashMap<>());
        patch.put(DataComponentTypes.ITEM_NAME, Component.text(entry.get("name").getAsString()));
        patch.put(DataComponentTypes.ITEM_MODEL, Key.key("convergence:" + path));
        patch.put(DataComponentTypes.CUSTOM_DATA, NbtMap.builder().putCompound("$polymer:stack", NbtMap.builder().putString("id", "convergence:" + path).putInt("count", 1).build()).build());
        return new ItemStack(session.getItemMappings().getMapping(base).getJavaItem().javaId(), 1, patch);
    }
    private static void verifyDefinition(ItemDefinition definition, String slot, JsonObject entry) {
        require(definition != null && definition.isComponentBased(), "Missing component-based armor definition");
        NbtMap root = definition.getComponentData(); NbtMap components = root.getCompound("components");
        require(root.getString("name").equals(definition.getIdentifier()) && root.getInt("id") == definition.getRuntimeId(), "Armor definition root identity differs");
        require(components.getCompound("minecraft:wearable").getString("slot").equals("slot.armor." + slot), "Native wearable slot differs: " + definition.getIdentifier() + "/" + components);
        require(components.getCompound("minecraft:wearable").getInt("protection") == entry.get("protection").getAsInt(), "Armor protection definition differs");
        require(components.getCompound("item_properties").getCompound("minecraft:icon").getCompound("textures").getString("default").equals(entry.get("icon").getAsString()), "Armor icon definition differs");
        require(components.getCompound("item_properties").getBoolean("allow_off_hand"), "Native wearable definition lost actual components");
    }
    private static MobArmorEquipmentPacket dispatch(CaptureSession session, Equipment[] equipment) throws Exception {
        ClientboundSetEquipmentPacket original = new ClientboundSetEquipmentPacket(71, equipment); ByteBuf input = Unpooled.buffer();
        try {
            original.serialize(input); ClientboundSetEquipmentPacket decoded = new ClientboundSetEquipmentPacket(input);
            require(!input.isReadable() && original.equals(decoded), "Native Java armor equipment codec changed input");
            session.upstreamPackets.clear(); new JavaSetEquipmentTranslator().translate(session, decoded);
        } finally { input.release(); }
        List<MobArmorEquipmentPacket> armor = session.upstreamPackets.stream().filter(MobArmorEquipmentPacket.class::isInstance).map(MobArmorEquipmentPacket.class::cast).toList();
        require(armor.size() == 1, "Native equipment translator did not emit exactly one armor packet");
        MobArmorEquipmentPacket published = armor.getFirst(); MobArmorEquipmentPacket received = (MobArmorEquipmentPacket) wire(session, published);
        require(received.equals(published), "Complete native armor packet changed on Bedrock wire");
        require(received.getRuntimeEntityId() == session.getPlayerEntity().geyserId(), "Armor packet references the wrong entity"); return received;
    }
    private static ItemData armor(MobArmorEquipmentPacket p, int slot) { return switch(slot) { case 0 -> p.getHelmet(); case 1 -> p.getChestplate(); case 2 -> p.getLeggings(); case 3 -> p.getBoots(); default -> throw new IllegalArgumentException(); }; }
    private static BedrockPacket wire(CaptureSession session, BedrockPacket original) throws Exception {
        ByteBuf payload = Unpooled.buffer();
        try {
            var codec = session.getUpstream().getSession().getCodec(); codec.tryEncode(session.getUpstream().getCodecHelper(), payload, original);
            session.lastPayload = new byte[payload.readableBytes()]; payload.getBytes(payload.readerIndex(), session.lastPayload);
            int id = session.clientCodec.getPacketDefinition(original.getClass()).getId();
            require(codec.getPacketDefinition(original.getClass()).getId() == id, "Native client/server packet ID mismatch");
            BedrockPacket actual = session.clientCodec.tryDecode(session.clientHelper, payload, id, PacketRecipient.CLIENT);
            require(!payload.isReadable() && actual.getClass() == original.getClass(), "Native Bedrock armor decoder did not consume complete packet"); return actual;
        } finally { payload.release(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static final class OfflineBootstrap extends GeyserStandaloneBootstrap {
        private final Path work; private final GeyserConfig configuration;
        OfflineBootstrap(Path work) { this.work = work; configuration = super.loadConfig(GeyserConfig.class); }
        @Override public Path getConfigFolder() { return work; }
        @Override public GeyserConfig config() { return configuration; }
    }
    private static final class CaptureSession extends GeyserSession implements AutoCloseable {
        final List<BedrockPacket> upstreamPackets = new ArrayList<>(); final EmbeddedChannel channel;
        final BedrockCodec clientCodec; final BedrockCodecHelper clientHelper;
        byte[] lastPayload;
        CaptureSession(ItemMappings mappings, int protocol) { this(mappings, transport(protocol)); }
        private CaptureSession(ItemMappings mappings, Transport transport) {
            super(GeyserImpl.getInstance(), transport.session, transport.channel.eventLoop()); channel = transport.channel;
            setItemMappings(mappings); setBlockMappings(BlockRegistries.BLOCKS.get(protocolVersion()));
            getUpstream().getCodecHelper().setItemDefinitions(mappings); getUpstream().getCodecHelper().setBlockDefinitions(getBlockMappings());
            clientCodec = switch(protocolVersion()) { case 1001 -> Bedrock_v1001.CODEC; case 2168 -> Bedrock_v2168_hotfix4.CODEC; case 2193 -> Bedrock_v2193.CODEC; default -> throw new IllegalStateException("Unexpected codec"); };
            clientHelper = clientCodec.createHelper(); clientHelper.setItemDefinitions(mappings); clientHelper.setBlockDefinitions(getBlockMappings()); clientHelper.setEncodingSettings(EncodingSettings.CLIENT);
            setInventoryHolder(getPlayerInventoryHolder());
        }
        @Override public GameMode getGameMode() { return GameMode.CREATIVE; }
        @Override public void sendDownstreamGamePacket(Packet packet) { throw new IllegalStateException("Offline armor proof must not send Java packets"); }
        @Override public void sendUpstreamPacket(BedrockPacket packet) { upstreamPackets.add(packet); }
        @Override public void sendUpstreamPacketImmediately(BedrockPacket packet) { upstreamPackets.add(packet); }
        @Override public void close() { channel.finishAndReleaseAll(); }
        private static Transport transport(int protocol) { EmbeddedChannel c = new EmbeddedChannel(new BedrockPacketCodec_v3()); BedrockPeer peer = new BedrockPeer(c, BedrockServerSession::new); BedrockServerSession s = new BedrockServerSession(peer, 0); s.setCodec(GameProtocol.getBedrockCodec(protocol)); return new Transport(c, s); }
        private record Transport(EmbeddedChannel channel, BedrockServerSession session) { }
    }
}
