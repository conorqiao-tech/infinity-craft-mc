package dev.convergence.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.TrackedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface LivingVitalsAccess {
    @Accessor("HEALTH")
    static TrackedData<Float> infinity$health() { throw new AssertionError(); }
}
