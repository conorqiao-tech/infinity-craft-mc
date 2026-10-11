package dev.convergence;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Test-mod observer: only discovery calls in the current scoped server-thread sample are checked. */
public final class OdysseyJournalChunkProbe {
    public static final class Sample implements AutoCloseable {
        final ServerChunkCache source;
        final Map<Long,Integer> completedLookups=new HashMap<>();
        int synchronousFullRequests;
        private Sample(ServerChunkCache source){this.source=source;}
        int lookups(ChunkPos pos){return completedLookups.getOrDefault(pos.pack(),0);}
        int totalLookups(){return completedLookups.values().stream().mapToInt(Integer::intValue).sum();}
        @Override public void close(){
            if(ACTIVE.get()!=this)throw new IllegalStateException("Wrong journal chunk observation scope");
            ACTIVE.remove();
        }
    }
    private static final ThreadLocal<Sample> ACTIVE=new ThreadLocal<>();
    static Sample begin(ServerChunkCache source){
        if(ACTIVE.get()!=null)throw new IllegalStateException("Nested journal chunk observation");
        var sample=new Sample(source);ACTIVE.set(sample);return sample;
    }
    public static void completedLookup(ServerChunkCache source,int x,int z){
        var sample=ACTIVE.get();
        if(sample!=null&&sample.source==source)sample.completedLookups.merge(ChunkPos.pack(x,z),1,Integer::sum);
    }
    public static void synchronousLookup(ServerChunkCache source,ChunkStatus status){
        var sample=ACTIVE.get();
        if(sample!=null&&sample.source==source&&status==ChunkStatus.FULL){
            sample.synchronousFullRequests++;
            // Fail before the old synchronous path can wait on the deliberately incomplete future.
            throw new IllegalStateException("Odyssey discovery requested a synchronous FULL chunk");
        }
    }
    private OdysseyJournalChunkProbe(){}
}
