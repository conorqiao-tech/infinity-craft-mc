package dev.convergence;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.component.CustomData;

/** Native test-mod observers, scoped to a player or one immutable custom-data component. */
public final class MenuTrackingPerformanceProbe {
    public static final class PickerWork { public int scans, slotReads; }
    public static final class PickerSample {
        final Map<Integer,PickerWork> ticks = new HashMap<>();
        PickerWork at(int tick) { return ticks.getOrDefault(tick,new PickerWork()); }
    }
    public static final class TagSample { public int copies; }
    private static final Map<UUID,PickerSample> PICKERS = new HashMap<>();
    private static final Map<CustomData,TagSample> TAGS = new IdentityHashMap<>();
    static PickerSample beginPicker(ServerPlayer player) {
        var sample = new PickerSample();
        if(PICKERS.putIfAbsent(player.getUUID(),sample)!=null)throw new IllegalStateException("Nested picker observation");
        return sample;
    }
    static void endPicker(ServerPlayer player) { PICKERS.remove(player.getUUID()); }
    private static PickerWork pickerWork(ServerPlayer player) {
        var sample = PICKERS.get(player.getUUID());
        return sample==null?null:sample.ticks.computeIfAbsent(player.level().getServer().getTickCount(),unused->new PickerWork());
    }
    public static void scan(ServerPlayer player) { var work=pickerWork(player);if(work!=null)work.scans++; }
    public static void slot(ServerPlayer player) { var work=pickerWork(player);if(work!=null)work.slotReads++; }
    static TagSample beginTag(CustomData data) {
        var sample = new TagSample();
        if(TAGS.putIfAbsent(data,sample)!=null)throw new IllegalStateException("Nested custom-data observation");
        return sample;
    }
    static void endTag(CustomData data) { TAGS.remove(data); }
    public static void copy(CustomData data) { var sample=TAGS.get(data);if(sample!=null)sample.copies++; }
    private MenuTrackingPerformanceProbe() {}
}
