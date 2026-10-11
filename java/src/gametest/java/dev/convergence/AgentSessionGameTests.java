package dev.convergence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

/** Real native saved reconnects; no provider requests or manufactured player replacements. */
public class AgentSessionGameTests {
    private static Path temporary(String prefix) {
        try { return Files.createTempDirectory(prefix); }
        catch (IOException failure) { throw new IllegalStateException("Cannot prepare isolated session fixture", failure); }
    }
    private static void removeDirectory(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) { throw new IllegalStateException("Cannot clean isolated session fixture", failure); }
    }
    private static void operator(ServerPlayer player) {
        OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
        player.setGameMode(GameType.SURVIVAL);
    }
    private static void oldDisconnect(NativeSessionFixture.Joined old) {
        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(old.player.connection, old.server);
    }

    private abstract static class HelperScenario extends NativeSessionFixture {
        final AgentCompanions core;
        Joined owner;
        String helperId;
        HelperScenario(GameTestHelper c) { super(c); core = AgentCompanions.get(server); }
        void spawn(String name, AgentCompanions.Profile profile) {
            var world = owner.player.level(); var feet = owner.player.blockPosition();
            // Only already loaded disposable terrain: helper creation must not add tickets.
            for (var at : BlockPos.betweenClosed(feet.offset(-7, -1, -7), feet.offset(7, 4, 7)))
                if (world.hasChunk(at.getX() >> 4, at.getZ() >> 4))
                    world.setBlockAndUpdate(at, (at.getY() == feet.getY() - 1 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
            c.assertValueEqual(core.spawn(owner.player, name), 1, "The actual helper spawn command succeeds");
            var entry = core.owned(owner.player, name);
            c.assertTrue(entry != null, "Native helper creation creates a real saved roster entry");
            helperId = entry.getKey();
            c.assertTrue(helper() != null, "Native helper creation also supplies its actual loaded entity");
            core.profile(owner.player, name, profile);
        }
        IronGolem helper() { return helperId == null ? null : core.loaded.get(UUID.fromString(helperId)); }
        @Override void cleanup() {
            if (owner != null) core.ceasefire(owner.player);
            if (helperId != null) {
                var tracked = AgentChat.TRACKING.get(server); if (tracked != null) tracked.remove(helperId);
                var golem = helper(); core.data.agents.remove(helperId);
                if (golem != null) golem.discard();
                core.loaded.remove(UUID.fromString(helperId)); core.save();
            }
        }
    }

    private static final class CombatScenario extends HelperScenario {
        Joined oldOwner, oldTarget, target;
        AgentActions queue, previousQueue;
        Path directory;
        boolean previousPvp, changedPvp, replacedQueue;
        String disconnectedPending;
        CombatScenario(GameTestHelper c) { super(c); }
        @Override void start() {
            oldOwner = owner = join("agent-owner", 3); operator(owner.player);
            oldTarget = target = join("agent-target", 8); target.player.setGameMode(GameType.SURVIVAL);
            join("agent-observer", 12);
            previousPvp = c.getLevel().getGameRules().get(GameRules.PVP); changedPvp = true;
            c.getLevel().getGameRules().set(GameRules.PVP, true, server);
            later(10, this::initial);
        }
        String propose() {
            Set<String> before = Set.copyOf(queue.data.proposals.keySet()); queue.target(owner.player, target.player);
            String id = queue.data.proposals.keySet().stream().filter(key -> !before.contains(key)).findFirst().orElse(null);
            c.assertTrue(id != null, "The current eligible owner and target create an exact-session proposal"); return id;
        }
        void approve(String id) {
            queue.ownerApprove(owner.player, id); queue.codexApprove(server.createCommandSourceStack(), id);
            c.assertValueEqual(queue.data.proposals.get(id).state, AgentActions.State.DISPATCHED, "Both approvals consume the reviewed proposal");
            c.assertTrue(core.validPlayerTarget(owner.player) != null, "Both approvals create an actual current-session order");
        }
        void control() { core.control(helper(), core.data.agents.get(helperId), server.getTickCount()); }
        void initial() {
            spawn("fighter", AgentCompanions.Profile.PRIMITIVE);
            directory = temporary("infinity-agent-session-");
            queue = new AgentActions(server, directory.resolve("actions.json"), Clock.systemUTC(), command -> {
                c.assertTrue(false, "Player-target reviews never dispatch an arbitrary command");
            });
            previousQueue = AgentActions.INSTANCES.put(server, queue); replacedQueue = true;
            approve(propose()); control();
            c.assertTrue(helper().getTarget() == target.player, "A fully approved native helper pursues the exact player");
            disconnectedPending = propose(); queue.ownerApprove(owner.player, disconnectedPending);
            reconnect(oldOwner, replacement -> { owner = replacement; freshOwner(); });
        }
        void staleOwner() {
            AgentActions.invalidatePlayer(oldOwner.player); core.invalidatePlayer(oldOwner.player);
            c.assertTrue(core.validPlayerTarget(oldOwner.player) == null, "A stale owner cannot read the fresh owner's order");
            core.ceasefire(oldOwner.player); queue.ceasefire(oldOwner.player); oldDisconnect(oldOwner);
        }
        void freshOwner() {
            c.assertTrue(owner.player != oldOwner.player && owner.player.connection != oldOwner.player.connection,
                "Native saved owner login supplies a different exact player and listener");
            c.assertValueEqual(queue.data.proposals.get(disconnectedPending).state, AgentActions.State.CANCELLED,
                "Genuine original-owner logout cancels its unfinished target review");
            c.assertTrue(!core.playerTargets.containsKey(owner.player.getUUID()), "Genuine owner logout clears its previously active order");
            operator(owner.player); String pending = propose(); staleOwner();
            queue.ownerApprove(oldOwner.player, pending); queue.cancel(oldOwner.player, pending);
            c.assertValueEqual(queue.data.proposals.get(pending).state, AgentActions.State.PENDING,
                "Old lifecycle, ceasefire, approve and cancel entrypoints cannot change the replacement owner's new review");
            queue.codexApprove(server.createCommandSourceStack(), pending);
            c.assertValueEqual(queue.data.proposals.get(pending).state, AgentActions.State.PENDING, "A reconnect still cannot skip owner approval");
            c.assertTrue(core.validPlayerTarget(owner.player) == null, "No order exists before both current approvals");
            queue.ownerApprove(owner.player, pending);
            c.assertTrue(core.validPlayerTarget(owner.player) == null, "Current owner approval alone remains inert");
            queue.codexApprove(server.createCommandSourceStack(), pending); var order = core.validPlayerTarget(owner.player);
            staleOwner(); control();
            c.assertTrue(core.validPlayerTarget(owner.player) == order && helper().getTarget() == target.player,
                "Old callbacks and stale lookups preserve the fresh doubly approved combat order");
            disconnectedPending = propose(); queue.ownerApprove(owner.player, disconnectedPending);
            reconnect(oldTarget, replacement -> { target = replacement; freshTarget(); });
        }
        void freshTarget() {
            target.player.setGameMode(GameType.SURVIVAL);
            c.assertValueEqual(queue.data.proposals.get(disconnectedPending).state, AgentActions.State.CANCELLED,
                "Genuine target logout cancels the unfinished review for that exact target");
            c.assertTrue(core.validPlayerTarget(owner.player) == null, "The replacement target cannot inherit the old target's order");
            String pending = propose(); AgentActions.invalidatePlayer(oldTarget.player); core.invalidatePlayer(oldTarget.player); oldDisconnect(oldTarget);
            c.assertValueEqual(queue.data.proposals.get(pending).state, AgentActions.State.PENDING,
                "A delayed old-target callback cannot cancel the new exact-target proposal");
            approve(pending); var order = core.validPlayerTarget(owner.player);
            AgentActions.invalidatePlayer(oldTarget.player); core.invalidatePlayer(oldTarget.player); oldDisconnect(oldTarget); control();
            c.assertTrue(core.validPlayerTarget(owner.player) == order && helper().getTarget() == target.player,
                "A fresh doubly approved target order survives stale target lifecycle callbacks");
            OperatorGameTests.deop(owner.player); core.ceasefire(owner.player);
            c.assertTrue(!core.playerTargets.containsKey(owner.player.getUUID()) && helper().getTarget() == null,
                "The actual current owner can still stop its order immediately after OP revocation");
            operator(owner.player); approve(propose()); String currentPending = propose();
            owner.close();
            c.assertTrue(!core.playerTargets.containsKey(owner.player.getUUID()), "Genuine current-owner logout still cancels its combat order");
            c.assertValueEqual(queue.data.proposals.get(currentPending).state, AgentActions.State.CANCELLED,
                "Genuine current-owner logout still cancels fresh unfinished reviews");
            succeed();
        }
        @Override void cleanup() {
            super.cleanup();
            if (changedPvp) c.getLevel().getGameRules().set(GameRules.PVP, previousPvp, server);
            if (replacedQueue) { if (previousQueue == null) AgentActions.INSTANCES.remove(server); else AgentActions.INSTANCES.put(server, previousQueue); }
            removeDirectory(directory);
        }
    }

    private static final class HistoryScenario extends HelperScenario {
        Joined oldOwner;
        AgentChat.Tracker oldTracker;
        AgentCompanions.Agent record;
        HistoryScenario(GameTestHelper c) { super(c); }
        @Override void start() {
            oldOwner = owner = join("agent-history", 4); operator(owner.player); join("history-observer", 11);
            later(10, () -> {
                spawn("guide", AgentCompanions.Profile.API); record = core.data.agents.get(helperId);
                AgentChat.track(server, 100); AgentChat.track(server, 200);
                oldTracker = AgentChat.TRACKING.get(server).get(helperId);
                c.assertTrue(oldTracker != null && oldTracker.samples.size() >= 2, "Real API helper records bounded old-session observations");
                reconnect(oldOwner, replacement -> { owner = replacement; fresh(); });
            });
        }
        void fresh() {
            operator(owner.player);
            c.assertTrue(core.data.agents.get(helperId) == record, "The observer keeps the same loaded helper record through the native reconnect");
            c.assertTrue(!AgentChat.TRACKING.get(server).containsKey(helperId), "Actual logout resets the old connection's history immediately");
            c.assertTrue(!oldTracker.current(core, owner.player, record.name()), "The captured old tracker rejects a new listener even with the same helper record and owner UUID");
            // Reinsert only the captured cache to model a read before delayed pruning;
            // the player, listener, helper and saved reconnect all remain native.
            AgentChat.TRACKING.get(server).put(helperId, oldTracker);
            c.assertValueEqual(AgentChat.recent(core, owner.player, helperId, record).size(), 1,
                "History queried before a tracking cleanup tick cannot expose old-session observations");
            c.assertValueEqual(AgentChat.history(owner.player.createCommandSourceStack(), record.name()), 1,
                "The actual history command remains usable immediately after reconnect");
            AgentChat.track(server, 300); var freshTracker = AgentChat.TRACKING.get(server).get(helperId);
            c.assertTrue(freshTracker != oldTracker && freshTracker.ownerConnection == owner.player.connection,
                "Tracking replaces the stale cache with the new exact connection");
            AgentChat.track(server, 400); int samples = freshTracker.samples.size(); oldDisconnect(oldOwner);
            c.assertTrue(AgentChat.TRACKING.get(server).get(helperId) == freshTracker && freshTracker.samples.size() == samples,
                "Delayed old disconnect leaves fresh API history intact");
            owner.close();
            c.assertTrue(!AgentChat.TRACKING.get(server).containsKey(helperId), "Genuine current logout still clears its fresh API history");
            succeed();
        }
    }

    private static final class AssistantClock extends Clock {
        Instant now = Instant.parse("2026-10-11T12:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance() { now = now.plusSeconds(10); }
    }
    private static final class AssistantScenario extends NativeSessionFixture {
        Joined oldOwner, owner;
        Path directory;
        final AssistantClock clock = new AssistantClock();
        final ArrayList<CompletableFuture<String>> responses = new ArrayList<>();
        ServerAssistant.AiService service, previousService;
        ServerAssistant.Session session, previousSession;
        boolean replacedService, replacedSession;
        AssistantScenario(GameTestHelper c) { super(c); }
        @Override void start() {
            oldOwner = owner = join("agent-assistant", 5); operator(owner.player);
            directory = temporary("infinity-assistant-session-");
            try {
                Files.writeString(directory.resolve("infinity-ai-key.txt"), "local-fake-transport-fixture-key");
                Files.writeString(directory.resolve("infinity-ai.json"), "{\"enabled\":true,\"ownerOnly\":true,\"dailyRequestLimit\":10}");
            } catch (IOException failure) { throw new IllegalStateException("Cannot write local fake assistant configuration", failure); }
            service = new ServerAssistant.AiService(directory, directory.resolve("usage.json"), (config, key, question, facts) -> {
                var response = new CompletableFuture<String>(); responses.add(response); return response;
            }, clock);
            previousService = ServerAssistant.AI.put(server, service); replacedService = true;
            session = new ServerAssistant.Session(); previousSession = ServerAssistant.SESSIONS.put(server, session); replacedSession = true;
            ask(); var oldFuture = responses.getLast();
            reconnect(oldOwner, replacement -> { owner = replacement; fresh(oldFuture); });
        }
        void ask() {
            var source = owner.player.createCommandSourceStack();
            c.assertTrue(ServerAssistant.take(source), "The real current-session assistant cooldown accepts the request");
            c.assertValueEqual(ServerAssistant.askExternal(source, "local lifecycle fixture", ServerAssistant.PREFIX, ServerAssistant.AI_PREFIX,
                "Only local fixture facts", () -> true, true), 1, "Actual assistant request routing uses the supplied local fake transport");
        }
        void fresh(CompletableFuture<String> oldFuture) {
            var id = owner.player.getUUID();
            c.assertTrue(oldFuture.isCancelled() && !service.inflight.containsKey(id), "Genuine old logout cancels its own pending fake request");
            c.assertTrue(!session.readyAt.containsKey(id) && !session.readyConnections.containsKey(id), "Genuine old logout clears its own assistant cooldown ownership");
            operator(owner.player); clock.advance(); ask(); var freshFuture = responses.getLast(); var readyAt = session.readyAt.get(id);
            c.assertTrue(freshFuture != oldFuture && service.inflightConnections.get(id) == owner.player.connection,
                "The replacement owns a distinct pending future and exact listener");
            oldDisconnect(oldOwner); service.finish(id, oldFuture);
            c.assertTrue(service.inflight.get(id) == freshFuture && !freshFuture.isCancelled(), "Old disconnect and late old completion cannot cancel or remove the fresh request");
            c.assertTrue(service.inflightConnections.get(id) == owner.player.connection && session.readyAt.get(id).equals(readyAt)
                && session.readyConnections.get(id) == owner.player.connection, "Old callbacks preserve fresh future and cooldown ownership");
            c.assertFalse(ServerAssistant.take(oldOwner.player.createCommandSourceStack()), "A detached old caller cannot acquire a cooldown or disturb fresh ownership");
            later(2, () -> {
                c.assertTrue(service.inflight.get(id) == freshFuture && !freshFuture.isCancelled(), "Queued old completion still leaves the fresh native session's work intact");
                c.assertFalse(ServerAssistant.take(owner.player.createCommandSourceStack()), "The current cooldown still applies after delayed old callbacks");
                owner.close();
                c.assertTrue(freshFuture.isCancelled() && !service.inflight.containsKey(id) && !service.inflightConnections.containsKey(id),
                    "Genuine current logout still cancels and releases the fresh pending fake request");
                c.assertTrue(!session.readyAt.containsKey(id) && !session.readyConnections.containsKey(id), "Genuine current logout still releases its cooldown binding");
                c.assertValueEqual(responses.size(), 2, "Only two local fake requests ran; no AI service or network transport was created");
                succeed();
            });
        }
        @Override void cleanup() {
            if (service != null) service.close();
            if (replacedService) { if (previousService == null) ServerAssistant.AI.remove(server); else ServerAssistant.AI.put(server, previousService); }
            if (replacedSession) { if (previousSession == null) ServerAssistant.SESSIONS.remove(server); else ServerAssistant.SESSIONS.put(server, previousSession); }
            removeDirectory(directory);
        }
    }

    @GameTest(maxTicks = 1200)
    public void nativeReconnectPreservesFreshDualApprovalsAndCombatOrders(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c, () -> new CombatScenario(c));
    }
    @GameTest(maxTicks = 1200)
    public void nativeReconnectResetsApiHistoryAndIgnoresOldDisconnect(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c, () -> new HistoryScenario(c));
    }
    @GameTest(maxTicks = 1200)
    public void nativeReconnectKeepsFreshAssistantFutureAndCooldown(GameTestHelper c) {
        NativeSessionFixture.startSerialized(c, () -> new AssistantScenario(c));
    }
}
