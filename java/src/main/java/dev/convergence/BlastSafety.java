package dev.convergence;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;

/** Queue live TNT instead of deleting it; bound a single pathological explosion's work. */
public final class BlastSafety {
    static final int TNT_PER_TICK=2;
    static final long WINDOW_NANOS=8_000_000L;
    public static final float MAX_RADIUS=8;
    private static final Map<MinecraftServer,Budget> BUDGETS=new WeakHashMap<>();
    private static final class Budget { int tick=Integer.MIN_VALUE,count;long first; }
    private BlastSafety(){}
    public static boolean detonate(MinecraftServer server){
        var budget=BUDGETS.computeIfAbsent(server,s->new Budget());
        int tick=server.getTickCount();long now=System.nanoTime();
        if(budget.tick!=tick){budget.tick=tick;budget.count=0;budget.first=now;}
        if(budget.count>=TNT_PER_TICK||(budget.count>0&&now-budget.first>=WINDOW_NANOS))return false;
        budget.count++;return true;
    }
    public static float radius(float value){return Float.isFinite(value)?Math.max(0,Math.min(value,MAX_RADIUS)):0;}
}
