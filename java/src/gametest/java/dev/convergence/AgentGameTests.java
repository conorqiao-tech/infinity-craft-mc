package dev.convergence;

import net.minecraft.world.entity.EntityTypes;
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
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class AgentGameTests {
    private void acknowledgeLoadedPlayer(ServerPlayer player) {
        // Embedded clients do not acknowledge world transfers or respawn packets themselves.
        player.hasChangedDimension();
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
    }
    private ServerPlayer respawnLoadedPlayer(ServerPlayer player) {
        // The actual packet handler also rebinds networkHandler.player to the new life.
        // Calling PlayerManager.respawnPlayer alone omits that essential client-session step.
        var handler = player.connection;
        handler.handleClientCommand(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
            net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        if (handler.player == player) throw new IllegalStateException("The mock client did not respawn its dead player");
        acknowledgeLoadedPlayer(handler.player);
        return handler.player;
    }
    private ServerPlayer player(GameTestHelper c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var data = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var p = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, data.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, data);
        p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        c.getLevel().getServer().getPlayerList().op(new net.minecraft.server.players.NameAndId(profile), java.util.Optional.of(LevelBasedPermissionSet.OWNER), java.util.Optional.of(false));
        BlockPos feet = c.absolutePos(new BlockPos(3, 20, 3));
        for (BlockPos at : BlockPos.betweenClosed(feet.offset(-7, -1, -7), feet.offset(7, 4, 7)))
            c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        p.setPos(Vec3.atBottomCenterOf(feet)); p.setNoGravity(true); p.setGameMode(GameType.SURVIVAL);
        return p;
    }
    private IronGolem golem(AgentCompanions s, ServerPlayer p, String name) {
        s.spawn(p, name); var entry = s.owned(p, name);
        if (entry == null) throw new IllegalStateException("No helper was spawned");
        return s.loaded.get(UUID.fromString(entry.getKey()));
    }
    private void cleanup(AgentCompanions s, ServerPlayer p) {
        s.ceasefire(p);
        var names = s.data.agents.values().stream().filter(a -> a.owner().equals(p.getStringUUID())).map(AgentCompanions.Agent::name).toList();
        for (String name : names) s.dismiss(p, name);
        s.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
        s.server.getPlayerList().remove(p);
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
    @GameTest public void dismissalCannotFollowTreeMapSuccessorAndRemoveAnotherOwnersHelper(GameTestHelper c) throws Exception {
        var owner=player(c,"dismiss-exact-owner");var other=player(c,"dismiss-exact-other");
        var dir=Files.createTempDirectory("infinity-dismiss-identity-");
        var isolated=new AgentCompanions(c.getLevel().getServer(),dir.resolve("agents.json"));
        var ids=new UUID[]{new UUID(0,1),new UUID(0,2),new UUID(0,3)};
        var mobs=new IronGolem[3];
        try {
            for(int i=0;i<3;i++) {
                mobs[i]=EntityTypes.IRON_GOLEM.create(c.getLevel(),EntitySpawnReason.COMMAND);mobs[i].setUUID(ids[i]);
                isolated.loaded.put(ids[i],mobs[i]);
            }
            // Deliberately build a root with two children. TreeMap.remove changes
            // that live Entry to its successor, independent of random UUID order.
            for(int i:new int[]{1,0,2})isolated.data.agents.put(ids[i].toString(),
                new AgentCompanions.Agent((i==2?other:owner).getStringUUID(),i==1?"middle":i==0?"left":"foreign",
                    AgentCompanions.Mode.FOLLOW,AgentCompanions.Profile.REGULAR,c.getLevel().dimension().identifier().toString(),0,0,0));
            isolated.save();isolated.dismiss(owner,"middle");
            c.assertTrue(mobs[1].isRemoved(),"The exact selected helper is removed");
            c.assertFalse(mobs[0].isRemoved() || mobs[2].isRemoved(),"Neither a peer nor another owner's helper can be removed by tree-node replacement");
            c.assertTrue(!isolated.loaded.containsKey(ids[1]) && isolated.loaded.get(ids[0])==mobs[0] && isolated.loaded.get(ids[2])==mobs[2],"Only the selected loaded identity is forgotten");
            var restored=new AgentCompanions(isolated.server,dir.resolve("agents.json"));
            c.assertTrue(!restored.data.agents.containsKey(ids[1].toString()) && restored.data.agents.containsKey(ids[0].toString()) && restored.data.agents.containsKey(ids[2].toString()),"Saved roster retains both untouched helpers");
        } finally {
            for(var mob:mobs)if(mob!=null)mob.discard();
            cleanup(AgentCompanions.get(c.getLevel().getServer()),owner);cleanup(AgentCompanions.get(c.getLevel().getServer()),other);removeDirectory(dir);
        }
        c.succeed();
    }

    @GameTest public void helpersRequirePermissionLevelFour(GameTestHelper c) {
        var root = c.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("agent");
        var source = c.getLevel().getServer().createCommandSourceStack();
        c.assertFalse(root.canUse(source.withPermission(PermissionSet.NO_PERMISSIONS)), "Ordinary players cannot control helpers");
        c.assertFalse(root.canUse(source.withPermission(LevelBasedPermissionSet.GAMEMASTER)), "OP2 cannot control helpers");
        c.assertFalse(root.canUse(source.withPermission(LevelBasedPermissionSet.ADMIN)), "OP3 cannot control helpers");
        c.assertTrue(root.canUse(source.withPermission(LevelBasedPermissionSet.OWNER)), "OP4 controls helpers");
        for (String command : new String[]{"help", "spawn", "follow", "guard", "stay", "dismiss", "list", "profile", "status", "squad", "recall"}) c.assertTrue(root.getChild(command) != null, "Command exists: " + command);
        c.succeed();
    }
    @GameTest(maxTicks = 200) public void manualRecallCrossesModesPreservingIdentityStatsAndResetHistory(GameTestHelper c) {
        var owner = player(c, "recall-owner");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        var destination = GameModes.world(helpers.server, GameModes.Mode.HARDCORE);
        boolean[] waiting={false};
        // A real owner keeps its arrival chunk entity-ticking; since 26.1 an embedded test player's
        // ticket can land a tick later, which would save the arriving helper into the chunk instead.
        var arrival = net.minecraft.world.level.ChunkPos.containing(owner.blockPosition());
        Runnable release = () -> {cleanup(helpers, owner); destination.setChunkForced(arrival.x(), arrival.z(), false);};
        try {
            destination.setChunkForced(arrival.x(), arrival.z(), true);
            var helper = golem(helpers, owner, "traveler");
            var id = helper.getUUID();
            helpers.profile(owner, "traveler", AgentCompanions.Profile.ULTIMATE_FINALS);
            helpers.mode(owner, "traveler", AgentCompanions.Mode.STAY);
            var modifier = Identifier.fromNamespaceAndPath("infinity_test", "recall_modifier");
            helper.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(19);
            helper.getAttribute(Attributes.ATTACK_DAMAGE).addPermanentModifier(
                new AttributeModifier(modifier, 5, AttributeModifier.Operation.ADD_VALUE));
            c.assertTrue(AdminStats.set(owner.createCommandSourceStack(), helper, "attack_damage", 45).success(), "Set real admin damage before transfer");
            c.assertTrue(AdminStats.set(owner.createCommandSourceStack(), helper, "max_health", 320).success(), "Set real admin capacity before transfer");
            c.assertTrue(AdminStats.set(owner.createCommandSourceStack(), helper, "health", 280).success(), "Set real health before transfer");
            var feet = owner.blockPosition();
            for (var at : BlockPos.betweenClosed(feet.offset(-8, -1, -8), feet.offset(8, 6, 8)))
                destination.setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            var target = new TeleportTransition(destination, owner.position(), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
            c.assertTrue(helper.teleport(target) == null, "A normal helper portal cannot cross isolated modes");
            c.assertFalse(AgentCompanions.allowRecallTeleport(helper, target), "No permit exists before an explicit recall");
            c.assertTrue(owner.teleport(target) == owner, "Actual OP4 owner moves to another mode");
            acknowledgeLoadedPlayer(owner);
            helpers.recall(owner, "traveler");
            var moved = helpers.loaded.get(id);
            c.assertTrue(moved != null && moved != helper && moved.level() == destination, "Native inter-mode transfer rebinds the destination entity");
            c.assertValueEqual(moved.getHealth(), 280f, "Current health survives recall");
            c.assertValueEqual(moved.getMaxHealth(), 320f, "Edited capacity survives recall");
            c.assertValueEqual(moved.getAttributeValue(Attributes.ATTACK_DAMAGE), 50d, "Edited base and independent modifier survive recall");
            c.assertValueEqual(AdminStats.original(moved, AdminStats.find(moved, "attack_damage")), 19d, "Original admin reset value survives native transfer");
            var record = helpers.data.agents.get(id.toString());
            c.assertValueEqual(record.profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Recall preserves profile");
            c.assertValueEqual(record.mode(), AgentCompanions.Mode.FOLLOW, "Explicit recall resumes following");
            c.assertValueEqual(record.dimension(), destination.dimension().identifier().toString(), "Anchor dimension follows the actual destination");
            c.assertValueEqual(record.anchor(), moved.position(), "Saved anchor uses the checked arrival point");
            helpers.unload(helper);
            c.assertTrue(helpers.loaded.get(id) == moved, "A late old-world unload cannot erase the destination instance");
            c.assertFalse(AgentCompanions.allowRecallTeleport(moved, target), "Permit is cleared after synchronous transfer");
            var back = new TeleportTransition(c.getLevel(), Vec3.atBottomCenterOf(feet), Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING);
            c.assertTrue(moved.teleport(back) == null, "Later generic portals remain blocked");
            // Since 26.1 a newly ticketed chunk can take several ticks to become entity-ticking;
            // re-check each tick until tracking settles instead of at a fixed delay.
            c.succeedWhen(()->{
                var oldWorldEntity=c.getLevel().getEntity(id);
                c.assertTrue((oldWorldEntity==null || oldWorldEntity.isRemoved()) && destination.getEntity(id)==moved && !moved.isRemoved(),
                    "After native chunk/entity tracking updates, exactly one live world entity has the same UUID. source="+oldWorldEntity+" destination="+destination.getEntity(id)+" removed="+moved.isRemoved()+" age="+moved.tickCount+" ready="+destination.isPositionEntityTicking(moved.blockPosition()));
                release.run();
            });
            waiting[0]=true;
        } finally { if(!waiting[0])release.run(); }
    }
    @GameTest public void recallRefusesForeignRevokedDeadUnloadedAndUnsafeScaledHelpers(GameTestHelper c) throws Exception {
        var owner = player(c, "recall-checks");
        var other = player(c, "recall-other");
        var helpers = AgentCompanions.get(c.getLevel().getServer());
        IronGolem helper = null;
        var passenger = EntityTypes.PIG.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            helper = golem(helpers, owner, "checked");
            helpers.mode(owner, "checked", AgentCompanions.Mode.STAY);
            var start = helper.position();
            helpers.recall(other, "checked");
            c.assertValueEqual(helper.position(), start, "Even another OP4 cannot recall a foreign helper");
            OperatorGameTests.level(owner, LevelBasedPermissionSet.ADMIN);
            helpers.server.getCommands().getDispatcher().execute("agent recall checked", helpers.server.createCommandSourceStack().withEntity(owner));
            c.assertValueEqual(helper.position(), start, "Console execute-as cannot bypass the owner's actual OP level");
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            owner.setGameMode(GameType.SPECTATOR);
            helpers.recall(owner, "checked");
            c.assertValueEqual(helper.position(), start, "Spectator owner cannot recall");
            owner.setGameMode(GameType.SURVIVAL);
            owner.setHealth(0);
            helpers.recall(owner, "checked");
            c.assertValueEqual(helper.position(), start, "Dead owner cannot recall");
            owner.setHealth(20);
            helpers.loaded.remove(helper.getUUID());
            helpers.recall(owner, "checked");
            c.assertValueEqual(helper.position(), start, "Unloaded identity is never recreated or searched into loaded chunks");
            c.assertTrue(!helpers.loaded.containsKey(helper.getUUID()), "Unloaded refusal leaves the loaded registry unchanged");
            helpers.loaded.put(helper.getUUID(), helper);
            passenger.setPos(helper.position());
            c.getLevel().addFreshEntity(passenger);
            c.assertTrue(passenger.startRiding(helper, true, false), "A real mounted passenger exercises native transfer containment");
            helpers.recall(owner, "checked");
            c.assertValueEqual(helper.position(), start, "Recall refuses to transfer a helper with passengers");
            c.assertTrue(passenger.getVehicle() == helper, "Refusal leaves unrelated passenger state intact");
            passenger.stopRiding(); passenger.discard();
            helper.getAttribute(Attributes.SCALE).setBaseValue(3);
            helpers.recall(owner, "checked");
            c.assertFalse(helper.getDimensions(helper.getPose()).makeBoundingBox(helper.position()).intersects(owner.getBoundingBox()), "Safe placement includes the owner using the latest effective scale before the next entity tick");
            c.assertValueEqual(helpers.owned(owner, "checked").getValue().mode(), AgentCompanions.Mode.FOLLOW, "An enlarged helper can be recalled where its full body fits");
            helpers.mode(owner, "checked", AgentCompanions.Mode.STAY);
            start = helper.position();
            helper.getAttribute(Attributes.SCALE).setBaseValue(2);
            var feet = owner.blockPosition();
            for (var at : BlockPos.betweenClosed(feet.offset(-7, 3, -7), feet.offset(7, 3, 7)))
                c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            helpers.recall(owner, "checked");
            c.assertValueEqual(helper.position(), start, "Recall checks the edited scale's actual body against a low ceiling");
            c.assertValueEqual(helpers.owned(owner, "checked").getValue().mode(), AgentCompanions.Mode.STAY, "Failed recalls do not change movement or roster identity");
            c.assertFalse(AgentCompanions.allowRecallTeleport(helper,
                new TeleportTransition(GameModes.world(helpers.server, GameModes.Mode.HARDCORE), start, Vec3.ZERO, 0, 0, TeleportTransition.DO_NOTHING)),
                "Rejected requests leave no reusable cross-mode permit");
        } finally {
            passenger.discard();
            if (helper != null && !helper.isRemoved()) helpers.loaded.put(helper.getUUID(), helper);
            OperatorGameTests.level(owner, LevelBasedPermissionSet.OWNER);
            cleanup(helpers, owner); cleanup(helpers, other);
        }
        c.succeed();
    }
    @GameTest public void helpersBoundNamesOwnershipAndUnloadedCount(GameTestHelper c) {
        var p = player(c, "helper-owner"); var other = player(c, "helper-other"); var s = AgentCompanions.get(c.getLevel().getServer());
        try {
            s.spawn(p, "../escape"); c.assertValueEqual(s.count(p), 0, "Invalid names rejected");
            var golem = golem(s, p, "one"); s.spawn(p, "one"); c.assertValueEqual(s.count(p), 1, "Duplicate name rejected");
            s.mode(other, "one", AgentCompanions.Mode.STAY); s.dismiss(other, "one");
            c.assertTrue(s.owned(p, "one") != null, "Other UUID cannot modify or dismiss owner's helper");
            c.assertValueEqual(s.owned(p, "one").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Unauthorized mode change rejected");
            for (String name : new String[]{"two", "three", "four", "five", "six"}) golem(s, p, name);
            s.loaded.remove(golem.getUUID()); s.spawn(p, "seven");
            c.assertValueEqual(s.count(p), AgentCompanions.LIMIT, "Unloaded helper still consumes a slot");
            c.assertTrue(s.owned(p, "seven") == null, "Seventh helper rejected");
            s.dismiss(p, "one"); s.load(golem); c.assertTrue(golem.isRemoved(), "Dismissed unloaded entity removed on next load");
            c.assertValueEqual(s.count(p), AgentCompanions.LIMIT - 1, "Dismissal frees exactly one slot");
        } finally { cleanup(s, p); cleanup(s, other); }
        c.succeed();
    }
    @GameTest public void helpersPersistRosterVanillaTagsAndModes(GameTestHelper c) {
        var p = player(c, "helper-save"); var s = AgentCompanions.get(c.getLevel().getServer());
        try {
            var golem = golem(s, p, "keeper"); s.profile(p, "keeper", AgentCompanions.Profile.ULTIMATE_FINALS); s.mode(p, "keeper", AgentCompanions.Mode.GUARD);
            var record = s.owned(p, "keeper");
            c.assertValueEqual(AgentCompanions.read(s.file).agents.get(record.getKey()).mode(), AgentCompanions.Mode.GUARD, "Guard mode and ownership survive roster reload");
            c.assertValueEqual(AgentCompanions.read(s.file).agents.get(record.getKey()).profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Profile survives roster reload");
            var write = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, c.getLevel().registryAccess()); golem.saveWithoutId(write);
            var restored = EntityTypes.IRON_GOLEM.create(c.getLevel(), EntitySpawnReason.LOAD);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, c.getLevel().registryAccess(), write.buildResult()));
            c.assertTrue(restored.entityTags().contains(AgentCompanions.TAG), "Vanilla Tags preserve identity after entity NBT roundtrip");
            c.assertTrue(restored.entityTags().contains("infinity_owner_" + p.getStringUUID()), "Owner is identifiable in vanilla NBT");
            c.assertTrue(restored.entityTags().contains("infinity_mode_guard"), "Mode is identifiable in vanilla NBT");
            c.assertTrue(restored.entityTags().contains("infinity_profile_ultimate_finals"), "Profile is identifiable in vanilla NBT");
            golem.discard(); s.load(restored);
            c.assertTrue(restored.isPersistenceRequired(), "Loaded helper does not despawn");
            c.assertTrue(((AgentGoalAccess) restored).infinity$getTargetSelector().getAvailableGoals().isEmpty(), "Reload removes vanilla revenge and village targeting");
            c.assertTrue(s.loaded.get(restored.getUUID()) == restored, "Reload reconnects helper to its roster UUID");
        } finally { cleanup(s, p); }
        c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena", maxTicks = 60) public void helpersActuallyFollowUsingNavigation(GameTestHelper c) {
        var p = player(c, "helper-follow"); var s = AgentCompanions.get(c.getLevel().getServer());
        var feet=c.absolutePos(new BlockPos(16,20,16));
        for(var at:BlockPos.betweenClosed(feet.offset(-7,-1,-7),feet.offset(7,4,7)))
            c.getLevel().setBlockAndUpdate(at,at.getY()==feet.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        p.setPos(Vec3.atBottomCenterOf(feet));
        // The arena extends outside the tiny GameTest structure's ticketed chunk.
        // Embedded clients do not provide a real player's simulation tickets there.
        // Ticket the complete platform (including the golem's starting chunk) so
        // the assertion measures native navigation rather than chunk scheduling.
        var forced = new ArrayList<net.minecraft.world.level.ChunkPos>();
        for (int x = (p.getBlockX() - 7) >> 4; x <= (p.getBlockX() + 7) >> 4; x++)
            for (int z = (p.getBlockZ() - 7) >> 4; z <= (p.getBlockZ() + 7) >> 4; z++)
                if (c.getLevel().setChunkForced(x, z, true)) forced.add(new net.minecraft.world.level.ChunkPos(x, z));
        Runnable finish = () -> {
            try { cleanup(s, p); }
            finally { for (var chunk : forced) c.getLevel().setChunkForced(chunk.x(), chunk.z(), false); }
        };
        final IronGolem golem;
        try {
            golem = golem(s, p, "walker");
            golem.setPos(p.getX() - 5, p.getY(), p.getZ());
        } catch (RuntimeException | Error failure) { finish.run(); throw failure; }
        double start = golem.distanceToSqr(p);
        boolean[] started={false};
        c.failIfEver(() -> {
            if(started[0] || !golem.onGround())return;
            try {
                s.control(golem, s.owned(p, "walker").getValue(), s.server.getTickCount());
                c.assertFalse(golem.getNavigation().isDone(), "Follow starts real vanilla path navigation after landing");
                started[0]=true;
            } catch (RuntimeException | Error failure) { finish.run(); throw failure; }
        });
        c.runAtTickTime(45, () -> {
            try {
                c.assertTrue(started[0], "Helper landed and started vanilla path navigation");
                c.assertTrue(golem.distanceToSqr(p) < start - 1, "Helper walked toward the owner through vanilla navigation");
            }
            finally { finish.run(); }
            c.succeed();
        });
    }
    @GameTest public void helpersAttackHostilesAndNeverDamagePlayersOrPets(GameTestHelper c) {
        var p = player(c, "helper-combat"); var s = AgentCompanions.get(c.getLevel().getServer());
        var wolf = EntityTypes.WOLF.create(c.getLevel(), EntitySpawnReason.COMMAND);
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "shield"); wolf.setPos(golem.position()); wolf.tame(p); c.getLevel().addFreshEntity(wolf);
            zombie.setPos(golem.position().add(1, 0, 0)); zombie.setNoAi(true); c.getLevel().addFreshEntity(zombie);
            // Golem hits roll up to 21 damage; since 26.1 getTarget() drops a target that died.
            zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); zombie.setHealth(100);
            float playerHealth = p.getHealth(), petHealth = wolf.getHealth(), hostileHealth = zombie.getHealth();
            golem.setTarget(p); c.assertFalse(golem.doHurtTarget(c.getLevel(), p), "Real golem attack on player is blocked");
            c.assertValueEqual(p.getHealth(), playerHealth, "Player health unchanged");
            golem.setTarget(wolf); c.assertFalse(golem.doHurtTarget(c.getLevel(), wolf), "Real golem attack on tamed pet is blocked");
            c.assertValueEqual(wolf.getHealth(), petHealth, "Pet health unchanged");
            golem.hurtServer(c.getLevel(), golem.damageSources().playerAttack(p), 1);
            golem.setTarget(null); golem.tick();
            c.assertTrue(golem.getTarget() != p, "Vanilla revenge goals cannot target the attacking owner");
            s.control(golem, s.owned(p, "shield").getValue(), 20);
            c.assertTrue(golem.getTarget() == zombie, "Helper chooses a nearby hostile mob");
            c.assertTrue(zombie.getHealth() < hostileHealth, "Real helper melee attack damages hostile mob");
            s.mode(p, "shield", AgentCompanions.Mode.STAY); s.control(golem, s.owned(p, "shield").getValue(), 40);
            c.assertTrue(golem.getTarget() == null, "Stay disables combat");
            c.assertTrue(golem.getNavigation().isDone(), "Stay halts navigation");
        } finally { wolf.discard(); zombie.discard(); cleanup(s, p); }
        c.succeed();
    }
    @GameTest public void followingHelpersPrioritizeThreatsToOwner(GameTestHelper c) {
        var p = player(c, "helper-protect"); var s = AgentCompanions.get(c.getLevel().getServer());
        var nearby = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        var threat = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "protector");
            golem.setPos(p.position().add(1, 0, 0));
            nearby.setPos(p.position().add(3, 0, 0)); nearby.setNoAi(true); c.getLevel().addFreshEntity(nearby);
            threat.setPos(p.position().add(6, 0, 0)); threat.setNoAi(true); c.getLevel().addFreshEntity(threat);
            threat.setTarget(p);
            s.control(golem, s.owned(p, "protector").getValue(), 20);
            c.assertTrue(golem.getTarget() == threat, "Follow protects its owner before pursuing a closer bystander");
            s.mode(p, "protector", AgentCompanions.Mode.GUARD);
            s.control(golem, s.owned(p, "protector").getValue(), 25);
            c.assertTrue(golem.getTarget() == nearby, "Guard keeps choosing the nearest hostile near its anchor");
        } finally { nearby.discard(); threat.discard(); cleanup(s, p); }
        c.succeed();
    }
    @GameTest(maxTicks = 60) public void helpersPauseForLogoutDeathAndDifferentDimensions(GameTestHelper c) {
        var p = player(c, "helper-lifecycle"); var s = AgentCompanions.get(c.getLevel().getServer());
        var golem = golem(s, p, "patient");
        golem.setPos(p.getX() - 5, p.getY(), p.getZ());
        boolean[] checked={false};
        c.failIfEver(() -> {
          if(checked[0])return;
          checked[0]=true;
          // Mock clients can leave the spawned golem mid-fall for the whole test.
          // The behavior under test is helper control on a known solid floor.
          golem.setOnGround(true);
          try {
            var agent = s.owned(p, "patient").getValue();
            s.control(golem, agent, 20);
            c.assertFalse(golem.getNavigation().isDone(), "Live owner permits following");
            p.setHealth(0); s.control(golem, agent, 25);
            c.assertTrue(golem.getNavigation().isDone(), "Owner death pauses helper"); p.setHealth(p.getMaxHealth());
            s.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
            s.control(golem, agent, 30); c.assertTrue(golem.getNavigation().isDone(), "Losing OP4 pauses helper");
            s.server.getPlayerList().op(new net.minecraft.server.players.NameAndId(p.getGameProfile()), java.util.Optional.of(LevelBasedPermissionSet.OWNER), java.util.Optional.of(false));
            var offline = new AgentCompanions.Agent(UUID.randomUUID().toString(), agent.name(), agent.mode(), agent.dimension(), agent.x(), agent.y(), agent.z());
            s.control(golem, offline, 30); c.assertTrue(golem.getNavigation().isDone(), "Offline owner pauses helper without removing persistent roster");
            Vec3 pos = p.position(); var nether = s.server.getLevel(Level.NETHER);
            p.teleportTo(nether, 0, 100, 0, Set.of(), 0, 0, true); s.control(golem, agent, 35);
            c.assertTrue(golem.getNavigation().isDone(), "Different dimension pauses helper");
            c.assertTrue(golem.level() == c.getLevel(), "Helper stays in its original dimension");
            p.teleportTo(c.getLevel(), pos.x, pos.y, pos.z, Set.of(), 0, 0, true);
            s.mode(p, "patient", AgentCompanions.Mode.GUARD); var guard = s.owned(p, "patient").getValue();
            golem.setPos(golem.position().add(5, 0, 0)); s.control(golem, guard, 40);
            c.assertFalse(golem.getNavigation().isDone(), "Guard navigates back toward its saved anchor");
            golem.hurtServer(c.getLevel(), golem.damageSources().genericKill(), 10000);
            c.assertValueEqual(s.count(p), 0, "Helper death removes ownership record and frees slot");
          } finally { cleanup(s, p); }
          c.succeed();
        });
    }
    @GameTest public void helperCorruptRosterPreservesOriginal(GameTestHelper c) {
        try {
            var file = Files.createTempDirectory("infinity-agent-test-").resolve("state.json"); Files.writeString(file, "{broken-json");
            boolean failed = false; try { AgentCompanions.read(file); } catch (IllegalStateException expected) { failed = true; }
            c.assertTrue(failed, "Corrupt roster fails visibly rather than losing ownership");
            c.assertValueEqual(Files.readString(file), "{broken-json", "Original data retained for recovery");
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
        c.succeed();
    }
    @GameTest public void helperRosterMigratesAndRejectsInvalidProfiles(GameTestHelper c) throws Exception {
        Path dir = Files.createTempDirectory("infinity-helper-migration-");
        try {
            Path file = dir.resolve("roster.json"); var data = new AgentCompanions.Data(); data.format = 1;
            String id = UUID.randomUUID().toString(), owner = UUID.randomUUID().toString();
            data.agents.put(id, new AgentCompanions.Agent(owner, "legacy", AgentCompanions.Mode.GUARD, "minecraft:overworld", 0, 64, 0));
            var json = com.google.gson.JsonParser.parseString(CommunityServer.GSON.toJson(data)).getAsJsonObject();
            json.getAsJsonObject("agents").getAsJsonObject(id).remove("profile");
            String original = json.toString(); Files.writeString(file, original);
            var migrated = AgentCompanions.read(file);
            c.assertValueEqual(migrated.format, 2, "Legacy schema upgrades in memory");
            c.assertValueEqual(migrated.agents.get(id).profile(), AgentCompanions.Profile.REGULAR, "Legacy helpers keep their regular combat behavior");
            c.assertValueEqual(migrated.agents.get(id).mode(), AgentCompanions.Mode.GUARD, "Migration retains guard movement");
            c.assertValueEqual(Files.readString(file), original, "A read does not overwrite a legacy roster");
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
            c.assertValueEqual(AgentCompanions.Profile.parse("ultimate-finals"), AgentCompanions.Profile.ULTIMATE_FINALS, "Command profile names accept the documented hyphen alias");
        } finally { removeDirectory(dir); }
        c.succeed();
    }
    @GameTest public void helperRosterBoundsFileSizeAndGlobalCount(GameTestHelper c) throws Exception {
        Path dir = Files.createTempDirectory("infinity-helper-bounds-");
        try {
            Path file = dir.resolve("roster.json"); String oversized = " ".repeat(AgentCompanions.MAX_BYTES + 1); Files.writeString(file, oversized);
            boolean rejected = false; try { AgentCompanions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Oversized data rejected before parsing");
            c.assertValueEqual(Files.size(file), (long) AgentCompanions.MAX_BYTES + 1, "Oversized original remains intact");
            var data = new AgentCompanions.Data(); String owner = UUID.randomUUID().toString();
            for (int index = 0; index <= AgentCompanions.GLOBAL_LIMIT; index++) {
                if (index % AgentCompanions.LIMIT == 0) owner = UUID.randomUUID().toString();
                data.agents.put(UUID.randomUUID().toString(), new AgentCompanions.Agent(owner, "helper-" + index, AgentCompanions.Mode.STAY, "minecraft:overworld", 0, 64, 0));
            }
            Files.writeString(file, CommunityServer.GSON.toJson(data)); rejected = false;
            try { AgentCompanions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Roster rejects a 25th helper even when individual owners remain within six");
        } finally { removeDirectory(dir); }
        c.succeed();
    }
    @GameTest public void helperGlobalLimitIncludesUnloadedOwners(GameTestHelper c) throws Exception {
        var p = player(c, "helper-global"); Path dir = Files.createTempDirectory("infinity-helper-global-");
        var companions = AgentCompanions.get(c.getLevel().getServer());
        try {
            var isolated = new AgentCompanions(companions.server, dir.resolve("roster.json")); String owner = UUID.randomUUID().toString();
            for (int index = 0; index < AgentCompanions.GLOBAL_LIMIT; index++) {
                if (index % AgentCompanions.LIMIT == 0) owner = UUID.randomUUID().toString();
                isolated.data.agents.put(UUID.randomUUID().toString(), new AgentCompanions.Agent(owner, "helper-" + index, AgentCompanions.Mode.STAY, "minecraft:overworld", 0, 64, 0));
            }
            isolated.save(); isolated.spawn(p, "overflow");
            c.assertValueEqual(isolated.count(p), 0, "New owner cannot bypass global cap");
            c.assertValueEqual(isolated.data.agents.size(), AgentCompanions.GLOBAL_LIMIT, "Unloaded helpers count toward global limit");
            c.assertTrue(isolated.loaded.isEmpty(), "Rejected creation loads no entities or chunks");
        } finally { cleanup(companions, p); removeDirectory(dir); }
        c.succeed();
    }
    @GameTest public void helperMutationsRecheckOpFourInsideMethods(GameTestHelper c) {
        var p = player(c, "helper-revoke"); var s = AgentCompanions.get(c.getLevel().getServer());
        try {
            golem(s, p, "locked");
            s.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
            s.spawn(p, "extra"); s.mode(p, "locked", AgentCompanions.Mode.STAY); s.profile(p, "locked", AgentCompanions.Profile.DEBUG);
            s.squad(p, AgentCompanions.Mode.GUARD); s.dismiss(p, "locked");
            c.assertValueEqual(s.count(p), 1, "Permission loss blocks spawn and dismissal even without command dispatcher");
            c.assertValueEqual(s.owned(p, "locked").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Permission loss blocks direct movement and squad changes");
            c.assertValueEqual(s.owned(p, "locked").getValue().profile(), AgentCompanions.Profile.REGULAR, "Permission loss blocks direct profile changes");
        } finally {
            s.server.getPlayerList().op(new net.minecraft.server.players.NameAndId(p.getGameProfile()), java.util.Optional.of(LevelBasedPermissionSet.OWNER), java.util.Optional.of(false));
            cleanup(s, p);
        }
        c.succeed();
    }
    @GameTest public void helperSquadChangesOnlyLoadedSameDimensionOwnedHelpers(GameTestHelper c) {
        var p = player(c, "helper-squad"); var other = player(c, "helper-stranger"); var s = AgentCompanions.get(c.getLevel().getServer());
        try {
            var first = golem(s, p, "first"); var second = golem(s, p, "second"); var unloaded = golem(s, p, "unloaded");
            var stranger = golem(s, other, "stranger"); s.loaded.remove(unloaded.getUUID());
            s.squad(p, AgentCompanions.Mode.GUARD);
            c.assertValueEqual(s.owned(p, "first").getValue().mode(), AgentCompanions.Mode.GUARD, "First loaded squad member guards");
            c.assertValueEqual(s.owned(p, "second").getValue().mode(), AgentCompanions.Mode.GUARD, "Second loaded squad member guards");
            c.assertValueEqual(s.owned(p, "unloaded").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Squad command does not modify unloaded helpers");
            c.assertValueEqual(s.owned(other, "stranger").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Squad cannot control another OP's helper");
            var prior = AgentCompanions.read(s.file.resolveSibling(s.file.getFileName() + ".previous"));
            c.assertValueEqual(prior.agents.get(first.getStringUUID()).mode(), AgentCompanions.Mode.FOLLOW, "One squad save preserves the previous state of the first helper");
            c.assertValueEqual(prior.agents.get(second.getStringUUID()).mode(), AgentCompanions.Mode.FOLLOW, "One squad save preserves the previous state of the whole squad");
            var position = p.position(); p.teleportTo(s.server.getLevel(Level.NETHER), 0, 100, 0, Set.of(), 0, 0, true);
            s.squad(p, AgentCompanions.Mode.STAY);
            c.assertValueEqual(s.owned(p, "first").getValue().mode(), AgentCompanions.Mode.GUARD, "Squad skips helpers in another dimension");
            p.teleportTo(c.getLevel(), position.x, position.y, position.z, Set.of(), 0, 0, true);
            s.dismiss(p, "unloaded"); s.load(unloaded); c.assertTrue(unloaded.isRemoved(), "Skipped unloaded helper remains dismissible");
        } finally { cleanup(s, p); cleanup(s, other); }
        c.succeed();
    }
    @GameTest public void passiveProfilesDisableEvenForcedHostileDamage(GameTestHelper c) {
        var p = player(c, "helper-passive"); var s = AgentCompanions.get(c.getLevel().getServer());
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "observer"); zombie.setPos(golem.position().add(1, 0, 0)); zombie.setNoAi(true); c.getLevel().addFreshEntity(zombie);
            float health = zombie.getHealth();
            for (var profile : new AgentCompanions.Profile[]{AgentCompanions.Profile.DEBUG, AgentCompanions.Profile.CLI, AgentCompanions.Profile.API}) {
                s.profile(p, "observer", profile); s.control(golem, s.owned(p, "observer").getValue(), 20);
                c.assertTrue(golem.getTarget() == null, "Passive profile chooses no hostile target: " + profile);
                golem.setTarget(zombie); c.assertFalse(golem.doHurtTarget(c.getLevel(), zombie), "Independent damage gate blocks forced attacks for " + profile);
                c.assertValueEqual(zombie.getHealth(), health, "Passive forced attack causes no damage");
            }
            String saved = CommunityServer.GSON.toJson(s.data); var tags = Set.copyOf(golem.entityTags());
            s.status(p, "observer"); c.assertValueEqual(CommunityServer.GSON.toJson(s.data), saved, "Status diagnostics cannot change saved helper state");
            c.assertValueEqual(golem.entityTags(), tags, "Status diagnostics do not mutate tags");
        } finally { zombie.discard(); cleanup(s, p); }
        c.succeed();
    }
    @GameTest public void primitiveProfileIsReadyToAttackAtLongerSensingRange(GameTestHelper c) {
        var p = player(c, "helper-primitive"); var s = AgentCompanions.get(c.getLevel().getServer());
        var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var golem = golem(s, p, "fighter"); golem.setPos(p.position().add(1, 0, 0));
            zombie.setPos(p.position().add(11, 0, 0)); zombie.setNoAi(true); c.getLevel().addFreshEntity(zombie);
            s.control(golem, s.owned(p, "fighter").getValue(), 20); c.assertTrue(golem.getTarget() == null, "Regular senses hostiles within ten blocks");
            s.profile(p, "fighter", AgentCompanions.Profile.PRIMITIVE); s.control(golem, s.owned(p, "fighter").getValue(), 25);
            c.assertTrue(golem.getTarget() == zombie, "Primitive proactively engages a hostile eleven blocks away");
            s.mode(p, "fighter", AgentCompanions.Mode.STAY); s.control(golem, s.owned(p, "fighter").getValue(), 30);
            c.assertTrue(golem.getTarget() == null, "Stay still stops an aggressive primitive helper");
        } finally { zombie.discard(); cleanup(s, p); }
        c.succeed();
    }
    @GameTest public void ultimateFinalsSquadSharesFocusAndFlanksHostiles(GameTestHelper c) {
        var p = player(c, "helper-hive"); var s = AgentCompanions.get(c.getLevel().getServer());
        var focus = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND); var closer = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var first = golem(s, p, "alpha"); var second = golem(s, p, "beta");
            s.profile(p, "alpha", AgentCompanions.Profile.ULTIMATE_FINALS); s.profile(p, "beta", AgentCompanions.Profile.ULTIMATE_FINALS);
            first.setPos(p.position().add(-3, 0, 0)); second.setPos(p.position().add(4, 0, 0));
            focus.setPos(p.position().add(0, 0, 4)); focus.setNoAi(true); c.getLevel().addFreshEntity(focus);
            closer.setPos(p.position().add(5, 0, 3)); closer.setNoAi(true); c.getLevel().addFreshEntity(closer);
            s.control(first, s.owned(p, "alpha").getValue(), 20); s.control(second, s.owned(p, "beta").getValue(), 20);
            c.assertTrue(first.getTarget() == focus && second.getTarget() == focus, "Hive shares focus despite a nearer hostile for one golem");
            var left = s.flankPoint(first, s.owned(p, "alpha").getValue(), p, focus); var right = s.flankPoint(second, s.owned(p, "beta").getValue(), p, focus);
            c.assertTrue(left != null && right != null && left.distanceToSqr(right) > 4, "Squad receives separate clear loaded flanking positions");
            closer.setTarget(p); s.control(first, s.owned(p, "alpha").getValue(), 25); s.control(second, s.owned(p, "beta").getValue(), 25);
            c.assertTrue(first.getTarget() == closer && second.getTarget() == closer, "Owner threat overrides prior shared focus");
            c.assertFalse(AgentCompanions.allowDamage(p, p.damageSources().mobAttack(first)), "Aggressive hive still cannot attack a player");
        } finally { focus.discard(); closer.discard(); cleanup(s, p); }
        c.succeed();
    }
    @GameTest public void approvedPlayerTargetCoordinatesAggressiveProfilesAndKeepsRegularUnchanged(GameTestHelper c) {
        var owner = player(c, "hive-owner"); var target = player(c, "hive-target"); var bystander = player(c, "hive-bystander");
        var s = AgentCompanions.get(c.getLevel().getServer()); var zombie = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server);
            var first = golem(s, owner, "alpha"); var second = golem(s, owner, "beta"); var regular = golem(s, owner, "regular");
            first.setPos(owner.position().add(-2, 0, 0)); second.setPos(owner.position().add(2, 0, 0)); regular.setPos(owner.position().add(5, 0, 0));
            target.setPos(owner.position().add(0, 0, 5)); bystander.setPos(owner.position().add(0, 0, 2));
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
            zombie.setPos(owner.position().add(5, 0, 3)); zombie.setNoAi(true); c.getLevel().addFreshEntity(zombie);
            s.profile(owner, "alpha", AgentCompanions.Profile.PRIMITIVE); s.profile(owner, "beta", AgentCompanions.Profile.ULTIMATE_FINALS);
            c.assertFalse(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(first)), "No player damage before exact reviewed assignment");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Core accepts a valid exact player target after action review");
            s.control(first, s.owned(owner, "alpha").getValue(), 20); s.control(second, s.owned(owner, "beta").getValue(), 20); s.control(regular, s.owned(owner, "regular").getValue(), 20);
            c.assertTrue(first.getTarget() == target && second.getTarget() == target, "Primitive and Ultimate Finals share the same explicitly named player");
            c.assertTrue(regular.getTarget() == zombie, "Regular keeps hostile-mob behavior during a player order");
            c.assertFalse(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(first)), "Approval does not permit remote melee damage while pursuit is still needed");
            target.setPos(first.position().add(1, 0, 0));
            bystander.setPos(first.position().add(0, 0, 1));
            c.assertTrue(first.isWithinMeleeAttackRange(target) && first.getSensing().hasLineOfSight(target), "Approved damage fixture is now in melee range with clear sight");
            c.assertFalse(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(regular)), "Regular cannot gain player damage from a squad order");
            c.assertFalse(AgentCompanions.allowDamage(bystander, bystander.damageSources().mobAttack(first)), "A nearer unnamed player is never authorized");
            c.assertFalse(AgentCompanions.allowDamage(owner, owner.damageSources().mobAttack(first)), "Owner is never authorized");
            c.assertTrue(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(first)), "Independent gate accepts only the exact current target");
            float health = target.getHealth();
            c.assertTrue(first.doHurtTarget(c.getLevel(), target), "Real golem melee attack is permitted for the approved player");
            c.assertTrue(target.getHealth() < health, "Approved melee causes real player damage");
            s.dismiss(owner, "alpha");
            c.assertFalse(AgentCompanions.approvedPlayerTarget(first, target), "A removed helper keeps no player-target approval");
        } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); zombie.discard(); cleanup(s, owner); cleanup(s, target); cleanup(s, bystander); }
        c.succeed();
    }
    @GameTest public void playerTargetHonorsWorldPvpFriendlyFireModesAndSelfProtection(GameTestHelper c) {
        var owner = player(c, "hive-rules-owner"); var target = player(c, "hive-rules-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP); var scoreboard = s.server.getScoreboard();
        var team = scoreboard.addPlayerTeam("hive-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter");
            s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE); target.setPos(owner.position().add(0, 0, 5));
            c.assertFalse(s.assignPlayerTarget(owner, owner), "An owner cannot order an attack on themself");
            for (var mode : new GameType[]{GameType.CREATIVE, GameType.SPECTATOR}) {
                target.setGameMode(mode); c.assertFalse(s.assignPlayerTarget(owner, target), "Player mode protected: " + mode);
            }
            target.setGameMode(GameType.ADVENTURE); c.assertTrue(s.assignPlayerTarget(owner, target), "Adventure players can be explicitly targeted");
            c.getLevel().getGameRules().set(GameRules.PVP, false, s.server);
            c.assertFalse(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(helper)), "Disabling world PvP immediately blocks forced helper damage");
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "PvP change clears the order");
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server);
            scoreboard.addPlayerToTeam(owner.getScoreboardName(), team); scoreboard.addPlayerToTeam(target.getScoreboardName(), team); team.setAllowFriendlyFire(false);
            c.assertFalse(s.assignPlayerTarget(owner, target), "Team friendly-fire protection is respected");
            team.setAllowFriendlyFire(true); c.assertTrue(s.assignPlayerTarget(owner, target), "An explicit order is eligible when team PvP is allowed");
            team.setAllowFriendlyFire(false); s.tick(); c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "Changing friendly fire clears an already active order");
            scoreboard.removePlayerFromTeam(owner.getScoreboardName()); scoreboard.removePlayerFromTeam(target.getScoreboardName());
            target.setHealth(0); c.assertFalse(s.assignPlayerTarget(owner, target), "A dead target is protected"); target.setHealth(target.getMaxHealth());
            owner.setGameMode(GameType.SPECTATOR); c.assertFalse(s.assignPlayerTarget(owner, target), "Spectator owner cannot activate an attack"); owner.setGameMode(GameType.SURVIVAL);
            s.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(owner.getGameProfile()));
            c.assertFalse(s.assignPlayerTarget(owner, target), "Revoked owner permissions prevent direct activation");
        } finally {
            c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); scoreboard.removePlayerTeam(team);
            s.server.getPlayerList().op(new net.minecraft.server.players.NameAndId(owner.getGameProfile()), java.util.Optional.of(LevelBasedPermissionSet.OWNER), java.util.Optional.of(false));
            cleanup(s, owner); cleanup(s, target);
        }
        c.succeed();
    }
    @GameTest public void playerTargetExpiresWithoutPersistenceOrAutomaticResume(GameTestHelper c) throws Exception {
        var owner = player(c, "hive-expiry-owner"); var target = player(c, "hive-expiry-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        var dir = Files.createTempDirectory("infinity-hive-expiry-"); var clock = new ActionClock(); boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPos(owner.position().add(1, 0, 0)); target.setPos(owner.position().add(0, 0, 5));
            var isolated = new AgentCompanions(s.server, dir.resolve("roster.json"), clock); var record = s.owned(owner, "fighter");
            isolated.data.agents.put(record.getKey(), record.getValue()); isolated.loaded.put(helper.getUUID(), helper); isolated.save();
            c.assertTrue(isolated.assignPlayerTarget(owner, target), "Runtime order is active before expiry");
            isolated.control(helper, record.getValue(), 20); c.assertTrue(helper.getTarget() == target, "Order selects the player before expiry");
            c.assertFalse(Files.readString(isolated.file).contains(target.getStringUUID()), "Player attack order is not written to the roster");
            c.assertTrue(new AgentCompanions(s.server, isolated.file, clock).playerTargets.isEmpty(), "Reload cannot replay a prior player order");
            clock.advance(AgentCompanions.PLAYER_TARGET_LIFETIME_MS); isolated.control(helper, record.getValue(), 25);
            c.assertTrue(isolated.playerTargets.isEmpty() && helper.getTarget() == null, "Five-minute deadline clears target and movement");
            isolated.control(helper, record.getValue(), 30); c.assertTrue(helper.getTarget() == null, "An expired order does not resume on later ticks");
            c.assertTrue(isolated.assignPlayerTarget(owner, target), "A new reviewed activation can begin after expiry");
            clock.advance(-1); isolated.control(helper, record.getValue(), 35);
            c.assertTrue(isolated.playerTargets.isEmpty() && helper.getTarget() == null, "Clock rollback clears an order instead of extending its lifetime");
        } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); removeDirectory(dir); }
        c.succeed();
    }
    @GameTest public void playerCeasefireAndStayImmediatelyClearCombatOrders(GameTestHelper c) {
        var owner = player(c, "hive-stop-owner"); var target = player(c, "hive-stop-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.ULTIMATE_FINALS);
            helper.setPos(owner.position().add(1, 0, 0)); target.setPos(owner.position().add(0, 0, 5));
            c.assertTrue(s.assignPlayerTarget(owner, target), "Starts a reviewed target"); s.control(helper, s.owned(owner, "fighter").getValue(), 20);
            c.assertTrue(helper.getTarget() == target, "Order is selected before ceasefire"); s.ceasefire(owner);
            c.assertTrue(helper.getTarget() == null && helper.getNavigation().isDone(), "Ceasefire immediately clears targeting and navigation");
            c.assertTrue(s.playerTargetStatus(owner).equals("Player target: none."), "Status confirms no active player order");
            helper.setTarget(target); c.assertFalse(helper.doHurtTarget(c.getLevel(), target), "Forced attacks cannot bypass ceasefire");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Another attack needs another reviewed activation"); s.mode(owner, "fighter", AgentCompanions.Mode.STAY);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()) && helper.getTarget() == null, "Stopping the only aggressive helper clears its order");
            s.mode(owner, "fighter", AgentCompanions.Mode.FOLLOW); s.control(helper, s.owned(owner, "fighter").getValue(), 25);
            c.assertTrue(helper.getTarget() == null, "Leaving Stay cannot revive an old order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "New explicit activation succeeds after Stay"); s.profile(owner, "fighter", AgentCompanions.Profile.API);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "Changing the only aggressive helper to a passive profile clears the order");
        } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
        c.succeed();
    }
    @GameTest public void playerTargetDeathRespawnAndLogoutRequireANewOrder(GameTestHelper c) {
        var owner = player(c, "hive-life-owner"); var target = player(c, "hive-life-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPos(owner.position().add(1, 0, 0)); target.setPos(owner.position().add(0, 0, 5));
            c.assertTrue(s.assignPlayerTarget(owner, target), "Player order begins for current life"); s.control(helper, s.owned(owner, "fighter").getValue(), 20);
            target.hurtServer(c.getLevel(), target.damageSources().genericKill(), Float.MAX_VALUE);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()) && helper.getTarget() == null, "Actual target death immediately halts the whole order");
            UUID oldUuid = target.getUUID(); target = respawnLoadedPlayer(target);
            target.setGameMode(GameType.SURVIVAL); target.teleportTo(c.getLevel(), owner.getX(), owner.getY(), owner.getZ() + 5, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(target);
            c.assertValueEqual(target.getUUID(), oldUuid, "Respawn retains account UUID but creates a new life");
            s.control(helper, s.owned(owner, "fighter").getValue(), 25); c.assertTrue(helper.getTarget() == null, "A respawned player never inherits the previous-life order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Respawn requires a fresh explicit reviewed assignment; eligibility=" + s.targetEligibility(owner, target)
                + "; targetAlive=" + target.isAlive() + "; targetRemoved=" + target.isRemoved() + "; targetConnected=" + !target.hasDisconnected());
            s.control(helper, s.owned(owner, "fighter").getValue(), 30);
            s.server.getPlayerList().remove(target); c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()) && helper.getTarget() == null, "Target logout immediately clears the order");
        } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); if (s.server.getPlayerList().getPlayer(target.getUUID()) == target) cleanup(s, target); }
        c.succeed();
    }
    @GameTest public void playerTargetCannotResumeAfterPermissionDimensionOrLeashChanges(GameTestHelper c) {
        var owner = player(c, "hive-range-owner"); var target = player(c, "hive-range-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPos(owner.position().add(1, 0, 0)); Vec3 targetPosition = owner.position().add(0, 0, 5); target.setPos(targetPosition);
            c.assertTrue(s.assignPlayerTarget(owner, target), "Current owner permissions allow exact activation");
            s.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(owner.getGameProfile()));
            c.assertFalse(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(helper)), "Permission loss blocks damage before the next control tick");
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "Permission loss consumes the order");
            s.server.getPlayerList().op(new net.minecraft.server.players.NameAndId(owner.getGameProfile()), java.util.Optional.of(LevelBasedPermissionSet.OWNER), java.util.Optional.of(false));
            s.control(helper, s.owned(owner, "fighter").getValue(), 20); c.assertTrue(helper.getTarget() == null, "Restored OP4 does not revive a prior order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation before dimension change");
            target.teleportTo(s.server.getLevel(Level.NETHER), 0, 100, 0, Set.of(), 0, 0, true);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()) && helper.getTarget() == null, "Target changing dimension immediately clears the shared order");
            target.teleportTo(c.getLevel(), targetPosition.x, targetPosition.y, targetPosition.z, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(target);
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation after target returns");
            Vec3 ownerPosition = owner.position(); owner.teleportTo(s.server.getLevel(Level.NETHER), 0, 100, 0, Set.of(), 0, 0, true);
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "Owner dimension change clears the order"); owner.teleportTo(c.getLevel(), ownerPosition.x, ownerPosition.y, ownerPosition.z, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(owner);
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation before target moves beyond the owner leash"); target.setPos(owner.position().add(49, 0, 0)); s.tick();
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "Owner-target separation beyond 48 blocks clears the order"); target.setPos(targetPosition);
            s.control(helper, s.owned(owner, "fighter").getValue(), 25); c.assertTrue(helper.getTarget() == null, "A target returning within range cannot revive a consumed order");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Fresh activation before owner death"); owner.hurtServer(c.getLevel(), owner.damageSources().genericKill(), Float.MAX_VALUE);
            c.assertFalse(owner.isAlive(), "The loaded owner actually died from server damage");
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "Actual owner death immediately clears the order");
            owner = respawnLoadedPlayer(owner);
            owner.setGameMode(GameType.SURVIVAL); owner.teleportTo(c.getLevel(), ownerPosition.x, ownerPosition.y, ownerPosition.z, Set.of(), 0, 0, true);
            acknowledgeLoadedPlayer(owner);
            s.control(helper, s.owned(owner, "fighter").getValue(), 30); c.assertTrue(helper.getTarget() == null, "Respawned owner cannot restore the previous-life combat order");
        } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
        c.succeed();
    }
    @GameTest public void playerOrdersKeepPursuitBeyondSwingRangeAndGuardsWaitInsideTheirLeash(GameTestHelper c) {
        var owner = player(c, "hive-pursuit-owner"); var target = player(c, "hive-pursuit-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        try {
            c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            helper.setPos(owner.position().add(1, 0, 0)); target.setPos(owner.position().add(0, 0, 5));
            c.assertTrue(s.assignPlayerTarget(owner, target), "Reviewed pursuit begins nearby");
            target.setPos(owner.position().add(0, 0, 30)); s.control(helper, s.owned(owner, "fighter").getValue(), 20);
            c.assertTrue(s.targetEligibility(owner, target) == null, "A pending proposal stays eligible beyond the helper's 24-block damage range");
            c.assertTrue(s.validPlayerTarget(owner) != null && helper.getTarget() == target, "An active follow order keeps pursuing within the owner's 48-block range");
            c.assertFalse(AgentCompanions.allowDamage(target, target.damageSources().mobAttack(helper)), "Pursuit never permits remote damage beyond 24 blocks");
            float health = target.getHealth(); c.assertFalse(helper.doHurtTarget(c.getLevel(), target), "A forced ranged swing fails the independent damage gate");
            c.assertValueEqual(target.getHealth(), health, "Distant pursuit causes no remote damage");
            s.mode(owner, "fighter", AgentCompanions.Mode.GUARD); s.control(helper, s.owned(owner, "fighter").getValue(), 25);
            c.assertTrue(s.validPlayerTarget(owner) != null && helper.getTarget() == null, "Guard retains its order while waiting for the approved player outside its 14-block anchor range");
            c.assertTrue(helper.getNavigation().isDone(), "A guard at its anchor does not chase beyond its guard leash");
            target.setPos(helper.position().add(0, 0, 5)); s.control(helper, s.owned(owner, "fighter").getValue(), 30);
            c.assertTrue(helper.getTarget() == target, "The same still-valid guard order resumes when the approved player returns inside the guard range");
            helper.setPos(helper.position().add(15, 0, 0)); s.tick();
            c.assertTrue(!s.playerTargets.containsKey(owner.getUUID()), "An order cancels when its only compatible helper leaves its own movement leash");
        } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); }
        c.succeed();
    }
    @GameTest(structure="convergence_tests:combat_arena", maxTicks = 80) public void approvedPlayerPursuitNavigatesAroundWallsButCannotStrikeThroughThem(GameTestHelper c) {
        var owner = player(c, "hive-corner-owner"); var target = player(c, "hive-corner-target"); var s = AgentCompanions.get(c.getLevel().getServer());
        boolean pvp = c.getLevel().getGameRules().get(GameRules.PVP);
        // This asynchronous arena is larger than the framework's empty structure.
        // Isolate it from neighboring test placement/cleanup and explicitly tick it.
        BlockPos feet=c.absolutePos(new BlockPos(16,20,16));
        for(BlockPos at:BlockPos.betweenClosed(feet.offset(-7,-1,-7),feet.offset(7,4,7)))
            c.getLevel().setBlockAndUpdate(at,at.getY()==feet.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        var tickets=new ArrayList<net.minecraft.world.level.ChunkPos>();
        var ticket=new net.minecraft.server.level.TicketType(9000,net.minecraft.server.level.TicketType.FLAG_LOADING|net.minecraft.server.level.TicketType.FLAG_SIMULATION|net.minecraft.server.level.TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
        for(int x=(feet.getX()-7)>>4;x<=(feet.getX()+7)>>4;x++)for(int z=(feet.getZ()-7)>>4;z<=(feet.getZ()+7)>>4;z++){
            var chunk=new net.minecraft.world.level.ChunkPos(x,z);tickets.add(chunk);c.getLevel().getChunkSource().addTicketWithRadius(ticket,chunk,2);
        }
        Runnable releaseTickets=()->{for(var chunk:tickets)c.getLevel().getChunkSource().removeTicketWithRadius(ticket,chunk,2);};
        Vec3 center=Vec3.atBottomCenterOf(feet);
        owner.setPos(center.add(0, 0, -5)); target.setPos(center.add(3, 0, 0));
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); target.setHealth(100);
        c.getLevel().getGameRules().set(GameRules.PVP, true, s.server); var helper = golem(s, owner, "fighter"); s.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
        helper.setPos(center.add(-3, 0, 0)); helper.setOnGround(true);
        BlockPos wall = BlockPos.containing(center);
        for (BlockPos at : BlockPos.betweenClosed(wall.offset(0, 0, -1), wall.offset(0, 3, 1))) c.getLevel().setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
        double initialDistance = helper.distanceToSqr(target);
        try {
            c.assertFalse(helper.getSensing().hasLineOfSight(target), "A solid wall actually blocks the golem's initial line of sight");
            c.assertTrue(s.assignPlayerTarget(owner, target), "Exact reviewed identity authorizes pursuit around an obstruction");
            s.control(helper, s.owned(owner, "fighter").getValue(), s.server.getTickCount());
            c.assertTrue(helper.getTarget() == target && !helper.getNavigation().isDone(), "Approved pursuit retains its target and starts real navigation without line of sight");
            float health = target.getHealth(); c.assertFalse(helper.doHurtTarget(c.getLevel(), target), "The independent damage gate rejects a forced swing through the wall");
            c.assertValueEqual(target.getHealth(), health, "Occluded pursuit cannot damage the player through terrain");
        } catch (RuntimeException failure) {
            c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); releaseTickets.run(); throw failure;
        }
        java.util.function.Supplier<String> pursuitState = () -> "eligibility=" + s.targetEligibility(owner, target)
            + "; pvp=" + c.getLevel().getGameRules().get(GameRules.PVP)
            + "; owner=" + owner.position() + "/" + owner.getHealth() + "/" + owner.gameMode()
            + "; target=" + target.position() + "/" + target.getHealth() + "/" + target.gameMode()
            + "; helper=" + helper.position() + "/" + helper.getHealth() + "/removed=" + helper.isRemoved()
            + "; loaded=" + (s.loaded.get(helper.getUUID()) == helper)
            + "; pause=" + (s.data.agents.containsKey(helper.getStringUUID()) ? s.pauseReason(owner, helper, s.data.agents.get(helper.getStringUUID())) : "missing record");
        String[] firstLost = {null};
        for (int tick = 1; tick < 65; tick++) {
            final int at = tick;
            c.runAtTickTime(tick, () -> {if(firstLost[0] == null && !s.playerTargets.containsKey(owner.getUUID()))firstLost[0] = "tick=" + at + "; " + pursuitState.get();});
        }
        c.runAtTickTime(65, () -> {
            try {
                c.assertTrue(s.validPlayerTarget(owner) != null, "Normal navigation around a wall does not consume the approved order; first lost: " + firstLost[0] + "; final: " + pursuitState.get());
                c.assertTrue(helper.distanceToSqr(target) < initialDistance - 1, "Server ticks actually move the golem around the obstruction toward the approved player");
                c.assertTrue(helper.getSensing().hasLineOfSight(target), "The golem reaches a clear sight line after navigating around the corner");
            } finally { c.getLevel().getGameRules().set(GameRules.PVP, pvp, s.server); cleanup(s, owner); cleanup(s, target); releaseTickets.run(); }
            c.succeed();
        });
    }
    @GameTest public void flankSlotsOnlyCountNearbyPeersEngagingTheSameTarget(GameTestHelper c) {
        var owner = player(c, "hive-flank-owner"); var s = AgentCompanions.get(c.getLevel().getServer());
        var focus = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND); var alternative = EntityTypes.ZOMBIE.create(c.getLevel(), EntitySpawnReason.COMMAND);
        try {
            var first = golem(s, owner, "alpha"); var second = golem(s, owner, "beta"); var distant = golem(s, owner, "distant"); var otherFight = golem(s, owner, "other");
            for (String name : new String[]{"alpha", "beta", "distant", "other"}) s.profile(owner, name, AgentCompanions.Profile.ULTIMATE_FINALS);
            first.setPos(owner.position().add(-3, 0, 0)); second.setPos(owner.position().add(3, 0, 0));
            distant.setPos(owner.position().add(30, 0, 0)); distant.setNoGravity(true); s.mode(owner, "distant", AgentCompanions.Mode.GUARD);
            otherFight.setPos(owner.position().add(4, 0, 5));
            focus.setPos(owner.position().add(0, 0, 4)); focus.setNoAi(true); c.getLevel().addFreshEntity(focus);
            alternative.setPos(owner.position().add(5, 0, 5)); alternative.setNoAi(true); c.getLevel().addFreshEntity(alternative);
            first.setTarget(focus); second.setTarget(focus); distant.setTarget(focus); otherFight.setTarget(alternative);
            var left = s.flankPoint(first, s.owned(owner, "alpha").getValue(), owner, focus); var right = s.flankPoint(second, s.owned(owner, "beta").getValue(), owner, focus);
            c.assertTrue(left != null && right != null, "The two actual combat peers have clear flanking positions");
            c.assertTrue(left.add(right).scale(.5).distanceToSqr(focus.position()) < .000001, "Two engaged peers receive opposite angles despite distant guards and another fight");
            second.setTarget(alternative);
            c.assertTrue(s.flankPoint(first, s.owned(owner, "alpha").getValue(), owner, focus) == null, "A lone engaged helper uses direct pursuit rather than a slot reserved for absent combat peers");
        } finally { focus.discard(); alternative.discard(); cleanup(s, owner); }
        c.succeed();
    }
    @GameTest public void helperActionsRequireBothLiveApprovalsAndNeverReplay(GameTestHelper c) throws Exception {
        var p = player(c, "action-owner"); var companions = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-action-test-");
        var dispatched = new ArrayList<String>(); var clock = new ActionClock();
        try {
            var queue = new AgentActions(companions.server, dir.resolve("actions.json"), clock, dispatched::add);
            queue.suggest(p, "make it day");
            String id = queue.data.proposals.keySet().iterator().next();
            c.assertTrue(dispatched.isEmpty(), "Proposing cannot dispatch a command");
            queue.codexApprove(companions.server.createCommandSourceStack(), id);
            c.assertTrue(dispatched.isEmpty(), "Console review alone cannot dispatch without owner approval");
            queue.ownerApprove(p, id);
            c.assertTrue(dispatched.isEmpty(), "Owner approval alone cannot dispatch a command");
            c.assertValueEqual(queue.data.proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Owner approval is durable without automatic execution");
            var reloaded = new AgentActions(companions.server, queue.file, clock, dispatched::add);
            c.assertTrue(dispatched.isEmpty(), "Restart does not execute a stored owner approval");
            reloaded.codexApprove(companions.server.createCommandSourceStack(), id);
            c.assertValueEqual(dispatched, java.util.List.of("time set day"), "Live console review dispatches exactly the stored allowlisted command");
            reloaded.codexApprove(companions.server.createCommandSourceStack(), id);
            c.assertValueEqual(dispatched.size(), 1, "Consumed approval cannot replay");
            var afterRestart = new AgentActions(companions.server, queue.file, clock, dispatched::add);
            afterRestart.codexApprove(companions.server.createCommandSourceStack(), id);
            c.assertValueEqual(dispatched.size(), 1, "Consumed approval remains terminal after restart");
        } finally { cleanup(companions, p); removeDirectory(dir); }
        c.succeed();
    }
    @GameTest public void helperActionsExpireAndRejectOtherOwnersAndArbitraryCommands(GameTestHelper c) throws Exception {
        var p = player(c, "action-first"); var other = player(c, "action-other");
        var companions = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-action-expiry-");
        var dispatched = new ArrayList<String>(); var clock = new ActionClock();
        try {
            var queue = new AgentActions(companions.server, dir.resolve("actions.json"), clock, dispatched::add);
            queue.suggest(p, "set night");
            String id = queue.data.proposals.keySet().iterator().next();
            queue.ownerApprove(other, id); queue.cancel(other, id);
            c.assertValueEqual(queue.data.proposals.get(id).state, AgentActions.State.PENDING, "Another OP4 cannot approve or cancel an owner's proposal");
            c.assertTrue(AgentActions.Action.fromRequest("set day; op attacker") == null, "Prompt text cannot become arbitrary commands");
            queue.suggest(p, "set day; op attacker");
            c.assertValueEqual(queue.data.proposals.size(), 1, "Unsupported action does not enter the queue");
            clock.advance(AgentActions.LIFETIME_MS);
            queue.ownerApprove(p, id);
            c.assertValueEqual(queue.data.proposals.get(id).state, AgentActions.State.EXPIRED, "Expired proposal cannot gain owner approval");
            queue.codexApprove(companions.server.createCommandSourceStack(), id);
            c.assertTrue(dispatched.isEmpty(), "Expired proposal cannot dispatch");
        } finally { cleanup(companions, p); cleanup(companions, other); removeDirectory(dir); }
        c.succeed();
    }
    @GameTest public void helperActionsCodexGateAcceptsOnlyLocalConsole(GameTestHelper c) throws Exception {
        var p = player(c, "action-console"); var companions = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-action-console-");
        var dispatched = new ArrayList<String>();
        try {
            var queue = new AgentActions(companions.server, dir.resolve("actions.json"), new ActionClock(), dispatched::add);
            queue.suggest(p, "clear weather"); String id = queue.data.proposals.keySet().iterator().next(); queue.ownerApprove(p, id);
            var root = companions.server.getCommands().getDispatcher().getRoot().getChild("agent-codex-approve");
            var console = companions.server.createCommandSourceStack();
            var otherOutput = console.withSource(CommandSource.NULL);
            c.assertTrue(root.canUse(console), "Real local server console can enter final review");
            c.assertFalse(root.canUse(p.createCommandSourceStack()), "Even OP4 players cannot enter Codex review command");
            c.assertFalse(root.canUse(otherOutput), "RCON and command-block style outputs cannot enter local-console review");
            var scheduledFunction = console.withPermission(LevelBasedPermissionSet.GAMEMASTER).withSuppressedOutput();
            c.assertFalse(root.canUse(scheduledFunction), "Scheduled datapack functions cannot impersonate the console");
            c.assertFalse(root.canUse(console.withSuppressedOutput()), "A silent derived source cannot impersonate a direct console line");
            c.assertFalse(root.canUse(companions.server.getFunctions().getGameLoopSender()),
                "The actual scheduled function source cannot enter Codex review");
            queue.codexApprove(p.createCommandSourceStack(), id); queue.codexApprove(otherOutput, id); queue.codexApprove(scheduledFunction, id);
            c.assertTrue(dispatched.isEmpty(), "Non-console sources cannot dispatch despite owner approval");
            c.assertValueEqual(queue.data.proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Rejected sources cannot consume approval");
        } finally { cleanup(companions, p); removeDirectory(dir); }
        c.succeed();
    }
}
