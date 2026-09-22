package dev.xyat.realmcontrol.beacon.client;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;

public final class BeaconRenderEventHandler {
    private static boolean installed;

    private BeaconRenderEventHandler() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        KineticClientEvents.onLevelRender(
                KineticClientEvents.LevelRenderStage.AFTER_PARTICLES,
                BeaconRangeRenderer::render
        );
    }
}
