package dev.xyat.realmcontrol.worldblock.client;

import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.realmcontrol.worldblock.client.gui.ItemCacheHudRenderer;
import dev.xyat.realmcontrol.worldblock.client.gui.ItemSearchCache;
import dev.xyat.realmcontrol.worldblock.client.gui.OreBannedScreen;
import dev.xyat.realmcontrol.worldblock.client.gui.OreMergeScreen;
import dev.xyat.realmcontrol.worldblock.client.gui.WeightedBlockMergeScreen;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WorldBlockClientProxy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static String pendingSaveSuccessKey;
    private static boolean installed;

    private WorldBlockClientProxy() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        KineticClientEvents.onLogout(WorldBlockClientProxy::onClientLogout);
        ItemCacheHudRenderer.install();
    }

    public static void openOreMergeGui() {
        Screen parent = KineticClientRuntime.currentScreen();
        ItemSearchCache.prepareCache(() -> KineticClientRuntime.openScreen(new OreMergeScreen(parent, copyOreMergedRules(), () -> {
        })));
    }

    public static void openOreBannedGui() {
        Screen parent = KineticClientRuntime.currentScreen();
        ItemSearchCache.prepareCache(() -> KineticClientRuntime.openScreen(new OreBannedScreen(parent)));
    }

    public static void openWeightedBlockMergeGui() {
        Screen parent = KineticClientRuntime.currentScreen();
        ItemSearchCache.prepareCache(() -> KineticClientRuntime.openScreen(new WeightedBlockMergeScreen(parent)));
    }

    private static Map<String, List<String>> copyOreMergedRules() {
        Map<String, List<String>> tempRules = new HashMap<>();
        if (WorldBlockConfig.data != null && WorldBlockConfig.data.oreMergedItems != null) {
            for (Map.Entry<String, List<String>> entry : WorldBlockConfig.data.oreMergedItems.entrySet()) {
                tempRules.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        return tempRules;
    }

    public static void handleSyncWorldBlockConfig(String jsonData) {
        try {
            if (WorldBlockConfig.applyJson(jsonData, "client sync packet", false)) {
                ItemSearchCache.clear();
            }
        } catch (Throwable e) {
            LOGGER.error("Failed to sync world block configuration", e);
        }
    }

    public static void beginServerSave(String successTranslationKey) {
        pendingSaveSuccessKey = successTranslationKey;
    }

    public static void handleSaveResult(boolean success) {
        String successKey = pendingSaveSuccessKey;
        pendingSaveSuccessKey = null;
        if (success) {
            if (successKey != null && !successKey.isBlank()) {
                KineticOverlays.toast(
                        "worldblock_save_success_" + successKey,
                        Component.translatable(successKey)
                );
            } else {
                LOGGER.warn("WorldBlock save succeeded without a matching operation success key");
            }
            return;
        }
        KineticOverlays.toast(
                "worldblock_save_failed",
                Component.translatable("gui.kineticcore.config.save_failed")
        );
    }

    private static void onClientLogout() {
        pendingSaveSuccessKey = null;
        try {
            WorldBlockConfig.applyJson(
                    WorldBlockConfig.GSON.toJson(new WorldBlockConfig.Data()),
                    "client logout reset",
                    false
            );
            ItemSearchCache.clear();
        } catch (Throwable e) {
            LOGGER.error("Failed to clear synced world block configuration", e);
        }
    }
}
