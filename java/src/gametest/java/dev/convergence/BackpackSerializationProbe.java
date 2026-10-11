package dev.convergence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/** Native-test-only observations of the real backpack serializer, scoped to one owner. */
public final class BackpackSerializationProbe {
    public static final class Sample {public int attempts,completed;}
    private static final Map<UUID,Sample> ACTIVE=new HashMap<>();
    public static Sample begin(ServerPlayer owner) {
        var sample=new Sample();
        if(ACTIVE.putIfAbsent(owner.getUUID(),sample)!=null)throw new IllegalStateException("Nested backpack measurement");
        return sample;
    }
    public static void end(ServerPlayer owner) {ACTIVE.remove(owner.getUUID());}
    public static void attempt(ServerPlayer owner) {var sample=ACTIVE.get(owner.getUUID());if(sample!=null)sample.attempts++;}
    public static void completed(ServerPlayer owner) {var sample=ACTIVE.get(owner.getUUID());if(sample!=null)sample.completed++;}
    private BackpackSerializationProbe() {}
}
