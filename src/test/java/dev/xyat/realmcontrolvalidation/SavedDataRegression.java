package dev.xyat.realmcontrolvalidation;

import dev.xyat.realmcontrol.beacon.data.WorldChunkLoaderManager;
import dev.xyat.realmcontrol.beacon.util.BeaconStateManager;
import dev.xyat.realmcontrol.worldblock.event.LoadedChunkRewriteSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;

public final class SavedDataRegression {
    public static void main(String[] args) {
        CompoundTag loaders = new CompoundTag();
        CompoundTag beacon = new CompoundTag();
        beacon.putLong("Pos", new BlockPos(-17, 90, 32).asLong());
        beacon.putInt("Radius", 2);
        ListTag beacons = new ListTag();
        beacons.add(beacon);
        loaders.put("Beacons", beacons);
        WorldChunkLoaderManager loaded = WorldChunkLoaderManager.load(loaders);
        require(loaders.equals(save(loaded)), "beacon radius data round trip");
        require(loaded.isChunkProtected(-5, 5), "negative-coordinate protected boundary");
        require(!loaded.isChunkProtected(-6, 5), "outside protected boundary");

        CompoundTag rewrite = new CompoundTag();
        rewrite.putLongArray("fixed", new long[]{Long.MIN_VALUE, 0, Long.MAX_VALUE});
        rewrite.putLongArray("weighted", new long[]{-1, 4294967296L});
        LoadedChunkRewriteSavedData markers = LoadedChunkRewriteSavedData.load(rewrite);
        LoadedChunkRewriteSavedData restored = LoadedChunkRewriteSavedData.load(save(markers));
        for (long key : rewrite.getLongArray("fixed")) require(restored.isFixedProcessed(key), "fixed marker " + key);
        for (long key : rewrite.getLongArray("weighted")) require(restored.isWeightedProcessed(key), "weighted marker " + key);
        require(!restored.isWeightedProcessed(0), "fixed/weighted markers independent");

        CompoundTag state = new CompoundTag();
        CompoundTag dimension = new CompoundTag();
        dimension.putLongArray("Pos", new long[]{7});
        dimension.putIntArray("Count", new int[]{3});
        CompoundTag global = new CompoundTag();
        global.put("minecraft:overworld", dimension);
        state.put("GlobalRefs", global);
        state.put("PlayerRefs", new ListTag());
        CompoundTag offline = new CompoundTag();
        offline.putUUID("UUID", new java.util.UUID(123, 456));
        offline.putLong("Time", 1720000000123L);
        ListTag times = new ListTag();
        times.add(offline);
        state.put("OfflineTimes", times);
        require(state.equals(save(BeaconStateManager.load(state))), "beacon owner/offline/reference data round trip");
        System.out.println("SavedDataRegression: three legacy schemas and chunk boundaries passed");
    }

    private static CompoundTag save(SavedData data) {
        //? if >=1.21 {
        /*return data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY);
        *///?} else {
        return data.save(new CompoundTag());
        //?}
    }

    public static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
