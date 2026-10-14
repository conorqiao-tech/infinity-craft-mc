package dev.infinityhub.geyser;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.PacketRecipient;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType;
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag;
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryContentPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket;
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlaySoundPacket;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.registry.type.ItemMappings;
import org.geysermc.geyser.translator.item.ItemTranslator;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EntityEvent;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundSetCreativeModeSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

/** Real native translators and codecs, with capture only at the transport boundary. This does
 * not simulate an authenticated Bedrock client, render an attachable, or replace Java ammo rules.
 */
final class OwnedEquipmentProof {
    /** Mandatory startup check of the registry consumed by GeyserSession.connect, and actual
     * forward item model selection. It is safe before Creative aliases are canonicalized.
     */
    static int[] verifyDefinitions(Catalogue catalogue, OwnedEquipmentSupport support) throws Exception {
        support.verifyUnowned(); int componentPackets = 0, modelCases = 0, baselinePackets = 0;
        for (var state : support.protocols.values()) {
            try (var session = new CreativeProtocolProof.CaptureSession(state.mappings, state.protocol)) {
                // This is exactly the native login ItemComponentPacket payload construction.
                // The newer protocol's item definitions are published here, not in StartGame.
                ItemComponentPacket published = new ItemComponentPacket();
                published.getItems().addAll(state.mappings.getItemDefinitions().values());
                // Keep the actual pre-adapter registry as a native codec baseline. The
                // pinned v776 serializer writes an empty compound for null component
                // data, including Geyser's unowned custom-block fallback definitions.
                // Comparing raw definition objects would falsely reject that wire form.
                ItemComponentPacket untouched = new ItemComponentPacket();
                untouched.getItems().addAll(state.definitions.values());
                ItemComponentPacket baseline = (ItemComponentPacket) wire(session, untouched); baselinePackets++;
                Map<Integer, ItemDefinition> nativeBaseline = definitionsById(baseline);
                require(nativeBaseline.size() == state.definitions.size(), "Untouched native login definition count changed on wire");
                for (ItemDefinition original : untouched.getItems())
                    requireWireDefinition(state.protocol, original, nativeBaseline.get(original.getRuntimeId()), "untouched baseline");
                ItemComponentPacket decoded = (ItemComponentPacket) wire(session, published); componentPackets++;
                require(decoded.getItems().size() == published.getItems().size(), "Login item definition count changed on wire");
                Map<Integer, ItemDefinition> received = definitionsById(decoded);
                for (ItemDefinition definition : published.getItems()) {
                    ItemDefinition actual = received.get(definition.getRuntimeId());
                    requireWireDefinition(state.protocol, definition, actual, "owned adapter publication");
                    boolean owned = state.changes.stream().anyMatch(c -> c.replacement().itemDefinition() == definition);
                    if (!owned) require(Objects.equals(actual, nativeBaseline.get(definition.getRuntimeId())),
                            "Complete unowned login components differ from the real untouched codec baseline: protocol=" + state.protocol
                                    + "; " + definitionDifference(nativeBaseline.get(definition.getRuntimeId()), actual));
                }
                for (var change : state.changes) {
                    var definition = (SimpleItemDefinition) received.get(change.replacement().itemDefinition().getRuntimeId());
                    OwnedEquipmentSupport.validateDefinition(change.entry(), definition, true);
                    ItemStack canonical = canonical(session, change.entry());
                    ItemData owned = ItemTranslator.translateToBedrock(session, canonical);
                    require(owned.getDefinition() == change.replacement().itemDefinition()
                            && owned.getDefinition().getIdentifier().equals(change.entry().canonical()), "Canonical original model selection failed");
                    require(owned.getTag() != null && owned.getTag().getCompound("display").getString("Name").contains(change.entry().name()),
                            "Canonical original model lost its literal gear name");
                    ItemData ordinary = ItemTranslator.translateToBedrock(session, ordinary(session, change.entry().base()));
                    require(ordinary.getDefinition() == change.mapping().getBedrockDefinition()
                            && ordinary.getDefinition().getIdentifier().equals(change.entry().base()), "Ordinary vanilla model selection changed");
                    modelCases++;
                }
                for (String path : OwnedEquipmentSupport.NATIVE_ARROWS) {
                    Catalogue.Entry entry = entry(catalogue, path);
                    ItemData arrow = ItemTranslator.translateToBedrock(session, canonical(session, entry));
                    require(arrow.getDefinition() == state.mappings.getMapping("minecraft:arrow").getBedrockDefinition()
                            && arrow.getDefinition().getIdentifier().equals("minecraft:arrow"), "Custom arrow broke the native ammunition ID");
                }
            }
        }
        support.verifyUnowned(); return new int[]{componentPackets, modelCases, baselinePackets};
    }

