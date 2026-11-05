package dev.xyat.realmcontrol.structure.mega;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Keeps the vanilla features that overwrite whatever is in their way, lakes and forest boulders, out of mega
 * structures that keep other structures out. Such a feature placed from a neighbouring chunk that is decorated
 * later would otherwise cut into blocks the structure placed earlier, leaving holes of lava or air in its floor.
 * A chunk the structure covers holds a reference to it, so the feature's area is checked against those.
 */
public final class MegaFeatureGuard {
    private MegaFeatureGuard() {
    }

    /** Whether a feature reaching {@code radius} blocks around {@code origin} would touch a guarded mega structure. */
    public static boolean blocked(WorldGenLevel level, BlockPos origin, int radius) {
        for (int cx = (origin.getX() - radius) >> 4; cx <= (origin.getX() + radius) >> 4; cx++) {
            for (int cz = (origin.getZ() - radius) >> 4; cz <= (origin.getZ() + radius) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (Structure structure : level.getChunk(cx, cz).getAllReferences().keySet()) {
                    if (structure instanceof MegaStructure mega && mega.suppressOthers) return true;
                }
            }
        }
        return false;
    }
}
