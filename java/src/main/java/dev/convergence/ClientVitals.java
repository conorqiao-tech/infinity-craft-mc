package dev.convergence;

import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Bounded client vitals only. Authoritative stats, damage and saved values stay untouched. */
public final class ClientVitals {
    static final float DISPLAY_MAXIMUM = 40;

    /** One listener owns this bounded history; no player or entity is retained globally. */
    public static final class CapacityHistory {
        private static final int LIMIT = 256;
        private final LinkedHashMap<Integer, Capacity> targets = new LinkedHashMap<>();
        private ServerPlayer viewer;
        private Level world;
        private boolean disabled;

        private void disable() {
            targets.clear(); viewer = null; world = null; disabled = true;
        }

        private void bind(ServerPlayer player) {
            var level = player == null ? null : player.level();
            if (viewer != player || world != level) {
                targets.clear(); viewer = player; world = level;
            }
        }

        private Capacity previous(LivingEntity target) {
            var previous = targets.get(target.getId());
            if (previous != null && (previous.target != target || !previous.uuid.equals(target.getUUID()))) {
                targets.remove(target.getId()); return null;
            }
            return previous;
        }

        private Capacity remember(LivingEntity target) {
            var previous = previous(target);
            if (previous != null) return previous;
            if (targets.size() >= LIMIT) targets.pollFirstEntry();
            var created = new Capacity(target);
            targets.put(target.getId(), created);
            return created;
        }

        private void healthSent(ServerPlayer player, float health, int food, float saturation) {
            var previous = previous(player);
            if (previous != null) {
                previous.health = health; previous.food = food; previous.saturation = saturation;
            }
        }

        private void metadataSent(LivingEntity target, List<SynchedEntityData.DataValue<?>> values) {
            var previous = previous(target);
            if (previous == null) return;
            for (var value : values) {
                if (!(value.value() instanceof Float number)) continue;
                if (value.id() == LivingVitalsAccess.infinity$health().id()) previous.health = number;
                else if (target instanceof Player && value.id() == PlayerVitalsAccess.infinity$absorption().id()) previous.absorption = number;
            }
        }
    }

    private static final class Capacity {
        final LivingEntity target;
        final UUID uuid;
        boolean healthKnown, absorptionKnown;
        double maxHealth, maxAbsorption;
        float health, absorption, saturation;
        int food;
        Capacity(LivingEntity target) { this.target = target; this.uuid = target.getUUID(); }
    }

    static float health(float value, float maximum) {
        if (!(value > 0)) return 0;
        if (maximum <= DISPLAY_MAXIMUM) return value;
        return Math.min(DISPLAY_MAXIMUM, Math.max(0.01f, (float)((double)value / maximum * DISPLAY_MAXIMUM)));
    }

    static float absorption(float value) { return Math.max(0, Math.min(DISPLAY_MAXIMUM, value)); }

    static ClientboundSetHealthPacket healthPacket(ServerPlayer player) {
        return new ClientboundSetHealthPacket(health(player.getHealth(), player.getMaxHealth()),
            Math.max(0, Math.min(20, player.getFoodData().getFoodLevel())),
            Math.max(0, Math.min(20, player.getFoodData().getSaturationLevel())));
    }

    public static Packet<?> rewrite(Packet<?> packet, ServerPlayer player) {
        return project(packet, player, null);
    }

    public static Packet<?> rewrite(Packet<?> packet, ServerPlayer player, CapacityHistory history) {
        if (history == null) return project(packet, player, null);
        // Movement, chat, equipment and unrelated packets need neither a lock nor a lookup.
        if (!(packet instanceof ClientboundBundlePacket || packet instanceof ClientboundSetHealthPacket
            || packet instanceof ClientboundUpdateAttributesPacket || packet instanceof ClientboundSetEntityDataPacket
            || packet instanceof ClientboundAddEntityPacket || packet instanceof ClientboundRemoveEntitiesPacket
            || packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket)) return packet;
        synchronized (history) {
            // The native enqueue happens after this hook. Concurrent sends cannot share
            // a dependable delivery history, so this listener stays stateless thereafter.
            if (player != null && !player.level().getServer().isSameThread()) history.disable();
            if (history.disabled) return project(packet, player, null);
            history.bind(player);
            return project(packet, player, history);
        }
    }

