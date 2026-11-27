package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Plan a banner recipe in copied storage slots before spending any material. */
final class BannerWeaving {
    enum Result { WOVEN, NO_MATERIAL, NO_ROOM }

    private BannerWeaving() {}

    // Prefer singletons so a full inventory can reuse a slot freed by the recipe.
    private static boolean takeOne(List<ItemStack> stacks, Item item) {
        var plain = new ItemStack(item);
        int first = -1;
        for (int i = 0; i < stacks.size(); i++) {
            var stack = stacks.get(i);
            if (!ItemStack.isSameItemSameComponents(stack, plain)) continue;
            if (stack.getCount() == 1) { stacks.set(i, ItemStack.EMPTY); return true; }
            if (first < 0) first = i;
        }
        if (first < 0) return false;
        stacks.get(first).shrink(1);
        return true;
    }

    static Result weave(ServerPlayer p, ItemStack flag, Item... materials) {
        var after = new ArrayList<ItemStack>();
        for (var stack : p.getInventory().getNonEquipmentItems()) after.add(stack.copy());
        for (var material : materials) {
            if (!takeOne(after, material)) return Result.NO_MATERIAL;
        }
        // Place the result in a matching stack or the first free slot after taking ingredients.
        int output = -1;
        for (int i = 0; i < after.size(); i++) {
            var stack = after.get(i);
            if (stack.isEmpty() && output < 0) output = i;
            if (ItemStack.isSameItemSameComponents(stack, flag) && stack.getCount() < stack.getMaxStackSize()) {
                output = i;
                break;
            }
        }
        if (output < 0) return Result.NO_ROOM;
        after.set(output, flag.copyWithCount(after.get(output).getCount() + 1));
        for (int i = 0; i < after.size(); i++) {
            if (!ItemStack.matches(p.getInventory().getItem(i), after.get(i))) p.getInventory().setItem(i, after.get(i));
        }
        PlayerPersistence.save(p);
        p.inventoryMenu.sendAllDataToRemote();
        return Result.WOVEN;
    }
}
