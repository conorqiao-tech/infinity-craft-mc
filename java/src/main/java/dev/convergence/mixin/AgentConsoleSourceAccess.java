package dev.convergence.mixin;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Distinguish the local server console from RCON and command blocks. */
@Mixin(ServerCommandSource.class)
public interface AgentConsoleSourceAccess {
    @Accessor("output")
    CommandOutput infinity$getOutput();
}
