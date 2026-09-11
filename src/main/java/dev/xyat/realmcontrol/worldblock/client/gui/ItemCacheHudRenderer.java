package dev.xyat.realmcontrol.worldblock.client.gui;

import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.kineticcore.api.client.search.ItemSearchIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

public class ItemCacheHudRenderer {

    @SubscribeEvent
    public static void onPlayerJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide && event.getEntity() == Minecraft.getInstance().player) {
            ItemSearchIndex.clear();
            ItemSearchCache.clear();
        }
    }

    public static Component getDisplayNameCustom(ItemStack stack) {
        if (stack.getItem() == net.minecraft.world.item.Items.ENCHANTED_BOOK) {
            try {
                List<Component> lines = stack.getTooltipLines(Minecraft.getInstance().player, TooltipFlag.Default.NORMAL);
                if (lines.size() > 1) return Component.translatable("gui.realmcontrol.worldblock.common.tooltip_pair", lines.get(0), lines.get(1));
            } catch (Exception ignored) {}
        }
        return stack.getHoverName();
    }
}
