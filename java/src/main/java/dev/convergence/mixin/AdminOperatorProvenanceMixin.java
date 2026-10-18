package dev.convergence.mixin;

import dev.convergence.PurchasedAdminRoles;
import java.util.Optional;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve exact native owner decisions across later paid-role expiry and process restarts. */
@Mixin(PlayerList.class)
public abstract class AdminOperatorProvenanceMixin {
    @Inject(method="op(Lnet/minecraft/server/players/NameAndId;Ljava/util/Optional;Ljava/util/Optional;)V",at=@At("HEAD"))
    private void beforeOp(NameAndId target,Optional<LevelBasedPermissionSet> permissions,Optional<Boolean> bypass,CallbackInfo ci){
        PurchasedAdminRoles.beforeNativeOp((PlayerList)(Object)this,target,permissions,bypass);
    }
    @Inject(method="op(Lnet/minecraft/server/players/NameAndId;Ljava/util/Optional;Ljava/util/Optional;)V",at=@At("RETURN"))
    private void afterOp(NameAndId target,Optional<LevelBasedPermissionSet> permissions,Optional<Boolean> bypass,CallbackInfo ci){
        PurchasedAdminRoles.afterNativeOperatorChange((PlayerList)(Object)this,target);
    }
    @Inject(method="deop",at=@At("HEAD"))
    private void beforeDeop(NameAndId target,CallbackInfo ci){PurchasedAdminRoles.beforeNativeDeop((PlayerList)(Object)this,target);}
    @Inject(method="deop",at=@At("RETURN"))
    private void afterDeop(NameAndId target,CallbackInfo ci){PurchasedAdminRoles.afterNativeOperatorChange((PlayerList)(Object)this,target);}
}
