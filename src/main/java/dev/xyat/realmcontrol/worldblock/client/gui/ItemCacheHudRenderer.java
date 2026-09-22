package dev.xyat.realmcontrol.worldblock.client.gui;

import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.kineticcore.api.client.search.KineticItemSearch;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.world.event.KineticWorldEvents;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class ItemCacheHudRenderer {
    private static boolean installed;

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        KineticWorldEvents.onEntityJoin(KineticEventPriority.NORMAL, ItemCacheHudRenderer::onPlayerJoin);
    }

    private static void onPlayerJoin(KineticWorldEvents.EntityJoinContext event) {
        if (event.level().isClientSide() && event.entity() == KineticClientRuntime.localPlayer()) {
            KineticItemSearch.clear();
            ItemSearchCache.clear();
        }
    }

    public static Component getDisplayNameCustom(ItemStack stack) {
        if (stack.getItem() == net.minecraft.world.item.Items.ENCHANTED_BOOK) {
            try {
                List<Component> lines = stack.getTooltipLines(KineticClientRuntime.localPlayer(), TooltipFlag.Default.NORMAL);
                if (lines.size() > 1) return Component.translatable("gui.realmcontrol.worldblock.common.tooltip_pair", lines.get(0), lines.get(1));
            } catch (Exception ignored) {}
        }
        return stack.getHoverName();
    }
}
