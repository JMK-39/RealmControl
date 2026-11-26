
package dev.xyat.realmcontrol.structure.publish;

import dev.xyat.realmcontrol.structure.selection.Selection;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/** The exact inclusive destination bounds, checked before any chunks are requested. */
record PlacementVolume(BlockPos min, BlockPos max, long cells) {
    private static final int HORIZONTAL_LIMIT = 30_000_000;

    @Nullable
    static PlacementVolume at(BlockPos min, int[] size) {
        if (size.length != 3) return null;
        int x = size[0], y = size[1], z = size[2];
        if (x < 1 || x > Selection.MAX_FOOTPRINT || y < 1 || z < 1 || z > Selection.MAX_FOOTPRINT) return null;
        long maxX = (long) min.getX() + x - 1;
        long maxY = (long) min.getY() + y - 1;
        long maxZ = (long) min.getZ() + z - 1;
        if (min.getX() < -HORIZONTAL_LIMIT || maxX >= HORIZONTAL_LIMIT
                || min.getZ() < -HORIZONTAL_LIMIT || maxZ >= HORIZONTAL_LIMIT || maxY > Integer.MAX_VALUE) return null;
        return new PlacementVolume(min.immutable(), new BlockPos((int) maxX, (int) maxY, (int) maxZ), (long) x * y * z);
    }
}
