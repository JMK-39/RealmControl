package dev.xyat.realmcontrol.beacon.mixin;

import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.event.LevelChangedEvent;
import dev.xyat.realmcontrol.beacon.event.SpawnPreventionHandler;
import dev.xyat.realmcontrol.beacon.util.BeaconStateManager;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

public class BeaconMixins {
    @Mixin(BeaconBlockEntity.class)
    public static abstract class Logic implements IBeaconAccess {
        @Unique private boolean realmcontrol_beacon$ChunkLoadEnabled = false;
        @Unique private int realmcontrol_beacon$ChunkLoadRadius = -1;
        @Unique private boolean realmcontrol_beacon$SpawnPreventEnabled = true;
        @Unique private int realmcontrol_beacon$SpawnPreventRadius = -1;
        @Unique private int realmcontrol_beacon$SpawnPreventType = 0;
        @Unique private String realmcontrol_beacon$SpawnPreventCodes = "";
        @Unique private UUID realmcontrol_beacon$Owner = null;
        @Unique private boolean realmcontrol_beacon$WasOffline = false;

        @Override public boolean realmcontrol_beacon$isChunkLoadEnabled() { return realmcontrol_beacon$ChunkLoadEnabled; }
        @Override public void realmcontrol_beacon$setChunkLoadEnabled(boolean enabled) { this.realmcontrol_beacon$ChunkLoadEnabled = enabled; }
        @Override public int realmcontrol_beacon$getChunkLoadRadius() { return realmcontrol_beacon$ChunkLoadRadius; }
        @Override public void realmcontrol_beacon$setChunkLoadRadius(int radius) { this.realmcontrol_beacon$ChunkLoadRadius = radius; }
        @Override public boolean realmcontrol_beacon$isSpawnPreventEnabled() { return realmcontrol_beacon$SpawnPreventEnabled; }
        @Override public void realmcontrol_beacon$setSpawnPreventEnabled(boolean enabled) {
            this.realmcontrol_beacon$SpawnPreventEnabled = enabled;
            BeaconBlockEntity beacon = (BeaconBlockEntity) (Object) this;
            if (beacon.getLevel() != null && !beacon.getLevel().isClientSide) {
                int levels = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();
                if (enabled && levels > 0) {
                    int max = BeaconConfig.getBeaconRadius(levels);
                    int maxPrevent = max >= 0 ? max + 1 : -1;
                    int actual = realmcontrol_beacon$getActualSpawnPreventRadius(levels, maxPrevent);
                    if (actual >= 0) {
                        SpawnPreventionHandler.updateBeacon(beacon.getLevel(), beacon.getBlockPos(), actual, realmcontrol_beacon$SpawnPreventType, realmcontrol_beacon$SpawnPreventCodes);
                    } else {
                        SpawnPreventionHandler.removeBeacon(beacon.getLevel(), beacon.getBlockPos());
                    }
                } else {
                    SpawnPreventionHandler.removeBeacon(beacon.getLevel(), beacon.getBlockPos());
                }
            }
        }
        @Override public int realmcontrol_beacon$getSpawnPreventRadius() { return realmcontrol_beacon$SpawnPreventRadius; }
        @Override public void realmcontrol_beacon$setSpawnPreventRadius(int radius) { this.realmcontrol_beacon$SpawnPreventRadius = radius; }
        @Override public int realmcontrol_beacon$getSpawnPreventType() { return realmcontrol_beacon$SpawnPreventType; }
        @Override public void realmcontrol_beacon$setSpawnPreventType(int type) { this.realmcontrol_beacon$SpawnPreventType = type; }
        @Override public String realmcontrol_beacon$getSpawnPreventCodes() { return realmcontrol_beacon$SpawnPreventCodes; }
        @Override public void realmcontrol_beacon$setSpawnPreventCodes(String codes) { this.realmcontrol_beacon$SpawnPreventCodes = codes; }
        @Override public UUID realmcontrol_beacon$getOwner() { return realmcontrol_beacon$Owner; }
        @Override public void realmcontrol_beacon$setOwner(UUID uuid) { this.realmcontrol_beacon$Owner = uuid; }
        @Override public boolean realmcontrol_beacon$getWasOffline() { return realmcontrol_beacon$WasOffline; }
        @Override public void realmcontrol_beacon$setWasOffline(boolean wasOffline) { this.realmcontrol_beacon$WasOffline = wasOffline; }

