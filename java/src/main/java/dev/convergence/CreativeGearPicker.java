package dev.convergence;

import java.util.ArrayList;
import net.minecraft.util.Prediction;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
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

/** Vanilla chest icons and a vanilla compass keep the picker usable through Geyser. */
final class CreativeGearPicker {
    private static final String PICKER_NAME = "Infinity Gear Picker";
    private static final Map<UUID,Integer> OPEN_AFTER = new HashMap<>();
    private static final Set<UUID> SELECTED = new HashSet<>();

    private CreativeGearPicker() {}

    static boolean allowed(ServerPlayer player) {
        return player.isAlive() && player.isCreative() && !player.isSpectator()
            && GameModes.current(player) == GameModes.Mode.CREATIVE
            && !GameModes.TRANSITIONS.contains(player.getUUID())
            && !GameModes.PENDING.containsKey(player.getUUID());
    }

    static ItemStack picker() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(PICKER_NAME));
        return stack;
    }

    static boolean isPicker(ItemStack stack) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return stack.is(Items.COMPASS) && name != null && PICKER_NAME.equals(name.getString());
    }

    private static boolean hasPicker(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) if (isPicker(player.getInventory().getItem(slot))) return true;
        return false;
    }

    private static boolean hasWeapon(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) {
            var item = player.getInventory().getItem(slot).getItem();
            if (item == Convergence.ITEMS.get("convergence:sword")
                || item == Convergence.ITEMS.get("convergence:mace")
                || item == Convergence.ITEMS.get("convergence:spear")) return true;
        }
        return false;
    }

    private static int freeHotbarSlot(Inventory inventory, int from, int to) {
        for (int slot = from; slot <= to; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        return -1;
    }

    /** Only add to empty slots; returning players keep their Creative inventory. */
    private static boolean ensurePicker(ServerPlayer player) {
        if (hasPicker(player)) return true;
        Inventory inventory = player.getInventory();
        int slot = inventory.getItem(8).isEmpty() ? 8 : freeHotbarSlot(inventory, 0, 7);
        if (slot < 0) slot = inventory.getFreeSlot();
        if (slot < 0) return false;
        inventory.setItem(slot, picker());
        player.inventoryMenu.sendAllDataToRemote();
        return true;
    }

    static void onEnter(ServerPlayer player) {
        if (!player.isCreative() || GameModes.current(player) != GameModes.Mode.CREATIVE) return;
        Inventory inventory = player.getInventory();
        boolean hasPicker = ensurePicker(player);
        boolean weaponPresent = hasWeapon(player);
        boolean swordGranted = false;
        if (!weaponPresent) {
            int slot = freeHotbarSlot(inventory, 0, 7);
            if (slot >= 0) {
                inventory.setItem(slot, new ItemStack(Convergence.ITEMS.get("convergence:sword")));
                inventory.setSelectedSlot(slot);
                player.connection.send(new ClientboundSetHeldSlotPacket(slot));
                swordGranted = true;
            }
        }
        player.inventoryMenu.sendAllDataToRemote();
        String message = hasPicker
            ? "Infinity gear: select the named compass to pick a weapon or tool."
            : "Clear an inventory slot to receive the Infinity Gear Picker.";
        if (swordGranted) message += " A sword is ready in your hotbar.";
        else if (weaponPresent) message += " Your existing Infinity weapon is in your inventory.";
        else message += " Clear a hotbar slot to hold a weapon.";
        player.sendSystemMessage(Component.literal(message));
        OPEN_AFTER.put(player.getUUID(), player.level().getServer().getTickCount() + 3);
    }

    private static String label(String path) {
        return GearNames.label(path);
    }

    static int open(ServerPlayer player) {
        if (!allowed(player) || player.containerMenu != player.inventoryMenu
            || !player.containerMenu.getCarried().isEmpty()) return 0;
        List<String> paths = new ArrayList<>(Convergence.ITEMS.keySet());
        if (paths.size() > 54) throw new IllegalStateException("Infinity gear picker exceeds six rows");
        var view = new SimpleContainer(54);
        for (int slot = 0; slot < paths.size(); slot++) {
            String name = paths.get(slot);
            var icon = CrossplaySupport.BASES.get(name.substring("convergence:".length()));
            if (icon == null) throw new IllegalStateException("Missing safe picker icon for " + name);
            ItemStack stack = new ItemStack(icon);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(label(name.substring("convergence:".length()))));
            view.setItem(slot, stack);
        }
        player.openMenu(new SimpleMenuProvider((sync, inventory, who) ->
            new PickerHandler(sync, inventory, view, player, paths), Component.literal("Infinity Gear • Pick One")));
        if (isPicker(player.getMainHandItem())) SELECTED.add(player.getUUID());
        return 1;
    }

    /** Equip beside the compass so it remains in the hotbar for another pick. */
    static int equip(ServerPlayer player, String name) {
        if (!allowed(player) || !Convergence.ITEMS.containsKey(name)) return 0;
        Inventory inventory = player.getInventory();
        int slot = freeHotbarSlot(inventory, 0, 7);
        if (slot < 0) slot = 0;
        ItemStack previous = inventory.getItem(slot).copy();
        if (!previous.isEmpty() && inventory.getFreeSlot() < 0) {
            player.sendSystemMessage(Component.literal("Clear one inventory slot before choosing another item."));
            return 0;
        }
        var item = Convergence.ITEMS.get(name);
        inventory.setItem(slot, new ItemStack(item, item.getDefaultMaxStackSize() > 1 ? 64 : 1));
        if (!previous.isEmpty()) inventory.placeItemBackInInventory(previous, Prediction.SERVER_ONLY);
        inventory.setSelectedSlot(slot);
        player.connection.send(new ClientboundSetHeldSlotPacket(slot));
        player.inventoryMenu.sendAllDataToRemote();
        SELECTED.remove(player.getUUID());
        player.sendSystemMessage(Component.literal("Holding " + label(name.substring("convergence:".length())) + ". Tap the compass to choose again."));
        return 1;
    }

    static final class PickerHandler extends ChestMenu {
        final ServerPlayer owner;
        final List<String> paths;

        PickerHandler(int sync, Inventory inventory, SimpleContainer view, ServerPlayer owner, List<String> paths) {
            super(MenuType.GENERIC_9x6, sync, inventory, view, 6);
            this.owner = owner;
            this.paths = paths;
        }

        @Override public boolean stillValid(Player player) {
            return player == owner && allowed(owner);
        }

        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public void setSelectedBundleItemIndex(int slot, int selected) { }

        @Override public void clicked(int slot, int button, ContainerInput action, Player player) {
            if (player != owner || !allowed(owner)) { owner.closeContainer(); return; }
            if ((action == ContainerInput.PICKUP || action == ContainerInput.QUICK_MOVE)
                && slot >= 0 && slot < paths.size() && getCarried().isEmpty()) {
                String name = paths.get(slot);
                owner.closeContainer();
                equip(owner, name);
            } else sendAllDataToRemote();
        }
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerList().getPlayers()) {
                UUID id = player.getUUID();
                if (allowed(player) && !hasPicker(player)) ensurePicker(player);
                Integer at = OPEN_AFTER.get(id);
                if (at != null && server.getTickCount() >= at) {
                    OPEN_AFTER.remove(id);
                    open(player);
                }
                boolean selected = allowed(player) && isPicker(player.getMainHandItem());
                if (!selected) SELECTED.remove(id);
                else if (player.containerMenu == player.inventoryMenu) {
                    if (SELECTED.add(id)) open(player);
                } else if (!(player.containerMenu instanceof PickerHandler)) SELECTED.remove(id);
                if (!allowed(player) && player.containerMenu instanceof PickerHandler) player.closeContainer();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            OPEN_AFTER.remove(handler.player.getUUID());
            SELECTED.remove(handler.player.getUUID());
        });
    }
}
