package dev.convergence.mixin;

import dev.convergence.OdysseyStructure;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.level.BaseCommandBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Applies only while the native command block executes; player/console permissions are untouched. */
@Mixin(BaseCommandBlock.class)
public abstract class OdysseyCommandMixin {
    @ModifyArg(method="performCommand",at=@At(value="INVOKE",target="Lnet/minecraft/commands/Commands;performPrefixedCommand(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V"),index=0)
    private CommandSourceStack odysseyCommand(CommandSourceStack source) {
        return OdysseyStructure.repeatingSource((BaseCommandBlock)(Object)this,source);
    }
}
