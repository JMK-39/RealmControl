package dev.xyat.realmcontrol.teleport.api;

public interface ITeleportAuth {
    int realmcontrol_tpd$getTpCount();

    void realmcontrol_tpd$setTpCount(int count);

    long realmcontrol_tpd$getTpExpiry();

    void realmcontrol_tpd$setTpExpiry(long timestamp);

    default boolean hasTpAuth() {
        return realmcontrol_tpd$getTpCount() > 0 || System.currentTimeMillis() < realmcontrol_tpd$getTpExpiry();
    }

    default void consumeTpAuth() {
        if (System.currentTimeMillis() < realmcontrol_tpd$getTpExpiry()) return;
        if (realmcontrol_tpd$getTpCount() > 0) {
            realmcontrol_tpd$setTpCount(realmcontrol_tpd$getTpCount() - 1);
        }
    }
}
