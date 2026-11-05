package dev.xyat.realmcontrol.structure.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.realmcontrol.structure.client.gui.GenerationPage;
import dev.xyat.realmcontrol.structure.client.gui.MarkersPage;
import dev.xyat.realmcontrol.structure.client.gui.StructureEditorPage;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.selection.Selection;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the client knows of the structure editor: the selection in this dimension, the projects, running scans and
 * publishes, the biome catalog, the loot tables, the markers of the drafts around the player and the markers the
 * controller has gathered for one batch edit. Arriving data is handed to the open editor page.
 */
public final class EditorClient {
    private static Selection selection = new Selection();
    private static final List<ProjectEntry> PROJECTS = new ArrayList<>();
    private static final Map<String, StructureEditorNetwork.ScanProgressPacket> TASKS = new HashMap<>();
    @Nullable private static JsonObject biomes;
    @Nullable private static List<String> lootTables;
    private static final List<NearbyProject> NEARBY = new ArrayList<>();
    /** Marker keys the controller gathered with Shift + right click, by draft; the next markers page selects them. */
    private static final Map<String, Set<String>> BATCH = new HashMap<>();
    @Nullable private static TestPlacementInfo testPlacement;
    /** The draft whose publish leaves and enters the world again once it succeeds. */
    @Nullable private static String rejoinAfter;
    private static boolean rejoinNow;

    /** A draft scanned around the player, with its markers. */
    public record NearbyProject(StructureProject project, Markers markers) {
    }

    /** A project as the list shows it. */
    public record ProjectEntry(StructureProject project, int cutoutCount, boolean scanning) {
    }

    /** The world's test placement: which draft and where its box starts. */
    public record TestPlacementInfo(String project, String name, int x, int y, int z) {
    }

    /** Editor pages that want arriving data. */
    public interface Listener {
        default void projectsChanged(@Nullable String focus) {
        }

        default void detailArrived(StructureProject project, Cutouts cutouts) {
        }

        default void progressChanged(String id) {
        }

        default void biomesArrived(JsonObject catalog) {
        }

        default void markersArrived(StructureProject project, Markers markers, JsonObject tables, String focus) {
        }

        default void lootTablesArrived(List<String> ids) {
        }
    }

    static {
        KineticClientEvents.onLogout(() -> {
            selection = new Selection();
            PROJECTS.clear();
            TASKS.clear();
            biomes = null;
            lootTables = null;
            NEARBY.clear();
            BATCH.clear();
            testPlacement = null;
            rejoinAfter = null;
        });
        KineticClientEvents.onTick(KineticClientEvents.TickPhase.END, () -> {
            if (!rejoinNow) return;
            rejoinNow = false;
            WorldRejoin.run();
        });
    }

    private EditorClient() {
    }

    public static Selection selection() {
        return selection;
    }

    public static List<ProjectEntry> projects() {
        return PROJECTS;
    }

    /** The running scan or publish of a project, or {@code null}. */
    @Nullable
    public static StructureEditorNetwork.ScanProgressPacket task(String id) {
        return TASKS.get(id);
    }

    @Nullable
    public static JsonObject biomeCatalog() {
        return biomes;
    }

    /** Every loot table id, or {@code null} until the server sent them. */
    @Nullable
    public static List<String> lootTables() {
        return lootTables;
    }

    public static List<NearbyProject> nearby() {
        return NEARBY;
    }

    /** The batch of one draft, changed in place. */
    public static Set<String> batch(String id) {
        return BATCH.computeIfAbsent(id, key -> new LinkedHashSet<>());
    }

    public static void selection(Selection value) {
        selection = value;
    }

    /** The world's test placement, or {@code null}. */
    @Nullable
    public static TestPlacementInfo testPlacement() {
        return testPlacement;
    }

    /** Leaves and enters this single-player world again once the publish of {@code id} succeeds. */
    public static void rejoinAfterPublish(String id) {
        rejoinAfter = id;
    }

    public static void projects(JsonObject json) {
        JsonObject test = json.has("test") ? json.getAsJsonObject("test") : null;
        testPlacement = test == null ? null : new TestPlacementInfo(test.get("project").getAsString(), test.get("name").getAsString(),
                test.get("x").getAsInt(), test.get("y").getAsInt(), test.get("z").getAsInt());
        PROJECTS.clear();
        for (JsonElement element : json.getAsJsonArray("projects")) {
            JsonObject entry = element.getAsJsonObject();
            PROJECTS.add(new ProjectEntry(StructureProject.fromJson(entry), entry.get("cutout_count").getAsInt(),
                    entry.get("scanning").getAsBoolean()));
        }
        String focus = json.get("focus").getAsString();
        if (json.get("open").getAsBoolean() && KineticGui.findPage(StructureEditorPage.class) == null) {
            KineticGui.openChild(new StructureEditorPage(focus.isBlank() ? null : focus));
            return;
        }
        if (listener() instanceof Listener listener) listener.projectsChanged(focus.isBlank() ? null : focus);
    }

    public static void detail(StructureProject project, Cutouts cutouts) {
        if (listener() instanceof Listener listener) listener.detailArrived(project, cutouts);
    }

    /** The structure panel asked for a custom structure's rules: its draft's generation page opens over the panel. */
    public static void openGeneration(StructureProject project) {
        KineticGui.openChild(new GenerationPage(project));
    }

    public static void progress(StructureEditorNetwork.ScanProgressPacket packet) {
        if (packet.done()) TASKS.remove(packet.id());
        else TASKS.put(packet.id(), packet);
        // A publish (-1) of the draft to re-enter for: on success the world is left and opened again next tick.
        if (packet.done() && packet.waiting() == -1 && packet.id().equals(rejoinAfter)) {
            rejoinAfter = null;
            rejoinNow = packet.message() == null;
        }
        if (packet.done() && packet.message() != null) KineticOverlays.toast(packet.message());
        if (listener() instanceof Listener listener) listener.progressChanged(packet.id());
    }

    public static void biomes(JsonObject catalog) {
        biomes = catalog;
        if (listener() instanceof Listener listener) listener.biomesArrived(catalog);
    }

    public static void markers(StructureProject project, Markers markers, JsonObject tables, String focus, boolean open) {
        if (open) {
            BATCH.remove(project.id);
            KineticGui.openChild(new MarkersPage(project, markers, tables, focus));
            return;
        }
        if (listener() instanceof Listener listener) listener.markersArrived(project, markers, tables, focus);
    }

    public static void lootTables(List<String> ids) {
        lootTables = ids;
        if (listener() instanceof Listener listener) listener.lootTablesArrived(ids);
    }

    public static void nearby(JsonArray list) {
        NEARBY.clear();
        for (JsonElement element : list) {
            JsonObject entry = element.getAsJsonObject();
            NEARBY.add(new NearbyProject(StructureProject.fromJson(entry.getAsJsonObject("project")), Markers.fromJson(entry.getAsJsonObject("markers"))));
        }
    }

    public static void result(Component message, boolean success) {
        KineticOverlays.toast(message);
    }

    @Nullable
    private static KineticPage listener() {
        return KineticGui.currentPage();
    }
}
