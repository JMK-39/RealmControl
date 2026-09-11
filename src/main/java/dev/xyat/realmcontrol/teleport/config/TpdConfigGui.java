package dev.xyat.realmcontrol.teleport.config;

import dev.xyat.kineticcore.config.client.KTConfigApi;
import dev.xyat.kineticcore.config.client.KTConfigPage;
import dev.xyat.kineticcore.config.client.KTConfigScope;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class TpdConfigGui {
    public static final String PAGE_ID = "realmcontrol:tpd";

    private TpdConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(KTConfigPage.builder(
                        PAGE_ID,
                        Component.translatable("cfg.realmcontrol.teleport.tpd.title")
                )
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.IMMEDIATE)
                .pageDescription(Component.translatable("cfg.realmcontrol.teleport.tpd.description"))
                .booleanValue(
                        "enable_tp_modify",
                        Component.translatable("cfg.realmcontrol.teleport.tpd.modify"),
                        () -> TpdConfig.enableTpModify,
                        value -> TpdConfig.enableTpModify = value,
                        true,
                        Component.translatable("cfg.realmcontrol.teleport.tpd.modify.tooltip")
                )
                .booleanValue(
                        "admin_tp_bypass",
                        Component.translatable("cfg.realmcontrol.teleport.tpd.admin_bypass"),
                        () -> TpdConfig.adminTpBypass,
                        value -> TpdConfig.adminTpBypass = value,
                        true,
                        Component.translatable("cfg.realmcontrol.teleport.tpd.admin_bypass.tooltip")
                )
                .choice(
                        "tp_mode",
                        Component.translatable("cfg.realmcontrol.teleport.tpd.mode"),
                        () -> TpdConfig.tpMode,
                        TpdConfig::setTpMode,
                        "AUTHORIZED",
                        Component.translatable("cfg.realmcontrol.teleport.tpd.mode.tooltip"),
                        "FREE", "AUTHORIZED"
                )
                .stringValue(
                        "deny_custom_message",
                        Component.translatable("cfg.realmcontrol.teleport.tpd.deny_msg"),
                        () -> TpdConfig.tpDenyCustomMessage,
                        TpdConfig::setDenyCustomMessage,
                        "",
                        Component.translatable("cfg.realmcontrol.teleport.tpd.deny_msg.tooltip")
                )
                .build());
    }

    public static Screen create(Screen parent) {
        return KTConfigApi.createScreenForOwner(parent, "realmcontrol");
    }

}
