package dev.xyat.realmcontrol.teleport;

import com.mojang.logging.LogUtils;
import dev.xyat.realmcontrol.teleport.command.TpdCommandExtension;
import dev.xyat.realmcontrol.teleport.config.TpdConfig;
import dev.xyat.realmcontrol.teleport.config.TpdConfigGui;
import dev.xyat.kineticcore.config.server.KTServerConfigApi;
import dev.xyat.kineticcore.config.server.KTServerConfigSpec;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

public final class TeleportModule {
    public static final String MODID = "realmcontrol";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TeleportModule(FMLJavaModLoadingContext context) {
        TpdConfig.load();
        KTServerConfigApi.register(KTServerConfigSpec.builder(TpdConfigGui.PAGE_ID)
                .booleanValue(
                        "enable_tp_modify",
                        () -> TpdConfig.enableTpModify,
                        value -> TpdConfig.enableTpModify = value
                )
                .booleanValue(
                        "admin_tp_bypass",
                        () -> TpdConfig.adminTpBypass,
                        value -> TpdConfig.adminTpBypass = value
                )
                .stringValue(
                        "tp_mode",
                        () -> TpdConfig.tpMode,
                        TpdConfig::setTpMode
                )
                .stringValue(
                        "deny_custom_message",
                        () -> TpdConfig.tpDenyCustomMessage,
                        TpdConfig::setDenyCustomMessage
                )
                .onSave(TpdConfig::save)
                .build());
        TpdCommandExtension.install();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> TpdConfigGui.load());
    }
}
