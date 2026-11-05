package dev.xyat.realmcontrol.structure.mega;

import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

import javax.annotation.Nullable;

/**
 * One 32×32 tile of a mega structure, placed chunk by chunk with vanilla's template placement (turned, limited to
 * the chunk, block entities, entities and waterlogging as vanilla does them). Before it, the ground above each column
 * that holds a block of the tile is cleared up to the surface; after it, columns standing on a block are filled down
 * to the ground with the foundation block, at most 64 blocks. Columns the tile leaves empty, such as cut-outs, keep
 * the world as it is. A piece without a template only marks the middle of an instance whose middle is cut out.
 */
public final class MegaTilePiece extends StructurePiece {
    private static final int MAX_FOUNDATION = 64;
    @Nullable private final ResourceLocation template;
    private final BlockPos position;
    private final Rotation rotation;
    private final String foundation;
    private final String foundationBlock;

    public MegaTilePiece(@Nullable ResourceLocation template, BlockPos position, Rotation rotation, BoundingBox box, String foundation, String foundationBlock) {
        super(MegaTypes.PIECE.get(), 0, box);
        this.template = template;
        this.position = position;
        this.rotation = rotation;
        this.foundation = foundation;
        this.foundationBlock = foundationBlock;
    }

    public MegaTilePiece(CompoundTag tag) {
        super(MegaTypes.PIECE.get(), tag);
        String id = tag.getString("Template");
        this.template = id.isEmpty() ? null : KineticResourceIds.tryParse(id);
        this.position = new BlockPos(tag.getInt("TX"), tag.getInt("TY"), tag.getInt("TZ"));
        Rotation turn = Rotation.NONE;
        String name = tag.getString("Rot");
        for (Rotation candidate : Rotation.values()) if (candidate.name().equals(name)) turn = candidate;
        this.rotation = turn;
        this.foundation = tag.getString("Foundation");
        this.foundationBlock = tag.getString("FoundationBlock");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("Template", template == null ? "" : template.toString());
        tag.putInt("TX", position.getX());
        tag.putInt("TY", position.getY());
        tag.putInt("TZ", position.getZ());
        tag.putString("Rot", rotation.name());
        tag.putString("Foundation", foundation);
        tag.putString("FoundationBlock", foundationBlock);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random, BoundingBox box,
                            ChunkPos chunkPos, BlockPos pivot) {
        if (template == null) return;
        long started = System.nanoTime();
        MegaTemplates.Entry entry = MegaTemplates.get(level.getLevel().getStructureManager(), template);
        if (entry == null) return;
        boolean clear = "fill_clear".equals(foundation), fill = clear || "fill".equals(foundation);
        if (clear) clearAbove(level, box, entry);
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setBoundingBox(box).setKnownShape(true)
                .setIgnoreEntities(false).setFinalizeEntities(true).addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
        entry.template().placeInWorld(level, position, position, settings, random, 2);
        if (fill) fillBelow(level, box, entry);
        MegaStats.record(System.nanoTime() - started);
    }

    /** Clears the ground above each of the tile's columns that hold a block, from just above its top block to the surface. */
    private void clearAbove(WorldGenLevel level, BoundingBox box, MegaTemplates.Entry entry) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < entry.sizeZ(); z++) {
            for (int x = 0; x < entry.sizeX(); x++) {
                int top = entry.top(x, z);
                if (top < 0) continue;
                BlockPos column = position.offset(MegaLayout.turn(new BlockPos(x, 0, z), rotation));
                if (column.getX() < box.minX() || column.getX() > box.maxX() || column.getZ() < box.minZ() || column.getZ() > box.maxZ()) continue;
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, column.getX(), column.getZ());
                for (int y = position.getY() + top + 1; y < surface; y++) {
                    cursor.set(column.getX(), y, column.getZ());
                    BlockState state = level.getBlockState(cursor);
                    // Water and lava stay: emptying a lake would leave a hole.
                    if (!state.isAir() && state.getFluidState().isEmpty()) level.setBlock(cursor, air, 2);
                }
            }
        }
    }

    /** Fills below each of the tile's columns that stands on a block, down to the first solid block of the ground. */
    private void fillBelow(WorldGenLevel level, BoundingBox box, MegaTemplates.Entry entry) {
        BlockState parsed = BlockStates.parse(foundationBlock);
        BlockState block = parsed == null ? Blocks.STONE.defaultBlockState() : parsed;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int z = 0; z < entry.sizeZ(); z++) {
            for (int x = 0; x < entry.sizeX(); x++) {
                if (!entry.standsOnBlock(x, z)) continue;
                BlockPos column = position.offset(MegaLayout.turn(new BlockPos(x, 0, z), rotation));
                if (column.getX() < box.minX() || column.getX() > box.maxX() || column.getZ() < box.minZ() || column.getZ() > box.maxZ()) continue;
                for (int depth = 1; depth <= MAX_FOUNDATION; depth++) {
                    cursor.set(column.getX(), position.getY() - depth, column.getZ());
                    if (level.isOutsideBuildHeight(cursor)) break;
                    BlockState state = level.getBlockState(cursor);
                    if (!state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty()) break;
                    level.setBlock(cursor, block, 2);
                }
            }
        }
    }
}
