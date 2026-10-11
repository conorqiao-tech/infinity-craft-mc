package dev.convergence.mixin;

import dev.convergence.BlastSafety;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
public abstract class TntBudgetMixin {
    @Inject(method="tick",at=@At("HEAD"))
    private void infinity$queueDetonation(CallbackInfo ci){
        var tnt=(PrimedTnt)(Object)this;
        if(tnt.level() instanceof ServerLevel world&&tnt.getFuse()<=1&&!BlastSafety.detonate(world.getServer()))tnt.setFuse(2);
    }
}
