package dev.convergence;
import net.minecraft.nbt.CompoundTag;
/** Saved in the same atomic player file as the active inventory. */
public interface ModePlayer {
    CompoundTag infinity$state();
    void infinity$state(CompoundTag state);
}
