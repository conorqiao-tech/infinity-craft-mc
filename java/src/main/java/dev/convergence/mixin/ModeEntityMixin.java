package dev.convergence.mixin;
import dev.convergence.GameModes;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class ModeEntityMixin {
    @Inject(method="teleportTo", at=@At("HEAD"), cancellable=true)
    private void portal(TeleportTarget target, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity)(Object)this;
        if (self instanceof ServerPlayerEntity p && GameModes.allowTeleport(p,target.world())) return;
        if (GameModes.of(self.getEntityWorld()) != GameModes.of(target.world())) cir.setReturnValue(null);
    }
}
