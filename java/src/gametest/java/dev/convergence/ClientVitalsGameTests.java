package dev.convergence;

import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.TagValueOutput;

public class ClientVitalsGameTests {
    private static class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        Fixture(GameTestHelper c, String name) {
            var server = c.getLevel().getServer();
            var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), NativeSessionFixture.nativeProfileName(name));
            var data = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(server, c.getLevel(), profile, data.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, data);
            OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
            drain();
        }
        List<Packet<?>> drain() {
            channel.runPendingTasks();
            var result = new ArrayList<Packet<?>>();
            Object value;
            while ((value = channel.readOutbound()) != null) {
                if (value instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(result::add);
                else if (value instanceof Packet<?> packet) result.add(packet);
            }
            return result;
        }
        void set(GameTestHelper c, String id, double value) {
            var result = AdminStats.set(player.createCommandSourceStack(), player, id, value);
            c.assertTrue(result.success(), result.message());
        }
        @Override public void close() {
            OperatorGameTests.deop(player);
            player.level().getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }

    @GameTest public void unchangedTrackingListsAvoidAllProjectionCopies(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-copy-free")) {
            var metadata = new ClientboundSetEntityDataPacket(f.player.getId(), List.of(
                SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), 20f),
                SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), 0f)));
            var attributes = new ClientboundUpdateAttributesPacket(f.player.getId(),
                List.of(f.player.getAttribute(Attributes.MOVEMENT_SPEED), f.player.getAttribute(Attributes.ARMOR)));
            var children = new ArrayList<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>();
            for (int i = 0; i < 100; i++) { children.add(metadata); children.add(attributes); }
            var bundle = new ClientboundBundlePacket(children);
            var sample = ClientVitalsPerformanceProbe.begin();
            try {
                c.assertTrue(ClientVitals.rewrite(bundle, f.player) == bundle, "Unchanged 200-child native list returns the original bundle");
                c.assertValueEqual(sample.lists, 0, "No projection output lists are copied");
                c.assertValueEqual(sample.attributeCopies, 0, "No unused attribute packet is constructed");
                c.assertValueEqual(sample.metadataValues, 0, "Unchanged health and absorption entries are not recreated");
            } finally { ClientVitalsPerformanceProbe.end(); }
        }
        c.succeed();
    }

    @GameTest public void changedTrackingCopiesOnlyAffectedListsAndPreservesSharedEntries(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-lazy-copy")) {
            f.set(c, "max_health", 400); f.set(c, "absorption", 100); f.player.setHealth(20);
            var prefix = new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(f.player.getAttribute(Attributes.ARMOR)));
            var untouched = new SynchedEntityData.DataValue<>(250, EntityDataSerializers.FLOAT, 123f);
            var health = SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), 20f);
            var absorption = SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), 100f);
            var metadata = new ClientboundSetEntityDataPacket(f.player.getId(), List.of(untouched, health, absorption));
            var source = List.<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>of(prefix, prefix, metadata);
            var bundle = new ClientboundBundlePacket(source);
            var sample = ClientVitalsPerformanceProbe.begin();
            try {
                var rewritten = (ClientboundBundlePacket)ClientVitals.rewrite(bundle, f.player);
                var output = new ArrayList<Packet<?>>(); rewritten.subPackets().forEach(output::add);
                c.assertTrue(output.get(0) == prefix && output.get(1) == prefix, "Late changes preserve the untouched prefix and order");
                var projected = (ClientboundSetEntityDataPacket)output.get(2);
                c.assertTrue(projected.packedItems().get(0) == untouched, "Unrelated shared metadata remains identical");
                c.assertValueEqual(projected.packedItems().get(1).value(), 2f, "Health keeps the true capacity ratio");
                c.assertValueEqual(projected.packedItems().get(2).value(), 40f, "Absorption retains its display bound");
                c.assertTrue(metadata.packedItems().get(1) == health && metadata.packedItems().get(2) == absorption,
                    "Projection never overwrites another viewer's source metadata");
                c.assertValueEqual(sample.lists, 2, "Exactly one metadata list and one enclosing bundle list are copied");
                c.assertValueEqual(sample.attributeCopies, 0, "Unrelated armor attributes remain copy-free");
                c.assertValueEqual(sample.metadataValues, 2, "Only the two changed vitals entries are recreated");
            } finally { ClientVitalsPerformanceProbe.end(); }
        }
        c.succeed();
    }

    @GameTest public void unchangedCapacityStillRefreshesHudWithoutCopyingAttributes(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-cap-refresh")) {
            var capacity = new ClientboundUpdateAttributesPacket(f.player.getId(),
                List.of(f.player.getAttribute(Attributes.MAX_HEALTH), f.player.getAttribute(Attributes.MAX_ABSORPTION)));
            var sample = ClientVitalsPerformanceProbe.begin();
            try {
                for (int i = 0; i < 2; i++) {
                    var bundle = (ClientboundBundlePacket)ClientVitals.rewrite(capacity, f.player);
                    var packets = new ArrayList<Packet<?>>(); bundle.subPackets().forEach(packets::add);
                    c.assertTrue(packets.get(0) == capacity, "Ordinary capacity keeps the original attributes");
                    c.assertTrue(packets.get(1) instanceof ClientboundSetHealthPacket h && h.getHealth() == 20,
                        "Every capacity observation still refreshes the owner's HUD");
                    c.assertTrue(packets.get(2) instanceof ClientboundSetEntityDataPacket, "Absorption refresh is preserved");
                }
                c.assertValueEqual(sample.attributeCopies, 0, "No unused attribute copy is allocated for unchanged capacity");
            } finally { ClientVitalsPerformanceProbe.end(); }
            f.set(c, "max_health", 400); f.set(c, "absorption", 100);
            var mixed = new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(
                f.player.getAttribute(Attributes.ARMOR), f.player.getAttribute(Attributes.MAX_HEALTH),
                f.player.getAttribute(Attributes.MAX_ABSORPTION), f.player.getAttribute(Attributes.ATTACK_DAMAGE)));
            var original = new ArrayList<>(mixed.getValues());
            sample = ClientVitalsPerformanceProbe.begin();
            try {
                var projected = (ClientboundBundlePacket)ClientVitals.rewrite(mixed, f.player);
                var children = new ArrayList<Packet<?>>(); projected.subPackets().forEach(children::add);
                var attributes = (ClientboundUpdateAttributesPacket)children.getFirst();
                c.assertValueEqual(sample.attributeCopies, 1, "Two projected capacities share exactly one attribute copy");
                c.assertTrue(attributes != mixed, "The positive-control observer sees a real copy");
                c.assertTrue(attributes.getValues().get(0) == original.get(0) && attributes.getValues().get(3) == original.get(3),
                    "Ordinary prefix and tail snapshots retain identity and order");
                for (int i = 1; i <= 2; i++) {
                    c.assertValueEqual(attributes.getValues().get(i).base(), 40d, "Each projected capacity uses the native display maximum");
                    c.assertTrue(attributes.getValues().get(i).modifiers().isEmpty(), "Client capacity snapshots contain no true-capacity modifiers");
                }
                c.assertValueEqual(mixed.getValues(), original, "The shared input attributes stay unchanged");
            } finally { ClientVitalsPerformanceProbe.end(); }
        }
        c.succeed();
    }

    @GameTest public void nestedTrackingBundlesFlattenAndOneShotIterablesAreTraversedOnce(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-flatten")) {
            var health = new ClientboundSetHealthPacket(20, 20, 5);
            var attributes = new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(f.player.getAttribute(Attributes.ARMOR)));
            var nested = new ClientboundBundlePacket(List.of(attributes));
            var children = List.<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>of(health, nested, health);
            var lists = (ClientboundBundlePacket)ClientVitals.rewrite(new ClientboundBundlePacket(children), f.player);
            var output = new ArrayList<Packet<?>>(); lists.subPackets().forEach(output::add);
            c.assertValueEqual(output, List.of(health, attributes, health), "Even unchanged nested bundles are flattened in order");
            c.assertTrue(children.get(1) == nested, "Original nested bundle remains unmodified");
            f.player.connection.send(new ClientboundBundlePacket(children));
            c.assertValueEqual(f.drain(), List.of(health, attributes, health), "The actual outbound handler emits the flattened children");
            var traversals = new java.util.concurrent.atomic.AtomicInteger();
            Iterable<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> oneShot = () -> {
                if (traversals.incrementAndGet() != 1) throw new IllegalStateException("Second traversal");
                return children.iterator();
            };
            var generic = (ClientboundBundlePacket)ClientVitals.rewrite(new ClientboundBundlePacket(oneShot), f.player);
            output.clear(); generic.subPackets().forEach(output::add);
            c.assertValueEqual(output, List.of(health, attributes, health), "Generic iterable preserves every child exactly once");
            c.assertValueEqual(traversals.get(), 1, "Generic iterable is never rescanned for a changed prefix");
            var unchangedTraversals = new java.util.concurrent.atomic.AtomicInteger();
            Iterable<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> unchanged = () -> {
                if (unchangedTraversals.incrementAndGet() != 1) throw new IllegalStateException("Second unchanged traversal");
                return List.<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>>of(health, attributes, health).iterator();
            };
            var materialized = (ClientboundBundlePacket)ClientVitals.rewrite(new ClientboundBundlePacket(unchanged), f.player);
            output.clear(); materialized.subPackets().forEach(output::add);
            c.assertValueEqual(output, List.of(health, attributes, health), "Even unchanged one-shot input remains serializable after projection");
            output.clear(); materialized.subPackets().forEach(output::add);
            c.assertValueEqual(output.size(), 3, "The encoder can traverse the owned result again");
            c.assertValueEqual(unchangedTraversals.get(), 1, "Unchanged one-shot input is consumed once");
        }
        c.succeed();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @GameTest public void metadataEqualityPreservesSignedZeroNanAndNativeSerializers(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-float-data")) {
            var negativeZero = SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), -0f);
            var nan = SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), Float.NaN);
            SynchedEntityData.DataValue<?> wrongSerializer = new SynchedEntityData.DataValue(
                LivingVitalsAccess.infinity$health().id(), EntityDataSerializers.INT, 20f);
            var packet = new ClientboundSetEntityDataPacket(f.player.getId(), List.of(negativeZero, nan, wrongSerializer));
            var result = (ClientboundSetEntityDataPacket)ClientVitals.rewrite(packet, f.player);
            c.assertValueEqual(Float.floatToIntBits((Float)result.packedItems().get(0).value()), Float.floatToIntBits(0f), "Dead signed zero is canonicalized as before");
            c.assertTrue(result.packedItems().get(1) == nan, "Equal NaN absorption avoids a pointless replacement");
            c.assertTrue(result.packedItems().get(2).serializer() == LivingVitalsAccess.infinity$health().serializer(), "Matching vitals retain the native float serializer correction");
            c.assertTrue(packet.packedItems().get(0) == negativeZero && packet.packedItems().get(2) == wrongSerializer, "Source entries stay untouched");
        }
        c.succeed();
    }

    @GameTest public void enormousHealthUsesBoundedRealNetworkPacketsWithoutChangingSavedStats(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-giant")) {
            f.set(c, "health", 1e21);
            var before = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.player.registryAccess());
            f.player.saveWithoutId(before);
            f.player.connection.send(new ClientboundUpdateAttributesPacket(f.player.getId(),
                List.of(f.player.getAttribute(Attributes.MAX_HEALTH))));
            f.player.connection.send(new ClientboundSetHealthPacket(f.player.getHealth(), 20, 5));
            var packets = f.drain();
            var attrs = packets.stream().filter(p -> p instanceof ClientboundUpdateAttributesPacket).map(p -> (ClientboundUpdateAttributesPacket)p).toList();
            c.assertFalse(attrs.isEmpty(), "Real server network handler sends attributes");
            for (var packet : attrs) for (var entry : packet.getValues())
                c.assertValueEqual(entry.base(), 40d, "Client health capacity is bounded");
            var health = packets.stream().filter(p -> p instanceof ClientboundSetHealthPacket).map(p -> (ClientboundSetHealthPacket)p).toList();
            c.assertFalse(health.isEmpty(), "Real server network handler sends health");
            for (var packet : health) {
                c.assertValueEqual(packet.getHealth(), 40f, "Full enormous health displays a full compact bar");
                var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    ClientboundSetHealthPacket.STREAM_CODEC.encode(buf, packet);
                    c.assertValueEqual(ClientboundSetHealthPacket.STREAM_CODEC.decode(buf).getHealth(), 40f, "Native codec carries the bounded value");
                } finally { buf.release(); }
            }
            var after = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.player.registryAccess());
            f.player.saveWithoutId(after);
            c.assertValueEqual(after.buildResult(), before.buildResult(), "Sending HUD packets preserves all saved player state");
            c.assertTrue(f.player.getHealth() > 1e20 && f.player.getMaxHealth() > 1e20, "Actual gigantic health stays intact");
        }
        c.succeed();
    }

    @GameTest public void capacityOnlyEditsRefreshTheScaledHudAndCanReturnToNormal(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-capacity")) {
            f.set(c, "max_health", 400);
            f.player.setHealth(20);
            f.player.connection.send(new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(f.player.getAttribute(Attributes.MAX_HEALTH))));
            c.assertTrue(f.drain().stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket h && h.getHealth() == 2f), "Capacity-only packet resends the correct percentage");
            f.set(c, "max_health", 20);
            f.player.connection.send(new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(f.player.getAttribute(Attributes.MAX_HEALTH))));
            c.assertTrue(f.drain().stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket h && h.getHealth() == 20f), "Returning to ordinary capacity refreshes ordinary hearts");
        }
        c.succeed();
    }

    @GameTest public void largeAbsorptionAndHealthMetadataAreBoundedWithoutTouchingOtherFields(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-metadata")) {
            f.set(c, "health", 1e21); f.set(c, "absorption", 1e21);
            var untouched = new SynchedEntityData.DataValue<>(250, EntityDataSerializers.FLOAT, 123f);
            var packet = new ClientboundSetEntityDataPacket(f.player.getId(), List.of(
                SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), f.player.getHealth()),
                SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), f.player.getAbsorptionAmount()), untouched));
            f.player.connection.send(packet);
            var result = (ClientboundSetEntityDataPacket)f.drain().stream().filter(p -> p instanceof ClientboundSetEntityDataPacket).reduce((a,b) -> b).orElseThrow();
            c.assertValueEqual(result.packedItems().get(0).value(), 40f, "Tracked health is compact too");
            c.assertValueEqual(result.packedItems().get(1).value(), 40f, "Tracked absorption is bounded");
            c.assertValueEqual(result.packedItems().get(2), untouched, "Other metadata is untouched");
            c.assertTrue(f.player.getAbsorptionAmount() > 1e20, "Actual absorption stays intact");
            c.assertTrue(ClientVitals.rewrite(new ClientboundSetHealthPacket(0, 20, 5), f.player) instanceof ClientboundSetHealthPacket h && h.getHealth() == 0, "Death remains zero health");
            c.assertTrue(ClientVitals.health(1, Float.MAX_VALUE) > 0, "Tiny positive percentage still displays alive");
        }
        c.succeed();
    }

    @GameTest public void sharedBundlesAndRealModifiersAreNotMutatedByHudProjection(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-bundle")) {
            f.set(c, "max_health", 100);
            var instance = f.player.getAttribute(Attributes.MAX_HEALTH);
            var modifier = new AttributeModifier(Identifier.fromNamespaceAndPath("convergence_tests", "hud-bonus"), 100, AttributeModifier.Operation.ADD_VALUE);
            instance.addPermanentModifier(modifier);
            f.player.setHealth(100);
            var attributes = new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(instance));
            var original = attributes.getValues().getFirst();
            var unrelated = new ClientboundUpdateAttributesPacket(f.player.getId()+1000, List.of(instance));
            var ordinary = new ClientboundSetHealthPacket(20, 20, 5);
            f.player.connection.send(new ClientboundBundlePacket(List.of(attributes, unrelated)));
            var packets = f.drain();
            c.assertFalse(packets.stream().anyMatch(p -> p instanceof ClientboundBundlePacket), "Nested bundles are flattened");
            c.assertTrue(packets.contains(unrelated), "Another entity's shared packet is preserved");
            c.assertValueEqual(attributes.getValues().getFirst(), original, "Input packet and modifiers are unchanged for other viewers");
            c.assertValueEqual(instance.getValue(), 200d, "Real modifier still applies on the server");
            c.assertTrue(packets.stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket h && h.getHealth() == 20f), "Display ratio includes the effective modifier");
            instance.removeModifier(modifier.id());
            f.set(c, "max_health", 20);
            c.assertTrue(ClientVitals.rewrite(ordinary, f.player) == ordinary, "Normal health packets pass through unchanged");
        }
        c.succeed();
    }

    @GameTest public void observersReceiveBoundedPlayerVitalsWithoutChangingTheirOwnHud(GameTestHelper c) {
        try (var target = new Fixture(c, "hud-observed"); var viewer = new Fixture(c, "hud-viewer")) {
            target.set(c, "health", 1e21); target.set(c, "absorption", 1e21);
            target.player.setHealth(target.player.getMaxHealth() / 4);
            var attributes = new ClientboundUpdateAttributesPacket(target.player.getId(), List.of(
                target.player.getAttribute(Attributes.MAX_HEALTH), target.player.getAttribute(Attributes.MAX_ABSORPTION)));
            var before = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, target.player.registryAccess());
            target.player.saveWithoutId(before);
            viewer.player.connection.send(attributes);
            var packets = viewer.drain();
            var projected = (ClientboundUpdateAttributesPacket)packets.stream().filter(p -> p instanceof ClientboundUpdateAttributesPacket).findFirst().orElseThrow();
            c.assertTrue(projected.getValues().stream().allMatch(e -> e.base() == 40), "Other player's capacity is bounded for the observer too");
            var metadata = (ClientboundSetEntityDataPacket)packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket).findFirst().orElseThrow();
            c.assertValueEqual(metadata.packedItems().getFirst().value(), 10f, "Observed current health keeps its percentage");
            c.assertValueEqual(metadata.packedItems().getLast().value(), 40f, "Observed absorption stays bounded");
            c.assertFalse(packets.stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket), "Observed vitals never replace the viewer's own HUD");
            c.assertTrue(attributes.getValues().stream().allMatch(e -> e.base() > 1e20), "Shared source attribute packet is intact");
            var tracked = new ClientboundSetEntityDataPacket(target.player.getId(), List.of(
                SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), target.player.getHealth()),
                SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), target.player.getAbsorptionAmount())));
            viewer.player.connection.send(tracked);
            var updated = (ClientboundSetEntityDataPacket)viewer.drain().getFirst();
            c.assertValueEqual(updated.packedItems().getFirst().value(), 10f, "Subsequent observed health updates are bounded");
            c.assertValueEqual(updated.packedItems().getLast().value(), 40f, "Subsequent observed absorption updates are bounded");
            var after = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, target.player.registryAccess());
            target.player.saveWithoutId(after);
            c.assertValueEqual(after.buildResult(), before.buildResult(), "Sending another player's vitals preserves all saved stats");
            target.set(c, "max_health", 20);
            viewer.player.connection.send(new ClientboundUpdateAttributesPacket(target.player.getId(), List.of(target.player.getAttribute(Attributes.MAX_HEALTH))));
            var reset = (ClientboundSetEntityDataPacket)viewer.drain().stream().filter(p -> p instanceof ClientboundSetEntityDataPacket).findFirst().orElseThrow();
            c.assertValueEqual(reset.packedItems().getFirst().value(), 20f, "Capacity reset also refreshes observers");
        }
        c.succeed();
    }

    @GameTest public void helpersUseBoundedObservedHealthWhileOrdinaryMobsAreUntouched(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-helper-owner")) {
            var feet = c.absolutePos(new net.minecraft.core.BlockPos(3, 20, 3));
            for (var pos : net.minecraft.core.BlockPos.betweenClosed(feet.offset(-7, -1, -7), feet.offset(7, 4, 7)))
                c.getLevel().setBlockAndUpdate(pos, pos.getY() == feet.getY() - 1 ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            f.player.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(feet));
            f.player.setNoGravity(true);
            var agents = AgentCompanions.get(c.getLevel().getServer());
            agents.spawn(f.player, "hud-robot");
            var record = agents.owned(f.player, "hud-robot");
            c.assertTrue(record != null, "Real helper is registered");
            try {
                var helper = agents.loaded.get(UUID.fromString(record.getKey()));
                c.assertTrue(AdminStats.set(f.player.createCommandSourceStack(), helper, "health", 1e21).success(), "Actual helper capacity is expanded");
                helper.setHealth(helper.getMaxHealth() / 2);
                var before = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.registryAccess()); helper.saveWithoutId(before);
                f.drain();
                f.player.connection.send(new ClientboundUpdateAttributesPacket(helper.getId(), List.of(helper.getAttribute(Attributes.MAX_HEALTH))));
                var packets = f.drain();
                c.assertTrue(packets.stream().anyMatch(p -> p instanceof ClientboundUpdateAttributesPacket a && a.getValues().getFirst().base() == 40), "Huge helper capacity stays bounded on the actual outbound path");
                var metadata = (ClientboundSetEntityDataPacket)packets.stream().filter(p -> p instanceof ClientboundSetEntityDataPacket).findFirst().orElseThrow();
                c.assertValueEqual(metadata.packedItems().getFirst().value(), 20f, "Helper health preserves its percentage");
                c.assertValueEqual(metadata.packedItems().size(), 1, "Native helper metadata never borrows a player-only absorption field");
                c.assertFalse(packets.stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket), "Helper never changes owner's HUD");
                var tracked = new ClientboundSetEntityDataPacket(helper.getId(), List.of(SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), helper.getHealth())));
                c.assertValueEqual(((ClientboundSetEntityDataPacket)ClientVitals.rewrite(tracked, f.player)).packedItems().getFirst().value(), 20f, "Helper health updates stay bounded too");
                var after = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.registryAccess()); helper.saveWithoutId(after);
                c.assertValueEqual(after.buildResult(), before.buildResult(), "Sending helper vitals preserves saved entity state");
                var ordinary = net.minecraft.world.entity.EntityTypes.IRON_GOLEM.create(c.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                try {
                    c.getLevel().addFreshEntity(ordinary);
                    var nativeAttrs = new ClientboundUpdateAttributesPacket(ordinary.getId(), List.of(ordinary.getAttribute(Attributes.MAX_HEALTH)));
                    var nativeHealth = new ClientboundSetEntityDataPacket(ordinary.getId(), List.of(SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), ordinary.getHealth())));
                    c.assertTrue(ClientVitals.rewrite(nativeAttrs, f.player) == nativeAttrs && ClientVitals.rewrite(nativeHealth, f.player) == nativeHealth, "Unregistered mobs retain native health packets");
                } finally { ordinary.discard(); }
            } finally { agents.dismiss(f.player, "hud-robot"); }
        }
        c.succeed();
    }
}
