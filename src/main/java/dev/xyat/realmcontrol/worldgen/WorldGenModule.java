package dev.xyat.realmcontrol.worldgen;

import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.api.config.server.KTServerConfigApi;
import dev.xyat.realmcontrol.worldgen.command.WorldGenCommandExtension;
import dev.xyat.realmcontrol.worldgen.config.StructureGenerationControl;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfigGui;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import org.slf4j.Logger;

public final class WorldGenModule {
    public static final String MODID = "realmcontrol";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldGenModule() {
        KTServerConfigApi.registerActionPage(WorldGenConfigGui.RULES_PAGE_ID);
        WorldGenNetwork.register();
        StructureGenerationControl.install();
        WorldGenCommandExtension.install();
        KineticPlatform.runOnClient(() -> WorldGenConfigGui::load);
    }
}
