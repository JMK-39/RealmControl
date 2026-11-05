package dev.xyat.realmcontrol.structure.mega;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/**
 * Keeps other structures from starting inside a mega structure that asks for it: while vanilla decides the
 * structure starts of a chunk, the chunk is checked once against the mega instances around it (their footprint and
 * 64 blocks more, so a village starting just outside does not reach in), and then every other structure's start
 * there is skipped.
 */
public final class MegaCoverage {
    private static final int MARGIN = 64;
    private static final ThreadLocal<Boolean> COVERED = new ThreadLocal<>();

    private MegaCoverage() {
    }

    /** Before vanilla decides the chunk's structure starts. */
    public static void begin(RegistryAccess access, ChunkGeneratorStructureState state, ChunkGenerator generator, ChunkAccess chunk) {
        COVERED.set(covered(access, state, generator, chunk));
    }

    public static void end() {
        COVERED.remove();
    }

    /** Whether vanilla should not start this structure in the current chunk. */
    public static boolean skip(Structure structure) {
        return !(structure instanceof MegaStructure) && Boolean.TRUE.equals(COVERED.get());
    }

    private static boolean covered(RegistryAccess access, ChunkGeneratorStructureState state, ChunkGenerator generator, ChunkAccess chunk) {
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            if (!(set.value().placement() instanceof MegaPlacement)) continue;
            for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
                if (entry.structure().value() instanceof MegaStructure mega && mega.suppressOthers
                        && mega.covers(access, state, generator, chunk, chunk.getPos(), MARGIN)) {
                    return true;
                }
            }
        }
        return false;
    }
}
