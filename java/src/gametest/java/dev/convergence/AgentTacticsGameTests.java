package dev.convergence;

import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;

/** Actual golem movement and vanilla attacks; no simulated player equipment or custom damage. */
public class AgentTacticsGameTests {
    static final class Arena implements AutoCloseable {
        final TestContext context;
        final ServerPlayerEntity owner;
        final AgentCompanions helpers;
        final IronGolemEntity golem;
        final ZombieEntity target;
        final Vec3d start;
        Arena(TestContext context, String name) {
            this.context = context;
            owner = new ModeGameTests().player(context, name);
            OperatorGameTests.level(owner, LeveledPermissionPredicate.OWNERS);
            owner.changeGameMode(GameMode.SURVIVAL);
            BlockPos feet = context.getAbsolutePos(new BlockPos(4, 55, 4));
            for (BlockPos at : BlockPos.iterate(feet.add(-9, -1, -9), feet.add(9, 8, 9)))
                context.getWorld().setBlockState(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
            start = Vec3d.ofBottomCenter(feet);
            owner.setPosition(start.add(-5, 0, -5));
            helpers = AgentCompanions.get(context.getWorld().getServer());
            golem = add("alpha", start);
            target = EntityType.HUSK.create(context.getWorld(), SpawnReason.COMMAND);
            target.setAiDisabled(true); target.setPersistent();
            target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(500);
            target.setHealth(500); target.setPosition(start.add(5, 0, 0)); target.setOnGround(true);
            context.getWorld().spawnEntity(target);
        }
        IronGolemEntity add(String name, Vec3d point) {
            helpers.spawn(owner, name);
            var record = helpers.owned(owner, name);
            if (record == null) throw new IllegalStateException("No fixture helper");
            var result = helpers.loaded.get(UUID.fromString(record.getKey()));
            helpers.profile(owner, name, AgentCompanions.Profile.ULTIMATE_FINALS);
            result.setPosition(point); result.setOnGround(true); result.setVelocity(Vec3d.ZERO);
            return result;
        }
        AgentCompanions.Agent record(IronGolemEntity helper) { return helpers.data.agents.get(helper.getUuidAsString()); }
        boolean launch(int tick) {
            golem.setTarget(target);
            return helpers.startAerial(golem, record(golem), owner, target, tick);
        }
        public void close() {
            helpers.ceasefire(owner); target.discard();
            OperatorGameTests.level(owner, LeveledPermissionPredicate.OWNERS);
            var names = helpers.data.agents.values().stream().filter(a -> a.owner().equals(owner.getUuidAsString())).map(AgentCompanions.Agent::name).toList();
            for (String name : names) helpers.dismiss(owner, name);
            OperatorGameTests.deop(owner); helpers.server.getPlayerManager().remove(owner);
        }
    }

    @GameTest(maxTicks=60) public void ultimateLeapActuallyMovesThenUsesVanillaMelee(TestContext c) {
        var f = new Arena(c, "tactic-air");
        try {
            c.assertTrue(f.launch(f.helpers.server.getTicks()), "Clear ground launches a physical golem leap");
            c.assertTrue(f.golem.getVelocity().y > 0 && !f.golem.hasNoGravity(), "Launch uses upward velocity with ordinary gravity");
            c.waitAndRun(5, () -> {
                try {
                    c.assertTrue(f.golem.getY() > f.start.y + .3, "Real entity ticks raise the golem above the ground");
                    c.assertTrue(f.golem.getX() > f.start.x + .4, "Real entity ticks pursue the landing point");
                    c.waitAndRun(25, () -> {
                        try {
                            c.assertTrue(f.target.getHealth() < 500, "Landing closes to native melee range and causes a real golem hit");
                            c.assertTrue(f.helpers.lastAttack.containsKey(f.golem.getUuid()), "Damage follows the helper's recorded native attack path");
                            c.assertTrue(f.target.getHealth() > 350, "The sequence never uses Infinity player weapon burst damage");
                            c.assertTrue(f.golem.getY() < f.start.y + .3, "The helper descends and lands rather than hovering");
                            c.complete();
                        } finally { f.close(); }
                    });
                } catch (Throwable failure) { f.close(); throw failure; }
            });
        } catch (Throwable failure) { f.close(); throw failure; }
    }

