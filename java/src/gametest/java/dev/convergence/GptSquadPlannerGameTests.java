package dev.convergence;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Mock transport only: exercise the real bounded planner, shared budgets and native completion path. */
public class GptSquadPlannerGameTests {
    static final String VALID = "{\"action\":\"SQUAD_PLAN\",\"mode\":\"FOLLOW\",\"profile\":\"ULTIMATE_FINALS\",\"formation\":\"WEDGE\",\"reason\":\"Follow in spaced combat rows.\"}";
    final class Fixture implements AutoCloseable {
        final AgentActionsGameTests.Fixture nativeFixture;
        final ServerAssistant.AiService service, oldService;
        final AgentActions oldQueue;
        final List<CompletableFuture<String>> answers = new ArrayList<>();
        String goal, instructions;
        Fixture(GameTestHelper c, String name) throws Exception {
            nativeFixture = new AgentActionsGameTests().new Fixture(c, name);
            var directory = nativeFixture.directory.resolve("ai"); Files.createDirectories(directory);
            Files.writeString(directory.resolve("infinity-ai-key.txt"), "mock-only-never-transmitted");
            service = new ServerAssistant.AiService(directory, directory.resolve("usage.json"), (config, key, question, facts) -> {
                goal = question; instructions = facts;
                var future = new CompletableFuture<String>(); answers.add(future); return future;
            }, nativeFixture.clock);
            service.config.enabled = true;
            oldService = ServerAssistant.AI.put(nativeFixture.core.server, service);
            oldQueue = AgentActions.INSTANCES.put(nativeFixture.core.server, nativeFixture.queue);
        }
        void ready() {
            nativeFixture.clock.advance(11_000);
            var session = ServerAssistant.SESSIONS.get(nativeFixture.core.server);
            if (session != null) session.readyAt.remove(nativeFixture.owner.getUUID());
        }
        @Override public void close() throws Exception {
            service.close();
            if (oldService == null) ServerAssistant.AI.remove(nativeFixture.core.server); else ServerAssistant.AI.put(nativeFixture.core.server, oldService);
            if (oldQueue == null) AgentActions.INSTANCES.remove(nativeFixture.core.server); else AgentActions.INSTANCES.put(nativeFixture.core.server, oldQueue);
            nativeFixture.close();
        }
    }

    @GameTest public void strictPlansRejectExecutableAndAmbiguousOutput(GameTestHelper c) {
        var plan = GptSquadPlanner.parse(VALID);
        c.assertTrue(plan.actionable(), "Explicit allowlisted native enums are accepted");
        c.assertFalse(GptSquadPlanner.parse("{\"action\":\"NONE\",\"reason\":\"This needs an unsupported world edit.\"}").actionable(), "Unsupported goals can honestly produce no order");
        for (String bad : List.of(
            "```json\n" + VALID + "\n```", VALID + " {}", VALID.replace("\"FOLLOW\"", "\"FOLLOW\",\"mode\":\"STAY\""),
            VALID.replace("\"FOLLOW\"", "\"/op player\""), VALID.replace("\"ULTIMATE_FINALS\"", "\"CLI\""),
            VALID.replace("\"reason\":", "\"command\":\"save-all\",\"reason\":"),
            VALID.replace("\"WEDGE\"", "5"), VALID.replace("Follow in spaced combat rows.", "bad\\nline"),
            VALID.replace("Follow in spaced combat rows.", "x".repeat(241)),
            "{\"action\":\"NONE\",\"reason\":\"no\",\"mode\":\"FOLLOW\"}", "[" + VALID + "]")) {
            boolean rejected = false;
            try { GptSquadPlanner.parse(bad); } catch (IllegalArgumentException expected) { rejected = true; }
            c.assertTrue(rejected, "Malformed, duplicated or out-of-scope output cannot become an order");
        }
        c.succeed();
    }

