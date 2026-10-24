package dev.infinityhub.geyser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.geyser.api.entity.property.type.GeyserBooleanEntityProperty;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineEntityPropertiesEvent;
import org.geysermc.geyser.api.util.Identifier;
import org.geysermc.geyser.entity.type.Entity;
import org.geysermc.geyser.entity.type.player.PlayerEntity;
import org.geysermc.geyser.inventory.GeyserItemStack;
import org.geysermc.geyser.item.Items;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;
import org.geysermc.geyser.translator.protocol.java.JavaRespawnTranslator;
import org.geysermc.geyser.translator.protocol.java.entity.JavaSetEquipmentTranslator;
import org.geysermc.geyser.translator.protocol.java.inventory.JavaContainerSetContentTranslator;
import org.geysermc.geyser.translator.protocol.java.inventory.JavaContainerSetSlotTranslator;
import org.geysermc.geyser.translator.protocol.java.inventory.JavaSetPlayerInventoryTranslator;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundSetEquipmentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundSetPlayerInventoryPacket;

/** Cosmetic per-viewer state only: the native elytra item and its flight remain untouched.
 * Public Geyser APIs register the vanilla-player property and publish its value. The
 * exact pinned native translators still handle every original inventory/equipment packet.
 */
final class WingedChestSupport {
    static final String PROPERTY = "infinityhub:winged_chest";
    private static final Identifier PLAYER = Identifier.of("minecraft:player");
    private static volatile WingedChestSupport active;
    private final GeyserBooleanEntityProperty property;
    private final Map<Entity, Boolean> applied = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<GeyserSession> awaitingChest = Collections.synchronizedSet(
        Collections.newSetFromMap(new WeakHashMap<>()));
    private final Map<Class<? extends Packet>, PacketTranslator<? extends Packet>> installed = new LinkedHashMap<>();

    private WingedChestSupport(GeyserBooleanEntityProperty property) { this.property = property; }

    static WingedChestSupport define(GeyserDefineEntityPropertiesEvent event) {
        if (event.properties(PLAYER).size() >= 32
            || event.properties(PLAYER).stream().anyMatch(p -> p.identifier().equals(Identifier.of(PROPERTY))))
            throw new IllegalStateException("The owned winged-chest player property cannot be registered");
        return new WingedChestSupport(event.registerBooleanProperty(PLAYER, Identifier.of(PROPERTY), false));
    }

    void install() {
        if (active != null) throw new IllegalStateException("Winged-chest support was already installed");
        Map<Class<? extends Packet>, PacketTranslator<? extends Packet>> originals =
            new HashMap<>(Registries.JAVA_PACKET_TRANSLATORS.get());
        prepare(ClientboundSetEquipmentPacket.class, JavaSetEquipmentTranslator.class, this::equipment);
        prepare(ClientboundContainerSetSlotPacket.class, JavaContainerSetSlotTranslator.class, (session, packet) -> {
            if (packet.getContainerId() == 0 && packet.getSlot() == 6) awaitingChest.remove(session);
            sync(session);
        });
        prepare(ClientboundContainerSetContentPacket.class, JavaContainerSetContentTranslator.class, (session, packet) -> {
            if (packet.getContainerId() == 0 && packet.getItems().length > 6) awaitingChest.remove(session);
            sync(session);
        });
        prepare(ClientboundSetPlayerInventoryPacket.class, JavaSetPlayerInventoryTranslator.class, (session, packet) -> sync(session));
        prepare(ClientboundRespawnPacket.class, JavaRespawnTranslator.class, this::respawn);
        try {
            for (var entry : installed.entrySet())
                if (Registries.JAVA_PACKET_TRANSLATORS.register(entry.getKey(), entry.getValue()) != originals.get(entry.getKey()))
                    throw new IllegalStateException("A pinned winged-chest translator changed during registration");
            if (Registries.JAVA_PACKET_TRANSLATORS.get().size() != originals.size())
                throw new IllegalStateException("The Java translator registry size changed");
            originals.forEach((type, translator) -> {
                if (!installed.containsKey(type) && Registries.JAVA_PACKET_TRANSLATORS.get(type) != translator)
                    throw new IllegalStateException("An unowned Java translator changed");
            });
            verifySchema();
            active = this;
        } catch (RuntimeException error) {
            for (var type : installed.keySet()) Registries.JAVA_PACKET_TRANSLATORS.register(type, originals.get(type));
            throw error;
        }
    }

    @SuppressWarnings("unchecked")
    private <P extends Packet> void prepare(Class<P> packet, Class<?> expected, BiConsumer<GeyserSession, P> after) {
        var original = Registries.JAVA_PACKET_TRANSLATORS.get(packet);
        if (original == null || original.getClass() != expected)
            throw new IllegalStateException("Unsupported winged-chest translator: " + packet.getSimpleName());
        installed.put(packet, new AfterTranslator<>((PacketTranslator<P>) original, after));
    }

