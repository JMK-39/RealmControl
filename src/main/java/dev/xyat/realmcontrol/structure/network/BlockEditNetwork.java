package dev.xyat.realmcontrol.structure.network;

import dev.xyat.kineticcore.api.network.NetworkBuffer;
import dev.xyat.kineticcore.api.network.NetworkCodec;
import dev.xyat.kineticcore.api.network.NetworkVersionPolicy;
import dev.xyat.kineticcore.api.network.PacketChannel;
import dev.xyat.kineticcore.api.network.ServerPacketContext;
import dev.xyat.kineticcore.api.network.KineticCompression;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.client.gui.BlockEditPage;
import dev.xyat.realmcontrol.structure.client.gui.MarkersPage;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.InventoryEdits;
import dev.xyat.realmcontrol.structure.project.ProjectPreview;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.worldgen.preview.StructureThumbnailService;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Saves a single exact block from the structure editor without loading or changing its source world. */
public final class BlockEditNetwork {
    public static final int MAX_STATE = 2048;
    public static final int MAX_INVENTORY = 65_536;
    private static final int MAX_INVENTORY_BYTES = 12_000;
    private static final AtomicInteger NEXT_REQUEST = new AtomicInteger();
    private static final String MESSAGE = "msg.realmcontrol.structure.block_edit.";
    private static final PacketChannel CHANNEL = PacketChannel.create(
            KineticResourceIds.of(StructureEditorModule.MODID, "structure_block_edit"), "1", NetworkVersionPolicy.ANY);
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
        Thread thread = new Thread(task, "RealmControl structure block edits");
        thread.setDaemon(true);
        return thread;
    });

    private BlockEditNetwork() { }

    public static void register() {
        CHANNEL.registerServerbound(0, Edit.class, NetworkCodec.of((buffer, edit) -> edit.write(buffer), Edit::read), Edit::handle);
        CHANNEL.registerClientbound(1, Reply.class, NetworkCodec.of((buffer, reply) -> reply.write(buffer), Reply::read), Reply::handleClient);
    }

    public static void save(int request, StructureProject project, BlockPos pos, String expected, String replacement) {
        CHANNEL.sendToServer(new Edit(request, project.id, project.scannedAt, pos, false, expected, replacement));
    }

    public static int requestId() {
        return NEXT_REQUEST.incrementAndGet();
    }

    public static void saveInventory(int request, StructureProject project, BlockPos pos, String expected, String replacement) {
        CHANNEL.sendToServer(new Edit(request, project.id, project.scannedAt, pos, true, expected, replacement));
    }

    private record Edit(int request, String project, long scannedAt, BlockPos pos, boolean inventory, String expected, String replacement) {
        static Edit read(NetworkBuffer buffer) {
            int request = buffer.readVarInt();
            String project = buffer.readUtf(256);
            long scannedAt = buffer.readLong();
            BlockPos pos = new BlockPos(buffer.readInt(), buffer.readInt(), buffer.readInt());
            boolean inventory = buffer.readBoolean();
            return new Edit(request, project, scannedAt, pos, inventory, readValue(buffer, inventory), readValue(buffer, inventory));
        }

        void write(NetworkBuffer buffer) {
            buffer.writeVarInt(request);
            buffer.writeUtf(project, 256);
            buffer.writeLong(scannedAt);
            buffer.writeInt(pos.getX());
            buffer.writeInt(pos.getY());
            buffer.writeInt(pos.getZ());
            buffer.writeBoolean(inventory);
            writeValue(buffer, expected, inventory);
            writeValue(buffer, replacement, inventory);
        }

        private static String readValue(NetworkBuffer buffer, boolean inventory) {
            return inventory ? KineticCompression.decompressUtf8(buffer.readByteArray(MAX_INVENTORY_BYTES), MAX_INVENTORY)
                    : buffer.readUtf(MAX_STATE);
        }

        private static void writeValue(NetworkBuffer buffer, String value, boolean inventory) {
            if (inventory) buffer.writeByteArray(KineticCompression.compressUtf8(value, MAX_INVENTORY_BYTES), MAX_INVENTORY_BYTES);
            else buffer.writeUtf(value, MAX_STATE);
        }

        void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (!StructureEditorModule.allowed(player)) {
                CHANNEL.sendToPlayer(player, error("permission"));
                return;
            }
            if (ScanService.busy(project)) {
                CHANNEL.sendToPlayer(player, error("busy"));
                return;
            }
            MinecraftServer server = player.getServer();
            UUID owner = player.getUUID();
            try {
                WRITER.execute(() -> {
                    Reply reply = apply();
                    server.execute(() -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(owner);
                        if (reply.saved()) StructureThumbnailService.forget(server, ProjectPreview.PREFIX + project);
                        if (target == null) return;
                        CHANNEL.sendToPlayer(target, reply);
                        if (reply.saved()) StructureEditorNetwork.sendProjects(target, project);
                    });
                });
            } catch (RejectedExecutionException busy) {
                CHANNEL.sendToPlayer(player, error("busy"));
            }
        }

        private Reply apply() {
            try (ProjectStore.Draft draft = ProjectStore.openDraft(project)) {
                if (draft == null || draft.project().scannedAt != scannedAt) return error("conflict");
                ProjectStore.BlockEditStatus result;
                if (inventory) {
                    CompoundTag before = InventoryEdits.parse(expected), after = InventoryEdits.parse(replacement);
                    if (before == null || after == null) return error("invalid_items");
                    result = ProjectStore.replaceInventory(draft, pos, before, after);
                } else {
                    BlockState state = BlockStates.parse(replacement);
                    if (state == null) return error("invalid");
                    BlockState original = BlockStates.parse(expected);
                    if (state.hasBlockEntity() || original != null && original.hasBlockEntity()) return error("protected");
                    String normalized = state.isAir() ? BlockStates.AIR : BlockStates.serialize(state);
                    result = ProjectStore.replaceBlock(draft, pos, expected, normalized);
                }
                return switch (result) {
                    case SAVED -> new Reply(request, true, draft.project().blockRevision + 1, KineticI18n.translatable(MESSAGE + "saved"));
                    case SAME -> error("unchanged");
                    case OUTSIDE, CONFLICT -> error("conflict");
                    case PROTECTED -> error("protected");
                    case PALETTE_FULL -> error("palette_full");
                };
            } catch (IOException | RuntimeException failure) {
                return new Reply(request, false, 0, KineticI18n.translatable(MESSAGE + "failed", String.valueOf(failure.getMessage())));
            }
        }

        private Reply error(String key) {
            return new Reply(request, false, 0, KineticI18n.translatable(MESSAGE + key));
        }
    }

    public record Reply(int request, boolean saved, int revision, Component message) {
        static Reply read(NetworkBuffer buffer) {
            return new Reply(buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(), buffer.readComponent());
        }

        void write(NetworkBuffer buffer) {
            buffer.writeVarInt(request);
            buffer.writeBoolean(saved);
            buffer.writeVarInt(revision);
            buffer.writeComponent(message);
        }

        void handleClient() {
            BlockEditPage.receive(this);
            MarkersPage.receiveInventory(this);
        }
    }
}
