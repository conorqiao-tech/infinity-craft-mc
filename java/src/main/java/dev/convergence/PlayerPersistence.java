package dev.convergence;

import dev.convergence.mixin.PlayerSaveAccess;
import net.minecraft.server.level.ServerPlayer;

/** Immediate native persistence for the participants of a local action. Called on the server thread. */
final class PlayerPersistence {
    private PlayerPersistence() {}

    static void save(ServerPlayer player) {
        ((PlayerSaveAccess) player.level().getServer().getPlayerList()).infinity$save(player);
    }
}
