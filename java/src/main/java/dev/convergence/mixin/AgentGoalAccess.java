package dev.convergence.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla clearGoalsAndTasks does not clear revenge or village target goals. */
@Mixin(MobEntity.class)
public interface AgentGoalAccess {
    @Accessor("targetSelector")
    GoalSelector infinity$getTargetSelector();
}
