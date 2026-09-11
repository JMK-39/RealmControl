package dev.xyat.realmcontrol.worldgen;

import com.mojang.logging.LogUtils;
import dev.xyat.kineticcore.config.server.KTServerConfigApi;
import dev.xyat.realmcontrol.worldgen.command.WorldGenCommandExtension;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfigGui;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

public final class WorldGenModule {
    public static final String MODID = "realmcontrol";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldGenModule(FMLJavaModLoadingContext context) {
        KTServerConfigApi.registerActionPage(WorldGenConfigGui.RULES_PAGE_ID);
        WorldGenNetwork.register();
        WorldGenCommandExtension.install();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> WorldGenConfigGui::load);
    }
}
