package dev.convergence.backpackperf;

import dev.convergence.BackpackSerializationProbe;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the native test mod installs these serialization observers. */
@Mixin(targets="dev.convergence.BackpackStorage")
abstract class BackpackSerializationProbeMixin {
    @Inject(method="write",at=@At("HEAD"))
    private static void beforeWrite(ServerPlayer player,String mode,List<ItemStack> stacks,CallbackInfo ci) {BackpackSerializationProbe.attempt(player);}
    @Inject(method="write",at=@At("RETURN"))
    private static void afterWrite(ServerPlayer player,String mode,List<ItemStack> stacks,CallbackInfo ci) {BackpackSerializationProbe.completed(player);}
}
