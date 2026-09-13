package dev.xyat.realmcontrol.worldgen.network;

import com.mojang.datafixers.util.Pair;
import dev.xyat.kineticcore.api.KTNetworkProtocol;
import dev.xyat.kineticcore.api.NetworkCompressUtil;
import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;
import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.StructureGenerationControl;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfig;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = WorldGenModule.MODID)
public class WorldGenNetwork {
    private static final String PROTOCOL_VERSION = "6";
    private static final int MAX_COMPRESSED_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_PAYLOAD_BYTES = 32 * 1024 * 1024;
    private static final int MAX_STRING_LIST_SIZE = 65536;
    private static final int MAX_RULE_LIST_SIZE = 32768;
    private static final int LOCATE_RADIUS_CHUNKS = 100;
    public static SimpleChannel CHANNEL;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(WorldGenModule.MODID, "worldgen"),
                () -> PROTOCOL_VERSION,
                KTNetworkProtocol::acceptsAnyVersion,
                KTNetworkProtocol::acceptsAnyVersion
        );

        int id = 0;
        CHANNEL.registerMessage(id++, OpenWorldGenGuiPacket.class, OpenWorldGenGuiPacket::toBytes, OpenWorldGenGuiPacket::decode, OpenWorldGenGuiPacket::handle);
        CHANNEL.registerMessage(id++, SaveWorldGenPacket.class, SaveWorldGenPacket::toBytes, SaveWorldGenPacket::decode, SaveWorldGenPacket::handle);
        CHANNEL.registerMessage(id++, SaveWorldGenResultPacket.class, SaveWorldGenResultPacket::toBytes, SaveWorldGenResultPacket::new, SaveWorldGenResultPacket::handle);
        CHANNEL.registerMessage(id++, RequestOpenWorldGenGuiPacket.class, RequestOpenWorldGenGuiPacket::toBytes, RequestOpenWorldGenGuiPacket::new, RequestOpenWorldGenGuiPacket::handle);
        CHANNEL.registerMessage(id++, RequestStructureRegistryPacket.class, RequestStructureRegistryPacket::toBytes, RequestStructureRegistryPacket::new, RequestStructureRegistryPacket::handle);
        CHANNEL.registerMessage(id++, StructureRegistryPacket.class, StructureRegistryPacket::toBytes, StructureRegistryPacket::decode, StructureRegistryPacket::handle);
        CHANNEL.registerMessage(id++, LocateStructurePacket.class, LocateStructurePacket::toBytes, LocateStructurePacket::new, LocateStructurePacket::handle);
        CHANNEL.registerMessage(id++, TeleportStructureDimensionPacket.class, TeleportStructureDimensionPacket::toBytes, TeleportStructureDimensionPacket::new, TeleportStructureDimensionPacket::handle);
        CHANNEL.registerMessage(id++, StructureActionResultPacket.class, StructureActionResultPacket::toBytes, StructureActionResultPacket::new, StructureActionResultPacket::handle);
        CHANNEL.registerMessage(id++, RequestOpenBiomeControlPacket.class, RequestOpenBiomeControlPacket::toBytes, RequestOpenBiomeControlPacket::new, RequestOpenBiomeControlPacket::handle);
        CHANNEL.registerMessage(id++, OpenBiomeControlPacket.class, OpenBiomeControlPacket::toBytes, OpenBiomeControlPacket::decode, OpenBiomeControlPacket::handle);
        CHANNEL.registerMessage(id, SaveBiomeControlPacket.class, SaveBiomeControlPacket::toBytes, SaveBiomeControlPacket::decode, SaveBiomeControlPacket::handle);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        WorldGenConfig.load();
    }

    private static void writeCompressedPayload(FriendlyByteBuf target, Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(payload);
            byte[] raw = new byte[payload.readableBytes()];
            payload.getBytes(payload.readerIndex(), raw);
            target.writeByteArray(NetworkCompressUtil.compressBytes(raw));
        } finally {
            payload.release();
        }
    }

    private static FriendlyByteBuf readCompressedPayload(FriendlyByteBuf source) {
        byte[] compressed = source.readByteArray(MAX_COMPRESSED_PAYLOAD_BYTES);
        byte[] raw = NetworkCompressUtil.decompressBytes(compressed, MAX_DECOMPRESSED_PAYLOAD_BYTES);
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> values) {
        List<String> safeValues = values == null ? List.of() : values;
        if (safeValues.size() > MAX_STRING_LIST_SIZE) {
            throw new IllegalArgumentException("Too many string values");
        }
        buf.writeVarInt(safeValues.size());
        for (String value : safeValues) {
            buf.writeUtf(value == null ? "" : value);
        }
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_STRING_LIST_SIZE) {
            throw new IllegalArgumentException("Invalid string list size");
        }
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(buf.readUtf(32767));
        }
        return result;
    }


    private static void writeBiomeRuleList(FriendlyByteBuf buf, List<BiomeReplacementRule> rules) {
        List<BiomeReplacementRule> safeRules = rules == null ? List.of() : rules;
        if (safeRules.size() > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Too many biome rules");
        }
        buf.writeVarInt(safeRules.size());
        for (BiomeReplacementRule rule : safeRules) {
            buf.writeUtf(rule == null ? "" : rule.dimensionId());
            buf.writeUtf(rule == null ? "" : rule.source());
            buf.writeUtf(rule == null ? "" : rule.target());
        }
    }

    private static List<BiomeReplacementRule> readBiomeRuleList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Invalid biome rule list size");
        }
        List<BiomeReplacementRule> rules = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            rules.add(new BiomeReplacementRule(buf.readUtf(32767), buf.readUtf(32767), buf.readUtf(32767)));
        }
        return rules;
    }

    public static void requestOpenEditor() {
        if (CHANNEL != null) {
            CHANNEL.sendToServer(new RequestOpenWorldGenGuiPacket());
        }
    }

    public static void requestOpenBiomeControl() {
        if (CHANNEL != null) {
            CHANNEL.sendToServer(new RequestOpenBiomeControlPacket());
        }
    }

    public static void requestStructureRegistryRefresh() {
        if (CHANNEL != null) {
            CHANNEL.sendToServer(new RequestStructureRegistryPacket());
        }
    }

    public static void requestLocateStructure(String structureId) {
        if (CHANNEL != null && structureId != null && !structureId.isBlank()) {
            CHANNEL.sendToServer(new LocateStructurePacket(structureId));
        }
    }

    public static void requestTeleportStructureDimension(String structureId) {
        if (CHANNEL != null && structureId != null && !structureId.isBlank()) {
            CHANNEL.sendToServer(new TeleportStructureDimensionPacket(structureId));
        }
    }

    public static void openEditorForPlayer(ServerPlayer player) {
        if (player == null || !player.hasPermissions(2)) {
            return;
        }
        WorldGenConfig.load();
        MinecraftServer server = player.server;
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenWorldGenGuiPacket(
                        WorldGenConfig.enableStructureBlocking,
                        getAllStructureIds(server),
                        StructureGenerationControl.getDescriptors(server)
                )
        );
    }

    private static List<String> getAllStructureIds(MinecraftServer server) {
        return server.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().stream()
                .map(ResourceLocation::toString)
                .sorted()
                .collect(Collectors.toList());
    }

    public record LocateStructurePacket(String structureId) {
        public LocateStructurePacket(FriendlyByteBuf buf) {
            this(buf.readUtf(32767));
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(structureId == null ? "" : structureId);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    handleLocateStructure(player, structureId);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record TeleportStructureDimensionPacket(String structureId) {
        public TeleportStructureDimensionPacket(FriendlyByteBuf buf) {
            this(buf.readUtf(32767));
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeUtf(structureId == null ? "" : structureId);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    handleTeleportStructureDimension(player, structureId);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record StructureActionResultPacket(Component message) {
        public StructureActionResultPacket(FriendlyByteBuf buf) {
            this(buf.readComponent());
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeComponent(message);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> WorldGenNetworkClient.handleStructureActionResult(message)
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    private static void handleLocateStructure(ServerPlayer player, String structureId) {
        if (!player.hasPermissions(2)) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.no_permission"));
            return;
        }

        Optional<Holder.Reference<Structure>> structureHolder = getStructureHolder(player.server, structureId);
        if (structureHolder.isEmpty()) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.invalid_structure", structureId));
            return;
        }

        List<String> dimensionIds = StructureGenerationControl.getStructureDimensions(player.server, structureId);
        if (dimensionIds.isEmpty()) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.dimension_unknown", structureId));
            return;
        }

        String currentDimensionId = player.serverLevel().dimension().location().toString();
        if (!dimensionIds.contains(currentDimensionId)) {
            String targetDimensionId = preferredDimensionId(dimensionIds, currentDimensionId);
            sendStructureActionResult(player, Component.translatable(
                    "msg.realmcontrol.worldgen.structure_action.wrong_dimension",
                    dimensionDisplay(targetDimensionId)
            ));
            return;
        }

        ServerLevel level = player.serverLevel();
        Pair<BlockPos, Holder<Structure>> result = level.getChunkSource().getGenerator().findNearestMapStructure(
                level,
                HolderSet.direct(List.of(structureHolder.get())),
                player.blockPosition(),
                LOCATE_RADIUS_CHUNKS,
                false
        );
        if (result == null || result.getFirst() == null) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.not_found", structureId));
            return;
        }

        BlockPos safePos = findSafeTeleportPosition(level, result.getFirst());
        if (safePos == null) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.no_safe_position"));
            return;
        }

        player.teleportTo(level, safePos.getX() + 0.5D, safePos.getY(), safePos.getZ() + 0.5D, player.getYRot(), player.getXRot());
        sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.located", structureId));
    }

    private static void handleTeleportStructureDimension(ServerPlayer player, String structureId) {
        if (!player.hasPermissions(2)) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.no_permission"));
            return;
        }

        if (getStructureHolder(player.server, structureId).isEmpty()) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.invalid_structure", structureId));
            return;
        }

        List<String> dimensionIds = StructureGenerationControl.getStructureDimensions(player.server, structureId);
        if (dimensionIds.isEmpty()) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.dimension_unknown", structureId));
            return;
        }

        String currentDimensionId = player.serverLevel().dimension().location().toString();
        String targetDimensionId = preferredDimensionId(dimensionIds, currentDimensionId);
        if (dimensionIds.contains(currentDimensionId)) {
            sendStructureActionResult(player, Component.translatable(
                    "msg.realmcontrol.worldgen.structure_action.already_in_dimension",
                    dimensionDisplay(currentDimensionId)
            ));
            return;
        }

        ResourceLocation targetLocation = ResourceLocation.tryParse(targetDimensionId);
        if (targetLocation == null) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.dimension_missing", targetDimensionId));
            return;
        }

        ServerLevel targetLevel = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, targetLocation));
        if (targetLevel == null) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.dimension_missing", dimensionDisplay(targetDimensionId)));
            return;
        }

        double scale = DimensionType.getTeleportationScale(player.serverLevel().dimensionType(), targetLevel.dimensionType());
        int targetX = clampWorldCoordinate(player.getX() * scale);
        int targetZ = clampWorldCoordinate(player.getZ() * scale);
        BlockPos safePos = findSafeTeleportPosition(targetLevel, new BlockPos(targetX, targetLevel.getSeaLevel(), targetZ));
        if (safePos == null) {
            sendStructureActionResult(player, Component.translatable("msg.realmcontrol.worldgen.structure_action.no_safe_position"));
            return;
        }

        player.teleportTo(targetLevel, safePos.getX() + 0.5D, safePos.getY(), safePos.getZ() + 0.5D, player.getYRot(), player.getXRot());
        sendStructureActionResult(player, Component.translatable(
                "msg.realmcontrol.worldgen.structure_action.dimension_teleported",
                dimensionDisplay(targetDimensionId)
        ));
    }

    private static Optional<Holder.Reference<Structure>> getStructureHolder(MinecraftServer server, String structureId) {
        ResourceLocation id = ResourceLocation.tryParse(structureId);
        if (id == null) {
            return Optional.empty();
        }
        return server.registryAccess().registryOrThrow(Registries.STRUCTURE)
                .getHolder(ResourceKey.create(Registries.STRUCTURE, id));
    }

    private static String preferredDimensionId(List<String> dimensionIds, String currentDimensionId) {
        if (dimensionIds.contains(currentDimensionId)) {
            return currentDimensionId;
        }
        return dimensionIds.get(0);
    }

    private static Component dimensionDisplay(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> Component.translatable("gui.realmcontrol.worldgen.dimension.minecraft.overworld");
            case "minecraft:the_nether" -> Component.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_nether");
            case "minecraft:the_end" -> Component.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_end");
            case "twilightforest:twilight_forest" -> Component.translatable("gui.realmcontrol.worldgen.dimension.twilightforest.twilight_forest");
            default -> Component.translatable("gui.realmcontrol.worldgen.dimension.modded", dimensionId);
        };
    }

    private static int clampWorldCoordinate(double value) {
        return (int) Math.floor(Math.max(-29_999_872.0D, Math.min(29_999_872.0D, value)));
    }

    private static BlockPos findSafeTeleportPosition(ServerLevel level, BlockPos center) {
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    Integer y = findSafeY(level, center.getX() + dx, center.getZ() + dz, center.getY());
                    if (y != null) {
                        return new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    }
                }
            }
        }
        return null;
    }

    private static Integer findSafeY(ServerLevel level, int x, int z, int preferredY) {
        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 2;
        int logicalTop = level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 8;
        int scanTop = level.dimensionType().hasCeiling() ? Math.min(maxY, logicalTop) : maxY;

        if (preferredY > minY + 2 && preferredY <= scanTop) {
            int localTop = Math.min(scanTop, preferredY + 16);
            int localBottom = Math.max(minY, preferredY - 32);
            for (int y = localTop; y >= localBottom; y--) {
                if (isSafeStandingPosition(level, x, y, z)) {
                    return y;
                }
            }
        }

        for (int y = scanTop; y >= minY; y--) {
            if (isSafeStandingPosition(level, x, y, z)) {
                return y;
            }
        }
        return null;
    }

    private static boolean isSafeStandingPosition(ServerLevel level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos floor = feet.below();
        BlockPos head = feet.above();
        BlockState floorState = level.getBlockState(floor);
        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);
        return floorState.isFaceSturdy(level, floor, Direction.UP)
                && floorState.getFluidState().isEmpty()
                && feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, head).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty();
    }

    private static void sendStructureActionResult(ServerPlayer player, Component message) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StructureActionResultPacket(message));
    }

    public record RequestOpenWorldGenGuiPacket() {
        public RequestOpenWorldGenGuiPacket(FriendlyByteBuf buf) {
            this();
        }

        public void toBytes(FriendlyByteBuf buf) {
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player != null && player.hasPermissions(2)) {
                    openEditorForPlayer(player);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record RequestStructureRegistryPacket() {
        public RequestStructureRegistryPacket(FriendlyByteBuf buf) {
            this();
        }

        public void toBytes(FriendlyByteBuf buf) {
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) {
                    return;
                }
                CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new StructureRegistryPacket(
                                getAllStructureIds(player.server),
                                StructureGenerationControl.getDescriptors(player.server)
                        )
                );
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record StructureRegistryPacket(List<String> structures, List<StructureRuleDescriptor> descriptors) {
        public static StructureRegistryPacket decode(FriendlyByteBuf buf) {
            FriendlyByteBuf payload = readCompressedPayload(buf);
            try {
                return new StructureRegistryPacket(readStringList(payload), readDescriptorList(payload));
            } finally {
                payload.release();
            }
        }

        public void toBytes(FriendlyByteBuf buf) {
            writeCompressedPayload(buf, payload -> {
                writeStringList(payload, structures);
                writeDescriptorList(payload, descriptors);
            });
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> WorldGenNetworkClient.handleStructureRegistry(structures, descriptors)
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public record OpenWorldGenGuiPacket(
            boolean structureBlockingEnable,
            List<String> allStructs,
            List<StructureRuleDescriptor> structureDescriptors
    ) {
        public static OpenWorldGenGuiPacket decode(FriendlyByteBuf buf) {
            FriendlyByteBuf payload = readCompressedPayload(buf);
            try {
                return new OpenWorldGenGuiPacket(
                        payload.readBoolean(),
                        readStringList(payload),
                        readDescriptorList(payload)
                );
            } finally {
                payload.release();
            }
        }

        public void toBytes(FriendlyByteBuf buf) {
            writeCompressedPayload(buf, payload -> {
                payload.writeBoolean(structureBlockingEnable);
                writeStringList(payload, allStructs);
                writeDescriptorList(payload, structureDescriptors);
            });
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> WorldGenNetworkClient.handleOpenGui(this)
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SaveWorldGenResultPacket(boolean success) {
        public SaveWorldGenResultPacket(FriendlyByteBuf buf) {
            this(buf.readBoolean());
        }

        public void toBytes(FriendlyByteBuf buf) {
            buf.writeBoolean(success);
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> WorldGenNetworkClient.handleSaveResult(success)
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SaveWorldGenPacket(
            boolean structureBlockingEnable,
            List<StructureEntryRule> entryRules,
            List<StructurePlacementRule> placementRules
    ) {
        public static SaveWorldGenPacket decode(FriendlyByteBuf buf) {
            FriendlyByteBuf payload = readCompressedPayload(buf);
            try {
                return new SaveWorldGenPacket(
                        payload.readBoolean(),
                        readEntryRuleList(payload),
                        readPlacementRuleList(payload)
                );
            } finally {
                payload.release();
            }
        }

        public void toBytes(FriendlyByteBuf buf) {
            writeCompressedPayload(buf, payload -> {
                payload.writeBoolean(structureBlockingEnable);
                writeEntryRuleList(payload, entryRules);
                writePlacementRuleList(payload, placementRules);
            });
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null) {
                    return;
                }
                if (!player.hasPermissions(2)) {
                    sendSaveResult(player, false);
                    return;
                }

                Set<String> allowedStructures = player.server.registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().stream()
                        .map(ResourceLocation::toString)
                        .collect(Collectors.toSet());
                Set<String> allowedStructureSets = player.server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).keySet().stream()
                        .map(ResourceLocation::toString)
                        .collect(Collectors.toSet());

                if (!validateEntryRules(entryRules, allowedStructures)
                        || !validatePlacementRules(placementRules, allowedStructureSets, StructureGenerationControl.getDescriptors(player.server))) {
                    sendSaveResult(player, false);
                    return;
                }

                try {
                    WorldGenConfig.enableStructureBlocking = structureBlockingEnable;
                    WorldGenConfig.structureEntryRules = sanitizeEntryRules(entryRules, allowedStructures);
                    WorldGenConfig.structurePlacementRules = sanitizePlacementRules(placementRules, allowedStructureSets);
                    WorldGenConfig.save();
                    sendSaveResult(player, true);
                } catch (Throwable throwable) {
                    WorldGenModule.LOGGER.error("Failed to save structure generation config", throwable);
                    sendSaveResult(player, false);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record RequestOpenBiomeControlPacket() {
        public RequestOpenBiomeControlPacket(FriendlyByteBuf ignored) {
            this();
        }

        public void toBytes(FriendlyByteBuf ignored) {
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) {
                    return;
                }
                sendBiomeControlEditor(player);
            });
            ctx.get().setPacketHandled(true);
        }
    }

    public record OpenBiomeControlPacket(
            boolean enabled,
            List<BiomeReplacementRule> rules,
            List<String> biomes,
            List<String> biomeTags,
            List<String> dimensions
    ) {
        public static OpenBiomeControlPacket decode(FriendlyByteBuf buf) {
            FriendlyByteBuf payload = readCompressedPayload(buf);
            try {
                return new OpenBiomeControlPacket(
                        payload.readBoolean(),
                        readBiomeRuleList(payload),
                        readStringList(payload),
                        readStringList(payload),
                        readStringList(payload)
                );
            } finally {
                payload.release();
            }
        }

        public void toBytes(FriendlyByteBuf buf) {
            writeCompressedPayload(buf, payload -> {
                payload.writeBoolean(enabled);
                writeBiomeRuleList(payload, rules);
                writeStringList(payload, biomes);
                writeStringList(payload, biomeTags);
                writeStringList(payload, dimensions);
            });
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> WorldGenNetworkClient.handleOpenBiomeControl(this)
            ));
            ctx.get().setPacketHandled(true);
        }
    }

    public record SaveBiomeControlPacket(boolean enabled, List<BiomeReplacementRule> rules) {
        public static SaveBiomeControlPacket decode(FriendlyByteBuf buf) {
            FriendlyByteBuf payload = readCompressedPayload(buf);
            try {
                return new SaveBiomeControlPacket(payload.readBoolean(), readBiomeRuleList(payload));
            } finally {
                payload.release();
            }
        }

        public void toBytes(FriendlyByteBuf buf) {
            writeCompressedPayload(buf, payload -> {
                payload.writeBoolean(enabled);
                writeBiomeRuleList(payload, rules);
            });
        }

        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || !player.hasPermissions(2)) {
                    if (player != null) sendSaveResult(player, false);
                    return;
                }
                if (!validateBiomeRules(player.server, rules)) {
                    sendSaveResult(player, false);
                    return;
                }
                try {
                    WorldGenConfig.enableBiomeControl = enabled;
                    WorldGenConfig.biomeReplacementRules = new ArrayList<>(rules);
                    WorldGenConfig.save();
                    sendSaveResult(player, true);
                } catch (Throwable throwable) {
                    WorldGenModule.LOGGER.error("Failed to save biome generation config", throwable);
                    sendSaveResult(player, false);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    private static void sendBiomeControlEditor(ServerPlayer player) {
        WorldGenConfig.load();
        Registry<Biome> biomeRegistry = player.server.registryAccess().registryOrThrow(Registries.BIOME);
        List<String> biomes = biomeRegistry.keySet().stream().map(ResourceLocation::toString).sorted().toList();
        List<String> tags = biomeRegistry.getTagNames().map(tag -> "#" + tag.location()).sorted().toList();
        List<String> dimensions = getSupportedBiomeControlDimensions(player.server);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenBiomeControlPacket(
                WorldGenConfig.enableBiomeControl,
                new ArrayList<>(WorldGenConfig.biomeReplacementRules),
                biomes,
                tags,
                dimensions
        ));
    }

    private static List<String> getSupportedBiomeControlDimensions(MinecraftServer server) {
        Registry<LevelStem> registry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        List<String> dimensions = new ArrayList<>();
        for (ResourceKey<LevelStem> key : registry.registryKeySet()) {
            LevelStem stem = registry.get(key);
            if (stem != null && stem.generator().getBiomeSource() instanceof MultiNoiseBiomeSource) {
                dimensions.add(key.location().toString());
            }
        }
        dimensions.sort(String::compareTo);
        return dimensions;
    }

    private static boolean validateBiomeRules(MinecraftServer server, List<BiomeReplacementRule> rules) {
        if (rules == null || rules.size() > MAX_RULE_LIST_SIZE) return false;
        Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        Set<String> biomeIds = biomeRegistry.keySet().stream().map(ResourceLocation::toString).collect(Collectors.toSet());
        Set<String> tagIds = biomeRegistry.getTagNames().map(tag -> "#" + tag.location()).collect(Collectors.toSet());
        Set<String> dimensionIds = new HashSet<>(getSupportedBiomeControlDimensions(server));
        Set<String> seen = new HashSet<>();
        for (BiomeReplacementRule rule : rules) {
            if (rule == null || rule.isEmpty()) return false;
            if (!BiomeReplacementRule.ALL_DIMENSIONS.equals(rule.dimensionId()) && !dimensionIds.contains(rule.dimensionId())) return false;
            if (!(biomeIds.contains(rule.source()) || tagIds.contains(rule.source()))) return false;
            if (!rule.isRemoval() && !biomeIds.contains(rule.target())) return false;
            String signature = rule.dimensionId() + "|" + rule.source();
            if (!seen.add(signature)) return false;
        }
        return true;
    }

    private static void sendSaveResult(ServerPlayer player, boolean success) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SaveWorldGenResultPacket(success));
    }

    private static boolean validateEntryRules(List<StructureEntryRule> rules, Set<String> allowedStructures) {
        if (rules == null) return false;
        Set<String> seen = new HashSet<>();
        for (StructureEntryRule rule : rules) {
            if (rule == null || !allowedStructures.contains(rule.structureId()) || !seen.add(rule.structureId())) return false;
            if (rule.weight() != null && rule.weight() <= 0) return false;
        }
        return true;
    }

    private static boolean validatePlacementRules(
            List<StructurePlacementRule> rules,
            Set<String> allowedStructureSets,
            List<StructureRuleDescriptor> descriptors
    ) {
        if (rules == null) return false;
        Map<String, StructureRuleDescriptor> descriptorBySet = new HashMap<>();
        for (StructureRuleDescriptor descriptor : descriptors) {
            if (!descriptor.structureSetId().isBlank()) descriptorBySet.putIfAbsent(descriptor.structureSetId(), descriptor);
        }

        Set<String> seen = new HashSet<>();
        for (StructurePlacementRule rule : rules) {
            if (rule == null || !allowedStructureSets.contains(rule.structureSetId()) || !seen.add(rule.structureSetId())) return false;
            if (rule.frequency() != null && (!Float.isFinite(rule.frequency()) || rule.frequency() < 0.0F || rule.frequency() > 1.0F)) return false;
            StructureRuleDescriptor descriptor = descriptorBySet.get(rule.structureSetId());
            if (descriptor == null) return false;

            if ("random_spread".equals(descriptor.placementType())) {
                int spacing = rule.spacing() != null ? rule.spacing() : descriptor.originalSpacing();
                int separation = rule.separation() != null ? rule.separation() : descriptor.originalSeparation();
                if (spacing <= 0 || separation < 0 || separation >= spacing) return false;
                if (rule.spreadType() != null
                        && !"linear".equals(rule.spreadType())
                        && !"triangular".equals(rule.spreadType())) return false;
                if (rule.distance() != null || rule.spread() != null || rule.count() != null) return false;
            } else if ("concentric_rings".equals(descriptor.placementType())) {
                int distance = rule.distance() != null ? rule.distance() : descriptor.originalDistance();
                int spread = rule.spread() != null ? rule.spread() : descriptor.originalSpread();
                int count = rule.count() != null ? rule.count() : descriptor.originalCount();
                if (distance <= 0 || spread <= 0 || count <= 0) return false;
                if (rule.spacing() != null || rule.separation() != null || rule.spreadType() != null) return false;
            } else {
                return false;
            }
        }
        return true;
    }

    private static Map<String, StructureEntryRule> sanitizeEntryRules(List<StructureEntryRule> rules, Set<String> allowed) {
        Map<String, StructureEntryRule> result = new LinkedHashMap<>();
        for (StructureEntryRule rule : rules) {
            if (rule != null && allowed.contains(rule.structureId()) && !rule.isEmpty()) result.put(rule.structureId(), rule);
        }
        return result;
    }

    private static Map<String, StructurePlacementRule> sanitizePlacementRules(List<StructurePlacementRule> rules, Set<String> allowed) {
        Map<String, StructurePlacementRule> result = new LinkedHashMap<>();
        for (StructurePlacementRule rule : rules) {
            if (rule != null && allowed.contains(rule.structureSetId()) && !rule.isEmpty()) result.put(rule.structureSetId(), rule);
        }
        return result;
    }

    private static void writeEntryRuleList(FriendlyByteBuf buf, List<StructureEntryRule> rules) {
        List<StructureEntryRule> safeRules = rules == null ? List.of() : rules;
        if (safeRules.size() > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Too many structure entry rules");
        }
        buf.writeVarInt(safeRules.size());
        for (StructureEntryRule rule : safeRules) writeEntryRule(buf, rule);
    }

    private static List<StructureEntryRule> readEntryRuleList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Invalid structure entry rule count");
        }
        List<StructureEntryRule> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(readEntryRule(buf));
        return result;
    }

    private static void writeEntryRule(FriendlyByteBuf buf, StructureEntryRule rule) {
        buf.writeUtf(rule.structureId());
        buf.writeBoolean(rule.disabled());
        writeNullableInt(buf, rule.weight());
    }

    private static StructureEntryRule readEntryRule(FriendlyByteBuf buf) {
        return new StructureEntryRule(buf.readUtf(), buf.readBoolean(), readNullableInt(buf));
    }

    private static void writePlacementRuleList(FriendlyByteBuf buf, List<StructurePlacementRule> rules) {
        List<StructurePlacementRule> safeRules = rules == null ? List.of() : rules;
        if (safeRules.size() > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Too many structure placement rules");
        }
        buf.writeVarInt(safeRules.size());
        for (StructurePlacementRule rule : safeRules) writePlacementRule(buf, rule);
    }

    private static List<StructurePlacementRule> readPlacementRuleList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Invalid structure placement rule count");
        }
        List<StructurePlacementRule> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(readPlacementRule(buf));
        return result;
    }

    private static void writePlacementRule(FriendlyByteBuf buf, StructurePlacementRule rule) {
        buf.writeUtf(rule.structureSetId());
        writeNullableFloat(buf, rule.frequency());
        writeNullableInt(buf, rule.salt());
        writeNullableInt(buf, rule.spacing());
        writeNullableInt(buf, rule.separation());
        writeNullableString(buf, rule.spreadType());
        writeNullableInt(buf, rule.distance());
        writeNullableInt(buf, rule.spread());
        writeNullableInt(buf, rule.count());
    }

    private static StructurePlacementRule readPlacementRule(FriendlyByteBuf buf) {
        return new StructurePlacementRule(
                buf.readUtf(),
                readNullableFloat(buf),
                readNullableInt(buf),
                readNullableInt(buf),
                readNullableInt(buf),
                readNullableString(buf),
                readNullableInt(buf),
                readNullableInt(buf),
                readNullableInt(buf)
        );
    }

    private static void writeDescriptorList(FriendlyByteBuf buf, List<StructureRuleDescriptor> descriptors) {
        List<StructureRuleDescriptor> safeDescriptors = descriptors == null ? List.of() : descriptors;
        if (safeDescriptors.size() > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Too many structure descriptors");
        }
        buf.writeVarInt(safeDescriptors.size());
        for (StructureRuleDescriptor descriptor : safeDescriptors) writeDescriptor(buf, descriptor);
    }

    private static List<StructureRuleDescriptor> readDescriptorList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_RULE_LIST_SIZE) {
            throw new IllegalArgumentException("Invalid structure descriptor count");
        }
        List<StructureRuleDescriptor> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(readDescriptor(buf));
        return result;
    }

    private static void writeDescriptor(FriendlyByteBuf buf, StructureRuleDescriptor descriptor) {
        buf.writeUtf(descriptor.structureId());
        buf.writeUtf(descriptor.structureSetId());
        buf.writeUtf(descriptor.placementType());
        buf.writeVarInt(descriptor.originalWeight());
        buf.writeFloat(descriptor.originalFrequency());
        buf.writeInt(descriptor.originalSalt());
        writeNullableInt(buf, descriptor.originalSpacing());
        writeNullableInt(buf, descriptor.originalSeparation());
        writeNullableString(buf, descriptor.originalSpreadType());
        writeNullableInt(buf, descriptor.originalDistance());
        writeNullableInt(buf, descriptor.originalSpread());
        writeNullableInt(buf, descriptor.originalCount());
        writeStringList(buf, descriptor.dimensionIds());
        buf.writeBoolean(descriptor.entryRule() != null);
        if (descriptor.entryRule() != null) writeEntryRule(buf, descriptor.entryRule());
        buf.writeBoolean(descriptor.placementRule() != null);
        if (descriptor.placementRule() != null) writePlacementRule(buf, descriptor.placementRule());
    }

    private static StructureRuleDescriptor readDescriptor(FriendlyByteBuf buf) {
        String structureId = buf.readUtf();
        String structureSetId = buf.readUtf();
        String placementType = buf.readUtf();
        int originalWeight = buf.readVarInt();
        float originalFrequency = buf.readFloat();
        int originalSalt = buf.readInt();
        Integer originalSpacing = readNullableInt(buf);
        Integer originalSeparation = readNullableInt(buf);
        String originalSpreadType = readNullableString(buf);
        Integer originalDistance = readNullableInt(buf);
        Integer originalSpread = readNullableInt(buf);
        Integer originalCount = readNullableInt(buf);
        List<String> dimensionIds = readStringList(buf);
        StructureEntryRule entryRule = buf.readBoolean() ? readEntryRule(buf) : null;
        StructurePlacementRule placementRule = buf.readBoolean() ? readPlacementRule(buf) : null;
        return new StructureRuleDescriptor(
                structureId, structureSetId, placementType, originalWeight, originalFrequency, originalSalt,
                originalSpacing, originalSeparation, originalSpreadType,
                originalDistance, originalSpread, originalCount,
                dimensionIds, entryRule, placementRule
        );
    }

    private static void writeNullableInt(FriendlyByteBuf buf, Integer value) {
        buf.writeBoolean(value != null);
        if (value != null) buf.writeInt(value);
    }

    private static Integer readNullableInt(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readInt() : null;
    }

    private static void writeNullableFloat(FriendlyByteBuf buf, Float value) {
        buf.writeBoolean(value != null);
        if (value != null) buf.writeFloat(value);
    }

    private static Float readNullableFloat(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readFloat() : null;
    }

    private static void writeNullableString(FriendlyByteBuf buf, String value) {
        buf.writeBoolean(value != null);
        if (value != null) buf.writeUtf(value);
    }

    private static String readNullableString(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUtf() : null;
    }
}
