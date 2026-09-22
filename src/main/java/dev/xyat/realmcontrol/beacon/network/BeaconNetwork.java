package dev.xyat.realmcontrol.beacon.network;

import dev.xyat.realmcontrol.beacon.BeaconModule;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.client.BeaconGuiHandler;
import dev.xyat.realmcontrol.beacon.event.LevelChangedEvent;
import dev.xyat.realmcontrol.beacon.mixin.BeaconMenuAccessor;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.util.BeaconStateManager;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import dev.xyat.kineticcore.api.network.NetworkBuffer;
import dev.xyat.kineticcore.api.network.NetworkCodec;
import dev.xyat.kineticcore.api.network.NetworkVersionPolicy;
import dev.xyat.kineticcore.api.network.PacketChannel;
import dev.xyat.kineticcore.api.network.ServerPacketContext;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraftforge.common.MinecraftForge;

import java.util.UUID;

public class BeaconNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final int MAX_SPAWN_CODES_WIRE_LENGTH = 32;
    public static final PacketChannel CHANNEL = PacketChannel.create(
            new ResourceLocation(BeaconModule.MODID, "beacon"),
            PROTOCOL_VERSION,
            NetworkVersionPolicy.ANY
    );

    public static void register() {
        CHANNEL.registerServerbound(0, BeaconConfigPacket.class,
                NetworkCodec.of((buf, packet) -> packet.encode(buf), BeaconConfigPacket::decode),
                BeaconConfigPacket::handle);
        CHANNEL.registerClientbound(1, QuotaSyncPacket.class,
                NetworkCodec.of((buf, packet) -> packet.encode(buf), QuotaSyncPacket::decode),
                QuotaSyncPacket::handleClient);
        CHANNEL.registerClientbound(2, ServerConfigSyncPacket.class,
                NetworkCodec.of((buf, packet) -> packet.encode(buf), ServerConfigSyncPacket::decode),
                ServerConfigSyncPacket::handleClient);
        CHANNEL.registerClientbound(3, BeaconSaveResultPacket.class,
                NetworkCodec.of((buf, packet) -> packet.encode(buf), BeaconSaveResultPacket::decode),
                BeaconSaveResultPacket::handleClient);
    }

    private static String normalizeSpawnCodes(String raw) {
        if (raw == null || raw.length() > MAX_SPAWN_CODES_WIRE_LENGTH) return null;
        boolean[] seen = new boolean[8];
        StringBuilder normalized = new StringBuilder(8);
        for (int i = 0; i < raw.length(); i++) {
            char code = Character.toUpperCase(raw.charAt(i));
            if (code < 'A' || code > 'H') return null;
            int index = code - 'A';
            if (!seen[index]) {
                seen[index] = true;
                normalized.append(code);
            }
        }
        return normalized.toString();
    }

    private static boolean isRadiusDisallowed(int radius, int maximum) {
        return radius != -1 && (maximum < 0 || radius < 0 || radius > maximum);
    }

    private static void sendSaveResult(ServerPlayer player, boolean success) {
        if (player == null) return;
        CHANNEL.sendToPlayer(player, new BeaconSaveResultPacket(success));
    }

    public record BeaconSaveResultPacket(boolean success) {
        public static BeaconSaveResultPacket decode(NetworkBuffer buf) {
            return new BeaconSaveResultPacket(buf.readBoolean());
        }

        public void encode(NetworkBuffer buf) {
            buf.writeBoolean(success);
        }

        public void handleClient() {
            BeaconGuiHandler.handleSaveResult(success);
        }
    }

    public static void syncConfigToPlayer(ServerPlayer player) {
        CHANNEL.sendToPlayer(player, new ServerConfigSyncPacket(
                BeaconConfig.beaconOfflineTimeout,
                BeaconConfig.offlineDisableDeactivate,
                BeaconConfig.offlineDisableChunkLoad,
                BeaconConfig.offlineDisableSpawnPrevent,
                BeaconConfig.perPlayerLimitEnabled
        ));
    }

    public static void syncAllQuotas(MinecraftServer server) {
        BeaconStateManager state = BeaconStateManager.get(server);
        int globalUsed = state.getUsedQuota(null);
        int globalMax = BeaconConfig.globalChunkLoadLimit;
        int personalMax = BeaconConfig.perPlayerChunkLoadLimit;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int personalUsed = state.getUsedQuota(player.getUUID());
            CHANNEL.sendToPlayer(player, new QuotaSyncPacket(globalUsed, globalMax, personalUsed, personalMax));
        }
    }

    public record ServerConfigSyncPacket(int offlineTimeout, boolean deact, boolean cl, boolean sp, boolean perPlayer) {
        public static ServerConfigSyncPacket decode(NetworkBuffer buf) {
            return new ServerConfigSyncPacket(buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        }
        public void encode(NetworkBuffer buf) {
            buf.writeInt(offlineTimeout);
            buf.writeBoolean(deact);
            buf.writeBoolean(cl);
            buf.writeBoolean(sp);
            buf.writeBoolean(perPlayer);
        }
        public void handleClient() {
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.offlineTimeout = this.offlineTimeout;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.offlineDeact = this.deact;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.offlineCL = this.cl;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.offlineSP = this.sp;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.perPlayerEnabled = this.perPlayer;
        }
    }

    public record QuotaSyncPacket(int globalUsed, int globalMax, int personalUsed, int personalMax) {
        public static QuotaSyncPacket decode(NetworkBuffer buf) {
            return new QuotaSyncPacket(buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
        }
        public void encode(NetworkBuffer buf) {
            buf.writeInt(globalUsed);
            buf.writeInt(globalMax);
            buf.writeInt(personalUsed);
            buf.writeInt(personalMax);
        }
        public void handleClient() {
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.globalUsed = this.globalUsed;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.globalMax = this.globalMax;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.personalUsed = this.personalUsed;
            dev.xyat.realmcontrol.beacon.client.ClientQuotaCache.personalMax = this.personalMax;
        }
    }

    public record BeaconConfigPacket(boolean clEnabled, int clRad, boolean spEnabled, int spRad, int spType, String spCodes) {
        public static BeaconConfigPacket decode(NetworkBuffer buf) {
            return new BeaconConfigPacket(
                    buf.readBoolean(), buf.readInt(), buf.readBoolean(), buf.readInt(), buf.readInt(),
                    buf.readUtf(MAX_SPAWN_CODES_WIRE_LENGTH)
            );
        }

        public void encode(NetworkBuffer buf) {
            buf.writeBoolean(clEnabled);
            buf.writeInt(clRad);
            buf.writeBoolean(spEnabled);
            buf.writeInt(spRad);
            buf.writeInt(spType);
            buf.writeUtf(spCodes == null ? "" : spCodes, MAX_SPAWN_CODES_WIRE_LENGTH);
        }

        public void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
                if (!(player.containerMenu instanceof BeaconMenu menu) || !menu.stillValid(player)) {
                    sendSaveResult(player, false);
                    return;
                }

                ((BeaconMenuAccessor) menu).realmcontrol_beacon$getAccess().execute((level, pos) -> {
                    if (level.getBlockEntity(pos) instanceof BeaconBlockEntity beacon && beacon instanceof IBeaconAccess accessor) {
                        UUID owner = accessor.realmcontrol_beacon$getOwner();
                        if (owner != null
                                && !owner.equals(player.getUUID())
                                && !player.hasPermissions(2)) {
                            player.sendSystemMessage(Component.translatable("commands.generic.permission"));
                            sendSaveResult(player, false);
                            return;
                        }

                        int currentLevel = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();
                        int maxRadius = BeaconConfig.getBeaconRadius(currentLevel);
                        int maxPreventRadius = maxRadius >= 0 ? maxRadius + 1 : -1;
                        String normalizedCodes = normalizeSpawnCodes(this.spCodes);
                        if (isRadiusDisallowed(this.clRad, maxRadius)
                                || isRadiusDisallowed(this.spRad, maxPreventRadius)
                                || this.spType < 0 || this.spType > 2
                                || normalizedCodes == null) {
                            BeaconModule.LOGGER.warn("Rejected invalid beacon settings from {}", player.getGameProfile().getName());
                            sendSaveResult(player, false);
                            return;
                        }

                        // Preserve the placement owner so quota/offline accounting cannot
                        // be transferred merely by opening and saving another player's beacon.
                        if (owner == null) {
                            accessor.realmcontrol_beacon$setOwner(player.getUUID());
                        }
                        accessor.realmcontrol_beacon$setChunkLoadEnabled(this.clEnabled);
                        accessor.realmcontrol_beacon$setChunkLoadRadius(this.clRad);
                        accessor.realmcontrol_beacon$setSpawnPreventEnabled(this.spEnabled);
                        accessor.realmcontrol_beacon$setSpawnPreventRadius(this.spRad);
                        accessor.realmcontrol_beacon$setSpawnPreventType(this.spType);
                        accessor.realmcontrol_beacon$setSpawnPreventCodes(normalizedCodes);

                        beacon.setChanged();
                        level.sendBlockUpdated(pos, beacon.getBlockState(), beacon.getBlockState(), 3);

                        MinecraftForge.EVENT_BUS.post(new LevelChangedEvent(level, pos, beacon, currentLevel, currentLevel));

                        if (this.spEnabled && currentLevel > 0) {
                            int max = dev.xyat.realmcontrol.beacon.config.BeaconConfig.getBeaconRadius(currentLevel);
                            int maxPrevent = max >= 0 ? max + 1 : -1;
                            int act = accessor.realmcontrol_beacon$getActualSpawnPreventRadius(currentLevel, maxPrevent);
                            if (act >= 0) {
                                dev.xyat.realmcontrol.beacon.event.SpawnPreventionHandler.updateBeacon(level, pos, act, this.spType, normalizedCodes);
                            } else {
                                dev.xyat.realmcontrol.beacon.event.SpawnPreventionHandler.removeBeacon(level, pos);
                            }
                        } else {
                            dev.xyat.realmcontrol.beacon.event.SpawnPreventionHandler.removeBeacon(level, pos);
                        }
                        sendSaveResult(player, true);
                    } else {
                        sendSaveResult(player, false);
                    }
                });
        }
    }
}
