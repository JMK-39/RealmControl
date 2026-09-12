package dev.xyat.realmcontrol.worldblock.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.common.MinecraftForge;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LoadedChunkRewriteBootstrap {
    private static final Path CONFIG_PATH = Paths.get("config", "kineticcore", "worldblock.json");
    private static final StartupMode STARTUP_MODE = readStartupMode();

    private LoadedChunkRewriteBootstrap() {
    }

    public static void initialize() {
        if (!STARTUP_MODE.anyEnabled()) return;
        MinecraftForge.EVENT_BUS.register(new LoadedChunkBlockRewriteHandler());
    }

    public static boolean fixedEnabledAtStartup() {
        return STARTUP_MODE.fixedEnabled();
    }

    public static boolean weightedEnabledAtStartup() {
        return STARTUP_MODE.weightedEnabled();
    }

    private static StartupMode readStartupMode() {
        if (!Files.isRegularFile(CONFIG_PATH)) return StartupMode.DISABLED;
        try (BufferedReader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            boolean fixed = root.has("applyBlockReplacementToLoadedChunksOnce")
                    && root.get("applyBlockReplacementToLoadedChunksOnce").getAsBoolean();
            boolean weighted = root.has("applyWeightedBlockReplacementToLoadedChunksOnce")
                    && root.get("applyWeightedBlockReplacementToLoadedChunksOnce").getAsBoolean();
            return new StartupMode(fixed, weighted);
        } catch (Throwable ignored) {
            return StartupMode.DISABLED;
        }
    }

    private record StartupMode(boolean fixedEnabled, boolean weightedEnabled) {
        private static final StartupMode DISABLED = new StartupMode(false, false);

        private boolean anyEnabled() {
            return fixedEnabled || weightedEnabled;
        }
    }
}
