package dev.convergence;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;

/** Real native helper records and both gates; no GPT service or command executor. */
public class GptSquadActionsGameTests {
    private GptSquadPlanner.Plan plan() {
        return new GptSquadPlanner.Plan(AgentCompanions.Mode.GUARD, AgentCompanions.Profile.ULTIMATE_FINALS,
            AgentCompanions.Formation.RING, "Keep the reviewed loaded squad together.");
    }
    private String propose(AgentActionsGameTests.Fixture f) {
        var before = Set.copyOf(f.queue.data.proposals.keySet());
        f.queue.proposeSquad(f.owner, GptSquadPlanner.Scope.capture(f.owner), plan());
        return f.queue.data.proposals.keySet().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
    }

    @GameTest public void bothGatesConsumeDurablyBeforeNativeApplicationAndCannotReplay(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptBoth")) {
            var original = f.core.owned(f.owner, "fighter").getValue(); var shape = AgentCompanions.formation(f.owner);
            String id = propose(f); var proposal = f.queue.data.proposals.get(id);
            c.assertValueEqual(proposal.helperIds, List.of(f.helper.getStringUUID()), "Only exact captured native UUIDs");
            c.assertValueEqual(f.queue.data.format, 3, "Typed action uses explicit format3");
            c.assertTrue(f.queue.reviewDescription(proposal).contains("fighter"), "Human preview resolves native name");
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(proposal.state, AgentActions.State.PENDING, "Local gate cannot skip owner gate");
            f.queue.ownerApprove(f.owner, id);
            c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Owner gate alone changes no native record");
            c.assertValueEqual(AgentCompanions.formation(f.owner), shape, "Owner gate alone changes no formation");
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(AgentActions.read(f.queue.file).proposals.get(id).state, AgentActions.State.DISPATCHED, "Consumption is durable");
            c.assertValueEqual(f.core.owned(f.owner, "fighter").getValue().mode(), AgentCompanions.Mode.GUARD, "Native roster movement applied");
            c.assertValueEqual(f.core.owned(f.owner, "fighter").getValue().profile(), AgentCompanions.Profile.ULTIMATE_FINALS, "Native combat profile applied");
            c.assertValueEqual(AgentCompanions.formation(f.owner), AgentCompanions.Formation.RING, "Native owner formation applied");
            c.assertFalse(f.queue.squadSessions.containsKey(id), "Consumed RAM scope removed");
            var changed = f.core.owned(f.owner, "fighter").getValue(); f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == changed, "Replay does not apply again");
            c.assertTrue(f.commands.isEmpty(), "Typed orders never call command dispatcher");
        } c.succeed();
    }

    @GameTest public void otherOwnersAndNonlocalSourcesCannotSupplyEitherApproval(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptSource")) {
            String id = propose(f); var proposal = f.queue.data.proposals.get(id);
            f.queue.ownerApprove(f.target, id); f.queue.cancel(f.target, id);
            c.assertValueEqual(proposal.state, AgentActions.State.PENDING, "Different owner cannot approve or cancel");
            f.queue.ownerApprove(f.owner, id); var original = f.core.owned(f.owner, "fighter").getValue();
            var console = f.core.server.createCommandSourceStack();
            for (var source : List.of(f.owner.createCommandSourceStack(), console.withSource(CommandSource.NULL),
                    console.withSuppressedOutput(), f.core.server.getFunctions().getGameLoopSender())) {
                f.queue.codexApprove(source, id);
                c.assertValueEqual(proposal.state, AgentActions.State.OWNER_APPROVED, "Player/remote/silent/scheduled source refused");
                c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Rejected sources change no helpers");
            }
            c.assertTrue(AgentActions.Action.fromRequest("squad GUARD ULTIMATE_FINALS RING") == null, "Plain text cannot bypass typed capture");
            c.assertTrue(f.commands.isEmpty(), "No command interpolation");
        } c.succeed();
    }

    @GameTest public void metadataRecordFormationAndConnectionDriftCancelExactReview(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptDrift")) {
            String id = propose(f); f.queue.ownerApprove(f.owner, id); f.queue.data.proposals.get(id).helperMode = "STAY";
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Another allowlisted mode cannot replace reviewed metadata");
            c.assertValueEqual(f.core.owned(f.owner, "fighter").getValue().mode(), AgentCompanions.Mode.FOLLOW, "Tampered plan not applied");
            id = propose(f); f.queue.ownerApprove(f.owner, id); f.core.profile(f.owner, "fighter", AgentCompanions.Profile.REGULAR);
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Changed native record invalidates scope");
            id = propose(f); f.queue.ownerApprove(f.owner, id); AgentCompanions.cycleFormation(f.owner); f.queue.expire();
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Changed formation invalidates scope");
            id = propose(f); f.queue.ownerApprove(f.owner, id); var connection = f.owner.connection;
            try { f.owner.connection = f.target.connection; f.queue.expire(); } finally { f.owner.connection = connection; }
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Changed owner connection cannot inherit approval");
            id = propose(f); f.queue.ownerApprove(f.owner, id); f.owner.setGameMode(GameType.CREATIVE); f.queue.expire(); f.owner.setGameMode(GameType.SURVIVAL);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Changed owner mode invalidates scope");
            c.assertTrue(f.commands.isEmpty(), "No stale plan turns into a command");
        } c.succeed();
    }

    @GameTest public void restartExpiryLifecycleAndCeasefireDiscardRamScopes(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptLife")) {
            String id = propose(f); f.queue.ownerApprove(f.owner, id);
            var reopened = new AgentActions(f.core.server, f.queue.file, f.clock, f.commands::add);
            c.assertValueEqual(reopened.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Restart cancels active persisted plan");
            c.assertTrue(reopened.squadSessions.isEmpty(), "IDs never reconstruct sessions");
            reopened.codexApprove(f.core.server.createCommandSourceStack(), id); f.queue.cancel(f.owner, id);
            id = propose(f); f.queue.ownerApprove(f.owner, id); f.clock.advance(AgentActions.LIFETIME_MS); f.queue.expire();
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.EXPIRED, "Existing expiry applies");
            c.assertFalse(f.queue.squadSessions.containsKey(id), "Expired scope removed");
            id = propose(f); f.queue.ownerApprove(f.owner, id); var previous = AgentActions.INSTANCES.put(f.core.server, f.queue);
            try { AgentActions.invalidatePlayer(f.owner); }
            finally { if (previous == null) AgentActions.INSTANCES.remove(f.core.server); else AgentActions.INSTANCES.put(f.core.server, previous); }
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Existing lifecycle callback revokes typed owner scope");
            id = propose(f); f.queue.ownerApprove(f.owner, id); f.queue.ceasefire(f.owner);
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Ceasefire cancels pending squad changes");
            c.assertTrue(f.commands.isEmpty(), "Automatic revocations authorize no commands");
        } c.succeed();
    }

    @GameTest public void failedQueueConsumptionSavePreventsNativeApplicationAndReplay(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptQueueDisk")) {
            String id = propose(f); f.queue.ownerApprove(f.owner, id); var original = f.core.owned(f.owner, "fighter").getValue();
            Path backup = f.obstructSave(); boolean failed = false;
            try { f.queue.codexApprove(f.core.server.createCommandSourceStack(), id); } catch (IllegalStateException expected) { failed = true; }
            c.assertTrue(failed, "Actual queue storage failure is synchronous");
            c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Nothing applies before durable consumption");
            c.assertValueEqual(AgentActions.read(f.queue.file).proposals.get(id).state, AgentActions.State.OWNER_APPROVED, "Durable prior state preserved");
            c.assertValueEqual(f.queue.data.proposals.get(id).state, AgentActions.State.DISPATCHED, "RAM approval consumed conservatively");
            c.assertFalse(f.queue.squadSessions.containsKey(id), "Failed consumed scope cannot revive");
            f.repairSave(backup); f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Repair does not replay consumed intent");
            c.assertTrue(f.commands.isEmpty(), "Storage failure dispatches no command");
        } c.succeed();
    }

    @GameTest public void failedNativeRosterWriteLeavesActionDurablyConsumed(GameTestHelper c) throws Exception {
        try (var f = new AgentActionsGameTests().new Fixture(c, "gptNativeDisk")) {
            String id = propose(f); f.queue.ownerApprove(f.owner, id); var original = f.core.owned(f.owner, "fighter").getValue();
            var shape = AgentCompanions.formation(f.owner); Path blocker = f.core.file.resolveSibling(f.core.file.getFileName() + ".tmp");
            c.assertFalse(Files.exists(blocker), "Native temp boundary starts clear");
            Files.createDirectory(blocker); Files.writeString(blocker.resolve("keep"), "block actual atomic write");
            try {
                f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
                c.assertValueEqual(AgentActions.read(f.queue.file).proposals.get(id).state, AgentActions.State.DISPATCHED, "Durable consumption precedes failing native roster write");
                c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Native save failure restores existing record");
                c.assertValueEqual(AgentCompanions.formation(f.owner), shape, "Formation unchanged after failed roster write");
            } finally { Files.delete(blocker.resolve("keep")); Files.delete(blocker); }
            f.queue.codexApprove(f.core.server.createCommandSourceStack(), id);
            c.assertTrue(f.core.owned(f.owner, "fighter").getValue() == original, "Storage repair cannot replay the terminal action");
            c.assertTrue(f.commands.isEmpty(), "No fallback to null or arbitrary command");
        } c.succeed();
    }

    private AgentActions.Proposal validProposal() {
        var p = new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.SQUAD_PLAN, 1);
        p.helperIds = List.of(UUID.randomUUID().toString()); p.helperMode = "FOLLOW"; p.helperProfile = "REGULAR";
        p.helperFormation = "ESCORT"; p.planReason = "Use the exact captured squad."; p.planToken = UUID.randomUUID().toString(); return p;
    }

    @GameTest public void strictTypedMetadataRejectsLegacyFormatSmugglingWithoutOverwriting(GameTestHelper c) throws Exception {
        Path directory = Files.createTempDirectory("infinity-gpt-queue-validation-");
        try {
            var valid = validProposal(); c.assertTrue(valid.valid(), "Valid bounded metadata");
            List<Consumer<AgentActions.Proposal>> corruptions = List.of(p -> p.helperIds = List.of(),
                p -> p.helperIds = java.util.Collections.nCopies(51, UUID.randomUUID().toString()),
                p -> p.helperIds = List.of(p.helperIds.getFirst(), p.helperIds.getFirst()), p -> p.helperIds = List.of("1-1-1-1-1"),
                p -> p.helperMode = null, p -> p.helperMode = "time set day", p -> p.helperProfile = "PRIMITIVE", p -> p.helperFormation = "free text",
                p -> p.planReason = "x".repeat(241), p -> p.planReason = "first\nsecond", p -> p.planReason = "hidden\u202eorder",
                p -> p.planToken = "not a UUID", p -> p.targetUuid = UUID.randomUUID().toString(), p -> p.action = AgentActions.Action.DAY);
            Path file = directory.resolve("actions.json");
            for (var corrupt : corruptions) {
                var p = CommunityServer.GSON.fromJson(CommunityServer.GSON.toJson(valid), AgentActions.Proposal.class); corrupt.accept(p);
                c.assertFalse(p.valid(), "Malformed typed metadata refused");
                var data = new AgentActions.Data(); data.format = 3; data.proposals.put(UUID.randomUUID().toString(), p);
                String bytes = CommunityServer.GSON.toJson(data); Files.writeString(file, bytes);
                boolean rejected = false; try { AgentActions.read(file); } catch (IllegalStateException expected) { rejected = true; }
                c.assertTrue(rejected, "Persisted malformed metadata refused"); c.assertValueEqual(Files.readString(file), bytes, "Recovery bytes preserved");
            }
            for (int format : new int[]{1, 2}) {
                var data = new AgentActions.Data(); data.format = format; data.proposals.put(UUID.randomUUID().toString(), valid);
                Files.writeString(file, CommunityServer.GSON.toJson(data));
                boolean rejected = false; try { AgentActions.read(file); } catch (IllegalStateException expected) { rejected = true; }
                c.assertTrue(rejected, "Typed action cannot enter legacy format " + format);
                data.proposals.clear(); String id = UUID.randomUUID().toString();
                data.proposals.put(id, new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.DAY, 1));
                Files.writeString(file, CommunityServer.GSON.toJson(data));
                c.assertValueEqual(AgentActions.read(file).format, 2, "Old fixed formats retain compatibility");
                c.assertValueEqual(AgentActions.read(file).proposals.get(id).action, AgentActions.Action.DAY, "Exact legacy action retained");
            }
        } finally { try (var paths = Files.walk(directory)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
        c.succeed();
    }

    @GameTest public void byteLimitPrunesOldestTerminalOnlyAndProtectsLastGoodBackup(GameTestHelper c) throws Exception {
        Path directory = Files.createTempDirectory("infinity-gpt-queue-bytes-");
        try {
            var queue = new AgentActions(c.getLevel().getServer(), directory.resolve("actions.json"), new AgentActionsGameTests.ActionClock(),
                command -> { throw new AssertionError("No dispatch during size maintenance"); }); queue.data.format = 3;
            String activeId = UUID.randomUUID().toString(); var active = new AgentActions.Proposal(UUID.randomUUID().toString(), AgentActions.Action.DAY, 1);
            queue.data.proposals.put(activeId, active); var history = new ArrayList<String>();
            for (int i = 0; i < 64; i++) {
                var p = validProposal(); var ids = new ArrayList<String>(); for (int k = 0; k < 50; k++) ids.add(UUID.randomUUID().toString());
                p.helperIds = List.copyOf(ids); p.planReason = "<".repeat(240); p.createdAt = i + 2; p.state = AgentActions.State.CANCELLED;
                String id = UUID.randomUUID().toString(); history.add(id); queue.data.proposals.put(id, p);
            }
            c.assertTrue(CommunityServer.GSON.toJson(queue.data).getBytes(StandardCharsets.UTF_8).length > AgentActions.MAX_BYTES, "Valid terminal history exceeds bytes before trim");
            queue.save(); c.assertTrue(Files.size(queue.file) <= AgentActions.MAX_BYTES, "Actual UTF-8 queue bounded");
            c.assertTrue(queue.data.proposals.get(activeId) == active, "Active entry never pruned");
            c.assertFalse(queue.data.proposals.containsKey(history.getFirst()), "Oldest terminal pruned first");
            c.assertTrue(queue.data.proposals.containsKey(history.getLast()), "Newest history kept");
            c.assertValueEqual(AgentActions.read(queue.file).proposals.size(), queue.data.proposals.size(), "Result remains strictly readable");
            queue.save(); byte[] before = Files.readAllBytes(queue.file), backup = Files.readAllBytes(queue.file.resolveSibling("actions.json.previous"));
            queue.data.proposals.clear(); var oversized = validProposal(); oversized.planReason = "x".repeat(AgentActions.MAX_BYTES);
            queue.data.proposals.put(UUID.randomUUID().toString(), oversized);
            boolean rejected = false; try { queue.save(); } catch (IllegalStateException expected) { rejected = true; }
            c.assertTrue(rejected, "Active-only overflow fails closed");
            c.assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(queue.file)), "Main recovery bytes preserved");
            c.assertTrue(java.util.Arrays.equals(backup, Files.readAllBytes(queue.file.resolveSibling("actions.json.previous"))), "Failure precedes backup rotation");
        } finally { try (var paths = Files.walk(directory)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
        c.succeed();
    }
}
