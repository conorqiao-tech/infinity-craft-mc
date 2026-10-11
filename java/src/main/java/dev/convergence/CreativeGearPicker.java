package dev.convergence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.UpdateSelectedSlotS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Vanilla chest icons and a vanilla compass keep the picker usable through Geyser. */
final class CreativeGearPicker {
    private static final String PICKER_NAME = "Infinity Gear Picker";
    private static final Map<UUID,Integer> OPEN_AFTER = new HashMap<>();
    private static final Set<UUID> SELECTED = new HashSet<>();

    private CreativeGearPicker() {}

    static boolean allowed(ServerPlayerEntity player) {
        return player.isAlive() && player.isCreative() && !player.isSpectator()
            && GameModes.current(player) == GameModes.Mode.CREATIVE
            && !GameModes.TRANSITIONS.contains(player.getUuid())
            && !GameModes.PENDING.containsKey(player.getUuid());
    }

    static ItemStack picker() {
        ItemStack stack = new ItemStack(Items.COMPASS);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(PICKER_NAME));
        return stack;
    }

    static boolean isPicker(ItemStack stack) {
        Text name = stack.get(DataComponentTypes.CUSTOM_NAME);
        return stack.isOf(Items.COMPASS) && name != null && PICKER_NAME.equals(name.getString());
    }

    private static boolean hasPicker(ServerPlayerEntity player) {
        for (int slot = 0; slot < 36; slot++) if (isPicker(player.getInventory().getStack(slot))) return true;
        return false;
    }

    private static boolean hasWeapon(ServerPlayerEntity player) {
        for (int slot = 0; slot < 36; slot++) {
            var item = player.getInventory().getStack(slot).getItem();
            if (item == Convergence.ITEMS.get("convergence:sword")
                || item == Convergence.ITEMS.get("convergence:mace")
                || item == Convergence.ITEMS.get("convergence:spear")) return true;
        }
        return false;
    }

    private static int freeHotbarSlot(PlayerInventory inventory, int from, int to) {
        for (int slot = from; slot <= to; slot++) if (inventory.getStack(slot).isEmpty()) return slot;
        return -1;
    }

    /** Only add to empty slots; returning players keep their Creative inventory. */
    private static boolean ensurePicker(ServerPlayerEntity player) {
        if (hasPicker(player)) return true;
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getStack(8).isEmpty() ? 8 : freeHotbarSlot(inventory, 0, 7);
        if (slot < 0) slot = inventory.getEmptySlot();
        if (slot < 0) return false;
        inventory.setStack(slot, picker());
        player.playerScreenHandler.syncState();
        return true;
    }

    static void onEnter(ServerPlayerEntity player) {
        if (!player.isCreative() || GameModes.current(player) != GameModes.Mode.CREATIVE) return;
        PlayerInventory inventory = player.getInventory();
        boolean hasPicker = ensurePicker(player);
        boolean weaponPresent = hasWeapon(player);
        boolean swordGranted = false;
        if (!weaponPresent) {
            int slot = freeHotbarSlot(inventory, 0, 7);
            if (slot >= 0) {
                inventory.setStack(slot, new ItemStack(Convergence.ITEMS.get("convergence:sword")));
                inventory.setSelectedSlot(slot);
                player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(slot));
                swordGranted = true;
            }
        }
        player.playerScreenHandler.syncState();
        String message = hasPicker
            ? "Infinity gear: select the named compass to pick a weapon or tool."
            : "Clear an inventory slot to receive the Infinity Gear Picker.";
        if (swordGranted) message += " A sword is ready in your hotbar.";
        else if (weaponPresent) message += " Your existing Infinity weapon is in your inventory.";
        else message += " Clear a hotbar slot to hold a weapon.";
        player.sendMessage(Text.literal(message), false);
        OPEN_AFTER.put(player.getUuid(), player.getEntityWorld().getServer().getTicks() + 3);
    }

    private static String label(String path) {
        StringBuilder result = new StringBuilder("Infinity ");
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1)).append(' ');
        }
        return result.toString().trim();
    }

    static int open(ServerPlayerEntity player) {
        if (!allowed(player) || player.currentScreenHandler != player.playerScreenHandler
            || !player.currentScreenHandler.getCursorStack().isEmpty()) return 0;
        List<String> paths = new ArrayList<>(Convergence.ITEMS.keySet());
        if (paths.size() > 54) throw new IllegalStateException("Infinity gear picker exceeds six rows");
        var view = new SimpleInventory(54);
        for (int slot = 0; slot < paths.size(); slot++) {
            String name = paths.get(slot);
            var icon = CrossplaySupport.BASES.get(name.substring("convergence:".length()));
            if (icon == null) throw new IllegalStateException("Missing safe picker icon for " + name);
            ItemStack stack = new ItemStack(icon);
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(label(name.substring("convergence:".length()))));
            view.setStack(slot, stack);
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((sync, inventory, who) ->
            new PickerHandler(sync, inventory, view, player, paths), Text.literal("Infinity Gear • Pick One")));
        if (isPicker(player.getMainHandStack())) SELECTED.add(player.getUuid());
        return 1;
    }

    /** Equip beside the compass so it remains in the hotbar for another pick. */
    static int equip(ServerPlayerEntity player, String name) {
        if (!allowed(player) || !Convergence.ITEMS.containsKey(name)) return 0;
        PlayerInventory inventory = player.getInventory();
        int slot = freeHotbarSlot(inventory, 0, 7);
        if (slot < 0) slot = 0;
        ItemStack previous = inventory.getStack(slot).copy();
        if (!previous.isEmpty() && inventory.getEmptySlot() < 0) {
            player.sendMessage(Text.literal("Clear one inventory slot before choosing another item."), false);
            return 0;
        }
        var item = Convergence.ITEMS.get(name);
        inventory.setStack(slot, new ItemStack(item, item.getMaxCount() > 1 ? 64 : 1));
        if (!previous.isEmpty()) inventory.offerOrDrop(previous);
        inventory.setSelectedSlot(slot);
        player.networkHandler.sendPacket(new UpdateSelectedSlotS2CPacket(slot));
        player.playerScreenHandler.syncState();
        SELECTED.remove(player.getUuid());
        player.sendMessage(Text.literal("Holding " + label(name.substring("convergence:".length())) + ". Tap the compass to choose again."), false);
        return 1;
    }

    static final class PickerHandler extends GenericContainerScreenHandler {
        final ServerPlayerEntity owner;
        final List<String> paths;

        PickerHandler(int sync, PlayerInventory inventory, SimpleInventory view, ServerPlayerEntity owner, List<String> paths) {
            super(ScreenHandlerType.GENERIC_9X6, sync, inventory, view, 6);
            this.owner = owner;
            this.paths = paths;
        }

        @Override public boolean canUse(PlayerEntity player) {
            return player == owner && allowed(owner);
        }

        @Override public ItemStack quickMove(PlayerEntity player, int slot) { return ItemStack.EMPTY; }
        @Override public void selectBundleStack(int slot, int selected) { }

        @Override public void onSlotClick(int slot, int button, SlotActionType action, PlayerEntity player) {
            if (player != owner || !allowed(owner)) { owner.closeHandledScreen(); return; }
            if ((action == SlotActionType.PICKUP || action == SlotActionType.QUICK_MOVE)
                && slot >= 0 && slot < paths.size() && getCursorStack().isEmpty()) {
                String name = paths.get(slot);
                owner.closeHandledScreen();
                equip(owner, name);
            } else syncState();
        }
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerManager().getPlayerList()) {
                UUID id = player.getUuid();
                if (allowed(player) && !hasPicker(player)) ensurePicker(player);
                Integer at = OPEN_AFTER.get(id);
                if (at != null && server.getTicks() >= at) {
                    OPEN_AFTER.remove(id);
                    open(player);
                }
                boolean selected = allowed(player) && isPicker(player.getMainHandStack());
                if (!selected) SELECTED.remove(id);
                else if (player.currentScreenHandler == player.playerScreenHandler) {
                    if (SELECTED.add(id)) open(player);
                } else if (!(player.currentScreenHandler instanceof PickerHandler)) SELECTED.remove(id);
                if (!allowed(player) && player.currentScreenHandler instanceof PickerHandler) player.closeHandledScreen();
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            OPEN_AFTER.remove(handler.player.getUuid());
            SELECTED.remove(handler.player.getUuid());
        });
    }
}
