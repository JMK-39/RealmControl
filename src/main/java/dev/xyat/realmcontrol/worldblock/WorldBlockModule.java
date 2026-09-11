package dev.xyat.realmcontrol.worldblock;

import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.config.server.KTServerConfigApi;
import dev.xyat.realmcontrol.worldblock.command.WorldBlockCommandExtension;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfigGui;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

public final class WorldBlockModule {
    public static final String MODID = "realmcontrol";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldBlockModule(FMLJavaModLoadingContext context) {
        KTServerConfigApi.registerActionPage(WorldBlockConfigGui.PAGE_ID);
        WorldBlockNetwork.register();
        WorldBlockCommandExtension.install();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> WorldBlockConfigGui.load());
    }
}
