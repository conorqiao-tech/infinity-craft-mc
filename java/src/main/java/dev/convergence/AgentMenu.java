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
import net.minecraft.text.Text;

/** Read-only vanilla chest screens keep the helper controls usable through Geyser. */
final class AgentMenu {
    static final int FOLLOW = 28, GUARD = 30, STAY = 32, INFO = 34, DISMISS = 43, BACK = 49;
    static final int CONFIRM = 11, CANCEL = 15;
    enum Page { ROSTER, HELPER, DISMISS }

    private AgentMenu() {}

    static boolean allowed(ServerPlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && AgentCompanions.operator(player.getCommandSource());
    }

    static int helperSlot(int index) { return 10 + index; }
    static int profileSlot(int index) { return 10 + index; }

    static void icon(SimpleInventory view, int slot, Item item, String text) {
        var stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(text));
        view.setStack(slot, stack);
    }

    static void description(SimpleInventory view, int slot, String text) {
        view.getStack(slot).set(DataComponentTypes.LORE, new LoreComponent(java.util.List.of(Text.literal(text))));
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
            for (int index = 0; index < AgentCompanions.LIMIT; index++) {
                int slot = helperSlot(index);
                if (index < roster.size()) {
                    var entry = roster.get(index);
                    var agent = entry.getValue();
                    choices.put(slot, entry.getKey());
                    icon(view, slot, Items.IRON_INGOT,
                        agent.name() + " • " + agent.profile().label() + " • " + agent.mode().name().toLowerCase(java.util.Locale.ROOT));
                    description(view, slot, helpers.loaded.containsKey(UUID.fromString(entry.getKey()))
                        ? "Loaded • select to manage this helper" : "Unloaded • return nearby for movement controls");
                } else icon(view, slot, Items.LIME_DYE, "Create a helper • slot " + (index + 1));
            }
            movement(view, "Squad");
            icon(view, INFO, Items.BOOK, "Help • chat and approval commands");
        } else {
            title = selected.name() + (page == Page.DISMISS ? " • Dismiss?" : " • Helper");
            icon(view, 4, Items.IRON_INGOT, selected.name() + " • " + selected.profile().label());
            if (page == Page.DISMISS) {
                icon(view, CONFIRM, Items.RED_DYE, "Confirm dismiss " + selected.name());
                icon(view, CANCEL, Items.LIME_DYE, "Keep this helper");
            } else {
                var profiles = AgentCompanions.Profile.values();
                for (int index = 0; index < profiles.length; index++) {
                    var profile = profiles[index];
                    icon(view, profileSlot(index), profileIcon(profile),
                        (selected.profile() == profile ? "Selected: " : "Choose: ") + profile.label());
                    description(view, profileSlot(index), profile.description());
                }
                movement(view, selected.name());
                icon(view, INFO, Items.SPYGLASS, "Status • profile, location, and health");
                icon(view, DISMISS, Items.RED_DYE, "Dismiss helper • confirmation required");
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

    static final class Handler extends GenericContainerScreenHandler {
        final SimpleInventory view;
        final ServerPlayerEntity owner;
        final UUID ownerId;
        final Page page;
        final String helperId;
        final Map<Integer, String> choices;

        Handler(int sync, PlayerInventory inventory, SimpleInventory view, ServerPlayerEntity owner,
                Page page, String helperId, Map<Integer, String> choices) {
            super(ScreenHandlerType.GENERIC_9X6, sync, inventory, view, 6);
            this.view = view;
            this.owner = owner;
            this.ownerId = owner.getUuid();
            this.page = page;
            this.helperId = helperId;
            this.choices = Map.copyOf(choices);
        }

        @Override public boolean canUse(PlayerEntity player) {
            return player == owner && ownerId.equals(player.getUuid()) && allowed(owner);
        }
        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot, int selected) { }

        private void reopen(Page next, String id) {
            owner.closeHandledScreen();
            open(owner, next, id);
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
            if (page == Page.ROSTER) {
                if (clicked >= helperSlot(0) && clicked < helperSlot(AgentCompanions.LIMIT)) {
                    String id = choices.get(clicked);
                    if (id != null) {
                        if (selected(helpers, id) == null) { stale(); return; }
                        reopen(Page.HELPER, id);
                    } else {
                        String name = nextName(helpers, owner);
                        if (name != null) helpers.spawn(owner, name);
                        else owner.sendMessage(Text.literal("All helper slots are in use. Dismiss a helper to free one."), false);
                        reopen(Page.ROSTER, null);
                    }
                    return;
                }
                AgentCompanions.Mode movement = clicked == FOLLOW ? AgentCompanions.Mode.FOLLOW
                    : clicked == GUARD ? AgentCompanions.Mode.GUARD : clicked == STAY ? AgentCompanions.Mode.STAY : null;
                if (movement != null) {
                    helpers.squad(owner, movement);
                    reopen(Page.ROSTER, null);
                    return;
                }
                if (clicked == INFO) owner.sendMessage(Text.literal(
                    "Choose a helper and its profile. /agent ask <name> <message> talks to one helper. "
                    + "CLI proposals: /agent pending, /agent approve <id>, /agent cancel <id>. "
                    + "Every server-changing proposal also waits for live Codex review; this menu cannot run it."), false);
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
                        reopen(Page.HELPER, helperId);
                        return;
                    }
                    AgentCompanions.Mode movement = clicked == FOLLOW ? AgentCompanions.Mode.FOLLOW
                        : clicked == GUARD ? AgentCompanions.Mode.GUARD : clicked == STAY ? AgentCompanions.Mode.STAY : null;
                    if (movement != null) { helpers.mode(owner, agent.name(), movement); reopen(Page.HELPER, helperId); return; }
                    if (clicked == INFO) { helpers.status(owner, agent.name()); syncState(); return; }
                    if (clicked == DISMISS) { reopen(Page.DISMISS, helperId); return; }
                }
            }
            // Never delegate to the container: every stack is an icon, including on
            // drag, hotbar swap, throw, double-click, and player-inventory slots.
            syncState();
        }
    }
}
