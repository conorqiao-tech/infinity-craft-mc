package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import org.geysermc.cumulus.form.Form;

/** Native order previews supply owner consent only; live Codex approval remains separate. */
final class BedrockAgentOrdersMenu {
    private static final int PAGE_SIZE = 45;
    private static final List<String> ACTIONS = List.of("set day", "set night", "clear weather", "make it rain", "save world", "list players");
    private static final String TARGET_HELP = "First use Create helper in AI Helpers, then choose the Primitive or Ultimate Finals profile. "
        + "Keep that helper loaded and nearby. A different player must be alive in the same world, within 48 blocks, with PvP allowed.";
    private BedrockAgentOrdersMenu() {}

    static int open(ServerPlayer owner) { return open(owner, HelperForms.sender(owner)); }
    static int open(ServerPlayer owner, Predicate<Form> sender) { return main(owner, "", sender); }
    static int openPending(ServerPlayer owner, Predicate<Form> sender) { return pending(owner, "Player target proposed only. Both approvals are still required.", sender); }

    private static String content(String notice, String text) { return notice.isEmpty() ? text : notice + "\n\n" + text; }
    private static HelperForms.Choice choice(String label, Runnable action) { return new HelperForms.Choice(label, action); }

    private static int main(ServerPlayer owner, String notice, Predicate<Form> sender) {
        var choices = new ArrayList<HelperForms.Choice>();
        choices.add(choice("Choose player target", () -> targets(owner, 0, "", sender)));
        choices.add(choice("Pending orders • approve or cancel", () -> pending(owner, "", sender)));
        choices.add(choice("Choose any nearby living target", () -> AgentEntityTargetMenu.openBedrock(owner, sender)));
        for (String request : ACTIONS) choices.add(choice("Propose: " + request, () -> propose(owner, request, sender)));
        choices.add(choice("Back to AI Helpers", () -> BedrockAgentMenu.open(owner, sender)));
        return HelperForms.show(owner, "Helper Orders", content(notice,
            "Choose an exact proposal, review its ID, then approve it as owner. Nothing runs or attacks from a proposal or owner approval alone. "
            + "Ask Codex in this chat for a separate live review of that exact ID before it expires.\n\n" + TARGET_HELP), choices, () -> true, sender);
    }

