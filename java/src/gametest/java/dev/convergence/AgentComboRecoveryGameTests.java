package dev.convergence;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Real lifecycle/weapon regressions in isolated arenas, plus deterministic recovery boundaries. */
public class AgentComboRecoveryGameTests {
    private AgentTacticsGameTests.Arena arena(GameTestHelper c, String name) {
        var result = new AgentTacticsGameTests.Arena(c, name);
        result.owner.setPos(result.start.add(0, 0, -4));
        return result;
    }

    private Runnable cleanup(AgentTacticsGameTests.Arena arena, AtomicBoolean closed) {
        return () -> { if (closed.compareAndSet(false, true)) arena.close(); };
    }

    private void at(GameTestHelper c, int tick, AtomicBoolean closed, Runnable cleanup, Runnable action) {
        c.runAtTickTime(tick, () -> {
            if (closed.get()) return;
            try { action.run(); }
            catch (RuntimeException | Error failure) { cleanup.run(); throw failure; }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=90)
    public void blockedCloseArcKeepsMaceAndLandsCadencedGroundFollowup(GameTestHelper c) {
        var f = arena(c, "combo-close-cycle");
        var closed = new AtomicBoolean();
        var close = cleanup(f, closed);
        // A blocked arc deliberately exercises the ground fallback. Open close
        // combat is covered against native chasing AI by the chase regressions.
        for (var at : BlockPos.betweenClosed(BlockPos.containing(f.start).offset(-9, 3, -9),
                BlockPos.containing(f.start).offset(9, 3, 9)))
            c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        f.target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        f.golem.setPos(f.target.position().add(-1, 0, 0));
        float[] health = {f.target.getHealth()};
        boolean[] sawMace = {false};
        var hits = new ArrayList<Integer>();
        for (int tick=1; tick<=55; tick++) {
            final int observed = tick;
            at(c, tick, closed, close, () -> {
                if (f.golem.getMainHandItem().is(Items.MACE)) sawMace[0] = true;
                if (sawMace[0]) c.assertTrue(f.golem.getMainHandItem().is(Items.MACE),
                    "A point-blank target must not reset mace/spear equipment every control tick");
                float now = f.target.getHealth();
                if (now < health[0]) {
                    c.assertTrue(f.golem.getMainHandItem().is(Items.MACE), "Close damage uses the real mace");
                    hits.add(observed);
                }
                health[0] = now;
            });
        }
        at(c, 56, closed, close, () -> {
            try {
                c.assertTrue(sawMace[0] && hits.size()>=2,
                    "Normal server ticks deliver repeated close mace hits, not one hit then a charging dead zone: " + hits);
                for (int i=1; i<hits.size(); i++) c.assertTrue(hits.get(i)-hits.get(i-1)>=20,
                    "Stable weapon selection still preserves twenty-tick damage cadence: " + hits);
                c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "A close mace follow-up does not invent an aerial sequence");
                c.succeed();
            } finally { close.run(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=140)
    public void normalLifecycleObservesSpearGlideAndFallingMaceDamage(GameTestHelper c) {
        var f = arena(c, "combo-native-phases");
        var closed = new AtomicBoolean();
        var close = cleanup(f, closed);
        f.target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        boolean[] phases = new boolean[3];
        boolean[] nativeSmash = {false};
        float[] health = {f.target.getHealth()}, largestMaceHit = {0};
        // Do not call control, startAerial, strike, glide or set fallDistance.
        // The installed server lifecycle must acquire and execute the sequence.
        for (int tick=1; tick<=100; tick++) at(c, tick, closed, close, () -> {
            phases[0] |= f.golem.getMainHandItem().is(Items.NETHERITE_SPEAR) && f.golem.isUsingItem();
            phases[1] |= f.golem.isFallFlying() || f.golem.getFallFlyingTicks()>0;
            phases[2] |= f.golem.getMainHandItem().is(Items.MACE)
                && !f.golem.onGround() && (f.golem.fallDistance>0 || f.golem.getDeltaMovement().y<-.1);
            float now = f.target.getHealth();
            if (f.golem.getMainHandItem().is(Items.MACE) && now<health[0]) {
                largestMaceHit[0] = Math.max(largestMaceHit[0], health[0]-now);
                var source = f.target.getLastDamageSource();
                nativeSmash[0] |= source!=null && source.is(DamageTypes.MACE_SMASH);
            }
            health[0] = now;
        });
        at(c, 101, closed, close, () -> {
            try {
                c.assertTrue(phases[0] && phases[1] && phases[2],
                    "Normal ticks must observe native spear use, real glide and a descending mace: "
                        + java.util.Arrays.toString(phases));
                c.assertTrue(nativeSmash[0] && largestMaceHit[0]>0,
                    "The autonomous arc must deliver a real MACE_SMASH damage source, rather than only a ground punch: " + largestMaceHit[0]);
                c.assertTrue(f.helpers.lastAttack.containsKey(f.golem.getUUID()), "A native melee contact is recorded");
                c.assertFalse(f.golem.isNoGravity(), "The complete combo retains ordinary gravity");
                c.succeed();
            } finally { close.run(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void unavailableSquadPeerCannotStarveReadyLauncher(GameTestHelper c) {
        for (String unavailable : new String[]{"airborne", "cooldown", "too_far", "wrong_height", "blocked_route"}) {
            try (var f = arena(c, "combo-peer-" + unavailable)) {
                var beta = f.add("beta", f.start.add(0, 0, 2));
                f.golem.setTarget(f.target); beta.setTarget(f.target);
                if (unavailable.equals("airborne")) beta.setOnGround(false);
                if (unavailable.equals("cooldown")) f.helpers.nextLeap.put(beta.getUUID(), 200);
                if (unavailable.equals("too_far")) beta.setPos(f.start.add(0, 0, -5));
                if (unavailable.equals("wrong_height")) beta.setPos(f.start.add(0, 1, 2));
                if (unavailable.equals("blocked_route")) {
                    beta.setPos(f.start.add(0, 0, 4));
                    c.getLevel().setBlockAndUpdate(BlockPos.containing(beta.position()).above(3), Blocks.STONE.defaultBlockState());
                }
                c.assertTrue(f.launch(80), "Ready alpha can launch while beta is unavailable: " + unavailable);
                c.assertFalse(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 80),
                    "Ready-peer fallback never permits two simultaneous owner-target dives");
            }
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void movingTargetRefreshesOnlyToAnotherValidatedLanding(GameTestHelper c) {
        try (var f = arena(c, "combo-replan")) {
            c.assertTrue(f.launch(80), "A clear original route launches");
            var move = f.helpers.aerial.get(f.golem.getUUID());
            var original = move.landing;
            f.target.setPos(f.start.add(5, 0, 1));
            f.target.setDeltaMovement(0, 0, .12);
            f.helpers.tickAerial(f.golem, 85);
            c.assertTrue(f.helpers.aerial.get(f.golem.getUUID())==move, "Tracking preserves the same exact target/sequence");
            c.assertTrue(move.landing.distanceTo(original)>.25, "A moving target does not leave the landing frozen at launch");
            c.assertTrue(f.helpers.landingClear(f.golem, move.landing), "Updated landing retains full-body dry support");
            var lead = f.helpers.intercept(f.golem, f.record(f.golem), f.owner, f.target);
            c.assertTrue(lead!=null && lead.distanceTo(f.target.position())<=2.001, "Moving-target lead stays bounded");
            var validated = move.landing;
            f.target.setPos(f.start.add(5, 0, 4)); f.target.setDeltaMovement(Vec3.ZERO);
            var direction = f.target.position().subtract(f.golem.position()).multiply(1, 0, 1).normalize();
            double offset = f.helpers.landingOffset(f.golem, f.target);
            var desired = new Vec3(f.target.getX()-direction.x*offset, f.golem.getY(), f.target.getZ()-direction.z*offset);
            var shift = desired.subtract(validated).multiply(1, 0, 1);
            if (shift.lengthSqr()>4) shift=shift.normalize().scale(2);
            var unsafe = validated.add(shift);
            c.getLevel().setBlockAndUpdate(BlockPos.containing(unsafe).below(), Blocks.AIR.defaultBlockState());
            c.assertFalse(f.helpers.landingClear(f.golem, unsafe), "A missing floor makes only the replacement landing unsafe");
            f.helpers.tickAerial(f.golem, 90);
            c.assertTrue(f.helpers.aerial.get(f.golem.getUUID())==move && move.landing.equals(validated),
                "Unsafe target motion keeps the previous validated route, not an unchecked jump or teleport");
            c.assertFalse(f.golem.isNoGravity(), "Replanning does not suspend gravity");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void obstructionCancelsFlightWithoutErasingCooldownOrGroundCombat(GameTestHelper c) {
        try (var f = arena(c, "combo-obstructed")) {
            c.assertTrue(f.launch(80), "The initial clear path launches");
            int retry = f.helpers.nextLeap.get(f.golem.getUUID());
            float health = f.target.getHealth();
            var obstruction = BlockPos.containing(f.golem.position().add(.4, 0, 0));
            c.getLevel().setBlockAndUpdate(obstruction, Blocks.STONE.defaultBlockState());
            f.helpers.tickAerial(f.golem, 85);
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "A new obstacle cancels before unchecked steering");
            c.assertTrue(!f.golem.isFallFlying() && !f.golem.isNoGravity() && f.golem.getDeltaMovement().y<=0,
                "Cancellation restores normal falling instead of hovering or steering through the obstacle");
            c.assertValueEqual(f.target.getHealth(), health, "A blocked route invents no distant damage");
            c.assertValueEqual(f.helpers.nextLeap.get(f.golem.getUUID()), retry, "Failure retains its real cooldown");
            c.getLevel().setBlockAndUpdate(obstruction, Blocks.AIR.defaultBlockState());
            f.golem.setOnGround(true); f.golem.setDeltaMovement(Vec3.ZERO);
            f.helpers.control(f.golem, f.record(f.golem), 90);
            c.assertTrue(f.golem.getTarget()==f.target && f.golem.isUsingItem(), "Ground spear pursuit remains available after failure");
            c.assertFalse(f.launch(90), "Ground recovery cannot skip the aerial cooldown");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena")
    public void guardNativeContactCannotHurtHostileBeyondAnchorBetweenControls(GameTestHelper c) {
        try (var f = arena(c, "combo-guard-contact")) {
            f.helpers.mode(f.owner, "alpha", AgentCompanions.Mode.GUARD);
            f.target.setPos(f.start.add(14.2, 0, 0));
            f.golem.setPos(f.target.position().add(-1, 0, 0));
            AgentWeapons.mace(f.golem, f.target);
            float health = f.target.getHealth(); var velocity = f.target.getDeltaMovement();
            c.assertTrue(f.helpers.insideLeash(f.record(f.golem), f.owner, f.golem.position()), "Helper itself remains in guard range");
            c.assertFalse(f.helpers.insideLeash(f.record(f.golem), f.owner, f.target.position()), "Native contact target crossed the anchor boundary");
            c.assertFalse(f.golem.doHurtTarget(c.getLevel(), f.target), "Native weapon contact rechecks the leash between five-tick controls");
            c.assertValueEqual(f.target.getHealth(), health, "An out-of-leash hostile loses no health");
            c.assertValueEqual(f.target.getDeltaMovement(), velocity, "An out-of-leash contact causes no knockback");
            f.target.setPos(f.start.add(13.5, 0, 0)); f.golem.setPos(f.target.position().add(-1, 0, 0));
            c.assertTrue(f.golem.doHurtTarget(c.getLevel(), f.target), "An otherwise identical eligible contact still uses native damage");
            c.assertTrue(f.target.getHealth()<health, "In-leash control proves the refusal was the guard boundary");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=40)
    public void targetModeRevocationCancelsDiveBeforeFollowupDamage(GameTestHelper c) {
        var f = arena(c, "combo-revoke");
        var target = new ModeGameTests().player(c, "combo-review-target");
        var closed = new AtomicBoolean();
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) return;
            c.getLevel().getGameRules().set(GameRules.PVP, pvp, f.helpers.server);
            target.level().getServer().getPlayerList().remove(target); f.close();
        };
        try {
            // This case isolates revocation of the reviewed player. Leaving the
            // fixture's unrelated Husk alive would legitimately resume ordinary
            // hostile defense, including spear use, after the player is revoked.
            f.target.discard();
            c.getLevel().getGameRules().set(GameRules.PVP, true, f.helpers.server);
            target.setGameMode(GameType.SURVIVAL); target.setPos(f.start.add(5, 0, 0));
            c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "Fixture starts with the exact final reviewed player assignment");
            f.helpers.control(f.golem, f.record(f.golem), f.helpers.server.getTickCount());
            c.assertTrue(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, f.helpers.server.getTickCount()),
                "An eligible reviewed target starts a real dive");
            int retry = f.helpers.nextLeap.get(f.golem.getUUID());
            target.setGameMode(GameType.CREATIVE);
            float health = target.getHealth();
            at(c, 2, closed, close, () -> {
                try {
                    c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUUID()), "Native lifecycle revocation removes the active dive");
                    c.assertFalse(f.golem.isUsingItem() || f.golem.isFallFlying(), "No native spear use or glide survives revocation");
                    c.assertValueEqual(f.helpers.nextLeap.get(f.golem.getUUID()), retry, "Revocation does not erase the combat cooldown");
                    AgentWeapons.mace(f.golem, target);
                    c.assertFalse(f.golem.doHurtTarget(c.getLevel(), target), "A forced follow-up still cannot bypass revoked player eligibility");
                    c.assertValueEqual(target.getHealth(), health, "No revoked player damage occurs");
                    c.succeed();
                } finally { close.run(); }
            });
        } catch (RuntimeException | Error failure) { close.run(); throw failure; }
    }
}
