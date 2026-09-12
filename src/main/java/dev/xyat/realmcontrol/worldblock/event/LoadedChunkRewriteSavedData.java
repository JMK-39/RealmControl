package dev.xyat.realmcontrol.worldblock.event;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class LoadedChunkRewriteSavedData extends SavedData {
    private static final String DATA_NAME = "realmcontrol_loaded_chunk_rewrite";
    private final LongOpenHashSet fixedProcessed = new LongOpenHashSet();
    private final LongOpenHashSet weightedProcessed = new LongOpenHashSet();

    public static LoadedChunkRewriteSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                LoadedChunkRewriteSavedData::load,
                LoadedChunkRewriteSavedData::new,
                DATA_NAME
        );
    }

    public static LoadedChunkRewriteSavedData load(CompoundTag tag) {
        LoadedChunkRewriteSavedData data = new LoadedChunkRewriteSavedData();
        for (long chunkKey : tag.getLongArray("fixed")) data.fixedProcessed.add(chunkKey);
        for (long chunkKey : tag.getLongArray("weighted")) data.weightedProcessed.add(chunkKey);
        return data;
    }

    public boolean isFixedProcessed(long chunkKey) {
        return fixedProcessed.contains(chunkKey);
    }

    public void markFixedProcessed(long chunkKey) {
        if (fixedProcessed.add(chunkKey)) setDirty();
    }

    public boolean isWeightedProcessed(long chunkKey) {
        return weightedProcessed.contains(chunkKey);
    }

    public void markWeightedProcessed(long chunkKey) {
        if (weightedProcessed.add(chunkKey)) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLongArray("fixed", fixedProcessed.toLongArray());
        tag.putLongArray("weighted", weightedProcessed.toLongArray());
        return tag;
    }
}