        @Override
        public boolean realmcontrol_beacon$checkOffline() {
            if (this.realmcontrol_beacon$Owner == null || BeaconConfig.beaconOfflineTimeout < 0) return false;
            BeaconBlockEntity beacon = (BeaconBlockEntity) (Object) this;
            if (beacon.getLevel() != null && !beacon.getLevel().isClientSide) {
                MinecraftServer server = beacon.getLevel().getServer();
                if (server == null) return false;

                long offlineMins = BeaconStateManager.get(server).getOfflineMinutes(this.realmcontrol_beacon$Owner);
                return offlineMins >= 0 && offlineMins >= BeaconConfig.beaconOfflineTimeout;
            }
            return false;
        }

        @Inject(method = "saveAdditional", at = @At("TAIL"))
        private void saveKTSettings(CompoundTag tag, CallbackInfo ci) {
            tag.putBoolean("BeaconModuleChunkLoad", this.realmcontrol_beacon$ChunkLoadEnabled);
            tag.putInt("BeaconModuleChunkLoadRadius", this.realmcontrol_beacon$ChunkLoadRadius);
            tag.putBoolean("BeaconModuleSpawnPrevent", this.realmcontrol_beacon$SpawnPreventEnabled);
            tag.putInt("BeaconModuleSpawnPreventRadius", this.realmcontrol_beacon$SpawnPreventRadius);
            tag.putInt("BeaconModuleSpawnPreventType", this.realmcontrol_beacon$SpawnPreventType);
            tag.putString("BeaconModuleSpawnPreventCodes", this.realmcontrol_beacon$SpawnPreventCodes);
            tag.putInt("BeaconModuleStoredLevels", ((LevelAccess) this).realmcontrol_beacon$getLevels());
            tag.putBoolean("BeaconModuleWasOffline", this.realmcontrol_beacon$WasOffline);
            if (this.realmcontrol_beacon$Owner != null) tag.putUUID("BeaconModuleOwner", this.realmcontrol_beacon$Owner);
        }

        @Inject(method = "load", at = @At("TAIL"))
        private void loadKTSettings(CompoundTag tag, CallbackInfo ci) {
            if (tag.contains("BeaconModuleChunkLoad")) this.realmcontrol_beacon$ChunkLoadEnabled = tag.getBoolean("BeaconModuleChunkLoad");
            if (tag.contains("BeaconModuleChunkLoadRadius")) this.realmcontrol_beacon$ChunkLoadRadius = tag.getInt("BeaconModuleChunkLoadRadius");
            if (tag.contains("BeaconModuleSpawnPrevent")) this.realmcontrol_beacon$SpawnPreventEnabled = tag.getBoolean("BeaconModuleSpawnPrevent");
            if (tag.contains("BeaconModuleSpawnPreventRadius")) this.realmcontrol_beacon$SpawnPreventRadius = tag.getInt("BeaconModuleSpawnPreventRadius");
            if (tag.contains("BeaconModuleSpawnPreventType")) this.realmcontrol_beacon$SpawnPreventType = tag.getInt("BeaconModuleSpawnPreventType");
            if (tag.contains("BeaconModuleSpawnPreventCodes")) this.realmcontrol_beacon$SpawnPreventCodes = tag.getString("BeaconModuleSpawnPreventCodes");
            if (tag.contains("BeaconModuleStoredLevels")) ((LevelAccess) this).realmcontrol_beacon$setLevels(tag.getInt("BeaconModuleStoredLevels"));
            if (tag.contains("BeaconModuleWasOffline")) this.realmcontrol_beacon$WasOffline = tag.getBoolean("BeaconModuleWasOffline");
            if (tag.contains("BeaconModuleOwner")) this.realmcontrol_beacon$Owner = tag.getUUID("BeaconModuleOwner");
        }

