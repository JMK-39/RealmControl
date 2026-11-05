package dev.xyat.realmcontrol.structure.mega;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

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
    public final int sizeX, sizeY, sizeZ, anchorX, anchorZ;

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
        return 16 * Math.max(Math.max(-minGroupX(), maxGroupX()), Math.max(-minGroupZ(), maxGroupZ()));
    }

    /** The start chunk of group (k, l) relative to the anchor chunk, turned with the structure. */
    public static BlockPos groupOffset(Rotation rotation, int k, int l) {
        return turn(new BlockPos(16 * k, 0, 16 * l), rotation);
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
}
