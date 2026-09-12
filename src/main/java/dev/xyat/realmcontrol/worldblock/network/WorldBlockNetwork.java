package dev.xyat.realmcontrol.worldblock.network;

import dev.xyat.kineticcore.api.KTNetworkProtocol;
import dev.xyat.kineticcore.api.NetworkCompressUtil;
import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

@Mod.EventBusSubscriber(modid = WorldBlockModule.MODID)
public final class WorldBlockNetwork {
    private static final Logger LOGGER = LogManager.getLogger("realmcontrol/WorldBlockNetwork");
    private static final String PROTOCOL_VERSION = "2";
    private static final int MAX_COMPRESSED_CONFIG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_CONFIG_BYTES = 32 * 1024 * 1024;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(WorldBlockModule.MODID, "world_block_network"),
            () -> PROTOCOL_VERSION,
            KTNetworkProtocol::acceptsAnyVersion,
            KTNetworkProtocol::acceptsAnyVersion
    );

    public static final int EDITOR_MERGE_ORE = 0;
    public static final int EDITOR_BAN_ORE = 1;
    public static final int EDITOR_WEIGHTED_BLOCK = 2;

    private WorldBlockNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, OpenOreMergeGuiPacket.class, OpenOreMergeGuiPacket::encode, OpenOreMergeGuiPacket::decode, OpenOreMergeGuiPacket::handle);
        CHANNEL.registerMessage(id++, OpenWeightedBlockMergeGuiPacket.class, OpenWeightedBlockMergeGuiPacket::encode, OpenWeightedBlockMergeGuiPacket::decode, OpenWeightedBlockMergeGuiPacket::handle);
        CHANNEL.registerMessage(id++, OpenOreBannedGuiPacket.class, OpenOreBannedGuiPacket::encode, OpenOreBannedGuiPacket::decode, OpenOreBannedGuiPacket::handle);
        CHANNEL.registerMessage(id++, SaveWorldBlockConfigPacket.class, SaveWorldBlockConfigPacket::encode, SaveWorldBlockConfigPacket::decode, SaveWorldBlockConfigPacket::handle);
        CHANNEL.registerMessage(id++, SyncWorldBlockConfigPacket.class, SyncWorldBlockConfigPacket::encode, SyncWorldBlockConfigPacket::decode, SyncWorldBlockConfigPacket::handle);
        CHANNEL.registerMessage(id++, RequestOpenEditorPacket.class, RequestOpenEditorPacket::encode, RequestOpenEditorPacket::decode, RequestOpenEditorPacket::handle);
        CHANNEL.registerMessage(id, SaveWorldBlockConfigResultPacket.class, SaveWorldBlockConfigResultPacket::encode, SaveWorldBlockConfigResultPacket::decode, SaveWorldBlockConfigResultPacket::handle);
    }

    public static void requestOpenEditor(int editorType) {
        CHANNEL.sendToServer(new RequestOpenEditorPacket(editorType));
    }

    public record RequestOpenEditorPacket(int editorType) {
        public static void encode(RequestOpenEditorPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.editorType);
        }

        public static RequestOpenEditorPacket decode(FriendlyByteBuf buf) {
            return new RequestOpenEditorPacket(buf.readVarInt());
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) return;
                switch (editorType) {
                    case EDITOR_MERGE_ORE -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenOreMergeGuiPacket());
                    case EDITOR_WEIGHTED_BLOCK -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenWeightedBlockMergeGuiPacket());
                    case EDITOR_BAN_ORE -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenOreBannedGuiPacket());
                    default -> {
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        WorldBlockConfig.load();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sendServerConfigToPlayer(player, true);
        }
    }

    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player != null) {
            sendServerConfigToPlayer(player, true);
            return;
        }
        WorldBlockConfig.load();
        String json = WorldBlockConfig.getNetworkJson();
        for (ServerPlayer target : event.getPlayerList().getPlayers()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> target), new SyncWorldBlockConfigPacket(json));
        }
    }

    public static void sendServerConfigToPlayer(ServerPlayer player, boolean reloadFromFile) {
        if (player == null) return;
        try {
            if (reloadFromFile) WorldBlockConfig.load();
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SyncWorldBlockConfigPacket(WorldBlockConfig.getNetworkJson()));
        } catch (Throwable e) {
            LOGGER.error("Failed to sync world block config to player {}", player.getGameProfile().getName(), e);
        }
    }

    public static void syncServerConfigToAllPlayers() {
        try {
            CHANNEL.send(PacketDistributor.ALL.noArg(), new SyncWorldBlockConfigPacket(WorldBlockConfig.getNetworkJson()));
        } catch (Throwable e) {
            LOGGER.error("Failed to sync world block config to all players", e);
        }
    }

    public static final class OpenOreMergeGuiPacket {
        public static void encode(OpenOreMergeGuiPacket msg, FriendlyByteBuf buf) {
        }

        public static OpenOreMergeGuiPacket decode(FriendlyByteBuf buf) {
            return new OpenOreMergeGuiPacket();
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy::openOreMergeGui
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class OpenWeightedBlockMergeGuiPacket {
        public static void encode(OpenWeightedBlockMergeGuiPacket msg, FriendlyByteBuf buf) {
        }

        public static OpenWeightedBlockMergeGuiPacket decode(FriendlyByteBuf buf) {
            return new OpenWeightedBlockMergeGuiPacket();
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy::openWeightedBlockMergeGui
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class OpenOreBannedGuiPacket {
        public static void encode(OpenOreBannedGuiPacket msg, FriendlyByteBuf buf) {
        }

        public static OpenOreBannedGuiPacket decode(FriendlyByteBuf buf) {
            return new OpenOreBannedGuiPacket();
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy::openOreBannedGui
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class SaveWorldBlockConfigPacket {
        private final String jsonData;

        public SaveWorldBlockConfigPacket(String jsonData) {
            this.jsonData = jsonData;
        }

        public static void encode(SaveWorldBlockConfigPacket msg, FriendlyByteBuf buf) {
            buf.writeByteArray(NetworkCompressUtil.compress(msg.jsonData));
        }

        public static SaveWorldBlockConfigPacket decode(FriendlyByteBuf buf) {
            try {
                return new SaveWorldBlockConfigPacket(NetworkCompressUtil.decompress(buf.readByteArray(MAX_COMPRESSED_CONFIG_BYTES), MAX_DECOMPRESSED_CONFIG_BYTES));
            } catch (Throwable e) {
                LOGGER.error("Failed to decode world block save packet", e);
                return new SaveWorldBlockConfigPacket("");
            }
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) return;

                boolean success = player.hasPermissions(2)
                        && WorldBlockConfig.applyJson(jsonData, "server packet from " + player.getGameProfile().getName(), true);
                if (success) {
                                syncServerConfigToAllPlayers();
                } else {
                    sendServerConfigToPlayer(player, false);
                }
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SaveWorldBlockConfigResultPacket(success));
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record SaveWorldBlockConfigResultPacket(boolean success) {
        public static void encode(SaveWorldBlockConfigResultPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.success);
        }

        public static SaveWorldBlockConfigResultPacket decode(FriendlyByteBuf buffer) {
            return new SaveWorldBlockConfigResultPacket(buffer.readBoolean());
        }

        public static void handle(SaveWorldBlockConfigResultPacket packet, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.handleSaveResult(packet.success)
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public static final class SyncWorldBlockConfigPacket {
        private final String jsonData;

        public SyncWorldBlockConfigPacket(String jsonData) {
            this.jsonData = jsonData;
        }

        public static void encode(SyncWorldBlockConfigPacket msg, FriendlyByteBuf buf) {
            buf.writeByteArray(NetworkCompressUtil.compress(msg.jsonData));
        }

        public static SyncWorldBlockConfigPacket decode(FriendlyByteBuf buf) {
            try {
                return new SyncWorldBlockConfigPacket(NetworkCompressUtil.decompress(buf.readByteArray(MAX_COMPRESSED_CONFIG_BYTES), MAX_DECOMPRESSED_CONFIG_BYTES));
            } catch (Throwable e) {
                LOGGER.error("Failed to decode world block sync packet", e);
                return new SyncWorldBlockConfigPacket("");
            }
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.handleSyncWorldBlockConfig(jsonData)
            ));
            ctx.get().setPacketHandled(true);
        }
    }
}
