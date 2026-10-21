package dev.convergence;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** An exclusive, phased fixture: native connections and physically cold saved terrain. */
public class ModeTransferGameTests {
    private static final int BLOCK_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final class Joined implements AutoCloseable {
        final NativePlayConnection wire;
        final Connection connection;
        final EmbeddedChannel channel;
        final MinecraftServer server;
        ServerPlayer player;
        PositionMoveRotation clientPosition;
        boolean closed;

        Joined(GameTestHelper c, String name, BlockPos relative) {
            server = c.getLevel().getServer();
            wire = new NativePlayConnection(c.getLevel().registryAccess());
            connection = wire.connection; channel = wire.channel;
            var profile = new GameProfile(UUID.randomUUID(), NativeSessionFixture.nativeProfileName(name));
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(server, c.getLevel(), profile, cookie.clientInformation());
            clientPosition = PositionMoveRotation.of(player);
            try {
                server.getPlayerList().placeNewPlayer(connection, player, cookie);
                configure();
                var feet = c.absolutePos(relative);
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                    c.getLevel().setBlockAndUpdate(feet.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    c.getLevel().setBlockAndUpdate(feet.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                    c.getLevel().setBlockAndUpdate(feet.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                }
                player.connection.teleport(feet.getX() + .5, feet.getY(), feet.getZ() + .5, 0, 0);
                player.setGameMode(GameType.SURVIVAL);
                drain();
                server.getConnection().getConnections().add(connection);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        Joined(GameTestHelper c, PrepareSpawnTask task, GameProfile profile) {
            server = c.getLevel().getServer();
            wire = new NativePlayConnection(c.getLevel().registryAccess());
            connection = wire.connection; channel = wire.channel;
            try {
                player = task.spawnPlayer(connection, CommonListenerCookie.createInitial(profile, false));
                clientPosition = PositionMoveRotation.of(player);
                configure(); drain();
                server.getConnection().getConnections().add(connection);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        private void configure() {
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            var info = ClientInformation.createDefault();
            player.updateOptions(new ClientInformation(info.language(), 2, info.chatVisibility(), info.chatColors(),
                info.modelCustomisation(), info.mainHand(), info.textFilteringEnabled(), info.allowsListing(), info.particleStatus()));
            player.setNoGravity(true);
            player.setPermanentlyInvulnerable(true);
        }

        void drain() {
            if (closed) return;
            var packets = new ArrayList<Packet<?>>();
            for (var packet : wire.drain()) flatten(packet, packets);
            for (var packet : packets) {
                if (packet instanceof ClientboundRespawnPacket) {
                    // The real new-dimension packet has been encoded successfully.
                    player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                } else if (packet instanceof ClientboundPlayerPositionPacket position) {
                    clientPosition = PositionMoveRotation.calculateAbsolute(clientPosition, position.change(), position.relatives());
                    var at = clientPosition.position();
                    player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                        position.id(), at.x(), at.y(), at.z(), clientPosition.yRot(), clientPosition.xRot()));
                } else if (packet instanceof ClientboundChunkBatchFinishedPacket) {
                    player.connection.handleChunkBatchReceived(new ServerboundChunkBatchReceivedPacket(64f));
                    player.level().getChunkSource().move(player);
                }
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            server.getConnection().getConnections().remove(connection);
            if (player != null) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
                player.closeContainer();
                connection.disconnect(Component.literal("Mode transfer fixture finished"));
                connection.handleDisconnection();
            }
            channel.finishAndReleaseAll();
        }
    }

    private static void flatten(Packet<?> packet, List<Packet<?>> result) {
        if (packet instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(child -> flatten(child, result));
        else result.add(packet);
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper c;
        final MinecraftServer server;
        final ServerLevel hardcore;
        final ChunkPos coldChunk = new ChunkPos(24000, 24000);
        final Map<BlockPos, BlockState> terrain = new LinkedHashMap<>();
        final BlockPos feet;
        final CommunityServer.Place destination;
        Joined owner, observer;
        DestinationChunks.Lease fixtureLease;
        PrepareSpawnTask login;
        CompoundTag before;
        byte[] ownerFile, observerFile;
        int requested, playerTicks;
        boolean inventoryUpdated, closed;

        Scenario(GameTestHelper c) {
            this.c = c; server = c.getLevel().getServer(); hardcore = GameModes.world(server, GameModes.Mode.HARDCORE);
            feet = coldChunk.getBlockAt(8, hardcore.getMinY() + 129, 8);
            destination = new CommunityServer.Place(hardcore.dimension().identifier().toString(),
                feet.getX() + .5, feet.getY(), feet.getZ() + .5, 23, 0);
            try {
                owner = new Joined(c, "mode-cold-owner", new BlockPos(2, 2, 2));
                observer = new Joined(c, "mode-observer", new BlockPos(5, 2, 2));
                c.assertFalse(Memberships.gameplayBypass(owner.player), "The transfer fixture is an ordinary native player");
                c.assertTrue(hardcore.getChunkSource().getChunkNow(coldChunk.x(), coldChunk.z()) == null,
                    "The owned remote fixture starts physically cold");
                fixtureLease = DestinationChunks.acquire(hardcore, coldChunk);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        void guard(Runnable action) {
            if (closed) return;
            try { owner.drain(); observer.drain(); action.run(); }
            catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void later(int ticks, Runnable action) { c.runAfterDelay(ticks, () -> guard(action)); }
        void await(String label, int remaining, BooleanSupplier ready, Runnable waiting, Runnable done) {
            if (ready.getAsBoolean()) { done.run(); return; }
            c.assertTrue(remaining > 0, label + " completes within its bounded native tick window");
            waiting.run();
            later(1, () -> await(label, remaining - 1, ready, waiting, done));
        }
        void command(String text) {
            try { c.assertValueEqual(server.getCommands().getDispatcher().execute(text, owner.player.createCommandSourceStack()),
                1, "The real player command is accepted: " + text); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalStateException(failure); }
        }
        byte[] file(ServerPlayer player) {
            try { return Files.readAllBytes(GameModes.savedPlayerFile(server, player.getUUID())); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }

        void start() {
            await("Native fixture FULL loading", 180, () -> {
                c.assertTrue(fixtureLease.failure() == null, "The actual fixture lease has no native load failure");
                return fixtureLease.ready();
            }, () -> {}, () -> {
                // Only disposable fixture terrain is changed, before the measured request.
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = -1; y <= 2; y++) {
                    var at = feet.offset(x, y, z); terrain.put(at, hardcore.getBlockState(at));
                    c.assertTrue(hardcore.setBlock(at, (y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState(), BLOCK_FLAGS)
                        || hardcore.getBlockState(at).is(y == -1 ? Blocks.STONE : Blocks.AIR), "Owned fixture terrain is in native world bounds");
                }
                c.assertTrue(CommunityServer.safe(hardcore, destination), "The saved fixture is dry and safe before native unloading");
                fixtureLease.close(); fixtureLease = null;
                await("Physical fixture unloading", 180,
                    () -> hardcore.getChunkSource().getChunkNow(coldChunk.x(), coldChunk.z()) == null,
                    () -> {}, this::requestCold);
            });
        }

        void requestCold() {
            var p = owner.player;
            var profile = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, p.registryAccess());
            profile.store("inventory", ItemStackWithSlot.CODEC.listOf(), List.of(new ItemStackWithSlot(0, new ItemStack(Items.EMERALD, 11))));
            profile.putString("place", CommunityServer.GSON.toJson(destination));
            profile.putFloat("health", 20); profile.putInt("foodLevel", 20); profile.putFloat("foodSaturationLevel", 5);
            var profiles = GameModes.state(p).getCompoundOrEmpty("profiles");
            profiles.put("HARDCORE", profile.buildResult()); GameModes.state(p).put("profiles", profiles);
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            p.getInventory().setItem(8, ServerMenu.navigator(p));
            p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 2));
            observer.player.getInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 4));
            PlayerPersistence.save(p); PlayerPersistence.save(observer.player);
            before = GameModes.state(p).copy(); ownerFile = file(p); observerFile = file(observer.player);
            GameModes.state(observer.player).putBoolean("unsaved_observer", true);
            c.assertTrue(hardcore.getChunkSource().getChunkNow(coldChunk.x(), coldChunk.z()) == null,
                "The actual saved destination remains cold immediately before /play");
            requested = server.getTickCount(); playerTicks = p.tickCount;
            command("play hardcore");
            c.assertTrue(GameModes.PENDING.containsKey(p.getUUID()), "Ordinary /play enters the real preparation and warmup path");
            await("Cold ordinary transfer", 240, () -> GameModes.current(p) == GameModes.Mode.HARDCORE, () -> {
                c.assertValueEqual(GameModes.current(p), GameModes.Mode.SURVIVAL, "Waiting retains the old mode");
                c.assertValueEqual(p.level(), c.getLevel(), "Waiting retains the old realm");
                c.assertValueEqual(GameModes.state(p), before, "Waiting never captures, removes or restores profiles");
                c.assertValueEqual(p.inventoryMenu.getCarried().getCount(), 2, "Waiting never consumes the carried cursor");
                c.assertTrue(Arrays.equals(ownerFile, file(p)), "Waiting does not save an intermediate participant state");
                c.assertTrue(Arrays.equals(observerFile, file(observer.player)), "Waiting does not save unrelated online players");
                c.assertTrue(GameModes.PENDING.containsKey(p.getUUID()), "The valid request stays pending until its real destination is ready");
                if (!inventoryUpdated && server.getTickCount() - requested >= 20) {
                    p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 9)); inventoryUpdated = true;
                }
            }, this::verifyColdSuccess);
        }

        void verifyColdSuccess() {
            var p = owner.player;
            c.assertTrue(server.getTickCount() - requested >= 60, "Cold readiness never skips the ordinary 60-tick warmup");
            c.assertTrue(p.tickCount - playerTicks >= 60, "The connected native player keeps ticking while its destination is prepared");
            c.assertTrue(inventoryUpdated, "Inventory changed during the waiting window");
            c.assertValueEqual(p.level(), hardcore, "Commit enters the requested real Hardcore world");
            c.assertTrue(p.position().distanceToSqr(new net.minecraft.world.phys.Vec3(destination.x(), destination.y(), destination.z())) < .001,
                "A cold saved home wins over a warm origin fallback");
            c.assertTrue(p.getInventory().getItem(0).is(Items.EMERALD) && p.getInventory().getItem(0).getCount() == 11,
                "Commit restores the destination inventory");
            c.assertTrue(p.inventoryMenu.getCarried().isEmpty(), "Commit returns the cursor to the old profile before restore");
            var survival = GameModes.state(p).getCompoundOrEmpty("profiles").getCompoundOrEmpty("SURVIVAL");
            c.assertValueEqual(profileCount(p, survival, Items.DIAMOND), 9, "Commit captures the current old inventory, including changes made while waiting");
            c.assertValueEqual(profileCount(p, survival, Items.EMERALD), 2, "The exact old cursor stack is conserved in its original mode");
            try {
                var saved = NbtIo.readCompressed(GameModes.savedPlayerFile(server, p.getUUID()), NbtAccounter.create(64L * 1024 * 1024));
                c.assertValueEqual(saved.getStringOr("Dimension", ""), destination.dimension(), "Native participant persistence contains the actual committed realm");
                c.assertValueEqual(saved.getCompoundOrEmpty("InfinityModes").getStringOr("active", ""), "HARDCORE", "Native participant persistence contains the committed mode");
            } catch (IOException failure) { throw new IllegalStateException(failure); }
            c.assertTrue(Arrays.equals(observerFile, file(observer.player)), "Successful participant commit never saves the observer's unsaved state");
            command("play survival");
            await("Ordinary return to Survival", 180, () -> GameModes.current(p) == GameModes.Mode.SURVIVAL, () -> {}, this::movementCancellation);
        }

        void movementCancellation() {
            var p = owner.player;
            c.assertValueEqual(itemCount(p, Items.DIAMOND), 9, "The real return restores all current Survival diamonds");
            c.assertValueEqual(itemCount(p, Items.EMERALD), 2, "Only the conserved old cursor returns to Survival");
            command("menu");
            c.assertTrue(p.containerMenu instanceof ServerMenu.Handler, "The cancellation route opens the real Infinity Menu");
            p.containerMenu.clicked(ServerMenu.HARDCORE, 0, ContainerInput.PICKUP, p);
            c.assertTrue(GameModes.PENDING.containsKey(p.getUUID()), "The actual menu click starts ordinary preparation");
            var state = GameModes.state(p).copy(); var saved = file(p);
            later(2, () -> {
                var origin = GameModes.PENDING.get(p.getUUID()).origin();
                p.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(p.getX() + .5, p.getY(), p.getZ(), true, false));
                c.assertTrue(CommunityServer.moved(origin, p), "The real inbound movement packet moved the native player beyond the cancellation threshold");
                later(3, () -> {
                    c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "Native movement cancels the real pending menu request");
                    c.assertValueEqual(GameModes.state(p), state, "Movement cancellation never captures or restores profiles");
                    c.assertTrue(Arrays.equals(saved, file(p)), "Movement cancellation never persists a partial switch");
                    later(65, () -> {
                        c.assertValueEqual(GameModes.current(p), GameModes.Mode.SURVIVAL, "A later chunk completion cannot resurrect the canceled request");
                        deathAndRespawn();
                    });
                });
            });
        }

        void deathAndRespawn() {
            var p = owner.player; var hardcoreProfile = GameModes.state(p).getCompoundOrEmpty("profiles").getCompoundOrEmpty("HARDCORE").copy();
            var at = c.absolutePos(new BlockPos(2, 2, 2));
            p.setRespawnPosition(new ServerPlayer.RespawnConfig(LevelData.RespawnData.of(p.level().dimension(), at, 0, 0), true), false);
            command("play hardcore");
            c.assertTrue(GameModes.PENDING.containsKey(p.getUUID()), "A new ordinary request is pending before actual death");
            p.hasChangedDimension(); p.setPermanentlyInvulnerable(false);
            c.assertTrue(p.hurtServer(p.level(), p.damageSources().genericKill(), Float.MAX_VALUE), "Actual native damage reaches the joined player");
            c.assertFalse(p.isAlive(), "The fixture died through the native damage path");
            c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "Positive native damage and death cancel destination preparation");
            p.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            owner.player = p.connection.player;
            c.assertTrue(owner.player != p && owner.player.isAlive(), "The real death packet creates an alive native replacement player");
            c.assertValueEqual(GameModes.current(owner.player), GameModes.Mode.SURVIVAL, "Pending destination state cannot leak into native respawn");
            c.assertValueEqual(GameModes.of(owner.player.level()), GameModes.Mode.SURVIVAL, "Native respawn retains the Survival realm policy");
            c.assertValueEqual(GameModes.state(owner.player).getCompoundOrEmpty("profiles").getCompoundOrEmpty("HARDCORE"), hardcoreProfile,
                "Actual death and native respawn preserve the inactive Hardcore profile");
            owner.player.setNoGravity(true); owner.player.setPermanentlyInvulnerable(true);
            owner.drain();
            // The actual damage cooldown is respected by the next ordinary request.
            later(201, this::reconnect);
        }

