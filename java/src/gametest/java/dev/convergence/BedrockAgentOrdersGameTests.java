package dev.convergence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.impl.FormDefinitions;

/** Feed real encoded Cumulus forms into their actual result callbacks and approval queue. */
public class BedrockAgentOrdersGameTests {
    private static JsonObject encoded(Form form) {
        return JsonParser.parseString(FormDefinitions.instance().definitionFor(form).codec().jsonData(form)).getAsJsonObject();
    }
    private static void reply(Form form, String payload) {
        try { FormDefinitions.instance().definitionFor(form).handleFormResponse(form, payload); }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static void click(Form form, String prefix) {
        var buttons = encoded(form).getAsJsonArray("buttons");
        for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).getAsJsonObject().get("text").getAsString().startsWith(prefix)) {
            reply(form, Integer.toString(i)); return;
        }
        throw new IllegalStateException("Missing encoded form button: " + prefix);
    }
    private static String text(Form form) { return encoded(form).get("content").getAsString(); }

    private static final class Fixture implements AutoCloseable {
        final AgentActionsGameTests.Fixture live;
        final AgentActions previous;
        final List<Form> forms = new ArrayList<>();
        final Predicate<Form> sender = form -> { encoded(form); forms.add(form); return true; };
        Fixture(GameTestHelper context, String prefix) throws Exception {
            live = new AgentActionsGameTests().new Fixture(context, prefix);
            previous = AgentActions.INSTANCES.put(live.core.server, live.queue);
        }
        Form last() { return forms.getLast(); }
        void open() { BedrockAgentOrdersMenu.open(live.owner, sender); }
        String proposal() {
            return live.queue.data.proposals.entrySet().stream().filter(e -> e.getValue().owner.equals(live.owner.getStringUUID()) && e.getValue().active())
                .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
        }
        public void close() throws Exception {
            for (var player : live.players) {
                HelperForms.ACTIVE.remove(player.getUUID());
                HelperForms.DELIVERIES.remove(player.getUUID());
                player.inventoryMenu.setCarried(ItemStack.EMPTY);
                GameModes.TRANSITIONS.remove(player.getUUID());
                OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
            }
            try { live.close(); }
            finally {
                if (previous == null) AgentActions.INSTANCES.remove(live.core.server, live.queue);
                else AgentActions.INSTANCES.put(live.core.server, previous);
            }
        }
    }

    @GameTest public void encodedServerOrderRequiresOwnerThenSeparateLiveConsoleAndCannotReplay(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-order")) {
            f.live.owner.getInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            f.open();
            c.assertValueEqual(encoded(f.last()).get("type").getAsString(), "form", "Orders use a native simple form");
            click(f.last(), "Propose: set day");
            String id = f.proposal(); var order = f.live.queue.data.proposals.get(id); var review = f.last();
            c.assertTrue(text(review).contains(id) && text(review).contains("/time set day"), "Encoded review includes the exact ID and command");
            c.assertTrue(text(review).contains("Needs your owner approval"), "A proposal visibly needs owner consent");
            c.assertTrue(f.live.commands.isEmpty(), "Opening and proposing run no command");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(order.state, AgentActions.State.PENDING, "Even console cannot skip owner consent");
            click(review, "Approve as owner only");
            c.assertValueEqual(order.state, AgentActions.State.OWNER_APPROVED, "Actual owner callback records only owner approval");
            c.assertTrue(text(f.last()).contains(id) && text(f.last()).contains("Waiting for a separate live Codex"), "Waiting status remains actionable and identifies the exact order");
            c.assertTrue(f.live.commands.isEmpty(), "Owner approval alone dispatches nothing");
            int forms = f.forms.size(); click(review, "Approve as owner only");
            c.assertValueEqual(f.forms.size(), forms, "Replaying a consumed form cannot replace the new review");
            f.live.queue.codexApprove(f.live.owner.createCommandSourceStack(), id);
            c.assertTrue(f.live.commands.isEmpty(), "The owner cannot impersonate the separate local-console gate");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(f.live.commands, List.of("time set day"), "Only both genuine gates dispatch the exact command once");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), id); click(review, "Approve as owner only");
            c.assertValueEqual(f.live.commands.size(), 1, "Old forms and consumed IDs cannot replay dispatch");
            c.assertValueEqual(f.live.owner.getInventory().getItem(0).getCount(), 7, "Forms preserve owned inventory");
            c.assertTrue(f.live.owner.containerMenu == f.live.owner.inventoryMenu && f.live.owner.inventoryMenu.getCarried().isEmpty(), "Form controls never become inventory items");
        }
        c.succeed();
    }

    @GameTest public void encodedPlayerTargetCannotAttackBeforeBothApprovals(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-target")) {
            f.open(); click(f.last(), "Choose player target");
            click(f.last(), "Propose target: " + f.live.target.getName().getString());
            String id = f.proposal(); var order = f.live.queue.data.proposals.get(id); var review = f.last();
            c.assertTrue(text(review).contains(id) && text(review).contains(order.targetUuid)
                && text(review).contains(order.targetSession) && text(review).contains(order.targetDimension), "Encoded target review preserves exact identity, world and session");
            c.assertValueEqual(order.targetUuid, f.live.target.getStringUUID(), "Selecting a native button captures the exact player object");
            f.live.control();
            c.assertTrue(f.live.helper.getTarget() == null && f.live.core.validPlayerTarget(f.live.owner) == null, "Proposal alone activates no player combat");
            c.assertFalse(AgentCompanions.allowDamage(f.live.target, f.live.target.damageSources().mobAttack(f.live.helper)), "Player damage gate stays closed before approvals");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), id); f.live.control();
            c.assertTrue(f.live.helper.getTarget() == null, "Console cannot approve a still-unapproved native target review");
            click(review, "Approve as owner only"); f.live.control();
            c.assertTrue(f.live.helper.getTarget() == null && f.live.core.validPlayerTarget(f.live.owner) == null, "Owner form approval alone starts no pursuit");
            c.assertFalse(AgentCompanions.allowDamage(f.live.target, f.live.target.damageSources().mobAttack(f.live.helper)), "Owner approval does not open the player damage gate");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), id); f.live.control();
            c.assertTrue(f.live.helper.getTarget() == f.live.target, "Both approval gates activate only the exact reviewed target");
            c.assertTrue(f.live.commands.isEmpty(), "Player target activation never builds or dispatches a general command");
            f.live.queue.ceasefire(f.live.owner); click(review, "Approve as owner only"); f.live.control();
            c.assertTrue(f.live.helper.getTarget() == null, "Replaying old owner consent cannot undo ceasefire");
        }
        c.succeed();
    }

    @GameTest public void encodedTargetPickerRejectsSameUuidReplacementAndReportsMissingCompatibleHelpers(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-session")) {
            f.open(); click(f.last(), "Choose player target"); var picker = f.last();
            var profile = f.live.target.getGameProfile();
            f.live.core.server.getPlayerList().remove(f.live.target);
            var replacement = f.live.add(profile); replacement.setPos(f.live.owner.position().add(4, 0, 0));
            click(picker, "Propose target: " + profile.name());
            c.assertTrue(f.live.queue.data.proposals.isEmpty(), "A same-UUID and same-name replacement cannot use the old target button");
            c.assertTrue(text(f.last()).contains("exact player session changed"), "Stale selection failure is visible in the native form");
            f.live.core.dismiss(f.live.owner, "fighter");
            c.assertValueEqual(f.live.core.count(f.live.owner), 0, "The failure fixture has an empty helper roster");
            f.open(); click(f.last(), "Choose player target");
            c.assertTrue(text(f.last()).contains("Create helper") && text(f.last()).contains("Primitive or Ultimate Finals"), "Unavailable target page explains helper creation and compatible profiles");
            click(f.last(), "Unavailable: " + profile.name());
            c.assertTrue(text(f.last()).contains("Target unavailable") && text(f.last()).contains("No active loaded"), "A profile refusal is shown over the menu, not only in chat");
            c.assertTrue(f.live.queue.data.proposals.isEmpty() && f.live.commands.isEmpty(), "Refused targets produce neither proposals nor commands");
        }
        c.succeed();
    }

    @GameTest public void encodedReviewRejectsObjectStateDescriptionOwnerAndExpiryChanges(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-snapshot")) {
            for (String change : List.of("object", "state", "description", "owner", "created", "expired")) {
                f.live.queue.data.proposals.clear();
                f.open(); click(f.last(), "Propose: set day");
                String id = f.proposal(); var original = f.live.queue.data.proposals.get(id); var review = f.last();
                switch (change) {
                    case "object" -> f.live.queue.data.proposals.put(id, new AgentActions.Proposal(original.owner, original.action, original.createdAt));
                    case "state" -> original.state = AgentActions.State.OWNER_APPROVED;
                    case "description" -> original.action = AgentActions.Action.NIGHT;
                    case "owner" -> original.owner = f.live.target.getStringUUID();
                    case "created" -> original.createdAt--;
                    case "expired" -> f.live.clock.advance(AgentActions.LIFETIME_MS);
                    default -> throw new IllegalStateException(change);
                }
                var current = f.live.queue.data.proposals.get(id); var before = current.state;
                int count = f.forms.size(); click(review, "Approve as owner only");
                c.assertValueEqual(current.state, before, "Stale " + change + " cannot approve, cancel, or expire an order during pure context validation");
                c.assertTrue(f.live.commands.isEmpty(), "Stale " + change + " cannot dispatch a command");
                c.assertTrue(f.forms.size() > count && f.last() != review, "A changed " + change + " gets native failure feedback");
            }
        }
        c.succeed();
    }

    @GameTest public void encodedPendingWithTransitioningTargetShowsNativeRecoveryWithoutApproval(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-transition")) {
            f.open(); click(f.last(), "Choose player target");
            click(f.last(), "Propose target: " + f.live.target.getName().getString());
            String id = f.proposal(); var order = f.live.queue.data.proposals.get(id);
            var binding = f.live.queue.targetSessions.get(id);
            c.assertValueEqual(order.state, AgentActions.State.PENDING, "The exact target proposal initially has neither approval");
            GameModes.TRANSITIONS.add(f.live.target.getUUID());
            // AgentActions.expire does not consume this transient mode marker.
            // The page's exact target-session context is already false at show time.
            f.open(); int screens = f.forms.size(); click(f.last(), "Pending orders");
            var recovery = encoded(f.last());
            c.assertTrue(f.forms.size() > screens, "An initially stale target context still sends a native recovery form");
            c.assertValueEqual(recovery.get("type").getAsString(), "form", "Recovery is an actual encoded native form");
            c.assertValueEqual(recovery.get("title").getAsString(), "Helper or order changed", "The recovery identifies the stale helper/order context");
            c.assertTrue(recovery.getAsJsonArray("buttons").asList().stream().anyMatch(button ->
                button.getAsJsonObject().get("text").getAsString().equals("Reopen AI Helpers")), "Recovery has an actionable native reopen button");
            c.assertTrue(f.live.queue.data.proposals.get(id) == order && f.live.queue.targetSessions.get(id) == binding,
                "Initial context validation preserves the exact proposal and target binding");
            c.assertValueEqual(order.state, AgentActions.State.PENDING, "Opening a stale pending page cannot approve or cancel its proposal");
            f.live.control();
            c.assertTrue(f.live.helper.getTarget() == null && f.live.core.validPlayerTarget(f.live.owner) == null,
                "Neither proposal nor recovery activates player combat");
            c.assertFalse(AgentCompanions.allowDamage(f.live.target, f.live.target.damageSources().mobAttack(f.live.helper)),
                "The player-damage gate remains closed");
            f.live.queue.codexApprove(f.live.core.server.createCommandSourceStack(), id);
            c.assertValueEqual(order.state, AgentActions.State.PENDING, "Separate console review still cannot bypass the missing owner approval");
            c.assertTrue(f.live.commands.isEmpty(), "No recovery or approval attempt dispatches a command");
        }
        c.succeed();
    }

    @GameTest public void encodedOrderReviewRechecksOpModeCursorAndReplacement(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-context")) {
            for (String change : List.of("permission", "mode", "cursor", "transition", "replacement")) {
                f.live.queue.data.proposals.clear(); f.open(); click(f.last(), "Propose: set day");
                String id = f.proposal(); var order = f.live.queue.data.proposals.get(id); var review = f.last();
                switch (change) {
                    case "permission" -> OperatorGameTests.level(f.live.owner, LevelBasedPermissionSet.ADMIN);
                    case "mode" -> f.live.owner.setGameMode(GameType.CREATIVE);
                    case "cursor" -> f.live.owner.inventoryMenu.setCarried(new ItemStack(Items.DIAMOND));
                    case "transition" -> GameModes.TRANSITIONS.add(f.live.owner.getUUID());
                    case "replacement" -> f.open();
                    default -> throw new IllegalStateException(change);
                }
                click(review, "Approve as owner only");
                c.assertValueEqual(order.state, AgentActions.State.PENDING, "Changed " + change + " cannot approve the displayed order");
                c.assertTrue(f.live.commands.isEmpty(), "Changed " + change + " cannot dispatch");
                OperatorGameTests.level(f.live.owner, LevelBasedPermissionSet.OWNER);
                f.live.owner.setGameMode(GameType.SURVIVAL); f.live.owner.inventoryMenu.setCarried(ItemStack.EMPTY);
                GameModes.TRANSITIONS.remove(f.live.owner.getUUID());
            }
        }
        c.succeed();
    }

    @GameTest public void encodedPendingQueueShowsExactIdsAndCanCancelWithoutDispatch(GameTestHelper c) throws Exception {
        try (var f = new Fixture(c, "touch-pending")) {
            for (String request : List.of("set day", "set night", "save world")) { f.open(); click(f.last(), "Propose: " + request); }
            f.open(); click(f.last(), "Propose: list players");
            c.assertTrue(text(f.last()).contains("queue is full"), "Queue refusal is visible in the pending native form");
            c.assertValueEqual(f.live.queue.data.proposals.size(), 3, "Queue limit cannot be bypassed through native forms");
            String id = f.proposal();
            for (String key : f.live.queue.data.proposals.keySet()) c.assertTrue(text(f.last()).contains(key), "Pending form displays every exact owned ID");
            String foreign = UUID.randomUUID().toString();
            var foreignOrder = new AgentActions.Proposal(f.live.target.getStringUUID(), AgentActions.Action.CLEAR, f.live.clock.millis());
            f.live.queue.data.proposals.put(foreign, foreignOrder);
            click(f.last(), "Refresh pending orders");
            c.assertFalse(text(f.last()).contains(foreign), "Another owner's proposal is never exposed as a selectable pending order");
            click(f.last(), "Review: /time set day"); click(f.last(), "Cancel this order");
            c.assertValueEqual(f.live.queue.data.proposals.get(id).state, AgentActions.State.CANCELLED, "Cancel applies only to the reviewed owned order");
            c.assertValueEqual(foreignOrder.state, AgentActions.State.PENDING, "Reviewing and cancelling an owned order leaves the foreign proposal unchanged");
            c.assertTrue(text(f.last()).contains("Cancelled ID: " + id), "Cancellation is visible in a native result page");
            c.assertTrue(f.live.commands.isEmpty(), "Review, queue-full handling and cancellation never dispatch");
            f.open(); click(f.last(), "Back to AI Helpers");
            c.assertTrue(f.last() != null && f.live.owner.containerMenu == f.live.owner.inventoryMenu, "Back returns through native helper controls without a chest");
        }
        c.succeed();
    }
}
