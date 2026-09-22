package dev.xyat.realmcontrol.beacon.event;

import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.registry.KineticRegistries;
import dev.xyat.kineticcore.api.world.event.KineticWorldEvents;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnPreventionHandler {
    private static boolean installed;

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        KineticWorldEvents.onChunkLoad(KineticEventPriority.NORMAL, SpawnPreventionHandler::onChunkLoad);
        KineticWorldEvents.onChunkUnload(KineticEventPriority.NORMAL, SpawnPreventionHandler::onChunkUnload);
        KineticWorldEvents.onBlockBreak(KineticEventPriority.NORMAL, SpawnPreventionHandler::onBlockBreak);
        KineticWorldEvents.onMobFinalizeSpawn(KineticEventPriority.NORMAL, SpawnPreventionHandler::onCheckSpawn);
        KineticWorldEvents.onBabySpawn(KineticEventPriority.NORMAL, SpawnPreventionHandler::onBabySpawn);
    }

    public record BeaconProtectData(int radius, int type, String codes) {}

    public static final Map<ResourceKey<Level>, Map<BlockPos, BeaconProtectData>> ACTIVE_SPAWN_PREVENTERS = new ConcurrentHashMap<>();

    public static void updateBeacon(Level level, BlockPos pos, int loadRadius, int type, String codes) {
        if (level == null || level.isClientSide || loadRadius < 0) return;
        ACTIVE_SPAWN_PREVENTERS.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>()).put(pos, new BeaconProtectData(loadRadius, type, codes == null ? "" : codes.toUpperCase()));
    }

    public static void removeBeacon(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) return;
        Map<BlockPos, BeaconProtectData> map = ACTIVE_SPAWN_PREVENTERS.get(level.dimension());
        if (map != null) map.remove(pos);
    }

    private static void onChunkLoad(KineticWorldEvents.ChunkContext event) {
        if (event.level() instanceof ServerLevel level && event.chunk() instanceof LevelChunk chunk) {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof BeaconBlockEntity beacon && be instanceof IBeaconAccess accessor) {
                    int levels = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();
                    if (accessor.realmcontrol_beacon$isSpawnPreventEnabled() && levels > 0) {
                        int max = BeaconConfig.getBeaconRadius(levels);
                        int maxPrevent = max >= 0 ? max + 1 : -1;
                        int actual = accessor.realmcontrol_beacon$getActualSpawnPreventRadius(levels, maxPrevent);
                        if (actual >= 0) {
                            updateBeacon(level, beacon.getBlockPos(), actual, accessor.realmcontrol_beacon$getSpawnPreventType(), accessor.realmcontrol_beacon$getSpawnPreventCodes());
                        }
                    }
                }
            }
        }
    }

    private static void onChunkUnload(KineticWorldEvents.ChunkContext event) {
        if (event.level() instanceof ServerLevel level && event.chunk() instanceof LevelChunk chunk) {
            for (BlockEntity be : chunk.getBlockEntities().values()) {
                if (be instanceof BeaconBlockEntity beacon) {
                    removeBeacon(level, beacon.getBlockPos());
                }
            }
        }
    }

    private static void onBlockBreak(KineticWorldEvents.BlockBreakContext event) {
        if (event.state().is(Blocks.BEACON) && event.level() instanceof ServerLevel level) {
            removeBeacon(level, event.pos());
        }
    }

    private static boolean shouldCancelSpawn(ServerLevel level, BlockPos spawnPos, Entity entity, String spawnCode) {
        Map<BlockPos, BeaconProtectData> beacons = ACTIVE_SPAWN_PREVENTERS.get(level.dimension());
        if (beacons == null || beacons.isEmpty()) return false;

        ResourceLocation entityRL = KineticRegistries.entityTypes().id(entity.getType());
        if (entityRL == null) return false;

        int chunkX = spawnPos.getX() >> 4;
        int chunkZ = spawnPos.getZ() >> 4;

        boolean isProtected = false;
        int protectType = 0;
        String localCodes = "";

        for (Map.Entry<BlockPos, BeaconProtectData> entry : beacons.entrySet()) {
            BlockPos bPos = entry.getKey();
            int preventRadiusChunks = entry.getValue().radius();
            int bChunkX = bPos.getX() >> 4;
            int bChunkZ = bPos.getZ() >> 4;

            if (Math.abs(chunkX - bChunkX) <= preventRadiusChunks && Math.abs(chunkZ - bChunkZ) <= preventRadiusChunks) {
                isProtected = true;
                protectType = entry.getValue().type();
                localCodes = entry.getValue().codes();
                break;
            }
        }

        if (!isProtected) return false;

        boolean isEnemy = entity instanceof net.minecraft.world.entity.monster.Enemy;
        if (protectType == 1 && !isEnemy) return false;
        if (protectType == 2 && isEnemy) return false;

        String entityId = entityRL.toString();

        if (BeaconConfig.BEACON_SPAWN_WHITELIST_CACHE.contains(entityId)) return false;
        if (BeaconConfig.BEACON_SPAWN_BLACKLIST_CACHE.contains(entityId)) return true;

        if (localCodes == null || localCodes.isEmpty()) {
            String rules = BeaconConfig.BEACON_RULES_CACHE.get(entityId);
            if (rules != null) {
                return rules.contains(spawnCode);
            } else {
                return "A".equals(spawnCode);
            }
        } else {
            return localCodes.contains(spawnCode);
        }
    }

    private static void onCheckSpawn(KineticWorldEvents.MobFinalizeSpawnContext event) {
        ServerLevel level = event.serverLevel();
        if (level == null || !BeaconConfig.enableBeaconSpawnPrevention) return;

        String spawnCode = mapSpawnTypeToCode(event.spawnType());

        if (shouldCancelSpawn(level, event.entity().blockPosition(), event.entity(), spawnCode)) {
            event.cancel();
        }
    }

    private static void onBabySpawn(KineticWorldEvents.BabySpawnContext event) {
        if (!(event.parentA().level() instanceof ServerLevel level)) return;
        if (!BeaconConfig.enableBeaconSpawnPrevention) return;
        if (event.child() == null) return;

        if (shouldCancelSpawn(level, event.parentA().blockPosition(), event.child(), "H")) {
            event.cancel();
        }
    }

    private static String mapSpawnTypeToCode(MobSpawnType type) {
        return switch (type) {
            case CONVERSION -> "B";
            case COMMAND -> "C";
            case SPAWN_EGG, BUCKET, DISPENSER -> "D";
            case SPAWNER -> "E";
            case MOB_SUMMONED -> "F";
            case EVENT, REINFORCEMENT -> "G";
            case BREEDING -> "H";
            default -> "A";
        };
    }
}