    private static int targets(ServerPlayer owner, int index, String notice, Predicate<Form> sender) {
        var server = owner.level().getServer();
        var players = server.getPlayerList().getPlayers().stream().filter(p -> p != owner && p.level() == owner.level())
            .sorted(Comparator.comparing(p -> p.getName().getString())).map(AdminStatsMenu.Session::new).toList();
        int pages = Math.max(1, (players.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(index, pages - 1));
        var choices = new ArrayList<HelperForms.Choice>();
        var text = new StringBuilder(TARGET_HELP).append("\n\n");
        if (players.isEmpty()) text.append("No other players are currently in this world. Create a helper and choose a compatible profile first, then have the target player join this world.");
        for (int i = page * PAGE_SIZE; i < Math.min(players.size(), (page + 1) * PAGE_SIZE); i++) {
            var target = players.get(i);
            String reason = AgentCompanions.get(server).targetEligibility(owner, target.player());
            text.append(target.player().getName().getString()).append(": ").append(reason == null ? "Available for a proposal." : reason).append('\n');
            choices.add(choice((reason == null ? "Propose target: " : "Unavailable: ") + target.player().getName().getString(),
                () -> proposeTarget(owner, target, page, sender)));
        }
        if (page > 0) choices.add(choice("Previous players", () -> targets(owner, page - 1, "", sender)));
        if (page + 1 < pages) choices.add(choice("Next players", () -> targets(owner, page + 1, "", sender)));
        choices.add(choice("Back to Orders", () -> main(owner, "", sender)));
        return HelperForms.show(owner, "Choose player target", content(notice, text.toString()), choices, () -> true, sender);
    }

    private static void propose(ServerPlayer owner, String request, Predicate<Form> sender) {
        var queue = AgentActions.get(owner.level().getServer());
        queue.expire();
        if (queue.queueFull(owner)) { pending(owner, "The order queue is full. Cancel an order or wait for expiry before proposing another.", sender); return; }
        var previous = Set.copyOf(queue.data.proposals.keySet());
        try { queue.suggest(owner, request); }
        catch (IllegalStateException failure) { pending(owner, "The proposal could not be saved. This form dispatched nothing. Review pending orders before continuing.", sender); return; }
        String id = queue.data.proposals.keySet().stream().filter(key -> !previous.contains(key)).findFirst().orElse(null);
        if (id == null) pending(owner, "The proposal was refused. Nothing ran. Reopen Orders and try again.", sender);
        else review(owner, id, "Proposed only. Your approval and a separate live Codex approval are still required.", sender);
    }

    private static void proposeTarget(ServerPlayer owner, AdminStatsMenu.Session target, int page, Predicate<Form> sender) {
        if (!target.valid()) { targets(owner, page, "That exact player session changed. No target was proposed. Select the current player again.", sender); return; }
        var queue = AgentActions.get(owner.level().getServer());
        String reason = AgentCompanions.get(queue.server).targetEligibility(owner, target.player());
        if (reason != null) { targets(owner, page, "Target unavailable: " + reason + "\n" + TARGET_HELP, sender); return; }
        queue.expire();
        if (queue.queueFull(owner)) { pending(owner, "The order queue is full. Cancel an order or wait for expiry.", sender); return; }
        var previous = Set.copyOf(queue.data.proposals.keySet());
        try { queue.target(owner, target.player()); }
        catch (IllegalStateException failure) { pending(owner, "The target proposal could not be saved. This form started no player combat. Review pending orders before continuing.", sender); return; }
        String id = queue.data.proposals.keySet().stream().filter(key -> !previous.contains(key)).findFirst().orElse(null);
        if (id == null) targets(owner, page, "The target proposal was refused. Nothing attacks. Review this player's availability and try again.", sender);
        else review(owner, id, "Target proposed only. Both approvals are still required before any player attack.", sender);
    }

    /** Pure context: even a same-ID replacement or a rewritten expiry needs a fresh review. */
    private record Review(AgentActions queue, String id, AgentActions.Proposal proposal, AgentActions.State state,
            AgentActions.Action action, String ownerId, String description, long createdAt,
            AgentActions.TargetSession targetBinding, AdminStatsMenu.Session target) {
        Review(AgentActions queue, String id, AgentActions.Proposal proposal) {
            this(queue, id, proposal, proposal.state, proposal.action, proposal.owner, queue.reviewDescription(proposal), proposal.createdAt,
                queue.targetSessions.get(id), queue.targetSessions.get(id) == null ? null : new AdminStatsMenu.Session(queue.targetSessions.get(id).target()));
        }
        boolean valid(ServerPlayer owner) {
            long now = queue.clock.millis();
            if (AgentActions.INSTANCES.get(queue.server) != queue || queue.data.proposals.get(id) != proposal
                    || !ownerId.equals(owner.getStringUUID()) || !ownerId.equals(proposal.owner)
                    || !proposal.active() || proposal.state != state || proposal.action != action || proposal.createdAt != createdAt
                    || now < createdAt || now - createdAt >= AgentActions.LIFETIME_MS || !description.equals(queue.reviewDescription(proposal))) return false;
            return action != AgentActions.Action.TARGET || target != null && target.valid()
                && queue.targetSessions.get(id) == targetBinding && queue.targetProblem(id, proposal) == null;
        }
        String status() {
            return state == AgentActions.State.PENDING ? "Needs your owner approval. Codex has not approved execution."
                : "Owner approved. Waiting for a separate live Codex review; nothing has run from this form.";
        }
        long secondsRemaining() { return Math.max(0, (AgentActions.LIFETIME_MS - (queue.clock.millis() - createdAt) + 999) / 1000); }
    }

    private static int pending(ServerPlayer owner, String notice, Predicate<Form> sender) {
        var queue = AgentActions.get(owner.level().getServer());
        queue.expire();
        var snapshots = queue.data.proposals.entrySet().stream()
            .filter(e -> e.getValue().active() && e.getValue().owner.equals(owner.getStringUUID()))
            .map(e -> new Review(queue, e.getKey(), e.getValue())).toList();
        var choices = new ArrayList<HelperForms.Choice>();
        var text = new StringBuilder("Each order needs your approval and a separate live Codex approval.\n");
        if (snapshots.isEmpty()) text.append("\nNo pending orders. Return to Orders to create an exact proposal.");
        for (var snapshot : snapshots) {
            text.append("\nID: ").append(snapshot.id()).append('\n').append(snapshot.description()).append('\n').append(snapshot.status())
                .append("\nExpires in ").append(snapshot.secondsRemaining()).append(" seconds.\n");
            choices.add(choice(snapshot.proposal().action == AgentActions.Action.SQUAD_PLAN ? "Review GPT squad plan • " + snapshot.proposal().helperIds.size() + " helpers" : "Review: " + snapshot.description(), () -> review(owner, snapshot.id(), "", sender)));
        }
        choices.add(choice("Refresh pending orders", () -> pending(owner, "", sender)));
        choices.add(choice("Back to Orders", () -> main(owner, "", sender)));
        return HelperForms.show(owner, "Pending helper orders", content(notice, text.toString()), choices,
            () -> snapshots.stream().allMatch(snapshot -> snapshot.valid(owner)), sender);
    }

    private static int review(ServerPlayer owner, String id, String notice, Predicate<Form> sender) {
        var queue = AgentActions.get(owner.level().getServer());
        queue.expire();
        var proposal = queue.data.proposals.get(id);
        if (proposal == null || !proposal.active() || !proposal.owner.equals(owner.getStringUUID()))
            return pending(owner, "That order expired, changed, or does not belong to you. Nothing ran.", sender);
        var snapshot = new Review(queue, id, proposal);
        var choices = new ArrayList<HelperForms.Choice>();
        if (snapshot.state() == AgentActions.State.PENDING)
            choices.add(choice("Approve as owner only", () -> decide(owner, snapshot, false, sender)));
        else choices.add(choice("Refresh approval status", () -> review(owner, id, "", sender)));
        choices.add(choice("Cancel this order", () -> decide(owner, snapshot, true, sender)));
        choices.add(choice("Back to pending orders", () -> pending(owner, "", sender)));
        String text = "ID: " + id + "\nExact order: " + snapshot.description() + "\n\n" + snapshot.status()
            + "\nExpires in " + snapshot.secondsRemaining() + " seconds.\n\nAsk Codex in this chat to review this exact ID before expiry. "
            + "Owner consent alone never runs the action or starts player combat.";
        return HelperForms.show(owner, "Review helper order", content(notice, text), choices, () -> snapshot.valid(owner), sender);
    }

    private static void decide(ServerPlayer owner, Review snapshot, boolean cancel, Predicate<Form> sender) {
        if (!snapshot.valid(owner)) { pending(owner, "The exact reviewed order changed or expired. Nothing ran. Review the current order again.", sender); return; }
        try {
            if (cancel) snapshot.queue().cancel(owner, snapshot.id());
            else snapshot.queue().ownerApprove(owner, snapshot.id());
        } catch (IllegalStateException failure) {
            pending(owner, "The order update could not be saved. This form dispatched nothing. Review pending orders before continuing.", sender); return;
        }
        if (cancel) pending(owner, "Cancelled ID: " + snapshot.id() + ". This order will not run.", sender);
        else review(owner, snapshot.id(), "Your approval was saved. Waiting for a separate live Codex approval of ID: " + snapshot.id(), sender);
    }
}
