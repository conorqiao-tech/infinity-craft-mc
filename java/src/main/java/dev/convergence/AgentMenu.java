package dev.convergence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.text.Text;

/** Read-only vanilla chest screens keep the helper controls usable through Geyser. */
final class AgentMenu {
    static final int FOLLOW = 28, GUARD = 30, STAY = 32, INFO = 34, CEASEFIRE = 37, ASK = 39, RECALL = 41, DISMISS = 43, BACK = 49;
    static final int CONFIRM = 11, CANCEL = 15;
    static final int ORDERS = 19;
    static final String ASK_QUESTION = "Explain my helper's current state and suggest what I should do next.";
    enum Page { ROSTER, HELPER, DISMISS }

    private AgentMenu() {}

    static boolean allowed(ServerPlayerEntity player) {
        return player.isAlive() && !player.isRemoved() && !player.isDisconnected() && !player.isSpectator()
            && player.getEntityWorld().getServer().getPlayerManager().getPlayer(player.getUuid()) == player
            && AgentCompanions.operator(player.getCommandSource());
    }

    static int helperSlot(int index) { return 10 + index; }
    static int profileSlot(int index) { return 10 + index; }

    static void icon(SimpleInventory view, int slot, Item item, String text) {
        var stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(text));
        view.setStack(slot, stack);
    }

    static void description(SimpleInventory view, int slot, String text) {
        var lines = new java.util.ArrayList<Text>();
        var line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > 43) {
                lines.add(Text.literal(line.toString())); line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(Text.literal(line.toString()));
        view.getStack(slot).set(DataComponentTypes.LORE, new LoreComponent(lines));
    }

    static Item profileIcon(AgentCompanions.Profile profile) {
        return switch (profile) {
            case PRIMITIVE -> Items.WOODEN_SWORD;
            case REGULAR -> Items.IRON_SWORD;
            case ULTIMATE_FINALS -> Items.NETHERITE_SWORD;
            case DEBUG -> Items.SPYGLASS;
            case CLI -> Items.REPEATER;
            case API -> Items.AMETHYST_SHARD;
        };
    }

    static String nextName(AgentCompanions helpers, ServerPlayerEntity owner) {
        for (int index = 1; index <= AgentCompanions.LIMIT; index++) {
            String name = "helper-" + index;
            if (helpers.owned(owner, name) == null) return name;
        }
        return null;
    }

    static int open(ServerPlayerEntity player) { return open(player, Page.ROSTER, null); }

    private static int open(ServerPlayerEntity player, Page page, String helperId) {
        if (!allowed(player)) {
            player.sendMessage(Text.literal("Helper controls require a living, non-spectator OP4 owner."), false);
            return 0;
        }
        if (player.currentScreenHandler != player.playerScreenHandler
            || !player.currentScreenHandler.getCursorStack().isEmpty()) {
            player.sendMessage(Text.literal("Close your current screen and empty the cursor before opening helpers."), false);
            return 0;
        }
        var helpers = AgentCompanions.get(player.getEntityWorld().getServer());
        var selected = helperId == null ? null : helpers.data.agents.get(helperId);
        if (page != Page.ROSTER && (selected == null || !selected.owner().equals(player.getUuidAsString()))) {
            player.sendMessage(Text.literal("That helper is no longer in your roster."), false);
            page = Page.ROSTER;
            helperId = null;
        }
        var view = new SimpleInventory(54);
        var choices = new LinkedHashMap<Integer, String>();
        String title;
        if (page == Page.ROSTER) {
            title = "Your Helper Squad";
            icon(view, 4, Items.IRON_GOLEM_SPAWN_EGG,
                "Helpers: " + helpers.count(player) + "/" + AgentCompanions.LIMIT);
            var roster = helpers.data.agents.entrySet().stream()
                .filter(entry -> entry.getValue().owner().equals(player.getUuidAsString()))
                .sorted(java.util.Comparator.comparing(entry -> entry.getValue().name())).toList();
            if (roster.isEmpty()) description(view, 4, "No helpers yet. Tap the green Create your first helper button.");
            for (int index = 0; index < AgentCompanions.LIMIT; index++) {
                int slot = helperSlot(index);
                if (index < roster.size()) {
                    var entry = roster.get(index);
                    var agent = entry.getValue();
                    choices.put(slot, entry.getKey());
                    rosterIcon(view, slot, helpers, entry.getKey(), agent);
                } else {
                    icon(view, slot, Items.LIME_DYE, roster.isEmpty() && index == 0
                        ? "Create your first helper" : "Create a helper • slot " + (index + 1));
                    description(view, slot, "Tap to summon a named iron golem beside you. Stand on clear solid ground.");
                }
            }
            movement(view, "Squad");
            icon(view, INFO, Items.BOOK, "Help • chat and approval commands");
            hive(view, helpers, player);
            icon(view, BACK, Items.ARROW, "Back to Infinity Menu");
        } else {
            title = selected.name() + (page == Page.DISMISS ? " • Dismiss?" : " • Helper");
            icon(view, 4, Items.IRON_INGOT, selected.name() + " • " + selected.profile().label());
            if (page == Page.DISMISS) {
                icon(view, CONFIRM, Items.RED_DYE, "Confirm dismiss " + selected.name());
                icon(view, CANCEL, Items.LIME_DYE, "Keep this helper");
            } else {
                profileChoices(view, selected);
                movement(view, selected.name());
                icon(view, INFO, Items.SPYGLASS, "Status • profile, location, and health");
                askButton(view, player);
                recallButton(view);
                icon(view, DISMISS, Items.RED_DYE, "Dismiss helper • confirmation required");
                hive(view, helpers, player);
            }
            icon(view, BACK, Items.ARROW, "Back to your squad");
        }
        Page openedPage = page;
        String openedId = helperId;
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, openedPage, openedId, choices), Text.literal(title)));
        return 1;
    }

    private static void movement(SimpleInventory view, String label) {
        icon(view, FOLLOW, Items.LEAD, label + " • follow");
        icon(view, GUARD, Items.SHIELD, label + " • guard current area");
        icon(view, STAY, Items.REDSTONE_TORCH, label + " • stay / pause");
    }

    private static void askButton(SimpleInventory view, ServerPlayerEntity owner) {
        boolean codex = ServerAssistant.codexEnabled(owner.getEntityWorld().getServer());
        icon(view, ASK, Items.WRITABLE_BOOK, codex ? "Ask Codex" : "Ask helper");
        description(view, ASK, (codex ? "Sends limited Minecraft facts to Codex using the host's signed-in account. "
            : "Uses the configured AI or local helper. Configured AI receives limited Minecraft facts. ")
            + "Explains this helper's state and suggests a next step. Answers cannot execute commands.");
    }

    private static void recallButton(SimpleInventory view) {
        icon(view, RECALL, Items.ENDER_PEARL, "Bring here • then follow");
        description(view, RECALL, "Moves this existing loaded helper beside you, even from another mode. Keeps health, stats and profile. Clears your squad's player-target orders and pending target approvals. Unloaded helpers must first be loaded by visiting their area.");
    }

    private static void rosterIcon(SimpleInventory view, int slot, AgentCompanions helpers, String id, AgentCompanions.Agent agent) {
        icon(view, slot, Items.IRON_INGOT,
            agent.name() + " • " + agent.profile().label() + " • " + agent.mode().name().toLowerCase(java.util.Locale.ROOT));
        var golem = helpers.loaded.get(UUID.fromString(id));
        description(view, slot, AgentCompanions.location(golem, agent)
            + (golem != null && golem.isAlive() && !golem.isRemoved()
                ? " • select, then Bring here to move it beside you" : " • return nearby to load this helper"));
    }

    private static void profileChoices(SimpleInventory view, AgentCompanions.Agent selected) {
        var profiles = AgentCompanions.Profile.values();
        for (int index = 0; index < profiles.length; index++) {
            var profile = profiles[index];
            icon(view, profileSlot(index), profileIcon(profile),
                (selected.profile() == profile ? "Selected: " : "Choose: ") + profile.label());
            description(view, profileSlot(index), profile.description());
        }
    }

    private static void hive(SimpleInventory view, AgentCompanions helpers, ServerPlayerEntity owner) {
        icon(view,ORDERS,Items.WRITABLE_BOOK,"Orders • targets and approvals");
        description(view,ORDERS,"Choose a player, propose an action, review, approve or cancel without typing a command. Live Codex review is still required.");
        icon(view, 22, Items.COMPASS, helpers.playerTargetStatus(owner));
        description(view, 22, "Use Orders to choose a target. Owner and live Codex approval are required.");
        icon(view, CEASEFIRE, Items.WHITE_BANNER, "Ceasefire • clear your player target and queued orders");
    }

    static final class Handler extends GenericContainerScreenHandler {
        final SimpleInventory view;
        final ServerPlayerEntity owner;
        final ServerPlayNetworkHandler ownerConnection;
        final UUID ownerId;
        final Page page;
        final String helperId;
        final Map<Integer, String> choices;

        Handler(int sync, PlayerInventory inventory, SimpleInventory view, ServerPlayerEntity owner,
                Page page, String helperId, Map<Integer, String> choices) {
            super(ScreenHandlerType.GENERIC_9X6, sync, inventory, view, 6);
            this.view = view;
            this.owner = owner;
            this.ownerConnection = owner.networkHandler;
            this.ownerId = owner.getUuid();
            this.page = page;
            this.helperId = helperId;
            this.choices = Map.copyOf(choices);
        }

        @Override public boolean canUse(PlayerEntity player) {
            return player == owner && ownerId.equals(player.getUuid()) && owner.networkHandler == ownerConnection && allowed(owner);
        }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot, int selected) { }

        private void reopen(Page next, String id) {
            owner.closeHandledScreen();
            open(owner, next, id);
        }

        /** Stable buttons update slots without sending a close/open pair to Geyser.
         * Identity or page changes still get a fresh sync ID, rejecting old-window packets. */
        private void refresh() {
            if (owner.currentScreenHandler != this) return;
            if (!canUse(owner)) { owner.closeHandledScreen(); return; }
            var helpers = AgentCompanions.get(owner.getEntityWorld().getServer());
            if (page == Page.ROSTER) {
                var currentIds = helpers.data.agents.entrySet().stream()
                    .filter(entry -> entry.getValue().owner().equals(ownerId.toString()))
                    .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                if (!currentIds.equals(java.util.Set.copyOf(choices.values()))) { reopen(Page.ROSTER, null); return; }
                icon(view, 4, Items.IRON_GOLEM_SPAWN_EGG, "Helpers: " + helpers.count(owner) + "/" + AgentCompanions.LIMIT);
                for (var choice : choices.entrySet()) {
                    var agent = selected(helpers, choice.getValue());
                    if (agent == null) { stale(); return; }
                    rosterIcon(view, choice.getKey(), helpers, choice.getValue(), agent);
                }
                hive(view, helpers, owner);
            } else if (page == Page.HELPER) {
                var agent = selected(helpers, helperId);
                if (agent == null) { stale(); return; }
                icon(view, 4, Items.IRON_INGOT, agent.name() + " • " + agent.profile().label());
                profileChoices(view, agent);
                hive(view, helpers, owner);
                askButton(view, owner);
                recallButton(view);
            }
            syncState();
        }

        private AgentCompanions.Agent selected(AgentCompanions helpers, String id) {
            var agent = helpers.data.agents.get(id);
            return agent != null && agent.owner().equals(ownerId.toString()) ? agent : null;
        }

        private void stale() {
            owner.sendMessage(Text.literal("Your helper roster changed. Choose the helper again."), false);
            reopen(Page.ROSTER, null);
        }

        @Override public void onSlotClick(int clicked, int button, SlotActionType action, PlayerEntity player) {
            // A packet for an old window cannot affect a newer screen or another owner.
            if (player != owner || owner.currentScreenHandler != this) return;
            if (!canUse(player)) { owner.closeHandledScreen(); return; }
            if ((action != SlotActionType.PICKUP && action != SlotActionType.QUICK_MOVE)
                || button < 0 || button > 1 || !getCursorStack().isEmpty()) { syncState(); return; }
            var helpers = AgentCompanions.get(owner.getEntityWorld().getServer());
            if(clicked==ORDERS && page!=Page.DISMISS) { owner.closeHandledScreen();AgentOrdersMenu.open(owner);return; }
            if (clicked == CEASEFIRE && page != Page.DISMISS) {
                AgentActions.get(helpers.server).ceasefire(owner);
                refresh();
                return;
            }
            if (page == Page.ROSTER) {
                if (clicked == BACK) { owner.closeHandledScreen(); ServerMenu.open(owner); return; }
                if (clicked >= helperSlot(0) && clicked < helperSlot(AgentCompanions.LIMIT)) {
                    String id = choices.get(clicked);
                    if (id != null) {
                        if (selected(helpers, id) == null) { stale(); return; }
                        reopen(Page.HELPER, id);
                    } else {
                        String name = nextName(helpers, owner);
                        // Reveal the nearby helper on success, and keep a failed spawn's
                        // explanation visible instead of hiding it behind another chest.
                        owner.closeHandledScreen();
                        if (name != null) {
                            helpers.spawn(owner, name);
                            if (helpers.owned(owner, name) != null) {
                                var message = Text.literal(name + " appeared beside you. Look for the named iron golem.");
                                owner.sendMessage(message, false);
                                owner.sendMessage(message, true);
                            }
                        } else owner.sendMessage(Text.literal("All helper slots are in use. Dismiss a helper to free one."), false);
                    }
                    return;
                }
                AgentCompanions.Mode movement = clicked == FOLLOW ? AgentCompanions.Mode.FOLLOW
                    : clicked == GUARD ? AgentCompanions.Mode.GUARD : clicked == STAY ? AgentCompanions.Mode.STAY : null;
                if (movement != null) {
                    helpers.squad(owner, movement);
                    refresh();
                    return;
                }
                if (clicked == INFO) {
                    owner.closeHandledScreen();
                    owner.sendMessage(Text.literal(
                        "Choose a helper and its profile, then tap Ask Codex or Ask helper for guidance. "
                        + "/agent ask <name> <message> lets you write your own question. "
                        + "Player targeting: /agent target <player>; /agent ceasefire stops it. "
                        + "CLI proposals: /agent pending, /agent approve <id>, /agent cancel <id>. "
                        + "Every server-changing proposal also waits for live Codex review; this menu cannot run it."), false);
                    return;
                }
            } else {
                if (clicked == BACK) { reopen(Page.ROSTER, null); return; }
                var agent = selected(helpers, helperId);
                if (agent == null) { stale(); return; }
                if (page == Page.DISMISS) {
                    if (clicked == CONFIRM) { helpers.dismiss(owner, agent.name()); reopen(Page.ROSTER, null); return; }
                    if (clicked == CANCEL) { reopen(Page.HELPER, helperId); return; }
                } else {
                    var profiles = AgentCompanions.Profile.values();
                    for (int index = 0; index < profiles.length; index++) if (clicked == profileSlot(index)) {
                        helpers.profile(owner, agent.name(), profiles[index]);
                        refresh();
                        return;
                    }
                    AgentCompanions.Mode movement = clicked == FOLLOW ? AgentCompanions.Mode.FOLLOW
                        : clicked == GUARD ? AgentCompanions.Mode.GUARD : clicked == STAY ? AgentCompanions.Mode.STAY : null;
                    if (movement != null) { helpers.mode(owner, agent.name(), movement); refresh(); return; }
                    if (clicked == INFO) { owner.closeHandledScreen(); helpers.status(owner, agent.name()); return; }
                    if (clicked == RECALL) { owner.closeHandledScreen(); helpers.recall(owner, agent.name()); return; }
                    if (clicked == ASK) {
                        owner.closeHandledScreen();
                        AgentChat.ask(owner.getCommandSource(), agent.name(), ASK_QUESTION);
                        return;
                    }
                    if (clicked == DISMISS) { reopen(Page.DISMISS, helperId); return; }
                }
            }
            // Never delegate to the container: every stack is an icon, including on
            // drag, hotbar swap, throw, double-click, and player-inventory slots.
            syncState();
        }
    }
}
