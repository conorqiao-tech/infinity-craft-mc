package dev.convergence;

/** Test-mod-only counters for copy sites in the actual packet projection method. */
public final class ClientVitalsPerformanceProbe {
    public static final class Sample {
        public int lists, attributeCopies, metadataValues;
    }
    private static final ThreadLocal<Sample> ACTIVE = new ThreadLocal<>();
    static Sample begin() {
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested vitals observation");
        var sample = new Sample();
        ACTIVE.set(sample);
        return sample;
    }
    static void end() { ACTIVE.remove(); }
    public static void list() { var sample = ACTIVE.get(); if (sample != null) sample.lists++; }
    public static void attributes() { var sample = ACTIVE.get(); if (sample != null) sample.attributeCopies++; }
    public static void metadata() { var sample = ACTIVE.get(); if (sample != null) sample.metadataValues++; }
    private ClientVitalsPerformanceProbe() {}
}