        void reconnect() {
            var old = owner; var profile = old.player.getGameProfile(); var id = old.player.getUUID();
            PlayerPersistence.save(old.player);
            command("play hardcore");
            c.assertTrue(GameModes.PENDING.containsKey(old.player.getUUID()), "The old native connection has an actual pending request before disconnect");
            old.close();
            c.assertFalse(GameModes.PENDING.containsKey(id), "Native disconnect cancels the old session's request");
            c.assertTrue(server.getPlayerList().getPlayer(id) == null, "Native disconnect removes the old player before reconnect");
            login = new PrepareSpawnTask(server, old.player.nameAndId());
            login.start(packet -> {});
            await("Native reconnect configuration", 180, () -> login.tick(), () -> {}, () -> {
                owner = new Joined(c, login, profile);
                c.assertValueEqual(GameModes.current(owner.player), GameModes.Mode.SURVIVAL, "The real native login factory loads the last committed mode");
                c.assertValueEqual(GameModes.of(owner.player.level()), GameModes.Mode.SURVIVAL, "The real login factory selects the matching saved realm");
                c.assertFalse(GameModes.PENDING.containsKey(id), "Reconnecting never reuses an old pending request");
                command("play hardcore");
                var replacement = GameModes.PENDING.get(id);
                c.assertTrue(replacement != null, "The replacement native session may submit a fresh ordinary request");
                // A stale object cannot overwrite or cancel the replacement's request.
                GameModes.request(old.player, GameModes.Mode.CREATIVE, null);
                c.assertTrue(GameModes.PENDING.get(id) == replacement, "A stale old player cannot replace the fresh session's pending request");
                ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(old.player.connection, server);
                c.assertTrue(GameModes.PENDING.get(id) == replacement, "A late old disconnect callback cannot cancel the replacement request");
                var started = server.getTickCount();
                await("Replacement session transfer", 180, () -> GameModes.current(owner.player) == GameModes.Mode.HARDCORE, () -> {}, () -> {
                    c.assertTrue(server.getTickCount() - started >= 60, "The reconnected ordinary session still honors the native warmup window");
                    c.assertTrue(owner.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(destination.x(), destination.y(), destination.z())) < .001,
                        "The fresh session resumes its own saved home rather than an old callback's destination");
                    c.assertTrue(server.getPlayerList().getPlayer(id) == owner.player, "Only the replacement object remains in PlayerList at completion");
                    close(); c.succeed();
                });
            });
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            var cleanup = List.<Runnable>of(
                () -> { if (fixtureLease != null) { fixtureLease.close(); fixtureLease = null; } },
                () -> { if (login != null) login.close(); },
                () -> { if (owner != null) owner.close(); },
                () -> { if (observer != null) observer.close(); },
                () -> {
                    if (!terrain.isEmpty()) {
                        // Cleanup may reload only the one owned fixture chunk;
                        // measured requests never use a synchronous load.
                        hardcore.getChunk(coldChunk.x(), coldChunk.z());
                        for (var entry : terrain.entrySet()) hardcore.setBlock(entry.getKey(), entry.getValue(), BLOCK_FLAGS);
                        terrain.clear();
                    }
                });
            Throwable problem = null;
            for (var action : cleanup) try { action.run(); }
            catch (RuntimeException | Error failure) {
                if (problem == null) problem = failure; else problem.addSuppressed(failure);
            }
            if (problem instanceof RuntimeException runtime) throw runtime;
            if (problem instanceof Error error) throw error;
        }
    }

    private static int profileCount(ServerPlayer player, CompoundTag profile, net.minecraft.world.item.Item item) {
        int count = 0;
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), profile);
        for (var stack : input.listOrEmpty("inventory", ItemStackWithSlot.CODEC)) if (stack.stack().is(item)) count += stack.stack().getCount();
        return count;
    }
    private static int itemCount(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot); if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    @GameTest(maxTicks = 1800) public void nativeColdSavedTransferPreservesWaitingStateAndSessionCancellation(GameTestHelper c) {
        var scenario = new Scenario(c);
        scenario.guard(scenario::start);
    }
}
