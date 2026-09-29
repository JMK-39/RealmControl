package dev.xyat.realmcontrol.beacon.config;

import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.config.client.KTConfigPage;
import dev.xyat.kineticcore.api.config.client.KTConfigScope;
import dev.xyat.kineticcore.api.text.KineticI18n;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

public final class BeaconConfigGui {
    public static final String PAGE_ID = "realmcontrol:beacon";

    private BeaconConfigGui() {
    }

    public static void load() {
        KTConfigApi.register(KTConfigPage.builder(
                        PAGE_ID,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon")
                )
                .pageDescription(KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.description"))
                .scope(KTConfigScope.SERVER_AUTHORITATIVE)
                .serverManaged()
                .applyTiming(KTConfigPage.ApplyTiming.MIXED)
                .applyNotice(KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.apply_notice"))
                .divider()
                .description(KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.chunk.description"))
                .booleanValue(
                        "enable_chunk_loading",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.chunk"),
                        () -> BeaconConfig.enableBeaconChunkLoading,
                        value -> BeaconConfig.enableBeaconChunkLoading = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.chunk.tooltip")
                )
                .intList(
                        "level_radii",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.radii"),
                        () -> BeaconConfig.beaconLevelRadii,
                        value -> BeaconConfig.beaconLevelRadii = value,
                        List.of(0, 1, 2, 3),
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.radii.tooltip")
                )
                .divider()
                .description(KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn.description"))
                .booleanValue(
                        "enable_spawn_prevention",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_prevent"),
                        () -> BeaconConfig.enableBeaconSpawnPrevention,
                        value -> BeaconConfig.enableBeaconSpawnPrevention = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_prevent.tooltip")
                )
                .entityList(
                        "spawn_whitelist",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_whitelist"),
                        () -> BeaconConfig.beaconSpawnWhitelist,
                        value -> BeaconConfig.beaconSpawnWhitelist = value,
                        List.of("minecraft:villager"),
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_whitelist.tooltip")
                )
                .entityList(
                        "spawn_blacklist",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_blacklist"),
                        () -> BeaconConfig.beaconSpawnBlacklist,
                        value -> BeaconConfig.beaconSpawnBlacklist = value,
                        List.of(),
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_blacklist.tooltip")
                )
                .stringList(
                        "spawn_rules",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_rules"),
                        () -> BeaconConfig.beaconRulesRaw,
                        value -> BeaconConfig.beaconRulesRaw = value,
                        List.of("minecraft:zombie;A", "minecraft:skeleton;AE"),
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.spawn_rules.tooltip")
                )
                .divider()
                .description(KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.limits.description"))
                .intValue(
                        "global_chunk_load_limit",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.global_limit"),
                        () -> BeaconConfig.globalChunkLoadLimit,
                        value -> BeaconConfig.globalChunkLoadLimit = value,
                        500,
                        0,
                        Integer.MAX_VALUE,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.global_limit.tooltip")
                )
                .booleanValue(
                        "per_player_limit_enabled",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.per_player_enable"),
                        () -> BeaconConfig.perPlayerLimitEnabled,
                        value -> BeaconConfig.perPlayerLimitEnabled = value,
                        false,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.per_player_enable.tooltip")
                )
                .intValue(
                        "per_player_chunk_load_limit",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.per_player_limit"),
                        () -> BeaconConfig.perPlayerChunkLoadLimit,
                        value -> BeaconConfig.perPlayerChunkLoadLimit = value,
                        100,
                        0,
                        Integer.MAX_VALUE,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.per_player_limit.tooltip")
                )
                .divider()
                .description(KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline.description"))
                .intValue(
                        "offline_timeout",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_timeout"),
                        () -> BeaconConfig.beaconOfflineTimeout,
                        value -> BeaconConfig.beaconOfflineTimeout = value,
                        4320,
                        -1,
                        Integer.MAX_VALUE,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_timeout.tooltip")
                )
                .booleanValue(
                        "offline_deactivate",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_deactivate"),
                        () -> BeaconConfig.offlineDisableDeactivate,
                        value -> BeaconConfig.offlineDisableDeactivate = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_deactivate.tooltip")
                )
                .booleanValue(
                        "offline_chunk_loading",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_cl"),
                        () -> BeaconConfig.offlineDisableChunkLoad,
                        value -> BeaconConfig.offlineDisableChunkLoad = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_cl.tooltip")
                )
                .booleanValue(
                        "offline_spawn_prevention",
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_sp"),
                        () -> BeaconConfig.offlineDisableSpawnPrevent,
                        value -> BeaconConfig.offlineDisableSpawnPrevent = value,
                        true,
                        KineticI18n.translatable("cfg.realmcontrol.beacon.beacon.offline_sp.tooltip")
                )
                .build());
    }

    public static void open() {
        KTConfigApi.openPage(PAGE_ID);
    }
}
