package dev.xyat.realmcontrol.teleport.mixin;

import dev.xyat.realmcontrol.teleport.api.ITeleportAuth;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PlayerTeleportDataMixin implements ITeleportAuth {
    @Unique private static final String TELEPORT_DATA_KEY = "realmcontrolTeleport";
    @Unique private int realmcontrol_tpd$tpCount;
    @Unique private long realmcontrol_tpd$tpExpiry;

    @Override
    public int realmcontrol_tpd$getTpCount() {
        return realmcontrol_tpd$tpCount;
    }

    @Override
    public void realmcontrol_tpd$setTpCount(int count) {
        realmcontrol_tpd$tpCount = Math.max(0, count);
    }

    @Override
    public long realmcontrol_tpd$getTpExpiry() {
        return realmcontrol_tpd$tpExpiry;
    }

    @Override
    public void realmcontrol_tpd$setTpExpiry(long timestamp) {
        realmcontrol_tpd$tpExpiry = timestamp;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void realmcontrol_tpd$saveTpData(CompoundTag tag, CallbackInfo callback) {
        CompoundTag data = new CompoundTag();
        data.putInt("TpCount", realmcontrol_tpd$tpCount);
        data.putLong("TpExpiry", realmcontrol_tpd$tpExpiry);
        tag.put(TELEPORT_DATA_KEY, data);
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void realmcontrol_tpd$loadTpData(CompoundTag tag, CallbackInfo callback) {
        if (!tag.contains(TELEPORT_DATA_KEY, 10)) return;
        CompoundTag data = tag.getCompound(TELEPORT_DATA_KEY);
        realmcontrol_tpd$tpCount = data.getInt("TpCount");
        realmcontrol_tpd$tpExpiry = data.getLong("TpExpiry");
    }

    @Inject(method = "restoreFrom", at = @At("TAIL"))
    private void realmcontrol_tpd$onClone(ServerPlayer oldPlayer, boolean wonGame, CallbackInfo callback) {
        if (oldPlayer instanceof ITeleportAuth oldAuth) {
            realmcontrol_tpd$setTpCount(oldAuth.realmcontrol_tpd$getTpCount());
            realmcontrol_tpd$setTpExpiry(oldAuth.realmcontrol_tpd$getTpExpiry());
        }
    }
}
