package dev.convergence.mixin;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.convergence.InfinityApiary;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BeehiveBlock.class)
abstract class ApiaryHarvestMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void rememberTool(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> result,
            @Share("tool") LocalRef<Item> tool) {
        tool.set(stack.getItem());
    }

    // Vanilla's unsmoked branch releases bees through a different method.
    @Inject(method = "useItemOn", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/BeehiveBlock;resetHoneyLevel(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
        shift = At.Shift.AFTER))
    private void recordSmokedHarvest(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> result,
            @Share("tool") LocalRef<Item> tool) {
        if (player instanceof ServerPlayer serverPlayer) {
            var after = level.getBlockState(pos);
            if (after.is(state.getBlock()) && after.getValue(BeehiveBlock.HONEY_LEVEL) == 0)
                InfinityApiary.recordHarvest(serverPlayer, tool.get());
        }
    }
}
