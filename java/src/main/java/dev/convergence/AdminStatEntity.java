package dev.convergence;

import net.minecraft.nbt.CompoundTag;

/** Undo metadata saved alongside an AI helper's vanilla attributes in its entity chunk. */
public interface AdminStatEntity {
    CompoundTag infinity$adminStats();
    void infinity$adminStats(CompoundTag data);
}
