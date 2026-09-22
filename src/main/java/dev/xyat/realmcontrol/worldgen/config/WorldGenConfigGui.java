package dev.xyat.realmcontrol.worldgen.config;

import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.config.client.KTConfigPage;
import dev.xyat.kineticcore.api.config.client.KTConfigScope;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorldGenConfigGui {
    public static final String RULES_PAGE_ID = "realmcontrol:rules";

    private WorldGenConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(buildRulesPage());
    }

    public static Screen create(Screen parent) {
        return KTConfigApi.createScreenForOwner(parent, "realmcontrol");
    }

    private static KTConfigPage buildRulesPage() {
        return KTConfigPage.builder(
                        RULES_PAGE_ID,
                        Component.translatable("cfg.realmcontrol.worldgen.worldgen.rules.title")
                )
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.MIXED)
                .applyNotice(Component.translatable("cfg.realmcontrol.worldgen.worldgen.apply_notice"))
                .pageDescription(Component.translatable("cfg.realmcontrol.worldgen.worldgen.rules.description"))
                .action(
                        "open_rule_editor",
                        Component.translatable("cfg.realmcontrol.worldgen.worldgen.rules.open"),
                        WorldGenNetwork::requestOpenEditor,
                        Component.translatable("cfg.realmcontrol.worldgen.worldgen.rules.open.tooltip")
                )
                .build();
    }
}
