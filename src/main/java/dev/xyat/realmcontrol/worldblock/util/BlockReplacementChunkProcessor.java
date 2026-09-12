package dev.xyat.realmcontrol.worldblock.util;

import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class BlockReplacementChunkProcessor {
    private BlockReplacementChunkProcessor() {
    }

    public static void rewriteLoadedTargets(ServerLevel level, LevelChunk chunk, boolean includeFixed, boolean includeWeighted) {
        if ((!includeFixed && !includeWeighted) || level == null || chunk == null) return;

        Set<Block> fixedSources = includeFixed ? WorldBlockConfig.worldgenReplacementBlockStates.keySet() : Set.of();
        Set<Block> weightedSources = includeWeighted ? WorldBlockConfig.worldgenWeightedBlockReplacements.keySet() : Set.of();
        if (fixedSources.isEmpty() && weightedSources.isEmpty()) return;

        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        int minBuildY = level.getMinBuildHeight();
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(state -> isCandidate(state, fixedSources, weightedSources))) continue;

            int minY = minBuildY + sectionIndex * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState sourceState = section.getBlockState(x, y, z);
                        if (sourceState == null || sourceState.isAir()) continue;

                        BlockState targetState = sourceState;
                        if (includeWeighted && weightedSources.contains(sourceState.getBlock())) {
                            WorldBlockConfig.WeightedBlockReplacement weighted = WorldBlockConfig.worldgenWeightedBlockReplacements.get(sourceState.getBlock());
                            if (weighted != null) targetState = weighted.resolveState(sourceState);
                        } else if (includeFixed && fixedSources.contains(sourceState.getBlock())) {
                            BlockState fixed = WorldBlockConfig.worldgenFastPlainStateRewrites.get(sourceState);
                            if (fixed != null && rollFixedChance(sourceState.getBlock())) targetState = fixed;
                        }

                        if (targetState == sourceState || targetState.equals(sourceState)) continue;
                        pos.set(minX + x, minY + y, minZ + z);
                        level.setBlock(pos, targetState, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    private static boolean isCandidate(BlockState state, Set<Block> fixedSources, Set<Block> weightedSources) {
        if (state == null || state.isAir()) return false;
        Block block = state.getBlock();
        return fixedSources.contains(block) || weightedSources.contains(block);
    }

    private static boolean rollFixedChance(Block source) {
        int chance = WorldBlockConfig.worldgenFixedReplacementChances.getOrDefault(source, 100);
        if (chance >= 100) return true;
        if (chance <= 0) return false;
        return ThreadLocalRandom.current().nextInt(100) < chance;
    }
}
