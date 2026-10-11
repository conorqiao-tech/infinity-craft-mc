package dev.convergence;

import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;

/** Bounded HUD packets only. The authoritative stats, damage and saved values stay untouched. */
public final class ClientVitals {
    static final float DISPLAY_MAXIMUM = 40;

    static float health(float value, float maximum) {
        if (!(value > 0)) return 0;
        if (maximum <= DISPLAY_MAXIMUM) return value;
        return Math.min(DISPLAY_MAXIMUM, Math.max(0.01f, (float)((double)value / maximum * DISPLAY_MAXIMUM)));
    }

    static float absorption(float value) { return Math.max(0, Math.min(DISPLAY_MAXIMUM, value)); }

    static HealthUpdateS2CPacket healthPacket(ServerPlayerEntity player) {
        return new HealthUpdateS2CPacket(health(player.getHealth(), player.getMaxHealth()),
            Math.max(0, Math.min(20, player.getHungerManager().getFoodLevel())),
            Math.max(0, Math.min(20, player.getHungerManager().getSaturationLevel())));
    }

    public static Packet<?> rewrite(Packet<?> packet, ServerPlayerEntity player) {
        if (player == null) return packet;
        if (packet instanceof BundleS2CPacket bundle) {
            var output = new ArrayList<Packet<? super ClientPlayPacketListener>>();
            boolean changed = false;
            for (var child : bundle.getPackets()) {
                var replacement = rewrite(child, player);
                changed |= replacement != child;
                // Bundles cannot contain another bundle on the wire.
                if (replacement instanceof BundleS2CPacket nested) nested.getPackets().forEach(output::add);
                else output.add(playPacket(replacement));
            }
            return changed ? new BundleS2CPacket(output) : packet;
        }
        if (packet instanceof HealthUpdateS2CPacket update) {
            float value = health(update.getHealth(), player.getMaxHealth());
            int food = Math.max(0, Math.min(20, update.getFood()));
            float saturation = Math.max(0, Math.min(20, update.getSaturation()));
            return value == update.getHealth() && food == update.getFood() && saturation == update.getSaturation()
                ? packet : new HealthUpdateS2CPacket(value, food, saturation);
        }
        if (packet instanceof EntityAttributesS2CPacket attributes && attributes.getEntityId() == player.getId()) {
            var copy = new EntityAttributesS2CPacket(player.getId(), List.of());
            boolean changed = false;
            boolean capacity = false;
            for (var entry : attributes.getEntries()) {
                capacity |= entry.attribute().equals(EntityAttributes.MAX_HEALTH) || entry.attribute().equals(EntityAttributes.MAX_ABSORPTION);
                double display = entry.attribute().equals(EntityAttributes.MAX_HEALTH) ? player.getMaxHealth()
                    : entry.attribute().equals(EntityAttributes.MAX_ABSORPTION) ? player.getMaxAbsorption() : -1;
                if (display > DISPLAY_MAXIMUM) {
                    copy.getEntries().add(new EntityAttributesS2CPacket.Entry(entry.attribute(), DISPLAY_MAXIMUM, List.of()));
                    changed = true;
                } else copy.getEntries().add(entry);
            }
            // A capacity-only edit does not change native lastSentHealth. Refresh the HUD anyway.
            return capacity ? new BundleS2CPacket(List.of(changed ? copy : attributes, healthPacket(player), absorptionPacket(player))) : packet;
        }
        if (packet instanceof EntityTrackerUpdateS2CPacket tracker && tracker.id() == player.getId()) {
            var values = new ArrayList<DataTracker.SerializedEntry<?>>();
            boolean changed = false;
            for (var entry : tracker.trackedValues()) {
                var replacement = entry;
                if (entry.id() == LivingVitalsAccess.infinity$health().id() && entry.value() instanceof Float value)
                    replacement = DataTracker.SerializedEntry.of(LivingVitalsAccess.infinity$health(), health(value, player.getMaxHealth()));
                else if (entry.id() == PlayerVitalsAccess.infinity$absorption().id() && entry.value() instanceof Float value)
                    replacement = DataTracker.SerializedEntry.of(PlayerVitalsAccess.infinity$absorption(), absorption(value));
                changed |= !replacement.equals(entry);
                values.add(replacement);
            }
            return changed ? new EntityTrackerUpdateS2CPacket(tracker.id(), values) : packet;
        }
        return packet;
    }

    private static EntityTrackerUpdateS2CPacket absorptionPacket(ServerPlayerEntity player) {
        return new EntityTrackerUpdateS2CPacket(player.getId(), List.of(DataTracker.SerializedEntry.of(
            PlayerVitalsAccess.infinity$absorption(), absorption(player.getAbsorptionAmount()))));
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientPlayPacketListener> playPacket(Packet<?> packet) {
        return (Packet<? super ClientPlayPacketListener>)packet;
    }
}
