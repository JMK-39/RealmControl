package dev.xyat.realmcontrol.worldblock;

import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.api.config.server.KTServerConfigApi;
import dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy;
import dev.xyat.realmcontrol.worldblock.command.WorldBlockCommandExtension;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfigGui;
import dev.xyat.realmcontrol.worldblock.event.LoadedChunkRewriteBootstrap;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import org.slf4j.Logger;

public final class WorldBlockModule {
    public static final String MODID = "realmcontrol";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldBlockModule() {
        KTServerConfigApi.registerActionPage(WorldBlockConfigGui.PAGE_ID);
        WorldBlockNetwork.register();
        WorldBlockCommandExtension.install();
        LoadedChunkRewriteBootstrap.initialize();
        KineticPlatform.runOnClient(() -> WorldBlockConfigGui::load);
        KineticPlatform.runOnClient(() -> WorldBlockClientProxy::install);
    }
}
