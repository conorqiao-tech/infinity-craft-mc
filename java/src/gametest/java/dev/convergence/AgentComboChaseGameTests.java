package dev.convergence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Autonomous combat against native chasing AI, including ordinary knockback. */
public class AgentComboChaseGameTests {
    private static final class ChaseArena implements AutoCloseable {
        private static long sequence = 1900;
        final net.minecraft.server.level.TicketType ticket = new net.minecraft.server.level.TicketType(++sequence,
            net.minecraft.server.level.TicketType.FLAG_LOADING | net.minecraft.server.level.TicketType.FLAG_SIMULATION
                | net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        final List<ChunkPos> chunks = new ArrayList<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final GameTestHelper context;
        final ServerPlayer owner;
        final AgentCompanions helpers;
        final IronGolem helper;
        final Zombie hostile;
        final Vec3 initialHelper;
        final double nativeResistance;
        final ArrayDeque<String> trace = new ArrayDeque<>();
        float previousHealth;
        int smashes, flights, damagingContacts;
        boolean wasFlying, sawSpearUse, sawChase;

        ChaseArena(GameTestHelper c, String name, double distance, boolean roof) {
            context = c;
            owner = new ModeGameTests().player(c, name);
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SURVIVAL);
            var feet = c.absolutePos(new BlockPos(24, 55, 24));
            // Stay inside the reserved48x64x48 structure. Ordinary knockback
            // must encounter real supported ground and perimeter walls rather
            // than throwing the victim off a tiny artificial floating platform.
            for (var at : BlockPos.betweenClosed(feet.offset(-22, -1, -22), feet.offset(22, 8, 22))) {
                boolean wall = (Math.abs(at.getX() - feet.getX()) == 22 || Math.abs(at.getZ() - feet.getZ()) == 22)
                    && at.getY() >= feet.getY() && at.getY() <= feet.getY() + 5;
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 || wall
                    || roof && at.getY() == feet.getY() + 3 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            for (int x = (feet.getX() - 22) >> 4; x <= (feet.getX() + 22) >> 4; x++)
                for (int z = (feet.getZ() - 22) >> 4; z <= (feet.getZ() + 22) >> 4; z++) {
                    var chunk = new ChunkPos(x, z); chunks.add(chunk);
                    c.getLevel().getChunkSource().addTicketWithRadius(ticket, chunk, 2);
                }
            owner.setPos(Vec3.atBottomCenterOf(feet).add(0, 0, -4));
            helpers = AgentCompanions.get(c.getLevel().getServer());
            helpers.spawn(owner, "alpha");
            var record = helpers.owned(owner, "alpha");
            if (record == null) throw new IllegalStateException("Normal helper creation failed");
            helper = helpers.loaded.get(UUID.fromString(record.getKey()));
            helpers.profile(owner, "alpha", AgentCompanions.Profile.ULTIMATE_FINALS);
            // Only initial placement is fixed. No combat control, launch, native
            // item use, attack, falling distance or victim movement is driven here.
            helper.setPos(Vec3.atBottomCenterOf(feet));
            initialHelper = helper.position();
            hostile = EntityTypes.HUSK.create(c.getLevel(), EntitySpawnReason.COMMAND);
            hostile.setPersistenceRequired();
            // Extend health solely to observe successive native attacks. AI,
            // movement, damage and knockback retain the Husk's native defaults.
            hostile.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            hostile.setHealth(1000);
            hostile.setPos(initialHelper.add(distance, 0, 0));
            nativeResistance = hostile.getAttribute(Attributes.KNOCKBACK_RESISTANCE).getBaseValue();
            previousHealth = hostile.getHealth();
            c.getLevel().addFreshEntity(hostile);
        }

        void observe(int tick) {
            if (hostile.isAlive()) context.assertTrue(hostile.getY() >= initialHelper.y - .15,
                "The active victim stays above the arena's supported floor" + diagnostic());
            boolean flying = helper.isFallFlying() || helper.getFallFlyingTicks() > 0;
            if (flying && !wasFlying) flights++;
            wasFlying = flying;
            sawSpearUse |= helper.getMainHandItem().is(Items.NETHERITE_SPEAR) && helper.isUsingItem();
            sawChase |= hostile.getTarget() == helper && hostile.getNavigation().isInProgress();
            float health = hostile.getHealth();
            if (health < previousHealth) {
                damagingContacts++;
                var source = hostile.getLastDamageSource();
                if (source != null && source.is(DamageTypes.MACE_SMASH)) smashes++;
            }
            previousHealth = health;
            if (tick % 5 == 0) {
                var phase = helpers.comboFeedback(helper.getUUID());
                trace.add(String.format(Locale.ROOT,
                    "t=%d d=%.2f y=%.2f/%.2f targetXZ=%.2f/%.2f hp=%.1f/%.1f weapon=%s use=%d fall=%.2f ground=%s chase=%s phase=%s",
                    tick, helper.distanceTo(hostile), helper.getY(), hostile.getY(), hostile.getX() - initialHelper.x,
                    hostile.getZ() - initialHelper.z, helper.getHealth(), hostile.getHealth(),
                    helper.getMainHandItem().getHoverName().getString(), helper.getTicksUsingItem(), helper.fallDistance,
                    helper.onGround(), hostile.getTarget() == helper, phase == null ? "none" : phase.phase() + ": " + phase.detail()));
                if (trace.size() > 48) trace.removeFirst();
            }
        }

        String diagnostic() { return " flights=" + flights + " smashes=" + smashes + " contacts=" + damagingContacts + " trace=" + trace; }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            hostile.discard();
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            helpers.ceasefire(owner); helpers.dismiss(owner, "alpha");
            OperatorGameTests.deop(owner); helpers.server.getPlayerList().remove(owner);
            for (var chunk : chunks) owner.level().getChunkSource().removeTicketWithRadius(ticket, chunk, 2);
        }
    }