        @Inject(method = "tick", at = @At("HEAD"))
        private static void realmcontrol_beacon$checkStateBeforeTick(Level level, BlockPos pos, BlockState state, BeaconBlockEntity beacon, CallbackInfo ci) {
            if (level.isClientSide) return;
            IBeaconAccess accessor = (IBeaconAccess) beacon;
            boolean isOffline = accessor.realmcontrol_beacon$checkOffline();
            boolean wasOffline = accessor.realmcontrol_beacon$getWasOffline();

            if (isOffline != wasOffline) {
                accessor.realmcontrol_beacon$setWasOffline(isOffline);

                level.sendBlockUpdated(pos, state, state, 3);

                int currentLevel = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();

                MinecraftForge.EVENT_BUS.post(new LevelChangedEvent(level, pos, beacon, currentLevel, currentLevel));

                if (accessor.realmcontrol_beacon$isSpawnPreventEnabled() && currentLevel > 0) {
                    int max = BeaconConfig.getBeaconRadius(currentLevel);
                    int maxPrevent = max >= 0 ? max + 1 : -1;
                    int actual = accessor.realmcontrol_beacon$getActualSpawnPreventRadius(currentLevel, maxPrevent);
                    if (actual >= 0) {
                        SpawnPreventionHandler.updateBeacon(level, pos, actual, accessor.realmcontrol_beacon$getSpawnPreventType(), accessor.realmcontrol_beacon$getSpawnPreventCodes());
                    } else {
                        SpawnPreventionHandler.removeBeacon(level, pos);
                    }
                } else {
                    SpawnPreventionHandler.removeBeacon(level, pos);
                }
            }
        }

        @Redirect(
                method = "tick",
                at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/entity/BeaconBlockEntity;levels:I", opcode = org.objectweb.asm.Opcodes.PUTFIELD)
        )
        private static void realmcontrol_beacon$interceptLevelUpdate(BeaconBlockEntity beacon, int newLevel) {
            int oldLevel = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();
            ((LevelAccess) beacon).realmcontrol_beacon$setLevels(newLevel);

            if (beacon.getLevel() != null && !beacon.getLevel().isClientSide && oldLevel != newLevel) {
                MinecraftForge.EVENT_BUS.post(new LevelChangedEvent(beacon.getLevel(), beacon.getBlockPos(), beacon, oldLevel, newLevel));
                IBeaconAccess accessor = (IBeaconAccess) beacon;
                if (accessor.realmcontrol_beacon$isSpawnPreventEnabled() && newLevel > 0) {
                    int max = BeaconConfig.getBeaconRadius(newLevel);
                    int maxPrevent = max >= 0 ? max + 1 : -1;
                    int actual = accessor.realmcontrol_beacon$getActualSpawnPreventRadius(newLevel, maxPrevent);
                    if (actual >= 0) {
                        SpawnPreventionHandler.updateBeacon(beacon.getLevel(), beacon.getBlockPos(), actual, accessor.realmcontrol_beacon$getSpawnPreventType(), accessor.realmcontrol_beacon$getSpawnPreventCodes());
                    } else {
                        SpawnPreventionHandler.removeBeacon(beacon.getLevel(), beacon.getBlockPos());
                    }
                } else {
                    SpawnPreventionHandler.removeBeacon(beacon.getLevel(), beacon.getBlockPos());
                }
            }
        }

        @Inject(method = "updateBase", at = @At("RETURN"), cancellable = true)
        private static void realmcontrol_beacon$interceptUpdateBase(Level level, int x, int y, int z, CallbackInfoReturnable<Integer> cir) {
            if (level != null) {
                BlockEntity be = level.getBlockEntity(new BlockPos(x, y, z));
                if (be instanceof BeaconBlockEntity && be instanceof IBeaconAccess accessor) {
                    if (BeaconConfig.offlineDisableDeactivate && accessor.realmcontrol_beacon$getWasOffline()) {
                        cir.setReturnValue(0);
                    }
                }
            }
        }
    }
}
