package dev.convergence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Test-mod-only counters. Never bundled in the playable mod. */
public final class HelperPerformanceProbe {
    public static final class Sample {
        public long controls, queries, candidates, predicateVisits, sightCalls, sightRays;
        public long focusLookups, focusPeerVisits, moveRequests, pathSearches;
        public long controlNanos, pathNanos;
        public final List<Long> individualControlNanos = new ArrayList<>();
    }
    public static Sample active;
    public static Consumer<Sample> lifecycle;
    private static long controlStarted, pathStarted;
    private static boolean inControl;
    public static boolean measuringControl() { return active != null && inControl; }
    public static Sample begin() { if(active!=null)throw new IllegalStateException("Nested measurement");return active=new Sample(); }
    public static Sample end() { var result=active;active=null;inControl=false;return result; }
    public static void tickStart(int tick) { if(lifecycle!=null && tick%5==0)begin(); }
    public static void tickEnd() { if(lifecycle!=null && active!=null)lifecycle.accept(end()); }
    public static void controlStart() { if(active!=null){inControl=true;active.controls++;controlStarted=System.nanoTime();} }
    public static void controlEnd() { if(measuringControl()){long elapsed=System.nanoTime()-controlStarted;active.controlNanos+=elapsed;active.individualControlNanos.add(elapsed);inControl=false;} }
    public static void pathStart() { if(measuringControl()){active.pathSearches++;pathStarted=System.nanoTime();} }
    public static void pathEnd() { if(measuringControl())active.pathNanos+=System.nanoTime()-pathStarted; }
    private HelperPerformanceProbe() {}
}
