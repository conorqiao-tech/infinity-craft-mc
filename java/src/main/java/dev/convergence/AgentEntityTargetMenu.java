package dev.convergence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.geysermc.cumulus.form.Form;

/** Explicit selection and a second exact-entity review; ordinary targeting stays hostile-only. */
final class AgentEntityTargetMenu {
    static final int PAGE_SIZE = 45, PREVIOUS = 45, BACK = 49, NEXT = 53, CONFIRM = 11, CANCEL = 15;
    private AgentEntityTargetMenu() {}

    record Selection(AdminStatsMenu.Session actor, LivingEntity target, UUID id, ServerLevel world,
                     String name, String type, String location, AdminStatsMenu.Session player) {
        Selection(ServerPlayer owner, LivingEntity target) {
            this(new AdminStatsMenu.Session(owner), target, target.getUUID(), owner.level(), plain(target.getName().getString()),
                BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString(),
                target.getBlockX() + ", " + target.getBlockY() + ", " + target.getBlockZ(),
                target instanceof ServerPlayer p ? new AdminStatsMenu.Session(p) : null);
        }
        boolean valid(ServerPlayer owner) {
            return actor.player() == owner && actor.valid() && AgentMenu.allowed(owner) && target.isAlive() && !target.isRemoved()
                && target.level() == world && owner.level() == world && world.getEntity(id) == target
                && id.equals(target.getUUID()) && name.equals(plain(target.getName().getString()))
                && type.equals(BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString())
                && !AgentCompanions.registeredHelper(target) && target != owner
                && owner.distanceToSqr(target) <= 48 * 48 && (player == null || player.valid());
        }
        String description() {
            return name + "\nType: " + type + "\nUUID: " + id + "\nWorld: " + world.dimension().identifier()
                + "\nPosition when selected: " + location;
        }
    }

    static String plain(String value) {
        String text = value.replaceAll("[\\p{Cntrl}§]", " ").strip();
        return text.length() <= 64 ? text : text.substring(0, 63) + "…";
    }

    static List<Selection> nearby(ServerPlayer owner) {
        return owner.level().getEntitiesOfClass(LivingEntity.class, owner.getBoundingBox().inflate(48), target ->
            target != owner && target.isAlive() && !target.isRemoved() && !target.isSpectator()
                && !AgentCompanions.registeredHelper(target) && owner.distanceToSqr(target) <= 48 * 48).stream()
            .sorted(Comparator.comparingDouble((LivingEntity target) -> owner.distanceToSqr(target)).thenComparing(LivingEntity::getUUID))
            .limit(512).map(target -> new Selection(owner, target)).toList();
    }

    static String reason(ServerPlayer owner, Selection selected) {
        if (!selected.valid(owner)) return "The exact target or owner session changed. Select it again.";
        return selected.target() instanceof ServerPlayer player
            ? AgentCompanions.get(owner.level().getServer()).targetEligibility(owner, player)
            : AgentEntityTargets.problem(owner, selected.target());
    }

    static int open(ServerPlayer owner) {
        return CrossplaySupport.bedrock(owner) ? openBedrock(owner, HelperForms.sender(owner)) : show(owner, 0, null, "");
    }
    static int openBedrock(ServerPlayer owner, Predicate<Form> sender) { return bedrock(owner, 0, null, "", sender); }

    private static String guidance() {
        return "Choose a nearby living target, then confirm that exact entity. Animals and pets are never chosen automatically. "
            + "Explicit mob targets last at most five minutes while loaded, nearby and eligible. Stay, Ceasefire and invalid sessions stop combat. "
            + "Player selections create an order requiring your approval and separate live Codex approval. Native reach, line of sight, damage immunity and movement limits still apply.";
    }

