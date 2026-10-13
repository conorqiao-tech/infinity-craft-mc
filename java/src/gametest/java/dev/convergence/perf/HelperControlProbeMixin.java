package dev.convergence.perf;

import dev.convergence.AgentCompanions;
import dev.convergence.HelperPerformanceProbe;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AgentCompanions.class)
abstract class HelperControlProbeMixin {
    @Shadow @Final net.minecraft.server.MinecraftServer server;
    @Inject(method="tick",at=@At("HEAD")) private void tickStart(CallbackInfo ci){HelperPerformanceProbe.tickStart(server.getTickCount());}
    @Inject(method="tick",at=@At("RETURN")) private void tickEnd(CallbackInfo ci){HelperPerformanceProbe.tickEnd();}
    @Inject(method="control",at=@At("HEAD")) private void controlStart(CallbackInfo ci){HelperPerformanceProbe.controlStart();}
    @Inject(method="control",at=@At("RETURN")) private void controlEnd(CallbackInfo ci){HelperPerformanceProbe.controlEnd();}
    @Inject(method="sharedFocusRank",at=@At("HEAD")) private void focusRank(CallbackInfoReturnable<Integer> ci){if(HelperPerformanceProbe.measuringControl())HelperPerformanceProbe.active.focusLookups++;}
    @Redirect(method="sharedFocusRank",at=@At(value="INVOKE",target="Ljava/util/Iterator;next()Ljava/lang/Object;"))
    private Object focusPeer(Iterator<?> iterator){if(HelperPerformanceProbe.measuringControl())HelperPerformanceProbe.active.focusPeerVisits++;return iterator.next();}
    @Redirect(method="control",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private List<Mob> query(ServerLevel world,Class<Mob> type,AABB box,Predicate<Mob> predicate){
        if(!HelperPerformanceProbe.measuringControl())return world.getEntitiesOfClass(type,box,predicate);
        HelperPerformanceProbe.active.queries++;
        var result=world.getEntitiesOfClass(type,box,mob->{HelperPerformanceProbe.active.predicateVisits++;return predicate.test(mob);});
        HelperPerformanceProbe.active.candidates+=result.size();return result;
    }
}