    @GameTest(maxTicks=100) public void realRequestQueuesReviewWithoutChangingNativeOrders(GameTestHelper c) throws Exception {
        var f = new Fixture(c, "GptReview");
        try {
            var owner = f.nativeFixture.owner;
            var before = f.nativeFixture.core.owned(owner, "fighter").getValue();
            c.assertValueEqual(GptSquadPlanner.request(owner, "Escort me with strong combos"), 1, "Owner explicitly requests one GPT plan");
            c.assertValueEqual(f.answers.size(), 1, "Exactly one mock call, not one call per helper");
            c.assertValueEqual(f.service.usage.requests, 1, "Planner consumes shared persisted usage");
            c.assertTrue(f.instructions.contains("loaded_owned_helpers") && !f.instructions.contains(owner.getStringUUID())
                && !f.instructions.contains(owner.getGameProfile().name()) && !f.instructions.contains("infinity-ai-key"), "Only bounded aggregate facts accompany the goal");
            c.assertTrue(f.nativeFixture.queue.data.proposals.isEmpty(), "No order before the answer");
            f.answers.getFirst().complete(VALID);
            c.runAfterDelay(2, () -> {
                try {
                    var proposals = f.nativeFixture.queue.data.proposals.values();
                    c.assertValueEqual(proposals.size(), 1, "Validated output enters the real review queue");
                    c.assertTrue(proposals.iterator().next().state == AgentActions.State.PENDING, "GPT cannot grant owner approval");
                    c.assertTrue(f.nativeFixture.core.owned(owner, "fighter").getValue() == before, "Native settings remain unchanged until both approvals");
                    c.assertTrue(f.service.inflight.isEmpty(), "Completion releases the shared pending slot");
                } finally { try { f.close(); } catch (Exception error) { throw new RuntimeException(error); } }
                // A real server has one AI service; exercise this second async
                // scenario after the first exact fixture releases that service.
                try { staleHelperCompletionAndMalformedAnswerAreDiscarded(c); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
        } catch (Throwable error) { f.close(); throw error; }
    }

    private void staleHelperCompletionAndMalformedAnswerAreDiscarded(GameTestHelper c) throws Exception {
        var f = new Fixture(c, "GptStale");
        try {
            var owner = f.nativeFixture.owner;
            GptSquadPlanner.request(owner, "Escort me");
            f.nativeFixture.core.mode(owner, "fighter", AgentCompanions.Mode.STAY);
            f.answers.getFirst().complete(VALID);
            c.runAfterDelay(2, () -> {
                try {
                    c.assertTrue(f.nativeFixture.queue.data.proposals.isEmpty(), "A changed exact helper invalidates a late GPT answer");
                    f.ready(); GptSquadPlanner.request(owner, "Escort me");
                    f.answers.getLast().complete(VALID.replace("\"WEDGE\"", "\"exec code\""));
                    c.runAfterDelay(2, () -> {
                        try {
                            c.assertTrue(f.nativeFixture.queue.data.proposals.isEmpty(), "Invalid model output creates no proposal");
                            c.assertValueEqual(f.service.usage.requests, 2, "Accepted failed attempts still count against the shared budget");
                            c.succeed();
                        } finally { try { f.close(); } catch (Exception error) { throw new RuntimeException(error); } }
                    });
                } catch (Throwable error) { try { f.close(); } catch (Exception ignored) {} throw error; }
            });
        } catch (Throwable error) { f.close(); throw error; }
    }

    @GameTest public void disabledBudgetPendingAndPermissionsNeverBypassSharedService(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "GptLimits")) {
            var owner = f.nativeFixture.owner;
            f.service.config.enabled = false;
            GptSquadPlanner.request(owner, "Escort");
            c.assertTrue(f.answers.isEmpty(), "Disabled service sends nothing");
            f.ready(); f.service.config.enabled = true; f.service.config.dailyRequestLimit = 0;
            GptSquadPlanner.request(owner, "Escort");
            c.assertTrue(f.answers.isEmpty(), "Zero daily budget sends nothing");
            f.ready(); f.service.config.dailyRequestLimit = 5;
            GptSquadPlanner.request(owner, "Escort"); f.ready(); GptSquadPlanner.request(owner, "Defend");
            c.assertValueEqual(f.answers.size(), 1, "Pending request is shared across planner requests");
            OperatorGameTests.deop(owner); f.ready(); GptSquadPlanner.request(owner, "Defend");
            c.assertValueEqual(f.answers.size(), 1, "Current OP4 rights are required");
        }
        c.succeed();
    }

    @GameTest public void scopePinsOwnerAndExactRosterWithoutPublishingIdentityToModel(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "GptScope")) {
            var owner = f.nativeFixture.owner;
            var scope = GptSquadPlanner.Scope.capture(owner);
            c.assertTrue(scope.problem() == null && scope.ids().size() == 1, "Capture pins exactly the loaded owned native squad");
            c.assertTrue(scope.review(GptSquadPlanner.parse(VALID)), "One exact plan may be sealed for review");
            c.assertFalse(scope.review(GptSquadPlanner.parse(VALID)), "Scope cannot be rebound to another model response");
            AgentCompanions.cycleFormation(owner);
            c.assertTrue(scope.problem() != null, "An intervening formation edit invalidates the reviewed context");
            c.assertValueEqual(scope.apply(GptSquadPlanner.parse(VALID)), 0, "Stale scope cannot apply any orders");
        }
        c.succeed();
    }
}