    private static int show(ServerPlayer owner, int index, Selection selected, String notice) {
        if (!AgentMenu.allowed(owner) || !HelperForms.emptyScreen(owner)) return 0;
        var view = new SimpleContainer(54);
        var choices = new LinkedHashMap<Integer, Selection>();
        List<Selection> nearby = selected == null ? nearby(owner) : List.of();
        int pages = Math.max(1, (nearby.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = selected == null ? Math.max(0, Math.min(index, pages - 1)) : Math.max(0, index);
        if (selected == null) {
            if (nearby.isEmpty()) {
                AgentMenu.icon(view, 4, Items.BARRIER, "No nearby living targets");
                AgentMenu.description(view, 4, guidance());
            }
            for (int i = page * PAGE_SIZE, slot = 0; i < nearby.size() && slot < PAGE_SIZE; i++, slot++) {
                var target = nearby.get(i); choices.put(slot, target);
                AgentMenu.icon(view, slot, target.player() == null ? Items.IRON_SWORD : Items.PLAYER_HEAD, "Choose: " + target.name());
                String reason = reason(owner, target);
                AgentMenu.description(view, slot, target.description() + " • " + (reason == null ? "Select to review; no attack yet." : reason));
            }
            if (page > 0) AgentMenu.icon(view, PREVIOUS, Items.ARROW, "Previous targets");
            if (page + 1 < pages) AgentMenu.icon(view, NEXT, Items.ARROW, "Next targets");
        } else {
            String reason = reason(owner, selected);
            AgentMenu.icon(view, 4, selected.player() == null ? Items.IRON_SWORD : Items.PLAYER_HEAD, "Review exact target: " + selected.name());
            AgentMenu.description(view, 4, selected.description() + " • " + guidance() + (notice.isEmpty() ? "" : " • " + notice));
            AgentMenu.icon(view, CONFIRM, reason == null ? Items.DYE.lime() : Items.BARRIER,
                selected.player() == null ? "Confirm this mob target" : "Propose this player • two approvals");
            AgentMenu.description(view, CONFIRM, reason == null ? selected.player() == null
                ? "Your active Primitive/Ultimate Finals helpers will target only this exact chosen entity. Ceasefire stops them."
                : "Creates an inert player proposal. Owner and live Codex approvals are still required." : reason);
            AgentMenu.icon(view, CANCEL, Items.DYE.red(), "Cancel • do not target");
        }
        AgentMenu.icon(view, BACK, Items.ARROW, selected == null ? "Back to Orders" : "Back to targets");
        owner.openMenu(new SimpleMenuProvider((sync, inventory, who) -> new Handler(sync, inventory, view, owner, page, selected, choices),
            Component.literal(selected == null ? "Squad target • " + (page + 1) + "/" + pages : "Review squad target")));
        return 1;
    }

    static final class Handler extends ChestMenu {
        final ServerPlayer owner;
        final AdminStatsMenu.Session actor;
        final int page;
        final Selection selected;
        final Map<Integer, Selection> choices;
        Handler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner, int page, Selection selected, Map<Integer, Selection> choices) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            this.owner = owner; this.actor = new AdminStatsMenu.Session(owner); this.page = page; this.selected = selected; this.choices = Map.copyOf(choices);
        }
        @Override public boolean stillValid(Player player) { return player == owner && owner.containerMenu == this && actor.valid() && AgentMenu.allowed(owner); }
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) {}
        void reopen(int index, Selection target, String notice) { owner.closeContainer(); show(owner, index, target, notice); }
        @Override public void clicked(int slot, int button, ContainerInput action, Player player) {
            if (player != owner || owner.containerMenu != this) return;
            if (!stillValid(player)) { owner.closeContainer(); return; }
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE) || button < 0 || button > 1
                    || !getCarried().isEmpty() || slot < 0 || slot >= 54) { sendAllDataToRemote(); return; }
            if (slot == BACK || selected != null && slot == CANCEL) {
                if (selected == null) { owner.closeContainer(); AgentOrdersMenu.open(owner); }
                else reopen(page, null, "");
            } else if (selected == null) {
                if ((slot == PREVIOUS || slot == NEXT) && !getSlot(slot).getItem().isEmpty()) reopen(page + (slot == NEXT ? 1 : -1), null, "");
                else if (choices.containsKey(slot)) {
                    var target = choices.get(slot);
                    reopen(page, target.valid(owner) ? target : null, "");
                }
            } else if (slot == CONFIRM) {
                String reason = reason(owner, selected);
                if (reason != null) { reopen(page, selected.valid(owner) ? selected : null, reason); return; }
                owner.closeContainer();
                apply(owner, selected);
                if (selected.player() != null) AgentOrdersMenu.openPending(owner);
            }
        }
    }

    private static int bedrock(ServerPlayer owner, int index, Selection selected, String notice, Predicate<Form> sender) {
        var choices = new ArrayList<HelperForms.Choice>();
        if (selected == null) {
            var nearby = nearby(owner); int pages = Math.max(1, (nearby.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = Math.max(0, Math.min(index, pages - 1));
            for (int i = page * PAGE_SIZE; i < nearby.size() && i < (page + 1) * PAGE_SIZE; i++) {
                var target = nearby.get(i);
                choices.add(new HelperForms.Choice("Choose: " + target.name() + " • " + target.type(),
                    () -> bedrock(owner, page, target, "", sender)));
            }
            if (page > 0) choices.add(new HelperForms.Choice("Previous targets", () -> bedrock(owner, page - 1, null, "", sender)));
            if (page + 1 < pages) choices.add(new HelperForms.Choice("Next targets", () -> bedrock(owner, page + 1, null, "", sender)));
            choices.add(new HelperForms.Choice("Back to Orders", () -> BedrockAgentOrdersMenu.open(owner, sender)));
            return HelperForms.show(owner, "Squad target • " + (page + 1) + "/" + pages,
                (notice.isEmpty() ? "" : notice + "\n\n") + guidance() + (nearby.isEmpty() ? "\n\nNo nearby living targets." : ""), choices, () -> true, sender);
        }
        String reason = reason(owner, selected);
        choices.add(new HelperForms.Choice(selected.player() == null ? "Confirm this mob target" : "Propose this player • two approvals", () -> {
            String problem = reason(owner, selected);
            if (problem != null) { bedrock(owner, index, null, problem, sender); return; }
            apply(owner, selected);
            if (selected.player() != null) BedrockAgentOrdersMenu.openPending(owner, sender);
        }));
        choices.add(new HelperForms.Choice("Cancel • do not target", () -> bedrock(owner, index, null, "Cancelled. No new target was set.", sender)));
        return HelperForms.show(owner, "Review exact squad target", selected.description() + "\n\n" + guidance()
            + (reason == null ? "" : "\n\nUnavailable: " + reason), choices, () -> selected.valid(owner), sender);
    }

    private static void apply(ServerPlayer owner, Selection selected) {
        try {
            if (reason(owner, selected) != null) { owner.sendSystemMessage(Component.literal("The exact target changed. Reopen squad targets.")); return; }
            if (selected.target() instanceof ServerPlayer player) {
                AgentEntityTargets.clear(owner);
                AgentActions.get(owner.level().getServer()).target(owner, player);
            } else if (AgentEntityTargets.select(owner, selected.target())) {
                var text = Component.literal("Squad target: " + selected.name() + " • " + selected.id()
                    + ". Active Primitive/Ultimate Finals helpers will pursue this exact target. Ceasefire stops the order.");
                owner.sendSystemMessage(text); owner.sendOverlayMessage(text);
            } else owner.sendSystemMessage(Component.literal("The target is no longer available. No new mob target was set."));
        } catch (IllegalStateException failure) {
            owner.sendSystemMessage(Component.literal("The prior target order could not be safely retired. No new target was set. Review pending orders."));
        }
    }
}
