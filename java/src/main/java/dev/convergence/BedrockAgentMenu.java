package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.IronGolem;
import org.geysermc.cumulus.form.Form;

/** Native touch controls share the existing helper mutators and exact-session form transport. */
final class BedrockAgentMenu {
    private BedrockAgentMenu() {}

    static int open(ServerPlayer owner) { return open(owner, HelperForms.sender(owner)); }
    static int open(ServerPlayer owner, Predicate<Form> sender) {
        if (!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner)) {
            HelperForms.message(owner, "Close other screens before using helpers. A living OP4 owner is required.");
            return 0;
        }
        return roster(owner, "", sender);
    }

    private static AgentCompanions helpers(ServerPlayer owner) {
        return AgentCompanions.get(owner.level().getServer());
    }

    private static Map<String, AgentCompanions.Agent> snapshot(ServerPlayer owner) {
        var result = new LinkedHashMap<String, AgentCompanions.Agent>();
        helpers(owner).data.agents.entrySet().stream()
            .filter(e -> e.getValue().owner().equals(owner.getStringUUID()))
            .sorted(Comparator.comparing(e -> e.getValue().name()))
            .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static boolean current(ServerPlayer owner, Map<String, AgentCompanions.Agent> saved) {
        var now = snapshot(owner);
        return now.size() == saved.size() && saved.entrySet().stream().allMatch(e -> now.get(e.getKey()) == e.getValue());
    }

    private static boolean current(ServerPlayer owner, String id, AgentCompanions.Agent agent, IronGolem loaded) {
        return agent != null && agent.owner().equals(owner.getStringUUID())
            && helpers(owner).data.agents.get(id) == agent
            && helpers(owner).loaded.get(UUID.fromString(id)) == loaded;
    }

    private static IronGolem live(AgentCompanions helpers, String id) {
        var golem = helpers.loaded.get(UUID.fromString(id));
        return golem != null && golem.isAlive() && !golem.isRemoved()
            && AgentCompanions.isAgent(golem) && golem.level() instanceof ServerLevel level
            && level.getEntity(golem.getUUID()) == golem ? golem : null;
    }

    private static String withFeedback(String feedback, String text) {
        return feedback.isEmpty() ? text : feedback + "\n\n" + text;
    }

    private static int roster(ServerPlayer owner, String feedback, Predicate<Form> sender) {
        var helpers = helpers(owner);
        var saved = snapshot(owner);
        var choices = new ArrayList<HelperForms.Choice>();
        if (saved.size() < AgentCompanions.LIMIT)
            choices.add(new HelperForms.Choice(saved.isEmpty() ? "Create your first helper" : "Create another helper",
                () -> create(owner, "", sender)));
        for (var entry : saved.entrySet()) {
            String id = entry.getKey(); var agent = entry.getValue();
            choices.add(new HelperForms.Choice(agent.name() + " • " + agent.profile().label()
                + (live(helpers, id) == null ? " • unloaded" : " • " + agent.mode().name().toLowerCase(java.util.Locale.ROOT)),
                () -> detail(owner, id, "", sender)));
        }
        if (!saved.isEmpty()) choices.add(new HelperForms.Choice("Squad controls • movement, profiles and recall", () -> squad(owner, "", sender)));
        choices.add(new HelperForms.Choice("Orders • targets and both approvals", () -> BedrockAgentOrdersMenu.open(owner, sender)));
        choices.add(new HelperForms.Choice("Back to Infinity Menu", () -> ServerMenu.open(owner)));
        String content = "Your helpers: " + saved.size() + "/" + AgentCompanions.LIMIT + ".\n"
            + (saved.isEmpty() ? "No helpers yet. Create one, then choose its profile. Nothing spawns just by opening this screen."
                : "Choose a named helper for its live status and controls. Unloaded helpers stay saved; these menus never load distant chunks.")
            + "\n\nRegular follows and guards. Ultimate Finals uses a real spear charge, elytra flight and a falling mace strike when the target and terrain allow it."
            + "\nPlayer targets and server-changing AI orders still require your approval and separate live Codex review.\n" + HelperCombatStatus.squadSummary(owner);
        return HelperForms.show(owner, "Your Helper Squad", withFeedback(feedback, content), choices, () -> current(owner, saved), sender);
    }

    private static int create(ServerPlayer owner, String feedback, Predicate<Form> sender) {
        var saved = snapshot(owner);
        var choices = new ArrayList<HelperForms.Choice>();
        if (saved.size() < AgentCompanions.LIMIT) {
            for (var profile : creationProfiles())
                choices.add(new HelperForms.Choice("Create: " + profile.label(), () -> spawn(owner, profile, sender)));
        }
        choices.add(new HelperForms.Choice("Back to your squad", () -> roster(owner, "", sender)));
        String content = "Choose the new helper's profile. It appears on clear solid ground beside you; the screen closes on success so you can see it.\n"
            + "Regular: follows/guards and fights nearby hostile mobs.\nUltimate Finals: shared squad focus, native spear charge, brief elytra flight, then a falling mace strike.\n"
            + "Primitive seeks hostile mobs. Debug, CLI and API are passive physical profiles. CLI does not edit or run commands automatically; API never sends a question just by opening a screen.\n"
            + "Player attacks need both approvals. Maximum " + AgentCompanions.LIMIT + " per owner and " + AgentCompanions.GLOBAL_LIMIT + " server-wide, including unloaded helpers.";
        return HelperForms.show(owner, "Create a helper", withFeedback(feedback, content), choices, () -> current(owner, saved), sender);
    }

    private static List<AgentCompanions.Profile> creationProfiles() {
        return List.of(AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.ULTIMATE_FINALS,
            AgentCompanions.Profile.PRIMITIVE, AgentCompanions.Profile.DEBUG, AgentCompanions.Profile.CLI, AgentCompanions.Profile.API);
    }

    private static void spawn(ServerPlayer owner, AgentCompanions.Profile profile, Predicate<Form> sender) {
        var helpers = helpers(owner);
        String name = AgentMenu.nextName(helpers, owner);
        if (name == null || helpers.count(owner) >= AgentCompanions.LIMIT) {
            roster(owner, "All your helper slots are in use. Dismiss one only if you want to free its slot.", sender); return;
        }
        if (helpers.data.agents.size() >= AgentCompanions.GLOBAL_LIMIT) {
            create(owner, "The server's helper slots are full, including unloaded helpers. Nothing was created.", sender); return;
        }
        helpers.spawn(owner, name);
        var entry = helpers.owned(owner, name);
        if (entry == null || live(helpers, entry.getKey()) == null) {
            create(owner, "No helper appeared. Move onto clear solid ground with open space around you and try again. No existing helper was replaced.", sender); return;
        }
        helpers.profile(owner, name, profile);
        var message = Component.literal(name + " appeared beside you • " + profile.label() + ". Reopen AI Helpers to give orders.");
        owner.sendSystemMessage(message);
        owner.sendOverlayMessage(message);
        // The form response already closes the native form. Do not immediately
        // reopen a screen over the actual newly spawned helper.
    }

    private static int detail(ServerPlayer owner, String id, String feedback, Predicate<Form> sender) {
        var helpers = helpers(owner);
        var agent = helpers.data.agents.get(id);
        if (agent == null || !agent.owner().equals(owner.getStringUUID()))
            return roster(owner, "That helper is no longer in your roster. Choose it again.", sender);
        var captured = helpers.loaded.get(UUID.fromString(id));
        var golem = live(helpers, id);
        var choices = new ArrayList<HelperForms.Choice>();
        if (golem != null && golem.level() == owner.level()) {
            for (var mode : AgentCompanions.Mode.values()) choices.add(new HelperForms.Choice(
                switch (mode) { case FOLLOW -> "Follow me"; case GUARD -> "Guard this helper's current area"; case STAY -> "Stay / pause"; },
                () -> { helpers.mode(owner, agent.name(), mode); detail(owner, id, "Movement updated: " + mode.name().toLowerCase(java.util.Locale.ROOT) + ".", sender); }));
        }
        choices.add(new HelperForms.Choice("Choose profile • " + agent.profile().label(), () -> profiles(owner, id, sender)));
        if (golem != null) choices.add(new HelperForms.Choice("Bring this helper here • then follow", () -> {
            helpers.recall(owner, agent.name());
            if (helpers.data.agents.get(id) == agent)
                detail(owner, id, "Bring here could not move this helper. Make room on solid ground, remove passengers, then try again. Its saved roster and stats were kept.", sender);
            else owner.sendOverlayMessage(Component.literal(agent.name() + " is beside you and following. Health, stats and profile were kept."));
        }));
        choices.add(new HelperForms.Choice("Refresh live status", () -> detail(owner, id, "Status refreshed.", sender)));
        choices.add(new HelperForms.Choice(ServerAssistant.codexEnabled(helpers.server) ? "Ask Codex • send this helper's facts" : "Ask helper • request guidance", () -> {
            AgentChat.ask(owner.createCommandSourceStack(), agent.name(), AgentMenu.ASK_QUESTION);
        }));
        choices.add(new HelperForms.Choice("Orders • targets and both approvals", () -> BedrockAgentOrdersMenu.open(owner, sender)));
        choices.add(new HelperForms.Choice("Ceasefire • clear player-target orders", () -> {
            AgentActions.get(helpers.server).ceasefire(owner); detail(owner, id, "Player target and pending player-target orders cleared.", sender);
        }));
        choices.add(new HelperForms.Choice("Dismiss helper • review first", () -> dismiss(owner, id, sender)));
        choices.add(new HelperForms.Choice("Back to your squad", () -> roster(owner, "", sender)));
        String content = HelperCombatStatus.summary(owner, id, agent) + "\n\n" + agent.profile().description()
            + "\n" + (golem == null ? "Unloaded: return near its saved location to load it. Movement and recall are unavailable."
                : golem.level() != owner.level() ? "In another world: use Bring here before giving movement orders." : "Movement applies to this helper only.")
            + "\nAsk sends limited Minecraft facts to the configured service only when tapped. Answers cannot execute commands.\n" + HelperCombatStatus.squadSummary(owner);
        return HelperForms.show(owner, agent.name() + " • Helper", withFeedback(feedback, content), choices,
            () -> current(owner, id, agent, captured) && (golem == null || live(helpers, id) == golem), sender);
    }

    private static int profiles(ServerPlayer owner, String id, Predicate<Form> sender) {
        var helpers = helpers(owner); var agent = helpers.data.agents.get(id);
        if (agent == null || !agent.owner().equals(owner.getStringUUID())) return roster(owner, "Choose your helper again.", sender);
        var captured = helpers.loaded.get(UUID.fromString(id));
        var choices = new ArrayList<HelperForms.Choice>();
        var content = new StringBuilder("Current: ").append(agent.profile().label()).append(". Movement and edited stats are kept.\n");
        for (var profile : creationProfiles()) {
            content.append("\n").append(profile.label()).append(": ").append(profile.description());
            choices.add(new HelperForms.Choice("Choose: " + profile.label(), () -> {
                helpers.profile(owner, agent.name(), profile); detail(owner, id, "Profile updated: " + profile.label() + ".", sender);
            }));
        }
        choices.add(new HelperForms.Choice("Back to helper", () -> detail(owner, id, "", sender)));
        return HelperForms.show(owner, agent.name() + " • Profiles", content.toString(), choices, () -> current(owner, id, agent, captured), sender);
    }

    private static int dismiss(ServerPlayer owner, String id, Predicate<Form> sender) {
        var helpers = helpers(owner); var agent = helpers.data.agents.get(id);
        if (agent == null || !agent.owner().equals(owner.getStringUUID())) return roster(owner, "Choose your helper again.", sender);
        var captured = helpers.loaded.get(UUID.fromString(id));
        return HelperForms.show(owner, "Dismiss " + agent.name() + "?",
            "This removes this exact helper, including its saved identity and edited stats. It does not merely pause it. Unloaded entities are removed when their area next loads. Choose Keep to cancel.",
            List.of(new HelperForms.Choice("Confirm dismiss " + agent.name(), () -> {
                helpers.dismiss(owner, agent.name()); roster(owner, agent.name() + " was dismissed.", sender);
            }), new HelperForms.Choice("Keep this helper", () -> detail(owner, id, "Kept this helper. Nothing was removed.", sender))),
            () -> current(owner, id, agent, captured), sender);
    }

    private static int squad(ServerPlayer owner, String feedback, Predicate<Form> sender) {
        var helpers = helpers(owner); var saved = snapshot(owner);
        if (saved.isEmpty()) return roster(owner, "Create your first helper before using squad controls.", sender);
        var choices = new ArrayList<HelperForms.Choice>();
        long loaded = saved.keySet().stream().filter(id -> live(helpers, id) != null).count();
        long sameWorld = saved.keySet().stream().map(id -> live(helpers, id)).filter(g -> g != null && g.level() == owner.level()).count();
        if (sameWorld > 0) for (var mode : AgentCompanions.Mode.values()) choices.add(new HelperForms.Choice(
            "Squad: " + mode.name().toLowerCase(java.util.Locale.ROOT), () -> {
                helpers.squad(owner, mode); squad(owner, "Requested " + mode.name().toLowerCase(java.util.Locale.ROOT) + " for loaded helpers in this world. Unloaded and other-world helpers were skipped.", sender);
            }));
        for (var profile : List.of(AgentCompanions.Profile.PRIMITIVE, AgentCompanions.Profile.REGULAR, AgentCompanions.Profile.ULTIMATE_FINALS))
            choices.add(new HelperForms.Choice("Squad profile: " + profile.label(), () -> {
                helpers.squadProfile(owner, profile); squad(owner, "Saved squad profile: " + profile.label() + ". Movement and edited stats are kept.", sender);
            }));
        if (loaded > 0) choices.add(new HelperForms.Choice("Bring loaded squad here", () -> {
            helpers.recallSquad(owner);
            long nearby = snapshot(owner).keySet().stream().map(id -> live(helpers, id))
                .filter(g -> g != null && g.level() == owner.level() && g.distanceToSqr(owner) < 36).count();
            squad(owner, "Bring here finished: " + nearby + " helpers now nearby. Unloaded helpers and those without safe landing space were kept where they were.", sender);
        }));
        choices.add(new HelperForms.Choice("Cycle follow formation • " + AgentCompanions.formation(owner), () -> {
            AgentCompanions.cycleFormation(owner); squad(owner, "Follow formation: " + AgentCompanions.formation(owner) + ". Combat approvals are separate.", sender);
        }));
        choices.add(new HelperForms.Choice("Ceasefire • clear player-target orders", () -> {
            AgentActions.get(helpers.server).ceasefire(owner); squad(owner, "Player target and pending player-target orders cleared.", sender);
        }));
        choices.add(new HelperForms.Choice("Orders • targets and both approvals", () -> BedrockAgentOrdersMenu.open(owner, sender)));
        choices.add(new HelperForms.Choice("Back to your squad", () -> roster(owner, "", sender)));
        String content = saved.size() + " saved helpers; " + loaded + " loaded; " + sameWorld + " loaded in this world.\n"
            + "Movement affects loaded helpers here. Profiles also save for unloaded helpers. Bring here never loads distant chunks and clears player-target orders.\n"
            + "Ultimate Finals shares focus and uses native spear, elytra and mace tactics when conditions allow. STAY must be changed to Follow/Guard to resume.\n" + HelperCombatStatus.squadSummary(owner);
        return HelperForms.show(owner, "Squad controls", withFeedback(feedback, content), choices, () -> current(owner, saved), sender);
    }
}
