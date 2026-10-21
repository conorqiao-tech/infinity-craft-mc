package dev.convergence;

import java.util.HashMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Temporary native metadata changes confined to disposable GameTest worlds. */
final class NativeStructureFixture {
    private NativeStructureFixture() {}

    /** A missing start is an absent map entry, never a null value for native save/worldgen. */
    static void replaceStart(ChunkAccess chunk, Structure structure, StructureStart start) {
        if (start != null) {
            chunk.setStartForStructure(structure, start);
            return;
        }
        var starts = new HashMap<>(chunk.getAllStarts());
        starts.remove(structure);
        chunk.setAllStarts(starts);
    }
}
