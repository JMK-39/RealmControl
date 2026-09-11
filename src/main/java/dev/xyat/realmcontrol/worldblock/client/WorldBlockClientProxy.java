package dev.xyat.realmcontrol.worldblock.client;

import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.api.client.overlay.GuiOverlay;
import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldblock.client.gui.ItemSearchCache;
import dev.xyat.realmcontrol.worldblock.client.gui.OreBannedScreen;
import dev.xyat.realmcontrol.worldblock.client.gui.OreMergeScreen;
import dev.xyat.realmcontrol.worldblock.client.gui.WeightedBlockMergeScreen;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = WorldBlockModule.MODID, value = Dist.CLIENT)
public final class WorldBlockClientProxy {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static String pendingSaveSuccessKey;

    private WorldBlockClientProxy() {
    }

    public static void openOreMergeGui() {
        Screen parent = Minecraft.getInstance().screen;
        ItemSearchCache.prepareCache(() -> Minecraft.getInstance().setScreen(new OreMergeScreen(parent, copyOreMergedRules(), () -> {
        })));
    }

    public static void openOreBannedGui() {
        Screen parent = Minecraft.getInstance().screen;
        ItemSearchCache.prepareCache(() -> Minecraft.getInstance().setScreen(new OreBannedScreen(parent)));
    }

    public static void openWeightedBlockMergeGui() {
        Screen parent = Minecraft.getInstance().screen;
        ItemSearchCache.prepareCache(() -> Minecraft.getInstance().setScreen(new WeightedBlockMergeScreen(parent)));
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
                GuiOverlay.toast(
                        "worldblock_save_success_" + successKey,
                        Component.translatable(successKey)
                );
            } else {
                LOGGER.warn("WorldBlock save succeeded without a matching operation success key");
            }
            return;
        }
        GuiOverlay.toast(
                "worldblock_save_failed",
                Component.translatable("gui.kineticcore.config.save_failed")
        );
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
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
