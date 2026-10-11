package dev.convergence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

/** Test-mod-only observations of real native calls, scoped to one owner. */
public final class RcPlanePerformanceProbe {
    public static final class Sample {
        public int queries;
        public final List<AABB> bounds=new ArrayList<>();
        public final List<UUID> candidates=new ArrayList<>();
        public final List<Integer> noticeTicks=new ArrayList<>();
    }
    private static final Map<UUID,Sample> ACTIVE=new HashMap<>();
    public static Sample begin(ServerPlayer owner){
        var sample=new Sample();
        if(ACTIVE.putIfAbsent(owner.getUUID(),sample)!=null)throw new IllegalStateException("Nested plane measurement");
        return sample;
    }
    public static void end(ServerPlayer owner){ACTIVE.remove(owner.getUUID());}
    public static void query(ServerPlayer owner,AABB box){var sample=ACTIVE.get(owner.getUUID());if(sample!=null){sample.queries++;sample.bounds.add(box);}}
    public static void candidates(ServerPlayer owner,List<? extends net.minecraft.world.entity.LivingEntity> entities){var sample=ACTIVE.get(owner.getUUID());if(sample!=null)for(var entity:entities)sample.candidates.add(entity.getUUID());}
    public static void queryPlane(Object plane,AABB box){query(((RcPlanes.Plane)plane).owner,box);}
    public static void planeCandidates(Object plane,List<? extends net.minecraft.world.entity.LivingEntity> entities){candidates(((RcPlanes.Plane)plane).owner,entities);}
    public static void notice(ServerPlayer owner){var sample=ACTIVE.get(owner.getUUID());if(sample!=null)sample.noticeTicks.add(owner.level().getServer().getTickCount());}
    private RcPlanePerformanceProbe(){}
}
