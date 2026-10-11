package dev.convergence;

import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;

public class ClientVitalsGameTests {
    private static class Fixture implements AutoCloseable {
        final ServerPlayerEntity player;
        final EmbeddedChannel channel;
        Fixture(TestContext c, String name) {
            var server = c.getWorld().getServer();
            var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
            var data = ConnectedClientData.createDefault(profile, false);
            player = new ServerPlayerEntity(server, c.getWorld(), profile, data.syncedOptions());
            var connection = new ClientConnection(NetworkSide.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            server.getPlayerManager().onPlayerConnect(connection, player, data);
            OperatorGameTests.level(player, LeveledPermissionPredicate.OWNERS);
            drain();
        }
        List<Packet<?>> drain() {
            channel.runPendingTasks();
            var result = new ArrayList<Packet<?>>();
            Object value;
            while ((value = channel.readOutbound()) != null) {
                if (value instanceof BundleS2CPacket bundle) bundle.getPackets().forEach(result::add);
                else if (value instanceof Packet<?> packet) result.add(packet);
            }
            return result;
        }
        void set(TestContext c, String id, double value) {
            var result = AdminStats.set(player.getCommandSource(), player, id, value);
            c.assertTrue(result.success(), result.message());
        }
        @Override public void close() {
            OperatorGameTests.deop(player);
            player.getEntityWorld().getServer().getPlayerManager().remove(player);
            channel.finishAndReleaseAll();
        }
    }

    @GameTest public void enormousHealthUsesBoundedRealNetworkPacketsWithoutChangingSavedStats(TestContext c) {
        try (var f = new Fixture(c, "hud-giant")) {
            f.set(c, "health", 1e21);
            var before = NbtWriteView.create(ErrorReporter.EMPTY, f.player.getRegistryManager());
            f.player.writeData(before);
            f.player.networkHandler.sendPacket(new EntityAttributesS2CPacket(f.player.getId(),
                List.of(f.player.getAttributeInstance(EntityAttributes.MAX_HEALTH))));
            f.player.networkHandler.sendPacket(new HealthUpdateS2CPacket(f.player.getHealth(), 20, 5));
            var packets = f.drain();
            var attrs = packets.stream().filter(p -> p instanceof EntityAttributesS2CPacket).map(p -> (EntityAttributesS2CPacket)p).toList();
            c.assertFalse(attrs.isEmpty(), "Real server network handler sends attributes");
            for (var packet : attrs) for (var entry : packet.getEntries())
                c.assertEquals(entry.base(), 40d, "Client health capacity is bounded");
            var health = packets.stream().filter(p -> p instanceof HealthUpdateS2CPacket).map(p -> (HealthUpdateS2CPacket)p).toList();
            c.assertFalse(health.isEmpty(), "Real server network handler sends health");
            for (var packet : health) {
                c.assertEquals(packet.getHealth(), 40f, "Full enormous health displays a full compact bar");
                var buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    HealthUpdateS2CPacket.CODEC.encode(buf, packet);
                    c.assertEquals(HealthUpdateS2CPacket.CODEC.decode(buf).getHealth(), 40f, "Native codec carries the bounded value");
                } finally { buf.release(); }
            }
            var after = NbtWriteView.create(ErrorReporter.EMPTY, f.player.getRegistryManager());
            f.player.writeData(after);
            c.assertEquals(after.getNbt(), before.getNbt(), "Sending HUD packets preserves all saved player state");
            c.assertTrue(f.player.getHealth() > 1e20 && f.player.getMaxHealth() > 1e20, "Actual gigantic health stays intact");
        }
        c.complete();
    }

