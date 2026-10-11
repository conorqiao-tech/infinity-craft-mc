package dev.convergence.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla clearGoalsAndTasks does not clear revenge or village target goals. */
@Mixin(Mob.class)
public interface AgentGoalAccess {
    @Accessor("targetSelector")
    GoalSelector infinity$getTargetSelector();
}
