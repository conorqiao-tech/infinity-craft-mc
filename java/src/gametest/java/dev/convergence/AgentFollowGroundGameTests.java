package dev.convergence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Native lifecycle following, player-sized passages and grounded safety. */
public class AgentFollowGroundGameTests {
    private static final class FollowArena implements AutoCloseable {
        private static long sequence = 2200;
        final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++sequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final List<ChunkPos> chunks = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final NativeSessionFixture.Joined session;
        final AgentCompanions helpers;
        final IronGolem helper;
        final Vec3 start;
        final UUID id;
        final float health;
        final double speed, scale;
        final ArrayDeque<String> trace = new ArrayDeque<>();
        Vec3 previous;

        FollowArena(GameTestHelper c, String name, double ownerX, double ownerY, boolean tunnel, boolean missingFloor) {
            session = new NativeSessionFixture.Joined(c, name, 3);
            var owner = session.player;
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(ownerY > 0 ? GameType.CREATIVE : GameType.SURVIVAL);
            if (ownerY > 0) { owner.getAbilities().flying = true; owner.onUpdateAbilities(); }
            var feet = c.absolutePos(new BlockPos(20, 55, 20));
            for (var at : BlockPos.betweenClosed(feet.offset(-21, -1, -21), feet.offset(21, 13, 21))) {
                boolean floor = at.getY() == feet.getY() - 1 && (!missingFloor || at.getX() < feet.getX() + 3);
                c.getLevel().setBlockAndUpdate(at, floor ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            for (int x = (feet.getX() - 21) >> 4; x <= (feet.getX() + 21) >> 4; x++)
                for (int z = (feet.getZ() - 21) >> 4; z <= (feet.getZ() + 21) >> 4; z++) {
                    var chunk = new ChunkPos(x, z); chunks.add(chunk);
                    c.getLevel().getChunkSource().addTicketWithRadius(ticket, chunk, 2);
                }
            start = Vec3.atBottomCenterOf(feet);
            owner.connection.teleport(start.x, start.y, start.z, 0, 0); session.drain();
            helpers = AgentCompanions.get(c.getLevel().getServer());
            helpers.spawn(owner, "walker");
            var record = helpers.owned(owner, "walker");
            if (record == null) throw new IllegalStateException("Normal helper creation failed");
            helper = helpers.loaded.get(UUID.fromString(record.getKey()));
            // Fixed initial placement only. Every later helper position is
            // produced by native navigation, collision and movement ticks.
            helper.setPos(start);
            id = helper.getUUID(); health = helper.getHealth();
            speed = helper.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue();
            scale = helper.getAttribute(Attributes.SCALE).getBaseValue();
            previous = helper.position();
            if (tunnel) {
                // A full divider prevents walking around the one-block-wide,
                // two-block-high passage instead of actually traversing it.
                for (var at : BlockPos.betweenClosed(feet.offset(2, 0, -21), feet.offset(8, 5, 21)))
                    c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
                for (var at : BlockPos.betweenClosed(feet.offset(2, 0, 0), feet.offset(8, 1, 0)))
                    c.getLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            }
            owner.connection.teleport(start.x + ownerX, start.y + ownerY, start.z, 0, 0); session.drain();
        }

        void observe(GameTestHelper c, int tick) {
            session.drain();
            var at = helper.position();
            c.assertTrue(at.distanceTo(previous) < .95, "Following uses bounded native walking, not a teleport" + diagnostic());
            c.assertFalse(helper.isNoGravity() || helper.isFallFlying(), "Following never invents helper flight" + diagnostic());
            c.assertTrue(at.y <= start.y + .65 && at.y >= start.y - .15, "The helper stays on the supported flat floor" + diagnostic());
            previous = at;
            if (tick % 5 == 0) {
                var phase = helpers.comboFeedback(id);
                trace.add(String.format(Locale.ROOT, "t=%d helper=%.2f/%.2f/%.2f owner=%.2f/%.2f/%.2f pathDone=%s phase=%s",
                    tick, at.x - start.x, at.y - start.y, at.z - start.z,
                    session.player.getX() - start.x, session.player.getY() - start.y, session.player.getZ() - start.z,
                    helper.getNavigation().isDone(), phase == null ? "none" : phase.phase() + ": " + phase.detail()));
                if (trace.size() > 36) trace.removeFirst();
            }
        }

        void unchanged(GameTestHelper c) {
            c.assertTrue(helpers.loaded.get(id) == helper && helper.getUUID().equals(id), "Following preserves the exact saved entity UUID");
            c.assertValueEqual(helper.getHealth(), health, "Following preserves current health");
            c.assertValueEqual(helper.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(), speed, "Following does not rewrite movement stats");
            c.assertValueEqual(helper.getAttribute(Attributes.SCALE).getBaseValue(), scale, "Following does not rewrite scale");
        }

        String diagnostic() { return " trace=" + trace; }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            OperatorGameTests.level(session.player, LevelBasedPermissionSet.OWNER);
            helpers.dismiss(session.player, "walker");
            for (var chunk : chunks) session.player.level().getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
            session.close();
        }
    }

