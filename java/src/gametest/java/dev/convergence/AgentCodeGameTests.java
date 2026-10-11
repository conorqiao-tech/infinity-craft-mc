package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** The game stores a reviewed workflow; it must never treat request text as executable code. */
public class AgentCodeGameTests {
    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private ServerPlayer player(GameTestHelper c, String name) {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
        var data = net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
        var player = new ServerPlayer(c.getLevel().getServer(), c.getLevel(), profile, data.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        c.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, data);
        player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        operator(player, LevelBasedPermissionSet.OWNER);
        player.setNoGravity(true);
        return player;
    }

    private void operator(ServerPlayer player, LevelBasedPermissionSet level) {
        var manager = player.level().getServer().getPlayerList();
        manager.deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        manager.op(new net.minecraft.server.players.NameAndId(player.getGameProfile()),
            java.util.Optional.of(level), java.util.Optional.of(false));
    }

    private String helper(AgentCompanions core, ServerPlayer owner, String name) {
        String id = UUID.randomUUID().toString();
        core.data.agents.put(id, new AgentCompanions.Agent(owner.getStringUUID(), name,
            AgentCompanions.Mode.STAY, AgentCompanions.Profile.CLI,
            owner.level().dimension().identifier().toString(), owner.getX(), owner.getY(), owner.getZ()));
        core.save();
        return id;
    }

    private String propose(AgentCodeRequests queue, ServerPlayer owner, String helper, String text) {
        var before = java.util.Set.copyOf(queue.data.requests.keySet());
        queue.propose(owner, helper, text);
        return queue.data.requests.keySet().stream().filter(id -> !before.contains(id)).findFirst().orElseThrow();
    }

    private void cleanup(AgentCompanions core, ServerPlayer player) {
        core.data.agents.entrySet().removeIf(entry -> entry.getValue().owner().equals(player.getStringUUID()));
        core.save();
        core.server.getPlayerList().deop(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
        if (core.server.getPlayerList().getPlayer(player.getUUID()) == player) core.server.getPlayerList().remove(player);
    }

    private void removeDirectory(Path directory) throws java.io.IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void rejectsUnchanged(GameTestHelper c, Path file, String json, String reason) throws java.io.IOException {
        Files.writeString(file, json);
        boolean rejected = false;
        try { AgentCodeRequests.read(file); } catch (IllegalStateException expected) { rejected = true; }
        c.assertTrue(rejected, reason);
        c.assertValueEqual(Files.readString(file), json, "Rejected queue remains available for recovery");
    }

    static final class RequestClock extends Clock {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long millis) { now = now.plusMillis(millis); }
    }

