package dev.convergence;
import net.minecraft.nbt.NbtCompound;
/** Saved in the same atomic player file as the active inventory. */
public interface ModePlayer {
    NbtCompound infinity$state();
    void infinity$state(NbtCompound state);
}
