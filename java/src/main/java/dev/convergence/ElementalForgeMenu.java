package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import org.geysermc.cumulus.form.Form;
import org.geysermc.cumulus.form.SimpleForm;

/** The same Forge actions drive Java screens and single-use native Bedrock touch forms. */
final class ElementalForgeMenu {
    enum Page { HOME, CRAFTING }
    enum Kit { ELEMENTAL, INFINITY, RELICS, BLOCKS }
    record Choice(Item icon, String label, Runnable action) {}
    record View(Page page, String title, String content, List<Choice> choices) {}
    record Ticket(UUID nonce, ServerPlayer player, ServerGamePacketListenerImpl connection,
                  ServerLevel world, GameModes.Mode mode, int expires) {
        boolean valid() {
            return player.connection == connection && player.level() == world && GameModes.current(player) == mode
                && emptyScreen(player) && world.getServer().getTickCount() < expires;
        }
    }
    record Delivery(Ticket ticket, Form form, int due) {}

    static final List<String> RELICS = List.of("firebrand", "tidecaller", "terra_gauntlet", "windstep");
    static final List<String> BLOCKS = List.of("ember_crystal", "tide_glass", "earth_rune", "air_lattice", "infinity_prism");
    static final Map<UUID, Ticket> ACTIVE = new ConcurrentHashMap<>();
    static final Map<UUID, Delivery> DELIVERIES = new ConcurrentHashMap<>();
    static final int FIRST_CHOICE = 10;

    private ElementalForgeMenu() {}

    private static boolean emptyScreen(ServerPlayer p) {
        return ServerMenu.allowed(p) && p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty();
    }

    static List<ItemStack> stacks(Kit kit) {
        if (kit == Kit.ELEMENTAL) return ElementalArmor.kit(ElementalArmor.Suit.ELEMENTAL);
        if (kit == Kit.INFINITY) return ElementalArmor.kit(ElementalArmor.Suit.INFINITY);
        return (kit == Kit.RELICS ? RELICS : BLOCKS).stream().map(path -> {
            var item = Convergence.ITEMS.get("convergence:" + path);
            if (item == null) throw new IllegalStateException("Forge item is not registered: " + path);
            return new ItemStack(item, kit == Kit.BLOCKS ? 64 : 1);
        }).toList();
    }

    /** Delivery is all-or-nothing and persists the same inventory that received the real items. */
    static int claim(ServerPlayer p, Kit kit) {
        if (kit == null || !emptyScreen(p) || !ServerMenu.gearAllowed(p)) return 0;
        var items = stacks(kit);
        var empty = new ArrayList<Integer>();
        for (int slot = 0; slot < 36; slot++) if (p.getInventory().getItem(slot).isEmpty()) empty.add(slot);
        if (empty.size() < items.size()) return 0;
        for (int i = 0; i < items.size(); i++) p.getInventory().setItem(empty.get(i), items.get(i).copy());
        p.inventoryMenu.sendAllDataToRemote();
        PlayerPersistence.save(p);
        return items.size();
    }

    static void attune(ServerPlayer p, ElementalArmor.Element element) {
        int result = emptyScreen(p) ? ElementalArmor.select(p, element) : 0;
        open(p, Page.HOME, result == 1 ? "Attuned to " + element.displayName() + ". Wear all four Elemental pieces to activate it."
            : "Attunement is available in Survival, Hardcore and Creative after closing other screens.");
    }

    private static void deliver(ServerPlayer p, Kit kit) {
        int result = claim(p, kit);
        open(p, Page.HOME, result > 0 ? "Added " + result + " real items to your inventory. Move armor into its equipment slots."
            : "Creative, Admin or OP2 required. Clear " + stacks(kit).size() + " inventory slots; your existing items are preserved.");
    }

