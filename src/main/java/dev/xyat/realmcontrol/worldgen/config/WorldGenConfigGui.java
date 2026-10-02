package dev.xyat.realmcontrol.worldgen.config;

import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.config.client.KTConfigPage;
import dev.xyat.kineticcore.api.config.client.KTConfigScope;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;

public final class WorldGenConfigGui {
    public static final String RULES_PAGE_ID = "realmcontrol:rules";

    private WorldGenConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(buildRulesPage());
    }

    public static void open() {
        KTConfigApi.openOwner("realmcontrol");
    }

    private static KTConfigPage buildRulesPage() {
        return KTConfigPage.builder(
                        RULES_PAGE_ID,
                        KineticI18n.translatable("cfg.realmcontrol.worldgen.worldgen.rules.title")
                )
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.MIXED)
                .applyNotice(KineticI18n.translatable("cfg.realmcontrol.worldgen.worldgen.apply_notice"))
                .pageDescription(KineticI18n.translatable("cfg.realmcontrol.worldgen.worldgen.rules.description"))
                .action(
                        "open_rule_editor",
                        KineticI18n.translatable("cfg.realmcontrol.worldgen.worldgen.rules.open"),
                        WorldGenNetwork::requestOpenEditor,
                        KineticI18n.translatable("cfg.realmcontrol.worldgen.worldgen.rules.open.tooltip")
                )
                .build();
    }
}
