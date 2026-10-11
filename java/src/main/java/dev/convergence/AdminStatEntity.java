package dev.convergence;

import net.minecraft.nbt.NbtCompound;

/** Undo metadata saved alongside an AI helper's vanilla attributes in its entity chunk. */
public interface AdminStatEntity {
    NbtCompound infinity$adminStats();
    void infinity$adminStats(NbtCompound data);
}