    private static Packet<?> project(Packet<?> packet, ServerPlayer player, CapacityHistory history) {
        if (player == null) return packet;
        if (history != null) {
            if (packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) history.targets.clear();
            else if (packet instanceof ClientboundAddEntityPacket added) history.targets.remove(added.getId());
            else if (packet instanceof ClientboundRemoveEntitiesPacket removed)
                removed.entityIds().forEach((int id) -> history.targets.remove(id));
        }
        if (packet instanceof ClientboundBundlePacket bundle) {
            // Native tracking bundles normally use a list. Copy only when projection or
            // flattening changes a child; arbitrary iterables retain the single-pass path.
            if (bundle.subPackets() instanceof List<?> children && children instanceof java.util.RandomAccess) {
                ArrayList<Packet<? super ClientGamePacketListener>> output = null;
                for (int i = 0; i < children.size(); i++) {
                    var child = (Packet<?>)children.get(i);
                    var replacement = project(child, player, history);
                    if (output == null && (replacement != child || replacement instanceof ClientboundBundlePacket)) {
                        output = new ArrayList<>();
                        for (int prefix = 0; prefix < i; prefix++) output.add(playPacket((Packet<?>)children.get(prefix)));
                    }
                    if (output != null) append(output, replacement);
                }
                return output == null ? packet : new ClientboundBundlePacket(output);
            }
            var output = new ArrayList<Packet<? super ClientGamePacketListener>>();
            for (var child : bundle.subPackets()) {
                var replacement = project(child, player, history);
                // Bundles cannot contain another bundle on the wire.
                append(output, replacement);
            }
            // The iterable may be one-shot. Own the collected children even when
            // unchanged, so the encoder never has to traverse an exhausted input.
            return new ClientboundBundlePacket(output);
        }
        if (packet instanceof ClientboundSetHealthPacket update) {
            float value = health(update.getHealth(), player.getMaxHealth());
            int food = Math.max(0, Math.min(20, update.getFood()));
            float saturation = Math.max(0, Math.min(20, update.getSaturation()));
            if (history != null) history.healthSent(player, value, food, saturation);
            return value == update.getHealth() && food == update.getFood() && saturation == update.getSaturation()
                ? packet : new ClientboundSetHealthPacket(value, food, saturation);
        }
        if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            var target = visibleTarget(player, attributes.getEntityId());
            if (target == null) {
                if (history != null) history.targets.remove(attributes.getEntityId());
                return packet;
            }
            ClientboundUpdateAttributesPacket copy = null;
            boolean healthCapacity = false, absorptionCapacity = false;
            var original = attributes.getValues();
            for (int i = 0; i < original.size(); i++) {
                var entry = original.get(i);
                healthCapacity |= entry.attribute().equals(Attributes.MAX_HEALTH);
                absorptionCapacity |= entry.attribute().equals(Attributes.MAX_ABSORPTION);
                double display = entry.attribute().equals(Attributes.MAX_HEALTH) ? target.getMaxHealth()
                    : entry.attribute().equals(Attributes.MAX_ABSORPTION) ? target.getMaxAbsorption() : -1;
                if (display > DISPLAY_MAXIMUM) {
                    if (copy == null) {
                        copy = new ClientboundUpdateAttributesPacket(target.getId(), List.of());
                        copy.getValues().addAll(original);
                    }
                    copy.getValues().set(i, new ClientboundUpdateAttributesPacket.AttributeSnapshot(entry.attribute(), DISPLAY_MAXIMUM, List.of()));
                }
            }
            if (!healthCapacity && !absorptionCapacity) return packet;
            var projectedAttributes = copy == null ? attributes : copy;
            if (history == null) return target == player
                ? new ClientboundBundlePacket(List.of(projectedAttributes, healthPacket(player), absorptionPacket(player)))
                : new ClientboundBundlePacket(List.of(projectedAttributes, trackedVitals(target)));
            double maxHealth = target.getAttributeValue(Attributes.MAX_HEALTH);
            double maxAbsorption = target.getAttributeValue(Attributes.MAX_ABSORPTION);
            float health = health(target.getHealth(), (float)maxHealth);
            float absorption = target instanceof Player tracked ? absorption(tracked.getAbsorptionAmount()) : 0;
            int food = target == player ? Math.max(0, Math.min(20, player.getFoodData().getFoodLevel())) : 0;
            float saturation = target == player ? Math.max(0, Math.min(20, player.getFoodData().getSaturationLevel())) : 0;
            var previous = history.previous(target);
            // Only an attribute actually present on the wire can establish its capacity.
            // Comparing true maxima also catches 400 -> 800 behind the same wire cap of 40.
            boolean refresh = previous == null
                || healthCapacity && (!previous.healthKnown || Double.compare(previous.maxHealth, maxHealth) != 0)
                || absorptionCapacity && (!previous.absorptionKnown || Double.compare(previous.maxAbsorption, maxAbsorption) != 0)
                || Float.compare(previous.health, health) != 0
                || target instanceof Player && Float.compare(previous.absorption, absorption) != 0
                || target == player && (previous.food != food || Float.compare(previous.saturation, saturation) != 0);
            Packet<?> output = projectedAttributes;
            if (refresh) output = target == player
                ? new ClientboundBundlePacket(List.of(projectedAttributes,
                    new ClientboundSetHealthPacket(health, food, saturation), absorptionPacket(player)))
                : new ClientboundBundlePacket(List.of(projectedAttributes, trackedVitals(target)));
            var remembered = history.remember(target);
            if (healthCapacity) { remembered.healthKnown = true; remembered.maxHealth = maxHealth; }
            if (absorptionCapacity) { remembered.absorptionKnown = true; remembered.maxAbsorption = maxAbsorption; }
            if (refresh) {
                remembered.health = health; remembered.absorption = absorption;
                remembered.food = food; remembered.saturation = saturation;
            }
            return output;
        }
        if (packet instanceof ClientboundSetEntityDataPacket tracker) {
            var target = visibleTarget(player, tracker.id());
            if (target == null) {
                if (history != null) history.targets.remove(tracker.id());
                return packet;
            }
            ArrayList<SynchedEntityData.DataValue<?>> values = null;
            var original = tracker.packedItems();
            int i = 0;
            for (var entry : original) {
                SynchedEntityData.DataValue<?> replacement = null;
                if (entry.id() == LivingVitalsAccess.infinity$health().id() && entry.value() instanceof Float value) {
                    float projected = health(value, target.getMaxHealth());
                    if (Float.compare(projected, value) != 0 || !entry.serializer().equals(LivingVitalsAccess.infinity$health().serializer()))
                        replacement = SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), projected);
                } else if (target instanceof Player && entry.id() == PlayerVitalsAccess.infinity$absorption().id() && entry.value() instanceof Float value) {
                    float projected = absorption(value);
                    if (Float.compare(projected, value) != 0 || !entry.serializer().equals(PlayerVitalsAccess.infinity$absorption().serializer()))
                        replacement = SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), projected);
                }
                if (replacement != null) {
                    if (values == null) values = new ArrayList<>(original);
                    values.set(i, replacement);
                }
                i++;
            }
            Packet<?> output = values == null ? packet : new ClientboundSetEntityDataPacket(tracker.id(), values);
            if (history != null) history.metadataSent(target, values == null ? original : values);
            return output;
        }
        return packet;
    }

    private static void append(List<Packet<? super ClientGamePacketListener>> output, Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket nested) nested.subPackets().forEach(output::add);
        else output.add(playPacket(packet));
    }

    private static LivingEntity visibleTarget(ServerPlayer viewer, int id) {
        if (viewer.getId() == id) return viewer;
        var entity = viewer.level().getEntity(id);
        return entity instanceof LivingEntity living && (living instanceof ServerPlayer || AdminStats.helper(living)) ? living : null;
    }

    private static ClientboundSetEntityDataPacket trackedVitals(LivingEntity target) {
        var values = new ArrayList<SynchedEntityData.DataValue<?>>();
        values.add(SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), health(target.getHealth(), target.getMaxHealth())));
        if (target instanceof Player player)
            values.add(SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), absorption(player.getAbsorptionAmount())));
        return new ClientboundSetEntityDataPacket(target.getId(), values);
    }

    private static ClientboundSetEntityDataPacket absorptionPacket(ServerPlayer player) {
        return new ClientboundSetEntityDataPacket(player.getId(), List.of(SynchedEntityData.DataValue.create(
            PlayerVitalsAccess.infinity$absorption(), absorption(player.getAbsorptionAmount()))));
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> playPacket(Packet<?> packet) {
        return (Packet<? super ClientGamePacketListener>)packet;
    }
}
