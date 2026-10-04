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
        for (long key : dev.xyat.realmcontrol.util.Nbt.longArray(rewrite, "fixed")) require(restored.isFixedProcessed(key), "fixed marker " + key);
        for (long key : dev.xyat.realmcontrol.util.Nbt.longArray(rewrite, "weighted")) require(restored.isWeightedProcessed(key), "weighted marker " + key);
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
        dev.xyat.realmcontrol.util.Nbt.putUuid(offline, "UUID", new java.util.UUID(123, 456));
        offline.putLong("Time", 1720000000123L);
        ListTag times = new ListTag();
        times.add(offline);
        state.put("OfflineTimes", times);
        require(state.equals(save(BeaconStateManager.load(state))), "beacon owner/offline/reference data round trip");
        System.out.println("SavedDataRegression: three legacy schemas and chunk boundaries passed");
    }

    // 26.1 saved data serializes through each type's codec, so every class keeps its own save.
    //? if >=26.1 {
    /*private static CompoundTag save(WorldChunkLoaderManager data) {
        return data.save(new CompoundTag());
    }

    private static CompoundTag save(LoadedChunkRewriteSavedData data) {
        return data.save(new CompoundTag());
    }

    private static CompoundTag save(BeaconStateManager data) {
        return data.save(new CompoundTag());
    }
    *///?} else if >=1.21 {
    /*private static CompoundTag save(SavedData data) {
        return data.save(new CompoundTag(), net.minecraft.core.RegistryAccess.EMPTY);
    }
    *///?} else {
    private static CompoundTag save(SavedData data) {
        return data.save(new CompoundTag());
    }
    //?}

    public static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
