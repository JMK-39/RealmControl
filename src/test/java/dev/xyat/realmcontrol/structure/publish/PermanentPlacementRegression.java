
package dev.xyat.realmcontrol.structure.publish;

import net.minecraft.core.BlockPos;

/** Regression for operator-selected bounds and large coordinates used by direct placement. */
public final class PermanentPlacementRegression {
    private PermanentPlacementRegression() {
    }

    public static void main(String[] args) {
        verifyBounds();
        System.out.println("REALM_PERMANENT_PLACEMENT_REGRESSION_PASS");
    }

    private static void verifyBounds() {
        // A full 4096-footprint placement is wider than the old test-place limit and its volume needs a long.
        var giant = PlacementVolume.at(new BlockPos(-17, 60, -33), new int[]{4096, 257, 4096});
        check(giant != null, "the direct route rejected a giant structure");
        check(giant.max().equals(new BlockPos(4078, 316, 4062)), "the far inclusive corner moved");
        check(giant.cells() == 4_311_744_512L, "giant volume overflowed");

        var one = PlacementVolume.at(new BlockPos(-30_000_000, -64, 29_999_999), new int[]{1, 1, 1});
        check(one != null && one.max().equals(one.min()), "single block or world-edge coordinates were lost");
        check(PlacementVolume.at(new BlockPos(29_999_999, 64, 0), new int[]{2, 1, 1}) == null, "placement crosses the world edge");
        check(PlacementVolume.at(new BlockPos(-30_000_001, 64, 0), new int[]{1, 1, 1}) == null, "placement starts beyond the world edge");
        check(PlacementVolume.at(new BlockPos(0, Integer.MAX_VALUE, 0), new int[]{1, 2, 1}) == null, "height overflowed");
        check(PlacementVolume.at(BlockPos.ZERO, new int[]{4097, 1, 1}) == null, "oversize draft accepted");
        check(PlacementVolume.at(BlockPos.ZERO, new int[]{1, 0, 1}) == null, "empty dimension accepted");
        check(PlacementVolume.at(BlockPos.ZERO, new int[]{1, 1}) == null, "malformed dimensions accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
