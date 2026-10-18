package dev.convergence;

import java.util.Locale;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.IronGolem;

/** Read-only explanations of the existing helper controls and combat checks. */
final class HelperCombatStatus {
    private HelperCombatStatus() {}

    static String summary(ServerPlayer owner, String id, AgentCompanions.Agent agent) {
        String ownerProblem = ownerProblem(owner);
        if (ownerProblem != null) return "Paused: " + ownerProblem;
        var helpers = AgentCompanions.get(owner.level().getServer());
        if (agent == null || id == null || helpers.data.agents.get(id) != agent
            || !agent.owner().equals(owner.getStringUUID()))
            return "That helper changed or is no longer in your roster. Choose it again.";
        UUID helperId;
        try { helperId = UUID.fromString(id); }
        catch (IllegalArgumentException invalid) { return "That helper is unavailable. Choose it again."; }
        var golem = helpers.loaded.get(helperId);
        String pause = helpers.pauseReason(owner, golem, agent);
        var text = new StringBuilder(agent.profile().label()).append(" • ")
            .append(agent.mode().name().toLowerCase(Locale.ROOT)).append('\n');
        if (pause != null) {
            text.append("Paused: ").append(pause).append('.');
            if (golem == null) text.append(" Visit its saved area; Bring here cannot load distant chunks.");
            else if (agent.mode() == AgentCompanions.Mode.STAY) text.append(" Choose Follow or Guard to resume.");
            else if (owner.level() != golem.level() || golem.distanceToSqr(owner) > 48 * 48)
                text.append(" Bring here can recall an already loaded helper to clear ground.");
            text.append('\n');
        }
        switch (agent.profile()) {
            case REGULAR -> text.append("Hostile defense only. Choose Ultimate Finals for spear/glide/mace combos.");
            case PRIMITIVE -> text.append("Hostile defense; combos need Ultimate Finals.\n")
                .append(playerOrder(helpers, owner).message());
            case DEBUG, CLI, API -> text.append("Combat is off for this passive profile. Follow and Guard still control movement.");
            case ULTIMATE_FINALS -> ultimate(text, helpers, owner, helperId, golem, pause);
        }
        return text.toString();
    }

    static String squadSummary(ServerPlayer owner) {
        String ownerProblem = ownerProblem(owner);
        if (ownerProblem != null) return "Paused: " + ownerProblem;
        var helpers = AgentCompanions.get(owner.level().getServer());
        int count = 0, loaded = 0, combo = 0;
        for (var entry : helpers.data.agents.entrySet()) {
            var agent = entry.getValue();
            if (!agent.owner().equals(owner.getStringUUID())) continue;
            count++;
            var golem = helpers.loaded.get(UUID.fromString(entry.getKey()));
            if (golem == null || !golem.isAlive() || golem.isRemoved()) continue;
            loaded++;
            if (agent.profile() == AgentCompanions.Profile.ULTIMATE_FINALS
                && helpers.pauseReason(owner, golem, agent) == null) combo++;
        }
        if (count == 0) return "No helpers yet. Create your first helper on clear solid ground. Follow and combo controls need a helper.";
        return "Helpers: " + count + "/" + AgentCompanions.LIMIT + "; " + loaded + " loaded; " + combo
            + " Ultimate Finals ready for target checks.\n"
            + "Combos need Ultimate Finals on Follow or Guard. Player attacks require owner and live Codex approval.";
    }

    private static void ultimate(StringBuilder text, AgentCompanions helpers, ServerPlayer owner,
                                 UUID id, IronGolem golem, String pause) {
        var order = playerOrder(helpers, owner);
        if (golem != null && golem.isAlive() && !golem.isRemoved()) {
            var held = golem.getMainHandItem();
            var target = golem.getTarget();
            var sequence = helpers.aerial.get(id);
            boolean blockedPlayer = target instanceof ServerPlayer && order.target() != target
                || sequence != null && sequence.target instanceof ServerPlayer && order.target() != sequence.target;
            text.append("Held: ").append(held.isEmpty() ? "empty" : plain(held.getHoverName().getString()))
                .append("; gliding: ").append(golem.isFallFlying() ? "yes" : "no")
                .append("; target: ").append(target == null ? "none" : plain(target.getName().getString())).append(".\n");
            if (pause != null) text.append("Combo paused.\n");
            else if (blockedPlayer) text.append("Player combo blocked: this player is not the current eligible reviewed target.\n");
            else if (sequence != null) text.append("Combo sequence in progress.\n");
            else if (target != null) text.append("Spear/glide/mace sequence runs when combat and terrain checks pass.\n");
            else if (order.target() == null) text.append("Waiting for an eligible hostile.\n");
            else if (!helpers.playerPursuitReady(golem, helpers.data.agents.get(id.toString()), owner, order.target()))
                text.append("Reviewed player target is outside this helper's movement area.\n");
            else text.append("Waiting for the reviewed player target.\n");
            int remaining = Math.max(0, helpers.nextLeap.getOrDefault(id, 0) - helpers.server.getTickCount());
            text.append(remaining == 0 ? "Aerial cooldown ready." : "Aerial cooldown: " + ((remaining + 19) / 20) + "s.")
                .append(" A leap still needs a clear, dry, level route at 4–7 blocks.\n");
        } else text.append(golem == null ? "Combo unavailable until this helper is loaded.\n"
            : "Combo unavailable while this helper is unavailable.\n");
        text.append(order.message());
    }

    private record PlayerOrder(ServerPlayer target, String message) {}

    /** Do not call validPlayerTarget/playerTargetStatus: those can ceasefire an expired order. */
    private static PlayerOrder playerOrder(AgentCompanions helpers, ServerPlayer owner) {
        var order = helpers.playerTargets.get(owner.getUUID());
        if (order == null) return new PlayerOrder(null, "Player attacks require an exact target, owner approval and live Codex approval.");
        long now = helpers.clock.millis();
        if (order.owner() != owner || order.ownerConnection() != owner.connection || order.target() == null
            || order.targetConnection() != order.target().connection || order.world() != owner.level()
            || order.world() != order.target().level() || now < order.issuedAt() || now >= order.expiresAt())
            return new PlayerOrder(null, "Player order expired or its session changed. A new target needs both approvals.");
        String reason = helpers.targetEligibility(owner, order.target());
        if (reason != null) return new PlayerOrder(null, "Player order unavailable: " + reason);
        return new PlayerOrder(order.target(), "Reviewed player target: " + plain(order.target().getName().getString())
            + ". Eligibility, reach and line of sight still apply.");
    }

    private static String ownerProblem(ServerPlayer owner) {
        if (owner == null) return "owner offline.";
        if (owner.connection == null || owner.hasDisconnected() || owner.isRemoved()
            || owner.level().getServer().getPlayerList().getPlayer(owner.getUUID()) != owner)
            return "owner must remain connected.";
        if (!AgentCompanions.operator(owner.createCommandSourceStack())) return "owner needs OP4.";
        if (!owner.isAlive()) return "owner dead.";
        if (owner.isSpectator()) return "owner in spectator mode.";
        return null;
    }

    private static String plain(String value) {
        String text = value.replaceAll("[\\p{Cntrl}§]", " ").strip();
        return text.length() <= 48 ? text : text.substring(0, 47) + "…";
    }
}
