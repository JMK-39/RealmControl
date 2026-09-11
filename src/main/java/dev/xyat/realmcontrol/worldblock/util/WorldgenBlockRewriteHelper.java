package dev.xyat.realmcontrol.worldblock.util;

import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

public final class WorldgenBlockRewriteHelper {
    private static final ThreadLocal<Boolean> WORLDGEN_THREAD = ThreadLocal.withInitial(WorldgenBlockRewriteHelper::detectWorldgenThread);

    private WorldgenBlockRewriteHelper() {
    }

    public static BlockState rewriteFromRawChunkSection(BlockState state) {
        if (!WorldBlockConfig.hasRawWorldgenRewriteRules) return state;
        if (!WORLDGEN_THREAD.get()) return state;

        BlockState replacement = WorldBlockConfig.getFastWorldgenRewriteState(state);
        return replacement == null ? state : replacement;
    }

    private static boolean detectWorldgenThread() {
        String name = Thread.currentThread().getName();
        if (name == null) return false;
        String clean = name.toLowerCase(Locale.ROOT);
        return clean.contains("worker-main")
                || clean.contains("worldgen")
                || clean.contains("world gen")
                || clean.contains("chunk")
                || clean.contains("terrain");
    }
}