    @GameTest public void capacityOnlyEditsRefreshTheScaledHudAndCanReturnToNormal(TestContext c) {
        try (var f = new Fixture(c, "hud-capacity")) {
            f.set(c, "max_health", 400);
            f.player.setHealth(20);
            f.player.networkHandler.sendPacket(new EntityAttributesS2CPacket(f.player.getId(), List.of(f.player.getAttributeInstance(EntityAttributes.MAX_HEALTH))));
            c.assertTrue(f.drain().stream().anyMatch(p -> p instanceof HealthUpdateS2CPacket h && h.getHealth() == 2f), "Capacity-only packet resends the correct percentage");
            f.set(c, "max_health", 20);
            f.player.networkHandler.sendPacket(new EntityAttributesS2CPacket(f.player.getId(), List.of(f.player.getAttributeInstance(EntityAttributes.MAX_HEALTH))));
            c.assertTrue(f.drain().stream().anyMatch(p -> p instanceof HealthUpdateS2CPacket h && h.getHealth() == 20f), "Returning to ordinary capacity refreshes ordinary hearts");
        }
        c.complete();
    }

    @GameTest public void largeAbsorptionAndHealthMetadataAreBoundedWithoutTouchingOtherFields(TestContext c) {
        try (var f = new Fixture(c, "hud-metadata")) {
            f.set(c, "health", 1e21); f.set(c, "absorption", 1e21);
            var untouched = new DataTracker.SerializedEntry<>(250, TrackedDataHandlerRegistry.FLOAT, 123f);
            var packet = new EntityTrackerUpdateS2CPacket(f.player.getId(), List.of(
                DataTracker.SerializedEntry.of(LivingVitalsAccess.infinity$health(), f.player.getHealth()),
                DataTracker.SerializedEntry.of(PlayerVitalsAccess.infinity$absorption(), f.player.getAbsorptionAmount()), untouched));
            f.player.networkHandler.sendPacket(packet);
            var result = (EntityTrackerUpdateS2CPacket)f.drain().stream().filter(p -> p instanceof EntityTrackerUpdateS2CPacket).reduce((a,b) -> b).orElseThrow();
            c.assertEquals(result.trackedValues().get(0).value(), 40f, "Tracked health is compact too");
            c.assertEquals(result.trackedValues().get(1).value(), 40f, "Tracked absorption is bounded");
            c.assertEquals(result.trackedValues().get(2), untouched, "Other metadata is untouched");
            c.assertTrue(f.player.getAbsorptionAmount() > 1e20, "Actual absorption stays intact");
            c.assertTrue(ClientVitals.rewrite(new HealthUpdateS2CPacket(0, 20, 5), f.player) instanceof HealthUpdateS2CPacket h && h.getHealth() == 0, "Death remains zero health");
            c.assertTrue(ClientVitals.health(1, Float.MAX_VALUE) > 0, "Tiny positive percentage still displays alive");
        }
        c.complete();
    }

    @GameTest public void sharedBundlesAndRealModifiersAreNotMutatedByHudProjection(TestContext c) {
        try (var f = new Fixture(c, "hud-bundle")) {
            f.set(c, "max_health", 100);
            var instance = f.player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
            var modifier = new EntityAttributeModifier(Identifier.of("convergence_tests", "hud-bonus"), 100, EntityAttributeModifier.Operation.ADD_VALUE);
            instance.addPersistentModifier(modifier);
            f.player.setHealth(100);
            var attributes = new EntityAttributesS2CPacket(f.player.getId(), List.of(instance));
            var original = attributes.getEntries().getFirst();
            var unrelated = new EntityAttributesS2CPacket(f.player.getId()+1000, List.of(instance));
            var ordinary = new HealthUpdateS2CPacket(20, 20, 5);
            f.player.networkHandler.sendPacket(new BundleS2CPacket(List.of(attributes, unrelated)));
            var packets = f.drain();
            c.assertFalse(packets.stream().anyMatch(p -> p instanceof BundleS2CPacket), "Nested bundles are flattened");
            c.assertTrue(packets.contains(unrelated), "Another entity's shared packet is preserved");
            c.assertEquals(attributes.getEntries().getFirst(), original, "Input packet and modifiers are unchanged for other viewers");
            c.assertEquals(instance.getValue(), 200d, "Real modifier still applies on the server");
            c.assertTrue(packets.stream().anyMatch(p -> p instanceof HealthUpdateS2CPacket h && h.getHealth() == 20f), "Display ratio includes the effective modifier");
            instance.removeModifier(modifier.id());
            f.set(c, "max_health", 20);
            c.assertTrue(ClientVitals.rewrite(ordinary, f.player) == ordinary, "Normal health packets pass through unchanged");
        }
        c.complete();
    }

