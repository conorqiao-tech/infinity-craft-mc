package dev.convergence.mixin;

import dev.convergence.AdminStatEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IronGolem.class)
public abstract class AdminStatGolemMixin implements AdminStatEntity {
    @Unique private CompoundTag infinity$adminStats=new CompoundTag();
    public CompoundTag infinity$adminStats() {return infinity$adminStats;}
    public void infinity$adminStats(CompoundTag data) {infinity$adminStats=data;}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"))
    private void save(ValueOutput view,CallbackInfo ci) {
        if(!infinity$adminStats.isEmpty())view.store("InfinityAdminStats",CompoundTag.CODEC,infinity$adminStats);
    }
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"))
    private void load(ValueInput view,CallbackInfo ci) {
        infinity$adminStats=view.read("InfinityAdminStats",CompoundTag.CODEC).orElseGet(CompoundTag::new);
        dev.convergence.AdminStats.restoreExtended((IronGolem)(Object)this,view);
    }
}
