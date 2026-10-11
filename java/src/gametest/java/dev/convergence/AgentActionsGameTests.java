package dev.convergence;

import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** A named player target is inert until both real approval gates have passed. */
public class AgentActionsGameTests {
    static final class ActionClock extends Clock {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long millis) { now = now.plusMillis(millis); }
    }

    private ServerPlayer player(GameTestHelper c, GameProfile profile) {
        var data = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var p = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, data.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, data);
        p.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        operator(p, LevelBasedPermissionSet.OWNER);
        p.setGameMode(GameType.SURVIVAL); p.setNoGravity(true);
        return p;
    }

    private void operator(ServerPlayer p, LevelBasedPermissionSet permissions) {
        var manager = p.level().getServer().getPlayerList();
        var entry = new net.minecraft.server.players.NameAndId(p.getGameProfile());
        manager.deop(entry); manager.op(entry, java.util.Optional.of(permissions), java.util.Optional.of(false));
    }

    final class Fixture implements AutoCloseable {
        final GameTestHelper context;
        final AgentCompanions core;
        final Path directory;
        final ActionClock clock = new ActionClock();
        final List<String> commands = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        final AgentActions queue;
        final ServerPlayer owner, target;
        final IronGolem helper;
        final boolean pvp;

        Fixture(GameTestHelper c, String prefix) throws Exception {
            context = c; core = AgentCompanions.get(c.getLevel().getServer());
            pvp = c.getLevel().getGameRules().get(GameRules.PVP);
            c.getLevel().getGameRules().set(GameRules.PVP, true, core.server);
            BlockPos feet = c.absolutePos(new BlockPos(3, 20, 3));
            for (BlockPos at : BlockPos.betweenClosed(feet.offset(-7, -1, -7), feet.offset(7, 4, 7)))
                c.getLevel().setBlockAndUpdate(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            owner = add(prefix + "Owner"); target = add(prefix + "Target");
            owner.setPos(Vec3.atBottomCenterOf(feet)); target.setPos(owner.position().add(4, 0, 0));
            core.spawn(owner, "fighter");
            var entry = core.owned(owner, "fighter");
            if (entry == null) throw new IllegalStateException("No native helper could be created for target review");
            helper = core.loaded.get(UUID.fromString(entry.getKey())); helper.setPos(owner.position().add(-2, 0, 0));
            core.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            directory = Files.createTempDirectory("infinity-target-review-");
            queue = new AgentActions(core.server, directory.resolve("actions.json"), clock, commands::add);
        }

        ServerPlayer add(String name) {
            return add(new GameProfile(UUID.randomUUID(), name));
        }

        ServerPlayer add(GameProfile profile) {
            var p = player(context, profile); players.add(p); return p;
        }

        String propose(ServerPlayer p, ServerPlayer victim) {
            Set<String> before = Set.copyOf(queue.data.proposals.keySet()); queue.target(p, victim);
            return queue.data.proposals.keySet().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
        }

        void control() { core.control(helper, core.owned(owner, "fighter").getValue(), core.server.getTickCount()); }

        public void close() throws Exception {
            cWorld().getGameRules().set(GameRules.PVP, pvp, core.server);
            for (var p : players) {
                core.ceasefire(p);
                var names = core.data.agents.values().stream().filter(a -> a.owner().equals(p.getStringUUID())).map(AgentCompanions.Agent::name).toList();
                for (String name : names) core.dismiss(p, name);
                core.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(p.getGameProfile()));
                if (core.server.getPlayerList().getPlayer(p.getUUID()) == p) core.server.getPlayerList().remove(p);
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }

        ServerLevel cWorld() { return context.getLevel(); }
    }

    @GameTest public void playerTargetNeedsBothApprovalsAndCannotReplay(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "dual")) {
            var global = AgentActions.get(f.core.server);
            int pending = global.data.proposals.size();
            var root = f.core.server.getCommands().getDispatcher().getRoot().getChild("agent");
            c.assertTrue(root.getChild("target") != null, "The real player-target command is registered");
            try {
                f.core.server.getCommands().getDispatcher().execute("agent target @r", f.owner.createCommandSourceStack());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                // A literal string argument may reject '@' during parsing,
                // before the callback can reject a selector as a player name.
            }
            c.assertValueEqual(global.data.proposals.size(), pending, "The registered command rejects random player selectors");
            f.core.server.getCommands().getDispatcher().execute("agent target \"@r\"", f.owner.createCommandSourceStack());
            c.assertValueEqual(global.data.proposals.size(), pending, "Quoting a selector cannot bypass exact online-name validation");
            String id = f.propose(f.owner, f.target);
            var proposal = f.queue.data.proposals.get(id);
            c.assertValueEqual(proposal.targetUuid, f.target.getStringUUID(), "Proposal captures the exact target UUID");
            c.assertValueEqual(proposal.targetName, f.target.getGameProfile().name(), "Proposal captures the exact review name");
            c.assertTrue(proposal.description().contains(proposal.targetSession) && proposal.description().contains(proposal.targetDimension),
                "Both review previews include the session token and dimension");
            f.control(); c.assertTrue(f.helper.getTarget() == null, "A proposed target cannot start combat");
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(proposal.state, AgentActions.State.PENDING, "Console cannot skip the owner's approval");
            f.queue.ownerApprove(f.owner, id); f.control();
            c.assertTrue(f.helper.getTarget() == null, "Owner approval alone cannot start combat");
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id); f.control();
            c.assertValueEqual(proposal.state, AgentActions.State.DISPATCHED, "Final approval consumes the exact proposal before activation");
            c.assertTrue(f.helper.getTarget() == f.target, "The approved helper attacks the exact named player");
            c.assertFalse(AgentCompanions.allowDamage(f.target, f.target.damageSources().mobAttack(f.helper)), "Both approvals permit pursuit but not remote melee damage");
            f.target.setPos(f.helper.position().add(1, 0, 0));
            c.assertTrue(f.helper.isWithinMeleeAttackRange(f.target) && f.helper.getSensing().hasLineOfSight(f.target), "Approved damage fixture is in melee range with clear sight");
            c.assertTrue(AgentCompanions.allowDamage(f.target, f.target.damageSources().mobAttack(f.helper)), "Only approved combat may pass the damage gate");
            c.assertTrue(f.commands.isEmpty(), "Player-target activation never interpolates a server command");
            f.queue.ceasefire(f.owner); f.queue.codexApprove(f.core.server.createCommandSourceStack(), id); f.control();
            c.assertTrue(f.helper.getTarget() == null && f.core.validPlayerTarget(f.owner) == null, "Consumed approval cannot restart combat after a ceasefire");
        } catch (Exception | Error failure) {
            failure.printStackTrace();
            throw failure;
        }
        c.succeed();
    }

    @GameTest public void playerTargetRejectsOtherOwnersAndNonlocalApprovalSources(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "source")) {
            var other = f.add("sourceOther"); other.setPos(f.owner.position().add(0, 0, 5));
            String id = f.propose(f.owner, f.target); var proposal = f.queue.data.proposals.get(id);
            f.queue.ownerApprove(other, id); f.queue.cancel(other, id);
            c.assertValueEqual(proposal.state, AgentActions.State.PENDING, "Another OP4 cannot approve or cancel this target");
            f.queue.ownerApprove(f.owner, id);
            var console = f.core.server.createCommandSourceStack();
            for (var source : List.of(f.owner.createCommandSourceStack(), console.withSource(CommandSource.NULL), console.withSuppressedOutput(),
                f.core.server.getFunctions().getGameLoopSender())) {
                f.queue.codexApprove(source, id);
                c.assertValueEqual(proposal.state, AgentActions.State.OWNER_APPROVED, "Player, remote, silent, and scheduled sources cannot act as Codex review");
                c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "Rejected approval sources never create a live player order");
            }
            c.assertTrue(AgentActions.Action.fromRequest("attack sourceTarget") == null, "AI request text cannot silently create a player-target proposal");
            c.assertTrue(AgentActions.Action.fromRequest("set day; agent target sourceTarget") == null, "No arbitrary command text enters the fixed-action dispatcher");
        }
        c.succeed();
    }

    @GameTest public void reconnectWithSameNameAndUuidCannotInheritTargetApproval(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "session")) {
            String id = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, id);
            var profile = f.target.getGameProfile(); f.core.server.getPlayerList().remove(f.target);
            var replacement = f.add(profile); replacement.setPos(f.owner.position().add(4, 0, 0));
            c.assertTrue(f.core.targetEligibility(f.owner, replacement) == null, "Replacement session is independently eligible for a new request");
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "The old proposal is cancelled instead of following a reused UUID or name");
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "A new login never inherits the previous target's approvals");
        }
        c.succeed();
    }

    @GameTest public void pendingTargetsCancelAfterDeathOrDimensionChange(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "life")) {
            String dead = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, dead);
            f.target.setHealth(0); f.queue.expire(); f.target.setHealth(f.target.getMaxHealth());
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), dead);
            c.assertValueEqual(f.queue.data.proposals.get(dead).state, AgentActions.State.CANCELLED, "A death cannot be undone to reuse an old combat approval");
            String moved = f.propose(f.owner, f.target);
            ServerLevel original = f.target.level();
            f.target.setServerLevel(GameModes.world(f.core.server, GameModes.Mode.HARDCORE)); f.queue.expire(); f.target.setServerLevel(original);
            f.queue.ownerApprove(f.owner, moved);
            c.assertValueEqual(f.queue.data.proposals.get(moved).state, AgentActions.State.CANCELLED, "Returning from another dimension cannot resurrect the old proposal");
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "Life and world invalidations never execute combat");
        }
        c.succeed();
    }

    @GameTest public void playerTargetRechecksModePvpAndFriendlyFireAtApproval(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "rules")) {
            for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
                String id = f.propose(f.owner, f.target); f.target.setGameMode(mode);
                f.queue.ownerApprove(f.owner, id); f.target.setGameMode(GameType.SURVIVAL);
                c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Changing target mode cancels rather than suspends its proposal");
            }
            String pvp = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, pvp);
            c.getLevel().getGameRules().set(GameRules.PVP, false, f.core.server);
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), pvp);
            c.assertValueEqual(f.queue.data.proposals.get(pvp).state, AgentActions.State.CANCELLED, "World PvP disabling invalidates the second approval");
            c.getLevel().getGameRules().set(GameRules.PVP, true, f.core.server);
            String teamId = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, teamId);
            var board = f.core.server.getScoreboard(); var team = board.addPlayerTeam("pvp-" + UUID.randomUUID().toString().substring(0, 8));
            try {
                team.setAllowFriendlyFire(false); board.addPlayerToTeam(f.owner.getScoreboardName(), team); board.addPlayerToTeam(f.target.getScoreboardName(), team);
                f.queue.codexApprove(f.core.server.createCommandSourceStack(), teamId);
                c.assertValueEqual(f.queue.data.proposals.get(teamId).state, AgentActions.State.CANCELLED, "A friendly-fire team change cancels an approved proposal");
            } finally { board.removePlayerTeam(team); }
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "No forbidden rule combination reaches the combat controller");
        }
        c.succeed();
    }

    @GameTest public void proposalsNeedOriginalOp4AndAnActiveAggressiveHelper(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "ready")) {
            String noPermission = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, noPermission);
            operator(f.owner, LevelBasedPermissionSet.ADMIN); f.queue.codexApprove(f.core.server.createCommandSourceStack(), noPermission);
            c.assertValueEqual(f.queue.data.proposals.get(noPermission).state, AgentActions.State.CANCELLED, "DeOP to level three cancels the player's proposal");
            operator(f.owner, LevelBasedPermissionSet.OWNER);
            for (AgentCompanions.Profile profile : List.of(AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.DEBUG, AgentCompanions.Profile.CLI, AgentCompanions.Profile.API)) {
                String id = f.propose(f.owner, f.target); f.core.profile(f.owner, "fighter", profile); f.queue.expire();
                c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Only Primitive and Ultimate Finals can keep a target request eligible");
                int before = f.queue.data.proposals.size(); f.queue.target(f.owner, f.target);
                c.assertValueEqual(f.queue.data.proposals.size(), before, "A passive or Regular squad cannot propose player combat");
                f.core.profile(f.owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            }
            String paused = f.propose(f.owner, f.target); f.core.mode(f.owner, "fighter", AgentCompanions.Mode.STAY); f.queue.expire();
            c.assertValueEqual(f.queue.data.proposals.get(paused).state, AgentActions.State.CANCELLED, "Stay cancels the pending player-target proposal");
        }
        c.succeed();
    }

    @GameTest public void targetSnapshotCannotChangeAndRestartNeverResumesCombat(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "snapshot")) {
            String changed = f.propose(f.owner, f.target); var proposal = f.queue.data.proposals.get(changed);
            proposal.targetName = "DifferentPlayer"; f.queue.ownerApprove(f.owner, changed);
            c.assertValueEqual(proposal.state, AgentActions.State.CANCELLED, "Changing a persisted preview cannot retarget its immutable live session");
            String id = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, id);
            var reloaded = new AgentActions(f.core.server, f.queue.file, f.clock, f.commands::add);
            c.assertValueEqual(reloaded.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Restart cancels active target approvals instead of rebuilding sessions from UUIDs");
            reloaded.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null && f.commands.isEmpty(), "Reading durable target history cannot run a command or combat order");
        }
        c.succeed();
    }

    @GameTest public void targetCeasefireIsImmediateOwnerScopedAndExpiryIsTerminal(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "stop")) {
            var other = f.add("stopOther"); other.setPos(f.owner.position().add(0, 0, 4));
            String id = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, id);
            f.queue.ceasefire(other);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Another owner's ceasefire cannot cancel this request");
            f.queue.ceasefire(f.owner);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "The owner's ceasefire cancels approvals without waiting for review");
            String expired = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, expired);
            f.clock.advance(AgentActions.LIFETIME_MS); f.queue.codexApprove(f.core.server.createCommandSourceStack(), expired);
            c.assertValueEqual(f.queue.data.proposals.get(expired).state, AgentActions.State.EXPIRED, "The proposal lifetime is enforced at the final gate");
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "Cancelled and expired requests never establish player combat");
        }
        c.succeed();
    }

    @GameTest public void legacyActionQueueMigratesAndInvalidTargetDataStaysUntouched(GameTestHelper c) throws Exception {
        Path directory = Files.createTempDirectory("infinity-action-validation-");
        try {
            Path file = directory.resolve("actions.json"); var data = new AgentActions.Data(); data.format = 1;
            String id = UUID.randomUUID().toString();
            data.proposals.put(id, new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.DAY, 1));
            Files.writeString(file, CommunityServer.GSON.toJson(data));
            c.assertValueEqual(AgentActions.read(file).format, 2, "Legacy fixed actions migrate without losing their history");
            c.assertValueEqual(AgentActions.read(file).proposals.get(id).action, AgentActions.Action.DAY, "Migration retains the exact allowlisted command");
            data.format = 2; var target = new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.TARGET, 1);
            target.targetUuid = UUID.randomUUID().toString(); target.targetName = "first\nsecond";
            target.targetDimension = "minecraft:overworld"; target.targetSession = UUID.randomUUID().toString(); data.proposals.put(id, target);
            String corrupt = CommunityServer.GSON.toJson(data); Files.writeString(file, corrupt);
            boolean rejected = false; try { AgentActions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Control characters cannot become target review output lines");
            c.assertValueEqual(Files.readString(file), corrupt, "Invalid target queue remains intact for recovery");
            c.assertFalse(AgentActions.validPlayerName("player\u202efake"), "Format control characters are also rejected");
            c.assertFalse(AgentActions.validPlayerName("player\u2028fake"), "Line separators cannot split an exact target preview");
            c.assertFalse(AgentActions.validPlayerName("player\u2029fake"), "Paragraph separators cannot split an exact target preview");
            target.targetName = "ValidPlayer"; target.targetDimension = "minecraft:" + "x".repeat(129);
            c.assertFalse(target.valid(), "Stored dimension identifiers have the same bounded review limit as new proposals");
            String tooLarge = " ".repeat(AgentActions.MAX_BYTES + 1); Files.writeString(file, tooLarge);
            rejected = false; try { AgentActions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "The byte limit is enforced before JSON deserialization");
            c.assertValueEqual(Files.readString(file), tooLarge, "An oversized queue is never overwritten");
        } finally {
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        c.succeed();
    }
}
