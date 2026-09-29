package dev.xyat.realmcontrol.teleport.config;

import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.config.client.KTConfigPage;
import dev.xyat.kineticcore.api.config.client.KTConfigScope;
import dev.xyat.kineticcore.api.text.KineticI18n;
import net.minecraft.client.gui.screens.Screen;

public final class TpdConfigGui {
    public static final String PAGE_ID = "realmcontrol:tpd";

    private TpdConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(KTConfigPage.builder(
                        PAGE_ID,
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.title")
                )
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.IMMEDIATE)
                .pageDescription(KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.description"))
                .booleanValue(
                        "enable_tp_modify",
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.modify"),
                        () -> TpdConfig.enableTpModify,
                        value -> TpdConfig.enableTpModify = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.modify.tooltip")
                )
                .booleanValue(
                        "admin_tp_bypass",
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.admin_bypass"),
                        () -> TpdConfig.adminTpBypass,
                        value -> TpdConfig.adminTpBypass = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.admin_bypass.tooltip")
                )
                .choice(
                        "tp_mode",
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.mode"),
                        () -> TpdConfig.tpMode,
                        TpdConfig::setTpMode,
                        "AUTHORIZED",
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.mode.tooltip"),
                        "FREE", "AUTHORIZED"
                )
                .longTextValue(
                        "deny_custom_message",
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.deny_msg"),
                        () -> TpdConfig.tpDenyCustomMessage,
                        TpdConfig::setDenyCustomMessage,
                        "",
                        KineticI18n.translatable("cfg.realmcontrol.teleport.tpd.deny_msg.tooltip")
                )
                .build());
    }

    public static void open() {
        KTConfigApi.openOwner("realmcontrol");
    }

}
