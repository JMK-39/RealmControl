package dev.xyat.realmcontrol.worldblock.config;

import dev.xyat.kineticcore.config.client.KTConfigApi;
import dev.xyat.kineticcore.config.client.KTConfigPage;
import dev.xyat.kineticcore.config.client.KTConfigScope;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorldBlockConfigGui {
    public static final String PAGE_ID = "realmcontrol:world_block";

    private WorldBlockConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(KTConfigPage.builder(
                        PAGE_ID,
                        Component.translatable("cfg.realmcontrol.worldblock.worldblock")
                )
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.MIXED)
                .section(Component.translatable("cfg.realmcontrol.worldblock.editors.title"))
                .description(Component.translatable("cfg.realmcontrol.worldblock.editors.description"))
                .action(
                        "open_mergeore_editor",
                        Component.translatable("cfg.realmcontrol.worldblock.editor.mergeore"),
                        () -> WorldBlockNetwork.requestOpenEditor(WorldBlockNetwork.EDITOR_MERGE_ORE),
                        Component.translatable("cfg.realmcontrol.worldblock.editor.mergeore.tooltip")
                )
                .action(
                        "open_weighted_block_editor",
                        Component.translatable("cfg.realmcontrol.worldblock.editor.weighted_block"),
                        () -> WorldBlockNetwork.requestOpenEditor(WorldBlockNetwork.EDITOR_WEIGHTED_BLOCK),
                        Component.translatable("cfg.realmcontrol.worldblock.editor.weighted_block.tooltip")
                )
                .action(
                        "open_banore_editor",
                        Component.translatable("cfg.realmcontrol.worldblock.editor.banore"),
                        () -> WorldBlockNetwork.requestOpenEditor(WorldBlockNetwork.EDITOR_BAN_ORE),
                        Component.translatable("cfg.realmcontrol.worldblock.editor.banore.tooltip")
                )
                .build());
    }

    public static Screen create(Screen parent) {
        return KTConfigApi.createScreen(parent, PAGE_ID);
    }
}
