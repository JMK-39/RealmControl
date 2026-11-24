package dev.xyat.realmcontrol.structure.client;

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
import java.util.List;
import java.util.Map;

/**
 * What the client knows of the structure editor: the selection in this dimension, the projects, running scans and
 * publishes, the biome catalog and the loot tables. Arriving data is handed to the open editor page.
 */
public final class EditorClient {
    private static Selection selection = new Selection();
    private static final List<ProjectEntry> PROJECTS = new ArrayList<>();
    private static final Map<String, StructureEditorNetwork.ScanProgressPacket> TASKS = new HashMap<>();
    @Nullable private static JsonObject biomes;
    @Nullable private static List<String> lootTables;
    @Nullable private static TestPlacementInfo testPlacement;
    /** The draft whose publish leaves and enters the world again once it succeeds. */
    @Nullable private static String rejoinAfter;
    private static boolean rejoinNow;

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

        /** The answer to {@link StructureEditorNetwork#editEntity}: {@code key}, then {@code data} or {@code problem}. */
        default void entityDataArrived(String projectId, JsonObject answer) {
        }
    }

    static {
        KineticClientEvents.onLogout(() -> {
            selection = new Selection();
            PROJECTS.clear();
            TASKS.clear();
            biomes = null;
            lootTables = null;
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
        // The draft a scan, save or publish just changed: its row picture is asked for again.
        if (!focus.isBlank()) {
            dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient.refresh(dev.xyat.realmcontrol.structure.project.ProjectPreview.PREFIX + focus);
        }
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
            KineticGui.openChild(new MarkersPage(project, markers, tables, focus));
            return;
        }
        if (listener() instanceof Listener listener) listener.markersArrived(project, markers, tables, focus);
    }

    public static void lootTables(List<String> ids) {
        lootTables = ids;
        if (listener() instanceof Listener listener) listener.lootTablesArrived(ids);
    }

    public static void entityData(String projectId, JsonObject answer) {
        if (answer.has("problem")) KineticOverlays.toast(dev.xyat.kineticcore.api.text.KineticI18n.translatable(answer.get("problem").getAsString()));
        // The 3D editor hears it even while a picker it opened is in front.
        MarkersPage page = KineticGui.findPage(MarkersPage.class);
        if (page != null) page.entityDataArrived(projectId, answer);
    }

    public static void result(Component message, boolean success) {
        KineticOverlays.toast(message);
    }

    @Nullable
    private static KineticPage listener() {
        return KineticGui.currentPage();
    }
}
