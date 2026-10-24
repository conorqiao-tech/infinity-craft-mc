package dev.convergence;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** An explicit quantity/profile decision creates one immediate native helper batch. */
final class HelperBatchMenu {
    static final int AMOUNT = 4, PRESET_ONE = 10, PRESET_FIVE = 11, PRESET_TEN = 12,
        PRESET_TWENTY_FIVE = 13, PRESET_MAX = 14;
    static final int MINUS_TEN = 19, MINUS_ONE = 20, PLUS_ONE = 24, PLUS_TEN = 25;
    static final int PROFILE_FIRST = 28, SPAWN = 40, BACK = 49;
    private HelperBatchMenu() {}

    /** The exact session and capacity reviewed by the owner; one attempt consumes it. */
    static final class Request {
        final AdminStatsMenu.Session actor;
        final int remaining;
        final Map<String, AgentCompanions.Agent> saved;
        boolean consumed;
        Request(ServerPlayer owner) {
            actor = new AdminStatsMenu.Session(owner);
            var helpers = AgentCompanions.get(owner.level().getServer());
            remaining = helpers.remaining(owner);
            saved = snapshot(helpers, owner);
        }
        boolean valid(ServerPlayer owner) {
            if (consumed || actor.player() != owner || !actor.valid() || !AgentMenu.allowed(owner) || remaining < 1) return false;
            var helpers = AgentCompanions.get(actor.world().getServer());
            var current = snapshot(helpers, owner);
            return helpers.remaining(owner) == remaining && current.size() == saved.size()
                && saved.entrySet().stream().allMatch(e -> current.get(e.getKey()) == e.getValue());
        }
        int spawn(ServerPlayer owner, int amount, AgentCompanions.Profile profile) {
            if (!valid(owner) || amount < 1 || amount > remaining || profile == null) return 0;
            consumed = true;
            return AgentCompanions.get(actor.world().getServer()).spawnBatch(owner, amount, profile);
        }
    }

    private static Map<String, AgentCompanions.Agent> snapshot(AgentCompanions helpers, ServerPlayer owner) {
        var saved = new LinkedHashMap<String, AgentCompanions.Agent>();
        helpers.data.agents.forEach((id, agent) -> {
            if (agent.owner().equals(owner.getStringUUID())) saved.put(id, agent);
        });
        return Map.copyOf(saved);
    }

    static int open(ServerPlayer owner) {
        if (!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner)) return 0;
        if (CrossplaySupport.bedrock(owner)) return BedrockAgentMenu.openBatch(owner, HelperForms.sender(owner));
        var request = new Request(owner);
        if (!request.valid(owner)) {
            HelperForms.message(owner, "No helper slots are free. The owner and server limits include unloaded helpers.");
            return 0;
        }
        var view = new SimpleContainer(54);
        owner.openMenu(new SimpleMenuProvider((sync, inventory, who) -> new Handler(sync, inventory, view, owner, request),
            Component.literal("Create helpers • choose batch")));
        return 1;
    }

    static final class Handler extends ChestMenu {
        final ServerPlayer owner;
        final Request request;
        final SimpleContainer view;
        int amount = 1;
        AgentCompanions.Profile profile = AgentCompanions.Profile.REGULAR;
        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner, Request request) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            this.owner = owner; this.request = request; this.view = view;
            render();
        }
        @Override public boolean stillValid(Player player) {
            return player == owner && owner.containerMenu == this && request.valid(owner);
        }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) {}

        private void render() {
            AgentMenu.icon(view, AMOUNT, Items.IRON_GOLEM_SPAWN_EGG, "Batch: " + amount + " • free slots: " + request.remaining);
            AgentMenu.description(view, AMOUNT, "Creates this many new helpers together on clear loaded ground beside you. The 50 owner and server limits include saved unloaded helpers. Existing helpers keep their identities and state.");
            int[] slots = {PRESET_ONE, PRESET_FIVE, PRESET_TEN, PRESET_TWENTY_FIVE, PRESET_MAX};
            int[] values = {1, Math.min(5, request.remaining), Math.min(10, request.remaining), Math.min(25, request.remaining), request.remaining};
            for (int i = 0; i < slots.length; i++)
                AgentMenu.icon(view, slots[i], Items.IRON_INGOT, "Set quantity: " + values[i] + (slots[i] == PRESET_MAX ? " • all free slots" : ""));
            AgentMenu.icon(view, MINUS_TEN, Items.ARROW, "Quantity −10");
            AgentMenu.icon(view, MINUS_ONE, Items.ARROW, "Quantity −1");
            AgentMenu.icon(view, PLUS_ONE, Items.ARROW, "Quantity +1");
            AgentMenu.icon(view, PLUS_TEN, Items.ARROW, "Quantity +10");
            var profiles = AgentCompanions.Profile.values();
            for (int i = 0; i < profiles.length; i++) {
                var choice = profiles[i];
                AgentMenu.icon(view, PROFILE_FIRST + i, AgentMenu.profileIcon(choice),
                    (choice == profile ? "Selected: " : "Choose: ") + choice.label());
                AgentMenu.description(view, PROFILE_FIRST + i, choice.description());
            }
            AgentMenu.icon(view, SPAWN, Items.DYE.lime(), "Spawn " + amount + " helpers • " + profile.label());
            AgentMenu.description(view, SPAWN, "One activation creates the entire selected batch. Nothing appears while changing quantity or profile. New helpers follow you. Existing combat and target permissions still apply.");
            AgentMenu.icon(view, BACK, Items.ARROW, "Cancel • back to your squad");
        }

        @Override public void clicked(int slot, int button, ContainerInput action, Player player) {
            if (player != owner || owner.containerMenu != this) return;
            if (!stillValid(player)) {
                owner.closeContainer();
                HelperForms.message(owner, "Your helper capacity or player session changed. Reopen AI Helpers to choose a new batch.");
                return;
            }
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE)
                    || button < 0 || button > 1 || !getCarried().isEmpty() || slot < 0 || slot >= 54) {
                sendAllDataToRemote(); return;
            }
            if (slot == BACK) { owner.closeContainer(); AgentMenu.open(owner); return; }
            if (slot == SPAWN) {
                owner.closeContainer();
                request.spawn(owner, amount, profile);
                return;
            }
            int next = switch (slot) {
                case PRESET_ONE -> 1;
                case PRESET_FIVE -> Math.min(5, request.remaining);
                case PRESET_TEN -> Math.min(10, request.remaining);
                case PRESET_TWENTY_FIVE -> Math.min(25, request.remaining);
                case PRESET_MAX -> request.remaining;
                case MINUS_TEN -> amount - 10;
                case MINUS_ONE -> amount - 1;
                case PLUS_ONE -> amount + 1;
                case PLUS_TEN -> amount + 10;
                default -> amount;
            };
            amount = Math.max(1, Math.min(request.remaining, next));
            int index = slot - PROFILE_FIRST;
            if (index >= 0 && index < AgentCompanions.Profile.values().length) profile = AgentCompanions.Profile.values()[index];
            render(); sendAllDataToRemote();
        }
    }
}
