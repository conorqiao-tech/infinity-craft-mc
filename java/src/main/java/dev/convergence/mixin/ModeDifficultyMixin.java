package dev.convergence.mixin;
import dev.convergence.GameModes;
import net.minecraft.world.World;
import net.minecraft.world.Difficulty;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(World.class)
public abstract class ModeDifficultyMixin implements net.minecraft.world.WorldAccess {
    @Override
    public Difficulty getDifficulty() {
        var mode = GameModes.of((World)(Object)this);
        if (mode == GameModes.Mode.HARDCORE) return Difficulty.HARD;
        if (mode != GameModes.Mode.SURVIVAL) return Difficulty.PEACEFUL;
        return getLevelProperties().getDifficulty();
    }
}
