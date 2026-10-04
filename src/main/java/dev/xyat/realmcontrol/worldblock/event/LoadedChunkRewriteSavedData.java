package dev.xyat.realmcontrol.worldblock.event;

import javax.annotation.Nonnull;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import dev.xyat.realmcontrol.util.Nbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

public final class LoadedChunkRewriteSavedData extends SavedData {
    private static final String DATA_NAME = "realmcontrol_loaded_chunk_rewrite";
    private final LongOpenHashSet fixedProcessed = new LongOpenHashSet();
    private final LongOpenHashSet weightedProcessed = new LongOpenHashSet();

    public static LoadedChunkRewriteSavedData get(ServerLevel level) {
        //? if >=26.1 {
        /*return level.getDataStorage().computeIfAbsent(new net.minecraft.world.level.saveddata.SavedDataType<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("realmcontrol", DATA_NAME), LoadedChunkRewriteSavedData::new,
                CompoundTag.CODEC.xmap(LoadedChunkRewriteSavedData::load, data -> data.save(new CompoundTag()))));
        *///?} else if >=1.21 {
        /*return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(LoadedChunkRewriteSavedData::new,
                (tag, registries) -> load(tag)), DATA_NAME);
        *///?} else {
        return level.getDataStorage().computeIfAbsent(
                LoadedChunkRewriteSavedData::load,
                LoadedChunkRewriteSavedData::new,
                DATA_NAME
        );
        //?}
    }

    public static LoadedChunkRewriteSavedData load(CompoundTag tag) {
        LoadedChunkRewriteSavedData data = new LoadedChunkRewriteSavedData();
        for (long chunkKey : Nbt.longArray(tag, "fixed")) data.fixedProcessed.add(chunkKey);
        for (long chunkKey : Nbt.longArray(tag, "weighted")) data.weightedProcessed.add(chunkKey);
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

    // 26.1 saves through the codec above, so save is a plain method there.
    //? if <26.1
    @Override
    //? if >=1.21 <26.1 {
    /*public CompoundTag save(@Nonnull CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
    *///?} else {
    public CompoundTag save(@Nonnull CompoundTag tag) {
    //?}
        tag.putLongArray("fixed", fixedProcessed.toLongArray());
        tag.putLongArray("weighted", weightedProcessed.toLongArray());
        return tag;
    }
}
