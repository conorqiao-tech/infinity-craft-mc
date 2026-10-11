package dev.convergence.menuperf;

import dev.convergence.MenuTrackingPerformanceProbe;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the real registered tick path rather than a duplicate picker implementation. */
@Mixin(targets="dev.convergence.CreativeGearPicker")
abstract class CreativePickerProbeMixin {
    @Inject(method="hasPicker",at=@At("HEAD"))
    private static void scan(ServerPlayer player,CallbackInfoReturnable<Boolean> cir) { MenuTrackingPerformanceProbe.scan(player); }
    @Redirect(method="hasPicker",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Inventory;getItem(I)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack slot(Inventory inventory,int slot,ServerPlayer player) {
        MenuTrackingPerformanceProbe.slot(player);
        return inventory.getItem(slot);
    }
}