    static JsonObject run(Catalogue catalogue, OwnedEquipmentSupport support) throws Exception {
        int[] mandatory = verifyDefinitions(catalogue, support);
        int slotCases = 0, chargedCases = 0, rangedCases = 0, shieldCases = 0, mixedShieldCases = 0, totemCases = 0, arrowCases = 0, wirePackets = mandatory[0] + mandatory[2];
        for (var state : support.protocols.values()) {
            try (var session = new CreativeProtocolProof.CaptureSession(state.mappings, state.protocol)) {
                for (var change : state.changes) {
                    verifySlot(session, canonical(session, change.entry()), change.entry().canonical()); slotCases++; wirePackets++;
                }
                for (String path : OwnedEquipmentSupport.NATIVE_ARROWS) {
                    verifySlot(session, canonical(session, entry(catalogue, path)), "minecraft:arrow"); arrowCases++; wirePackets++;
                }
                Catalogue.Entry crossbow = support.entries.get("crossbow");
                List<ItemStack> projectiles = new ArrayList<>(); projectiles.add(ordinary(session, "minecraft:arrow"));
                for (String path : OwnedEquipmentSupport.NATIVE_ARROWS) projectiles.add(canonical(session, entry(catalogue, path)));
                projectiles.add(ordinary(session, "minecraft:firework_rocket"));
                for (ItemStack projectile : projectiles) {
                    ItemStack charged = canonical(session, crossbow);
                    charged.getDataComponentsPatch().put(DataComponentTypes.CHARGED_PROJECTILES, List.of(projectile));
                    ItemData expectedProjectile = ItemTranslator.translateToBedrock(session, projectile);
                    ItemData actual = verifySlot(session, charged, crossbow.canonical()); slotCases++; wirePackets++;
                    require(actual.getTag() != null, "Charged crossbow NBT missing"); NbtMap nested = actual.getTag().getCompound("chargedItem");
                    require(nested.getString("Name").equals(expectedProjectile.getDefinition().getIdentifier())
                            && nested.getByte("Count") == expectedProjectile.getCount() && nested.getShort("Damage") == expectedProjectile.getDamage()
                            && Objects.equals(nested.getCompound("tag", null), expectedProjectile.getTag()), "Native charged projectile lost identity/components");
                    require(session.getPlayerInventory().getItem(36).getItemStack().getDataComponentsPatch().get(DataComponentTypes.CHARGED_PROJECTILES).equals(List.of(projectile)),
                            "Complete charged projectile Java components changed");
                    chargedCases++;
                }
                ItemStack cleared = canonical(session, crossbow); cleared.getDataComponentsPatch().put(DataComponentTypes.CHARGED_PROJECTILES, List.of());
                ItemData clearData = verifySlot(session, cleared, crossbow.canonical()); slotCases++; wirePackets++;
                require(clearData.getTag() == null || !clearData.getTag().containsKey("chargedItem"), "Empty charge retained a stale projectile"); chargedCases++;

                // Native use/release routing has no client-side ammo fabrication. Java's actual
                // Creative ammo-free loading, Survival consumption and rocket rules are separate
                // native Fabric game tests; this fixture proves the native Geyser control path.
                for (GameMode mode : new GameMode[]{GameMode.CREATIVE, GameMode.SURVIVAL}) {
                    session.mode = mode;
                    for (String path : List.of("bow", "crossbow")) for (boolean custom : new boolean[]{false, true}) {
                        resetHands(session);
                        ItemStack held = custom ? canonical(session, support.entries.get(path)) : ordinary(session, OwnedEquipmentSupport.BASES.get(path));
                        session.getPlayerInventory().setItem(36, GeyserItemStack.from(session, held), session);
                        Snapshot before = Snapshot.capture(session.getPlayerInventory().getItem(36)); session.packets.clear();
                        session.useItem(Hand.MAIN_HAND);
                        require(session.packets.size() == 1 && session.packets.getFirst() instanceof ServerboundUseItemPacket,
                                "Native ranged use did not route to the Java server");
                        ServerboundUseItemPacket used = (ServerboundUseItemPacket) session.packets.getFirst();
                        require(used.getHand() == Hand.MAIN_HAND, "Native ranged use selected the wrong hand"); verifyJavaUse(used);
                        session.packets.clear(); session.releaseItem();
                        require(session.packets.size() == 1 && session.packets.getFirst() instanceof ServerboundPlayerActionPacket,
                                "Native ranged release did not route to the Java server");
                        ServerboundPlayerActionPacket released = (ServerboundPlayerActionPacket) session.packets.getFirst();
                        require(released.getAction() == PlayerAction.RELEASE_USE_ITEM, "Native ranged release action changed"); verifyJavaRelease(released);
                        before.requireSame(session.getPlayerInventory().getItem(36), "ranged controls"); rangedCases++;
                    }
                }
                session.mode = GameMode.SURVIVAL;
                for (Hand hand : new Hand[]{Hand.MAIN_HAND, Hand.OFF_HAND}) for (boolean custom : new boolean[]{false, true}) {
                    resetHands(session); int slot = hand == Hand.MAIN_HAND ? 36 : 45;
                    ItemStack shield = custom ? canonical(session, support.entries.get("shield")) : ordinary(session, "minecraft:shield");
                    session.getPlayerInventory().setItem(slot, GeyserItemStack.from(session, shield), session);
                    Snapshot before = Snapshot.capture(session.getPlayerInventory().getItem(slot)); session.packets.clear();
                    require(session.attemptToBlock() && session.getPlayerEntity().getFlag(EntityFlag.BLOCKING), "Native shield block was not recognized");
                    require(session.packets.size() == 1 && session.packets.getFirst() instanceof ServerboundUseItemPacket,
                            "Native shield block did not send the actual use packet");
                    ServerboundUseItemPacket used = (ServerboundUseItemPacket) session.packets.getFirst();
                    require(used.getHand() == hand, "Native shield block selected the wrong hand"); verifyJavaUse(used);
                    before.requireSame(session.getPlayerInventory().getItem(slot), "shield block"); shieldCases++;
                }
                // The pinned native method prioritizes a shield in the main hand, even
                // when the opposite hand holds a shield with a different Bedrock model.
                for (boolean[] customHands : new boolean[][]{{true, false}, {false, true}, {true, true}}) {
                    resetHands(session);
                    ItemStack main = customHands[0] ? canonical(session, support.entries.get("shield")) : ordinary(session, "minecraft:shield");
                    ItemStack off = customHands[1] ? canonical(session, support.entries.get("shield")) : ordinary(session, "minecraft:shield");
                    session.getPlayerInventory().setItem(36, GeyserItemStack.from(session, main), session);
                    session.getPlayerInventory().setItem(45, GeyserItemStack.from(session, off), session);
                    Snapshot beforeMain = Snapshot.capture(session.getPlayerInventory().getItem(36));
                    Snapshot beforeOff = Snapshot.capture(session.getPlayerInventory().getItem(45));
                    session.packets.clear();
                    require(session.attemptToBlock() && session.getPlayerEntity().getFlag(EntityFlag.BLOCKING), "Native mixed shield block was not recognized");
                    require(session.packets.size() == 1 && session.packets.getFirst() instanceof ServerboundUseItemPacket,
                            "Native mixed shield block did not send its single actual use packet");
                    ServerboundUseItemPacket used = (ServerboundUseItemPacket) session.packets.getFirst();
                    require(used.getHand() == Hand.MAIN_HAND, "Native mixed shield block lost main-hand priority"); verifyJavaUse(used);
                    beforeMain.requireSame(session.getPlayerInventory().getItem(36), "mixed shield main hand");
                    beforeOff.requireSame(session.getPlayerInventory().getItem(45), "mixed shield offhand");
                    shieldCases++; mixedShieldCases++;
                }
                for (Hand hand : new Hand[]{Hand.MAIN_HAND, Hand.OFF_HAND}) for (boolean custom : new boolean[]{false, true}) {
                    resetHands(session); int slot = hand == Hand.MAIN_HAND ? 36 : 45;
                    ItemStack totem = custom ? canonical(session, support.entries.get("totem")) : ordinary(session, "minecraft:totem_of_undying");
                    session.getPlayerInventory().setItem(slot, GeyserItemStack.from(session, totem), session);
                    Snapshot before = Snapshot.capture(session.getPlayerInventory().getItem(slot)); session.upstreamPackets.clear();
                    ClientboundEntityEventPacket event = new ClientboundEntityEventPacket(session.getPlayerEntity().javaId(), EntityEvent.TOTEM_OF_UNDYING_MAKE_SOUND);
                    ByteBuf payload = Unpooled.buffer();
                    try { event.serialize(payload); event = new ClientboundEntityEventPacket(payload); require(!payload.isReadable(), "Java totem event codec left unread bytes"); }
                    finally { payload.release(); }
                    require(Registries.JAVA_PACKET_TRANSLATORS.translate(ClientboundEntityEventPacket.class, event, session, true), "Actual Java totem dispatcher rejected fixture");
                    require(session.upstreamPackets.size() == 2 && session.upstreamPackets.stream().noneMatch(p -> p instanceof InventoryContentPacket || p instanceof InventorySlotPacket),
                            "Native totem event replaced an already held canonical totem");
                    boolean sound = false, consume = false;
                    for (BedrockPacket emitted : session.upstreamPackets) {
                        BedrockPacket actual = wire(session, emitted); wirePackets++;
                        if (actual instanceof PlaySoundPacket p) sound = p.getSound().equals("random.totem");
                        if (actual instanceof EntityEventPacket p) consume = p.getType() == EntityEventType.CONSUME_TOTEM;
                    }
                    require(sound && consume, "Native held totem consume event or sound missing");
                    before.requireSame(session.getPlayerInventory().getItem(slot), "totem event"); totemCases++;
                }
            }
        }
        support.verifyUnowned();
        require(mandatory[0] == 3 && mandatory[1] == 12 && mandatory[2] == 3 && slotCases == 30 && arrowCases == 9
                && chargedCases == 18 && rangedCases == 24 && shieldCases == 21 && mixedShieldCases == 9 && totemCases == 12 && wirePackets == 69,
                "Owned equipment native proof case counts incomplete");
        JsonObject receipt = new JsonObject();
        receipt.addProperty("component_packet_roundtrips", mandatory[0]); receipt.addProperty("owned_model_cases", mandatory[1]);
        receipt.addProperty("untouched_baseline_component_packet_roundtrips", mandatory[2]);
        receipt.addProperty("complete_unowned_wire_components_match_native_baseline", true);
        receipt.addProperty("native_inventory_slot_cases", slotCases); receipt.addProperty("native_arrow_fallback_cases", arrowCases);
        receipt.addProperty("charged_projectile_and_empty_cases", chargedCases); receipt.addProperty("native_ranged_use_release_cases", rangedCases);
        receipt.addProperty("native_shield_main_and_offhand_cases", shieldCases); receipt.addProperty("native_totem_main_and_offhand_cases", totemCases);
        receipt.addProperty("native_mixed_shield_cases", mixedShieldCases);
        receipt.addProperty("bedrock_wire_packets", wirePackets); receipt.addProperty("ordinary_vanilla_definitions_unchanged", true);
        receipt.addProperty("complete_cached_components_and_network_ids_preserved", true);
        receipt.addProperty("native_arrow_identity_preserved", true); receipt.addProperty("charged_projectile_retention", true);
        receipt.addProperty("creative_arrow_ammunition_component", true); receipt.addProperty("crossbow_offhand_rocket_ammunition_component", true);
        receipt.addProperty("java_ammo_rules_proven_here", false); receipt.addProperty("device_input_or_rendering_proven_here", false);
        receipt.addProperty("scope", "Native pinned Geyser item selection, component publication, inventory codecs, use/release, shield blocking and held-totem event routing; Fabric mechanics are verified separately; authenticated device input and rendering still require testing");
        return receipt;
    }

