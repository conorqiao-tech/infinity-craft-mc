package dev.convergence;

import dev.convergence.mixin.AgentGoalAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.command.permission.PermissionPredicate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.test.TestContext;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import net.minecraft.world.rule.GameRules;

public class AgentGameTests {
    private void acknowledgeLoadedPlayer(ServerPlayerEntity player) {
        // Embedded clients do not acknowledge world transfers or respawn packets themselves.
        player.onTeleportationDone();
        player.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
    }
    private ServerPlayerEntity respawnLoadedPlayer(ServerPlayerEntity player) {
        // The actual packet handler also rebinds networkHandler.player to the new life.
        // Calling PlayerManager.respawnPlayer alone omits that essential client-session step.
        var handler = player.networkHandler;
        handler.onClientStatus(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
            net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.PERFORM_RESPAWN));
        if (handler.player == player) throw new IllegalStateException("The mock client did not respawn its dead player");
        acknowledgeLoadedPlayer(handler.player);
        return handler.player;
    }
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
        s.ceasefire(p);
        var names = s.data.agents.values().stream().filter(a -> a.owner().equals(p.getUuidAsString())).map(AgentCompanions.Agent::name).toList();
        for (String name : names) s.dismiss(p, name);
        s.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
        s.server.getPlayerManager().remove(p);
    }
    private void removeDirectory(Path dir) throws java.io.IOException {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    static final class ActionClock extends Clock {
        Instant now = Instant.parse("2026-09-29T12:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long millis) { now = now.plusMillis(millis); }
    }
    @GameTest public void helpersRequirePermissionLevelFour(TestContext c) {
        var root = c.getWorld().getServer().getCommandManager().getDispatcher().getRoot().getChild("agent");
        var source = c.getWorld().getServer().getCommandSource();
        c.assertFalse(root.canUse(source.withPermissions(PermissionPredicate.NONE)), "Ordinary players cannot control helpers");
        c.assertFalse(root.canUse(source.withPermissions(LeveledPermissionPredicate.GAMEMASTERS)), "OP2 cannot control helpers");
        c.assertFalse(root.canUse(source.withPermissions(LeveledPermissionPredicate.ADMINS)), "OP3 cannot control helpers");
        c.assertTrue(root.canUse(source.withPermissions(LeveledPermissionPredicate.OWNERS)), "OP4 controls helpers");
        for (String command : new String[]{"help", "spawn", "follow", "guard", "stay", "dismiss", "list", "profile", "status", "squad", "recall"}) c.assertTrue(root.getChild(command) != null, "Command exists: " + command);
        c.complete();
    }
    @GameTest public void manualRecallCrossesModesPreservingIdentityStatsAndResetHistory(TestContext c) {
        var owner = player(c, "recall-owner");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        var destination = GameModes.world(helpers.server, GameModes.Mode.HARDCORE);
        try {
            var helper = golem(helpers, owner, "traveler");
            var id = helper.getUuid();
            helpers.profile(owner, "traveler", AgentCompanions.Profile.ULTIMATE_FINALS);
            helpers.mode(owner, "traveler", AgentCompanions.Mode.STAY);
            var modifier = Identifier.of("infinity_test", "recall_modifier");
            helper.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE).setBaseValue(19);
            helper.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE).addPersistentModifier(
                new EntityAttributeModifier(modifier, 5, EntityAttributeModifier.Operation.ADD_VALUE));
            c.assertTrue(AdminStats.set(owner.getCommandSource(), helper, "attack_damage", 45).success(), "Set real admin damage before transfer");
            c.assertTrue(AdminStats.set(owner.getCommandSource(), helper, "max_health", 320).success(), "Set real admin capacity before transfer");
            c.assertTrue(AdminStats.set(owner.getCommandSource(), helper, "health", 280).success(), "Set real health before transfer");
            var feet = owner.getBlockPos();
            for (var at : BlockPos.iterate(feet.add(-8, -1, -8), feet.add(8, 6, 8)))
                destination.setBlockState(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
            var target = new TeleportTarget(destination, owner.getEntityPos(), Vec3d.ZERO, 0, 0, TeleportTarget.NO_OP);
            c.assertTrue(helper.teleportTo(target) == null, "A normal helper portal cannot cross isolated modes");
            c.assertFalse(AgentCompanions.allowRecallTeleport(helper, target), "No permit exists before an explicit recall");
            c.assertTrue(owner.teleportTo(target) == owner, "Actual OP4 owner moves to another mode");
            acknowledgeLoadedPlayer(owner);
            helpers.recall(owner, "traveler");
            var moved = helpers.loaded.get(id);
            c.assertTrue(moved != null && moved != helper && moved.getEntityWorld() == destination, "Native inter-mode transfer rebinds the destination entity");
            c.assertTrue(c.getWorld().getEntity(id) == null && destination.getEntity(id) == moved, "There is exactly one live world entity with the same UUID");
            c.assertEquals(moved.getHealth(), 280f, "Current health survives recall");
            c.assertEquals(moved.getMaxHealth(), 320f, "Edited capacity survives recall");
            c.assertEquals(moved.getAttributeValue(EntityAttributes.ATTACK_DAMAGE), 50d, "Edited base and independent modifier survive recall");
            c.assertEquals(AdminStats.original(moved, AdminStats.find(moved, "attack_damage")), 19d, "Original admin reset value survives native transfer");
            var record = helpers.data.agents.get(id.toString());
            c.assertEquals(record.profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Recall preserves profile");
            c.assertEquals(record.mode(), AgentCompanions.Mode.FOLLOW, "Explicit recall resumes following");
            c.assertEquals(record.dimension(), destination.getRegistryKey().getValue().toString(), "Anchor dimension follows the actual destination");
            c.assertEquals(record.anchor(), moved.getEntityPos(), "Saved anchor uses the checked arrival point");
            helpers.unload(helper);
            c.assertTrue(helpers.loaded.get(id) == moved, "A late old-world unload cannot erase the destination instance");
            c.assertFalse(AgentCompanions.allowRecallTeleport(moved, target), "Permit is cleared after synchronous transfer");
            var back = new TeleportTarget(c.getWorld(), Vec3d.ofBottomCenter(feet), Vec3d.ZERO, 0, 0, TeleportTarget.NO_OP);
            c.assertTrue(moved.teleportTo(back) == null, "Later generic portals remain blocked");
        } finally { cleanup(helpers, owner); }
        c.complete();
    }
    @GameTest public void recallRefusesForeignRevokedDeadUnloadedAndUnsafeScaledHelpers(TestContext c) throws Exception {
        var owner = player(c, "recall-checks");
        var other = player(c, "recall-other");
        var helpers = AgentCompanions.get(c.getWorld().getServer());
        IronGolemEntity helper = null;
        var passenger = EntityType.PIG.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            helper = golem(helpers, owner, "checked");
            helpers.mode(owner, "checked", AgentCompanions.Mode.STAY);
            var start = helper.getEntityPos();
            helpers.recall(other, "checked");
            c.assertEquals(helper.getEntityPos(), start, "Even another OP4 cannot recall a foreign helper");
            OperatorGameTests.level(owner, LeveledPermissionPredicate.ADMINS);
            helpers.server.getCommandManager().getDispatcher().execute("agent recall checked", helpers.server.getCommandSource().withEntity(owner));
            c.assertEquals(helper.getEntityPos(), start, "Console execute-as cannot bypass the owner's actual OP level");
            OperatorGameTests.level(owner, LeveledPermissionPredicate.OWNERS);
            owner.changeGameMode(GameMode.SPECTATOR);
            helpers.recall(owner, "checked");
            c.assertEquals(helper.getEntityPos(), start, "Spectator owner cannot recall");
            owner.changeGameMode(GameMode.SURVIVAL);
            owner.setHealth(0);
            helpers.recall(owner, "checked");
            c.assertEquals(helper.getEntityPos(), start, "Dead owner cannot recall");
            owner.setHealth(20);
            helpers.loaded.remove(helper.getUuid());
            helpers.recall(owner, "checked");
            c.assertEquals(helper.getEntityPos(), start, "Unloaded identity is never recreated or searched into loaded chunks");
            c.assertTrue(!helpers.loaded.containsKey(helper.getUuid()), "Unloaded refusal leaves the loaded registry unchanged");
            helpers.loaded.put(helper.getUuid(), helper);
            passenger.setPosition(helper.getEntityPos());
            c.getWorld().spawnEntity(passenger);
            c.assertTrue(passenger.startRiding(helper, true, false), "A real mounted passenger exercises native transfer containment");
            helpers.recall(owner, "checked");
            c.assertEquals(helper.getEntityPos(), start, "Recall refuses to transfer a helper with passengers");
            c.assertTrue(passenger.getVehicle() == helper, "Refusal leaves unrelated passenger state intact");
            passenger.stopRiding(); passenger.discard();
            helper.getAttributeInstance(EntityAttributes.SCALE).setBaseValue(3);
            helpers.recall(owner, "checked");
            c.assertFalse(helper.getDimensions(helper.getPose()).getBoxAt(helper.getEntityPos()).intersects(owner.getBoundingBox()), "Safe placement includes the owner using the latest effective scale before the next entity tick");
            c.assertEquals(helpers.owned(owner, "checked").getValue().mode(), AgentCompanions.Mode.FOLLOW, "An enlarged helper can be recalled where its full body fits");
            helpers.mode(owner, "checked", AgentCompanions.Mode.STAY);
            start = helper.getEntityPos();
            helper.getAttributeInstance(EntityAttributes.SCALE).setBaseValue(2);
            var feet = owner.getBlockPos();
            for (var at : BlockPos.iterate(feet.add(-7, 3, -7), feet.add(7, 3, 7)))
                c.getWorld().setBlockState(at, Blocks.STONE.getDefaultState());
            helpers.recall(owner, "checked");
            c.assertEquals(helper.getEntityPos(), start, "Recall checks the edited scale's actual body against a low ceiling");
            c.assertEquals(helpers.owned(owner, "checked").getValue().mode(), AgentCompanions.Mode.STAY, "Failed recalls do not change movement or roster identity");
            c.assertFalse(AgentCompanions.allowRecallTeleport(helper,
                new TeleportTarget(GameModes.world(helpers.server, GameModes.Mode.HARDCORE), start, Vec3d.ZERO, 0, 0, TeleportTarget.NO_OP)),
                "Rejected requests leave no reusable cross-mode permit");
        } finally {
            passenger.discard();
            if (helper != null && !helper.isRemoved()) helpers.loaded.put(helper.getUuid(), helper);
            OperatorGameTests.level(owner, LeveledPermissionPredicate.OWNERS);
            cleanup(helpers, owner); cleanup(helpers, other);
        }
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
            for (String name : new String[]{"two", "three", "four", "five", "six"}) golem(s, p, name);
            s.loaded.remove(golem.getUuid()); s.spawn(p, "seven");
            c.assertEquals(s.count(p), AgentCompanions.LIMIT, "Unloaded helper still consumes a slot");
            c.assertTrue(s.owned(p, "seven") == null, "Seventh helper rejected");
            s.dismiss(p, "one"); s.load(golem); c.assertTrue(golem.isRemoved(), "Dismissed unloaded entity removed on next load");
            c.assertEquals(s.count(p), AgentCompanions.LIMIT - 1, "Dismissal frees exactly one slot");
        } finally { cleanup(s, p); cleanup(s, other); }
        c.complete();
    }
    @GameTest public void helpersPersistRosterVanillaTagsAndModes(TestContext c) {
        var p = player(c, "helper-save"); var s = AgentCompanions.get(c.getWorld().getServer());
        try {
            var golem = golem(s, p, "keeper"); s.profile(p, "keeper", AgentCompanions.Profile.ULTIMATE_FINALS); s.mode(p, "keeper", AgentCompanions.Mode.GUARD);
            var record = s.owned(p, "keeper");
            c.assertEquals(AgentCompanions.read(s.file).agents.get(record.getKey()).mode(), AgentCompanions.Mode.GUARD, "Guard mode and ownership survive roster reload");
            c.assertEquals(AgentCompanions.read(s.file).agents.get(record.getKey()).profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Profile survives roster reload");
            var write = NbtWriteView.create(ErrorReporter.EMPTY, c.getWorld().getRegistryManager()); golem.writeData(write);
            var restored = EntityType.IRON_GOLEM.create(c.getWorld(), SpawnReason.LOAD);
            restored.readData(NbtReadView.create(ErrorReporter.EMPTY, c.getWorld().getRegistryManager(), write.getNbt()));
            c.assertTrue(restored.getCommandTags().contains(AgentCompanions.TAG), "Vanilla Tags preserve identity after entity NBT roundtrip");
            c.assertTrue(restored.getCommandTags().contains("infinity_owner_" + p.getUuidAsString()), "Owner is identifiable in vanilla NBT");
            c.assertTrue(restored.getCommandTags().contains("infinity_mode_guard"), "Mode is identifiable in vanilla NBT");
            c.assertTrue(restored.getCommandTags().contains("infinity_profile_ultimate_finals"), "Profile is identifiable in vanilla NBT");
            golem.discard(); s.load(restored);
            c.assertTrue(restored.isPersistent(), "Loaded helper does not despawn");
            c.assertTrue(((AgentGoalAccess) restored).infinity$getTargetSelector().getGoals().isEmpty(), "Reload removes vanilla revenge and village targeting");
            c.assertTrue(s.loaded.get(restored.getUuid()) == restored, "Reload reconnects helper to its roster UUID");
        } finally { cleanup(s, p); }
        c.complete();
    }
    @GameTest(maxTicks = 60) public void helpersActuallyFollowUsingNavigation(TestContext c) {
        var p = player(c, "helper-follow"); var s = AgentCompanions.get(c.getWorld().getServer());
        // The arena extends outside the tiny GameTest structure's ticketed chunk.
        // Embedded clients do not provide a real player's simulation tickets there.
        // Ticket the complete platform (including the golem's starting chunk) so
        // the assertion measures native navigation rather than chunk scheduling.
        var forced = new ArrayList<net.minecraft.util.math.ChunkPos>();
        for (int x = (p.getBlockX() - 7) >> 4; x <= (p.getBlockX() + 7) >> 4; x++)
            for (int z = (p.getBlockZ() - 7) >> 4; z <= (p.getBlockZ() + 7) >> 4; z++)
                if (c.getWorld().setChunkForced(x, z, true)) forced.add(new net.minecraft.util.math.ChunkPos(x, z));
        Runnable finish = () -> {
            try { cleanup(s, p); }
            finally { for (var chunk : forced) c.getWorld().setChunkForced(chunk.x, chunk.z, false); }
        };
        final IronGolemEntity golem;
        try {
            golem = golem(s, p, "walker");
            golem.setPosition(p.getX() - 5, p.getY(), p.getZ());
        } catch (RuntimeException | Error failure) { finish.run(); throw failure; }
        double start = golem.squaredDistanceTo(p);
        boolean[] started={false};
        c.runAtEveryTick(() -> {
            if(started[0] || !golem.isOnGround())return;
            try {
                s.control(golem, s.owned(p, "walker").getValue(), s.server.getTicks());
                c.assertFalse(golem.getNavigation().isIdle(), "Follow starts real vanilla path navigation after landing");
                started[0]=true;
            } catch (RuntimeException | Error failure) { finish.run(); throw failure; }
        });
        c.runAtTick(45, () -> {
            try {
                c.assertTrue(started[0], "Helper landed and started vanilla path navigation");
                c.assertTrue(golem.squaredDistanceTo(p) < start - 1, "Helper walked toward the owner through vanilla navigation");
            }
            finally { finish.run(); }
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
    @GameTest public void followingHelpersPrioritizeThreatsToOwner(TestContext c) {
        var p = player(c, "helper-protect"); var s = AgentCompanions.get(c.getWorld().getServer());
        var nearby = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        var threat = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "protector");
            golem.setPosition(p.getEntityPos().add(1, 0, 0));
            nearby.setPosition(p.getEntityPos().add(3, 0, 0)); nearby.setAiDisabled(true); c.getWorld().spawnEntity(nearby);
            threat.setPosition(p.getEntityPos().add(6, 0, 0)); threat.setAiDisabled(true); c.getWorld().spawnEntity(threat);
            threat.setTarget(p);
            s.control(golem, s.owned(p, "protector").getValue(), 20);
            c.assertTrue(golem.getTarget() == threat, "Follow protects its owner before pursuing a closer bystander");
            s.mode(p, "protector", AgentCompanions.Mode.GUARD);
            s.control(golem, s.owned(p, "protector").getValue(), 25);
            c.assertTrue(golem.getTarget() == nearby, "Guard keeps choosing the nearest hostile near its anchor");
        } finally { nearby.discard(); threat.discard(); cleanup(s, p); }
        c.complete();
    }
    @GameTest(maxTicks = 60) public void helpersPauseForLogoutDeathAndDifferentDimensions(TestContext c) {
        var p = player(c, "helper-lifecycle"); var s = AgentCompanions.get(c.getWorld().getServer());
        var golem = golem(s, p, "patient");
        golem.setPosition(p.getX() - 5, p.getY(), p.getZ());
        boolean[] checked={false};
        c.runAtEveryTick(() -> {
          if(checked[0])return;
          checked[0]=true;
          // Mock clients can leave the spawned golem mid-fall for the whole test.
          // The behavior under test is helper control on a known solid floor.
          golem.setOnGround(true);
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
    @GameTest public void helperRosterMigratesAndRejectsInvalidProfiles(TestContext c) throws Exception {
        Path dir = Files.createTempDirectory("infinity-helper-migration-");
        try {
            Path file = dir.resolve("roster.json"); var data = new AgentCompanions.Data(); data.format = 1;
            String id = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString();
            data.agents.put(id, new AgentCompanions.Agent(owner, "legacy", AgentCompanions.Mode.GUARD, "minecraft:overworld", 0, 64, 0));
            var json = com.google.gson.JsonParser.parseString(CommunityServer.GSON.toJson(data)).getAsJsonObject();
            json.getAsJsonObject("agents").getAsJsonObject(id).remove("profile");
            String original = json.toString(); Files.writeString(file, original);
            var migrated = AgentCompanions.read(file);
            c.assertEquals(migrated.format, 2, "Legacy schema upgrades in memory");
            c.assertEquals(migrated.agents.get(id).profile(), AgentCompanions.Profile.REGULAR, "Legacy helpers keep their regular combat behavior");
            c.assertEquals(migrated.agents.get(id).mode(), AgentCompanions.Mode.GUARD, "Migration retains guard movement");
            c.assertEquals(Files.readString(file), original, "A read does not overwrite a legacy roster");
            json.addProperty("format", 2);
            for (String profile : new String[]{"UNKNOWN", "regular"}) {
                json.getAsJsonObject("agents").getAsJsonObject(id).addProperty("profile", profile);
                Files.writeString(file, json.toString()); boolean rejected = false;
                try { AgentCompanions.read(file); } catch (IllegalStateException expected) { rejected = true; }
                c.assertTrue(rejected, "Unknown and noncanonical persisted profiles fail visibly: " + profile);
            }
            json.getAsJsonObject("agents").getAsJsonObject(id).remove("profile"); Files.writeString(file, json.toString());
            boolean missingRejected = false; try { AgentCompanions.read(file); } catch (IllegalStateException expected) { missingRejected = true; }
            c.assertTrue(missingRejected, "Format 2 must provide each helper profile");
            c.assertEquals(AgentCompanions.Profile.parse("ultimate-finals"), AgentCompanions.Profile.ULTIMATE_FINALS, "Command profile names accept the documented hyphen alias");
        } finally { removeDirectory(dir); }
        c.complete();
    }
    @GameTest public void helperRosterBoundsFileSizeAndGlobalCount(TestContext c) throws Exception {
        Path dir = Files.createTempDirectory("infinity-helper-bounds-");
        try {
            Path file = dir.resolve("roster.json"); String oversized = " ".repeat(AgentCompanions.MAX_BYTES + 1); Files.writeString(file, oversized);
            boolean rejected = false; try { AgentCompanions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Oversized data rejected before parsing");
            c.assertEquals(Files.size(file), (long) AgentCompanions.MAX_BYTES + 1, "Oversized original remains intact");
            var data = new AgentCompanions.Data(); String owner = UUID.randomUUID().toString();
            for (int index = 0; index <= AgentCompanions.GLOBAL_LIMIT; index++) {
                if (index % AgentCompanions.LIMIT == 0) owner = UUID.randomUUID().toString();
                data.agents.put(UUID.randomUUID().toString(), new AgentCompanions.Agent(owner, "helper-" + index, AgentCompanions.Mode.STAY, "minecraft:overworld", 0, 64, 0));
            }
            Files.writeString(file, CommunityServer.GSON.toJson(data)); rejected = false;
            try { AgentCompanions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Roster rejects a 25th helper even when individual owners remain within six");
        } finally { removeDirectory(dir); }
        c.complete();
    }
    @GameTest public void helperGlobalLimitIncludesUnloadedOwners(TestContext c) throws Exception {
        var p = player(c, "helper-global"); Path dir = Files.createTempDirectory("infinity-helper-global-");
        var companions = AgentCompanions.get(c.getWorld().getServer());
        try {
            var isolated = new AgentCompanions(companions.server, dir.resolve("roster.json")); String owner = UUID.randomUUID().toString();
            for (int index = 0; index < AgentCompanions.GLOBAL_LIMIT; index++) {
                if (index % AgentCompanions.LIMIT == 0) owner = UUID.randomUUID().toString();
                isolated.data.agents.put(UUID.randomUUID().toString(), new AgentCompanions.Agent(owner, "helper-" + index, AgentCompanions.Mode.STAY, "minecraft:overworld", 0, 64, 0));
            }
            isolated.save(); isolated.spawn(p, "overflow");
            c.assertEquals(isolated.count(p), 0, "New owner cannot bypass global cap");
            c.assertEquals(isolated.data.agents.size(), AgentCompanions.GLOBAL_LIMIT, "Unloaded helpers count toward global limit");
            c.assertTrue(isolated.loaded.isEmpty(), "Rejected creation loads no entities or chunks");
        } finally { cleanup(companions, p); removeDirectory(dir); }
        c.complete();
    }
    @GameTest public void helperMutationsRecheckOpFourInsideMethods(TestContext c) {
        var p = player(c, "helper-revoke"); var s = AgentCompanions.get(c.getWorld().getServer());
        try {
            golem(s, p, "locked");
            s.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
            s.spawn(p, "extra"); s.mode(p, "locked", AgentCompanions.Mode.STAY); s.profile(p, "locked", AgentCompanions.Profile.DEBUG);
            s.squad(p, AgentCompanions.Mode.GUARD); s.dismiss(p, "locked");
            c.assertEquals(s.count(p), 1, "Permission loss blocks spawn and dismissal even without command dispatcher");
            c.assertEquals(s.owned(p, "locked").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Permission loss blocks direct movement and squad changes");
            c.assertEquals(s.owned(p, "locked").getValue().profile(), AgentCompanions.Profile.REGULAR, "Permission loss blocks direct profile changes");
        } finally {
            s.server.getPlayerManager().addToOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()), java.util.Optional.of(LeveledPermissionPredicate.OWNERS), java.util.Optional.of(false));
            cleanup(s, p);
        }
        c.complete();
    }
    @GameTest public void helperSquadChangesOnlyLoadedSameDimensionOwnedHelpers(TestContext c) {
        var p = player(c, "helper-squad"); var other = player(c, "helper-stranger"); var s = AgentCompanions.get(c.getWorld().getServer());
        try {
            var first = golem(s, p, "first"); var second = golem(s, p, "second"); var unloaded = golem(s, p, "unloaded");
            var stranger = golem(s, other, "stranger"); s.loaded.remove(unloaded.getUuid());
            s.squad(p, AgentCompanions.Mode.GUARD);
            c.assertEquals(s.owned(p, "first").getValue().mode(), AgentCompanions.Mode.GUARD, "First loaded squad member guards");
            c.assertEquals(s.owned(p, "second").getValue().mode(), AgentCompanions.Mode.GUARD, "Second loaded squad member guards");
            c.assertEquals(s.owned(p, "unloaded").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Squad command does not modify unloaded helpers");
            c.assertEquals(s.owned(other, "stranger").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Squad cannot control another OP's helper");
            var prior = AgentCompanions.read(s.file.resolveSibling(s.file.getFileName() + ".previous"));
            c.assertEquals(prior.agents.get(first.getUuidAsString()).mode(), AgentCompanions.Mode.FOLLOW, "One squad save preserves the previous state of the first helper");
            c.assertEquals(prior.agents.get(second.getUuidAsString()).mode(), AgentCompanions.Mode.FOLLOW, "One squad save preserves the previous state of the whole squad");
            var position = p.getEntityPos(); p.teleport(s.server.getWorld(World.NETHER), 0, 100, 0, Set.of(), 0, 0, true);
            s.squad(p, AgentCompanions.Mode.STAY);
            c.assertEquals(s.owned(p, "first").getValue().mode(), AgentCompanions.Mode.GUARD, "Squad skips helpers in another dimension");
            p.teleport(c.getWorld(), position.x, position.y, position.z, Set.of(), 0, 0, true);
            s.dismiss(p, "unloaded"); s.load(unloaded); c.assertTrue(unloaded.isRemoved(), "Skipped unloaded helper remains dismissible");
        } finally { cleanup(s, p); cleanup(s, other); }
        c.complete();
    }
    @GameTest public void passiveProfilesDisableEvenForcedHostileDamage(TestContext c) {
        var p = player(c, "helper-passive"); var s = AgentCompanions.get(c.getWorld().getServer());
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "observer"); zombie.setPosition(golem.getEntityPos().add(1, 0, 0)); zombie.setAiDisabled(true); c.getWorld().spawnEntity(zombie);
            float health = zombie.getHealth();
            for (var profile : new AgentCompanions.Profile[]{AgentCompanions.Profile.DEBUG, AgentCompanions.Profile.CLI, AgentCompanions.Profile.API}) {
                s.profile(p, "observer", profile); s.control(golem, s.owned(p, "observer").getValue(), 20);
                c.assertTrue(golem.getTarget() == null, "Passive profile chooses no hostile target: " + profile);
                golem.setTarget(zombie); c.assertFalse(golem.tryAttack(c.getWorld(), zombie), "Independent damage gate blocks forced attacks for " + profile);
                c.assertEquals(zombie.getHealth(), health, "Passive forced attack causes no damage");
            }
            String saved = CommunityServer.GSON.toJson(s.data); var tags = Set.copyOf(golem.getCommandTags());
            s.status(p, "observer"); c.assertEquals(CommunityServer.GSON.toJson(s.data), saved, "Status diagnostics cannot change saved helper state");
            c.assertEquals(golem.getCommandTags(), tags, "Status diagnostics do not mutate tags");
        } finally { zombie.discard(); cleanup(s, p); }
        c.complete();
    }
    @GameTest public void primitiveProfileIsReadyToAttackAtLongerSensingRange(TestContext c) {
        var p = player(c, "helper-primitive"); var s = AgentCompanions.get(c.getWorld().getServer());
        var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "fighter"); golem.setPosition(p.getEntityPos().add(1, 0, 0));
            zombie.setPosition(p.getEntityPos().add(11, 0, 0)); zombie.setAiDisabled(true); c.getWorld().spawnEntity(zombie);
            s.control(golem, s.owned(p, "fighter").getValue(), 20); c.assertTrue(golem.getTarget() == null, "Regular senses hostiles within ten blocks");
            s.profile(p, "fighter", AgentCompanions.Profile.PRIMITIVE); s.control(golem, s.owned(p, "fighter").getValue(), 25);
            c.assertTrue(golem.getTarget() == zombie, "Primitive proactively engages a hostile eleven blocks away");
            s.mode(p, "fighter", AgentCompanions.Mode.STAY); s.control(golem, s.owned(p, "fighter").getValue(), 30);
            c.assertTrue(golem.getTarget() == null, "Stay still stops an aggressive primitive helper");
        } finally { zombie.discard(); cleanup(s, p); }
        c.complete();
    }
    @GameTest public void ultimateFinalsSquadSharesFocusAndFlanksHostiles(TestContext c) {
        var p = player(c, "helper-hive"); var s = AgentCompanions.get(c.getWorld().getServer());
        var focus = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND); var closer = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var first = golem(s, p, "alpha"); var second = golem(s, p, "beta");
            s.profile(p, "alpha", AgentCompanions.Profile.ULTIMATE_FINALS); s.profile(p, "beta", AgentCompanions.Profile.ULTIMATE_FINALS);
            first.setPosition(p.getEntityPos().add(-3, 0, 0)); second.setPosition(p.getEntityPos().add(4, 0, 0));
            focus.setPosition(p.getEntityPos().add(0, 0, 4)); focus.setAiDisabled(true); c.getWorld().spawnEntity(focus);
            closer.setPosition(p.getEntityPos().add(5, 0, 3)); closer.setAiDisabled(true); c.getWorld().spawnEntity(closer);
            s.control(first, s.owned(p, "alpha").getValue(), 20); s.control(second, s.owned(p, "beta").getValue(), 20);
            c.assertTrue(first.getTarget() == focus && second.getTarget() == focus, "Hive shares focus despite a nearer hostile for one golem");
            var left = s.flankPoint(first, s.owned(p, "alpha").getValue(), p, focus); var right = s.flankPoint(second, s.owned(p, "beta").getValue(), p, focus);
            c.assertTrue(left != null && right != null && left.squaredDistanceTo(right) > 4, "Squad receives separate clear loaded flanking positions");
            closer.setTarget(p); s.control(first, s.owned(p, "alpha").getValue(), 25); s.control(second, s.owned(p, "beta").getValue(), 25);
            c.assertTrue(first.getTarget() == closer && second.getTarget() == closer, "Owner threat overrides prior shared focus");
            c.assertFalse(AgentCompanions.allowDamage(p, p.getDamageSources().mobAttack(first)), "Aggressive hive still cannot attack a player");
        } finally { focus.discard(); closer.discard(); cleanup(s, p); }
        c.complete();
    }
    @GameTest public void approvedPlayerTargetCoordinatesAggressiveProfilesAndKeepsRegularUnchanged(TestContext c) {
        var owner = player(c, "hive-owner"); var target = player(c, "hive-target"); var bystander = player(c, "hive-bystander");
        var s = AgentCompanions.get(c.getWorld().getServer()); var zombie = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server);
            var first = golem(s, owner, "alpha"); var second = golem(s, owner, "beta"); var regular = golem(s, owner, "regular");
            first.setPosition(owner.getEntityPos().add(-2, 0, 0)); second.setPosition(owner.getEntityPos().add(2, 0, 0)); regular.setPosition(owner.getEntityPos().add(5, 0, 0));
            target.setPosition(owner.getEntityPos().add(0, 0, 5)); bystander.setPosition(owner.getEntityPos().add(0, 0, 2));
            target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            zombie.setPosition(owner.getEntityPos().add(5, 0, 3)); zombie.setAiDisabled(true); c.getWorld().spawnEntity(zombie);
            s.profile(owner, "alpha", AgentCompanions.Profile.PRIMITIVE); s.profile(owner, "beta", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(first)), "No player damage before exact reviewed assignment");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Core accepts a valid exact player target after action review");
            s.control(first, s.owned(owner, "alpha").getValue(), 20); s.control(second, s.owned(owner, "beta").getValue(), 20); s.control(regular, s.owned(owner, "regular").getValue(), 20);
            c.assertTrue(first.getTarget() == target && second.getTarget() == target, "Primitive and Ultimate Finals share the same explicitly named player");
            c.assertTrue(regular.getTarget() == zombie, "Regular keeps hostile-mob behavior during a player order");
            c.assertFalse(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(first)), "Approval does not permit remote melee damage while pursuit is still needed");
            target.setPosition(first.getEntityPos().add(1, 0, 0));
            bystander.setPosition(first.getEntityPos().add(0, 0, 1));
            c.assertTrue(first.isInAttackRange(target) && first.getVisibilityCache().canSee(target), "Approved damage fixture is now in melee range with clear sight");
            c.assertFalse(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(regular)), "Regular cannot gain player damage from a squad order");
            c.assertFalse(AgentCompanions.allowDamage(bystander, bystander.getDamageSources().mobAttack(first)), "A nearer unnamed player is never authorized");
            c.assertFalse(AgentCompanions.allowDamage(owner, owner.getDamageSources().mobAttack(first)), "Owner is never authorized");
            c.assertTrue(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(first)), "Independent gate accepts only the exact current target");
            float health = target.getHealth();
            c.assertTrue(first.tryAttack(c.getWorld(), target), "Real golem melee attack is permitted for the approved player");
            c.assertTrue(target.getHealth() < health, "Approved melee causes real player damage");
        } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); zombie.discard(); cleanup(s, owner); cleanup(s, target); cleanup(s, bystander); }
        c.complete();
    }
    @GameTest public void playerTargetHonorsWorldPvpFriendlyFireModesAndSelfProtection(TestContext c) {
        var owner = player(c, "hive-rules-owner"); var target = player(c, "hive-rules-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP); var scoreboard = s.server.getScoreboard();
        var team = scoreboard.addTeam("hive-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter");
            s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE); target.setPosition(owner.getEntityPos().add(0, 0, 5));
            c.assertFalse(s.assignPlayerTarget(owner, owner), "An owner cannot order an attack on themself");
            for (var mode : new GameMode[]{GameMode.CREATIVE, GameMode.SPECTATOR}) {
                target.changeGameMode(mode); c.assertFalse(s.assignPlayerTarget(owner, target), "Player mode protected: " + mode);
            }
            target.changeGameMode(GameMode.ADVENTURE); c.assertTrue(s.assignPlayerTarget(owner, target), "Adventure players can be explicitly targeted");
            c.getWorld().getGameRules().setValue(GameRules.PVP, false, s.server);
            c.assertFalse(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(helper)), "Disabling world PvP immediately blocks forced helper damage");
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "PvP change clears the order");
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server);
            scoreboard.addScoreHolderToTeam(owner.getNameForScoreboard(), team); scoreboard.addScoreHolderToTeam(target.getNameForScoreboard(), team); team.setFriendlyFireAllowed(false);
            c.assertFalse(s.assignPlayerTarget(owner, target), "Team friendly-fire protection is respected");
            team.setFriendlyFireAllowed(true); c.assertTrue(s.assignPlayerTarget(owner, target), "An explicit order is eligible when team PvP is allowed");
            team.setFriendlyFireAllowed(false); s.tick(); c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "Changing friendly fire clears an already active order");
            scoreboard.clearTeam(owner.getNameForScoreboard()); scoreboard.clearTeam(target.getNameForScoreboard());
            target.setHealth(0); c.assertFalse(s.assignPlayerTarget(owner, target), "A dead target is protected"); target.setHealth(target.getMaxHealth());
            owner.changeGameMode(GameMode.SPECTATOR); c.assertFalse(s.assignPlayerTarget(owner, target), "Spectator owner cannot activate an attack"); owner.changeGameMode(GameMode.SURVIVAL);
            s.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(owner.getGameProfile()));
            c.assertFalse(s.assignPlayerTarget(owner, target), "Revoked owner permissions prevent direct activation");
        } finally {
            c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); scoreboard.removeTeam(team);
            s.server.getPlayerManager().addToOperators(new net.minecraft.server.PlayerConfigEntry(owner.getGameProfile()), java.util.Optional.of(LeveledPermissionPredicate.OWNERS), java.util.Optional.of(false));
            cleanup(s, owner); cleanup(s, target);
        }
        c.complete();
    }
    @GameTest public void playerTargetExpiresWithoutPersistenceOrAutomaticResume(TestContext c) throws Exception {
        var owner = player(c, "hive-expiry-owner"); var target = player(c, "hive-expiry-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        var dir = Files.createTempDirectory("infinity-hive-expiry-"); var clock = new ActionClock(); boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPosition(owner.getEntityPos().add(1, 0, 0)); target.setPosition(owner.getEntityPos().add(0, 0, 5));
            var isolated = new AgentCompanions(s.server, dir.resolve("roster.json"), clock); var record = s.owned(owner, "fighter");
            isolated.data.agents.put(record.getKey(), record.getValue()); isolated.loaded.put(helper.getUuid(), helper); isolated.save();
            c.assertTrue(isolated.assignPlayerTarget(owner, target), "Runtime order is active before expiry");
            isolated.control(helper, record.getValue(), 20); c.assertTrue(helper.getTarget() == target, "Order selects the player before expiry");
            c.assertFalse(Files.readString(isolated.file).contains(target.getUuidAsString()), "Player attack order is not written to the roster");
            c.assertTrue(new AgentCompanions(s.server, isolated.file, clock).playerTargets.isEmpty(), "Reload cannot replay a prior player order");
            clock.advance(AgentCompanions.PLAYER_TARGET_LIFETIME_MS); isolated.control(helper, record.getValue(), 25);
            c.assertTrue(isolated.playerTargets.isEmpty() && helper.getTarget() == null, "Five-minute deadline clears target and movement");
            isolated.control(helper, record.getValue(), 30); c.assertTrue(helper.getTarget() == null, "An expired order does not resume on later ticks");
            c.assertTrue(isolated.assignPlayerTarget(owner, target), "A new reviewed activation can begin after expiry");
            clock.advance(-1); isolated.control(helper, record.getValue(), 35);
            c.assertTrue(isolated.playerTargets.isEmpty() && helper.getTarget() == null, "Clock rollback clears an order instead of extending its lifetime");
        } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); removeDirectory(dir); }
        c.complete();
    }
    @GameTest public void playerCeasefireAndStayImmediatelyClearCombatOrders(TestContext c) {
        var owner = player(c, "hive-stop-owner"); var target = player(c, "hive-stop-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.ULTIMATE_FINALS);
            helper.setPosition(owner.getEntityPos().add(1, 0, 0)); target.setPosition(owner.getEntityPos().add(0, 0, 5));
            c.assertTrue(s.assignPlayerTarget(owner, target), "Starts a reviewed target"); s.control(helper, s.owned(owner, "fighter").getValue(), 20);
            c.assertTrue(helper.getTarget() == target, "Order is selected before ceasefire"); s.ceasefire(owner);
            c.assertTrue(helper.getTarget() == null && helper.getNavigation().isIdle(), "Ceasefire immediately clears targeting and navigation");
            c.assertTrue(s.playerTargetStatus(owner).equals("Player target: none."), "Status confirms no active player order");
            helper.setTarget(target); c.assertFalse(helper.tryAttack(c.getWorld(), target), "Forced attacks cannot bypass ceasefire");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Another attack needs another reviewed activation"); s.mode(owner, "fighter", AgentCompanions.Mode.STAY);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()) && helper.getTarget() == null, "Stopping the only aggressive helper clears its order");
            s.mode(owner, "fighter", AgentCompanions.Mode.FOLLOW); s.control(helper, s.owned(owner, "fighter").getValue(), 25);
            c.assertTrue(helper.getTarget() == null, "Leaving Stay cannot revive an old order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "New explicit activation succeeds after Stay"); s.profile(owner, "fighter", AgentCompanions.Profile.API);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "Changing the only aggressive helper to a passive profile clears the order");
        } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
        c.complete();
    }
    @GameTest public void playerTargetDeathRespawnAndLogoutRequireANewOrder(TestContext c) {
        var owner = player(c, "hive-life-owner"); var target = player(c, "hive-life-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPosition(owner.getEntityPos().add(1, 0, 0)); target.setPosition(owner.getEntityPos().add(0, 0, 5));
            c.assertTrue(s.assignPlayerTarget(owner, target), "Player order begins for current life"); s.control(helper, s.owned(owner, "fighter").getValue(), 20);
            target.damage(c.getWorld(), target.getDamageSources().genericKill(), Float.MAX_VALUE);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()) && helper.getTarget() == null, "Actual target death immediately halts the whole order");
            UUID oldUuid = target.getUuid(); target = respawnLoadedPlayer(target);
            target.changeGameMode(GameMode.SURVIVAL); target.teleport(c.getWorld(), owner.getX(), owner.getY(), owner.getZ() + 5, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(target);
            c.assertEquals(target.getUuid(), oldUuid, "Respawn retains account UUID but creates a new life");
            s.control(helper, s.owned(owner, "fighter").getValue(), 25); c.assertTrue(helper.getTarget() == null, "A respawned player never inherits the previous-life order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Respawn requires a fresh explicit reviewed assignment; eligibility=" + s.targetEligibility(owner, target)
                + "; targetAlive=" + target.isAlive() + "; targetRemoved=" + target.isRemoved() + "; targetConnected=" + !target.isDisconnected());
            s.control(helper, s.owned(owner, "fighter").getValue(), 30);
            s.server.getPlayerManager().remove(target); c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()) && helper.getTarget() == null, "Target logout immediately clears the order");
        } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); if (s.server.getPlayerManager().getPlayer(target.getUuid()) == target) cleanup(s, target); }
        c.complete();
    }
    @GameTest public void playerTargetCannotResumeAfterPermissionDimensionOrLeashChanges(TestContext c) {
        var owner = player(c, "hive-range-owner"); var target = player(c, "hive-range-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPosition(owner.getEntityPos().add(1, 0, 0)); Vec3d targetPosition = owner.getEntityPos().add(0, 0, 5); target.setPosition(targetPosition);
            c.assertTrue(s.assignPlayerTarget(owner, target), "Current owner permissions allow exact activation");
            s.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(owner.getGameProfile()));
            c.assertFalse(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(helper)), "Permission loss blocks damage before the next control tick");
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "Permission loss consumes the order");
            s.server.getPlayerManager().addToOperators(new net.minecraft.server.PlayerConfigEntry(owner.getGameProfile()), java.util.Optional.of(LeveledPermissionPredicate.OWNERS), java.util.Optional.of(false));
            s.control(helper, s.owned(owner, "fighter").getValue(), 20); c.assertTrue(helper.getTarget() == null, "Restored OP4 does not revive a prior order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation before dimension change");
            target.teleport(s.server.getWorld(World.NETHER), 0, 100, 0, Set.of(), 0, 0, true);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()) && helper.getTarget() == null, "Target changing dimension immediately clears the shared order");
            target.teleport(c.getWorld(), targetPosition.x, targetPosition.y, targetPosition.z, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(target);
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation after target returns");
            Vec3d ownerPosition = owner.getEntityPos(); owner.teleport(s.server.getWorld(World.NETHER), 0, 100, 0, Set.of(), 0, 0, true);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "Owner dimension change clears the order"); owner.teleport(c.getWorld(), ownerPosition.x, ownerPosition.y, ownerPosition.z, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(owner);
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation before target moves beyond the owner leash"); target.setPosition(owner.getEntityPos().add(49, 0, 0)); s.tick();
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "Owner-target separation beyond 48 blocks clears the order"); target.setPosition(targetPosition);
            s.control(helper, s.owned(owner, "fighter").getValue(), 25); c.assertTrue(helper.getTarget() == null, "A target returning within range cannot revive a consumed order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation before owner death"); owner.damage(c.getWorld(), owner.getDamageSources().genericKill(), Float.MAX_VALUE);
            c.assertFalse(owner.isAlive(), "The loaded owner actually died from server damage");
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "Actual owner death immediately clears the order");
            owner = respawnLoadedPlayer(owner);
            owner.changeGameMode(GameMode.SURVIVAL); owner.teleport(c.getWorld(), ownerPosition.x, ownerPosition.y, ownerPosition.z, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(owner);
            s.control(helper, s.owned(owner, "fighter").getValue(), 30); c.assertTrue(helper.getTarget() == null, "Respawned owner cannot restore the previous-life combat order");
        } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
        c.complete();
    }
    @GameTest public void playerOrdersKeepPursuitBeyondSwingRangeAndGuardsWaitInsideTheirLeash(TestContext c) {
        var owner = player(c, "hive-pursuit-owner"); var target = player(c, "hive-pursuit-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
        try {
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPosition(owner.getEntityPos().add(1, 0, 0)); target.setPosition(owner.getEntityPos().add(0, 0, 5));
            c.assertTrue(s.assignPlayerTarget(owner, target), "Reviewed pursuit begins nearby");
            target.setPosition(owner.getEntityPos().add(0, 0, 30)); s.control(helper, s.owned(owner, "fighter").getValue(), 20);
            c.assertTrue(s.targetEligibility(owner, target) == null, "A pending proposal stays eligible beyond the helper's 24-block damage range");
            c.assertTrue(s.validPlayerTarget(owner) != null && helper.getTarget() == target, "An active follow order keeps pursuing within the owner's 48-block range");
            c.assertFalse(AgentCompanions.allowDamage(target, target.getDamageSources().mobAttack(helper)), "Pursuit never permits remote damage beyond 24 blocks");
            float health = target.getHealth(); c.assertFalse(helper.tryAttack(c.getWorld(), target), "A forced ranged swing fails the independent damage gate");
            c.assertEquals(target.getHealth(), health, "Distant pursuit causes no remote damage");
            s.mode(owner, "fighter", AgentCompanions.Mode.GUARD); s.control(helper, s.owned(owner, "fighter").getValue(), 25);
            c.assertTrue(s.validPlayerTarget(owner) != null && helper.getTarget() == null, "Guard retains its order while waiting for the approved player outside its 14-block anchor range");
            c.assertTrue(helper.getNavigation().isIdle(), "A guard at its anchor does not chase beyond its guard leash");
            target.setPosition(helper.getEntityPos().add(0, 0, 5)); s.control(helper, s.owned(owner, "fighter").getValue(), 30);
            c.assertTrue(helper.getTarget() == target, "The same still-valid guard order resumes when the approved player returns inside the guard range");
            helper.setPosition(helper.getEntityPos().add(15, 0, 0)); s.tick();
            c.assertTrue(!s.playerTargets.containsKey(owner.getUuid()), "An order cancels when its only compatible helper leaves its own movement leash");
        } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
        c.complete();
    }
    @GameTest(maxTicks = 80) public void approvedPlayerPursuitNavigatesAroundWallsButCannotStrikeThroughThem(TestContext c) {
        var owner = player(c, "hive-corner-owner"); var target = player(c, "hive-corner-target"); var s = AgentCompanions.get(c.getWorld().getServer());
        boolean pvp = c.getWorld().getGameRules().getValue(GameRules.PVP); Vec3d center = owner.getEntityPos();
        owner.setPosition(center.add(0, 0, -5)); target.setPosition(center.add(3, 0, 0));
        target.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
        c.getWorld().getGameRules().setValue(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
        helper.setPosition(center.add(-3, 0, 0)); helper.setOnGround(true);
        BlockPos wall = BlockPos.ofFloored(center);
        for (BlockPos at : BlockPos.iterate(wall.add(0, 0, -1), wall.add(0, 3, 1))) c.getWorld().setBlockState(at, Blocks.STONE.getDefaultState());
        double initialDistance = helper.squaredDistanceTo(target);
        try {
            c.assertFalse(helper.getVisibilityCache().canSee(target), "A solid wall actually blocks the golem's initial line of sight");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Exact reviewed identity authorizes pursuit around an obstruction");
            s.control(helper, s.owned(owner, "fighter").getValue(), s.server.getTicks());
            c.assertTrue(helper.getTarget() == target && !helper.getNavigation().isIdle(), "Approved pursuit retains its target and starts real navigation without line of sight");
            float health = target.getHealth(); c.assertFalse(helper.tryAttack(c.getWorld(), target), "The independent damage gate rejects a forced swing through the wall");
            c.assertEquals(target.getHealth(), health, "Occluded pursuit cannot damage the player through terrain");
        } catch (RuntimeException failure) {
            c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); throw failure;
        }
        java.util.function.Supplier<String> pursuitState = () -> "eligibility=" + s.targetEligibility(owner, target)
            + "; pvp=" + c.getWorld().getGameRules().getValue(GameRules.PVP)
            + "; owner=" + owner.getEntityPos() + "/" + owner.getHealth() + "/" + owner.getGameMode()
            + "; target=" + target.getEntityPos() + "/" + target.getHealth() + "/" + target.getGameMode()
            + "; helper=" + helper.getEntityPos() + "/" + helper.getHealth() + "/removed=" + helper.isRemoved()
            + "; loaded=" + (s.loaded.get(helper.getUuid()) == helper)
            + "; pause=" + (s.data.agents.containsKey(helper.getUuidAsString()) ? s.pauseReason(owner, helper, s.data.agents.get(helper.getUuidAsString())) : "missing record");
        String[] firstLost = {null};
        for (int tick = 1; tick < 65; tick++) {
            final int at = tick;
            c.runAtTick(tick, () -> {if(firstLost[0] == null && !s.playerTargets.containsKey(owner.getUuid()))firstLost[0] = "tick=" + at + "; " + pursuitState.get();});
        }
        c.runAtTick(65, () -> {
            try {
                c.assertTrue(s.validPlayerTarget(owner) != null, "Normal navigation around a wall does not consume the approved order; first lost: " + firstLost[0] + "; final: " + pursuitState.get());
                c.assertTrue(helper.squaredDistanceTo(target) < initialDistance - 1, "Server ticks actually move the golem around the obstruction toward the approved player");
                c.assertTrue(helper.getVisibilityCache().canSee(target), "The golem reaches a clear sight line after navigating around the corner");
            } finally { c.getWorld().getGameRules().setValue(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
            c.complete();
        });
    }
    @GameTest public void flankSlotsOnlyCountNearbyPeersEngagingTheSameTarget(TestContext c) {
        var owner = player(c, "hive-flank-owner"); var s = AgentCompanions.get(c.getWorld().getServer());
        var focus = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND); var alternative = EntityType.ZOMBIE.create(c.getWorld(), SpawnReason.COMMAND);
        try {
            var first = golem(s, owner, "alpha"); var second = golem(s, owner, "beta"); var distant = golem(s, owner, "distant"); var otherFight = golem(s, owner, "other");
            for (String name : new String[]{"alpha", "beta", "distant", "other"}) s.profile(owner, name, AgentCompanions.Profile.ULTIMATE_FINALS);
            first.setPosition(owner.getEntityPos().add(-3, 0, 0)); second.setPosition(owner.getEntityPos().add(3, 0, 0));
            distant.setPosition(owner.getEntityPos().add(30, 0, 0)); distant.setNoGravity(true); s.mode(owner, "distant", AgentCompanions.Mode.GUARD);
            otherFight.setPosition(owner.getEntityPos().add(4, 0, 5));
            focus.setPosition(owner.getEntityPos().add(0, 0, 4)); focus.setAiDisabled(true); c.getWorld().spawnEntity(focus);
            alternative.setPosition(owner.getEntityPos().add(5, 0, 5)); alternative.setAiDisabled(true); c.getWorld().spawnEntity(alternative);
            first.setTarget(focus); second.setTarget(focus); distant.setTarget(focus); otherFight.setTarget(alternative);
            var left = s.flankPoint(first, s.owned(owner, "alpha").getValue(), owner, focus); var right = s.flankPoint(second, s.owned(owner, "beta").getValue(), owner, focus);
            c.assertTrue(left != null && right != null, "The two actual combat peers have clear flanking positions");
            c.assertTrue(left.add(right).multiply(.5).squaredDistanceTo(focus.getEntityPos()) < .000001, "Two engaged peers receive opposite angles despite distant guards and another fight");
            second.setTarget(alternative);
            c.assertTrue(s.flankPoint(first, s.owned(owner, "alpha").getValue(), owner, focus) == null, "A lone engaged helper uses direct pursuit rather than a slot reserved for absent combat peers");
        } finally { focus.discard(); alternative.discard(); cleanup(s, owner); }
        c.complete();
    }
    @GameTest public void helperActionsRequireBothLiveApprovalsAndNeverReplay(TestContext c) throws Exception {
        var p = player(c, "action-owner"); var companions = AgentCompanions.get(c.getWorld().getServer());
        Path dir = Files.createTempDirectory("infinity-action-test-");
        var dispatched = new ArrayList<String>(); var clock = new ActionClock();
        try {
            var queue = new AgentActions(companions.server, dir.resolve("actions.json"), clock, dispatched::add);
            queue.suggest(p, "make it day");
            String id = queue.data.proposals.keySet().iterator().next();
            c.assertTrue(dispatched.isEmpty(), "Proposing cannot dispatch a command");
            queue.codexApprove(companions.server.getCommandSource(), id);
            c.assertTrue(dispatched.isEmpty(), "Console review alone cannot dispatch without owner approval");
            queue.ownerApprove(p, id);
            c.assertTrue(dispatched.isEmpty(), "Owner approval alone cannot dispatch a command");
            c.assertEquals(queue.data.proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Owner approval is durable without automatic execution");
            var reloaded = new AgentActions(companions.server, queue.file, clock, dispatched::add);
            c.assertTrue(dispatched.isEmpty(), "Restart does not execute a stored owner approval");
            reloaded.codexApprove(companions.server.getCommandSource(), id);
            c.assertEquals(dispatched, java.util.List.of("time set day"), "Live console review dispatches exactly the stored allowlisted command");
            reloaded.codexApprove(companions.server.getCommandSource(), id);
            c.assertEquals(dispatched.size(), 1, "Consumed approval cannot replay");
            var afterRestart = new AgentActions(companions.server, queue.file, clock, dispatched::add);
            afterRestart.codexApprove(companions.server.getCommandSource(), id);
            c.assertEquals(dispatched.size(), 1, "Consumed approval remains terminal after restart");
        } finally { cleanup(companions, p); removeDirectory(dir); }
        c.complete();
    }
    @GameTest public void helperActionsExpireAndRejectOtherOwnersAndArbitraryCommands(TestContext c) throws Exception {
        var p = player(c, "action-first"); var other = player(c, "action-other");
        var companions = AgentCompanions.get(c.getWorld().getServer());
        Path dir = Files.createTempDirectory("infinity-action-expiry-");
        var dispatched = new ArrayList<String>(); var clock = new ActionClock();
        try {
            var queue = new AgentActions(companions.server, dir.resolve("actions.json"), clock, dispatched::add);
            queue.suggest(p, "set night");
            String id = queue.data.proposals.keySet().iterator().next();
            queue.ownerApprove(other, id); queue.cancel(other, id);
            c.assertEquals(queue.data.proposals.get(id).state, AgentActions.State.PENDING, "Another OP4 cannot approve or cancel an owner's proposal");
            c.assertTrue(AgentActions.Action.fromRequest("set day; op attacker") == null, "Prompt text cannot become arbitrary commands");
            queue.suggest(p, "set day; op attacker");
            c.assertEquals(queue.data.proposals.size(), 1, "Unsupported action does not enter the queue");
            clock.advance(AgentActions.LIFETIME_MS);
            queue.ownerApprove(p, id);
            c.assertEquals(queue.data.proposals.get(id).state, AgentActions.State.EXPIRED, "Expired proposal cannot gain owner approval");
            queue.codexApprove(companions.server.getCommandSource(), id);
            c.assertTrue(dispatched.isEmpty(), "Expired proposal cannot dispatch");
        } finally { cleanup(companions, p); cleanup(companions, other); removeDirectory(dir); }
        c.complete();
    }
    @GameTest public void helperActionsCodexGateAcceptsOnlyLocalConsole(TestContext c) throws Exception {
        var p = player(c, "action-console"); var companions = AgentCompanions.get(c.getWorld().getServer());
        Path dir = Files.createTempDirectory("infinity-action-console-");
        var dispatched = new ArrayList<String>();
        try {
            var queue = new AgentActions(companions.server, dir.resolve("actions.json"), new ActionClock(), dispatched::add);
            queue.suggest(p, "clear weather"); String id = queue.data.proposals.keySet().iterator().next(); queue.ownerApprove(p, id);
            var root = companions.server.getCommandManager().getDispatcher().getRoot().getChild("agent-codex-approve");
            var console = companions.server.getCommandSource();
            var otherOutput = console.withOutput(CommandOutput.DUMMY);
            c.assertTrue(root.canUse(console), "Real local server console can enter final review");
            c.assertFalse(root.canUse(p.getCommandSource()), "Even OP4 players cannot enter Codex review command");
            c.assertFalse(root.canUse(otherOutput), "RCON and command-block style outputs cannot enter local-console review");
            var scheduledFunction = console.withPermissions(LeveledPermissionPredicate.GAMEMASTERS).withSilent();
            c.assertFalse(root.canUse(scheduledFunction), "Scheduled datapack functions cannot impersonate the console");
            c.assertFalse(root.canUse(console.withSilent()), "A silent derived source cannot impersonate a direct console line");
            c.assertFalse(root.canUse(companions.server.getCommandFunctionManager().getScheduledCommandSource()),
                "The actual scheduled function source cannot enter Codex review");
            queue.codexApprove(p.getCommandSource(), id); queue.codexApprove(otherOutput, id); queue.codexApprove(scheduledFunction, id);
            c.assertTrue(dispatched.isEmpty(), "Non-console sources cannot dispatch despite owner approval");
            c.assertEquals(queue.data.proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Rejected sources cannot consume approval");
        } finally { cleanup(companions, p); removeDirectory(dir); }
        c.complete();
    }
}
