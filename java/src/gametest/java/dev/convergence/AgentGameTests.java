package dev.convergence;

import dev.convergence.mixin.AgentGoalAccess;
import java.nio.file.Files;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

public class AgentGameTests {
    private ServerPlayerEntity player(TestContext c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var data = net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);
        var p = new ServerPlayerEntity(c.getWorld().getServer(), c.getWorld(), profile, data.syncedOptions());
        var connection = new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, p, data);
        p.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        c.getWorld().getServer().getPlayerManager().addToOperators(new net.minecraft.server.PlayerConfigEntry(profile), java.util.Optional.of(LeveledPermissionPredicate.OWNERS), java.util.Optional.of(false));
        BlockPos feet = c.getAbsolutePos(new BlockPos(3, 20, 3));
        for (BlockPos at : BlockPos.iterate(feet.add(-7, -1, -7), feet.add(7, 4, 7)))
            c.getWorld().setBlockState(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        p.setPosition(Vec3d.ofBottomCenter(feet)); p.setNoGravity(true); p.changeGameMode(GameMode.SURVIVAL);
        return p;
    }
    private IronGolemEntity golem(AgentCompanions s, ServerPlayerEntity p, String name) {
        s.spawn(p, name); var entry = s.owned(p, name);
        if (entry == null) throw new IllegalStateException("No helper was spawned");
        return s.loaded.get(UUID.fromString(entry.getKey()));
    }
    private void cleanup(AgentCompanions s, ServerPlayerEntity p) {
        var names = s.data.agents.values().stream().filter(a -> a.owner().equals(p.getUuidAsString())).map(AgentCompanions.Agent::name).toList();
        for (String name : names) s.dismiss(p, name);
        s.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
        s.server.getPlayerManager().remove(p);
    }
    @GameTest public void helpersRequirePermissionLevelFour(TestContext c) {
        var root = c.getWorld().getServer().getCommandManager().getDispatcher().getRoot().getChild("agent");
        var source = c.getWorld().getServer().getCommandSource();
        c.assertFalse(root.canUse(source.withPermissions(PermissionPredicate.NONE)), "Ordinary players cannot control helpers");
        c.assertFalse(root.canUse(source.withPermissions(LeveledPermissionPredicate.GAMEMASTERS)), "OP2 cannot control helpers");
        c.assertFalse(root.canUse(source.withPermissions(LeveledPermissionPredicate.ADMINS)), "OP3 cannot control helpers");
        c.assertTrue(root.canUse(source.withPermissions(LeveledPermissionPredicate.OWNERS)), "OP4 controls helpers");
        for (String command : new String[]{"help", "spawn", "follow", "guard", "stay", "dismiss", "list"}) c.assertTrue(root.getChild(command) != null, "Command exists: " + command);
        c.complete();
    }
    @GameTest public void helpersBoundNamesOwnershipAndUnloadedCount(TestContext c) {
        var p = player(c, "helper-owner"); var other = player(c, "helper-other"); var s = AgentCompanions.get(c.getWorld().getServer());
        try {
            s.spawn(p, "../escape"); c.assertEquals(s.count(p), 0, "Invalid names rejected");
            var golem = golem(s, p, "one"); s.spawn(p, "one"); c.assertEquals(s.count(p), 1, "Duplicate name rejected");
            s.mode(other, "one", AgentCompanions.Mode.STAY); s.dismiss(other, "one");
            c.assertTrue(s.owned(p, "one") != null, "Other UUID cannot modify or dismiss owner's helper");
            c.assertEquals(s.owned(p, "one").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Unauthorized mode change rejected");
            golem(s, p, "two"); golem(s, p, "three");
            s.loaded.remove(golem.getUuid()); s.spawn(p, "four");
            c.assertEquals(s.count(p), AgentCompanions.LIMIT, "Unloaded helper still consumes a slot");
            c.assertTrue(s.owned(p, "four") == null, "Fourth helper rejected");
            s.dismiss(p, "one"); s.load(golem); c.assertTrue(golem.isRemoved(), "Dismissed unloaded entity removed on next load");
            c.assertEquals(s.count(p), 2, "Dismissal frees exactly one slot");
        } finally { cleanup(s, p); cleanup(s, other); }
        c.complete();
    }
    @GameTest public void helpersPersistRosterVanillaTagsAndModes(TestContext c) {
        var p = player(c, "helper-save"); var s = AgentCompanions.get(c.getWorld().getServer());
        try {
            var golem = golem(s, p, "keeper"); s.mode(p, "keeper", AgentCompanions.Mode.GUARD);
            var record = s.owned(p, "keeper");
            c.assertEquals(AgentCompanions.read(s.file).agents.get(record.getKey()).mode(), AgentCompanions.Mode.GUARD, "Guard mode and ownership survive roster reload");
            var write = NbtWriteView.create(ErrorReporter.EMPTY, c.getWorld().getRegistryManager()); golem.writeData(write);
            var restored = EntityType.IRON_GOLEM.create(c.getWorld(), SpawnReason.LOAD);
            restored.readData(NbtReadView.create(ErrorReporter.EMPTY, c.getWorld().getRegistryManager(), write.getNbt()));
            c.assertTrue(restored.getCommandTags().contains(AgentCompanions.TAG), "Vanilla Tags preserve identity after entity NBT roundtrip");
            c.assertTrue(restored.getCommandTags().contains("infinity_owner_" + p.getUuidAsString()), "Owner is identifiable in vanilla NBT");
            c.assertTrue(restored.getCommandTags().contains("infinity_mode_guard"), "Mode is identifiable in vanilla NBT");
            golem.discard(); s.load(restored);
            c.assertTrue(restored.isPersistent(), "Loaded helper does not despawn");
            c.assertTrue(((AgentGoalAccess) restored).infinity$getTargetSelector().getGoals().isEmpty(), "Reload removes vanilla revenge and village targeting");
            c.assertTrue(s.loaded.get(restored.getUuid()) == restored, "Reload reconnects helper to its roster UUID");
        } finally { cleanup(s, p); }
        c.complete();
    }
    @GameTest(maxTicks = 60) public void helpersActuallyFollowUsingNavigation(TestContext c) {
        var p = player(c, "helper-follow"); var s = AgentCompanions.get(c.getWorld().getServer());
        var golem = golem(s, p, "walker");
        golem.setPosition(p.getX() - 5, p.getY(), p.getZ());
        double start = golem.squaredDistanceTo(p);
        // The first physics tick varies with test chunk loading; navigation must
        // start as soon as the helper actually lands on the platform.
        boolean[] started={false};
        c.runAtEveryTick(() -> {
            if(started[0] || !golem.isOnGround())return;
            try {
                s.control(golem, s.owned(p, "walker").getValue(), s.server.getTicks());
                c.assertFalse(golem.getNavigation().isIdle(), "Follow starts real vanilla path navigation after landing");
                started[0]=true;
            } catch (RuntimeException failure) { cleanup(s, p); throw failure; }
        });
        c.runAtTick(45, () -> {
            try {
                c.assertTrue(started[0], "Helper landed and started vanilla path navigation");
                c.assertTrue(golem.squaredDistanceTo(p) < start - 1, "Helper walked toward the owner through vanilla navigation");
            }
            finally { cleanup(s, p); }
            c.complete();
        });
    }
    @GameTest public void helpersAttackHostilesAndNeverDamagePlayersOrPets(TestContext c) {
        var p = player(c, "helper-combat"); var s = AgentCompanions.get(c.getWorld().getServer());
        var wolf = EntityType.WOLF.create(c.getWorld(), SpawnReason.COMMAND);
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "shield"); wolf.setPosition(golem.getEntityPos()); wolf.setTamedBy(p); c.getWorld().spawnEntity(wolf);
            zombie.setPosition(golem.getEntityPos().add(1, 0, 0)); zombie.setAiDisabled(true); c.getWorld().spawnEntity(zombie);
            float playerHealth = p.getHealth(), petHealth = wolf.getHealth(), hostileHealth = zombie.getHealth();
            golem.setTarget(p); c.assertFalse(golem.tryAttack(c.getWorld(), p), "Real golem attack on player is blocked");
            c.assertEquals(p.getHealth(), playerHealth, "Player health unchanged");
            golem.setTarget(wolf); c.assertFalse(golem.tryAttack(c.getWorld(), wolf), "Real golem attack on tamed pet is blocked");
            c.assertEquals(wolf.getHealth(), petHealth, "Pet health unchanged");
            golem.damage(c.getWorld(), golem.getDamageSources().playerAttack(p), 1);
            golem.setTarget(null); golem.tick();
            c.assertTrue(golem.getTarget() != p, "Vanilla revenge goals cannot target the attacking owner");
            s.control(golem, s.owned(p, "shield").getValue(), 20);
            c.assertTrue(golem.getTarget() == zombie, "Helper chooses a nearby hostile mob");
            c.assertTrue(zombie.getHealth() < hostileHealth, "Real helper melee attack damages hostile mob");
            s.mode(p, "shield", AgentCompanions.Mode.STAY); s.control(golem, s.owned(p, "shield").getValue(), 40);
            c.assertTrue(golem.getTarget() == null, "Stay disables combat");
            c.assertTrue(golem.getNavigation().isIdle(), "Stay halts navigation");
        } finally { wolf.discard(); zombie.discard(); cleanup(s, p); }
        c.complete();
    }
    @GameTest public void helpersPauseForLogoutDeathAndDifferentDimensions(TestContext c) {
        var p = player(c, "helper-lifecycle"); var s = AgentCompanions.get(c.getWorld().getServer());
        var golem = golem(s, p, "patient");
        golem.setPosition(p.getX() - 5, p.getY(), p.getZ());
        boolean[] checked={false};
        c.runAtEveryTick(() -> {
          if(checked[0] || !golem.isOnGround())return;
          checked[0]=true;
          try {
            var agent = s.owned(p, "patient").getValue();
            s.control(golem, agent, 20);
            c.assertFalse(golem.getNavigation().isIdle(), "Live owner permits following");
            p.setHealth(0); s.control(golem, agent, 25);
            c.assertTrue(golem.getNavigation().isIdle(), "Owner death pauses helper"); p.setHealth(p.getMaxHealth());
            s.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
            s.control(golem, agent, 30); c.assertTrue(golem.getNavigation().isIdle(), "Losing OP4 pauses helper");
            s.server.getPlayerManager().addToOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()), java.util.Optional.of(LeveledPermissionPredicate.OWNERS), java.util.Optional.of(false));
            var offline = new AgentCompanions.Agent(UUID.randomUUID().toString(), agent.name(), agent.mode(), agent.dimension(), agent.x(), agent.y(), agent.z());
            s.control(golem, offline, 30); c.assertTrue(golem.getNavigation().isIdle(), "Offline owner pauses helper without removing persistent roster");
            Vec3d pos = p.getEntityPos(); var nether = s.server.getWorld(World.NETHER);
            p.teleport(nether, 0, 100, 0, Set.of(), 0, 0, true); s.control(golem, agent, 35);
            c.assertTrue(golem.getNavigation().isIdle(), "Different dimension pauses helper");
            c.assertTrue(golem.getEntityWorld() == c.getWorld(), "Helper stays in its original dimension");
            p.teleport(c.getWorld(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
            s.mode(p, "patient", AgentCompanions.Mode.GUARD); var guard = s.owned(p, "patient").getValue();
            golem.setPosition(golem.getEntityPos().add(5, 0, 0)); s.control(golem, guard, 40);
            c.assertFalse(golem.getNavigation().isIdle(), "Guard navigates back toward its saved anchor");
            golem.damage(c.getWorld(), golem.getDamageSources().genericKill(), 10000);
            c.assertEquals(s.count(p), 0, "Helper death removes ownership record and frees slot");
          } finally { cleanup(s, p); }
          c.complete();
        });
    }
    @GameTest public void helperCorruptRosterPreservesOriginal(TestContext c) {
        try {
            var file = Files.createTempDirectory("infinity-agent-test-").resolve("state.json"); Files.writeString(file, "{broken-json");
            boolean failed = false; try { AgentCompanions.read(file); } catch (IllegalStateException expected) { failed = true; }
            c.assertTrue(failed, "Corrupt roster fails visibly rather than losing ownership");
            c.assertEquals(Files.readString(file), "{broken-json", "Original data retained for recovery");
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        c.complete();
    }
}