    private static ItemData verifySlot(CreativeProtocolProof.CaptureSession session, ItemStack item, String identifier) throws Exception {
        resetHands(session); session.getPlayerInventory().setItem(36, GeyserItemStack.from(session, item), session);
        GeyserItemStack cached = session.getPlayerInventory().getItem(36); Snapshot before = Snapshot.capture(cached);
        ItemData expected = cached.getItemData(session); require(expected.getDefinition().getIdentifier().equals(identifier), "Original item model lost on cache projection");
        session.upstreamPackets.clear(); session.getPlayerInventoryHolder().updateSlot(36);
        require(session.upstreamPackets.size() == 1 && session.upstreamPackets.getFirst() instanceof InventorySlotPacket,
                "Actual PlayerInventoryTranslator did not emit its native slot packet");
        InventorySlotPacket packet = (InventorySlotPacket) wire(session, session.upstreamPackets.getFirst());
        require(packet.getSlot() == 0 && expected.equals(packet.getItem()), "Full native inventory item changed on wire");
        before.requireSame(session.getPlayerInventory().getItem(36), "inventory slot projection"); return packet.getItem();
    }

    private static void resetHands(CreativeProtocolProof.CaptureSession session) {
        session.getPlayerEntity().setFlag(EntityFlag.BLOCKING, false); session.getPlayerEntity().setFlag(EntityFlag.USING_ITEM, false);
        session.setLastChargedProjectilesTime(0); session.getPlayerInventory().setItem(36, GeyserItemStack.EMPTY, session);
        session.getPlayerInventory().setItem(45, GeyserItemStack.EMPTY, session);
    }
    private static Map<Integer, ItemDefinition> definitionsById(ItemComponentPacket packet) {
        Map<Integer, ItemDefinition> result = new HashMap<>();
        for (ItemDefinition definition : packet.getItems()) require(result.put(definition.getRuntimeId(), definition) == null,
                "Duplicate native login definition runtime ID");
        return result;
    }
    private static void requireWireDefinition(int protocol, ItemDefinition expected, ItemDefinition actual, String stage) {
        boolean metadata = actual != null && expected.getIdentifier().equals(actual.getIdentifier())
                && expected.getRuntimeId() == actual.getRuntimeId() && expected.getVersion() == actual.getVersion()
                && expected.isComponentBased() == actual.isComponentBased();
        NbtMap expectedNbt = expected.getComponentData();
        // This is the exact pinned serializer's only NBT normalization. Nonempty
        // components still compare in full; all ordinary entries additionally match
        // a separately encoded/decoded untouched native packet above.
        NbtMap encodedNbt = expectedNbt == null ? NbtMap.EMPTY : expectedNbt;
        require(metadata && encodedNbt.equals(actual.getComponentData()),
                "Complete native login definition changed on wire at " + stage + ": protocol=" + protocol
                        + "; " + definitionDifference(expected, actual));
    }
    private static String definitionDifference(ItemDefinition expected, ItemDefinition actual) {
        return "expected=" + definitionDetails(expected) + "; actual=" + definitionDetails(actual);
    }
    private static String definitionDetails(ItemDefinition definition) {
        if (definition == null) return "missing";
        String nbt = String.valueOf(definition.getComponentData());
        if (nbt.length() > 2048) nbt = nbt.substring(0, 2048) + " [bounded diagnostic]";
        return definition.getIdentifier() + "{runtime=" + definition.getRuntimeId() + ",version=" + definition.getVersion()
                + ",componentBased=" + definition.isComponentBased() + ",NBT=" + nbt + "}";
    }
    private static Catalogue.Entry entry(Catalogue catalogue, String path) { return catalogue.items.stream().filter(e -> e.path().equals(path)).findFirst().orElseThrow(); }
    private static ItemStack ordinary(CreativeProtocolProof.CaptureSession session, String base) { return new ItemStack(session.getItemMappings().getMapping(base).getJavaItem().javaId(), 1); }
    private static ItemStack canonical(CreativeProtocolProof.CaptureSession session, Catalogue.Entry entry) {
        DataComponents patch = new DataComponents(new HashMap<>());
        patch.put(DataComponentTypes.ITEM_NAME, Component.text(entry.name()));
        patch.put(DataComponentTypes.ITEM_MODEL, Key.key(entry.nativeAppearance() ? entry.base() : entry.canonical()));
        patch.put(DataComponentTypes.CUSTOM_DATA, NbtMap.builder().putCompound("$polymer:stack", NbtMap.builder()
                .putString("id", entry.canonical()).putInt("count", 1).build()).build());
        return new ItemStack(session.getItemMappings().getMapping(entry.base()).getJavaItem().javaId(), 1, patch);
    }
    private static BedrockPacket wire(CreativeProtocolProof.CaptureSession session, BedrockPacket packet) throws Exception {
        var codec = session.getUpstream().getSession().getCodec(); ByteBuf payload = Unpooled.buffer(); String stage = "encoding";
        try {
            codec.tryEncode(session.getUpstream().getCodecHelper(), payload, packet); stage = "decoding";
            int id = session.clientCodec.getPacketDefinition(packet.getClass()).getId();
            require(codec.getPacketDefinition(packet.getClass()).getId() == id, "Native client/server packet IDs differ");
            BedrockPacket decoded = session.clientCodec.tryDecode(session.clientCodecHelper, payload, id, PacketRecipient.CLIENT);
            require(!payload.isReadable() && decoded.getClass() == packet.getClass(), "Native equipment codec did not consume the exact payload");
            return decoded;
        } catch (Exception error) {
            StringBuilder causes = new StringBuilder();
            for (Throwable cause = error; cause != null && causes.length() < 2048; cause = cause.getCause()) {
                if (!causes.isEmpty()) causes.append(" -> "); causes.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            }
            throw new IllegalStateException("Native equipment codec failure while " + stage + "; protocol=" + codec.getProtocolVersion()
                    + "; packet=" + packet.getClass().getSimpleName() + "; reader=" + payload.readerIndex() + "; writer=" + payload.writerIndex() + "; causes=" + causes, error);
        } finally { payload.release(); }
    }
    private static void verifyJavaUse(ServerboundUseItemPacket packet) {
        ByteBuf wire = Unpooled.buffer();
        try { packet.serialize(wire); require(packet.equals(new ServerboundUseItemPacket(wire)) && !wire.isReadable(), "Native Java use-item codec changed packet"); }
        finally { wire.release(); }
    }
    private static void verifyJavaRelease(ServerboundPlayerActionPacket packet) {
        ByteBuf wire = Unpooled.buffer();
        try { packet.serialize(wire); require(packet.equals(new ServerboundPlayerActionPacket(wire)) && !wire.isReadable(), "Native Java release-item codec changed packet"); }
        finally { wire.release(); }
    }
    private record Snapshot(byte[] bytes, int netId) {
        static Snapshot capture(GeyserItemStack stack) {
            ByteBuf wire = Unpooled.buffer();
            try { new ServerboundSetCreativeModeSlotPacket((short) 0, stack.getItemStack()).serialize(wire); byte[] bytes = new byte[wire.readableBytes()]; wire.readBytes(bytes); return new Snapshot(bytes, stack.getNetId()); }
            finally { wire.release(); }
        }
        ItemStack decode() {
            ByteBuf wire = Unpooled.wrappedBuffer(bytes);
            try { ItemStack stack = new ServerboundSetCreativeModeSlotPacket(wire).getClickedItem(); require(!wire.isReadable(), "Complete equipment snapshot did not consume exact bytes"); return stack; }
            finally { wire.release(); }
        }
        void requireSame(GeyserItemStack actual, String stage) {
            Snapshot next = capture(actual);
            require(netId == next.netId && Objects.equals(decode(), next.decode()), "Complete cached equipment components/count/network ID changed at "
                    + stage + "; before=" + decode() + "; after=" + next.decode() + "; netIDs=" + netId + "/" + next.netId);
        }
    }
    private static void require(boolean pass, String message) { OwnedEquipmentSupport.require(pass, message); }
}
