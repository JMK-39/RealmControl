package dev.xyat.realmcontrol.structure.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.network.KineticCompression;
import dev.xyat.kineticcore.api.network.NetworkBuffer;
import dev.xyat.kineticcore.api.network.NetworkCodec;
import dev.xyat.kineticcore.api.network.NetworkVersionPolicy;
import dev.xyat.kineticcore.api.network.PacketChannel;
import dev.xyat.kineticcore.api.network.ServerPacketContext;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.publish.BiomeFilter;
import dev.xyat.realmcontrol.structure.publish.LootTables;
import dev.xyat.realmcontrol.structure.publish.Publisher;
import dev.xyat.realmcontrol.structure.publish.TestPlacement;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.structure.selection.SelectionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Packets of the structure editor; every server-side action needs game master permission. */
public final class StructureEditorNetwork {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JSON_CHARS = 32 * 1024 * 1024;
    private static final ExecutorService PUBLISHER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RealmControl structure publisher");
        thread.setDaemon(true);
        return thread;
    });
    public static final PacketChannel CHANNEL = PacketChannel.create(
            KineticResourceIds.of(StructureEditorModule.MODID, "structure_editor"), "1", NetworkVersionPolicy.ANY);

    private StructureEditorNetwork() {
    }

    public static void register() {
        CHANNEL.registerServerbound(0, SelectionActionPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), SelectionActionPacket::read), SelectionActionPacket::handle);
        CHANNEL.registerServerbound(1, ProjectRequestPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), ProjectRequestPacket::read), ProjectRequestPacket::handle);
        CHANNEL.registerServerbound(2, SaveProjectPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), SaveProjectPacket::read), SaveProjectPacket::handle);
        CHANNEL.registerClientbound(3, SelectionPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), SelectionPacket::read), SelectionPacket::handleClient);
        CHANNEL.registerClientbound(4, ProjectsPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), ProjectsPacket::read), ProjectsPacket::handleClient);
        CHANNEL.registerClientbound(5, ProjectDetailPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), ProjectDetailPacket::read), ProjectDetailPacket::handleClient);
        CHANNEL.registerClientbound(6, ScanProgressPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), ScanProgressPacket::read), ScanProgressPacket::handleClient);
        CHANNEL.registerClientbound(7, BiomeCatalogPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), BiomeCatalogPacket::read), BiomeCatalogPacket::handleClient);
        CHANNEL.registerClientbound(8, ResultPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), ResultPacket::read), ResultPacket::handleClient);
        CHANNEL.registerServerbound(9, SaveMarkersPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), SaveMarkersPacket::read), SaveMarkersPacket::handle);
        CHANNEL.registerServerbound(10, CreateTablePacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), CreateTablePacket::read), CreateTablePacket::handle);
        CHANNEL.registerServerbound(11, ToggleEntityPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), ToggleEntityPacket::read), ToggleEntityPacket::handle);
        CHANNEL.registerClientbound(12, MarkersPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), MarkersPacket::read), MarkersPacket::handleClient);
        CHANNEL.registerClientbound(13, LootTablesPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), LootTablesPacket::read), LootTablesPacket::handleClient);
        CHANNEL.registerClientbound(14, NearbyMarkersPacket.class, NetworkCodec.of((buf, msg) -> msg.write(buf), NearbyMarkersPacket::read), NearbyMarkersPacket::handleClient);
    }

    // ---- client side senders ----

    public static void selectionAction(SelectionStore.Action action, @Nullable BlockPos pos, int arg, int amount) {
        CHANNEL.sendToServer(new SelectionActionPacket(action, pos, arg, amount));
    }

    public static void request(ProjectRequestPacket.Kind kind, String id, String name) {
        CHANNEL.sendToServer(new ProjectRequestPacket(kind, id, name));
    }

    /** Saves a draft's settings, and its cut-outs unless {@code cutouts} is {@code null}. */
    public static void save(StructureProject project, @Nullable Cutouts cutouts) {
        CHANNEL.sendToServer(new SaveProjectPacket(project.id, project.toJson().toString(), cutouts == null ? "" : cutouts.toJson().toString()));
    }

    public static void saveMarkers(String id, Markers markers) {
        CHANNEL.sendToServer(new SaveMarkersPacket(id, markers.toJson().toString()));
    }

    /** Asks for a new loot table in the pack; {@code pos} is the container whose items {@link LootTables.Kind#ITEMS} uses. */
    public static void createTable(String projectId, String table, LootTables.Kind kind, String source, int[] pos) {
        CHANNEL.sendToServer(new CreateTablePacket(projectId, table, kind, source, pos[0], pos[1], pos[2]));
    }

    public static void toggleEntity(int entityId) {
        CHANNEL.sendToServer(new ToggleEntityPacket(entityId));
    }

    // ---- server side ----

    public static void result(ServerPlayer player, Component message, boolean success) {
        CHANNEL.sendToPlayer(player, new ResultPacket(message, success));
    }

    /** Sends every project; {@code focus} is the one the editor should select, {@code open} opens the editor. */
    public static void sendProjects(ServerPlayer player, @Nullable String focus) {
        sendProjects(player, focus, false);
    }

    public static void sendProjects(ServerPlayer player, @Nullable String focus, boolean open) {
        JsonArray list = new JsonArray();
        for (StructureProject project : ProjectStore.list()) {
            JsonObject json = project.toJson();
            json.addProperty("cutout_count", ProjectStore.cutouts(project.id).count());
            json.addProperty("scanning", ScanService.busy(project.id) || TestPlacement.busy(project.id));
            list.add(json);
        }
        JsonObject root = new JsonObject();
        root.add("projects", list);
        TestPlacement.Record test = TestPlacement.record(player.getServer());
        if (test != null) root.add("test", test.toJson());
        root.addProperty("focus", focus == null ? "" : focus);
        root.addProperty("open", open);
        CHANNEL.sendToPlayer(player, new ProjectsPacket(root.toString()));
    }

    private static void sendDetail(ServerPlayer player, String id) {
        sendDetail(player, id, false);
    }

    /** A draft with its cut-outs; {@code openGeneration} has the client open its generation page (from the structure panel). */
    private static void sendDetail(ServerPlayer player, String id, boolean openGeneration) {
        StructureProject project = ProjectStore.load(id);
        if (project == null) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", id), false);
            return;
        }
        CHANNEL.sendToPlayer(player, new ProjectDetailPacket(project.toJson().toString(), ProjectStore.cutouts(id).toJson().toString(), openGeneration));
    }

    /**
     * Sends a draft's markers with the state of the loot tables they use; {@code focus} names the markers the page
     * should select (see {@code MarkersPage}), {@code open} opens the page.
     */
    private static void sendMarkers(ServerPlayer player, String id, String focus, boolean open) {
        StructureProject project = ProjectStore.load(id);
        if (project == null) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", id), false);
            return;
        }
        Markers markers = ProjectStore.markers(id);
        CHANNEL.sendToPlayer(player, new MarkersPacket(project.toJson().toString(), markers.toJson().toString(),
                tableStates(player.getServer(), markers.tables()).toString(), focus, open));
    }

    /** For each table: whether it exists, and the items of its pack file this game does not know. */
    private static JsonObject tableStates(MinecraftServer server, java.util.List<String> tables) {
        JsonObject states = new JsonObject();
        for (String table : tables) {
            JsonObject state = new JsonObject();
            state.addProperty("exists", LootTables.exists(server, table));
            JsonArray missing = new JsonArray();
            LootTables.missingItems(table).forEach(missing::add);
            state.add("missing", missing);
            states.add(table, state);
        }
        return states;
    }

    /** The markers of the drafts scanned around the player, for the controller to draw. */
    private static void sendNearby(ServerPlayer player) {
        JsonArray list = new JsonArray();
        String dimension = player.level().dimension().location().toString();
        for (StructureProject project : ProjectStore.list()) {
            if (!project.dimension.equals(dimension) || !near(project, player.getX(), player.getY(), player.getZ(), 48)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", project.id);
            entry.add("project", project.toJson());
            entry.add("markers", ProjectStore.markers(project.id).toJson());
            list.add(entry);
        }
        CHANNEL.sendToPlayer(player, new NearbyMarkersPacket(list.toString()));
    }

    private static boolean near(StructureProject project, double x, double y, double z, int margin) {
        return x >= project.origin[0] - margin && x <= project.origin[0] + project.size[0] + margin
                && y >= project.origin[1] - margin && y <= project.origin[1] + project.size[1] + margin
                && z >= project.origin[2] - margin && z <= project.origin[2] + project.size[2] + margin;
    }

    /** Takes the editor's settings for the markers the server has; what the scan found stays as it is. */
    private static void saveMarkers(ServerPlayer player, String id, String json) {
        StructureProject project = ProjectStore.load(id);
        if (project == null) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", id), false);
            return;
        }
        Markers edited = Markers.fromJson(JsonParser.parseString(json).getAsJsonObject());
        Markers markers = ProjectStore.markers(id);
        for (Markers.Container container : markers.containers) {
            Markers.Container change = edited.container(container.x, container.y, container.z);
            if (change == null) continue;
            String table = change.table.trim().toLowerCase(Locale.ROOT);
            container.table = table.isEmpty() || StructureProject.validId(table) ? table : container.table;
            container.mode = container.lootable ? change.mode : "keep";
        }
        for (Markers.Spawner spawner : markers.spawners) {
            Markers.Spawner change = edited.spawner(spawner.x, spawner.y, spawner.z);
            if (change == null) continue;
            spawner.mode = change.mode;
            spawner.mobs = new java.util.ArrayList<>(change.mobs);
            spawner.minDelay = change.minDelay;
            spawner.maxDelay = change.maxDelay;
            spawner.count = change.count;
            spawner.maxNearby = change.maxNearby;
            spawner.playerRange = change.playerRange;
            spawner.spawnRange = change.spawnRange;
        }
        markers.entityTypes = edited.entityTypes;
        for (Markers.EntityMark mark : markers.entities) {
            Markers.EntityMark change = edited.entity(mark.type, mark.x, mark.y, mark.z);
            if (change != null) mark.keep = change.keep;
        }
        try {
            ProjectStore.saveMarkers(id, markers);
            if ("published".equals(project.status)) {
                project.status = "modified";
                ProjectStore.save(project);
            }
        } catch (IOException e) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())), false);
            return;
        }
        result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.saved", project.name), true);
        sendMarkers(player, id, "", false);
        sendNearby(player);
        sendProjects(player, null);
    }

    /**
     * The controller's entity mode: switches whether the entity looked at is published. It must still be about
     * where the scan saw it (within two blocks).
     */
    private static void toggleEntity(ServerPlayer player, int entityId) {
        net.minecraft.world.entity.Entity entity = player.level().getEntity(entityId);
        if (entity == null || entity.distanceToSqr(player) > 200 * 200) return;
        String dimension = player.level().dimension().location().toString();
        String type = net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString();
        for (StructureProject project : ProjectStore.list()) {
            if (!project.dimension.equals(dimension) || !near(project, entity.getX(), entity.getY(), entity.getZ(), 0)) continue;
            Markers markers = ProjectStore.markers(project.id);
            double x = entity.getX() - project.origin[0], y = entity.getY() - project.origin[1], z = entity.getZ() - project.origin[2];
            Markers.EntityMark best = null;
            double bestDistance = 4.0D;
            for (Markers.EntityMark mark : markers.entities) {
                double distance = (mark.x - x) * (mark.x - x) + (mark.y - y) * (mark.y - y) + (mark.z - z) * (mark.z - z);
                if (mark.type.equals(type) && distance < bestDistance) {
                    best = mark;
                    bestDistance = distance;
                }
            }
            if (best == null) {
                Component message = KineticI18n.translatable("msg.realmcontrol.structure.marker.entity_unknown");
                player.displayClientMessage(message, true);
                return;
            }
            boolean keep = !markers.keeps(best);
            best.keep = keep == markers.keepsType(type) ? null : keep;
            try {
                ProjectStore.saveMarkers(project.id, markers);
            } catch (IOException e) {
                result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())), false);
                return;
            }
            Component message = KineticI18n.translatable(keep ? "msg.realmcontrol.structure.marker.entity_kept" : "msg.realmcontrol.structure.marker.entity_dropped",
                    entity.getType().getDescription(), project.name);
            player.displayClientMessage(message, true);
            sendNearby(player);
            return;
        }
        Component message = KineticI18n.translatable("msg.realmcontrol.structure.marker.no_project");
        player.displayClientMessage(message, true);
    }

    private static void startScan(ServerPlayer player, String id, String name, boolean rescan) {
        id = id.trim().toLowerCase(Locale.ROOT);
        if (!StructureProject.validId(id)) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.bad_id", id), false);
            return;
        }
        ServerLevel level;
        Selection.Box region;
        java.util.List<Selection.Box> cutouts = java.util.List.of();
        if (rescan) {
            StructureProject project = ProjectStore.load(id);
            if (project == null) {
                result(player, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", id), false);
                return;
            }
            ResourceLocation dimension = ResourceLocation.tryParse(project.dimension);
            level = dimension == null ? null : player.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            if (level == null) {
                result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.no_dimension", project.dimension), false);
                return;
            }
            BlockPos min = new BlockPos(project.origin[0], project.origin[1], project.origin[2]);
            region = new Selection.Box(min, min.offset(project.size[0] - 1, project.size[1] - 1, project.size[2] - 1));
        } else {
            if (ProjectStore.exists(id)) {
                result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.exists", id), false);
                return;
            }
            Selection selection = SelectionStore.get(player);
            region = selection.region();
            String problem = Selection.problem(region);
            if (problem != null) {
                result(player, KineticI18n.translatable(problem, Selection.MAX_FOOTPRINT), false);
                return;
            }
            level = player.serverLevel();
            cutouts = selection.cutouts;
        }
        if (TestPlacement.busy(id)) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.busy"), false);
            return;
        }
        ScanService.start(player, id, name, level, region, cutouts);
    }

    private static void publish(ServerPlayer player, String id) {
        MinecraftServer server = player.getServer();
        UUID owner = player.getUUID();
        PUBLISHER.execute(() -> {
            long[] last = {0L};
            Publisher.Outcome outcome;
            try {
                outcome = Publisher.publish(server, id, share -> {
                    long now = System.nanoTime();
                    if (now - last[0] < 100_000_000L) return;
                    last[0] = now;
                    server.execute(() -> {
                        ServerPlayer target = server.getPlayerList().getPlayer(owner);
                        if (target != null) CHANNEL.sendToPlayer(target, new ScanProgressPacket(id, share, -1, false, null));
                    });
                });
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Publishing structure project {} failed", id, e);
                outcome = new Publisher.Outcome(false, KineticI18n.translatable("msg.realmcontrol.structure.publish.failed", String.valueOf(e.getMessage())));
            }
            Publisher.Outcome finished = outcome;
            server.execute(() -> {
                ServerPlayer target = server.getPlayerList().getPlayer(owner);
                if (target == null) return;
                CHANNEL.sendToPlayer(target, new ScanProgressPacket(id, 1F, -1, true, finished.success() ? null : finished.message()));
                result(target, finished.message(), finished.success());
                sendProjects(target, id);
            });
        });
    }

    private static void saveProject(ServerPlayer player, String projectJson, String cutoutsJson) {
        StructureProject edited = StructureProject.fromJson(JsonParser.parseString(projectJson).getAsJsonObject());
        Publisher.Outcome outcome = applyEdit(player.getServer(), edited, cutoutsJson);
        result(player, outcome.message(), outcome.success());
        if (!outcome.success()) return;
        sendProjects(player, edited.id);
        sendDetail(player, edited.id);
    }

    /**
     * Saves what the editor changes in a draft; where it came from and what the scan counted stay the server's. A
     * change to what the templates hold marks a published draft modified (it needs publishing again); a change to
     * the generation rules of a published one is written into its published files at once, so the editor and the
     * structure panel always show and edit the same values.
     */
    public static Publisher.Outcome applyEdit(MinecraftServer server, StructureProject edited, String cutoutsJson) {
        StructureProject project = ProjectStore.load(edited.id);
        if (project == null) return new Publisher.Outcome(false, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", edited.id));
        boolean templates = !project.air.equals(edited.air) || !project.ignoreBlocks.equals(edited.ignoreBlocks)
                || !project.save.toJson().equals(edited.save.toJson()) || !cutoutsJson.isEmpty();
        boolean rules = !project.generation.toJson().equals(edited.generation.toJson()) || project.embed != edited.embed;
        project.name = edited.name.isBlank() ? project.id : edited.name;
        project.embed = Math.max(0, Math.min(project.size[1], edited.embed));
        project.air = edited.air;
        project.ignoreBlocks = edited.ignoreBlocks;
        project.generation = edited.generation;
        project.save = edited.save;
        if (templates && "published".equals(project.status)) project.status = "modified";
        try {
            Publisher.Outcome synced = rules ? Publisher.syncGeneration(server, project) : null;
            if (synced != null && !synced.success()) return synced;
            ProjectStore.save(project);
            if (!cutoutsJson.isEmpty()) ProjectStore.saveCutouts(project.id, Cutouts.fromJson(JsonParser.parseString(cutoutsJson).getAsJsonObject()));
            if (synced != null) return synced;
        } catch (IOException e) {
            return new Publisher.Outcome(false, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())));
        }
        return new Publisher.Outcome(true, KineticI18n.translatable("msg.realmcontrol.structure.project.saved", project.name));
    }

    private static void importCutouts(ServerPlayer player, String id) {
        StructureProject project = ProjectStore.load(id);
        if (project == null) return;
        Selection selection = SelectionStore.get(player);
        Cutouts cutouts = ProjectStore.cutouts(id);
        int added = 0;
        for (Selection.Box box : selection.cutouts) {
            int minX = Math.max(box.min().getX() - project.origin[0], 0), minY = Math.max(box.min().getY() - project.origin[1], 0), minZ = Math.max(box.min().getZ() - project.origin[2], 0);
            int maxX = Math.min(box.max().getX() - project.origin[0], project.size[0] - 1), maxY = Math.min(box.max().getY() - project.origin[1], project.size[1] - 1);
            int maxZ = Math.min(box.max().getZ() - project.origin[2], project.size[2] - 1);
            if (minX > maxX || minY > maxY || minZ > maxZ || cutouts.boxes.size() >= Cutouts.MAX_BOXES) continue;
            cutouts.boxes.add(new Cutouts.Box("#" + (cutouts.boxes.size() + 1), minX, minY, minZ, maxX, maxY, maxZ, false));
            added++;
        }
        try {
            ProjectStore.saveCutouts(id, cutouts);
        } catch (IOException e) {
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())), false);
            return;
        }
        result(player, KineticI18n.translatable("msg.realmcontrol.structure.cutout.imported", added), added > 0);
        sendDetail(player, id);
    }

    private static boolean denied(ServerPlayer player) {
        if (StructureEditorModule.allowed(player)) return false;
        result(player, KineticI18n.translatable("msg.realmcontrol.structure.no_permission"), false);
        return true;
    }

    private static String readJson(NetworkBuffer buf) {
        return KineticCompression.decompressUtf8(buf.readByteArray(MAX_JSON_BYTES), MAX_JSON_CHARS);
    }

    private static void writeJson(NetworkBuffer buf, String json) {
        buf.writeByteArray(KineticCompression.compressUtf8(json, MAX_JSON_BYTES), MAX_JSON_BYTES);
    }

    // ---- packets ----

    public record SelectionActionPacket(SelectionStore.Action action, @Nullable BlockPos pos, int arg, int amount) {
        static SelectionActionPacket read(NetworkBuffer buf) {
            return new SelectionActionPacket(buf.readEnum(SelectionStore.Action.class), Selection.readPos(buf), buf.readVarInt(), buf.readInt());
        }

        void write(NetworkBuffer buf) {
            buf.writeEnum(action);
            Selection.writePos(buf, pos);
            buf.writeVarInt(arg);
            buf.writeInt(amount);
        }

        void handle(ServerPacketContext context) {
            SelectionStore.apply(context.sender(), action, pos, arg, amount);
        }
    }

    /** One editor request about a project; {@code name} is only used when a new project is scanned. */
    public record ProjectRequestPacket(Kind kind, String id, String name) {
        public enum Kind {
            OPEN_EDITOR, LIST, DETAIL, CREATE, RESCAN, CANCEL_SCAN, DELETE, PUBLISH, BIOMES, IMPORT_CUTOUTS,
            /** A draft's markers; {@code name} is the focus. */
            MARKERS,
            /** Opens the markers page of a draft; {@code name} is the focus. */
            OPEN_MARKERS, LOOT_TABLES, NEARBY_MARKERS,
            /** Opens the generation page of a draft, from the structure panel. */
            OPEN_GENERATION,
            /** Places a draft around the player for a test. */
            TEST_PLACE,
            /** Puts back the area of the world's test placement. */
            TEST_UNDO
        }

        static ProjectRequestPacket read(NetworkBuffer buf) {
            return new ProjectRequestPacket(buf.readEnum(Kind.class), buf.readUtf(256), buf.readUtf(8192));
        }

        void write(NetworkBuffer buf) {
            buf.writeEnum(kind);
            buf.writeUtf(id, 256);
            buf.writeUtf(name, 8192);
        }

        void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (denied(player)) return;
            switch (kind) {
                case OPEN_EDITOR -> sendProjects(player, id.isBlank() ? null : id, true);
                case LIST -> sendProjects(player, id.isBlank() ? null : id);
                case DETAIL -> sendDetail(player, id);
                case CREATE -> startScan(player, id, name, false);
                case RESCAN -> startScan(player, id, name, true);
                case CANCEL_SCAN -> {
                    ScanService.cancel(player, id);
                    TestPlacement.cancel(player, id);
                }
                case DELETE -> {
                    if (ScanService.busy(id) || TestPlacement.busy(id)) {
                        result(player, KineticI18n.translatable("msg.realmcontrol.structure.scan.busy"), false);
                        return;
                    }
                    try {
                        ProjectStore.delete(id);
                        result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.deleted", id), true);
                    } catch (IOException e) {
                        result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())), false);
                    }
                    sendProjects(player, null);
                }
                case PUBLISH -> publish(player, id);
                case BIOMES -> CHANNEL.sendToPlayer(player, new BiomeCatalogPacket(BiomeFilter.catalog(player.getServer()).toString()));
                case IMPORT_CUTOUTS -> importCutouts(player, id);
                case MARKERS -> sendMarkers(player, id, name, false);
                case OPEN_MARKERS -> {
                    sendProjects(player, null);
                    sendMarkers(player, id, name, true);
                }
                case LOOT_TABLES -> {
                    JsonArray ids = new JsonArray();
                    LootTables.ids(player.getServer()).forEach(ids::add);
                    CHANNEL.sendToPlayer(player, new LootTablesPacket(ids.toString()));
                }
                case NEARBY_MARKERS -> sendNearby(player);
                case OPEN_GENERATION -> sendDetail(player, id, true);
                case TEST_PLACE -> TestPlacement.place(player, id);
                case TEST_UNDO -> TestPlacement.undo(player);
            }
        }
    }

    public record SaveProjectPacket(String id, String projectJson, String cutoutsJson) {
        static SaveProjectPacket read(NetworkBuffer buf) {
            return new SaveProjectPacket(buf.readUtf(256), readJson(buf), readJson(buf));
        }

        void write(NetworkBuffer buf) {
            buf.writeUtf(id, 256);
            writeJson(buf, projectJson);
            writeJson(buf, cutoutsJson);
        }

        void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (denied(player)) return;
            try {
                saveProject(player, projectJson, cutoutsJson);
            } catch (RuntimeException e) {
                result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())), false);
            }
        }
    }

    public record SaveMarkersPacket(String id, String markersJson) {
        static SaveMarkersPacket read(NetworkBuffer buf) {
            return new SaveMarkersPacket(buf.readUtf(256), readJson(buf));
        }

        void write(NetworkBuffer buf) {
            buf.writeUtf(id, 256);
            writeJson(buf, markersJson);
        }

        void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (denied(player)) return;
            try {
                saveMarkers(player, id, markersJson);
            } catch (RuntimeException e) {
                result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage())), false);
            }
        }
    }

    public record CreateTablePacket(String projectId, String table, LootTables.Kind kind, String source, int x, int y, int z) {
        static CreateTablePacket read(NetworkBuffer buf) {
            return new CreateTablePacket(buf.readUtf(256), buf.readUtf(256), buf.readEnum(LootTables.Kind.class), buf.readUtf(256),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        void write(NetworkBuffer buf) {
            buf.writeUtf(projectId, 256);
            buf.writeUtf(table, 256);
            buf.writeEnum(kind);
            buf.writeUtf(source, 256);
            buf.writeVarInt(x);
            buf.writeVarInt(y);
            buf.writeVarInt(z);
        }

        void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (denied(player)) return;
            String id = table.trim().toLowerCase(Locale.ROOT);
            Component problem = LootTables.create(player.getServer(), id, kind, source, projectId, new int[]{x, y, z});
            if (problem != null) {
                result(player, problem, false);
                return;
            }
            result(player, KineticI18n.translatable("msg.realmcontrol.structure.loot.created", id), true);
            JsonArray ids = new JsonArray();
            LootTables.ids(player.getServer()).forEach(ids::add);
            CHANNEL.sendToPlayer(player, new LootTablesPacket(ids.toString()));
        }
    }

    public record ToggleEntityPacket(int entityId) {
        static ToggleEntityPacket read(NetworkBuffer buf) {
            return new ToggleEntityPacket(buf.readVarInt());
        }

        void write(NetworkBuffer buf) {
            buf.writeVarInt(entityId);
        }

        void handle(ServerPacketContext context) {
            ServerPlayer player = context.sender();
            if (denied(player)) return;
            toggleEntity(player, entityId);
        }
    }

    public record MarkersPacket(String projectJson, String markersJson, String tablesJson, String focus, boolean open) {
        static MarkersPacket read(NetworkBuffer buf) {
            return new MarkersPacket(readJson(buf), readJson(buf), readJson(buf), buf.readUtf(8192), buf.readBoolean());
        }

        void write(NetworkBuffer buf) {
            writeJson(buf, projectJson);
            writeJson(buf, markersJson);
            writeJson(buf, tablesJson);
            buf.writeUtf(focus, 8192);
            buf.writeBoolean(open);
        }

        void handleClient() {
            EditorClient.markers(StructureProject.fromJson(JsonParser.parseString(projectJson).getAsJsonObject()),
                    Markers.fromJson(JsonParser.parseString(markersJson).getAsJsonObject()),
                    JsonParser.parseString(tablesJson).getAsJsonObject(), focus, open);
        }
    }

    public record LootTablesPacket(String json) {
        static LootTablesPacket read(NetworkBuffer buf) {
            return new LootTablesPacket(readJson(buf));
        }

        void write(NetworkBuffer buf) {
            writeJson(buf, json);
        }

        void handleClient() {
            java.util.List<String> ids = new java.util.ArrayList<>();
            JsonParser.parseString(json).getAsJsonArray().forEach(element -> ids.add(element.getAsString()));
            EditorClient.lootTables(ids);
        }
    }

    public record NearbyMarkersPacket(String json) {
        static NearbyMarkersPacket read(NetworkBuffer buf) {
            return new NearbyMarkersPacket(readJson(buf));
        }

        void write(NetworkBuffer buf) {
            writeJson(buf, json);
        }

        void handleClient() {
            EditorClient.nearby(JsonParser.parseString(json).getAsJsonArray());
        }
    }

    public record SelectionPacket(Selection selection) {
        static SelectionPacket read(NetworkBuffer buf) {
            return new SelectionPacket(Selection.read(buf));
        }

        void write(NetworkBuffer buf) {
            selection.write(buf);
        }

        void handleClient() {
            EditorClient.selection(selection);
        }
    }

    public record ProjectsPacket(String json) {
        static ProjectsPacket read(NetworkBuffer buf) {
            return new ProjectsPacket(readJson(buf));
        }

        void write(NetworkBuffer buf) {
            writeJson(buf, json);
        }

        void handleClient() {
            EditorClient.projects(JsonParser.parseString(json).getAsJsonObject());
        }
    }

    public record ProjectDetailPacket(String projectJson, String cutoutsJson, boolean openGeneration) {
        static ProjectDetailPacket read(NetworkBuffer buf) {
            return new ProjectDetailPacket(readJson(buf), readJson(buf), buf.readBoolean());
        }

        void write(NetworkBuffer buf) {
            writeJson(buf, projectJson);
            writeJson(buf, cutoutsJson);
            buf.writeBoolean(openGeneration);
        }

        void handleClient() {
            StructureProject project = StructureProject.fromJson(JsonParser.parseString(projectJson).getAsJsonObject());
            if (openGeneration) EditorClient.openGeneration(project);
            else EditorClient.detail(project, Cutouts.fromJson(JsonParser.parseString(cutoutsJson).getAsJsonObject()));
        }
    }

    /**
     * Progress of a scan, or of a publish when {@code waiting} is -1. {@code waiting} is the place in line of a scan
     * that has not started; {@code done} ends the task, with {@code message} set when it failed or was cancelled.
     */
    public record ScanProgressPacket(String id, float share, int waiting, boolean done, @Nullable Component message) {
        static ScanProgressPacket read(NetworkBuffer buf) {
            String id = buf.readUtf(256);
            float share = buf.readFloat();
            int waiting = buf.readVarInt() - 1;
            boolean done = buf.readBoolean();
            return new ScanProgressPacket(id, share, waiting, done, buf.readBoolean() ? buf.readComponent() : null);
        }

        void write(NetworkBuffer buf) {
            buf.writeUtf(id, 256);
            buf.writeFloat(share);
            buf.writeVarInt(waiting + 1);
            buf.writeBoolean(done);
            buf.writeBoolean(message != null);
            if (message != null) buf.writeComponent(message);
        }

        void handleClient() {
            EditorClient.progress(this);
        }
    }

    public record BiomeCatalogPacket(String json) {
        static BiomeCatalogPacket read(NetworkBuffer buf) {
            return new BiomeCatalogPacket(readJson(buf));
        }

        void write(NetworkBuffer buf) {
            writeJson(buf, json);
        }

        void handleClient() {
            EditorClient.biomes(JsonParser.parseString(json).getAsJsonObject());
        }
    }

    public record ResultPacket(Component message, boolean success) {
        static ResultPacket read(NetworkBuffer buf) {
            return new ResultPacket(buf.readComponent(), buf.readBoolean());
        }

        void write(NetworkBuffer buf) {
            buf.writeComponent(message);
            buf.writeBoolean(success);
        }

        void handleClient() {
            EditorClient.result(message, success);
        }
    }
}