    private void exercise(GameTestHelper c, ChaseArena arena, int ticks, Runnable assertions) {
        for (int tick = 1; tick <= ticks; tick++) {
            int observed = tick;
            c.runAtTickTime(tick, () -> {
                if (arena.closed.get()) return;
                try { arena.observe(observed); }
                catch (RuntimeException | Error failure) { arena.close(); throw failure; }
            });
        }
        c.runAtTickTime(ticks + 1, () -> {
            try {
                c.assertFalse(arena.hostile.isNoAi(), "The hostile retains active native AI");
                c.assertValueEqual(arena.hostile.getAttribute(Attributes.KNOCKBACK_RESISTANCE).getBaseValue(),
                    arena.nativeResistance, "The fixture never suppresses ordinary victim knockback");
                assertions.run(); c.succeed();
            } finally { arena.close(); }
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=300)
    public void closeChasingHostileReachesPhysicalGlideAndNativeMaceSmash(GameTestHelper c) {
        var arena = new ChaseArena(c, "chase-close", 2, false);
        exercise(c, arena, 260, () -> {
            c.assertTrue(arena.sawChase, "The native hostile actually pursues the helper" + arena.diagnostic());
            c.assertTrue(arena.flights >= 1 && arena.smashes >= 1,
                "A close chasing hostile must not trap Ultimate Finals in ground-only combat" + arena.diagnostic());
            c.assertFalse(arena.helper.isNoGravity(), "Combo movement retains native gravity");
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=400)
    public void nativeChaseAndKnockbackAllowAnotherCompleteSequence(GameTestHelper c) {
        var arena = new ChaseArena(c, "chase-repeat", 5, false);
        exercise(c, arena, 360, () -> {
            c.assertTrue(arena.sawChase && arena.damagingContacts >= 2,
                "The native hostile keeps moving and taking real contacts" + arena.diagnostic());
            c.assertTrue(arena.flights >= 2 && arena.smashes >= 2,
                "Ordinary chase and native knockback must permit renewed complete combos" + arena.diagnostic());
            c.assertFalse(arena.helper.isNoGravity(), "Repeated combos retain native gravity");
        });
    }

    @GameTest(structure="convergence_tests:combat_arena", maxTicks=210)
    public void lowCeilingRetainsNativeGroundDamageWithoutUnsafeFlight(GameTestHelper c) {
        var arena = new ChaseArena(c, "combo-roof-chase", 2, true);
        exercise(c, arena, 170, () -> {
            c.assertTrue(arena.damagingContacts >= 2,
                "A blocked aerial route must retain useful native ground combat" + arena.diagnostic());
            c.assertTrue(arena.flights == 0 && !arena.helpers.aerial.containsKey(arena.helper.getUUID()),
                "A low ceiling never produces an unsafe aerial attempt" + arena.diagnostic());
        });
    }
}
