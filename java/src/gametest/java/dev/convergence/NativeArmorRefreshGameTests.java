package dev.convergence;

import com.mojang.authlib.GameProfile;
import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueOutput;

/** Counts actual registered armor renewals and native tracking sends; compatible with explore.31. */
public class NativeArmorRefreshGameTests {
    private static final int WARMUP = 40, TICKS = 120;
    private static final List<EquipmentSlot> ARMOR = List.of(
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private static final class TrackingTickets implements AutoCloseable {
        private static long sequence = 32000;
        private final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++sequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        private final net.minecraft.server.level.ServerLevel world;
        private final List<net.minecraft.world.level.ChunkPos> chunks = new ArrayList<>();
        TrackingTickets(ServerPlayer player) {
            world = player.level(); var at = player.chunkPosition();
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                var chunk = new net.minecraft.world.level.ChunkPos(at.x() + x, at.z() + z);
                chunks.add(chunk); world.getChunkSource().addTicketWithRadius(ticket, chunk, 2);
            }
        }
        @Override public void close() {
            for (var chunk : chunks) world.getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
            chunks.clear();
        }
    }

    private static final class Joined implements AutoCloseable {
        final NativePlayConnection wire;
        final Connection connection;
        final EmbeddedChannel channel;
        final ServerPlayer player;
        PositionMoveRotation clientPosition;
        Joined(GameTestHelper c, String name, BlockPos relative) {
            var server = c.getLevel().getServer();
            wire = new NativePlayConnection(c.getLevel().registryAccess());
            connection = wire.connection; channel = wire.channel;
            var profile = new GameProfile(UUID.randomUUID(), NativeSessionFixture.nativeProfileName(name));
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(server, c.getLevel(), profile, cookie.clientInformation());
            clientPosition = PositionMoveRotation.of(player);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var feet = c.absolutePos(relative);
            c.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
            c.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            player.connection.teleport(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
            player.setNoGravity(true);
            player.setPermanentlyInvulnerable(true);
            player.setGameMode(GameType.SURVIVAL);
            drain();
            // Real connections tick ServerGamePacketListenerImpl, which calls
            // ServerPlayer.doTick and advances native effect durations. Merely
            // adding a player to the level does not exercise that lifecycle.
            server.getConnection().getConnections().add(connection);
        }
        List<List<Packet<?>>> drain() {
            var sends = new ArrayList<List<Packet<?>>>();
            for (var packet : wire.drain()) {
                var children = new ArrayList<Packet<?>>();
                flatten(packet, children);
                sends.add(children);
                for (var child : children) if (child instanceof ClientboundPlayerPositionPacket position) {
                    clientPosition = PositionMoveRotation.calculateAbsolute(clientPosition, position.change(), position.relatives());
                    var at = clientPosition.position();
                    player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                        position.id(), at.x(), at.y(), at.z(), clientPosition.yRot(), clientPosition.xRot()));
                } else if (child instanceof ClientboundChunkBatchFinishedPacket) {
                    // The dummy client acknowledges only batches actually delivered by native chunk sending.
                    player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64f));
                    // Native movement updates tracking after a joining client's chunks arrive.
                    // Keep this stationary fixture's tracking position current through that
                    // same ChunkSource path; no entity pairing or capacity packet is fabricated.
                    player.level().getChunkSource().move(player);
                }
            }
            return sends;
        }
        @Override public void close() {
            player.level().getServer().getConnection().getConnections().remove(connection);
            player.containerMenu.setCarried(ItemStack.EMPTY);
            player.closeContainer();
            GameModes.PENDING.remove(player.getUUID());
            GameModes.TRANSITIONS.remove(player.getUUID());
            // Native disconnect removes the player once, before closing peers
            // can receive duplicate leave broadcasts from a second removal.
            connection.disconnect(Component.literal("Armor measurement finished"));
            connection.handleDisconnection();
            channel.finishAndReleaseAll();
        }
    }

    private static void flatten(Packet<?> packet, List<Packet<?>> result) {
        if (packet instanceof ClientboundBundlePacket bundle)
            bundle.subPackets().forEach(child -> flatten(child, result));
        else result.add(packet);
    }

    private static CompoundTag saved(ServerPlayer player) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(output);
        return output.buildResult();
    }

    private static boolean fields(ClientboundSetEntityDataPacket packet, int target, int... ids) {
        if (packet.id() != target || packet.packedItems().size() != ids.length) return false;
        for (int i = 0; i < ids.length; i++) if (packet.packedItems().get(i).id() != ids[i]) return false;
        return true;
    }

    private static final class Counts {
        long capacity_attribute_packets, max_health_attribute_deliveries, max_absorption_attribute_deliveries;
        long synthetic_health_refreshes, synthetic_absorption_refreshes, synthetic_metadata_refreshes;
        long total_health_packets, total_metadata_packets;
        void accept(GameTestHelper c, List<List<Packet<?>>> sends, ServerPlayer wearer, boolean owner) {
            int health = LivingVitalsAccess.infinity$health().id();
            int absorption = PlayerVitalsAccess.infinity$absorption().id();
            for (var packets : sends) for (int i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (packet instanceof ClientboundSetHealthPacket) total_health_packets++;
                if (packet instanceof ClientboundSetEntityDataPacket metadata && metadata.id() == wearer.getId()) total_metadata_packets++;
                if (!(packet instanceof ClientboundUpdateAttributesPacket attributes) || attributes.getEntityId() != wearer.getId()) continue;
                boolean capacity = false;
                for (var attribute : attributes.getValues()) {
                    if (attribute.attribute().equals(Attributes.MAX_HEALTH)) {
                        capacity = true; max_health_attribute_deliveries++;
                        c.assertValueEqual(attribute.base(), 40d, "Native over-capacity health attributes still reach each viewer with the display bound");
                        c.assertTrue(attribute.modifiers().isEmpty(), "Projected health cannot reapply native bonus modifiers on the client");
                    } else if (attribute.attribute().equals(Attributes.MAX_ABSORPTION)) {
                        capacity = true; max_absorption_attribute_deliveries++;
                        c.assertValueEqual(attribute.base(), wearer.getAttribute(Attributes.MAX_ABSORPTION).getBaseValue(),
                            "Native under-capacity absorption attributes retain their original base");
                        c.assertValueEqual(new HashSet<>(attribute.modifiers()), new HashSet<>(wearer.getAttribute(Attributes.MAX_ABSORPTION).getModifiers()),
                            "Native under-capacity absorption modifiers are still delivered intact");
                    }
                }
                if (!capacity) continue;
                capacity_attribute_packets++;
                // Native ServerEntity dirty metadata precedes attributes. Only the listener's
                // adjacent, exact refresh shape inside this send is counted as synthetic work.
                if (owner && i + 2 < packets.size() && packets.get(i + 1) instanceof ClientboundSetHealthPacket
                        && packets.get(i + 2) instanceof ClientboundSetEntityDataPacket metadata
                        && fields(metadata, wearer.getId(), absorption)) {
                    synthetic_health_refreshes++; synthetic_absorption_refreshes++; synthetic_metadata_refreshes++;
                } else if (!owner && i + 1 < packets.size() && packets.get(i + 1) instanceof ClientboundSetEntityDataPacket metadata
                        && fields(metadata, wearer.getId(), health, absorption)) {
                    synthetic_health_refreshes++; synthetic_absorption_refreshes++; synthetic_metadata_refreshes++;
                }
            }
        }
        void verify(GameTestHelper c, String viewer) {
            c.assertTrue(max_health_attribute_deliveries <= capacity_attribute_packets
                    && max_absorption_attribute_deliveries <= capacity_attribute_packets,
                viewer + " capacity fields are counted only inside actual native capacity packets");
            c.assertTrue(synthetic_metadata_refreshes <= capacity_attribute_packets,
                viewer + " cannot create more capacity refresh groups than native capacity sends");
        }
    }

    private static final class Window implements AutoCloseable {
        final Joined owner, observer;
        final TrackingTickets tickets;
        final Counts ownerCounts = new Counts(), observerCounts = new Counts();
        final List<ItemStack> gear = new ArrayList<>();
        CompoundTag before;
        double maximumHealth, maximumAbsorption, baseHealth, baseAbsorption;
        float health, absorption;
        int tickStart, ownerTickStart, observerTickStart, healthDuration, absorptionDuration, healthRenewals, absorptionRenewals;
        boolean closed;
        Window(GameTestHelper c) {
            owner = new Joined(c, "armor-owner", new BlockPos(2, 2, 2));
            try { observer = new Joined(c, "armor-observer", new BlockPos(4, 2, 2)); }
            catch (Exception | Error failure) { owner.close(); throw failure; }
            tickets = new TrackingTickets(owner.player);
            String[] names = {"helmet", "chestplate", "leggings", "boots"};
            for (int i = 0; i < ARMOR.size(); i++) owner.player.setItemSlot(ARMOR.get(i),
                new ItemStack(Convergence.ITEMS.get("convergence:" + names[i])));
        }
        void verifyTracking(GameTestHelper c) {
            var wearer = owner.player;
            var chunks = c.getLevel().getChunkSource().chunkMap;
            var chunk = wearer.chunkPosition();
            c.assertTrue(chunks.isChunkTracked(observer.player, chunk.x(), chunk.z()),
                "The observer's native chunk sender has actually delivered the wearer's chunk");
            boolean[] seen = {false};
            chunks.forEachEntityTrackedBy(observer.player, entity -> { if (entity == wearer) seen[0] = true; });
            c.assertTrue(seen[0], "The existing native ChunkMap ServerEntity tracks the wearer for this observer");
        }
        void begin(GameTestHelper c) {
            var p = owner.player;
            verifyTracking(c);
            maximumHealth = p.getMaxHealth(); maximumAbsorption = p.getAttributeValue(Attributes.MAX_ABSORPTION);
            baseHealth = p.getAttribute(Attributes.MAX_HEALTH).getBaseValue();
            baseAbsorption = p.getAttribute(Attributes.MAX_ABSORPTION).getBaseValue();
            health = p.getHealth(); absorption = p.getAbsorptionAmount();
            c.assertTrue(maximumHealth > 40 && maximumAbsorption > 0, "Registered full armor applied actual native capacity bonuses");
            c.assertValueEqual(health, (float)maximumHealth, "The wearer is fully healed before the measurement");
            c.assertValueEqual(absorption, (float)maximumAbsorption, "Native absorption is full before the measurement");
            before = saved(p);
            c.assertTrue(before.get("attributes") != null && before.get("equipment") != null,
                "Native saved attributes and equipment are present for the NBT invariant");
            for (var slot : ARMOR) gear.add(p.getItemBySlot(slot).copy());
            healthDuration = p.getEffect(MobEffects.HEALTH_BOOST).getDuration();
            absorptionDuration = p.getEffect(MobEffects.ABSORPTION).getDuration();
            tickStart = c.getLevel().getServer().getTickCount();
            ownerTickStart = p.tickCount; observerTickStart = observer.player.tickCount;
            owner.drain(); observer.drain();
        }
        void sample(GameTestHelper c) {
            var p = owner.player;
            verifyTracking(c);
            c.assertTrue(p.isAlive() && p.level() == c.getLevel(), "The measured wearer stays alive in the native test world");
            c.assertValueEqual((double)p.getMaxHealth(), maximumHealth, "Effective native health capacity remains stable");
            c.assertValueEqual(p.getAttributeValue(Attributes.MAX_ABSORPTION), maximumAbsorption, "Effective native absorption capacity remains stable");
            c.assertValueEqual(p.getHealth(), health, "No measured damage or healing changes native health");
            c.assertValueEqual(p.getAbsorptionAmount(), absorption, "No measured damage changes native absorption");
            var boost = p.getEffect(MobEffects.HEALTH_BOOST); var shield = p.getEffect(MobEffects.ABSORPTION);
            c.assertTrue(boost != null && boost.getAmplifier() == 9 && shield != null && shield.getAmplifier() == 3,
                "Real native armor effects remain active with their existing bonuses");
            if (boost.getDuration() > healthDuration) healthRenewals++;
            if (shield.getDuration() > absorptionDuration) absorptionRenewals++;
            healthDuration = boost.getDuration(); absorptionDuration = shield.getDuration();
            ownerCounts.accept(c, owner.drain(), p, true);
            observerCounts.accept(c, observer.drain(), p, false);
        }
        void finish(GameTestHelper c) throws Exception {
            var p = owner.player; var after = saved(p);
            c.assertValueEqual(c.getLevel().getServer().getTickCount() - tickStart, TICKS,
                "The window contains exactly 120 actual server ticks rather than repeated manual armor calls");
            c.assertValueEqual(p.tickCount - ownerTickStart, TICKS, "The native owner connection advanced 120 actual player ticks");
            c.assertValueEqual(observer.player.tickCount - observerTickStart, TICKS, "The native observer connection advanced 120 actual player ticks");
            ownerCounts.verify(c, "Owner"); observerCounts.verify(c, "Observer");
            c.assertTrue(healthRenewals >= 2 && absorptionRenewals >= 2, "Registered armor upkeep actually renewed both native effects during the window");
            c.assertValueEqual(p.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), baseHealth, "Packet projection preserves native health base stats");
            c.assertValueEqual(p.getAttribute(Attributes.MAX_ABSORPTION).getBaseValue(), baseAbsorption, "Packet projection preserves native absorption base stats");
            c.assertValueEqual(after.get("attributes"), before.get("attributes"), "Armor packet work preserves native saved attribute NBT");
            c.assertValueEqual(after.get("equipment"), before.get("equipment"), "Armor packet work preserves native saved equipment NBT");
            for (int i = 0; i < ARMOR.size(); i++) c.assertTrue(ItemStack.matches(gear.get(i), p.getItemBySlot(ARMOR.get(i))),
                "Armor count and every component remain unchanged in slot " + ARMOR.get(i));
            var nativeValues = new LinkedHashMap<String, Object>();
            nativeValues.put("maximum_health_before", maximumHealth); nativeValues.put("maximum_health_after", (double)p.getMaxHealth());
            nativeValues.put("maximum_absorption_before", maximumAbsorption); nativeValues.put("maximum_absorption_after", p.getAttributeValue(Attributes.MAX_ABSORPTION));
            nativeValues.put("health_before", health); nativeValues.put("health_after", p.getHealth());
            nativeValues.put("absorption_before", absorption); nativeValues.put("absorption_after", p.getAbsorptionAmount());
            nativeValues.put("base_health_unchanged", true); nativeValues.put("base_absorption_unchanged", true);
            nativeValues.put("gear_components_unchanged", true); nativeValues.put("saved_attribute_nbt_unchanged", true);
            nativeValues.put("saved_equipment_nbt_unchanged", true); nativeValues.put("effects_renewed", true);
            nativeValues.put("tracking_established", true);
            nativeValues.put("owner_player_ticks", p.tickCount - ownerTickStart);
            nativeValues.put("observer_player_ticks", observer.player.tickCount - observerTickStart);
            nativeValues.put("health_boost_renewals", healthRenewals); nativeValues.put("absorption_renewals", absorptionRenewals);
            var output = System.getProperty("infinity.capacity-performance.output");
            if (output != null && !output.isBlank()) {
                var report = new LinkedHashMap<String, Object>();
                report.put("schema", 1); report.put("ticks", TICKS); report.put("warmup_ticks", WARMUP);
                report.put("tick_start", tickStart); report.put("tick_end", c.getLevel().getServer().getTickCount());
                report.put("subjects", Map.of("owner", ownerCounts, "observer", observerCounts)); report.put("native", nativeValues);
                Files.writeString(Path.of(output), CommunityServer.GSON.toJson(report) + "\n");
            }
        }
        @Override public void close() { if (!closed) { closed = true; observer.close(); owner.close(); tickets.close(); } }
    }

    private static void step(GameTestHelper c, Window window, int elapsed) {
        c.runAfterDelay(1, () -> {
            try {
                int tick = elapsed + 1;
                if (tick <= WARMUP) {
                    // Registered Convergence END_SERVER_TICK applies armor; the fixture never calls it.
                    if (tick == 20) {
                        c.assertTrue(window.owner.player.hasEffect(MobEffects.HEALTH_BOOST), "Native registered armor has warmed before healing");
                        window.owner.player.setHealth(window.owner.player.getMaxHealth());
                    }
                    window.owner.drain(); window.observer.drain();
                    if (tick == WARMUP) window.begin(c);
                } else window.sample(c);
                if (tick == WARMUP + TICKS) {
                    window.finish(c); window.close(); c.succeed();
                } else step(c, window, tick);
            } catch (Exception | Error failure) { window.close(); throw new RuntimeException(failure); }
        });
    }

    @GameTest(maxTicks = 220)
    public void registeredArmorRenewalsRetainNativeCapacityAndBoundNetworkRefreshes(GameTestHelper c) {
        step(c, new Window(c), 0);
    }
}
