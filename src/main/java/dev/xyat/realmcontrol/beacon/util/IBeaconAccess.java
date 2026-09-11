package dev.xyat.realmcontrol.beacon.util;

import dev.xyat.realmcontrol.beacon.config.BeaconConfig;

import java.util.UUID;

public interface IBeaconAccess {
    boolean realmcontrol_beacon$isChunkLoadEnabled();
    void realmcontrol_beacon$setChunkLoadEnabled(boolean enabled);

    int realmcontrol_beacon$getChunkLoadRadius();
    void realmcontrol_beacon$setChunkLoadRadius(int radius);

    boolean realmcontrol_beacon$isSpawnPreventEnabled();
    void realmcontrol_beacon$setSpawnPreventEnabled(boolean enabled);

    int realmcontrol_beacon$getSpawnPreventRadius();
    void realmcontrol_beacon$setSpawnPreventRadius(int radius);

    int realmcontrol_beacon$getSpawnPreventType();
    void realmcontrol_beacon$setSpawnPreventType(int type);

    String realmcontrol_beacon$getSpawnPreventCodes();
    void realmcontrol_beacon$setSpawnPreventCodes(String codes);

    UUID realmcontrol_beacon$getOwner();
    void realmcontrol_beacon$setOwner(UUID uuid);

    boolean realmcontrol_beacon$getWasOffline();
    void realmcontrol_beacon$setWasOffline(boolean wasOffline);

    boolean realmcontrol_beacon$checkOffline();

    default int realmcontrol_beacon$getActualChunkLoadRadius(int level, int maxRadius) {
        if (!realmcontrol_beacon$isChunkLoadEnabled() || level <= 0 || maxRadius < 0) return -1;
        if (BeaconConfig.offlineDisableChunkLoad && realmcontrol_beacon$checkOffline()) return -1;
        int rad = realmcontrol_beacon$getChunkLoadRadius();
        return (rad < 0 || rad > maxRadius) ? maxRadius : rad;
    }

    default int realmcontrol_beacon$getActualSpawnPreventRadius(int level, int maxRadius) {
        if (!realmcontrol_beacon$isSpawnPreventEnabled() || level <= 0 || maxRadius < 0) return -1;
        if (BeaconConfig.offlineDisableSpawnPrevent && realmcontrol_beacon$checkOffline()) return -1;
        int rad = realmcontrol_beacon$getSpawnPreventRadius();
        return (rad < 0 || rad > maxRadius) ? maxRadius : rad;
    }
}
