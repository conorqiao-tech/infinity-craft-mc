package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class HelperPerformanceGameTests {
    static final class Squad implements AutoCloseable {
        final AgentTacticsGameTests.Arena arena;
        final List<IronGolem> helpers=new ArrayList<>();
        final List<Zombie> hostiles=new ArrayList<>();
        Squad(GameTestHelper c,String name) {
            arena=new AgentTacticsGameTests.Arena(c,name);
            arena.owner.setPos(arena.start);
            helpers.add(arena.golem);
            for(String helperName:List.of("beta","gamma","delta","epsilon","zeta"))helpers.add(arena.add(helperName,arena.start));
            for(var helper:helpers)arena.helpers.profile(arena.owner,arena.record(helper).name(),AgentCompanions.Profile.REGULAR);
            arena.target.discard();resetHelpers();
        }
        void resetHelpers() {
            for(int i=0;i<helpers.size();i++) {
                var helper=helpers.get(i);
                arena.helpers.cancelAerial(helper);helper.setTarget(null);helper.getSensing().tick();helper.getNavigation().stop();
                helper.setPos(arena.start.add(-5,0,-3+i*1.2));helper.setOnGround(true);helper.setDeltaMovement(Vec3.ZERO);
                arena.helpers.lastAttack.remove(helper.getUUID());
            }
        }
        void hostiles(int count) {
            for(var hostile:hostiles)hostile.discard();hostiles.clear();
            for(int i=0;i<count;i++) {
                var mob=EntityTypes.HUSK.create(arena.context.getLevel(),EntitySpawnReason.COMMAND);
                mob.setNoAi(true);mob.setPersistenceRequired();mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(500);mob.setHealth(500);
                mob.setPos(arena.start.add(4+(i%16)*.25,0,-2+(i/16)*.5));mob.setOnGround(true);
                arena.context.getLevel().addFreshEntity(mob);hostiles.add(mob);
            }
        }
        void resetHostiles() {
            for(int i=0;i<hostiles.size();i++) {
                var mob=hostiles.get(i);if(!mob.isAlive())throw new IllegalStateException("Benchmark lost a hostile");
                mob.setHealth(500);mob.setPos(arena.start.add(4+(i%16)*.25,0,-2+(i/16)*.5));mob.setDeltaMovement(Vec3.ZERO);
            }
        }
        HelperPerformanceProbe.Sample batch(int tick) {
            resetHelpers();HelperPerformanceProbe.begin();
            try {for(var helper:helpers)arena.helpers.control(helper,arena.record(helper),tick);return HelperPerformanceProbe.end();}
            finally {HelperPerformanceProbe.active=null;}
        }
        public void close(){HelperPerformanceProbe.lifecycle=null;HelperPerformanceProbe.active=null;for(var mob:hostiles)mob.discard();arena.close();}
    }

    @GameTest(structure="convergence_tests:combat_arena") public void sparseFocusWorkIsBoundedWithoutExtraQueriesOrSightRays(GameTestHelper c) {
        try(var squad=new Squad(c,"focus-counts")) {
            for(int count:new int[]{0,1,4}) {
                squad.hostiles(count);int loaded=squad.arena.helpers.loaded.size();var work=squad.batch(80);
                c.assertValueEqual(work.controls,6L,"All six actual control calls run");
                c.assertValueEqual(work.queries,6L,"Each helper retains its native candidate query");
                c.assertValueEqual(work.candidates,count*6L,"Actual native queries return exactly the fixture hostiles");
                c.assertValueEqual(work.sightRays,count*6L,"Each helper keeps independent uncached native sight rays");
                c.assertValueEqual(work.focusLookups,count>1?12L*(count-1):0L,"Focus comparisons preserve native min selection work");
                c.assertValueEqual(work.focusPeerVisits,count>1?6L*loaded:0L,"A multi-candidate control scans live peers once; sparse zero/one scans none");
            }
            squad.hostiles(2);squad.hostiles.getLast().setTarget(squad.arena.owner);
            var threat=squad.batch(85);
            c.assertValueEqual(threat.focusPeerVisits,0L,"A decisive owner-threat comparison never builds the lazy focus set");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena") public void focusUsesLiveEarlierHelpersAndExcludesPausedPassiveAndDeadTargets(GameTestHelper c) {
        try(var squad=new Squad(c,"focus-live")) {
            squad.hostiles(2);var f=squad.arena;var alpha=squad.helpers.getFirst();var beta=squad.helpers.get(1);
            var focus=squad.hostiles.getFirst();var closer=squad.hostiles.getLast();
            focus.setPos(f.start.add(4,0,0));closer.setPos(f.start.add(6,0,4));
            alpha.setPos(f.start.add(-5,0,-3));beta.setPos(f.start.add(6,0,3));
            f.helpers.control(alpha,f.record(alpha),80);f.helpers.control(beta,f.record(beta),80);
            c.assertTrue(alpha.getTarget()==focus && beta.getTarget()==focus,"Later helper sees the earlier helper's newly chosen focus within the same tick");
            closer.setTarget(f.owner);f.helpers.control(beta,f.record(beta),85);
            c.assertTrue(beta.getTarget()==closer,"A live owner threat still overrides squad focus");closer.setTarget(null);
            for(var mode:List.of(AgentCompanions.Mode.STAY,AgentCompanions.Mode.FOLLOW)) {
                f.helpers.mode(f.owner,"alpha",mode);
                f.helpers.profile(f.owner,"alpha",mode==AgentCompanions.Mode.STAY?AgentCompanions.Profile.REGULAR:AgentCompanions.Profile.DEBUG);
                alpha.setTarget(focus);f.helpers.control(beta,f.record(beta),90);
                c.assertTrue(beta.getTarget()==closer,"Paused and passive peers cannot reserve a focus: "+mode);
            }
            f.helpers.profile(f.owner,"alpha",AgentCompanions.Profile.REGULAR);alpha.setTarget(focus);
            focus.setHealth(0);f.helpers.control(beta,f.record(beta),95);
            c.assertTrue(beta.getTarget()==closer,"A dead former focus never survives into candidate selection");
            c.assertFalse(beta.doHurtTarget(c.getLevel(),f.owner),"Focus optimization cannot grant player damage permission");
        }
        c.succeed();
    }

    @GameTest(structure="convergence_tests:combat_arena") public void focusedTargetsStillNeedCurrentRangeAndEachHelpersSight(GameTestHelper c) {
        try(var squad=new Squad(c,"focus-sight")) {
            squad.hostiles(2);var f=squad.arena;var alpha=squad.helpers.getFirst();var beta=squad.helpers.get(1);
            var focus=squad.hostiles.getFirst();var visible=squad.hostiles.getLast();
            focus.setPos(f.start.add(4,0,0));visible.setPos(f.start.add(-6,0,-3));alpha.setTarget(focus);
            BlockPos wall=BlockPos.containing(f.start).east();
            for(var at:BlockPos.betweenClosed(wall.offset(0,0,-8),wall.offset(0,4,8)))c.getLevel().setBlockAndUpdate(at,Blocks.STONE.defaultBlockState());
            beta.setPos(f.start.add(-5,0,-3));beta.getSensing().tick();f.helpers.control(beta,f.record(beta),80);
            c.assertTrue(beta.getTarget()==visible,"A peer's focus never bypasses this helper's native sight check");
            for(var at:BlockPos.betweenClosed(wall.offset(0,0,-8),wall.offset(0,4,8)))c.getLevel().setBlockAndUpdate(at,Blocks.AIR.defaultBlockState());
            focus.setPos(f.start.add(11,0,0));beta.getSensing().tick();f.helpers.control(beta,f.record(beta),85);
            c.assertTrue(beta.getTarget()==visible,"Regular focus remains inside the exact ten-block owner range");
            f.helpers.profile(f.owner,"beta",AgentCompanions.Profile.PRIMITIVE);visible.setPos(f.start.add(-11,0,0));beta.getSensing().tick();
            f.helpers.control(beta,f.record(beta),90);
            c.assertTrue(beta.getTarget()==visible,"Primitive keeps its twelve-block range and golem-distance ordering without shared focus");
        }
        c.succeed();
    }
}