    static View view(ServerPlayer p, Page page, String notice) {
        var choices = new ArrayList<Choice>();
        String content;
        if (page == Page.HOME) {
            var selected = ElementalArmor.selected(p);
            var worn = ElementalArmor.fullSuit(p);
            content = "Four elements, one suit. Selected: " + selected.displayName() + ".\n"
                + "Equipped full set: " + (worn == null ? "none" : worn.displayName()) + ".\n"
                + "Wear the matching helmet, chestplate, leggings and boots. Mixed sets do not activate set powers. "
                + "Your selected element is saved and can be changed without commands.\n"
                + "Fire: " + ElementalArmor.Element.FIRE.powerDescription() + ".\n"
                + "Water: " + ElementalArmor.Element.WATER.powerDescription() + ".\n"
                + "Earth: " + ElementalArmor.Element.EARTH.powerDescription() + ".\n"
                + "Air: " + ElementalArmor.Element.AIR.powerDescription() + ".\n"
                + "Infinity: " + ElementalArmor.Suit.INFINITY.powerDescription() + ".\n"
                + "Most old attunement effects fade within four seconds; Night Vision lasts up to fifteen seconds. "
                + "Earth's extra knockback resistance changes immediately.\n"
                + "Survival: craft your gear at a crafting table. Creative inventory: search Elemental, Infinity or a relic's name. "
                + "Free kit buttons below require Creative, Admin or OP2.";
            for (var element : ElementalArmor.Element.values()) {
                Item icon = switch (element) {
                    case FIRE -> Items.BLAZE_POWDER;
                    case WATER -> Items.HEART_OF_THE_SEA;
                    case EARTH -> Items.EMERALD;
                    case AIR -> Items.FEATHER;
                };
                choices.add(new Choice(icon, (selected == element ? "Selected • " : "Attune • ") + element.displayName(), () -> attune(p, element)));
            }
            choices.add(new Choice(Items.DIAMOND_CHESTPLATE, "Get Elemental set • four empty slots", () -> deliver(p, Kit.ELEMENTAL)));
            choices.add(new Choice(Items.NETHERITE_CHESTPLATE, "Get Infinity set • four empty slots", () -> deliver(p, Kit.INFINITY)));
            choices.add(new Choice(Items.BLAZE_ROD, "Get four elemental relics • four empty slots", () -> deliver(p, Kit.RELICS)));
            choices.add(new Choice(Items.AMETHYST_BLOCK, "Get five block styles × 64 • five empty slots", () -> deliver(p, Kit.BLOCKS)));
            choices.add(new Choice(Items.CRAFTING_TABLE, "Crafting guide • Survival progression", () -> open(p, Page.CRAFTING, "")));
        } else {
            content = "Create these items at an ordinary crafting table; this screen does not consume materials.\n"
                + "Elemental Armor: diamond pieces surrounding an amethyst shard core. Infinity Armor: netherite ingots surrounding a Nether Star core.\n"
                + "In the patterns below, D is diamond for Elemental or netherite ingot for Infinity. C is the matching core. A dot means an empty slot.\n"
                + "Helmet: DDD / DCD\nChestplate: D.D / DCD / DDD\nLeggings: DDD / DCD / D.D\nBoots: D.D / DCD\n"
                + "Each recipe makes one matching armor piece. Elemental uses ordinary diamond durability; Infinity is unbreakable. "
                + "These are solid chestplates; use your separate Convergence wings when you want elytra flight.\n"
                + "The four relics are Firebrand, Tidecaller, Terra Gauntlet and Windstep. Five original full-cube building styles: "
                + "Ember Crystal, Tide Glass, Earth Rune, Air Lattice and Infinity Prism. Their crafting-table recipes are included with the pack.\n"
                + "Attunement belongs to your character, so trading a piece never changes someone else's selected element.";
            choices.add(new Choice(Items.AMETHYST_SHARD, "Back to Elemental Forge", () -> open(p, Page.HOME, "")));
        }
        choices.add(new Choice(Items.ARROW, "Back to Infinity Menu", () -> ServerMenu.open(p)));
        if (!notice.isBlank()) content = notice + "\n\n" + content;
        return new View(page, page == Page.HOME ? "Elemental Forge" : "Elemental Forge • Crafting", content, List.copyOf(choices));
    }

    static int open(ServerPlayer p) { return open(p, Page.HOME, ""); }

    static int open(ServerPlayer p, Page page, String notice) {
        if (!emptyScreen(p)) return 0;
        ACTIVE.remove(p.getUUID());
        DELIVERIES.remove(p.getUUID());
        if (CrossplaySupport.bedrock(p)) return form(p, page, notice, f -> {
            var ticket = ACTIVE.get(p.getUUID());
            if (ticket == null) return false;
            DELIVERIES.put(p.getUUID(), new Delivery(ticket, f, p.level().getServer().getTickCount() + 3));
            return true;
        });
        var view = view(p, page, notice);
        var chest = new SimpleContainer(54);
        var cards = CommonsMenu.wrap(view.content());
        for (int i = 0; i < cards.size() && i < 8; i++)
            CreativeStudio.icon(chest, i, Items.BOOK, view.title() + " • guide " + (i + 1), cards.get(i).toArray(String[]::new));
        for (int i = 0; i < view.choices().size(); i++) {
            var choice = view.choices().get(i);
            CreativeStudio.icon(chest, FIRST_CHOICE + i, choice.icon(), choice.label());
        }
        p.openMenu(new SimpleMenuProvider((sync, inventory, who) -> new Handler(sync, inventory, chest, p, view), Component.literal(view.title())));
        return 1;
    }