    @GameTest public void codeRequestsStoreOnlyWorkflowAndRequireReviewedCompletion(GameTestHelper c) throws Exception {
        var owner = player(c, "code-workflow");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-workflow-");
        try {
            helper(core, owner, "coder");
            var clock = new RequestClock();
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), clock);
            var console = core.server.createCommandSourceStack();
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
            var before = owner.getInventory().getItem(0).copy();
            long time = c.getLevel().getDefaultClockTime();
            int actions = AgentActions.get(core.server).data.proposals.size();
            String text = "Review a bug involving /give @s diamond; do not execute this example.";
            String id = propose(queue, owner, "coder", text);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.PENDING, "A request starts pending");
            c.assertValueEqual(queue.data.requests.get(id).text, text, "Request prose remains review evidence");
            queue.review(console, id);
            queue.complete(console, id, SHA);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.PENDING, "Console cannot skip owner approval");
            queue.approve(owner, id);
            var reloaded = new AgentCodeRequests(core.server, queue.file, clock);
            c.assertValueEqual(reloaded.data.requests.get(id).state, AgentCodeRequests.State.OWNER_APPROVED, "Owner approval survives restart without starting review");
            reloaded.complete(console, id, SHA);
            c.assertValueEqual(reloaded.data.requests.get(id).state, AgentCodeRequests.State.OWNER_APPROVED, "Completion cannot skip live review");
            reloaded.review(console, id);
            c.assertValueEqual(reloaded.data.requests.get(id).state, AgentCodeRequests.State.REVIEWING, "Console records live review only after approval");
            for (String invalid : List.of("51eae0a", SHA.toUpperCase(java.util.Locale.ROOT), "z".repeat(40), SHA + "0"))
                reloaded.complete(console, id, invalid);
            c.assertValueEqual(reloaded.data.requests.get(id).state, AgentCodeRequests.State.REVIEWING, "Only a lowercase full 40-character Git SHA is accepted");
            reloaded.complete(console, id, SHA);
            c.assertValueEqual(reloaded.data.requests.get(id).state, AgentCodeRequests.State.COMPLETED, "A valid completion records the terminal state");
            c.assertValueEqual(reloaded.data.requests.get(id).commit, SHA, "The full source commit is retained");
            var terminal = new AgentCodeRequests(core.server, queue.file, clock);
            terminal.review(console, id);
            terminal.complete(console, id, "a".repeat(40));
            c.assertValueEqual(terminal.data.requests.get(id).commit, SHA, "Restart and repeated review cannot overwrite a completed record");
            c.assertTrue(ItemStack.matches(before, owner.getInventory().getItem(0)), "Request and review never execute the example give command");
            c.assertValueEqual(c.getLevel().getDefaultClockTime(), time, "The synchronous workflow never changes world time");
            c.assertValueEqual(AgentActions.get(core.server).data.proposals.size(), actions, "Code requests do not enter the server-command dispatch queue");
        } finally { cleanup(core, owner); removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void codeRequestsRecheckOwnerPermissionsAndRejectOtherOwners(GameTestHelper c) throws Exception {
        var owner = player(c, "code-owner");
        var other = player(c, "code-other");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-owner-");
        try {
            helper(core, owner, "coder");
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), new RequestClock());
            String id = propose(queue, owner, "coder", "Fix a menu selection bug.");
            queue.approve(other, id); queue.cancel(other, id);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.PENDING, "Another OP4 cannot approve or cancel the owner's request");
            operator(owner, LevelBasedPermissionSet.ADMIN);
            queue.approve(owner, id);
            queue.propose(owner, "coder", "An OP3 request must be denied.");
            c.assertValueEqual(queue.data.requests.size(), 1, "Revoked permission cannot create another code request");
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.PENDING, "OP3 cannot approve an existing request");
            operator(owner, LevelBasedPermissionSet.OWNER);
            queue.approve(owner, id);
            operator(owner, LevelBasedPermissionSet.ADMIN);
            queue.review(core.server.createCommandSourceStack(), id);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.OWNER_APPROVED, "Review checks the owner's current OP4 permission");
            operator(owner, LevelBasedPermissionSet.OWNER);
            queue.review(core.server.createCommandSourceStack(), id);
            operator(owner, LevelBasedPermissionSet.ADMIN);
            queue.complete(core.server.createCommandSourceStack(), id, SHA);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.REVIEWING, "Completion rechecks OP4 after review began");
        } finally { cleanup(core, owner); cleanup(core, other); removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void codeRequestsRejectPlayerRemoteAndSilentConsoleReview(GameTestHelper c) throws Exception {
        var owner = player(c, "code-console");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-console-");
        try {
            helper(core, owner, "coder");
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), new RequestClock());
            String id = propose(queue, owner, "coder", "Add a tested course menu improvement.");
            queue.approve(owner, id);
            var console = core.server.createCommandSourceStack();
            var rejected = List.of(owner.createCommandSourceStack(), console.withSource(CommandSource.NULL), console.withSuppressedOutput(),
                core.server.getFunctions().getGameLoopSender());
            for (var source : rejected) {
                queue.review(source, id);
                c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.OWNER_APPROVED, "Nonlocal sources cannot start review");
            }
            queue.review(console, id);
            for (var source : rejected) {
                queue.complete(source, id, SHA);
                c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.REVIEWING, "Nonlocal sources cannot record completion");
            }
            c.assertTrue(queue.data.requests.get(id).commit.isEmpty(), "Rejected sources never record a commit");
            queue.complete(console, id, SHA);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.COMPLETED, "The direct local console can record a valid completion");
        } finally { cleanup(core, owner); removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void codeRequestsRemainBoundToOriginalCliHelperIdentity(GameTestHelper c) throws Exception {
        var owner = player(c, "code-helper");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-identity-");
        try {
            String helperId = helper(core, owner, "coder");
            var original = core.data.agents.get(helperId);
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), new RequestClock());
            String id = propose(queue, owner, "coder", "Improve the helper status display.");
            core.data.agents.put(helperId, original.profile(AgentCompanions.Profile.REGULAR)); core.save();
            queue.approve(owner, id);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.PENDING, "Changing away from CLI blocks owner approval");
            core.data.agents.put(helperId, original); core.save(); queue.approve(owner, id);
            core.dismiss(owner, "coder");
            String replacement = helper(core, owner, "coder");
            c.assertFalse(helperId.equals(replacement), "The same helper name can have a new roster UUID");
            queue.review(core.server.createCommandSourceStack(), id);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.OWNER_APPROVED, "A replacement helper cannot inherit an old request");
            core.data.agents.remove(replacement); core.data.agents.put(helperId, original); core.save();
            queue.review(core.server.createCommandSourceStack(), id);
            core.data.agents.put(helperId, original.profile(AgentCompanions.Profile.API)); core.save();
            queue.complete(core.server.createCommandSourceStack(), id, SHA);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.REVIEWING, "Changing profile during review prevents completion");
            core.dismiss(owner, "coder");
            queue.complete(core.server.createCommandSourceStack(), id, SHA);
            c.assertValueEqual(queue.data.requests.get(id).state, AgentCodeRequests.State.REVIEWING, "Dismissal during review prevents completion");
        } finally { cleanup(core, owner); removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void codeRequestsNeedOnlineOwnerAtReviewAndCompletion(GameTestHelper c) throws Exception {
        var first = player(c, "code-offline-a");
        var second = player(c, "code-offline-b");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-online-");
        try {
            helper(core, first, "coder"); helper(core, second, "coder");
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), new RequestClock());
            String beforeReview = propose(queue, first, "coder", "Improve a navigation label."); queue.approve(first, beforeReview);
            String duringReview = propose(queue, second, "coder", "Improve a backpack label."); queue.approve(second, duringReview);
            queue.review(core.server.createCommandSourceStack(), duringReview);
            core.server.getPlayerList().remove(first); core.server.getPlayerList().remove(second);
            queue.review(core.server.createCommandSourceStack(), beforeReview);
            queue.complete(core.server.createCommandSourceStack(), duringReview, SHA);
            c.assertValueEqual(queue.data.requests.get(beforeReview).state, AgentCodeRequests.State.OWNER_APPROVED, "Logout prevents starting a review");
            c.assertValueEqual(queue.data.requests.get(duringReview).state, AgentCodeRequests.State.REVIEWING, "Logout during review prevents completion");
        } finally { cleanup(core, first); cleanup(core, second); removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void codeRequestsExpireCancelAndBoundActiveOwnerQueue(GameTestHelper c) throws Exception {
        var owner = player(c, "code-limits");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-limits-");
        try {
            helper(core, owner, "coder");
            var clock = new RequestClock();
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), clock);
            String pending = propose(queue, owner, "coder", "First small improvement.");
            String approved = propose(queue, owner, "coder", "Second small improvement."); queue.approve(owner, approved);
            String reviewing = propose(queue, owner, "coder", "Third small improvement."); queue.approve(owner, reviewing); queue.review(core.server.createCommandSourceStack(), reviewing);
            queue.propose(owner, "coder", "A fourth active request must wait.");
            c.assertValueEqual(queue.data.requests.size(), AgentCodeRequests.PER_OWNER, "One owner cannot exceed the active request cap");
            queue.cancel(owner, approved);
            String next = propose(queue, owner, "coder", "Cancellation frees one active slot.");
            clock.advance(AgentCodeRequests.LIFETIME_MS);
            queue.approve(owner, pending); queue.complete(core.server.createCommandSourceStack(), reviewing, SHA);
            var persisted = AgentCodeRequests.read(queue.file);
            c.assertValueEqual(persisted.requests.get(approved).state, AgentCodeRequests.State.CANCELLED, "Cancellation survives expiry and queue reload");
            for (String id : List.of(pending, reviewing, next))
                c.assertValueEqual(persisted.requests.get(id).state, AgentCodeRequests.State.EXPIRED, "Expiry is terminal in every active workflow state");
            queue.review(core.server.createCommandSourceStack(), approved); queue.complete(core.server.createCommandSourceStack(), approved, SHA);
            c.assertValueEqual(queue.data.requests.get(approved).state, AgentCodeRequests.State.CANCELLED, "Cancelled work cannot resume or complete");
            c.assertTrue(queue.data.requests.values().stream().allMatch(request -> request.commit.isEmpty()), "Expired or cancelled work never acquires a commit");
        } finally { cleanup(core, owner); removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void codeRequestsValidateBoundedPlainTextAndPreserveRejectedFiles(GameTestHelper c) throws Exception {
        Path dir = Files.createTempDirectory("infinity-code-invalid-");
        try {
            Path file = dir.resolve("requests.json");
            c.assertTrue(AgentCodeRequests.validText("x".repeat(500)), "The documented maximum request is accepted");
            for (String text : List.of("", "   ", "x".repeat(501), "first\nsecond", "hidden\u202econtrol", "delete\u0000data"))
                c.assertFalse(AgentCodeRequests.validText(text), "Blank, oversized, control, or format text is rejected");
            var data = new AgentCodeRequests.Data();
            String id = UUID.randomUUID().toString();
            var request = new AgentCodeRequests.Request(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "coder", "A valid request.", 1);
            data.requests.put(id, request);
            request.text = "first\nsecond";
            rejectsUnchanged(c, file, CommunityServer.GSON.toJson(data), "Stored control characters cannot become console output lines");
            request.text = "x".repeat(501);
            rejectsUnchanged(c, file, CommunityServer.GSON.toJson(data), "Oversized persisted requests fail validation");
            request.text = "Valid again."; request.state = AgentCodeRequests.State.COMPLETED; request.commit = "51eae0a";
            rejectsUnchanged(c, file, CommunityServer.GSON.toJson(data), "Completed persisted records require a full source commit");
            request.state = AgentCodeRequests.State.PENDING; request.commit = SHA;
            rejectsUnchanged(c, file, CommunityServer.GSON.toJson(data), "An active persisted record cannot pretend to have a completion commit");
            rejectsUnchanged(c, file, " ".repeat(AgentCodeRequests.MAX_BYTES + 1), "The queue byte bound is enforced before deserialization");
            rejectsUnchanged(c, file, "{broken-json", "Corrupt JSON fails visibly without replacement");
        } finally { removeDirectory(dir); }
        c.succeed();
    }

    @GameTest public void cancelledCodeHistoryStaysBoundedAndReloadable(GameTestHelper c) throws Exception {
        var owner = player(c, "code-history");
        var core = AgentCompanions.get(c.getLevel().getServer());
        Path dir = Files.createTempDirectory("infinity-code-history-");
        try {
            helper(core, owner, "coder");
            var clock = new RequestClock();
            var queue = new AgentCodeRequests(core.server, dir.resolve("requests.json"), clock);
            String first = null, latest = null;
            for (int cycle = 0; cycle < 35; cycle++) {
                latest = propose(queue, owner, "coder", "Cancelled maintenance request " + cycle + ".");
                if (first == null) first = latest;
                queue.cancel(owner, latest);
                c.assertTrue(queue.data.requests.size() <= AgentCodeRequests.HISTORY,
                    "Cancellation trims terminal history before saving cycle " + cycle);
                clock.advance(1);
            }
            var reloaded = new AgentCodeRequests(core.server, queue.file, clock);
            c.assertValueEqual(reloaded.data.requests.size(), AgentCodeRequests.HISTORY,
                "A full cancellation history survives restart within its terminal bound");
            c.assertFalse(reloaded.data.requests.containsKey(first), "The oldest cancelled request is discarded");
            c.assertTrue(reloaded.data.requests.containsKey(latest), "The latest cancellation remains visible");
            c.assertTrue(reloaded.data.requests.values().stream().allMatch(request -> request.state == AgentCodeRequests.State.CANCELLED),
                "Reload does not reactivate any cancelled work");
            c.assertTrue(Files.size(queue.file) <= AgentCodeRequests.MAX_BYTES, "Bounded history stays within the saved queue byte cap");
        } finally { cleanup(core, owner); removeDirectory(dir); }
        c.succeed();
    }
}
