package dev.convergence.mixin;

import dev.convergence.AdminStatEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IronGolemEntity.class)
public abstract class AdminStatGolemMixin implements AdminStatEntity {
    @Unique private NbtCompound infinity$adminStats=new NbtCompound();
    public NbtCompound infinity$adminStats() {return infinity$adminStats;}
    public void infinity$adminStats(NbtCompound data) {infinity$adminStats=data;}
    @Inject(method="writeCustomData",at=@At("TAIL"))
    private void save(WriteView view,CallbackInfo ci) {
        if(!infinity$adminStats.isEmpty())view.put("InfinityAdminStats",NbtCompound.CODEC,infinity$adminStats);
    }
    @Inject(method="readCustomData",at=@At("TAIL"))
    private void load(ReadView view,CallbackInfo ci) {
        infinity$adminStats=view.read("InfinityAdminStats",NbtCompound.CODEC).orElseGet(NbtCompound::new);
    }
}
