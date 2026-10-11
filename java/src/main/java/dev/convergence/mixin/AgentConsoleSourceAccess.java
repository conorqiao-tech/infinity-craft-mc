package dev.convergence.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Distinguish the local server console from RCON and command blocks. */
@Mixin(CommandSourceStack.class)
public interface AgentConsoleSourceAccess {
    @Accessor("source")
    CommandSource infinity$getOutput();
}