    static final class Handler extends ChestMenu {
        final ServerPlayer owner;
        final ServerGamePacketListenerImpl connection;
        final ServerLevel world;
        final GameModes.Mode mode;
        final View view;
        private boolean consumed;

        Handler(int sync, Inventory inventory, SimpleContainer chest, ServerPlayer owner, View view) {
            super(MenuType.GENERIC_9x6, sync, inventory, chest, 6);
            this.owner = owner;
            connection = owner.connection;
            world = owner.level();
            mode = GameModes.current(owner);
            this.view = view;
        }

        @Override public boolean stillValid(Player p) {
            return !consumed && p == owner && owner.connection == connection && owner.containerMenu == this
                && ServerMenu.allowed(owner) && owner.level() == world && GameModes.current(owner) == mode;
        }
        @Override public ItemStack quickMoveStack(Player p, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) {}
        @Override public void clicked(int slot, int button, ContainerInput action, Player p) {
            if (p != owner || owner.connection != connection || owner.containerMenu != this) return;
            if (!stillValid(p)) { owner.closeContainer(); return; }
            int index = slot - FIRST_CHOICE;
            if ((action != ContainerInput.PICKUP && action != ContainerInput.QUICK_MOVE) || button < 0 || button > 1
                || !getCarried().isEmpty() || index < 0 || index >= view.choices().size()) { sendAllDataToRemote(); return; }
            consumed = true;
            owner.closeContainer();
            view.choices().get(index).action().run();
        }
    }

    static int form(ServerPlayer p, Page page, String notice, Predicate<Form> sender) {
        if (!emptyScreen(p)) return 0;
        var view = view(p, page, notice);
        var ticket = new Ticket(UUID.randomUUID(), p, p.connection, p.level(), GameModes.current(p), p.level().getServer().getTickCount() + 1800);
        ACTIVE.put(p.getUUID(), ticket);
        var builder = SimpleForm.builder().title(view.title()).content(view.content());
        for (var choice : view.choices()) builder.button(choice.label());
        builder.validResultHandler(result -> response(ticket, () -> {
            int index = result.clickedButtonId();
            if (index >= 0 && index < view.choices().size()) view.choices().get(index).action().run();
        })).closedOrInvalidResultHandler(() -> response(ticket, () -> {}));
        if (!sender.test(builder.build())) {
            ACTIVE.remove(p.getUUID(), ticket);
            Convergence.say(p, "Forge form could not be sent. Reopen Elemental Forge.");
            return 0;
        }
        return 1;
    }

    static void response(Ticket ticket, Runnable action) {
        var server = ticket.world().getServer();
        if (!server.isSameThread()) { server.execute(() -> response(ticket, action)); return; }
        if (!ACTIVE.remove(ticket.player().getUUID(), ticket)) return;
        DELIVERIES.computeIfPresent(ticket.player().getUUID(), (id, delivery) -> delivery.ticket() == ticket ? null : delivery);
        if (ticket.valid()) action.run();
    }

    static void register() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var ticket = ACTIVE.get(handler.player.getUUID());
            if (ticket != null && ticket.connection() == handler) {
                ACTIVE.remove(handler.player.getUUID(), ticket);
                DELIVERIES.computeIfPresent(handler.player.getUUID(), (id, delivery) -> delivery.ticket() == ticket ? null : delivery);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE.entrySet().removeIf(entry -> entry.getValue().world().getServer() == server);
            DELIVERIES.entrySet().removeIf(entry -> entry.getValue().ticket().world().getServer() == server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var entry : DELIVERIES.entrySet()) {
                var delivery = entry.getValue();
                if (delivery.ticket().world().getServer() != server || server.getTickCount() < delivery.due()) continue;
                if (!DELIVERIES.remove(entry.getKey(), delivery) || ACTIVE.get(entry.getKey()) != delivery.ticket()) continue;
                var api = org.geysermc.floodgate.api.FloodgateApi.getInstance();
                if (!delivery.ticket().valid() || api == null || !api.sendForm(entry.getKey(), delivery.form())) {
                    ACTIVE.remove(entry.getKey(), delivery.ticket());
                    Convergence.say(delivery.ticket().player(), "Forge form could not be sent. Reopen Elemental Forge.");
                }
            }
            ACTIVE.entrySet().removeIf(entry -> entry.getValue().world().getServer() == server && !entry.getValue().valid());
            DELIVERIES.entrySet().removeIf(entry -> entry.getValue().ticket().world().getServer() == server
                && ACTIVE.get(entry.getKey()) != entry.getValue().ticket());
        });
    }
}
