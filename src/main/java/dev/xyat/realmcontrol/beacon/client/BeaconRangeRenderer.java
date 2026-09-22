package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.render.KineticWorldRender;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Map;

public final class BeaconRangeRenderer {
    private static final double MIN_RENDER_Y = -64.0D;
    private static final double MAX_RENDER_Y = 320.0D;

    private BeaconRangeRenderer() {
    }

    public static void render(KineticClientEvents.LevelRenderContext context) {
        Player player = KineticClientRuntime.localPlayer();
        var level = KineticClientRuntime.currentLevel();
        if (player == null || level == null) return;
        if (!player.isHolding(Items.BEACON) || !player.isCrouching()) return;

        int renderDistance = KineticClientRuntime.renderDistanceChunks();
        ChunkPos playerChunkPos = new ChunkPos(player.blockPosition());

        try (KineticWorldRender.LineBatch batch = KineticWorldRender.beginLineBatch(context)) {
            for (int x = -renderDistance; x <= renderDistance; x++) {
                for (int z = -renderDistance; z <= renderDistance; z++) {
                    LevelChunk chunk = level.getChunk(playerChunkPos.x + x, playerChunkPos.z + z);
                    Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
                    if (blockEntities.isEmpty()) continue;

                    for (BlockEntity be : blockEntities.values()) {
                        if (!(be instanceof BeaconBlockEntity beacon) || !(beacon instanceof IBeaconAccess accessor)) {
                            continue;
                        }

                        int levels = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();
                        if (levels <= 0) continue;

                        int maxRad = BeaconConfig.getBeaconRadius(levels);
                        int maxPreventRad = maxRad >= 0 ? maxRad + 1 : -1;
                        ChunkPos chunkCenter = new ChunkPos(beacon.getBlockPos());

                        int loadRadius = accessor.realmcontrol_beacon$getActualChunkLoadRadius(levels, maxRad);
                        if (BeaconConfig.enableBeaconChunkLoading && loadRadius >= 0) {
                            batch.chunkCage(
                                    chunkCenter,
                                    loadRadius,
                                    MIN_RENDER_Y,
                                    MAX_RENDER_Y,
                                    KineticWorldRender.Indicator.SUCCESS
                            );
                        }

                        int preventRadius = accessor.realmcontrol_beacon$getActualSpawnPreventRadius(levels, maxPreventRad);
                        if (BeaconConfig.enableBeaconSpawnPrevention && preventRadius >= 0) {
                            batch.chunkCage(
                                    chunkCenter,
                                    preventRadius,
                                    MIN_RENDER_Y,
                                    MAX_RENDER_Y,
                                    KineticWorldRender.Indicator.INFO
                            );
                        }
                    }
                }
            }
        }
    }
}
