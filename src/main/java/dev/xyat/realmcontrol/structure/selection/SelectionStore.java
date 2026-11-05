package dev.xyat.realmcontrol.structure.selection;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps every player's selections on the server, one per dimension, saved with the world so they survive
 * reconnecting and restarts. Each change is sent back to the player for drawing.
 */
public final class SelectionStore {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** How far from the player a corner may be set: the controller reaches 512 blocks. */
    private static final double MAX_REACH = 600.0D;
    private static final Map<UUID, Map<String, Selection>> SELECTIONS = new HashMap<>();

    /** What a controller click or wheel turn does to the selection. */
    public enum Action {
        SET_A, SET_B, CUT_A, CUT_B, PUSH, PUSH_CUTOUT, DELETE_CUTOUT, CLEAR, CLEAR_CUTOUTS
    }

    private SelectionStore() {
    }

    /** The player's selection in their current dimension; never {@code null}. */
    public static synchronized Selection get(ServerPlayer player) {
        return SELECTIONS.computeIfAbsent(player.getUUID(), id -> new HashMap<>())
                .computeIfAbsent(dimension(player), id -> new Selection());
    }

    /**
     * Applies one controller action. {@code pos} is the clicked block for corners, {@code arg} the face for
     * {@link Action#PUSH} and {@link Action#PUSH_CUTOUT} (with {@code amount} blocks; the latter moves the newest
     * cut-out box) and the box index for {@link Action#DELETE_CUTOUT}.
     */
    public static void apply(ServerPlayer player, Action action, @Nullable BlockPos pos, int arg, int amount) {
        if (!StructureEditorModule.allowed(player)) {
            Component message = KineticI18n.translatable("msg.realmcontrol.structure.no_permission");
            player.displayClientMessage(message, true);
            return;
        }
        if (pos != null && pos.distToCenterSqr(player.position()) > MAX_REACH * MAX_REACH) return;
        Selection selection = get(player);
        Component message = null;
        synchronized (SelectionStore.class) {
            switch (action) {
                case SET_A -> {
                    if (pos == null) return;
                    selection.a = pos.immutable();
                    message = cornerMessage("a", pos, selection);
                }
                case SET_B -> {
                    if (pos == null) return;
                    selection.b = pos.immutable();
                    message = cornerMessage("b", pos, selection);
                }
                case CUT_A -> {
                    if (pos == null) return;
                    selection.cutA = pos.immutable();
                    message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.corner_a", pos.getX(), pos.getY(), pos.getZ());
                }
                case CUT_B -> {
                    if (pos == null) return;
                    if (selection.cutA == null) {
                        message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.need_a");
                    } else if (selection.cutouts.size() >= Selection.MAX_CUTOUTS) {
                        message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.full", Selection.MAX_CUTOUTS);
                    } else {
                        Selection.Box box = Selection.Box.of(selection.cutA, pos);
                        selection.cutouts.add(box);
                        selection.cutA = null;
                        message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.added", selection.cutouts.size(),
                                box.sizeX(), box.sizeY(), box.sizeZ());
                    }
                }
                case PUSH -> {
                    Selection.Box region = selection.region();
                    if (region == null || arg < 0 || arg >= Direction.values().length) return;
                    Selection.Box pushed = region.push(Direction.values()[arg], Math.max(-64, Math.min(64, amount)));
                    selection.a = pushed.min();
                    selection.b = pushed.max();
                    message = sizeMessage(selection);
                }
                case PUSH_CUTOUT -> {
                    if (selection.cutouts.isEmpty() || arg < 0 || arg >= Direction.values().length) return;
                    int last = selection.cutouts.size() - 1;
                    Selection.Box pushed = selection.cutouts.get(last).push(Direction.values()[arg], Math.max(-64, Math.min(64, amount)));
                    selection.cutouts.set(last, pushed);
                    message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.size", last + 1, pushed.sizeX(), pushed.sizeY(), pushed.sizeZ());
                }
                case DELETE_CUTOUT -> {
                    if (arg < 0 || arg >= selection.cutouts.size()) return;
                    selection.cutouts.remove(arg);
                    message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.removed", selection.cutouts.size());
                }
                case CLEAR -> {
                    selection.a = null;
                    selection.b = null;
                    selection.cutA = null;
                    selection.cutouts.clear();
                    message = KineticI18n.translatable("msg.realmcontrol.structure.selection.cleared");
                }
                case CLEAR_CUTOUTS -> {
                    selection.cutA = null;
                    selection.cutouts.clear();
                    message = KineticI18n.translatable("msg.realmcontrol.structure.cutout.cleared");
                }
            }
        }
        if (message != null) player.displayClientMessage(message, true);
        sync(player);
        save(player.getServer());
    }

    private static Component cornerMessage(String corner, BlockPos pos, Selection selection) {
        Selection.Box region = selection.region();
        if (region == null) {
            return KineticI18n.translatable("msg.realmcontrol.structure.selection.corner_" + corner, pos.getX(), pos.getY(), pos.getZ());
        }
        return KineticI18n.translatable("msg.realmcontrol.structure.selection.corner_" + corner + "_size", pos.getX(), pos.getY(), pos.getZ(),
                region.sizeX(), region.sizeY(), region.sizeZ());
    }

    private static Component sizeMessage(Selection selection) {
        Selection.Box region = selection.region();
        return region == null ? Component.empty() : KineticI18n.translatable("msg.realmcontrol.structure.selection.size",
                region.sizeX(), region.sizeY(), region.sizeZ());
    }

    /** Sends the player their selection in their current dimension. */
    public static void sync(ServerPlayer player) {
        StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.SelectionPacket(get(player)));
    }

    private static String dimension(ServerPlayer player) {
        return player.level().dimension().location().toString();
    }

    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("realmcontrol").resolve("structure_selections.json");
    }

    public static synchronized void load(MinecraftServer server) {
        SELECTIONS.clear();
        Path file = file(server);
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> player : root.entrySet()) {
                Map<String, Selection> byDimension = new HashMap<>();
                for (Map.Entry<String, JsonElement> dimension : player.getValue().getAsJsonObject().entrySet()) {
                    byDimension.put(dimension.getKey(), Selection.fromJson(dimension.getValue().getAsJsonObject()));
                }
                SELECTIONS.put(UUID.fromString(player.getKey()), byDimension);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read structure selections from {}", file, e);
        }
    }

    public static synchronized void save(@Nullable MinecraftServer server) {
        if (server == null) return;
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, Map<String, Selection>> player : SELECTIONS.entrySet()) {
            JsonObject byDimension = new JsonObject();
            for (Map.Entry<String, Selection> dimension : player.getValue().entrySet()) {
                if (!dimension.getValue().isEmpty()) byDimension.add(dimension.getKey(), dimension.getValue().toJson());
            }
            if (byDimension.size() > 0) root.add(player.getKey().toString(), byDimension);
        }
        Path file = file(server);
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.warn("Could not save structure selections to {}", file, e);
        }
    }
}
