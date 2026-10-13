package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

/** Warm destination fixtures; these cases prove gameplay policy, not cold loading. */
public class HardcoreExitGameTests {
    private static final ServerboundClientCommandPacket RESPAWN =
        new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);

    private static final class Joined implements AutoCloseable {
        final MinecraftServer server;
        final NativePlayConnection wire;
        ServerPlayer player;
        PositionMoveRotation clientPosition;
        boolean closed;

        Joined(GameTestHelper c, String name) {
            server = c.getLevel().getServer();
            wire = new NativePlayConnection(c.getLevel().registryAccess());
            try {
                // This existing helper explicitly loads the full native collision
                // halos before any measured request and joins an ordinary player.
                player = new ModeGameTests().player(c, name, wire.connection);
                clientPosition = PositionMoveRotation.of(player);
                configure();
                player.connection.teleport(player.getX(), player.getY(), player.getZ(), 0, 0);
                player.getInventory().setItem(8, ServerMenu.navigator(player));
                drain();
                server.getConnection().getConnections().add(wire.connection);
                c.assertFalse(Memberships.gameplayBypass(player), "The native fixture has no OP or Admin bypass");
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void configure() {
            var info = ClientInformation.createDefault();
            player.updateOptions(new ClientInformation(info.language(), 2, info.chatVisibility(), info.chatColors(),
                info.modelCustomisation(), info.mainHand(), info.textFilteringEnabled(), info.allowsListing(), info.particleStatus()));
            player.setNoGravity(true);
            // Environmental damage cannot perturb the fixture. The deliberately
            // used native genericKill source bypasses invulnerability, even at 1 HP.
            player.setPermanentlyInvulnerable(true);
            player.getFoodData().setFoodLevel(6);
            player.getFoodData().setSaturation(0);
        }

        void drain() {
            if (closed) return;
            for (var packet : wire.drain()) acknowledge(packet);
        }

        void acknowledge(Packet<?> packet) {
            if (packet instanceof ClientboundBundlePacket bundle) {
                for (var child : bundle.subPackets()) acknowledge(child);
            } else if (packet instanceof ClientboundRespawnPacket) {
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

        @Override public void close() {
            if (closed) return;
            closed = true;
            server.getConnection().getConnections().remove(wire.connection);
            try {
                if (player != null) {
                    player.containerMenu.setCarried(ItemStack.EMPTY);
                    player.closeContainer();
                    wire.connection.disconnect(Component.literal("Hardcore exit fixture finished"));
                    wire.connection.handleDisconnection();
                }
            } finally { wire.channel.finishAndReleaseAll(); }
        }
    }

    private static final class Scenario implements AutoCloseable {
        final GameTestHelper c;
        final MinecraftServer server;
        final Map<BlockPos, BlockState> terrain = new LinkedHashMap<>();
        Joined owner;
        Joined observer;
        boolean closed;

        Scenario(GameTestHelper c, String name, boolean withObserver) {
            this.c = c; server = c.getLevel().getServer();
            var feet = c.absolutePos(new BlockPos(2, 2, 2));
            for (var at : List.of(feet.below(), feet, feet.above())) terrain.put(at, c.getLevel().getBlockState(at));
            try {
                owner = new Joined(c, name);
                if (withObserver) {
                    observer = new Joined(c, "hcx-observer");
                    observer.player.setPos(owner.player.getX() + 4, owner.player.getY() + 5, owner.player.getZ());
                }
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        void guard(Runnable action) {
            if (closed) return;
            try { owner.drain(); if (observer != null) observer.drain(); action.run(); }
            catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void later(int ticks, Runnable action) { c.runAfterDelay(ticks, () -> guard(action)); }
        void await(String label, int remaining, BooleanSupplier ready, Runnable waiting, Runnable done) {
            if (ready.getAsBoolean()) { done.run(); return; }
            c.assertTrue(remaining > 0, label + " finishes within its bounded native tick window");
            waiting.run();
            later(1, () -> await(label, remaining - 1, ready, waiting, done));
        }
        void command(String command) {
            try { c.assertValueEqual(server.getCommands().getDispatcher().execute(command, owner.player.createCommandSourceStack()),
                1, "The registered player command returns normally: " + command); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalStateException(failure); }
        }
        int cooldown() { return CommunityServer.get(server).combat.getOrDefault(owner.player.getUUID(), 0); }
        byte[] file(ServerPlayer p) {
            try { return Files.readAllBytes(GameModes.savedPlayerFile(server, p.getUUID())); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        void nonfatalDamage() {
            var p = owner.player; float health = p.getHealth(); int now = server.getTickCount();
            c.assertTrue(p.hurtServer(p.level(), p.damageSources().genericKill(), 1), "The native nonfatal damage path accepts the hit");
            c.assertTrue(p.isAlive() && p.getHealth() < health, "The real native hit damages the living player");
            c.assertValueEqual(cooldown(), now + CommunityServer.COOLDOWN, "Registered damage callbacks set the real 200-tick cooldown");
        }
        void enterHardcore(Runnable done) {
            int requested = server.getTickCount(); command("play hardcore");
            c.assertTrue(GameModes.PENDING.containsKey(owner.player.getUUID()), "Ordinary Hardcore entry starts a real countdown");
            await("Ordinary Hardcore entry", 120, () -> GameModes.current(owner.player) == GameModes.Mode.HARDCORE,
                () -> c.assertTrue(GameModes.PENDING.containsKey(owner.player.getUUID()), "The fixture entry request remains pending"), () -> {
                    c.assertTrue(server.getTickCount() - requested >= 60, "Fixture entry uses the ordinary 60-tick countdown");
                    c.assertValueEqual(GameModes.of(owner.player.level()), GameModes.Mode.HARDCORE, "Entry reaches the actual Hardcore realm");
                    c.assertValueEqual(owner.player.gameMode(), GameType.SURVIVAL, "The Hardcore player initially has a living life");
                    owner.drain(); done.run();
                });
        }
        void succeed() { close(); c.succeed(); }

        @Override public void close() {
            if (closed) return;
            closed = true;
            var cleanup = new ArrayList<Runnable>();
            if (owner != null) cleanup.add(owner::close);
            if (observer != null) cleanup.add(observer::close);
            cleanup.add(() -> {
                for (var entry : terrain.entrySet()) c.getLevel().setBlock(entry.getKey(), entry.getValue(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                terrain.clear();
            });
            Throwable problem = null;
            for (var action : cleanup) try { action.run(); }
            catch (RuntimeException | Error failure) { if (problem == null) problem = failure; else problem.addSuppressed(failure); }
            if (problem instanceof RuntimeException runtime) throw runtime;
            if (problem instanceof Error error) throw error;
        }
    }

    @GameTest(maxTicks = 500)
    public void eliminatedSpectatorExitsDuringNativeCombatCooldownAfterFullCountdown(GameTestHelper c) {
        var s = new Scenario(c, "hcx-exit", true);
        s.guard(() -> {
            var p = s.owner.player;
            p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            p.getEnderChestInventory().setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
            s.observer.player.getInventory().setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            PlayerPersistence.save(s.observer.player); byte[] observerFile = s.file(s.observer.player);
            GameModes.state(s.observer.player).putString("unsaved_exit_observer", "must stay in memory");
            s.enterHardcore(() -> {
                var old = s.owner.player; int deathTick = s.server.getTickCount();
                c.assertTrue(old.hurtServer(old.level(), old.damageSources().genericKill(), Float.MAX_VALUE), "The actual native lethal hit is accepted");
                c.assertFalse(old.isAlive(), "Native damage actually kills the Hardcore player");
                c.assertTrue(GameModes.state(old).getBooleanOr("eliminated", false), "AFTER_DEATH records the actual Hardcore elimination");
                c.assertValueEqual(s.cooldown(), deathTick + 200, "The fatal hit establishes the native 200-tick combat window");
                old.connection.handleClientCommand(RESPAWN);
                var fresh = s.server.getPlayerList().getPlayer(old.getUUID());
                c.assertTrue(fresh != null && fresh != old && fresh.connection.player == fresh, "PERFORM_RESPAWN replaces the old player and rebinds its actual listener");
                s.owner.player = fresh; s.owner.configure(); s.owner.drain();
                c.assertTrue(fresh.isAlive(), "Native respawn creates a live spectator object");
                c.assertValueEqual(fresh.gameMode(), GameType.SPECTATOR, "Registered AFTER_RESPAWN grants only spectator abilities");
                c.assertValueEqual(GameModes.current(fresh), GameModes.Mode.HARDCORE, "Respawn preserves the Hardcore profile and realm");
                c.assertValueEqual(GameModes.of(fresh.level()), GameModes.Mode.HARDCORE, "Spectating stays in the actual Hardcore realm");
                fresh.getInventory().setItem(0, new ItemStack(Items.EMERALD, 11));
                fresh.getInventory().setItem(8, ServerMenu.navigator(fresh));
                fresh.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND_BLOCK, 2));
                fresh.inventoryMenu.setCarried(new ItemStack(Items.GOLD_INGOT, 3));
                PlayerPersistence.save(fresh);
                var before = GameModes.state(fresh).copy(); var inventory = inventoryCopy(fresh);
                var ender = enderCopy(fresh); var cursor = fresh.inventoryMenu.getCarried().copy();
                byte[] ownerFile = s.file(fresh); int requested = s.server.getTickCount();
                s.command("play survival"); var request = GameModes.PENDING.get(fresh.getUUID());
                c.assertTrue(request != null && !request.immediate(), "The eliminated spectator uses an ordinary countdown, not the trusted immediate route");
                c.assertValueEqual(request.finish(), requested + 60, "The actual ordinary deadline remains exactly 60 ticks");
                s.command("play hardcore");
                c.assertTrue(GameModes.PENDING.get(fresh.getUUID()) == request, "Elimination rejects re-entry without replacing the already accepted exit");
                s.await("Eliminated Hardcore exit", 120, () -> GameModes.current(fresh) == GameModes.Mode.SURVIVAL, () -> {
                    c.assertTrue(s.cooldown() > s.server.getTickCount(), "The inherited native damage cooldown remains active throughout the exit countdown");
                    c.assertTrue(GameModes.PENDING.get(fresh.getUUID()) == request, "The accepted spectator exit survives native polling despite inherited combat");
                    c.assertValueEqual(GameModes.current(fresh), GameModes.Mode.HARDCORE, "Waiting keeps the original mode");
                    c.assertValueEqual(fresh.gameMode(), GameType.SPECTATOR, "Waiting never grants a second Hardcore life");
                    c.assertValueEqual(GameModes.state(fresh), before, "Waiting never captures, removes or restores profiles");
                    assertContents(c, fresh, inventory, ender, cursor);
                    c.assertTrue(Arrays.equals(ownerFile, s.file(fresh)), "Waiting never saves an intermediate player state");
                    c.assertTrue(Arrays.equals(observerFile, s.file(s.observer.player)), "Waiting never saves an unrelated online player");
                }, () -> {
                    c.assertTrue(s.server.getTickCount() - requested >= 60, "The completed exit honors the full ordinary countdown");
                    c.assertTrue(s.cooldown() > s.server.getTickCount(), "Exit succeeds before the actual death cooldown expires");
                    c.assertFalse(GameModes.PENDING.containsKey(fresh.getUUID()), "Successful exit closes its actual pending preparation");
                    c.assertValueEqual(fresh.gameMode(), GameType.SURVIVAL, "Only the new Survival realm grants living abilities");
                    c.assertValueEqual(GameModes.of(fresh.level()), GameModes.Mode.SURVIVAL, "Commit reaches the matching Survival realm");
                    c.assertValueEqual(itemCount(fresh, Items.DIAMOND), 7, "Commit restores the original Survival inventory");
                    c.assertValueEqual(itemCount(fresh, Items.EMERALD), 0, "Hardcore inventory cannot enter Survival");
                    c.assertValueEqual(itemCount(fresh, Items.GOLD_INGOT), 0, "The old cursor cannot leak into Survival");
                    c.assertValueEqual(fresh.getEnderChestInventory().getItem(0).getCount(), 5, "The original Survival Ender chest is restored");
                    c.assertTrue(fresh.inventoryMenu.getCarried().isEmpty(), "Commit conserves the old cursor inside its original profile");
                    var state = GameModes.state(fresh); var profiles = state.getCompoundOrEmpty("profiles");
                    c.assertTrue(state.getBooleanOr("eliminated", false), "Exit never erases the one-life marker");
                    c.assertFalse(profiles.contains("SURVIVAL"), "The restored Survival profile is consumed exactly once");
                    var hardcore = profiles.getCompoundOrEmpty("HARDCORE");
                    c.assertValueEqual(profileCount(fresh, hardcore, "inventory", Items.EMERALD), 11, "Commit saves the exact Hardcore inventory");
                    c.assertValueEqual(profileCount(fresh, hardcore, "inventory", Items.GOLD_INGOT), 3, "Commit saves the exact old cursor count in Hardcore");
                    c.assertValueEqual(profileCount(fresh, hardcore, "ender", Items.DIAMOND_BLOCK), 2, "Commit preserves the separate Hardcore Ender chest");
                    verifyNativeSave(c, s, fresh);
                    c.assertTrue(Arrays.equals(observerFile, s.file(s.observer.player)), "Successful participant commit never saves the unrelated observer");
                    byte[] committed = s.file(fresh); var committedState = state.copy();
                    s.await("Actual death cooldown expiry", 201, () -> s.server.getTickCount() >= deathTick + 200, () -> {}, () -> {
                        c.assertTrue(s.cooldown() <= s.server.getTickCount(), "Re-entry is checked after actual cooldown expiry, not hidden behind combat rejection");
                        s.command("play hardcore");
                        c.assertFalse(GameModes.PENDING.containsKey(fresh.getUUID()), "An eliminated ordinary player still cannot re-enter Hardcore");
                        c.assertValueEqual(GameModes.current(fresh), GameModes.Mode.SURVIVAL, "Re-entry refusal keeps the matching Survival mode");
                        c.assertValueEqual(GameModes.state(fresh), committedState, "Re-entry refusal preserves all mode profiles and elimination");
                        c.assertTrue(Arrays.equals(committed, s.file(fresh)), "Re-entry refusal does not rewrite the committed player file");
                        s.succeed();
                    });
                });
            });
        });
    }

    @GameTest(maxTicks = 500)
    public void livingHardcoreStillRejectsCombatAndCancelsOnNewNativeDamage(GameTestHelper c) {
        var s = new Scenario(c, "hcx-hard-live", false);
        s.guard(() -> s.enterHardcore(() -> livingCombat(s, GameModes.Mode.HARDCORE, "play survival")));
    }

    @GameTest(maxTicks = 500)
    public void ordinarySurvivalStillRejectsCombatAndCancelsOnNewNativeDamage(GameTestHelper c) {
        var s = new Scenario(c, "hcx-surv-live", false);
        s.guard(() -> livingCombat(s, GameModes.Mode.SURVIVAL, "play creative"));
    }

    private static void livingCombat(Scenario s, GameModes.Mode mode, String exit) {
        var c = s.c; var p = s.owner.player;
        c.assertFalse(GameModes.state(p).getBooleanOr("eliminated", false), "The combat guard fixture has a living life");
        if (mode == GameModes.Mode.HARDCORE) {
            // Separate this living fixture from the other case's real death
            // drops at the shared native default spawn, without changing terrain.
            p.connection.teleport(p.getX() + 8, p.getY() + 8, p.getZ() + 8, 0, 0);
            s.owner.drain();
        }
        p.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
        p.inventoryMenu.setCarried(new ItemStack(Items.EMERALD, 2));
        s.nonfatalDamage(); int until = s.cooldown();
        PlayerPersistence.save(p); byte[] saved = s.file(p); var state = GameModes.state(p).copy();
        var inventory = inventoryCopy(p); var ender = enderCopy(p); var cursor = p.inventoryMenu.getCarried().copy();
        s.command(exit);
        c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "A living " + mode + " player cannot escape the real active damage cooldown");
        c.assertValueEqual(GameModes.current(p), mode, "Rejected combat exit keeps the original mode");
        c.assertValueEqual(GameModes.state(p), state, "Combat rejection preserves profiles");
        assertContents(c, p, inventory, ender, cursor);
        c.assertTrue(Arrays.equals(saved, s.file(p)), "Combat rejection never writes a partial mode state");
        s.await("Actual living-player cooldown expiry", 201, () -> s.server.getTickCount() >= until, () -> {}, () -> {
            c.assertTrue(s.cooldown() <= s.server.getTickCount(), "Only normal native ticks expire the living player's cooldown");
            s.command(exit); var request = GameModes.PENDING.get(p.getUUID());
            c.assertTrue(request != null && !request.immediate(), "The living player's request is accepted only after actual cooldown expiry");
            s.later(2, () -> {
                c.assertTrue(GameModes.PENDING.get(p.getUUID()) == request, "The accepted ordinary request remains pending before new damage");
                s.nonfatalDamage();
                c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "Registered new native damage immediately cancels a living player's pending exit");
                c.assertValueEqual(GameModes.state(p), state, "Damage cancellation never captures or restores mode profiles");
                assertContents(c, p, inventory, ender, cursor);
                c.assertTrue(Arrays.equals(saved, s.file(p)), "Damage cancellation never rewrites the player file");
                s.later(65, () -> {
                    c.assertValueEqual(GameModes.current(p), mode, "A later destination completion cannot resurrect the damaged player's canceled request");
                    c.assertFalse(GameModes.PENDING.containsKey(p.getUUID()), "The canceled request remains closed past its original deadline");
                    c.assertValueEqual(GameModes.state(p), state, "The abandoned preparation never changes profiles later");
                    assertContents(c, p, inventory, ender, cursor);
                    c.assertTrue(Arrays.equals(saved, s.file(p)), "The abandoned preparation never persists later");
                    s.succeed();
                });
            });
        });
    }

    private static List<ItemStack> inventoryCopy(ServerPlayer p) {
        var stacks = new ArrayList<ItemStack>();
        for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) stacks.add(p.getInventory().getItem(slot).copy());
        return stacks;
    }
    private static List<ItemStack> enderCopy(ServerPlayer p) {
        var stacks = new ArrayList<ItemStack>();
        for (int slot = 0; slot < p.getEnderChestInventory().getContainerSize(); slot++) stacks.add(p.getEnderChestInventory().getItem(slot).copy());
        return stacks;
    }
    private static void assertContents(GameTestHelper c, ServerPlayer p, List<ItemStack> inventory, List<ItemStack> ender, ItemStack cursor) {
        for (int slot = 0; slot < inventory.size(); slot++) c.assertTrue(ItemStack.matches(p.getInventory().getItem(slot), inventory.get(slot)), "Original inventory slot is conserved: " + slot);
        for (int slot = 0; slot < ender.size(); slot++) c.assertTrue(ItemStack.matches(p.getEnderChestInventory().getItem(slot), ender.get(slot)), "Original Ender chest slot is conserved: " + slot);
        c.assertTrue(ItemStack.matches(p.inventoryMenu.getCarried(), cursor), "The original cursor count and components are conserved");
    }
    private static int itemCount(ServerPlayer p, Item item) {
        int count = 0;
        for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) if (p.getInventory().getItem(slot).is(item)) count += p.getInventory().getItem(slot).getCount();
        return count;
    }
    private static int profileCount(ServerPlayer p, CompoundTag profile, String field, Item item) {
        int count = 0;
        var input = TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), profile);
        for (var stack : input.listOrEmpty(field, ItemStackWithSlot.CODEC)) if (stack.stack().is(item)) count += stack.stack().getCount();
        return count;
    }
    private static void verifyNativeSave(GameTestHelper c, Scenario s, ServerPlayer p) {
        try {
            var saved = NbtIo.readCompressed(GameModes.savedPlayerFile(s.server, p.getUUID()), NbtAccounter.create(64L * 1024 * 1024));
            c.assertValueEqual(saved.getStringOr("Dimension", ""), p.level().dimension().identifier().toString(), "The actual player file stores the committed realm");
            c.assertValueEqual(saved.getCompoundOrEmpty("InfinityModes"), GameModes.state(p), "The actual player file stores the complete committed profiles and one-life marker");
            var restored = new ServerPlayer(s.server, p.level(), p.getGameProfile(), p.clientInformation());
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, p.registryAccess(), saved));
            c.assertValueEqual(itemCount(restored, Items.DIAMOND), 7, "The actual saved native inventory reloads the original Survival diamonds");
            c.assertValueEqual(itemCount(restored, Items.EMERALD), 0, "The actual saved native inventory contains no leaked Hardcore emeralds");
            c.assertValueEqual(profileCount(restored, GameModes.state(restored).getCompoundOrEmpty("profiles").getCompoundOrEmpty("HARDCORE"), "inventory", Items.GOLD_INGOT), 3,
                "Native file reload retains the exact conserved Hardcore cursor");
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
}
