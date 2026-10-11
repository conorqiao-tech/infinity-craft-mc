package dev.convergence.mixin;
import dev.convergence.GameModes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import dev.convergence.AgentCompanions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class ModeEntityMixin {
    @Inject(method="teleport", at=@At("HEAD"), cancellable=true)
    private void portal(TeleportTransition target, CallbackInfoReturnable<Entity> cir) {
        Entity self = (Entity)(Object)this;
        if (self instanceof ServerPlayer p && GameModes.allowTeleport(p,target.newLevel())) return;
        if (AgentCompanions.allowRecallTeleport(self, target)) return;
        if (GameModes.of(self.level()) != GameModes.of(target.newLevel())) cir.setReturnValue(null);
    }
}
