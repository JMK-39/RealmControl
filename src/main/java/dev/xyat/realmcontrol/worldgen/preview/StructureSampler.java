package dev.xyat.realmcontrol.worldgen.preview;

import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Generates one sample of a structure for its preview: the structure's own generation picks a layout with a fixed
 * seed, then every piece places itself chunk by chunk into a {@link CaptureLevel}, the way world generation places
 * it. Runs off the server thread; nothing touches the real world.
 */
final class StructureSampler {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Chunk positions tried until the structure finds a valid layout (some need water, flat ground and so on). */
    private static final int ATTEMPTS = 16;

    /** A finished sample. {@code missing} lists template blocks this game does not know, with how often each occurs. */
    record Sample(KineticBlockSnapshot snapshot, Map<String, Integer> missing, int pieces, int sizeX, int sizeY,
                  int sizeZ, int failedChunks) {
    }

    private StructureSampler() {
    }

    /**
     * Samples a structure, reporting progress from 0 to 1. Returns {@code null} if no valid layout was found or
     * {@code cancelled} turned true.
     */
    @Nullable
    static Sample sample(ServerLevel level, Holder.Reference<Structure> structure, int variant, Consumer<Float> progress,
                         BooleanSupplier cancelled) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        long seed = 0x5EED_0000L + variant * 7919L;
        StructureStart start = null;
        for (int attempt = 0; attempt < ATTEMPTS && (start == null || !start.isValid()); attempt++) {
            if (cancelled.getAsBoolean()) return null;
            ChunkPos origin = new ChunkPos(1000 + attempt * 37 + variant * 211, 1000 - attempt * 29 + variant * 97);
            start = generate(level, structure, generator, randomState, seed, origin);
        }
        if (start == null || !start.isValid()) return null;

        BoundingBox box = start.getBoundingBox();
        CaptureLevel capture = new CaptureLevel(level, generator, randomState, seed);
        int fromX = box.minX() >> 4, toX = box.maxX() >> 4, fromZ = box.minZ() >> 4, toZ = box.maxZ() >> 4;
        int total = (toX - fromX + 1) * (toZ - fromZ + 1), done = 0, failed = 0;
        Map<String, Integer> missing;
        TemplateMissingTracker.startRecording();
        try {
            for (int cx = fromX; cx <= toX; cx++) {
                for (int cz = fromZ; cz <= toZ; cz++) {
                    if (cancelled.getAsBoolean()) return null;
                    ChunkPos chunk = new ChunkPos(cx, cz);
                    BoundingBox chunkBox = new BoundingBox(chunk.getMinBlockX(), level.getMinBuildHeight(), chunk.getMinBlockZ(),
                            chunk.getMaxBlockX(), level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ());
                    try {
                        start.placeInChunk(capture.level(), level.structureManager(), generator,
                                RandomSource.create(seed ^ ((long) cx << 32) ^ (cz & 0xFFFFFFFFL)), chunkBox, chunk);
                    } catch (RuntimeException | LinkageError e) {
                        // A piece that needs something the stand-in world does not have is left out of the preview.
                        if (failed++ == 0) LOGGER.warn("Structure preview of {}: a piece could not be placed in chunk {}", structure.key(), chunk, e);
                    }
                    progress.accept(++done / (float) total);
                }
            }
        } finally {
            missing = TemplateMissingTracker.stopRecording();
        }
        KineticBlockSnapshot snapshot = snapshot(capture);
        // Pieces that found nowhere to go in the stand-in terrain (a buried chest needs stone) leave nothing to show.
        if (snapshot.nonAirCount() == 0) return null;
        return new Sample(snapshot, missing, start.getPieces().size(), box.getXSpan(), box.getYSpan(),
                box.getZSpan(), failed);
    }

    @SuppressWarnings("deprecation")
    private static StructureStart generate(ServerLevel level, Holder.Reference<Structure> structure, ChunkGenerator generator,
                                           RandomState randomState, long seed, ChunkPos origin) {
        try {
            //? if >=26.1 {
            /*return structure.value().generate(structure, level.dimension(), level.registryAccess(), generator,
                    generator.getBiomeSource(), randomState, level.getStructureManager(), seed, origin, 0, level, biome -> true);
            *///?} else {
            return structure.value().generate(level.registryAccess(), generator, generator.getBiomeSource(), randomState,
                    level.getStructureManager(), seed, origin, 0, level, biome -> true);
            //?}
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The blocks the pieces wrote, without the terrain around them, at full detail when they fit in one snapshot. */
    private static KineticBlockSnapshot snapshot(CaptureLevel capture) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : capture.written().long2ObjectEntrySet()) {
            if (entry.getValue().isAir()) continue;
            pos.set(BlockPos.getX(entry.getLongKey()), BlockPos.getY(entry.getLongKey()), BlockPos.getZ(entry.getLongKey()));
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        if (minX == Integer.MAX_VALUE) return KineticBlockSnapshot.builder(1, 1, 1).build();
        int spanX = maxX - minX + 1, spanY = maxY - minY + 1, spanZ = maxZ - minZ + 1;
        int factor = KineticBlockSnapshot.factorFor(spanX, spanY, spanZ, KineticBlockSnapshot.MAX_VOLUME);
        KineticBlockSnapshot.Builder builder = KineticBlockSnapshot.builder((spanX + factor - 1) / factor,
                (spanY + factor - 1) / factor, (spanZ + factor - 1) / factor).scale(factor);
        for (Long2ObjectMap.Entry<BlockState> entry : capture.written().long2ObjectEntrySet()) {
            BlockState state = entry.getValue();
            if (state.isAir()) continue;
            long key = entry.getLongKey();
            builder.set((BlockPos.getX(key) - minX) / factor, (BlockPos.getY(key) - minY) / factor,
                    (BlockPos.getZ(key) - minZ) / factor, state);
        }
        int containers = 0;
        for (Map.Entry<Long, BlockEntity> entry : capture.blockEntities().entrySet()) {
            if (CaptureLevel.isContainer(entry.getValue())) containers++;
            if (factor == 1) {
                long key = entry.getKey();
                builder.blockEntity(BlockPos.getX(key) - minX, BlockPos.getY(key) - minY, BlockPos.getZ(key) - minZ,
                        CaptureLevel.idTag(entry.getValue()));
            }
        }
        builder.containers(containers);
        for (Entity entity : capture.entities()) {
            builder.entity(entity.getX() - minX, entity.getY() - minY, entity.getZ() - minZ, CaptureLevel.idTag(entity));
        }
        return builder.build();
    }
}