    @GameTest public void ceilingsAndWallsRejectLeapsAndKeepGroundPursuit(TestContext c) {
        try (var f = new Arena(c, "tactic-obstacle")) {
            // Follow mode senses hostiles within ten blocks of the owner, not the helper.
            f.owner.setPosition(f.start.add(0, 0, -4));
            BlockPos roof = BlockPos.ofFloored(f.start).up(3);
            for (BlockPos at : BlockPos.iterate(roof.add(-1, 0, -1), roof.add(6, 0, 1))) c.getWorld().setBlockState(at, Blocks.STONE.getDefaultState());
            c.assertFalse(f.launch(80), "Low roof prevents the full-height leap corridor");
            f.helpers.control(f.golem, f.record(f.golem), 80);
            c.assertTrue(f.golem.getTarget() == f.target, "The nearby hostile remains the ground pursuit target");
            c.assertFalse(f.golem.getNavigation().isIdle(), "Blocked aerial route falls back to native ground pursuit");
            c.assertTrue(f.golem.getVelocity().y <= 0, "Rejected leap adds no upward motion");
            for (BlockPos at : BlockPos.iterate(roof.add(-1, 0, -1), roof.add(6, 0, 1))) c.getWorld().setBlockState(at, Blocks.AIR.getDefaultState());
            BlockPos wall = BlockPos.ofFloored(f.start).east(2);
            for (BlockPos at : BlockPos.iterate(wall.add(0, 0, -2), wall.add(0, 6, 2))) c.getWorld().setBlockState(at, Blocks.STONE.getDefaultState());
            c.assertFalse(f.launch(80), "A full wall cannot be crossed by a leap");
            float health = f.target.getHealth();
            c.assertFalse(f.helpers.strike(f.golem, f.target, 80), "No distant or occluded aerial strike is possible");
            c.assertEquals(f.target.getHealth(), health, "Wall obstruction causes no damage");
        }
        c.complete();
    }

