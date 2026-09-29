package dev.xyat.realmcontrol.worldblock.client;

import dev.xyat.kineticcore.api.text.KineticI18n;
import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.realmcontrol.worldblock.client.gui.ItemCacheHudRenderer;
import dev.xyat.realmcontrol.worldblock.client.gui.ItemSearchCache;
import dev.xyat.realmcontrol.worldblock.client.gui.OreBannedPage;
import dev.xyat.realmcontrol.worldblock.client.gui.OreMergePage;
import dev.xyat.realmcontrol.worldblock.client.gui.WeightedBlockMergePage;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
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

    // 返回到打开时的当前界面（无界面时回到游戏）/ Back returns to the screen current when the page opens (or the game).
    public static void openOreMergeGui() {
        ItemSearchCache.prepareCache(() -> KineticGui.openChild(new OreMergePage(copyOreMergedRules(), () -> {
        })));
    }

    public static void openOreBannedGui() {
        ItemSearchCache.prepareCache(() -> KineticGui.openChild(new OreBannedPage()));
    }

    public static void openWeightedBlockMergeGui() {
        ItemSearchCache.prepareCache(() -> KineticGui.openChild(new WeightedBlockMergePage()));
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
                        KineticI18n.translatable(successKey)
                );
            } else {
                LOGGER.warn("WorldBlock save succeeded without a matching operation success key");
            }
            return;
        }
        KineticOverlays.toast(
                "worldblock_save_failed",
                KineticI18n.translatable("gui.kineticcore.config.save_failed")
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
