package dev.xyat.realmcontrol.worldblock.network;

import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.network.KineticCompression;
import dev.xyat.kineticcore.api.network.NetworkBuffer;
import dev.xyat.kineticcore.api.network.NetworkCodec;
import dev.xyat.kineticcore.api.network.NetworkVersionPolicy;
import dev.xyat.kineticcore.api.network.PacketChannel;
import dev.xyat.kineticcore.api.network.ServerPacketContext;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class WorldBlockNetwork {
    private static final Logger LOGGER = LogManager.getLogger("realmcontrol/WorldBlockNetwork");
    private static final String PROTOCOL_VERSION = "2";
    private static final int MAX_COMPRESSED_CONFIG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_CONFIG_BYTES = 32 * 1024 * 1024;
    private static boolean eventsInstalled;

    public static final PacketChannel CHANNEL = PacketChannel.create(
            new ResourceLocation(WorldBlockModule.MODID, "world_block_network"),
            PROTOCOL_VERSION,
            NetworkVersionPolicy.ANY
    );

    public static final int EDITOR_MERGE_ORE = 0;
    public static final int EDITOR_BAN_ORE = 1;
    public static final int EDITOR_WEIGHTED_BLOCK = 2;

    private WorldBlockNetwork() {
    }

    public static void register() {
        CHANNEL.registerClientbound(0, OpenOreMergeGuiPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), OpenOreMergeGuiPacket::decode),
                OpenOreMergeGuiPacket::handleClient);
        CHANNEL.registerClientbound(1, OpenWeightedBlockMergeGuiPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), OpenWeightedBlockMergeGuiPacket::decode),
                OpenWeightedBlockMergeGuiPacket::handleClient);
        CHANNEL.registerClientbound(2, OpenOreBannedGuiPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), OpenOreBannedGuiPacket::decode),
                OpenOreBannedGuiPacket::handleClient);
        CHANNEL.registerServerbound(3, SaveWorldBlockConfigPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), SaveWorldBlockConfigPacket::decode),
                SaveWorldBlockConfigPacket::handle);
        CHANNEL.registerClientbound(4, SyncWorldBlockConfigPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), SyncWorldBlockConfigPacket::decode),
                SyncWorldBlockConfigPacket::handleClient);
        CHANNEL.registerServerbound(5, RequestOpenEditorPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), RequestOpenEditorPacket::decode),
                RequestOpenEditorPacket::handle);
        CHANNEL.registerClientbound(6, SaveWorldBlockConfigResultPacket.class,
                NetworkCodec.of((buf, msg) -> msg.encode(buf), SaveWorldBlockConfigResultPacket::decode),
                SaveWorldBlockConfigResultPacket::handleClient);
        installEvents();
    }

    private static void installEvents() {
        if (eventsInstalled) return;
        eventsInstalled = true;
        KineticServerEvents.onAboutToStart(KineticEventPriority.NORMAL, server -> onServerAboutToStart());
        KineticServerEvents.onPlayerLogin(KineticEventPriority.NORMAL, WorldBlockNetwork::onPlayerLoggedIn);
        KineticServerEvents.onDatapackSync(KineticEventPriority.NORMAL, WorldBlockNetwork::onDatapackSync);
    }

    public static void requestOpenEditor(int editorType) {
        CHANNEL.sendToServer(new RequestOpenEditorPacket(editorType));
    }

    public record RequestOpenEditorPacket(int editorType) {
        public void encode(NetworkBuffer buf) {
            buf.writeVarInt(editorType);
        }

        public static RequestOpenEditorPacket decode(NetworkBuffer buf) {
            return new RequestOpenEditorPacket(buf.readVarInt());
        }

        public void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (!player.hasPermissions(2)) return;
            switch (editorType) {
                case EDITOR_MERGE_ORE -> CHANNEL.sendToPlayer(player, new OpenOreMergeGuiPacket());
                case EDITOR_WEIGHTED_BLOCK -> CHANNEL.sendToPlayer(player, new OpenWeightedBlockMergeGuiPacket());
                case EDITOR_BAN_ORE -> CHANNEL.sendToPlayer(player, new OpenOreBannedGuiPacket());
                default -> {
                }
            }
        }
    }

    private static void onServerAboutToStart() {
        WorldBlockConfig.load();
    }

    private static void onPlayerLoggedIn(ServerPlayer player) {
        sendServerConfigToPlayer(player, true);
    }

    private static void onDatapackSync(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        if (player != null) {
            sendServerConfigToPlayer(player, true);
            return;
        }
        WorldBlockConfig.load();
        String json = WorldBlockConfig.getNetworkJson();
        for (ServerPlayer target : server.getPlayerList().getPlayers()) {
            CHANNEL.sendToPlayer(target, new SyncWorldBlockConfigPacket(json));
        }
    }

    public static void sendServerConfigToPlayer(ServerPlayer player, boolean reloadFromFile) {
        if (player == null) return;
        try {
            if (reloadFromFile) WorldBlockConfig.load();
            CHANNEL.sendToPlayer(player, new SyncWorldBlockConfigPacket(WorldBlockConfig.getNetworkJson()));
        } catch (Throwable e) {
            LOGGER.error("Failed to sync world block config to player {}", player.getGameProfile().getName(), e);
        }
    }

    public static void syncServerConfigToAllPlayers() {
        try {
            CHANNEL.broadcast(new SyncWorldBlockConfigPacket(WorldBlockConfig.getNetworkJson()));
        } catch (Throwable e) {
            LOGGER.error("Failed to sync world block config to all players", e);
        }
    }

    public static final class OpenOreMergeGuiPacket {
        public void encode(NetworkBuffer buf) {
        }

        public static OpenOreMergeGuiPacket decode(NetworkBuffer buf) {
            return new OpenOreMergeGuiPacket();
        }

        public void handleClient() {
            dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.openOreMergeGui();
        }
    }

    public static final class OpenWeightedBlockMergeGuiPacket {
        public void encode(NetworkBuffer buf) {
        }

        public static OpenWeightedBlockMergeGuiPacket decode(NetworkBuffer buf) {
            return new OpenWeightedBlockMergeGuiPacket();
        }

        public void handleClient() {
            dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.openWeightedBlockMergeGui();
        }
    }

    public static final class OpenOreBannedGuiPacket {
        public void encode(NetworkBuffer buf) {
        }

        public static OpenOreBannedGuiPacket decode(NetworkBuffer buf) {
            return new OpenOreBannedGuiPacket();
        }

        public void handleClient() {
            dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.openOreBannedGui();
        }
    }

    public static final class SaveWorldBlockConfigPacket {
        private final String jsonData;

        public SaveWorldBlockConfigPacket(String jsonData) {
            this.jsonData = jsonData;
        }

        public void encode(NetworkBuffer buf) {
            buf.writeByteArray(KineticCompression.compressUtf8(jsonData, MAX_COMPRESSED_CONFIG_BYTES, MAX_DECOMPRESSED_CONFIG_BYTES), MAX_COMPRESSED_CONFIG_BYTES);
        }

        public static SaveWorldBlockConfigPacket decode(NetworkBuffer buf) {
            try {
                return new SaveWorldBlockConfigPacket(KineticCompression.decompressUtf8(buf.readByteArray(MAX_COMPRESSED_CONFIG_BYTES), MAX_DECOMPRESSED_CONFIG_BYTES));
            } catch (Throwable e) {
                LOGGER.error("Failed to decode world block save packet", e);
                return new SaveWorldBlockConfigPacket("");
            }
        }

        public void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            boolean success = player.hasPermissions(2)
                    && WorldBlockConfig.applyJson(jsonData, "server packet from " + player.getGameProfile().getName(), true);
            if (success) {
                syncServerConfigToAllPlayers();
            } else {
                sendServerConfigToPlayer(player, false);
            }
            CHANNEL.sendToPlayer(player, new SaveWorldBlockConfigResultPacket(success));
        }
    }

    public record SaveWorldBlockConfigResultPacket(boolean success) {
        public void encode(NetworkBuffer buffer) {
            buffer.writeBoolean(success);
        }

        public static SaveWorldBlockConfigResultPacket decode(NetworkBuffer buffer) {
            return new SaveWorldBlockConfigResultPacket(buffer.readBoolean());
        }

        public void handleClient() {
            dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.handleSaveResult(success);
        }
    }

    public static final class SyncWorldBlockConfigPacket {
        private final String jsonData;

        public SyncWorldBlockConfigPacket(String jsonData) {
            this.jsonData = jsonData;
        }

        public void encode(NetworkBuffer buf) {
            buf.writeByteArray(KineticCompression.compressUtf8(jsonData, MAX_COMPRESSED_CONFIG_BYTES, MAX_DECOMPRESSED_CONFIG_BYTES), MAX_COMPRESSED_CONFIG_BYTES);
        }

        public static SyncWorldBlockConfigPacket decode(NetworkBuffer buf) {
            try {
                return new SyncWorldBlockConfigPacket(KineticCompression.decompressUtf8(buf.readByteArray(MAX_COMPRESSED_CONFIG_BYTES), MAX_DECOMPRESSED_CONFIG_BYTES));
            } catch (Throwable e) {
                LOGGER.error("Failed to decode world block sync packet", e);
                return new SyncWorldBlockConfigPacket("");
            }
        }

        public void handleClient() {
            dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.handleSyncWorldBlockConfig(jsonData);
        }
    }
}