    @GameTest public void aerialRolesLeadBoundsCooldownAndGuardLeashAreDeterministic(TestContext c) {
        try (var f = new Arena(c, "tactic-roles")) {
            var beta = f.add("beta", f.start.add(0, 0, 2));
            f.golem.setTarget(f.target); beta.setTarget(f.target);
            c.assertFalse(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, f.target, 80), "The rotating second role leaves alpha on the ground");
            c.assertTrue(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 80), "The selected beta role starts the same-target dive");
            c.assertFalse(f.launch(160), "Only one peer can be in the same-target aerial sequence");
            f.helpers.cancelAerial(beta); beta.setOnGround(true);
            c.assertFalse(f.helpers.startAerial(beta, f.record(beta), f.owner, f.target, 81), "Cancelling cannot skip the per-helper leap cooldown");
            f.target.setVelocity(100, 0, 0);
            var prediction = f.helpers.intercept(f.golem, f.record(f.golem), f.owner, f.target);
            c.assertTrue(prediction != null && prediction.distanceTo(f.target.getEntityPos()) <= 2.001, "Very fast target motion has at most a two-block lead");
            f.helpers.mode(f.owner, "alpha", AgentCompanions.Mode.GUARD);
            c.assertFalse(f.helpers.airClear(f.golem, f.record(f.golem), f.owner, f.start, f.start.add(13.9, 0, 0), 3), "A guard arc cannot extend outside its three-dimensional leash");
        }
        c.complete();
    }

    @GameTest public void nativeComboKeepsAttackCadenceAndCancelsOnProfileRevocation(TestContext c) {
        try (var f = new Arena(c, "tactic-cadence")) {
            f.golem.setPosition(f.target.getEntityPos().add(-1.6, 0, 0));
            float before = f.target.getHealth();
            c.assertTrue(f.helpers.strike(f.golem, f.target, 20), "First normal melee strike succeeds");
            float after = f.target.getHealth();
            c.assertTrue(after < before, "Only vanilla attack damage is applied");
            c.assertFalse(f.helpers.strike(f.golem, f.target, 39), "Follow-up cannot bypass twenty-tick attack cadence");
            c.assertEquals(f.target.getHealth(), after, "Early follow-up adds no second damage event");
            f.golem.setPosition(f.start); f.golem.setOnGround(true); f.target.setPosition(f.start.add(5, 0, 0));
            f.target.setVelocity(Vec3d.ZERO);
            c.assertTrue(f.launch(80), "A fresh valid aerial sequence can start");
            f.helpers.profile(f.owner, "alpha", AgentCompanions.Profile.DEBUG);
            c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUuid()), "Passive profile change cancels the active aerial state immediately");
            c.assertTrue(f.golem.getVelocity().y <= 0 && !f.golem.hasNoGravity(), "Cancelled ascent stops steering and leaves gravity enabled");
            c.assertFalse(f.golem.tryAttack(c.getWorld(), f.target), "A passive helper cannot force the follow-up damage");
        }
        c.complete();
    }

    @GameTest public void playerDiveNeedsAnActiveOrderAndCeasefireOrDeopStopsIt(TestContext c) {
        try (var f = new Arena(c, "tactic-owner")) {
            var target = new ModeGameTests().player(c, "tactic-player");
            boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
            try {
                c.getWorld().getGameRules().setValue(GameRules.PVP, true, f.helpers.server);
                target.changeGameMode(GameMode.SURVIVAL); target.setPosition(f.start.add(5, 0, 0));
                f.golem.setTarget(target);
                c.assertFalse(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, 80), "Naming a player alone cannot authorize a dive");
                c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "Fixture activates the exact order through the final reviewed-assignment method");
                c.assertTrue(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, 80), "Approved exact target can be pursued");
                f.helpers.ceasefire(f.owner);
                c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUuid()), "Ceasefire cancels the airborne sequence immediately");
                f.golem.setPosition(target.getEntityPos().add(-1, 0, 0));
                c.assertFalse(f.golem.tryAttack(c.getWorld(), target), "Even a forced close hit cannot bypass the cancelled player order");
                f.golem.setPosition(f.start); f.golem.setOnGround(true);
                c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "Another sequence requires another exact reviewed assignment");
                c.assertTrue(f.helpers.startAerial(f.golem, f.record(f.golem), f.owner, target, 160), "A later approved sequence respects the cooldown");
                OperatorGameTests.deop(f.owner); f.helpers.tickAerial(f.golem, 161);
                c.assertFalse(f.helpers.aerial.containsKey(f.golem.getUuid()), "OP4 loss cancels the sequence before its next movement step");
                c.assertFalse(f.golem.tryAttack(c.getWorld(), target), "Revocation cannot leave a damaging follow-up behind");

                OperatorGameTests.level(f.owner, LeveledPermissionPredicate.OWNERS);
                f.golem.setPosition(f.start); f.golem.setOnGround(true);
                f.helpers.mode(f.owner, "alpha", AgentCompanions.Mode.GUARD);
                var beta = f.add("beta", f.start.add(0, 0, 2));
                f.helpers.mode(f.owner, "beta", AgentCompanions.Mode.GUARD);
                for (BlockPos at : BlockPos.iterate(BlockPos.ofFloored(f.start).add(11,-1,-3), BlockPos.ofFloored(f.start).add(17,-1,3)))
                    c.getWorld().setBlockState(at, Blocks.STONE.getDefaultState());
                target.setPosition(f.start.add(13.5, 0, 0));
                c.assertTrue(f.helpers.assignPlayerTarget(f.owner, target), "A fresh reviewed target remains inside both guard anchors");
                f.golem.setTarget(target); beta.setTarget(target);
                c.assertTrue(f.helpers.landingClear(f.golem, target.getEntityPos().add(1.6,0,0)), "Rejected flank has clear, supported terrain");
                c.assertTrue(f.helpers.playerPursuitReady(f.golem, f.record(f.golem), f.owner, target), "The player itself is within the guard leash");
                c.assertTrue(f.helpers.flankPoint(f.golem, f.record(f.golem), f.owner, target) == null, "A valid player target cannot steer its flanking guard beyond fourteen blocks");
            } finally {
                c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, f.helpers.server);
                f.helpers.server.getPlayerManager().remove(target);
            }
        }
        c.complete();
    }
}
