package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.realmcontrol.beacon.BeaconModule;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BeaconModule.MODID, value = Dist.CLIENT)
public class BeaconRenderEventHandler {

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        BeaconRangeRenderer.render(event);
    }
}