    @GameTest public void observersReceiveBoundedPlayerVitalsWithoutChangingTheirOwnHud(TestContext c) {
        try (var target = new Fixture(c, "hud-observed"); var viewer = new Fixture(c, "hud-viewer")) {
            target.set(c, "health", 1e21); target.set(c, "absorption", 1e21);
            target.player.setHealth(target.player.getMaxHealth() / 4);
            var attributes = new EntityAttributesS2CPacket(target.player.getId(), List.of(
                target.player.getAttributeInstance(EntityAttributes.MAX_HEALTH), target.player.getAttributeInstance(EntityAttributes.MAX_ABSORPTION)));
            var before = NbtWriteView.create(ErrorReporter.EMPTY, target.player.getRegistryManager());
            target.player.writeData(before);
            viewer.player.networkHandler.sendPacket(attributes);
            var packets = viewer.drain();
            var projected = (EntityAttributesS2CPacket)packets.stream().filter(p -> p instanceof EntityAttributesS2CPacket).findFirst().orElseThrow();
            c.assertTrue(projected.getEntries().stream().allMatch(e -> e.base() == 40), "Other player's capacity is bounded for the observer too");
            var metadata = (EntityTrackerUpdateS2CPacket)packets.stream().filter(p -> p instanceof EntityTrackerUpdateS2CPacket).findFirst().orElseThrow();
            c.assertEquals(metadata.trackedValues().getFirst().value(), 10f, "Observed current health keeps its percentage");
            c.assertEquals(metadata.trackedValues().getLast().value(), 40f, "Observed absorption stays bounded");
            c.assertFalse(packets.stream().anyMatch(p -> p instanceof HealthUpdateS2CPacket), "Observed vitals never replace the viewer's own HUD");
            c.assertTrue(attributes.getEntries().stream().allMatch(e -> e.base() > 1e20), "Shared source attribute packet is intact");
            var tracked = new EntityTrackerUpdateS2CPacket(target.player.getId(), List.of(
                DataTracker.SerializedEntry.of(LivingVitalsAccess.infinity$health(), target.player.getHealth()),
                DataTracker.SerializedEntry.of(PlayerVitalsAccess.infinity$absorption(), target.player.getAbsorptionAmount())));
            viewer.player.networkHandler.sendPacket(tracked);
            var updated = (EntityTrackerUpdateS2CPacket)viewer.drain().getFirst();
            c.assertEquals(updated.trackedValues().getFirst().value(), 10f, "Subsequent observed health updates are bounded");
            c.assertEquals(updated.trackedValues().getLast().value(), 40f, "Subsequent observed absorption updates are bounded");
            var after = NbtWriteView.create(ErrorReporter.EMPTY, target.player.getRegistryManager());
            target.player.writeData(after);
            c.assertEquals(after.getNbt(), before.getNbt(), "Sending another player's vitals preserves all saved stats");
            target.set(c, "max_health", 20);
            viewer.player.networkHandler.sendPacket(new EntityAttributesS2CPacket(target.player.getId(), List.of(target.player.getAttributeInstance(EntityAttributes.MAX_HEALTH))));
            var reset = (EntityTrackerUpdateS2CPacket)viewer.drain().stream().filter(p -> p instanceof EntityTrackerUpdateS2CPacket).findFirst().orElseThrow();
            c.assertEquals(reset.trackedValues().getFirst().value(), 20f, "Capacity reset also refreshes observers");
        }
        c.complete();
    }

