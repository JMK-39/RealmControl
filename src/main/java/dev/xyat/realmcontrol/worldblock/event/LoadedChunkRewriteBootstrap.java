package dev.xyat.realmcontrol.worldblock.event;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import dev.xyat.kineticcore.api.world.event.KineticWorldEvents;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LoadedChunkRewriteBootstrap {
    private static final Path CONFIG_PATH = KineticPlatform.configDirectory().resolve("kineticcore/worldblock.json");
    private static final StartupMode STARTUP_MODE = readStartupMode();

    private LoadedChunkRewriteBootstrap() {
    }

    public static void initialize() {
        if (!STARTUP_MODE.anyEnabled()) return;
        LoadedChunkBlockRewriteHandler handler = new LoadedChunkBlockRewriteHandler();
        KineticWorldEvents.onChunkLoad(KineticEventPriority.NORMAL, handler::onChunkLoad);
        KineticWorldEvents.onChunkUnload(KineticEventPriority.NORMAL, handler::onChunkUnload);
        KineticServerEvents.onStopped(KineticEventPriority.NORMAL, server -> handler.onServerStopped());
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.END, server -> handler.onServerTick());
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
