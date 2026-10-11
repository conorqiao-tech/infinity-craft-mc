package dev.convergence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/** Read-only vanilla chest screens keep the helper controls usable through Geyser. */
final class AgentMenu {
    static final int FOLLOW = 28, GUARD = 30, STAY = 32, INFO = 34, CEASEFIRE = 37, ASK = 39, RECALL = 41, DISMISS = 43, BACK = 49;
    static final int CONFIRM = 11, CANCEL = 15;
    static final int ORDERS = 19;
    static final String ASK_QUESTION = "Explain my helper's current state and suggest what I should do next.";
    enum Page { ROSTER, HELPER, DISMISS }

    private AgentMenu() {}

    static boolean allowed(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.hasDisconnected() && !player.isSpectator()
            && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
            && AgentCompanions.operator(player.createCommandSourceStack());
    }

    static int helperSlot(int index) { return 10 + index; }
    static int profileSlot(int index) { return 10 + index; }

    static void icon(SimpleContainer view, int slot, Item item, String text) {
        var stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(text));
        view.setItem(slot, stack);
    }

    static void description(SimpleContainer view, int slot, String text) {
        var lines = new java.util.ArrayList<Component>();
        var line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!line.isEmpty() && line.length() + word.length() + 1 > 43) {
                lines.add(Component.literal(line.toString())); line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(Component.literal(line.toString()));
        view.getItem(slot).set(DataComponents.LORE, new ItemLore(lines));
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

    static String nextName(AgentCompanions helpers, ServerPlayer owner) {
        for (int index = 1; index <= AgentCompanions.LIMIT; index++) {
            String name = "helper-" + index;
            if (helpers.owned(owner, name) == null) return name;
        }
        return null;
    }

    static int open(ServerPlayer player) { return open(player, Page.ROSTER, null); }

    private static int open(ServerPlayer player, Page page, String helperId) {
        if (!allowed(player)) {
            player.sendSystemMessage(Component.literal("Helper controls require a living, non-spectator OP4 owner."));
            return 0;
        }
        if (player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty()) {
            player.sendSystemMessage(Component.literal("Close your current screen and empty the cursor before opening helpers."));
            return 0;
        }
        var helpers = AgentCompanions.get(player.level().getServer());
        var selected = helperId == null ? null : helpers.data.agents.get(helperId);
        if (page != Page.ROSTER && (selected == null || !selected.owner().equals(player.getStringUUID()))) {
            player.sendSystemMessage(Component.literal("That helper is no longer in your roster."));
            page = Page.ROSTER;
            helperId = null;
        }
        var view = new SimpleContainer(54);
        var choices = new LinkedHashMap<Integer, String>();
        String title;
        if (page == Page.ROSTER) {
            title = "Your Helper Squad";
            icon(view, 4, Items.IRON_GOLEM_SPAWN_EGG,
                "Helpers: " + helpers.count(player) + "/" + AgentCompanions.LIMIT);
            var roster = helpers.data.agents.entrySet().stream()
                .filter(entry -> entry.getValue().owner().equals(player.getStringUUID()))
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
                    icon(view, slot, Items.DYE.lime(), roster.isEmpty() && index == 0
                        ? "Create your first helper" : "Create a helper • slot " + (index + 1));
                    description(view, slot, "Tap to summon a named AI player avatar beside you. Stand on clear solid ground.");
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
                icon(view, CONFIRM, Items.DYE.red(), "Confirm dismiss " + selected.name());
                icon(view, CANCEL, Items.DYE.lime(), "Keep this helper");
            } else {
                profileChoices(view, selected);
                movement(view, selected.name());
                icon(view, INFO, Items.SPYGLASS, "Status • profile, location, and health");
                askButton(view, player);
                recallButton(view);
                icon(view, DISMISS, Items.DYE.red(), "Dismiss helper • confirmation required");
                hive(view, helpers, player);
            }
            icon(view, BACK, Items.ARROW, "Back to your squad");
        }
        Page openedPage = page;
        String openedId = helperId;
        player.openMenu(new SimpleMenuProvider((sync, inventory, who) ->
            new Handler(sync, inventory, view, player, openedPage, openedId, choices), Component.literal(title)));
        return 1;
    }

    private static void movement(SimpleContainer view, String label) {
        icon(view, FOLLOW, Items.LEAD, label + " • follow");
        icon(view, GUARD, Items.SHIELD, label + " • guard current area");
        icon(view, STAY, Items.REDSTONE_TORCH, label + " • stay / pause");
    }

    private static void askButton(SimpleContainer view, ServerPlayer owner) {
        boolean codex = ServerAssistant.codexEnabled(owner.level().getServer());
        icon(view, ASK, Items.WRITABLE_BOOK, codex ? "Ask Codex" : "Ask helper");
        description(view, ASK, (codex ? "Sends limited Minecraft facts to Codex using the host's signed-in account. "
            : "Uses the configured AI or local helper. Configured AI receives limited Minecraft facts. ")
            + "Explains this helper's state and suggests a next step. Answers cannot execute commands.");
    }

    private static void recallButton(SimpleContainer view) {
        icon(view, RECALL, Items.ENDER_PEARL, "Bring here • then follow");
        description(view, RECALL, "Moves this existing loaded helper beside you, even from another mode. Keeps health, stats and profile. Clears your squad's player-target orders and pending target approvals. Unloaded helpers must first be loaded by visiting their area.");
    }

    private static void rosterIcon(SimpleContainer view, int slot, AgentCompanions helpers, String id, AgentCompanions.Agent agent) {
        icon(view, slot, Items.IRON_INGOT,
            agent.name() + " • " + agent.profile().label() + " • " + agent.mode().name().toLowerCase(java.util.Locale.ROOT));
        var golem = helpers.loaded.get(UUID.fromString(id));
        description(view, slot, AgentCompanions.location(golem, agent)
            + (golem != null && golem.isAlive() && !golem.isRemoved()
                ? " • select, then Bring here to move it beside you" : " • return nearby to load this helper"));
    }

    private static void profileChoices(SimpleContainer view, AgentCompanions.Agent selected) {
        var profiles = AgentCompanions.Profile.values();
        for (int index = 0; index < profiles.length; index++) {
            var profile = profiles[index];
            icon(view, profileSlot(index), profileIcon(profile),
                (selected.profile() == profile ? "Selected: " : "Choose: ") + profile.label());
            description(view, profileSlot(index), profile.description());
        }
    }

    private static void hive(SimpleContainer view, AgentCompanions helpers, ServerPlayer owner) {
        icon(view,ORDERS,Items.WRITABLE_BOOK,"Orders • targets and approvals");
        description(view,ORDERS,"Choose a player, propose an action, review, approve or cancel without typing a command. Live Codex review is still required.");
        icon(view, 22, Items.COMPASS, helpers.playerTargetStatus(owner));
        description(view, 22, "Use Orders to choose a target. Owner and live Codex approval are required.");
        icon(view, CEASEFIRE, Items.BANNER.white(), "Ceasefire • clear your player target and queued orders");
    }

    static final class Handler extends ChestMenu {
        final SimpleContainer view;
        final ServerPlayer owner;
        final ServerGamePacketListenerImpl ownerConnection;
        final UUID ownerId;
        final Page page;
        final String helperId;
        final Map<Integer, String> choices;

        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner,
                Page page, String helperId, Map<Integer, String> choices) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            this.view = view;
            this.owner = owner;
            this.ownerConnection = owner.connection;
            this.ownerId = owner.getUUID();
            this.page = page;
            this.helperId = helperId;
            this.choices = Map.copyOf(choices);
        }

        @Override public boolean stillValid(Player player) {
            return player == owner && ownerId.equals(player.getUUID()) && owner.connection == ownerConnection && allowed(owner);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) { }

        private void reopen(Page next, String id) {
            owner.closeContainer();
            open(owner, next, id);
        }

        /** Stable buttons update slots without sending a close/open pair to Geyser.
         * Identity or page changes still get a fresh sync ID, rejecting old-window packets. */
        private void refresh() {
            if (owner.containerMenu != this) return;
            if (!stillValid(owner)) { owner.closeContainer(); return; }
            var helpers = AgentCompanions.get(owner.level().getServer());
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
            sendAllDataToRemote();
        }

        private AgentCompanions.Agent selected(AgentCompanions helpers, String id) {
            var agent = helpers.data.agents.get(id);
            return agent != null && agent.owner().equals(ownerId.toString()) ? agent : null;
        }

        private void stale() {
            owner.sendSystemMessage(Component.literal("Your helper roster changed. Choose the helper again."));
            reopen(Page.ROSTER, null);
        }

        @Override public void clicked(int clicked, int button, ContainerInput action, Player player) {
            // A packet for an old window cannot affect a newer screen or another owner.
            if (player != owner || owner.containerMenu != this) return;
            if (!stillValid(player)) { owner.closeContainer(); return; }
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)
                || button < 0 || button > 1 || !getCarried().isEmpty()) { sendAllDataToRemote(); return; }
            var helpers = AgentCompanions.get(owner.level().getServer());
            if(clicked==ORDERS && page!=Page.DISMISS) { owner.closeContainer();AgentOrdersMenu.open(owner);return; }
            if (clicked == CEASEFIRE && page != Page.DISMISS) {
                AgentActions.get(helpers.server).ceasefire(owner);
                refresh();
                return;
            }
            if (page == Page.ROSTER) {
                if (clicked == BACK) { owner.closeContainer(); ServerMenu.open(owner); return; }
                if (clicked >= helperSlot(0) && clicked < helperSlot(AgentCompanions.LIMIT)) {
                    String id = choices.get(clicked);
                    if (id != null) {
                        if (selected(helpers, id) == null) { stale(); return; }
                        reopen(Page.HELPER, id);
                    } else {
                        String name = nextName(helpers, owner);
                        // Reveal the nearby helper on success, and keep a failed spawn's
                        // explanation visible instead of hiding it behind another chest.
                        owner.closeContainer();
                        if (name != null) {
                            helpers.spawn(owner, name);
                            if (helpers.owned(owner, name) != null) {
                                var message = Component.literal(name + " appeared beside you. Look for the named iron golem.");
                                owner.sendSystemMessage(message);
                                owner.sendOverlayMessage(message);
                            }
                        } else owner.sendSystemMessage(Component.literal("All helper slots are in use. Dismiss a helper to free one."));
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
                    owner.closeContainer();
                    owner.sendSystemMessage(Component.literal(
                        "Choose a helper and its profile, then tap Ask Codex or Ask helper for guidance. "
                        + "/agent ask <name> <message> lets you write your own question. "
                        + "Player targeting: /agent target <player>; /agent ceasefire stops it. "
                        + "CLI proposals: /agent pending, /agent approve <id>, /agent cancel <id>. "
                        + "Every server-changing proposal also waits for live Codex review; this menu cannot run it."));
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
                    if (clicked == INFO) { owner.closeContainer(); helpers.status(owner, agent.name()); return; }
                    if (clicked == RECALL) { owner.closeContainer(); helpers.recall(owner, agent.name()); return; }
                    if (clicked == ASK) {
                        owner.closeContainer();
                        AgentChat.ask(owner.createCommandSourceStack(), agent.name(), ASK_QUESTION);
                        return;
                    }
                    if (clicked == DISMISS) { reopen(Page.DISMISS, helperId); return; }
                }
            }
            // Never delegate to the container: every stack is an icon, including on
            // drag, hotbar swap, throw, double-click, and player-inventory slots.
            sendAllDataToRemote();
        }
    }
}