    @GameTest public void helpersUseBoundedObservedHealthWhileOrdinaryMobsAreUntouched(TestContext c) {
        try (var f = new Fixture(c, "hud-helper-owner")) {
            var feet = c.getAbsolutePos(new net.minecraft.util.math.BlockPos(3, 20, 3));
            for (var pos : net.minecraft.util.math.BlockPos.iterate(feet.add(-7, -1, -7), feet.add(7, 4, 7)))
                c.getWorld().setBlockState(pos, pos.getY() == feet.getY() - 1 ? net.minecraft.block.Blocks.STONE.getDefaultState() : net.minecraft.block.Blocks.AIR.getDefaultState());
            f.player.setPosition(net.minecraft.util.math.Vec3d.ofBottomCenter(feet));
            f.player.setNoGravity(true);
            var agents = AgentCompanions.get(c.getWorld().getServer());
            agents.spawn(f.player, "hud-robot");
            var record = agents.owned(f.player, "hud-robot");
            c.assertTrue(record != null, "Real helper is registered");
            try {
                var helper = agents.loaded.get(UUID.fromString(record.getKey()));
                c.assertTrue(AdminStats.set(f.player.getCommandSource(), helper, "health", 1e21).success(), "Actual helper capacity is expanded");
                helper.setHealth(helper.getMaxHealth() / 2);
                var before = NbtWriteView.create(ErrorReporter.EMPTY, helper.getRegistryManager()); helper.writeData(before);
                f.drain();
                f.player.networkHandler.sendPacket(new EntityAttributesS2CPacket(helper.getId(), List.of(helper.getAttributeInstance(EntityAttributes.MAX_HEALTH))));
                var packets = f.drain();
                c.assertTrue(packets.stream().anyMatch(p -> p instanceof EntityAttributesS2CPacket a && a.getEntries().getFirst().base() == 40), "Huge helper capacity stays bounded on the actual outbound path");
                var metadata = (EntityTrackerUpdateS2CPacket)packets.stream().filter(p -> p instanceof EntityTrackerUpdateS2CPacket).findFirst().orElseThrow();
                c.assertEquals(metadata.trackedValues().getFirst().value(), 20f, "Helper health preserves its percentage");
                c.assertEquals(metadata.trackedValues().size(), 1, "Native helper metadata never borrows a player-only absorption field");
                c.assertFalse(packets.stream().anyMatch(p -> p instanceof HealthUpdateS2CPacket), "Helper never changes owner's HUD");
                var tracked = new EntityTrackerUpdateS2CPacket(helper.getId(), List.of(DataTracker.SerializedEntry.of(LivingVitalsAccess.infinity$health(), helper.getHealth())));
                c.assertEquals(((EntityTrackerUpdateS2CPacket)ClientVitals.rewrite(tracked, f.player)).trackedValues().getFirst().value(), 20f, "Helper health updates stay bounded too");
                var after = NbtWriteView.create(ErrorReporter.EMPTY, helper.getRegistryManager()); helper.writeData(after);
                c.assertEquals(after.getNbt(), before.getNbt(), "Sending helper vitals preserves saved entity state");
                var ordinary = net.minecraft.entity.EntityType.IRON_GOLEM.create(c.getWorld(), net.minecraft.entity.SpawnReason.COMMAND);
                try {
                    c.getWorld().spawnEntity(ordinary);
                    var nativeAttrs = new EntityAttributesS2CPacket(ordinary.getId(), List.of(ordinary.getAttributeInstance(EntityAttributes.MAX_HEALTH)));
                    var nativeHealth = new EntityTrackerUpdateS2CPacket(ordinary.getId(), List.of(DataTracker.SerializedEntry.of(LivingVitalsAccess.infinity$health(), ordinary.getHealth())));
                    c.assertTrue(ClientVitals.rewrite(nativeAttrs, f.player) == nativeAttrs && ClientVitals.rewrite(nativeHealth, f.player) == nativeHealth, "Unregistered mobs retain native health packets");
                } finally { ordinary.discard(); }
            } finally { agents.dismiss(f.player, "hud-robot"); }
        }
        c.complete();
    }
}
