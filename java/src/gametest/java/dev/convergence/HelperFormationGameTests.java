package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Distinct slots plus actual native walking, exact-owner application and refusal paths. */
public class HelperFormationGameTests {
    private static boolean running;
    @FunctionalInterface private interface Scenario { void run(Arena arena) throws Exception; }
    private void serialized(GameTestHelper c, int count, String name, Scenario scenario) {
        var started = new AtomicBoolean();
        // Sequence admission runs after GameTestInfo's callback-map iteration.
        // Adding the full observation schedule from inside a retry callback can
        // rehash that native map before its iterator removes the launch callback.
        c.startSequence().thenWaitUntil(() -> c.assertFalse(running, "Waiting for the prior exact formation fixture"))
            .thenExecute(() -> {
                if (!started.compareAndSet(false, true)) return;
                running = true; Arena arena = null;
                try {
                    arena = new Arena(c, count, name); var active = arena;
                    c.runAfterDelay(400, () -> {
                        if (active.closed.get()) return;
                        active.close(); c.assertTrue(false, "Native formation scenario exceeded its deadline");
                    });
                    scenario.run(arena);
                } catch (RuntimeException | Error error) {
                    if (arena != null) arena.close(); else running = false; throw error;
                } catch (Exception error) {
                    if (arena != null) arena.close(); else running = false; throw new IllegalStateException(error);
                }
            });
    }
    private static final class Arena implements AutoCloseable {
        private static long sequence = 6400;
        final GameTestHelper c;
        final NativeSessionFixture.Joined session;
        final AgentCompanions previous, helpers;
        final Path directory;
        final Vec3 center;
        final List<IronGolem> members;
        final Map<UUID, Float> health = new HashMap<>();
        final Map<UUID, Vec3> last = new HashMap<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++sequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final List<ChunkPos> chunks = new ArrayList<>();
        Arena(GameTestHelper c, int count, String name) throws Exception {
            this.c = c; var server = c.getLevel().getServer(); previous = AgentCompanions.get(server);
            c.assertTrue(previous.data.agents.isEmpty(), "Formation fixture starts without unrelated squads");
            directory = Files.createTempDirectory("infinity-helper-formation-");
            helpers = new AgentCompanions(server, directory.resolve("agents.json")); AgentCompanions.INSTANCES.put(server, helpers);
            session = new NativeSessionFixture.Joined(c, name, 3);
            var owner = session.player; OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER); owner.setGameMode(GameType.SURVIVAL);
            var feet = c.absolutePos(new BlockPos(20, 55, 20)); center = Vec3.atBottomCenterOf(feet);
            for (var at : BlockPos.betweenClosed(feet.offset(-21, -1, -21), feet.offset(21, 12, 21)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            for (int x = (feet.getX() - 21) >> 4; x <= (feet.getX() + 21) >> 4; x++)
                for (int z = (feet.getZ() - 21) >> 4; z <= (feet.getZ() + 21) >> 4; z++) {
                    var chunk = new ChunkPos(x, z); chunks.add(chunk); c.getLevel().getChunkSource().addTicketWithRadius(ticket, chunk, 2);
                }
            owner.connection.teleport(center.x, center.y, center.z, 0, 0); session.drain(); helpers.save();
            c.assertValueEqual(helpers.spawnBatch(owner, count, AgentCompanions.Profile.DEBUG), count, "Fixture uses normal exact native batch creation");
            members = helpers.loaded.values().stream().sorted(java.util.Comparator.comparing(h -> helpers.data.agents.get(h.getStringUUID()).name())).toList();
            for (int index = 0; index < members.size(); index++) {
                var helper = members.get(index);
                // Initial placement only; later observations use real native ticks.
                helper.setPos(center.add(-13 + (index % 5) * 1.5, 0, 5 + (index / 5) * 1.5)); helper.setOnGround(true);
                helper.setHealth(37 + index); health.put(helper.getUUID(), helper.getHealth()); last.put(helper.getUUID(), helper.position());
            }
        }
        AgentCompanions.Agent record(IronGolem helper) { return helpers.data.agents.get(helper.getStringUUID()); }
        Map<UUID, AgentCompanions.Agent> records(List<IronGolem> chosen) {
            var result = new HashMap<UUID, AgentCompanions.Agent>(); chosen.forEach(h -> result.put(h.getUUID(), record(h))); return Map.copyOf(result);
        }
        Map<UUID, IronGolem> entities(List<IronGolem> chosen) {
            var result = new HashMap<UUID, IronGolem>(); chosen.forEach(h -> result.put(h.getUUID(), h)); return Map.copyOf(result);
        }
        void shape(AgentCompanions.Formation shape) { GameModes.state(session.player).putInt("helper_formation", shape.ordinal()); }
        Vec3 slot(IronGolem helper) { return helpers.formationPoint(helper, record(helper), session.player); }
        void observe() {
            session.drain();
            for (var helper : members) {
                c.assertTrue(helper.position().distanceTo(last.get(helper.getUUID())) < .95, "Formation movement uses bounded native walking, never teleportation");
                c.assertFalse(helper.isNoGravity() || helper.isFallFlying(), "Formation never invents flight");
                c.assertTrue(Math.abs(helper.getY() - center.y) < .65, "Following stays on the supported ground");
                c.assertTrue(helpers.loaded.get(helper.getUUID()) == helper, "Formation retains each exact registered entity");
                c.assertValueEqual(helper.getHealth(), health.get(helper.getUUID()), "Formation never heals or damages helpers");
                last.put(helper.getUUID(), helper.position());
            }
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            running = false;
            OperatorGameTests.level(session.player, LevelBasedPermissionSet.OWNER);
            for (var helper : new ArrayList<>(helpers.loaded.values())) { AgentAvatars.remove(helper); helper.discard(); }
            helpers.loaded.clear(); helpers.data.agents.clear();
            for (var chunk : chunks) c.getLevel().getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
            session.close();
            if (AgentCompanions.INSTANCES.get(helpers.server) == helpers) AgentCompanions.INSTANCES.put(helpers.server, previous);
            try (var paths = Files.walk(directory)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
    }

    @GameTest
    public void everyFormationHasDeterministicSeparatedSlotsForTwoThroughFiftyAndMirroredWedgePairs(GameTestHelper c) {
        for (var shape : AgentCompanions.Formation.values()) for (int count = 2; count <= 50; count++) {
            var points = new ArrayList<Vec3>();
            for (int index = 0; index < count; index++) {
                var point = AgentCompanions.formationOffset(shape, index, count);
                c.assertValueEqual(point, AgentCompanions.formationOffset(shape, index, count), "The same roster/yaw has stable slot coordinates");
                c.assertTrue(point.length() < 47, "All fifty slots fit inside the unchanged forty-eight-block follow leash");
                for (var other : points) c.assertTrue(point.distanceToSqr(other) > 2.5, "Every pair has room for a normal-sized walking helper in " + shape + " count " + count);
                points.add(point);
                if (shape == AgentCompanions.Formation.WEDGE && index % 2 == 1) {
                    var left = points.get(index - 1);
                    c.assertTrue(left.z == point.z && left.x == -point.x, "Integer wedge pairs have exactly mirrored sides at equal depth");
                }
            }
        } c.succeed();
    }

    private void walking(GameTestHelper c, AgentCompanions.Formation shape) throws Exception {
        serialized(c, 6, "formation-walk-" + shape.name().toLowerCase(java.util.Locale.ROOT), f -> {
        f.shape(shape);
        for (int tick = 1; tick <= 230; tick++) {
            int observed = tick;
            c.runAfterDelay(tick, () -> {
                if (f.closed.get()) return;
                try {
                    if (observed == 110) f.session.player.setYRot(90);
                    f.observe();
                } catch (RuntimeException | Error error) { f.close(); throw error; }
            });
        }
        c.runAfterDelay(231, () -> {
            try {
                var points = new ArrayList<Vec3>();
                for (var helper : f.members) {
                    var slot = f.slot(helper);
                    c.assertTrue(slot != null && helper.position().distanceToSqr(slot) < 1.5,
                        "Native walking follows the rotated owner-relative slot in " + shape + ": " + helper.position() + " -> " + slot);
                    for (var other : points) c.assertTrue(helper.position().distanceToSqr(other) > .7,
                        "Members hold separate native positions instead of piling onto the owner");
                    points.add(helper.position());
                }
                c.assertTrue(f.helpers.playerTargets.isEmpty(), "Formation grants no player combat approval"); c.succeed();
            } finally { f.close(); }
        });
        });
    }
    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void escortActuallyWalksToDistinctSlotsAndTurnsWithTheOwner(GameTestHelper c) throws Exception { walking(c, AgentCompanions.Formation.ESCORT); }
    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void wedgeActuallyWalksToDistinctSlotsAndTurnsWithTheOwner(GameTestHelper c) throws Exception { walking(c, AgentCompanions.Formation.WEDGE); }
    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void ringActuallyWalksToDistinctSlotsAndTurnsWithTheOwner(GameTestHelper c) throws Exception { walking(c, AgentCompanions.Formation.RING); }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void nativeFollowerWalksUpOrdinaryStepsToAnOwnersRaisedSupportedPlatform(GameTestHelper c) throws Exception {
        serialized(c, 1, "formation-stairs", f -> {
            var base = BlockPos.containing(f.center);
            for (var at : BlockPos.betweenClosed(base.offset(0, 0, -10), base.offset(17, 5, 10)))
                c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            for (int step = 0; step < 6; step++)
                for (var at : BlockPos.betweenClosed(base.offset(-6 + step, 0, -2), base.offset(-6 + step, step, 2)))
                    c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            var helper = f.members.getFirst(); helper.setPos(f.center.add(-12, 0, 0)); helper.setOnGround(true);
            f.session.player.connection.teleport(f.center.x + 8, f.center.y + 6, f.center.z, 0, 0); f.session.drain();
            var desired = f.slot(helper);
            c.assertTrue(desired != null && desired.y == f.center.y + 6,
                "A supported owner platform six blocks above the helper remains an ordinary walking destination");
            var previous = new Vec3[]{helper.position()}; var trace = new java.util.ArrayDeque<String>();
            for (int tick = 1; tick <= 240; tick++) {
                int observed = tick;
                c.runAfterDelay(tick, () -> {
                    if (f.closed.get()) return;
                    try {
                        f.session.drain();
                        c.assertTrue(helper.position().distanceTo(previous[0]) < 1.5, "Stair ascent uses bounded walking/step collision, not a teleport");
                        c.assertFalse(helper.isNoGravity() || helper.isFallFlying(), "Climbing supported steps does not grant flight");
                        c.assertTrue(f.helpers.loaded.get(helper.getUUID()) == helper, "Native stair walking retains the exact helper object");
                        previous[0] = helper.position();
                        if (observed % 10 == 0) {
                            var feedback = f.helpers.comboFeedback(helper.getUUID());
                            trace.add("t=" + observed + " at=" + helper.position().subtract(f.center) + " phase=" + (feedback == null ? "none" : feedback.text()));
                            if (trace.size() > 20) trace.removeFirst();
                        }
                    } catch (RuntimeException | Error failure) { f.close(); throw failure; }
                });
            }
            c.runAfterDelay(241, () -> {
                try {
                    var slot = f.slot(helper);
                    c.assertTrue(helper.getX() > f.center.x + 3 && Math.abs(helper.getY() - f.center.y - 6) < .25
                        && slot != null && helper.position().distanceToSqr(slot) < 1.5,
                        "Real native navigation climbs all six steps and reaches the owner's platform: " + trace);
                    c.assertValueEqual(helper.getHealth(), f.health.get(helper.getUUID()), "Native ascent preserves existing damage");
                    c.succeed();
                } finally { f.close(); }
            });
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void fiftyLoadedRingSlotsStaySeparateAndBlockedSlotsNeverCollapseOntoOwner(GameTestHelper c) throws Exception {
        serialized(c, 50, "formation-fifty", f -> { try (f) {
            f.shape(AgentCompanions.Formation.RING); var points = new ArrayList<Vec3>();
            for (var helper : f.members) {
                var point = f.slot(helper); c.assertTrue(point != null && f.helpers.landingClear(helper, point), "Every one of fifty ring slots has safe loaded support");
                for (var other : points) c.assertTrue(point.distanceToSqr(other) > 2.5, "Capacity-based ring radius keeps all fifty slots separate"); points.add(point);
            }
            for (var at : BlockPos.betweenClosed(BlockPos.containing(f.center).offset(-21, -1, -21), BlockPos.containing(f.center).offset(21, -1, 21)))
                c.getLevel().setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            f.session.player.setYRot(1); // Invalidate the same-tick plan after the terrain change.
            for (var helper : f.members) {
                c.assertTrue(f.slot(helper) == null, "Unsupported/blocked slots return no route, never the shared owner position");
                f.helpers.control(helper, f.record(helper), f.helpers.server.getTickCount());
                c.assertTrue(helper.getNavigation().isDone(), "A blocked member holds instead of starting an unsafe owner fallback");
            }
        } c.succeed(); });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void hoveringOwnerUsesSupportedSlotsAndStaleOwnerOrEntityCannotJoinFormation(GameTestHelper c) throws Exception {
        serialized(c, 3, "formation-hover", f -> { try (f) {
            f.session.player.setGameMode(GameType.CREATIVE); f.session.player.getAbilities().flying = true; f.session.player.onUpdateAbilities();
            f.session.player.connection.teleport(f.center.x, f.center.y + 8, f.center.z, 0, 0); f.session.drain();
            var points = new ArrayList<Vec3>();
            for (var helper : f.members) {
                var point = f.slot(helper); c.assertTrue(point != null && point.y == f.center.y, "Floating owners get grounded slots rather than targets in air");
                for (var other : points) c.assertTrue(point.distanceToSqr(other) > 2.5, "Ground projection preserves member separation"); points.add(point);
            }
            var helper = f.members.getFirst(); var old = f.record(helper);
            f.helpers.data.agents.put(helper.getStringUUID(), old.profile(AgentCompanions.Profile.REGULAR));
            c.assertTrue(f.helpers.formationPoint(helper, old, f.session.player) == null, "An obsolete saved record cannot control formation");
            OperatorGameTests.deop(f.session.player);
            c.assertTrue(f.slot(helper) == null, "Permission loss invalidates formation immediately");
            OperatorGameTests.level(f.session.player, LevelBasedPermissionSet.OWNER);
            f.session.player.setGameMode(GameType.SURVIVAL);
            f.session.player.connection.teleport(f.center.x, f.center.y, f.center.z, 0, 0); f.session.drain();
            c.assertTrue(f.slot(helper) != null, "Connected owner rebuilds an actual native formation plan");
            var field = AgentCompanions.class.getDeclaredField("formationPlans"); field.setAccessible(true);
            var cache = (Map<?, ?>)field.get(f.helpers);
            c.assertTrue(cache.containsKey(f.session.player.getUUID()), "Native formation calculation retains the current owner only during this tick");
            for (var member : f.members) member.discard();
            c.assertTrue(f.members.stream().allMatch(IronGolem::isRemoved), "Actual native removals retire the final formation member");
            f.helpers.tick();
            c.assertTrue(cache.isEmpty(), "Next ordinary helper tick releases cache after the final native helper unload");
            f.helpers.spawn(f.session.player, "cache-rejoin");
            var createdRecord = f.helpers.owned(f.session.player, "cache-rejoin");
            c.assertTrue(createdRecord != null, "Explicit native creation supplies a separate logout scenario");
            var created = f.helpers.loaded.get(UUID.fromString(createdRecord.getKey()));
            c.assertTrue(f.helpers.formationPoint(created, createdRecord.getValue(), f.session.player) != null,
                "Current connected session has a new actual cached plan");
            c.assertTrue(cache.containsKey(f.session.player.getUUID()), "Logout scenario starts with a populated native cache");
            f.session.close();
            c.assertTrue(f.helpers.server.getPlayerList().getPlayer(f.session.player.getUUID()) == null,
                "Actual native disconnect removes the owner session");
            f.helpers.tick();
            c.assertTrue(cache.isEmpty(), "Next ordinary helper tick releases the disconnected owner and helper references");
        } finally {
            // This case deliberately closes the native connection before Arena's
            // idempotent teardown; remove its cleanup-only temporary OP grant too.
            OperatorGameTests.deop(f.session.player);
        } c.succeed(); });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void reviewedSquadPlanAppliesExactlyThePinnedHelpersAndRejectsStaleOrRecoveryState(GameTestHelper c) throws Exception {
        serialized(c, 3, "formation-plan", f -> { try (f) {
            var selected = f.members.subList(0, 2); var beforeOther = f.record(f.members.get(2));
            var records = f.records(selected); var entities = f.entities(selected);
            c.assertValueEqual(f.helpers.applyReviewedSquadPlan(f.session.player, records, entities,
                AgentCompanions.Mode.GUARD, AgentCompanions.Profile.ULTIMATE_FINALS, AgentCompanions.Formation.RING), 2, "The approved native plan applies to exactly its two selected identities");
            c.assertTrue(f.record(f.members.get(2)) == beforeOther, "An unreviewed helper record is untouched");
            for (var helper : selected) {
                c.assertTrue(f.record(helper).mode() == AgentCompanions.Mode.GUARD && f.record(helper).profile() == AgentCompanions.Profile.ULTIMATE_FINALS, "Exact selected records receive the reviewed behavior");
                c.assertValueEqual(helper.getHealth(), f.health.get(helper.getUUID()), "Applying a plan preserves current damage");
            }
            c.assertValueEqual(AgentCompanions.formation(f.session.player), AgentCompanions.Formation.RING, "The selected formation is set explicitly, without cycling");
            c.assertValueEqual(f.helpers.applyReviewedSquadPlan(f.session.player, records, entities,
                AgentCompanions.Mode.FOLLOW, AgentCompanions.Profile.REGULAR, AgentCompanions.Formation.ESCORT), 0, "An old record snapshot cannot be replayed");
            f.helpers.creationRecoveryRequired = true;
            c.assertValueEqual(f.helpers.applyReviewedSquadPlan(f.session.player, f.records(selected), entities,
                AgentCompanions.Mode.FOLLOW, AgentCompanions.Profile.REGULAR, AgentCompanions.Formation.ESCORT), 0, "Recovery-latched ownership refuses plan application");
            f.helpers.creationRecoveryRequired = false;
            c.assertTrue(f.helpers.playerTargets.isEmpty(), "The API never creates player-target permission");
        } c.succeed(); });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=2000)
    public void reviewedSquadPlanWriterFailureRestoresRecordsAndPerformsNoNativeChanges(GameTestHelper c) throws Exception {
        serialized(c, 2, "formation-plan-fail", f -> { try (f) {
            var records = f.records(f.members); var entities = f.entities(f.members); var oldShape = AgentCompanions.formation(f.session.player);
            var previous = f.helpers.file.resolveSibling("agents.json.previous"); Files.deleteIfExists(previous); Files.createDirectory(previous); Files.writeString(previous.resolve("block"), "test-only");
            var positions = f.members.stream().map(IronGolem::position).toList();
            c.assertValueEqual(f.helpers.applyReviewedSquadPlan(f.session.player, records, entities,
                AgentCompanions.Mode.STAY, AgentCompanions.Profile.ULTIMATE_FINALS, AgentCompanions.Formation.WEDGE), 0, "A real ownership backup writer failure refuses the whole plan");
            for (int index = 0; index < f.members.size(); index++) {
                var helper = f.members.get(index);
                c.assertTrue(f.record(helper) == records.get(helper.getUUID()), "Original exact record objects are restored after write failure");
                c.assertValueEqual(helper.position(), positions.get(index), "No native movement is performed after save failure");
                c.assertTrue(helper.entityTags().contains("infinity_profile_debug"), "Native tags were not partially changed before save success");
            }
            c.assertValueEqual(AgentCompanions.formation(f.session.player), oldShape, "Failed ownership save does not change the player's formation");
        } c.succeed(); });
    }
}
