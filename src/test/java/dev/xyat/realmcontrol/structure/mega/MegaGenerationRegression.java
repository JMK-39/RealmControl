
package dev.xyat.realmcontrol.structure.mega;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compares bounded lookups against the former full scans over the same legacy layouts. */
public final class MegaGenerationRegression {
    private static final int GROUP_CHUNKS = 16;
    private static final int[][] SIZES = {{161, 193}, {256, 257}, {999, 529}, {1024, 1024}, {4096, 4096}, {4047, 2081}};

    private MegaGenerationRegression() {
    }

    public static void main(String[] args) {
        verifyBoundedGroupLookup();
        verifyGroupTileBounds();
        verifyFoundationBounds();
        verifyCoverage();
        System.out.println("REALM_MEGA_GENERATION_REGRESSION_PASS");
    }

    private static void verifyBoundedGroupLookup() {
        for (int[] size : SIZES) {
            var layout = new MegaLayout(size[0], 6, size[1]);
            for (Rotation rotation : Rotation.values()) {
                Set<Long> seen = new HashSet<>();
                for (int[] group : layout.groups()) {
                    var rotated = MegaLayout.groupOffset(rotation, group[0], group[1]);
                    var actual = layout.groupAtOffset(rotated.getX(), rotated.getZ(), rotation);
                    check(actual != null && actual.getX() == group[0] && actual.getZ() == group[1],
                            "group moved for " + Arrays.toString(size) + " " + rotation + " " + rotated);
                    seen.add(key(rotated.getX(), rotated.getZ()));
                    check(layout.groupAtOffset(rotated.getX() + 1, rotated.getZ(), rotation) == null,
                            "chunk between starts matched");
                }
                int reach = layout.maxGroupOffset() + GROUP_CHUNKS;
                for (int x = -reach; x <= reach; x += GROUP_CHUNKS) {
                    for (int z = -reach; z <= reach; z += GROUP_CHUNKS) {
                        check((layout.groupAtOffset(x, z, rotation) != null) == seen.contains(key(x, z)),
                                "group bounds missed a valid start or added an invalid start");
                    }
                }
            }
        }
    }

    private static void verifyGroupTileBounds() {
        for (int[] size : SIZES) {
            var layout = new MegaLayout(size[0], 6, size[1]);
            for (int[] group : layout.groups()) {
                var bounds = layout.tilesInGroup(group[0], group[1]);
                check(bounds.endX() - bounds.minX() <= 8 && bounds.endZ() - bounds.minZ() <= 8,
                        "a group may only inspect its section of tiles");
                for (int z = 0; z < layout.tilesZ(); z++) {
                    for (int x = 0; x < layout.tilesX(); x++) {
                        boolean expected = layout.groupX(x) == group[0] && layout.groupZ(z) == group[1];
                        if (inside(bounds, x, z) != expected) {
                            throw new AssertionError("tile group mismatch at " + x + "," + z);
                        }
                    }
                }
            }
        }
    }

    private static void verifyFoundationBounds() {
        for (Rotation rotation : Rotation.values()) {
            for (BlockPos origin : List.of(new BlockPos(0, 65, 0), new BlockPos(-33, 65, 15), new BlockPos(63, 65, -54))) {
                for (int[] size : new int[][]{{32,32}, {32,7}, {3,31}}) {
                    for (int cx = -6; cx < 6; cx++) {
                        for (int cz = -6; cz < 6; cz++) {
                            var box = new BoundingBox(cx << 4, -64, cz << 4, (cx << 4)+15, 320, (cz << 4)+15);
                            var bounds = MegaLayout.columnsInBox(origin, rotation, box, size[0], size[1]);
                            int count = 0;
                            for (int z = 0; z < size[1]; z++) {
                                for (int x = 0; x < size[0]; x++) {
                                    BlockPos column = origin.offset(MegaLayout.turn(new BlockPos(x, 0, z), rotation));
                                    boolean expected = box.isInside(column);
                                    check(inside(bounds, x, z) == expected, "rotated chunk bounds skipped or added a foundation column");
                                    if (expected) count++;
                                }
                            }
                            check(Math.max(0, bounds.endX() - bounds.minX()) * Math.max(0, bounds.endZ() - bounds.minZ()) == count,
                                    "foundation operation scanned columns outside its chunk");
                        }
                    }
                }
            }
        }
    }

    private static void verifyCoverage() {
        for (int[] size : SIZES) {
            var layout = new MegaLayout(size[0], 6, size[1]);
            int reach = layout.anchorReach(64);
            for (Rotation rotation : Rotation.values()) {
                for (int x : new int[]{-layout.anchorX, layout.sizeX - 1 - layout.anchorX}) {
                    for (int z : new int[]{-layout.anchorZ, layout.sizeZ - 1 - layout.anchorZ}) {
                        int worldX = switch (rotation) {
                            case NONE -> x;
                            case CLOCKWISE_90 -> -z;
                            case CLOCKWISE_180 -> -x;
                            case COUNTERCLOCKWISE_90 -> z;
                        };
                        int worldZ = switch (rotation) {
                            case NONE -> z;
                            case CLOCKWISE_90 -> x;
                            case CLOCKWISE_180 -> -z;
                            case COUNTERCLOCKWISE_90 -> -x;
                        };
                        check(((worldX - 64) >> 4) >= -reach && ((worldX + 64) >> 4) <= reach
                                && ((worldZ - 64) >> 4) >= -reach && ((worldZ + 64) >> 4) <= reach,
                                "coverage pruning lost the edge of an old footprint");
                    }
                }
            }
            check(reach < (Math.max(size[0], size[1]) + 64) / 16 + 2,
                    "coverage search still spans the whole footprint diameter");
        }
    }

    private static boolean inside(MegaLayout.Bounds bounds, int x, int z) {
        return x >= bounds.minX() && x < bounds.endX() && z >= bounds.minZ() && z < bounds.endZ();
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