    private static final class AfterTranslator<P extends Packet> extends PacketTranslator<P> {
        private final PacketTranslator<P> delegate;
        private final BiConsumer<GeyserSession, P> after;
        private AfterTranslator(PacketTranslator<P> delegate, BiConsumer<GeyserSession, P> after) {
            this.delegate = delegate; this.after = after;
        }
        @Override public void translate(GeyserSession session, P packet) {
            delegate.translate(session, packet);
            after.accept(session, packet);
        }
        @Override public boolean shouldExecuteInEventLoop() { return delegate.shouldExecuteInEventLoop(); }
    }

    private void equipment(GeyserSession session, ClientboundSetEquipmentPacket packet) {
        boolean chestUpdated = false;
        for (var equipment : packet.getEquipment())
            if (equipment.getSlot() == EquipmentSlot.CHESTPLATE) chestUpdated = true;
        if (!chestUpdated) return;
        var entity = session.getEntityCache().getEntityByJavaId(packet.getEntityId());
        if (!(entity instanceof PlayerEntity player)) return;
        if (entity == session.getPlayerEntity()) awaitingChest.remove(session);
        apply(session, player, canonical(player.getItemInSlot(EquipmentSlot.CHESTPLATE)));
    }

    private void respawn(GeyserSession session, ClientboundRespawnPacket packet) {
        // A death can leave the previous inventory cached until its authoritative
        // replacement arrives. Do not re-enable its shell from an unrelated packet.
        if (!packet.isKeepMetadata()) {
            awaitingChest.add(session);
            apply(session, session.getPlayerEntity(), false);
        } else sync(session);
    }

    /** Called after the existing request guard/native translator, never from raw client tags. */
    static void syncSelf(GeyserSession session) {
        var support = active;
        if (support != null) session.ensureInEventLoop(() -> support.sync(session));
    }

    private void sync(GeyserSession session) {
        apply(session, session.getPlayerEntity(), !awaitingChest.contains(session)
            && canonical(session.getPlayerInventory().getItem(6)));
    }

    private void apply(GeyserSession session, PlayerEntity player, boolean value) {
        session.ensureInEventLoop(() -> {
            if (player == null || player.getSession() != session || !player.isValid()
                || !player.definition().identifier().equals(PLAYER)
                || session.getEntityCache().getEntityByJavaId(player.getEntityId()) != player) return;
            var previous = applied.get(player);
            if (previous != null && previous.booleanValue() == value) return;
            // The public API now runs inline on this connection's event loop.
            // Cache only after its update succeeds; recheck identity after queuing.
            player.updateProperty(property, value);
            applied.put(player, value);
        });
    }

    private static boolean canonical(GeyserItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0 || !stack.is(Items.ELYTRA)) return false;
        Object component = stack.getComponent(DataComponentTypes.CUSTOM_DATA);
        return component instanceof NbtMap data && data.get("$polymer:stack") instanceof NbtMap marker
            && "convergence:chestplate".equals(marker.get("id"));
    }

    private void verifySchema() {
        long matches = Registries.BEDROCK_ENTITY_PROPERTIES.get().stream()
            .filter(nbt -> nbt.getString("type").equals(PLAYER.toString()))
            .flatMap(nbt -> nbt.getList("properties", NbtType.COMPOUND).stream())
            .filter(nbt -> nbt.getString("name").equals(PROPERTY) && nbt.getInt("type") == 2).count();
        if (matches != 1) throw new IllegalStateException("Missing unique native-player winged-chest Boolean schema");
        var definition = org.geysermc.geyser.entity.BedrockEntityDefinition.getVanilla(PLAYER);
        var found = definition.properties().stream().filter(p -> p.identifier().equals(Identifier.of(PROPERTY))).toList();
        if (found.size() != 1 || found.getFirst() != property)
            throw new IllegalStateException("Winged-chest property handle does not match the native player definition");
    }

    JsonObject receipt() {
        verifySchema();
        for (var entry : installed.entrySet())
            if (Registries.JAVA_PACKET_TRANSLATORS.get(entry.getKey()) != entry.getValue())
                throw new IllegalStateException("The owned winged-chest translator is no longer installed");
        var receipt = new JsonObject();
        receipt.addProperty("entity", PLAYER.toString()); receipt.addProperty("property", PROPERTY);
        receipt.addProperty("type", "Boolean"); receipt.addProperty("default", false);
        var paths = new JsonArray(); installed.keySet().forEach(type -> paths.add(type.getSimpleName()));
        receipt.add("delegated_java_paths", paths);
        receipt.addProperty("self_request_guard_callback", true);
        receipt.addProperty("canonical_marker_required", "$polymer:stack.id=convergence:chestplate and native minecraft:elytra");
        receipt.addProperty("ordinary_elytra_item_and_gameplay_unchanged", true);
        receipt.addProperty("device_rendering_verified", false);
        return receipt;
    }
}
