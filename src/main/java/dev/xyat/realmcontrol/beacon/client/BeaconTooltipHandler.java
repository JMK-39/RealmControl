package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.realmcontrol.beacon.util.ColorText;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BeaconTooltipHandler {
    private static boolean installed;

    private BeaconTooltipHandler() {
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        KineticClientEvents.onItemTooltip(BeaconTooltipHandler::onTooltip);
    }

    private static void onTooltip(KineticClientEvents.ItemTooltipContext event) {
        ItemStack stack = event.itemStack();
        if (stack.isEmpty()) return;

        List<Component> tooltip = event.tooltip();
        if (stack.is(Items.BEACON)) {
            tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.grid_hint"));
            tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.color_hint"));

            int globalMax = ClientQuotaCache.globalMax;
            int personalMax = ClientQuotaCache.personalMax;
            int personalUsed = ClientQuotaCache.personalUsed;
            int globalUsed = ClientQuotaCache.globalUsed;

            if (ClientQuotaCache.perPlayerEnabled) {
                int remain = Math.max(0, personalMax - personalUsed);
                tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.quota_both", personalUsed, remain, globalMax));
            } else {
                int remain = Math.max(0, globalMax - globalUsed);
                tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.quota_single", ColorText.translatable("msg.realmcontrol.beacon.beacon.quota_global").getString(), globalUsed, remain));
            }

            if (ClientQuotaCache.offlineTimeout >= 0) {
                String acts = getString();

                tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.offline_warn", ClientQuotaCache.offlineTimeout, acts.trim()));
            }

            if (KineticClientRuntime.shiftModifierDown()) {
                tooltip.add(Component.empty());
                for (int level = 1; level <= 4; level++) {
                    int radius = BeaconConfig.getBeaconRadius(level);
                    if (radius >= 0) {
                        int loadSize = 2 * radius + 1;
                        int preventSize = 2 * (radius + 1) + 1;

                        if (BeaconConfig.enableBeaconSpawnPrevention) {
                            tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.level_dual",
                                    level, loadSize, loadSize, preventSize, preventSize));
                        } else {
                            tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.level_single",
                                    level, loadSize, loadSize));
                        }
                    }
                }
            } else {
                tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.hold_sneak_hint"));
            }
        }
    }

    private static @NotNull String getString() {
        String acts = "";
        if (ClientQuotaCache.offlineDeact) acts += ColorText.translatable("tip.realmcontrol.beacon.beacon.act_deact").getString() + " ";
        if (ClientQuotaCache.offlineCL) acts += ColorText.translatable("tip.realmcontrol.beacon.beacon.act_cl").getString() + " ";
        if (ClientQuotaCache.offlineSP) acts += ColorText.translatable("tip.realmcontrol.beacon.beacon.act_sp").getString() + " ";
        if (acts.isEmpty()) acts = ColorText.translatable("tip.realmcontrol.beacon.beacon.act_none").getString();
        return acts;
    }
}
