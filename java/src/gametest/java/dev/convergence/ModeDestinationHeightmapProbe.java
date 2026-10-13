package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Test-mod counts of the actual native heightmap reads, scoped to one synchronous selector poll. */
public final class ModeDestinationHeightmapProbe {
    public static final class Sample implements AutoCloseable {
        final ServerLevel world;
        final List<BlockPos> columns=new ArrayList<>();
        private Sample(ServerLevel world){this.world=world;}
        @Override public void close(){
            if(ACTIVE.get()!=this)throw new IllegalStateException("Wrong destination heightmap observation scope");
            ACTIVE.remove();
        }
    }
    private static final ThreadLocal<Sample> ACTIVE=new ThreadLocal<>();
    static Sample begin(ServerLevel world){
        if(ACTIVE.get()!=null)throw new IllegalStateException("Nested destination heightmap observation");
        var sample=new Sample(world);ACTIVE.set(sample);return sample;
    }
    public static void read(ChunkAccess chunk,Heightmap.Types type,int x,int z){
        var sample=ACTIVE.get();
        if(sample!=null&&type==Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
            &&chunk instanceof LevelChunk full&&full.getLevel()==sample.world)
            sample.columns.add(new BlockPos((chunk.getPos().x()<<4)+x,0,(chunk.getPos().z()<<4)+z));
    }
    private ModeDestinationHeightmapProbe(){}
}
