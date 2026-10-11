package dev.convergence.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface AgentFlightAccess {
    @Invoker("setSharedFlag") void infinity$setFlag(int flag,boolean value);
}
