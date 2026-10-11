package dev.convergence.rcperf;

import dev.convergence.RcPlanePerformanceProbe;
import dev.convergence.RcPlanes;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only the native test mod installs these observers. */
@Mixin(RcPlanes.class)
abstract class RcPlaneProbeMixin {
    @Redirect(method="clear",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private static List<LivingEntity> nativeQuery(ServerLevel world,Class<LivingEntity> type,AABB box,Predicate<LivingEntity> predicate,@Coerce Object plane,Vec3 center){
        RcPlanePerformanceProbe.queryPlane(plane,box);
        var entities=world.getEntitiesOfClass(type,box,predicate);
        RcPlanePerformanceProbe.planeCandidates(plane,entities);
        return entities;
    }
    @Redirect(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;sendOverlayMessage(Lnet/minecraft/network/chat/Component;)V"))
    private static void nativeNotice(ServerPlayer owner,Component message){
        RcPlanePerformanceProbe.notice(owner);
        owner.sendOverlayMessage(message);
    }
}
