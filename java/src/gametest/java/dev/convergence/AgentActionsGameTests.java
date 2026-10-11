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
import net.minecraft.block.Blocks;
import net.minecraft.command.permission.LeveledPermissionPredicate;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;

/** A named player target is inert until both real approval gates have passed. */
public class AgentActionsGameTests {
    static final class ActionClock extends Clock {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long millis) { now = now.plusMillis(millis); }
    }

    private ServerPlayerEntity player(TestContext c, GameProfile profile) {
        var data = net.minecraft.server.network.ConnectedClientData.createDefault(profile, false);
        var p = new ServerPlayerEntity(c.getWorld().getServer(), c.getWorld(), profile, data.syncedOptions());
        var connection = new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, p, data);
        p.networkHandler.onPlayerLoaded(new net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket());
        operator(p, LeveledPermissionPredicate.OWNERS);
        p.changeGameMode(GameMode.SURVIVAL); p.setNoGravity(true);
        return p;
    }

    private void operator(ServerPlayerEntity p, LeveledPermissionPredicate permissions) {
        var manager = p.getEntityWorld().getServer().getPlayerManager();
        var entry = new net.minecraft.server.PlayerConfigEntry(p.getGameProfile());
        manager.removeFromOperators(entry); manager.addToOperators(entry, java.util.Optional.of(permissions), java.util.Optional.of(false));
    }

    final class Fixture implements AutoCloseable {
        final TestContext context;
        final AgentCompanions core;
        final Path directory;
        final ActionClock clock = new ActionClock();
        final List<String> commands = new ArrayList<>();
        final List<ServerPlayerEntity> players = new ArrayList<>();
        final AgentActions queue;
        final ServerPlayerEntity owner, target;
        final IronGolemEntity helper;
        final boolean pvp;

        Fixture(TestContext c, String prefix) throws Exception {
            context = c; core = AgentCompanions.get(c.getWorld().getServer());
            pvp = c.getWorld().getGameRules().getValue(GameRules.PVP);
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, core.server);
            BlockPos feet = c.getAbsolutePos(new BlockPos(3, 20, 3));
            for (BlockPos at : BlockPos.iterate(feet.add(-7, -1, -7), feet.add(7, 4, 7)))
                c.getWorld().setBlockState(at, at.getY() == feet.getY() - 1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
            owner = add(prefix + "Owner"); target = add(prefix + "Target");
            owner.setPosition(Vec3d.ofBottomCenter(feet)); target.setPosition(owner.getEntityPos().add(4, 0, 0));
            core.spawn(owner, "fighter");
            var entry = core.owned(owner, "fighter");
            if (entry == null) throw new IllegalStateException("No native helper could be created for target review");
            helper = core.loaded.get(UUID.fromString(entry.getKey())); helper.setPosition(owner.getEntityPos().add(-2, 0, 0));
            core.profile(owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            directory = Files.createTempDirectory("infinity-target-review-");
            queue = new AgentActions(core.server, directory.resolve("actions.json"), clock, commands::add);
        }

        ServerPlayerEntity add(String name) {
            return add(new GameProfile(UUID.randomUUID(), name));
        }

        ServerPlayerEntity add(GameProfile profile) {
            var p = player(context, profile); players.add(p); return p;
        }

        String propose(ServerPlayerEntity p, ServerPlayerEntity victim) {
            Set<String> before = Set.copyOf(queue.data.proposals.keySet()); queue.target(p, victim);
            return queue.data.proposals.keySet().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
        }

        void control() { core.control(helper, core.owned(owner, "fighter").getValue(), core.server.getTicks()); }

        public void close() throws Exception {
            cWorld().getGameRules().setValue(GameRules.PVP, pvp, core.server);
            for (var p : players) {
                core.ceasefire(p);
                var names = core.data.agents.values().stream().filter(a -> a.owner().equals(p.getUuidAsString())).map(AgentCompanions.Agent::name).toList();
                for (String name : names) core.dismiss(p, name);
                core.server.getPlayerManager().removeFromOperators(new net.minecraft.server.PlayerConfigEntry(p.getGameProfile()));
                if (core.server.getPlayerManager().getPlayer(p.getUuid()) == p) core.server.getPlayerManager().remove(p);
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }

        ServerWorld cWorld() { return context.getWorld(); }
    }

    @GameTest public void playerTargetNeedsBothApprovalsAndCannotReplay(TestContext c) throws Exception {
        try (var f = new Fixture(c, "dual")) {
            var global = AgentActions.get(f.core.server);
            int pending = global.data.proposals.size();
            var root = f.core.server.getCommandManager().getDispatcher().getRoot().getChild("agent");
            c.assertTrue(root.getChild("target") != null, "The real player-target command is registered");
            try {
                f.core.server.getCommandManager().getDispatcher().execute("agent target @r", f.owner.getCommandSource());
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) {
                // A literal string argument may reject '@' during parsing,
                // before the callback can reject a selector as a player name.
            }
            c.assertEquals(global.data.proposals.size(), pending, "The registered command rejects random player selectors");
            f.core.server.getCommandManager().getDispatcher().execute("agent target \"@r\"", f.owner.getCommandSource());
            c.assertEquals(global.data.proposals.size(), pending, "Quoting a selector cannot bypass exact online-name validation");
            String id = f.propose(f.owner, f.target);
            var proposal = f.queue.data.proposals.get(id);
            c.assertEquals(proposal.targetUuid, f.target.getUuidAsString(), "Proposal captures the exact target UUID");
            c.assertEquals(proposal.targetName, f.target.getGameProfile().name(), "Proposal captures the exact review name");
            c.assertTrue(proposal.description().contains(proposal.targetSession) && proposal.description().contains(proposal.targetDimension),
                "Both review previews include the session token and dimension");
            f.control(); c.assertTrue(f.helper.getTarget() == null, "A proposed target cannot start combat");
            f.queue.codexApprove(f.core.server.getCommandSource(), id);
            c.assertEquals(proposal.state, AgentActions.State.PENDING, "Console cannot skip the owner's approval");
            f.queue.ownerApprove(f.owner, id); f.control();
            c.assertTrue(f.helper.getTarget() == null, "Owner approval alone cannot start combat");
            f.queue.codexApprove(f.core.server.getCommandSource(), id); f.control();
            c.assertEquals(proposal.state, AgentActions.State.DISPATCHED, "Final approval consumes the exact proposal before activation");
            c.assertTrue(f.helper.getTarget() == f.target, "The approved helper attacks the exact named player");
            c.assertTrue(AgentCompanions.allowDamage(f.target, f.target.getDamageSources().mobAttack(f.helper)), "Only approved combat may pass the damage gate");
            c.assertTrue(f.commands.isEmpty(), "Player-target activation never interpolates a server command");
            f.queue.ceasefire(f.owner); f.queue.codexApprove(f.core.server.getCommandSource(), id); f.control();
            c.assertTrue(f.helper.getTarget() == null && f.core.validPlayerTarget(f.owner) == null, "Consumed approval cannot restart combat after a ceasefire");
        } catch (Exception | Error failure) {
            failure.printStackTrace();
            throw failure;
        }
        c.complete();
    }

    @GameTest public void playerTargetRejectsOtherOwnersAndNonlocalApprovalSources(TestContext c) throws Exception {
        try (var f = new Fixture(c, "source")) {
            var other = f.add("sourceOther"); other.setPosition(f.owner.getEntityPos().add(0, 0, 5));
            String id = f.propose(f.owner, f.target); var proposal = f.queue.data.proposals.get(id);
            f.queue.ownerApprove(other, id); f.queue.cancel(other, id);
            c.assertEquals(proposal.state, AgentActions.State.PENDING, "Another OP4 cannot approve or cancel this target");
            f.queue.ownerApprove(f.owner, id);
            var console = f.core.server.getCommandSource();
            for (var source : List.of(f.owner.getCommandSource(), console.withOutput(CommandOutput.DUMMY), console.withSilent(),
                f.core.server.getCommandFunctionManager().getScheduledCommandSource())) {
                f.queue.codexApprove(source, id);
                c.assertEquals(proposal.state, AgentActions.State.OWNER_APPROVED, "Player, remote, silent, and scheduled sources cannot act as Codex review");
                c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "Rejected approval sources never create a live player order");
            }
            c.assertTrue(AgentActions.Action.fromRequest("attack sourceTarget") == null, "AI request text cannot silently create a player-target proposal");
            c.assertTrue(AgentActions.Action.fromRequest("set day; agent target sourceTarget") == null, "No arbitrary command text enters the fixed-action dispatcher");
        }
        c.complete();
    }

    @GameTest public void reconnectWithSameNameAndUuidCannotInheritTargetApproval(TestContext c) throws Exception {
        try (var f = new Fixture(c, "session")) {
            String id = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, id);
            var profile = f.target.getGameProfile(); f.core.server.getPlayerManager().remove(f.target);
            var replacement = f.add(profile); replacement.setPosition(f.owner.getEntityPos().add(4, 0, 0));
            c.assertTrue(f.core.targetEligibility(f.owner, replacement) == null, "Replacement session is independently eligible for a new request");
            f.queue.codexApprove(f.core.server.getCommandSource(), id);
            c.assertEquals(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "The old proposal is cancelled instead of following a reused UUID or name");
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "A new login never inherits the previous target's approvals");
        }
        c.complete();
    }

    @GameTest public void pendingTargetsCancelAfterDeathOrDimensionChange(TestContext c) throws Exception {
        try (var f = new Fixture(c, "life")) {
            String dead = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, dead);
            f.target.setHealth(0); f.queue.expire(); f.target.setHealth(f.target.getMaxHealth());
            f.queue.codexApprove(f.core.server.getCommandSource(), dead);
            c.assertEquals(f.queue.data.proposals.get(dead).state, AgentActions.State.CANCELLED, "A death cannot be undone to reuse an old combat approval");
            String moved = f.propose(f.owner, f.target);
            ServerWorld original = f.target.getEntityWorld();
            f.target.setServerWorld(GameModes.world(f.core.server, GameModes.Mode.HARDCORE)); f.queue.expire(); f.target.setServerWorld(original);
            f.queue.ownerApprove(f.owner, moved);
            c.assertEquals(f.queue.data.proposals.get(moved).state, AgentActions.State.CANCELLED, "Returning from another dimension cannot resurrect the old proposal");
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "Life and world invalidations never execute combat");
        }
        c.complete();
    }

    @GameTest public void playerTargetRechecksModePvpAndFriendlyFireAtApproval(TestContext c) throws Exception {
        try (var f = new Fixture(c, "rules")) {
            for (GameMode mode : List.of(GameMode.CREATIVE, GameMode.SPECTATOR)) {
                String id = f.propose(f.owner, f.target); f.target.changeGameMode(mode);
                f.queue.ownerApprove(f.owner, id); f.target.changeGameMode(GameMode.SURVIVAL);
                c.assertEquals(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Changing target mode cancels rather than suspends its proposal");
            }
            String pvp = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, pvp);
            c.getWorld().getGameRules().setValue(GameRules.PVP, false, f.core.server);
            f.queue.codexApprove(f.core.server.getCommandSource(), pvp);
            c.assertEquals(f.queue.data.proposals.get(pvp).state, AgentActions.State.CANCELLED, "World PvP disabling invalidates the second approval");
            c.getWorld().getGameRules().setValue(GameRules.PVP, true, f.core.server);
            String teamId = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, teamId);
            var board = f.core.server.getScoreboard(); var team = board.addTeam("pvp-" + UUID.randomUUID().toString().substring(0, 8));
            try {
                team.setFriendlyFireAllowed(false); board.addScoreHolderToTeam(f.owner.getNameForScoreboard(), team); board.addScoreHolderToTeam(f.target.getNameForScoreboard(), team);
                f.queue.codexApprove(f.core.server.getCommandSource(), teamId);
                c.assertEquals(f.queue.data.proposals.get(teamId).state, AgentActions.State.CANCELLED, "A friendly-fire team change cancels an approved proposal");
            } finally { board.removeTeam(team); }
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "No forbidden rule combination reaches the combat controller");
        }
        c.complete();
    }

    @GameTest public void proposalsNeedOriginalOp4AndAnActiveAggressiveHelper(TestContext c) throws Exception {
        try (var f = new Fixture(c, "ready")) {
            String noPermission = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, noPermission);
            operator(f.owner, LeveledPermissionPredicate.ADMINS); f.queue.codexApprove(f.core.server.getCommandSource(), noPermission);
            c.assertEquals(f.queue.data.proposals.get(noPermission).state, AgentActions.State.CANCELLED, "DeOP to level three cancels the player's proposal");
            operator(f.owner, LeveledPermissionPredicate.OWNERS);
            for (AgentCompanions.Profile profile : List.of(AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.DEBUG, AgentCompanions.Profile.CLI, AgentCompanions.Profile.API)) {
                String id = f.propose(f.owner, f.target); f.core.profile(f.owner, "fighter", profile); f.queue.expire();
                c.assertEquals(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Only Primitive and Ultimate Finals can keep a target request eligible");
                int before = f.queue.data.proposals.size(); f.queue.target(f.owner, f.target);
                c.assertEquals(f.queue.data.proposals.size(), before, "A passive or Regular squad cannot propose player combat");
                f.core.profile(f.owner, "fighter", AgentCompanions.Profile.PRIMITIVE);
            }
            String paused = f.propose(f.owner, f.target); f.core.mode(f.owner, "fighter", AgentCompanions.Mode.STAY); f.queue.expire();
            c.assertEquals(f.queue.data.proposals.get(paused).state, AgentActions.State.CANCELLED, "Stay cancels the pending player-target proposal");
        }
        c.complete();
    }

    @GameTest public void targetSnapshotCannotChangeAndRestartNeverResumesCombat(TestContext c) throws Exception {
        try (var f = new Fixture(c, "snapshot")) {
            String changed = f.propose(f.owner, f.target); var proposal = f.queue.data.proposals.get(changed);
            proposal.targetName = "DifferentPlayer"; f.queue.ownerApprove(f.owner, changed);
            c.assertEquals(proposal.state, AgentActions.State.CANCELLED, "Changing a persisted preview cannot retarget its immutable live session");
            String id = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, id);
            var reloaded = new AgentActions(f.core.server, f.queue.file, f.clock, f.commands::add);
            c.assertEquals(reloaded.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Restart cancels active target approvals instead of rebuilding sessions from UUIDs");
            reloaded.codexApprove(f.core.server.getCommandSource(), id);
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null && f.commands.isEmpty(), "Reading durable target history cannot run a command or combat order");
        }
        c.complete();
    }

    @GameTest public void targetCeasefireIsImmediateOwnerScopedAndExpiryIsTerminal(TestContext c) throws Exception {
        try (var f = new Fixture(c, "stop")) {
            var other = f.add("stopOther"); other.setPosition(f.owner.getEntityPos().add(0, 0, 4));
            String id = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, id);
            f.queue.ceasefire(other);
            c.assertEquals(f.queue.data.proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Another owner's ceasefire cannot cancel this request");
            f.queue.ceasefire(f.owner);
            c.assertEquals(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "The owner's ceasefire cancels approvals without waiting for review");
            String expired = f.propose(f.owner, f.target); f.queue.ownerApprove(f.owner, expired);
            f.clock.advance(AgentActions.LIFETIME_MS); f.queue.codexApprove(f.core.server.getCommandSource(), expired);
            c.assertEquals(f.queue.data.proposals.get(expired).state, AgentActions.State.EXPIRED, "The proposal lifetime is enforced at the final gate");
            c.assertTrue(f.core.validPlayerTarget(f.owner) == null, "Cancelled and expired requests never establish player combat");
        }
        c.complete();
    }

    @GameTest public void legacyActionQueueMigratesAndInvalidTargetDataStaysUntouched(TestContext c) throws Exception {
        Path directory = Files.createTempDirectory("infinity-action-validation-");
        try {
            Path file = directory.resolve("actions.json"); var data = new AgentActions.Data(); data.format = 1;
            String id = UUID.randomUUID().toString();
            data.proposals.put(id, new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.DAY, 1));
            Files.writeString(file, CommunityServer.GSON.toJson(data));
            c.assertEquals(AgentActions.read(file).format, 2, "Legacy fixed actions migrate without losing their history");
            c.assertEquals(AgentActions.read(file).proposals.get(id).action, AgentActions.Action.DAY, "Migration retains the exact allowlisted command");
            data.format = 2; var target = new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.TARGET, 1);
            target.targetUuid = UUID.randomUUID().toString(); target.targetName = "first\nsecond";
            target.targetDimension = "minecraft:overworld"; target.targetSession = UUID.randomUUID().toString(); data.proposals.put(id, target);
            String corrupt = CommunityServer.GSON.toJson(data); Files.writeString(file, corrupt);
            boolean rejected = false; try { AgentActions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Control characters cannot become target review output lines");
            c.assertEquals(Files.readString(file), corrupt, "Invalid target queue remains intact for recovery");
            c.assertFalse(AgentActions.validPlayerName("player\u202efake"), "Format control characters are also rejected");
            c.assertFalse(AgentActions.validPlayerName("player\u2028fake"), "Line separators cannot split an exact target preview");
            c.assertFalse(AgentActions.validPlayerName("player\u2029fake"), "Paragraph separators cannot split an exact target preview");
            target.targetName = "ValidPlayer"; target.targetDimension = "minecraft:" + "x".repeat(129);
            c.assertFalse(target.valid(), "Stored dimension identifiers have the same bounded review limit as new proposals");
            String tooLarge = " ".repeat(AgentActions.MAX_BYTES + 1); Files.writeString(file, tooLarge);
            rejected = false; try { AgentActions.read(file); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "The byte limit is enforced before JSON deserialization");
            c.assertEquals(Files.readString(file), tooLarge, "An oversized queue is never overwritten");
        } finally {
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        c.complete();
    }
}
