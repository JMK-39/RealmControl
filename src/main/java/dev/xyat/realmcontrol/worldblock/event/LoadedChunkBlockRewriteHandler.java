package dev.xyat.realmcontrol.worldblock.event;

import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.util.BlockReplacementChunkProcessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import dev.xyat.kineticcore.api.world.event.KineticWorldEvents;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class LoadedChunkBlockRewriteHandler {
    private static final Set<LevelChunk> LOADED_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Set<LevelChunk> QUEUED_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Queue<PendingChunkRewrite> PENDING_CHUNKS = new ConcurrentLinkedQueue<>();

    LoadedChunkBlockRewriteHandler() {
    }

    void onChunkLoad(KineticWorldEvents.ChunkContext event) {
        if (!(event.chunk() instanceof LevelChunk chunk)) return;
        if (!(event.level() instanceof ServerLevel level)) return;

        LOADED_CHUNKS.add(chunk);
        if (areAllRewritesDisabled()) return;

        long chunkKey = chunk.getPos().toLong();
        if (event.newChunk()) {
            LoadedChunkRewriteSavedData markers = LoadedChunkRewriteSavedData.get(level);
            if (isFixedRewriteEnabled()) markers.markFixedProcessed(chunkKey);
            if (isWeightedRewriteEnabled()) markers.markWeightedProcessed(chunkKey);
            return;
        }

        if (needsFixedRewrite(level, chunkKey) || needsWeightedRewrite(level, chunkKey)) {
            queueChunk(level, chunk);
        }
    }

    void onChunkUnload(KineticWorldEvents.ChunkContext event) {
        if (event.chunk() instanceof LevelChunk chunk) {
            LOADED_CHUNKS.remove(chunk);
            QUEUED_CHUNKS.remove(chunk);
        }
    }

    void onServerStopped() {
        LOADED_CHUNKS.clear();
        QUEUED_CHUNKS.clear();
        PENDING_CHUNKS.clear();
    }

    void onServerTick() {
        if (areAllRewritesDisabled()) return;
        PendingChunkRewrite pending = PENDING_CHUNKS.poll();
        if (pending == null) return;
        processPendingChunk(pending);
    }

    private static boolean isFixedRewriteEnabled() {
        return LoadedChunkRewriteBootstrap.fixedEnabledAtStartup()
                && !WorldBlockConfig.worldgenReplacementBlockStates.isEmpty();
    }

    private static boolean isWeightedRewriteEnabled() {
        return LoadedChunkRewriteBootstrap.weightedEnabledAtStartup()
                && !WorldBlockConfig.worldgenWeightedBlockReplacements.isEmpty();
    }

    private static boolean areAllRewritesDisabled() {
        return !isFixedRewriteEnabled() && !isWeightedRewriteEnabled();
    }

    private static void queueChunk(ServerLevel level, LevelChunk chunk) {
        if (!QUEUED_CHUNKS.add(chunk)) return;
        PENDING_CHUNKS.offer(new PendingChunkRewrite(level, chunk));
    }

    private static void processPendingChunk(PendingChunkRewrite pending) {
        ServerLevel level = pending.level();
        LevelChunk chunk = pending.chunk();
        try {
            if (!LOADED_CHUNKS.contains(chunk)) return;
            if (!level.hasChunk(chunk.getPos().x, chunk.getPos().z)) return;

            long chunkKey = chunk.getPos().toLong();
            boolean rewriteFixed = needsFixedRewrite(level, chunkKey);
            boolean rewriteWeighted = needsWeightedRewrite(level, chunkKey);
            if (!rewriteFixed && !rewriteWeighted) return;

            BlockReplacementChunkProcessor.rewriteLoadedTargets(level, chunk, rewriteFixed, rewriteWeighted);
            LoadedChunkRewriteSavedData markers = LoadedChunkRewriteSavedData.get(level);
            if (rewriteFixed) markers.markFixedProcessed(chunkKey);
            if (rewriteWeighted) markers.markWeightedProcessed(chunkKey);
        } finally {
            QUEUED_CHUNKS.remove(chunk);
        }
    }

    private static boolean needsFixedRewrite(ServerLevel level, long chunkKey) {
        return isFixedRewriteEnabled() && !LoadedChunkRewriteSavedData.get(level).isFixedProcessed(chunkKey);
    }

    private static boolean needsWeightedRewrite(ServerLevel level, long chunkKey) {
        return isWeightedRewriteEnabled() && !LoadedChunkRewriteSavedData.get(level).isWeightedProcessed(chunkKey);
    }

    private record PendingChunkRewrite(ServerLevel level, LevelChunk chunk) {
    }
}