    private void exercise(GameTestHelper c, FollowArena arena, int ticks, IntConsumer input, Runnable assertions) {
        for (int tick = 1; tick <= ticks; tick++) {
            int observed = tick;
            c.runAtTickTime(tick, () -> {
                if (arena.closed.get()) return;
                try {
                    input.accept(observed);
                    // Match a real client's tick boundary: native26.3 rejects
                    // another position packet until this packet ends its tick.
                    arena.session.player.connection.handleClientTickEnd(ServerboundClientTickEndPacket.INSTANCE);
                    arena.observe(c, observed);
                }
                catch (RuntimeException | Error failure) { arena.close(); throw failure; }
            });
        }
        c.runAtTickTime(ticks + 1, () -> {
            try { assertions.run(); arena.unchanged(c); c.succeed(); }
            finally { arena.close(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=150)
    public void hoveringOwnerStillGetsAnOrdinaryWalkingFollowerBelow(GameTestHelper c) {
        var arena = new FollowArena(c, "follow-hover", 10, 8, false, false);
        exercise(c, arena, 110, tick -> {}, () -> {
            double horizontal = arena.helper.position().subtract(arena.session.player.position()).multiply(1, 0, 1).length();
            c.assertTrue(horizontal < 3.3 && arena.helper.getX() > arena.start.x + 6,
                "A floating owner must not leave a grounded follower standing at its old position" + arena.diagnostic());
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=190)
    public void playerAvatarHelperActuallyWalksThroughOneWideTwoHighPassage(GameTestHelper c) {
        var arena = new FollowArena(c, "flw-tunnel", 12, 0, true, false);
        exercise(c, arena, 150, tick -> {}, () -> {
            c.assertTrue(arena.helper.getX() > arena.start.x + 9,
                "The visible player helper must physically traverse a player-sized passage" + arena.diagnostic());
            c.assertTrue(arena.helper.getBbWidth() < .8 && arena.helper.getBbHeight() < 2,
                "The registered helper's actual collision body must fit that passage");
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=220)
    public void movingGroundOwnerIsFollowedThroughNativeMovePackets(GameTestHelper c) {
        var arena = new FollowArena(c, "flw-moving", 8, 0, false, false);
        exercise(c, arena, 170, tick -> {
            if (tick >= 60 && tick < 100) {
                var owner = arena.session.player;
                owner.connection.handleMovePlayer(new ServerboundMovePlayerPacket.Pos(owner.getX() + .2, owner.getY(), owner.getZ(), true, false));
            }
        }, () -> {
            c.assertTrue(arena.session.player.getX() > arena.start.x + 15,
                "Actual native player-move packets moved the owner along the ground" + arena.diagnostic());
            c.assertTrue(arena.helper.getX() > arena.start.x + 12 && arena.helper.distanceToSqr(arena.session.player) < 12,
                "The same helper continues walking after its owner moves again" + arena.diagnostic());
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=150)
    public void unsupportedOwnerColumnDoesNotSendTheFollowerIntoTheVoid(GameTestHelper c) {
        var arena = new FollowArena(c, "flw-unsupported", 10, 8, false, true);
        exercise(c, arena, 110, tick -> {}, () -> {
            c.assertTrue(arena.helper.getX() < arena.start.x + 3.1,
                "An unsupported owner column never becomes an unchecked walking destination" + arena.diagnostic());
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=110)
    public void ownerBeyondLeashDoesNotCauseTeleportFlightOrDistantPursuit(GameTestHelper c) {
        var arena = new FollowArena(c, "flw-leash", 60, 8, false, false);
        exercise(c, arena, 70, tick -> {}, () -> {
            c.assertTrue(arena.helper.position().distanceTo(arena.start) < .75,
                "The existing forty-eight-block owner leash still pauses remote following" + arena.diagnostic());
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=90)
    public void explicitCreationFitsPlayerCorridorButRejectsOverlapAndMissingSupport(GameTestHelper c) {
        var session = new NativeSessionFixture.Joined(c, "flw-create", 3);
        var owner = session.player;
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        try {
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SURVIVAL);
            var feet = c.absolutePos(new BlockPos(20, 55, 20));
            for (var at : BlockPos.betweenClosed(feet.offset(-6, -1, -5), feet.offset(6, 4, 5)))
                c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            for (var at : BlockPos.betweenClosed(feet.offset(-5, 0, 0), feet.offset(5, 1, 0)))
                c.getLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            var place = Vec3.atBottomCenterOf(feet);
            owner.connection.teleport(place.x, place.y, place.z, 0, 0); session.drain();
            c.assertTrue(c.getLevel().noBlockCollision(owner, owner.getBoundingBox()), "The real owner fits safely inside the narrow corridor");
            c.assertValueEqual(helpers.count(owner), 0, "Making a corridor does not create a helper");
            c.assertValueEqual(helpers.spawn(owner, "corridor"), 1, "An explicit helper spawn fits a one-wide, two-high passage");
            var record = helpers.owned(owner, "corridor");
            var helper = helpers.loaded.get(UUID.fromString(record.getKey()));
            var destination = helper.blockPosition();
            c.assertTrue(helper.distanceToSqr(owner) >= 4 && helper.distanceToSqr(owner) <= 16,
                "Creation chooses nearby space outside the owner's body");
            c.assertTrue(c.getLevel().noBlockCollision(helper, helper.getBoundingBox()), "The new helper's actual body clears both side walls and roof");
            var ordinary = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
            var ordinaryAtDestination = ordinary.getBoundingBox().move(helper.position().subtract(ordinary.position()));
            c.assertFalse(c.getLevel().noBlockCollision(ordinary, ordinaryAtDestination), "An ordinary golem still cannot fit the same corridor");
            ordinary.discard();

            // Keep only the owner's cell and the already occupied valid helper
            // cell. Every other nearby candidate is solid, not an alternate exit.
            for (var at : BlockPos.betweenClosed(feet.offset(-5, 0, 0), feet.offset(5, 1, 0)))
                if (at.getX() != feet.getX() && at.getX() != destination.getX())
                    c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            helpers.spawn(owner, "overlap");
            c.assertTrue(helpers.owned(owner, "overlap") == null && helpers.count(owner) == 1,
                "A passage-sized body never permits spawning into the existing helper");
            c.assertTrue(helpers.loaded.get(helper.getUUID()) == helper && helper.isAlive(), "A refused overlap leaves the original helper intact");
            helpers.dismiss(owner, "corridor");
            c.getLevel().setBlockAndUpdate(destination.below(), Blocks.AIR.defaultBlockState());
            helpers.spawn(owner, "unsupported");
            c.assertTrue(helpers.owned(owner, "unsupported") == null && helpers.count(owner) == 0,
                "A clear narrow cell with missing support is still refused");
        } finally {
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            for (var name : helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getStringUUID())).map(AgentCompanions.Agent::name).toList())
                helpers.dismiss(owner, name);
            session.close();
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=90)
    public void ordinaryGolemBodyAndEditedHelperScaleSurviveNativeSave(GameTestHelper c) {
        var arena = new FollowArena(c, "follow-body-save", 2, 0, false, false);
        IronGolem restored = null;
        try {
            var ordinary = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.COMMAND);
            c.assertTrue(ordinary.getBbWidth() > 1.3 && ordinary.getBbHeight() > 2.6,
                "Ordinary Minecraft golems keep their native large collision body");
            c.assertTrue(arena.helper.getBbWidth() < .8 && arena.helper.getBbHeight() < 2,
                "Only the registered player-avatar helper gets a passage-sized body");
            arena.helper.getAttribute(Attributes.SCALE).setBaseValue(1.5);
            arena.helper.setHealth(73);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, c.getLevel().registryAccess());
            arena.helper.saveWithoutId(output);
            restored = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.LOAD);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, c.getLevel().registryAccess(), output.buildResult()));
            c.assertValueEqual(restored.getUUID(), arena.helper.getUUID(), "The native entity save preserves the helper UUID");
            c.assertValueEqual(restored.getAttribute(Attributes.SCALE).getBaseValue(), 1.5d, "The adapter never rewrites edited scale");
            c.assertValueEqual(restored.getHealth(), 73f, "The native save preserves edited health");
            c.assertTrue(restored.entityTags().contains(AgentCompanions.TAG), "Native saved helper identity survives");
            c.succeed();
        } finally {
            if (restored != null) restored.discard();
            arena.close();
        }
    }
}
