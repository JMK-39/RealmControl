package dev.xyat.realmcontrol.beacon.config;

import dev.xyat.kineticcore.config.client.KTConfigApi;
import dev.xyat.kineticcore.config.client.KTConfigPage;
import dev.xyat.kineticcore.config.client.KTConfigScope;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class BeaconConfigGui {
    public static final String PAGE_ID = "realmcontrol:beacon";

    private BeaconConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(KTConfigPage.builder(
                        PAGE_ID,
                        Component.translatable("cfg.realmcontrol.beacon.beacon")
                )
                .pageDescription(Component.translatable("cfg.realmcontrol.beacon.beacon.description"))
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.MIXED)
                .applyNotice(Component.translatable("cfg.realmcontrol.beacon.beacon.apply_notice"))
                .section(Component.translatable("cfg.realmcontrol.beacon.beacon.chunk.section"))
                .description(Component.translatable("cfg.realmcontrol.beacon.beacon.chunk.description"))
                .booleanValue(
                        "enable_chunk_loading",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.chunk"),
                        () -> BeaconConfig.enableBeaconChunkLoading,
                        value -> BeaconConfig.enableBeaconChunkLoading = value,
                        true,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.chunk.tooltip")
                )
                .intList(
                        "level_radii",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.radii"),
                        () -> BeaconConfig.beaconLevelRadii,
                        value -> BeaconConfig.beaconLevelRadii = value,
                        List.of(0, 1, 2, 3),
                        Component.translatable("cfg.realmcontrol.beacon.beacon.radii.tooltip")
                )
                .section(Component.translatable("cfg.realmcontrol.beacon.beacon.spawn.section"))
                .description(Component.translatable("cfg.realmcontrol.beacon.beacon.spawn.description"))
                .booleanValue(
                        "enable_spawn_prevention",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_prevent"),
                        () -> BeaconConfig.enableBeaconSpawnPrevention,
                        value -> BeaconConfig.enableBeaconSpawnPrevention = value,
                        true,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_prevent.tooltip")
                )
                .entityList(
                        "spawn_whitelist",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_whitelist"),
                        () -> BeaconConfig.beaconSpawnWhitelist,
                        value -> BeaconConfig.beaconSpawnWhitelist = value,
                        List.of("minecraft:villager"),
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_whitelist.tooltip")
                )
                .entityList(
                        "spawn_blacklist",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_blacklist"),
                        () -> BeaconConfig.beaconSpawnBlacklist,
                        value -> BeaconConfig.beaconSpawnBlacklist = value,
                        List.of(),
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_blacklist.tooltip")
                )
                .stringList(
                        "spawn_rules",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_rules"),
                        () -> BeaconConfig.beaconRulesRaw,
                        value -> BeaconConfig.beaconRulesRaw = value,
                        List.of("minecraft:zombie;A", "minecraft:skeleton;AE"),
                        Component.translatable("cfg.realmcontrol.beacon.beacon.spawn_rules.tooltip")
                )
                .section(Component.translatable("cfg.realmcontrol.beacon.beacon.limits.section"))
                .description(Component.translatable("cfg.realmcontrol.beacon.beacon.limits.description"))
                .intValue(
                        "global_chunk_load_limit",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.global_limit"),
                        () -> BeaconConfig.globalChunkLoadLimit,
                        value -> BeaconConfig.globalChunkLoadLimit = value,
                        500,
                        0,
                        Integer.MAX_VALUE,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.global_limit.tooltip")
                )
                .booleanValue(
                        "per_player_limit_enabled",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.per_player_enable"),
                        () -> BeaconConfig.perPlayerLimitEnabled,
                        value -> BeaconConfig.perPlayerLimitEnabled = value,
                        false,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.per_player_enable.tooltip")
                )
                .intValue(
                        "per_player_chunk_load_limit",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.per_player_limit"),
                        () -> BeaconConfig.perPlayerChunkLoadLimit,
                        value -> BeaconConfig.perPlayerChunkLoadLimit = value,
                        100,
                        0,
                        Integer.MAX_VALUE,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.per_player_limit.tooltip")
                )
                .section(Component.translatable("cfg.realmcontrol.beacon.beacon.offline.section"))
                .description(Component.translatable("cfg.realmcontrol.beacon.beacon.offline.description"))
                .intValue(
                        "offline_timeout",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_timeout"),
                        () -> BeaconConfig.beaconOfflineTimeout,
                        value -> BeaconConfig.beaconOfflineTimeout = value,
                        4320,
                        -1,
                        Integer.MAX_VALUE,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_timeout.tooltip")
                )
                .booleanValue(
                        "offline_deactivate",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_deactivate"),
                        () -> BeaconConfig.offlineDisableDeactivate,
                        value -> BeaconConfig.offlineDisableDeactivate = value,
                        true,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_deactivate.tooltip")
                )
                .booleanValue(
                        "offline_chunk_loading",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_cl"),
                        () -> BeaconConfig.offlineDisableChunkLoad,
                        value -> BeaconConfig.offlineDisableChunkLoad = value,
                        true,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_cl.tooltip")
                )
                .booleanValue(
                        "offline_spawn_prevention",
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_sp"),
                        () -> BeaconConfig.offlineDisableSpawnPrevent,
                        value -> BeaconConfig.offlineDisableSpawnPrevent = value,
                        true,
                        Component.translatable("cfg.realmcontrol.beacon.beacon.offline_sp.tooltip")
                )
                .build());
    }

    public static Screen create(Screen parent) {
        return KTConfigApi.createScreen(parent, PAGE_ID);
    }
}
