package dev.convergence.mixin;
import dev.convergence.GameModes;
import dev.convergence.ModePlayer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.world.TeleportTarget;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayerEntity.class)
public abstract class ModePlayerMixin implements ModePlayer {
    @Unique private NbtCompound infinity$modeState = new NbtCompound();
    public NbtCompound infinity$state() { return infinity$modeState; }
    public void infinity$state(NbtCompound state) { infinity$modeState = state; }
    @Inject(method="writeCustomData", at=@At("TAIL"))
    private void save(WriteView view, CallbackInfo ci) { view.put("InfinityModes", NbtCompound.CODEC, infinity$modeState); }
    @Inject(method="readCustomData", at=@At("TAIL"))
    private void load(ReadView view, CallbackInfo ci) {
        infinity$modeState = view.read("InfinityModes", NbtCompound.CODEC).orElseGet(NbtCompound::new);
        dev.convergence.AdminStats.restoreExtended((ServerPlayerEntity)(Object)this,view);
    }
    @Inject(method="copyFrom(Lnet/minecraft/server/network/ServerPlayerEntity;Z)V", at=@At("TAIL"))
    private void copy(ServerPlayerEntity old, boolean alive, CallbackInfo ci) {
        infinity$modeState = ((ModePlayer)old).infinity$state().copy();
        dev.convergence.AdminStats.restoreExtended((ServerPlayerEntity)(Object)this,null);
    }
    @Inject(method="teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/server/network/ServerPlayerEntity;", at=@At("HEAD"), cancellable=true)
    private void portal(TeleportTarget target, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        if (!GameModes.allowTeleport((ServerPlayerEntity)(Object)this, target.world())) cir.setReturnValue(null);
        else GameModes.beforeOperatorTeleport((ServerPlayerEntity)(Object)this,target.world());
    }
    @Inject(method="teleport", at=@At("HEAD"), cancellable=true)
    private void teleport(ServerWorld world, double x, double y, double z, Set<PositionFlag> flags, float yaw, float pitch, boolean reset, CallbackInfoReturnable<Boolean> cir) {
        if (!GameModes.allowTeleport((ServerPlayerEntity)(Object)this, world)) cir.setReturnValue(false);
        else GameModes.beforeOperatorTeleport((ServerPlayerEntity)(Object)this,world);
    }
    @Inject(method="teleport", at=@At("RETURN"))
    private void teleported(ServerWorld world, double x, double y, double z, Set<PositionFlag> flags, float yaw, float pitch, boolean reset, CallbackInfoReturnable<Boolean> cir) {
        GameModes.afterOperatorTeleport((ServerPlayerEntity)(Object)this,cir.getReturnValue());
    }
    @Inject(method="teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/server/network/ServerPlayerEntity;", at=@At("RETURN"))
    private void portalled(TeleportTarget target, CallbackInfoReturnable<ServerPlayerEntity> cir) {
        GameModes.afterOperatorTeleport((ServerPlayerEntity)(Object)this,cir.getReturnValue()!=null);
    }
    @Inject(method="getRespawnTarget", at=@At("RETURN"), cancellable=true)
    private void respawn(boolean alive, TeleportTarget.PostDimensionTransition callback, CallbackInfoReturnable<TeleportTarget> cir) {
        cir.setReturnValue(GameModes.respawnTarget((ServerPlayerEntity)(Object)this, cir.getReturnValue(), callback));
    }
}
