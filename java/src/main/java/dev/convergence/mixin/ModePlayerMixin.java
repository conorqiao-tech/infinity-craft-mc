package dev.convergence.mixin;
import dev.convergence.GameModes;
import dev.convergence.ModePlayer;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayer.class)
public abstract class ModePlayerMixin implements ModePlayer {
    @Unique private CompoundTag infinity$modeState = new CompoundTag();
    public CompoundTag infinity$state() { return infinity$modeState; }
    public void infinity$state(CompoundTag state) { infinity$modeState = state; }
    @Inject(method="addAdditionalSaveData", at=@At("TAIL"))
    private void save(ValueOutput view, CallbackInfo ci) { view.store("InfinityModes", CompoundTag.CODEC, infinity$modeState); }
    @Inject(method="readAdditionalSaveData", at=@At("TAIL"))
    private void load(ValueInput view, CallbackInfo ci) {
        infinity$modeState = view.read("InfinityModes", CompoundTag.CODEC).orElseGet(CompoundTag::new);
        dev.convergence.AdminStats.restoreExtended((ServerPlayer)(Object)this,view);
    }
    @Inject(method="restoreFrom(Lnet/minecraft/server/level/ServerPlayer;Z)V", at=@At("TAIL"))
    private void copy(ServerPlayer old, boolean alive, CallbackInfo ci) {
        infinity$modeState = ((ModePlayer)old).infinity$state().copy();
        dev.convergence.AdminStats.restoreExtended((ServerPlayer)(Object)this,null);
    }
    @Inject(method="teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at=@At("HEAD"), cancellable=true)
    private void portal(TeleportTransition target, CallbackInfoReturnable<ServerPlayer> cir) {
        if (!GameModes.allowTeleport((ServerPlayer)(Object)this, target.newLevel())) cir.setReturnValue(null);
        else GameModes.beforeOperatorTeleport((ServerPlayer)(Object)this,target.newLevel());
    }
    @Inject(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at=@At("HEAD"), cancellable=true)
    private void teleport(ServerLevel world, double x, double y, double z, Set<Relative> flags, float yaw, float pitch, boolean reset, CallbackInfoReturnable<Boolean> cir) {
        if (!GameModes.allowTeleport((ServerPlayer)(Object)this, world)) cir.setReturnValue(false);
        else GameModes.beforeOperatorTeleport((ServerPlayer)(Object)this,world);
    }
    @Inject(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z", at=@At("RETURN"))
    private void teleported(ServerLevel world, double x, double y, double z, Set<Relative> flags, float yaw, float pitch, boolean reset, CallbackInfoReturnable<Boolean> cir) {
        GameModes.afterOperatorTeleport((ServerPlayer)(Object)this,cir.getReturnValue());
    }
    @Inject(method="teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;", at=@At("RETURN"))
    private void portalled(TeleportTransition target, CallbackInfoReturnable<ServerPlayer> cir) {
        GameModes.afterOperatorTeleport((ServerPlayer)(Object)this,cir.getReturnValue()!=null);
    }
    @Inject(method="findRespawnPositionAndUseSpawnBlock", at=@At("RETURN"), cancellable=true)
    private void respawn(boolean alive, TeleportTransition.PostTeleportTransition callback, CallbackInfoReturnable<TeleportTransition> cir) {
        cir.setReturnValue(GameModes.respawnTarget((ServerPlayer)(Object)this, cir.getReturnValue(), callback));
    }
}
