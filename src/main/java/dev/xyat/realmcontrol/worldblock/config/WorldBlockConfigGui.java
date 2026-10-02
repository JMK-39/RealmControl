package dev.xyat.realmcontrol.worldblock.config;

import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.config.client.KTConfigPage;
import dev.xyat.kineticcore.api.config.client.KTConfigScope;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;

public final class WorldBlockConfigGui {
    public static final String PAGE_ID = "realmcontrol:world_block";

    private WorldBlockConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(KTConfigPage.builder(
                        PAGE_ID,
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.worldblock")
                )
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.MIXED)
                .divider()
                .description(KineticI18n.translatable("cfg.realmcontrol.worldblock.editors.description"))
                .action(
                        "open_mergeore_editor",
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.editor.mergeore"),
                        () -> WorldBlockNetwork.requestOpenEditor(WorldBlockNetwork.EDITOR_MERGE_ORE),
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.editor.mergeore.tooltip")
                )
                .action(
                        "open_weighted_block_editor",
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.editor.weighted_block"),
                        () -> WorldBlockNetwork.requestOpenEditor(WorldBlockNetwork.EDITOR_WEIGHTED_BLOCK),
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.editor.weighted_block.tooltip")
                )
                .action(
                        "open_banore_editor",
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.editor.banore"),
                        () -> WorldBlockNetwork.requestOpenEditor(WorldBlockNetwork.EDITOR_BAN_ORE),
                        KineticI18n.translatable("cfg.realmcontrol.worldblock.editor.banore.tooltip")
                )
                .build());
    }

    public static void open() {
        KTConfigApi.openPage(PAGE_ID);
    }
}
