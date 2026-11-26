package dev.xyat.realmcontrol.structure.mega;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the parts of one mega structure instance go. The instance is centred on the corner of its anchor chunk (the
 * chunk vanilla's random spread picks): the structure's anchor cell, the multiple of 32 nearest its middle, lands
 * there and the structure turns about it. Its 32×32 tiles are split into groups 256 blocks wide around the anchor;
 * each group starts in the chunk at its middle, so every block of the group is at most 8 chunks from its start, as
 * far as vanilla looks for structure starts. The middle group starts in the anchor chunk itself, where /locate looks.
 */
public final class MegaLayout {
    public static final int TILE = 32, GROUP = 256;
    static final int MAX_DIMENSION = 4096;
    private static final int GROUP_CHUNKS = GROUP / 16;
    public final int sizeX, sizeY, sizeZ, anchorX, anchorZ;

    record Bounds(int minX, int minZ, int endX, int endZ) {
    }

    public MegaLayout(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.anchorX = anchorOf(this.sizeX);
        this.anchorZ = anchorOf(this.sizeZ);
    }

    /** The multiple of 32 nearest the middle, so group edges fall on tile edges. */
    static int anchorOf(int size) {
        return Math.min(size, (size / 2 + TILE / 2) / TILE * TILE);
    }

    public int tilesX() {
        return (sizeX + TILE - 1) / TILE;
    }

    public int tilesZ() {
        return (sizeZ + TILE - 1) / TILE;
    }

    /** The group a tile belongs to along one axis. */
    public int groupX(int tileX) {
        return Math.floorDiv(tileX * TILE - (anchorX - GROUP / 2), GROUP);
    }

    public int groupZ(int tileZ) {
        return Math.floorDiv(tileZ * TILE - (anchorZ - GROUP / 2), GROUP);
    }

    /** The groups along x, from the first to the last that holds any of the structure. */
    public int minGroupX() {
        return groupX(0);
    }

    public int maxGroupX() {
        return groupX(tilesX() - 1);
    }

    public int minGroupZ() {
        return groupZ(0);
    }

    public int maxGroupZ() {
        return groupZ(tilesZ() - 1);
    }

    /** How far, in chunks along either axis, a group start can be from the anchor chunk. */
    public int maxGroupOffset() {
        return GROUP_CHUNKS * Math.max(Math.max(-minGroupX(), maxGroupX()), Math.max(-minGroupZ(), maxGroupZ()));
    }

    /** The start chunk of group (k, l) relative to the anchor chunk, turned with the structure. */
    public static BlockPos groupOffset(Rotation rotation, int k, int l) {
        return turn(new BlockPos(GROUP_CHUNKS * k, 0, GROUP_CHUNKS * l), rotation);
    }

    /** The group at an offset from the anchor, or no group if the chunk is between starts or outside the layout. */
    @Nullable
    BlockPos groupAtOffset(int x, int z, Rotation rotation) {
        if (x % GROUP_CHUNKS != 0 || z % GROUP_CHUNKS != 0) return null;
        BlockPos group = turn(new BlockPos(x / GROUP_CHUNKS, 0, z / GROUP_CHUNKS), inverse(rotation));
        int gx = group.getX(), gz = group.getZ();
        return gx < minGroupX() || gx > maxGroupX() || gz < minGroupZ() || gz > maxGroupZ() ? null : group;
    }

    Bounds tilesInGroup(int gx, int gz) {
        int firstX = (anchorX - GROUP / 2 + gx * GROUP) / TILE;
        int firstZ = (anchorZ - GROUP / 2 + gz * GROUP) / TILE;
        return new Bounds(Math.max(0, firstX), Math.max(0, firstZ), Math.min(tilesX(), firstX + GROUP / TILE),
                Math.min(tilesZ(), firstZ + GROUP / TILE));
    }

    /** Farthest chunk coordinate reached by a rotated footprint and its margin, relative to its anchor. */
    int anchorReach(int margin) {
        int x = Math.max(anchorX, sizeX - 1 - anchorX);
        int z = Math.max(anchorZ, sizeZ - 1 - anchorZ);
        return (Math.max(x, z) + margin + 15) / 16;
    }

    /** Converts a horizontal world clip to the exact interval of tile-local columns it intersects. */
    static Bounds columnsInBox(BlockPos origin, Rotation rotation, BoundingBox box, int sizeX, int sizeZ) {
        Rotation undo = inverse(rotation);
        BlockPos first = turn(new BlockPos(box.minX() - origin.getX(), 0, box.minZ() - origin.getZ()), undo);
        BlockPos last = turn(new BlockPos(box.maxX() - origin.getX(), 0, box.maxZ() - origin.getZ()), undo);
        return new Bounds(Math.max(0, Math.min(first.getX(), last.getX())), Math.max(0, Math.min(first.getZ(), last.getZ())),
                Math.min(sizeX, Math.max(first.getX(), last.getX()) + 1), Math.min(sizeZ, Math.max(first.getZ(), last.getZ()) + 1));
    }

    /** The groups of the structure as {k, l} pairs. */
    public List<int[]> groups() {
        List<int[]> groups = new ArrayList<>();
        for (int k = minGroupX(); k <= maxGroupX(); k++) {
            for (int l = minGroupZ(); l <= maxGroupZ(); l++) groups.add(new int[]{k, l});
        }
        return groups;
    }

    /** The structure's turn for the instance at an anchor chunk: random per instance, as vanilla turns its pieces. */
    public static Rotation rotation(long seed, int anchorX, int anchorZ) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, anchorX, anchorZ);
        return Rotation.getRandom(random);
    }

    /** The world position of a cell of the structure, for an instance at {@code anchor} with its bottom at {@code bottom}. */
    public BlockPos world(ChunkPos anchor, int bottom, Rotation rotation, int x, int y, int z) {
        return turn(new BlockPos(x - anchorX, y, z - anchorZ), rotation).offset(anchor.getMinBlockX(), bottom, anchor.getMinBlockZ());
    }

    /** Converts a cell of an already generated instance back to draft coordinates. */
    public BlockPos local(BlockPos worldAnchor, Rotation rotation, BlockPos world) {
        return turn(world.subtract(worldAnchor), inverse(rotation)).offset(anchorX, 0, anchorZ);
    }

    /** The blocks the instance covers. */
    public BoundingBox footprint(ChunkPos anchor, int bottom, Rotation rotation) {
        return BoundingBox.fromCorners(world(anchor, bottom, rotation, 0, 0, 0), world(anchor, bottom, rotation, sizeX - 1, sizeY - 1, sizeZ - 1));
    }

    /** The blocks a tile covers once placed. */
    public BoundingBox tileBox(ChunkPos anchor, int bottom, Rotation rotation, int tileX, int tileZ) {
        int x = tileX * TILE, z = tileZ * TILE;
        return BoundingBox.fromCorners(world(anchor, bottom, rotation, x, 0, z),
                world(anchor, bottom, rotation, Math.min(sizeX, x + TILE) - 1, sizeY - 1, Math.min(sizeZ, z + TILE) - 1));
    }

    /** A chunk's x and z; written once here because 26.1 made the chunk position a record. */
    public static int chunkX(ChunkPos chunkPos) {
        return chunkPos.x;
    }

    public static int chunkZ(ChunkPos chunkPos) {
        return chunkPos.z;
    }

    static BlockPos turn(BlockPos pos, Rotation rotation) {
        return StructureTemplate.transform(pos, Mirror.NONE, rotation, BlockPos.ZERO);
    }

    private static Rotation inverse(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
    }
}
