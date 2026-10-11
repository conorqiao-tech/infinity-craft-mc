package dev.convergence.mixin;

import dev.convergence.ClientVitals;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerCommonNetworkHandler.class)
abstract class ClientVitalsMixin {
    @ModifyVariable(method="send", at=@At("HEAD"), argsOnly=true)
    private Packet<?> infinity$compactVitals(Packet<?> packet) {
        return ClientVitals.rewrite(packet, (Object)this instanceof ServerPlayNetworkHandler play ? play.player : null);
    }
}
