package dev.convergence.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Preserve vanilla player, stats and advancement saves without saving unrelated players. */
@Mixin(PlayerList.class)
public interface PlayerSaveAccess {
    @Invoker("save") void infinity$save(ServerPlayer player);
}
