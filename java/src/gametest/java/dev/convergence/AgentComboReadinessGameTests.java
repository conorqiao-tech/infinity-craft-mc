package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Squad readiness and acquired-hostile retention, separate from the initial sensing radius. */
public class AgentComboReadinessGameTests {
    private AgentTacticsGameTests.Arena arena(GameTestHelper c, String name) {
        var result = new AgentTacticsGameTests.Arena(c, "cr-" + Integer.toUnsignedString(name.hashCode(), 36));
        result.owner.setPos(result.start.add(0, 0, -4));
        return result;
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void recentlyStrikingPeerCannotHoldReadyCloseMaceLane(GameTestHelper c) {
        try (var f = arena(c, "ready-recent-hit")) {
            f.golem.setPos(f.target.position().add(-2, 0, 0));
            var beta = f.add("beta", f.target.position().add(0, 0, 2));
            f.golem.setTarget(f.target); beta.setTarget(f.target);
            AgentWeapons.mace(f.golem, f.target); AgentWeapons.mace(beta, f.target);
            f.helpers.lastAttack.put(f.golem.getUUID(), 80);
            float health = f.target.getHealth();
            c.assertTrue(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 85),
                "A peer inside its native strike cadence cannot reserve the ready teammate's close aerial lane");
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "The recently striking peer is not launched instead");
            c.assertTrue(f.helpers.aerial.containsKey(beta.getUUID()), "The exact ready teammate receives the lane");
            c.assertValueEqual(f.helpers.lastAttack.get(f.golem.getUUID()), 80, "Readiness does not reset the peer's strike cadence");
            c.assertValueEqual(f.target.getHealth(), health, "Choosing a lane invents no contact damage");
            c.assertTrue(beta.getMainHandItem().is(Items.MACE), "A close launch does not fabricate a point-blank spear contact");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void unwoundFarSpearPeerCannotHoldReadyCloseMaceLane(GameTestHelper c) {
        try (var f = arena(c, "ready-unwound-peer")) {
            var beta = f.add("beta", f.target.position().add(0, 0, 2));
            f.golem.setTarget(f.target); beta.setTarget(f.target);
            AgentWeapons.spear(f.golem, f.target); AgentWeapons.mace(beta, f.target);
            var kinetic = f.golem.getMainHandItem().get(DataComponents.KINETIC_WEAPON);
            c.assertTrue(kinetic != null && f.golem.getTicksUsingItem() < kinetic.delayTicks(),
                "The farther alphabetically first peer has a real incomplete native spear windup");
            int using = f.golem.getTicksUsingItem();
            c.assertTrue(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 80),
                "An unwound farther spear peer cannot block a ready close mace teammate");
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "The uncharged peer never receives the lane");
            c.assertValueEqual(f.golem.getTicksUsingItem(), using, "Lane selection never advances or restarts the peer's native windup");
            c.assertTrue(f.golem.isUsingItem(), "The unready peer can finish its native ground charge normally");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=100)
    public void realLifecycleRetainsAcquiredChasingHostileOutsideOwnerSensingSphere(GameTestHelper c) {
        var f = arena(c, "retain-native-chase");
        var closed = new AtomicBoolean();
        Runnable close = () -> { if (closed.compareAndSet(false, true)) f.close(); };
        f.target.setNoAi(false);
        f.target.setTarget(f.golem);
        var original = f.target.position();
        int[] outsideChecks = {0};
        float[] acquiredHealth = {500};
        c.runAtTickTime(10, () -> {
            if (closed.get()) return;
            try {
                c.assertTrue(f.golem.getTarget() == f.target, "Normal helper ticks acquire the nearby real-AI hostile first");
                acquiredHealth[0] = f.target.getHealth();
                // Both endpoints remain inside the existing follow leash and
                // combat distance. Only the owner's small acquisition sphere moves.
                f.owner.setPos(f.start.add(-8, 0, -8));
            } catch (RuntimeException | Error failure) { close.run(); throw failure; }
        });
        for (int tick = 20; tick <= 60; tick += 5) c.runAtTickTime(tick, () -> {
            if (closed.get()) return;
            try {
                if (f.target.isAlive() && !f.target.isRemoved() && f.target.distanceToSqr(f.owner) > 100
                        && f.target.distanceToSqr(f.golem) <= 24 * 24
                        && f.helpers.insideLeash(f.record(f.golem), f.owner, f.target.position())
                        && f.golem.getSensing().hasLineOfSight(f.target)) {
                    outsideChecks[0]++;
                    c.assertTrue(f.golem.getTarget() == f.target,
                        "A still eligible acquired hostile is not dropped merely because the owner moved beyond ten blocks");
                }
            } catch (RuntimeException | Error failure) { close.run(); throw failure; }
        });
        c.runAtTickTime(65, () -> {
            if (closed.get()) return;
            try {
                c.assertTrue(outsideChecks[0] >= 4, "The live fight is observed outside acquisition range across multiple normal control ticks");
                c.assertTrue(f.target.position().distanceToSqr(original) > .04, "The hostile genuinely moves under native AI and knockback");
                c.assertTrue(f.target.getHealth() < acquiredHealth[0], "The retained fight continues causing real damage after the owner moves");
                c.succeed();
            } finally { close.run(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void freshFarHostileIsNotAcquiredThroughRetentionFallback(GameTestHelper c) {
        try (var f = arena(c, "retain-fresh-far")) {
            f.target.setPos(f.start.add(8, 0, 8));
            f.golem.setTarget(null);
            c.assertTrue(f.target.distanceToSqr(f.owner) > 100 && f.target.distanceToSqr(f.golem) < 24 * 24,
                "The fresh hostile is outside acquisition but otherwise inside combat range");
            f.helpers.control(f.golem, f.record(f.golem), 80);
            c.assertTrue(f.golem.getTarget() == null, "Retention must not turn the whole movement leash into fresh hostile acquisition");
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "No fresh distant target starts a combo");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void retainedHostileCrossingGuardLeashIsReleased(GameTestHelper c) {
        try (var f = arena(c, "retain-guard-bound")) {
            f.helpers.mode(f.owner, "alpha", AgentCompanions.Mode.GUARD);
            f.target.setPos(f.start.add(14.2, 0, 0));
            f.golem.setPos(f.start.add(10.5, 0, 0));
            f.golem.setTarget(f.target);
            c.assertTrue(f.helpers.insideLeash(f.record(f.golem), f.owner, f.golem.position()), "The guard remains inside its own anchor leash");
            c.assertFalse(f.helpers.insideLeash(f.record(f.golem), f.owner, f.target.position()), "The old hostile crossed the guard boundary");
            f.helpers.control(f.golem, f.record(f.golem), 80);
            c.assertTrue(f.golem.getTarget() == null, "A previously acquired hostile outside the guard leash is not retained");
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "Guard retention never reserves an out-of-leash aerial route");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void retainedHostileBehindNewWallIsReleased(GameTestHelper c) {
        try (var f = arena(c, "retain-sight-bound")) {
            f.golem.setTarget(f.target);
            var wall = BlockPos.containing(f.start).east(2);
            for (var at : BlockPos.betweenClosed(wall.offset(0, 0, -2), wall.offset(0, 4, 2)))
                c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            f.golem.getSensing().tick();
            c.assertFalse(f.golem.getSensing().hasLineOfSight(f.target), "The new solid wall actually breaks native sight");
            f.helpers.control(f.golem, f.record(f.golem), 80);
            c.assertTrue(f.golem.getTarget() == null, "Retention does not pursue an occluded old hostile through a wall");
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "No aerial sequence bypasses the new wall");
        }
        c.succeed();
    }

    /** Functional capacity coverage only; this is not a tick-rate or load benchmark. */
    private static final class FiftySquad implements AutoCloseable {
        private static long ticketSequence = 2900;
        final GameTestHelper context;
        final net.minecraft.server.MinecraftServer server;
        final AgentCompanions previous;
        final AgentCompanions helpers;
        final AgentGameTests.ActionClock clock = new AgentGameTests.ActionClock();
        final ServerPlayer owner;
        final Vec3 center;
        final Path directory;
        final Map<UUID, IronGolem> identities = new HashMap<>();
        final List<ChunkPos> chunks = new ArrayList<>();
        final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++ticketSequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final boolean originalPvp;
        final AtomicBoolean closed = new AtomicBoolean();
        Zombie hostile;
        ServerPlayer reviewedPlayer;
        IronGolem sentinel;
        boolean sawHostileLane, sawPlayerLane, sawHostileAi, sawHostileMotion;
        int hostileObservations;
        final List<String> hostileSamples = new ArrayList<>();

        FiftySquad(GameTestHelper c) {
            context = c; server = c.getLevel().getServer();
            previous = AgentCompanions.get(server);
            if (!previous.data.agents.isEmpty()) throw new IllegalStateException("The capacity fixture needs its own empty helper runtime");
            originalPvp = c.getLevel().getGameRules().get(GameRules.PVP);
            try { directory = Files.createTempDirectory("infinity-fifty-helper-lifecycle-"); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            helpers = new AgentCompanions(server, directory.resolve("agents.json"), clock);
            AgentCompanions.INSTANCES.put(server, helpers);
            owner = new ModeGameTests().player(c, "fifty-owner");
            center = Vec3.atBottomCenterOf(c.absolutePos(new BlockPos(20, 55, 20)));
            try {
                OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
                owner.setGameMode(GameType.SURVIVAL);
                owner.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); owner.setHealth(1000);
                var feet = BlockPos.containing(center);
                for (int x = (feet.getX() - 15) >> 4; x <= (feet.getX() + 15) >> 4; x++)
                    for (int z = (feet.getZ() - 15) >> 4; z <= (feet.getZ() + 15) >> 4; z++) {
                        var chunk = new ChunkPos(x, z); chunks.add(chunk);
                        c.getLevel().getChunkSource().addTicketWithRadius(ticket, chunk, 2);
                    }
                for (var at : BlockPos.betweenClosed(feet.offset(-15, -1, -15), feet.offset(15, 8, 15)))
                    c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                owner.setPos(center.add(0, 0, -4));
                for (int index = 0; index < 50; index++) {
                    String name = "helper-" + (index + 1);
                    helpers.spawn(owner, name);
                    var record = helpers.owned(owner, name);
                    c.assertTrue(record != null, "Normal spawning creates capacity helper " + (index + 1));
                    var helper = helpers.loaded.get(UUID.fromString(record.getKey()));
                    c.assertTrue(helper != null, "The capacity helper is the actual loaded native entity");
                    helpers.profile(owner, name, AgentCompanions.Profile.ULTIMATE_FINALS);
                    // Three-block spacing leaves real golem collision room and
                    // a reusable spawn gap. Only initial placement is arranged.
                    helper.setPos(center.add(-10.5 + index % 8 * 3, 0, -9 + index / 8 * 3));
                    helper.setOnGround(true); helper.setDeltaMovement(Vec3.ZERO);
                    identities.put(helper.getUUID(), helper);
                    if (index == 0) {
                        sentinel = helper;
                        sentinel.getAttribute(Attributes.MAX_HEALTH).setBaseValue(175);
                        sentinel.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(7);
                        sentinel.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.19);
                        sentinel.setHealth(137);
                    }
                }
                c.assertValueEqual(sentinel.getHealth(), 137F, "Creating the rest of the squad never resets an existing helper's health");
                assertIdentities();
                hostile = EntityTypes.HUSK.create(c.getLevel(), EntitySpawnReason.COMMAND);
                hostile.setPersistenceRequired();
                // More health permits observation; native AI, gravity, movement,
                // damage immunity and knockback remain unchanged.
                hostile.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); hostile.setHealth(1000);
                hostile.setPos(center); c.assertTrue(c.getLevel().addFreshEntity(hostile), "The live-AI hostile enters the native world");
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        void assertIdentities() {
            context.assertValueEqual(helpers.count(owner), 50, "All fifty saved identities remain owned");
            for (var entry : identities.entrySet()) {
                var helper = entry.getValue();
                context.assertTrue(helpers.loaded.get(entry.getKey()) == helper
                    && context.getLevel().getEntity(entry.getKey()) == helper && helper.isAlive(),
                    "Normal lifecycle retains each exact native helper rather than replacing it");
                var record = helpers.data.agents.get(entry.getKey().toString());
                context.assertTrue(record != null && record.owner().equals(owner.getStringUUID())
                    && record.profile() == AgentCompanions.Profile.ULTIMATE_FINALS, "Ownership and the selected combat profile are retained");
            }
            context.assertValueEqual(sentinel.getAttribute(Attributes.MAX_HEALTH).getBaseValue(), 175D, "Edited maximum health remains native");
            context.assertValueEqual(sentinel.getAttribute(Attributes.ATTACK_DAMAGE).getBaseValue(), 7D, "Edited attack base is not reset by combat");
            context.assertValueEqual(sentinel.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue(), .19D, "Edited movement base is not reset by combat");
        }

        void observe(boolean playerPhase) {
            var target = playerPhase ? reviewedPlayer : hostile;
            long lanes = helpers.aerial.values().stream().filter(move -> move.agent.owner().equals(owner.getStringUUID())
                && move.target == target).count();
            context.assertTrue(lanes <= 1, "Fifty normal combat helpers keep at most one aerial lane per owner and target");
            if (playerPhase) sawPlayerLane |= lanes == 1;
            else {
                sawHostileLane |= lanes == 1;
                // In the dense melee a native Husk can already be in attack
                // reach and stop navigating. Its own AI must still acquire one
                // of these exact helpers, and ordinary world physics must move
                // it. Separate chase tests require actual path navigation.
                sawHostileAi |= hostile.getTarget() != null
                    && identities.get(hostile.getTarget().getUUID()) == hostile.getTarget();
                sawHostileMotion |= hostile.position().distanceToSqr(center) > .01;
                context.assertFalse(hostile.isNoAi(), "The hostile's ordinary AI stays enabled");
                hostileObservations++;
                if (hostileObservations <= 4 || hostileObservations % 5 == 0) {
                    var phases = new java.util.TreeMap<String, Integer>();
                    int grounded = 0, targeting = 0, readyCadence = 0, levelTarget = 0;
                    for (var helper : identities.values()) {
                        var feedback = helpers.comboFeedback(helper.getUUID());
                        phases.merge(feedback == null ? "no control feedback" : feedback.text(), 1, Integer::sum);
                        if (helper.onGround()) grounded++;
                        if (helper.getTarget() == hostile) targeting++;
                        if (server.getTickCount() - helpers.lastAttack.getOrDefault(helper.getUUID(), -20) >= 20) readyCadence++;
                        if (Math.abs(helper.getY() - hostile.getY()) <= .25) levelTarget++;
                    }
                    hostileSamples.add("observation=" + hostileObservations + ", nativeTick=" + server.getTickCount()
                        + ", lanes=" + lanes + ", hostileHealth=" + hostile.getHealth() + ", hostileOffset=" + hostile.position().subtract(center)
                        + ", hostileGround=" + hostile.onGround() + ", hostileMotion=" + hostile.getDeltaMovement()
                        + ", hostileTarget=" + (hostile.getTarget() == null ? "none" : hostile.getTarget().getType())
                        + ", hostileNavigating=" + hostile.getNavigation().isInProgress() + ", helpersGrounded=" + grounded
                        + ", helpersTargeting=" + targeting + ", helpersStrikeReady=" + readyCadence + ", helpersLevel=" + levelTarget
                        + ", phases=" + phases);
                }
            }
        }

        Map<UUID, Float> health() {
            var health = new HashMap<UUID, Float>();
            identities.forEach((id, helper) -> health.put(id, helper.getHealth()));
            return health;
        }

        void assertHealth(Map<UUID, Float> expected, String reason) {
            identities.forEach((id, helper) -> context.assertValueEqual(helper.getHealth(), expected.get(id), reason));
        }

        void assertHalted() {
            context.assertTrue(helpers.aerial.isEmpty(), "No stale aerial sequence survives the squad stop");
            for (var helper : identities.values()) context.assertTrue(helper.getTarget() == null
                && !helper.isUsingItem() && !helper.isFallFlying(), "Every helper cancels target, native item use and glide");
        }

        void reviewedPlayerPhase() {
            hostile.discard();
            reviewedPlayer = new ModeGameTests().player(context, "fifty-target");
            reviewedPlayer.setGameMode(GameType.SURVIVAL);
            // Set up a durable, grounded target once through the real owner
            // editor. Never heal it, clear damage immunity or reset cooldowns
            // while the native squad runs. A struck husk may be airborne here;
            // copying that position to a clientless player would freeze it aloft.
            context.assertTrue(AdminStats.set(owner.createCommandSourceStack(), reviewedPlayer, "health", 10000).success(),
                "The owner establishes the target's real finite health capacity before combat");
            reviewedPlayer.setPos(center.add(0, 0, 4));
            context.getLevel().getGameRules().set(GameRules.PVP, true, server);
            var before = health();
            helpers.squad(owner, AgentCompanions.Mode.FOLLOW);
            assertHealth(before, "Resuming fifty saved helpers never heals or resets them");
            // This is the same final reviewed-assignment test seam used by the
            // existing native approval tests. Menus cannot perform this review.
            context.assertTrue(helpers.assignPlayerTarget(owner, reviewedPlayer), "The exact eligible player receives the final reviewed assignment");
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (hostile != null) hostile.discard();
            if (reviewedPlayer != null) server.getPlayerList().remove(reviewedPlayer);
            helpers.ceasefire(owner);
            for (var record : new ArrayList<>(helpers.data.agents.values())) helpers.dismiss(owner, record.name());
            OperatorGameTests.deop(owner); server.getPlayerList().remove(owner);
            context.getLevel().getGameRules().set(GameRules.PVP, originalPvp, server);
            if (AgentCompanions.INSTANCES.get(server) == helpers) AgentCompanions.INSTANCES.put(server, previous);
            for (var chunk : chunks) context.getLevel().getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Could not retire the private capacity-test fixture", failure); }
        }
    }

    private void capacityAt(GameTestHelper c, AtomicReference<FiftySquad> fixture, int delay,
        java.util.function.Consumer<FiftySquad> action) {
        c.runAtTickTime(80 + delay, () -> {
            var f = fixture.get();
            c.assertTrue(f != null, "The one capacity fixture is initialized before its scheduled phase");
            if (f.closed.get()) return;
            try { action.accept(f); }
            catch (RuntimeException | Error failure) { f.close(); throw failure; }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=250)
    public void fiftyNormalHelpersKeepOneLaneAndHonorStayAndReviewedOrderExpiry(GameTestHelper c) {
        // The other lifecycle case ends at tick65. Defer this global-capacity
        // fixture so it cannot consume those independent cases' helper slots.
        // Native GameTestInfo iterates its scheduled-callback hash map directly.
        // Register every phase now; adding them from the tick-80 setup callback
        // can rehash that active iterator and execute the setup a second time.
        var fixture = new AtomicReference<FiftySquad>();
        c.runAtTickTime(80, () -> fixture.set(new FiftySquad(c)));
        for (int tick = 1; tick < 35; tick++) capacityAt(c, fixture, tick, f -> f.observe(false));
        final float[] stoppedHostileHealth = {0}, expiredPlayerHealth = {0};
        var expiryHealth = new AtomicReference<Map<UUID, Float>>();
        capacityAt(c, fixture, 35, f -> {
            f.assertIdentities();
            c.assertTrue(f.sawHostileLane, "The fifty-entity normal lifecycle exercises an actual aerial lane; " + f.hostileSamples);
            c.assertTrue(f.sawHostileAi, "Native hostile AI acquires one of the fifty exact helpers without a forced target; " + f.hostileSamples);
            c.assertTrue(f.sawHostileMotion, "The fifty-helper melee preserves actual hostile motion through native world physics; " + f.hostileSamples);
            c.assertTrue(f.hostile.getHealth() < 1000 && f.hostile.isAlive(), "The native fight causes real damage without an artificial invulnerable target");
            var before = f.health();
            f.helpers.squad(f.owner, AgentCompanions.Mode.STAY);
            f.assertHalted(); f.assertHealth(before, "Stopping the squad preserves every helper's current health");
            stoppedHostileHealth[0] = f.hostile.getHealth();
        });
        capacityAt(c, fixture, 40, f -> {
            f.assertHalted();
            c.assertValueEqual(f.hostile.getHealth(), stoppedHostileHealth[0], "Stay cannot leave native charging contacts or combo damage running");
            f.reviewedPlayerPhase();
        });
        // Stay must preserve the preceding fight's real leap cooldowns.
        // Allow those 80-tick cooldowns to finish naturally before expiry.
        for (int tick = 41; tick < 140; tick++) capacityAt(c, fixture, tick, f -> f.observe(true));
        capacityAt(c, fixture, 140, f -> {
            c.assertTrue(f.sawPlayerLane, "Normal ticks execute a reviewed player aerial lane before its real deadline");
            c.assertTrue(f.reviewedPlayer.isAlive() && f.reviewedPlayer.getHealth() < 10000,
                "The reviewed finite-health target survives actual native contacts until expiry");
            c.assertTrue(f.helpers.validPlayerTarget(f.owner) != null, "The exact reviewed assignment remains active before expiry");
            expiryHealth.set(f.health()); expiredPlayerHealth[0] = f.reviewedPlayer.getHealth();
            f.clock.advance(AgentCompanions.PLAYER_TARGET_LIFETIME_MS);
        });
        capacityAt(c, fixture, 142, f -> {
            c.assertTrue(f.helpers.playerTargets.isEmpty(), "The native server lifecycle expires the reviewed order for all fifty helpers");
            f.assertHalted();
            f.assertHealth(expiryHealth.get(), "Order expiry never resets helper health or edited stats");
            c.assertValueEqual(f.reviewedPlayer.getHealth(), expiredPlayerHealth[0], "No player damage crosses the exact reviewed-order expiry");
        });
        capacityAt(c, fixture, 150, f -> {
            f.assertIdentities(); f.assertHalted();
            c.assertValueEqual(f.reviewedPlayer.getHealth(), expiredPlayerHealth[0], "The expired fifty-helper order never resumes without a fresh review");
            c.succeed(); f.close();
        });
    }

}
