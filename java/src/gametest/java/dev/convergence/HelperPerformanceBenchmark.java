package dev.convergence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** Only registered by tools/benchmark_helpers.py in its disposable test-mod copy. */
public class HelperPerformanceBenchmark {
    @GameTest(structure="convergence_tests:combat_arena",maxTicks=7000)
    public void nativeLifecycleWorkAndTimings(GameTestHelper c) {
        var squad=new HelperPerformanceGameTests.Squad(c,"helper-benchmark");
        int warmup=Integer.getInteger("infinity.helper-performance.warmup",20);
        int samples=Integer.getInteger("infinity.helper-performance.samples",100);
        if(warmup<1 || warmup>100 || samples<1 || warmup+samples>300){squad.close();throw new IllegalArgumentException("Unbounded benchmark configuration");}
        var rows=new ArrayList<Object>();var cases=List.of(0,1,4,128);int[] phase={0},batch={0};
        squad.hostiles(cases.getFirst());
        HelperPerformanceProbe.lifecycle=work->{
            try {
                if(batch[0]++>=warmup) {
                    int count=cases.get(phase[0]);
                    c.assertValueEqual(work.controls,6L,"Every sampled lifecycle batch runs all six registered helpers");
                    c.assertValueEqual(work.queries,6L,"All helpers use their native hostile query");
                    c.assertValueEqual(work.candidates,count*6L,"Each sampled helper sees the entire eligible fixture population");
                    rows.add(java.util.Map.of("profile","REGULAR","hostiles",count,"sample",batch[0]-warmup-1,"work",work));
                }
                // Actual golem/native movement runs between control batches; maintain only the
                // no-AI hostile population's health, not golem positions, target or navigation.
                squad.resetHostiles();
                if(batch[0]>=warmup+samples) {
                    phase[0]++;batch[0]=0;
                    if(phase[0]==cases.size()) {
                        Path output=Path.of(System.getProperty("infinity.helper-performance.output"));
                        Files.writeString(output,CommunityServer.GSON.toJson(rows)+"\n");squad.close();
                        // Completion inside GameTestInfo.tick notifies its XML reporter;
                        // succeeding directly from END_SERVER_TICK skips those listeners.
                        c.runAfterDelay(1,c::succeed);
                    } else {squad.hostiles(cases.get(phase[0]));squad.resetHelpers();}
                }
            } catch(Exception|Error failure){squad.close();throw new RuntimeException(failure);}
        };
    }
}
