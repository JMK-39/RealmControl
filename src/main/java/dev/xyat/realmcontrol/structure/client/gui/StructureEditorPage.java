package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.render.KineticTexture;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticStructureView;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.ControllerClient;
import dev.xyat.realmcontrol.structure.client.CutoutSnapshots;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork.ProjectRequestPacket.Kind;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.publish.TestPlacement;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
import dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The structure editor's first page. Left: the drafts as rows with a small picture, the name, tier and state. Right:
 * the selected draft's actions in the order a draft is worked on (settings, cut-outs, 3D edit, generation; test and
 * publish at the far end), its 3D preview as it will generate (cut-outs hidden) and its figures in tiles below. What
 * manages the drafts themselves (rescan, delete, new from the controller's selection) is in the header.
 * Double-clicking a container, spawner or entity in the preview opens the 3D editor on it. Test Place puts a small or
 * medium draft around the player and turns into Undo Test for the draft that has the world's test placement. While
 * there is no draft, the right side shows the three steps to the first one.
 */
public final class StructureEditorPage extends KineticPage implements EditorClient.Listener {
    static final int ROW_HEIGHT = 34, ROW_GAP = 2;
    private static final int LIST_X = 4, LIST_Y = 46, LIST_W = 300, PREVIEW_X = LIST_X + LIST_W + 4;
    private static final int THUMBNAIL = 28;
    static final int STAT_H = 24;
    private static final int INFO_H = 12;
    /** The tiles under the preview and their widths, which fill the preview's width with 2 between them. */
    private static final String[] STAT_KEYS = {"size", "blocks", "containers", "entities", "cutouts"};
    private static final int[] STAT_W = {80, 62, 66, 56, 56};
    private static final String[] EDIT_KEYS = {"settings", "cutouts", "markers", "generation"};
    private static final int[] EDIT_W = {48, 48, 48, 56};
    private static final int TEST_W = 56, PUBLISH_W = 48, NEW_W = 72, DELETE_W = 48, RESCAN_W = 56;
    private static final int PUBLISH_COLOR = 0x55FF55, DELETE_COLOR = 0xFF5555, ID_COLOR = 0x9AA4B2, TASK_COLOR = 0xFF55AAFF;
    private static final int GUIDE_STEPS = 3, GUIDE_STEP_H = 40;

    private String search = "";
    @Nullable private String selectedId;
    private ProjectList list;
    private KineticStructureView preview;
    private StructurePreviewClient.Listener previewListener;
    @Nullable private StructurePreviewClient.Result previewResult;
    @Nullable private Cutouts selectedCutouts;
    @Nullable private dev.xyat.realmcontrol.structure.project.Markers selectedMarkers;
    private int previewRequest;
    private KineticButton settingsButton, cutoutButton, markersButton, generationButton, rescanButton, publishButton, deleteButton, newButton, testButton;
    private long lastClick;
    private int lastClickIndex = -1;

    public StructureEditorPage(@Nullable String focus) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.selectedId = focus;
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
        int right = width() - 4;
        newButton = ui().button(right - NEW_W, 4, NEW_W).text(KineticI18n.translatable("gui.realmcontrol.structure.new"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.new.tooltip"))
                .onClick(() -> openChild(new NewProjectPage(this))).build();
        deleteButton = ui().button(right - NEW_W - 4 - DELETE_W, 4, DELETE_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.delete"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action.delete.tooltip"))
                .onClick(() -> action("delete")).build();
        rescanButton = ui().button(right - NEW_W - 4 - DELETE_W - 4 - RESCAN_W, 4, RESCAN_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.rescan"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action.rescan.tooltip"))
                .onClick(() -> action("rescan")).build();

        KineticTextField searchField = ui().textField(LIST_X, 28, LIST_W).placeholder(KineticI18n.translatable("gui.realmcontrol.structure.search")).build();
        searchField.setTextValue(search);
        searchField.onTextChange(value -> {
            search = value == null ? "" : value;
            refreshList();
        });

        int x = PREVIEW_X;
        KineticButton[] edit = new KineticButton[EDIT_KEYS.length];
        for (int i = 0; i < EDIT_KEYS.length; i++) {
            String key = EDIT_KEYS[i];
            edit[i] = ui().button(x, 28, EDIT_W[i]).text(KineticI18n.translatable("gui.realmcontrol.structure.action." + key))
                    .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action." + key + ".tooltip"))
                    .onClick(() -> action(key)).build();
            x += EDIT_W[i] + 4;
        }
        settingsButton = edit[0];
        cutoutButton = edit[1];
        markersButton = edit[2];
        generationButton = edit[3];
        publishButton = ui().button(right - PUBLISH_W, 28, PUBLISH_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.publish"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action.publish.tooltip"))
                .onClick(() -> action("publish")).build();
        testButton = ui().button(right - PUBLISH_W - 4 - TEST_W, 28, TEST_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.test"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action.test.tooltip", StructureProject.MEDIUM_LIMIT, StructureProject.MEDIUM_LIMIT))
                .onClick(() -> action("test")).build();

        list = ui.add(new ProjectList(LIST_X, LIST_Y + 2, LIST_W, height() - LIST_Y - 6));
        preview = ui.add(new KineticStructureView(PREVIEW_X, LIST_Y, width() - 4 - PREVIEW_X, statsY() - 2 - LIST_Y));
        preview.setPickListener(new KineticStructureView.PickListener() {
            @Override
            public void clicked(@Nullable KineticStructureView.Marker marker, MouseInput input) {
                preview.setSelection(marker == null ? null : marker.key(), marker == null ? List.of() : List.of(marker.key()));
            }

            @Override
            public void activated(KineticStructureView.Marker marker) {
                if (selectedId != null) StructureEditorNetwork.request(Kind.OPEN_MARKERS, selectedId, marker.key());
            }
        });
        previewListener = new StructurePreviewClient.Listener() {
            @Override
            public void progress(float share) {
                if (preview != null && preview.snapshot() == null) preview.setLoading(share);
            }

            @Override
            public void finished(StructurePreviewClient.Result result) {
                previewResult = result;
                showPreview();
            }

            @Override
            public void failed(Component reason) {
                if (preview != null) preview.setMessage(reason);
            }
        };
        refreshList();
        select(selected() != null ? selectedId : firstId());
        updateButtons();
    }

    /** Top of the tiles under the preview. */
    private int statsY() {
        return height() - 4 - INFO_H - STAT_H;
    }

    @Nullable
    private EditorClient.ProjectEntry selected() {
        if (selectedId == null) return null;
        for (EditorClient.ProjectEntry entry : EditorClient.projects()) {
            if (entry.project().id.equals(selectedId)) return entry;
        }
        return null;
    }

    /** The first draft, which is shown when none was asked for. */
    @Nullable
    private static String firstId() {
        List<EditorClient.ProjectEntry> projects = EditorClient.projects();
        return projects.isEmpty() ? null : projects.get(0).project().id;
    }

    private void refreshList() {
        if (list == null) return;
        String query = search.trim().toLowerCase(Locale.ROOT);
        list.setItems(EditorClient.projects().stream().filter(entry -> query.isEmpty()
                || entry.project().id.contains(query) || entry.project().name.toLowerCase(Locale.ROOT).contains(query)).toList());
    }

    private void select(@Nullable String id) {
        selectedId = id;
        previewResult = null;
        selectedCutouts = null;
        selectedMarkers = null;
        if (preview == null) return;
        // Without any draft the three steps to the first one take the preview's place.
        preview.setControlVisible(!EditorClient.projects().isEmpty());
        preview.setMarkers(List.of());
        preview.setSelection(null, List.of());
        preview.setSnapshot(null);
        preview.setMessage(null);
        EditorClient.ProjectEntry entry = selected();
        if (entry == null) {
            preview.setMessage(KineticI18n.translatable("gui.realmcontrol.structure.select"));
            StructurePreviewClient.cancel(previewListener);
        } else {
            preview.setLoading(0F);
            StructurePreviewClient.request(PREVIEW_PREFIX + entry.project().id, 0, previewListener);
            StructureEditorNetwork.request(Kind.DETAIL, entry.project().id, "");
            // The markers, so a double-click in the preview opens the 3D editor on one.
            StructureEditorNetwork.request(Kind.MARKERS, entry.project().id, "");
        }
        updateButtons();
    }

    static final String PREVIEW_PREFIX = dev.xyat.realmcontrol.structure.project.ProjectPreview.PREFIX;

    /** Shows the preview as the structure will generate: cut-out cells hidden. */
    private void showPreview() {
        StructurePreviewClient.Result result = previewResult;
        Cutouts cutouts = selectedCutouts;
        if (result == null || preview == null) return;
        if (cutouts == null || cutouts.isEmpty()) {
            preview.setSnapshot(result.snapshot());
            return;
        }
        int request = ++previewRequest;
        CompletableFuture.supplyAsync(() -> CutoutSnapshots.apply(result.snapshot(), cutouts, false)).thenAccept(snapshot ->
                dev.xyat.kineticcore.api.runtime.KineticClientRuntime.execute(() -> {
                    if (request == previewRequest && preview != null && previewResult == result) preview.setSnapshot(snapshot);
                }));
    }

    private void updateButtons() {
        EditorClient.ProjectEntry entry = selected();
        boolean has = entry != null;
        boolean busy = has && (entry.scanning() || EditorClient.task(entry.project().id) != null);
        for (KineticButton button : new KineticButton[]{settingsButton, cutoutButton, markersButton, generationButton}) {
            if (button != null) button.setEnabled(has && !busy);
        }
        // Publish in green and Delete in red while they can be pressed; a disabled button keeps the plain disabled look.
        if (publishButton != null) {
            publishButton.setEnabled(has && !busy);
            publishButton.setText(tinted("gui.realmcontrol.structure.action.publish", has && !busy ? PUBLISH_COLOR : -1));
        }
        if (deleteButton != null) {
            deleteButton.setEnabled(has && !busy);
            deleteButton.setText(tinted("gui.realmcontrol.structure.action.delete", has && !busy ? DELETE_COLOR : -1));
        }
        if (testButton != null) {
            boolean undo = has && testedHere(entry);
            testButton.setEnabled(has && !busy && (undo || !"mega".equals(entry.project().tier())));
            testButton.setText(KineticI18n.translatable(undo ? "gui.realmcontrol.structure.action.test_undo" : "gui.realmcontrol.structure.action.test"));
        }
        if (rescanButton != null) {
            rescanButton.setEnabled(has);
            StructureEditorNetwork.ScanProgressPacket task = has ? EditorClient.task(entry.project().id) : null;
            boolean placing = task != null && task.waiting() <= TestPlacement.PLACING;
            rescanButton.setText(KineticI18n.translatable(!busy ? "gui.realmcontrol.structure.action.rescan"
                    : placing ? "gui.realmcontrol.structure.action.stop_test" : "gui.realmcontrol.structure.action.cancel_scan"));
        }
        if (newButton != null) newButton.setEnabled(EditorClient.selection().region() != null);
    }

    /** A label in a colour, or as it is for {@code rgb} -1. */
    private static Component tinted(String key, int rgb) {
        Component text = KineticI18n.translatable(key);
        return rgb < 0 ? text : text.copy().withStyle(style -> style.withColor(rgb));
    }

    private void action(String key) {
        EditorClient.ProjectEntry entry = selected();
        if (entry == null) return;
        String id = entry.project().id;
        switch (key) {
            case "settings" -> openChild(new ProjectSettingsPage(entry.project()));
            case "cutouts" -> openChild(new CutoutPage(entry.project()));
            case "markers" -> StructureEditorNetwork.request(Kind.OPEN_MARKERS, id, "");
            case "generation" -> openChild(new GenerationPage(entry.project()));
            case "rescan" -> {
                if (entry.scanning() || EditorClient.task(id) != null) {
                    StructureEditorNetwork.request(Kind.CANCEL_SCAN, id, "");
                } else {
                    openDialog(KineticI18n.translatable("gui.realmcontrol.structure.rescan.title"),
                            KineticI18n.translatable("gui.realmcontrol.structure.rescan.message", entry.project().name),
                            KineticI18n.translatable("gui.realmcontrol.structure.action.rescan"),
                            KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"),
                            () -> StructureEditorNetwork.request(Kind.RESCAN, id, ""), () -> { });
                }
            }
            case "publish" -> openChild(new PublishPage(entry.project()));
            case "test" -> test(entry);
            case "delete" -> openDialog(KineticI18n.translatable("gui.realmcontrol.structure.delete.title"),
                    KineticI18n.translatable("gui.realmcontrol.structure.delete.message", entry.project().name),
                    KineticI18n.translatable("gui.realmcontrol.structure.action.delete"),
                    KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"),
                    () -> StructureEditorNetwork.request(Kind.DELETE, id, ""), () -> { });
            default -> {
            }
        }
    }

    private static boolean testedHere(EditorClient.ProjectEntry entry) {
        EditorClient.TestPlacementInfo test = EditorClient.testPlacement();
        return test != null && test.project().equals(entry.project().id);
    }

    /** Places the draft for a test, or undoes the world's test placement: this draft's, or another one's first. */
    private void test(EditorClient.ProjectEntry entry) {
        EditorClient.TestPlacementInfo test = EditorClient.testPlacement();
        StructureProject project = entry.project();
        if (test != null) {
            Component message = test.project().equals(project.id)
                    ? KineticI18n.translatable("gui.realmcontrol.structure.test.undo.message", test.name(), test.x(), test.y(), test.z())
                    : KineticI18n.translatable("gui.realmcontrol.structure.test.other.message", test.name(), test.x(), test.y(), test.z());
            openDialog(KineticI18n.translatable("gui.realmcontrol.structure.test.undo.title"), message,
                    KineticI18n.translatable("gui.realmcontrol.structure.action.test_undo"),
                    KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"),
                    () -> StructureEditorNetwork.request(Kind.TEST_UNDO, test.project(), ""), () -> { });
            return;
        }
        openDialog(KineticI18n.translatable("gui.realmcontrol.structure.test.title"),
                KineticI18n.translatable("gui.realmcontrol.structure.test.message", project.name, project.size[0], project.size[1], project.size[2], project.embed),
                KineticI18n.translatable("gui.realmcontrol.structure.action.test"),
                KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"),
                () -> StructureEditorNetwork.request(Kind.TEST_PLACE, project.id, ""), () -> { });
    }

    @Override
    public void projectsChanged(@Nullable String focus) {
        String keep = focus != null ? focus : selectedId;
        refreshList();
        if (focus != null) {
            // A finished scan or publish changed this draft: read its preview again.
            StructurePreviewClient.clearCache();
            select(keep);
        } else if (selected() == null) {
            select(firstId());
        }
        updateButtons();
    }

    @Override
    public void detailArrived(StructureProject project, Cutouts cutouts) {
        if (!project.id.equals(selectedId)) return;
        selectedCutouts = cutouts;
        showPreview();
        showMarkers();
    }

    @Override
    public void markersArrived(StructureProject project, dev.xyat.realmcontrol.structure.project.Markers markers, com.google.gson.JsonObject tables, String focus) {
        if (!project.id.equals(selectedId)) return;
        selectedMarkers = markers;
        showMarkers();
    }

    /** The containers, spawners and entities the preview can pick, none in a cut-out. */
    private void showMarkers() {
        if (preview == null || selectedMarkers == null) return;
        List<KineticStructureView.Marker> markers = new java.util.ArrayList<>();
        for (MarkersPage.Row row : MarkersPage.rows(selectedMarkers)) {
            if (!MarkersPage.cutOut(row, selectedCutouts)) markers.add(MarkersPage.viewMarker(row, 0));
        }
        preview.setMarkers(markers);
    }

    @Override
    public void progressChanged(String id) {
        updateButtons();
    }

    @Override
    protected void onTick() {
        updateButtons();
    }

    @Override
    protected void onRemoved() {
        StructurePreviewClient.cancel(previewListener);
        StructureThumbnailClient.stop();
        if (preview != null) preview.release();
        super.onRemoved();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
        KineticTheme.surface(g, LIST_X, LIST_Y, LIST_W, height() - LIST_Y - 4, KineticTheme.Surface.PANEL_ALT);
        if (EditorClient.projects().isEmpty()) {
            KineticTheme.surface(g, PREVIEW_X, LIST_Y, width() - 4 - PREVIEW_X, height() - LIST_Y - 4, KineticTheme.Surface.PANEL_ALT);
        }
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - NEW_W - 4 - DELETE_W - 4 - RESCAN_W - 4 - width() / 2));
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.title"), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        // The rows drawn this frame said which pictures they show; the missing ones are asked for together.
        StructureThumbnailClient.flush();
        if (EditorClient.projects().isEmpty()) {
            guide(g);
            return;
        }
        EditorClient.ProjectEntry entry = selected();
        if (entry != null) stats(g, entry);
    }

    /** The selected draft's figures in tiles, and below them where it was scanned or what is wrong with it. */
    private void stats(KineticGraphics g, EditorClient.ProjectEntry entry) {
        StructureProject project = entry.project();
        KineticTheme.Palette palette = KineticTheme.current();
        String[] values = {
                project.size[0] + "×" + project.size[1] + "×" + project.size[2],
                String.format(Locale.ROOT, "%,d", project.blocks),
                String.valueOf(project.containers),
                String.valueOf(project.entities),
                String.valueOf(entry.cutoutCount())
        };
        int x = PREVIEW_X, y = statsY();
        for (int i = 0; i < STAT_KEYS.length; i++) {
            // A count of nothing steps back.
            statTile(g, x, y, STAT_W[i], KineticI18n.translatable("gui.realmcontrol.structure.stat." + STAT_KEYS[i]), Component.literal(values[i]),
                    values[i].equals("0") ? palette.mutedText() : palette.text());
            x += STAT_W[i] + 2;
        }
        Component info;
        int color;
        if (!project.missing.isEmpty()) {
            info = KineticI18n.translatable("gui.realmcontrol.worldgen.preview.missing", missingText(project));
            color = 0xFFFF5555;
        } else if (project.entityChunksSkipped > 0) {
            info = KineticI18n.translatable("gui.realmcontrol.structure.facts.entities_skipped", project.entityChunksSkipped);
            color = 0xFFFFAA00;
        } else {
            info = KineticI18n.translatable("gui.realmcontrol.structure.facts.origin", GenerationPage.dimensionName(project.dimension),
                    project.origin[0], project.origin[1], project.origin[2]);
            color = palette.mutedText();
        }
        g.scrollingText(info, PREVIEW_X + 4, y + STAT_H + 3, width() - 4 - PREVIEW_X - 8, color, false);
    }

    /**
     * The three steps to a first draft: take the controller, mark the region, make the draft. Steps already done are
     * green and the one to do next is yellow.
     */
    private void guide(KineticGraphics g) {
        KineticTheme.Palette palette = KineticTheme.current();
        int x = PREVIEW_X + 10, w = width() - 4 - PREVIEW_X - 20, y = LIST_Y + 10;
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.guide.title"), x, y, w, 0xFFFFAA00, false);
        y += 18;
        boolean region = EditorClient.selection().region() != null;
        boolean[] done = {region || ControllerClient.holding(), region, false};
        int next = !done[0] ? 0 : !done[1] ? 1 : 2;
        for (int step = 0; step < GUIDE_STEPS; step++) {
            KineticTheme.Indicator indicator = done[step] ? KineticTheme.Indicator.SUCCESS
                    : step == next ? KineticTheme.Indicator.WARNING : KineticTheme.Indicator.MUTED;
            int textX = x + KineticTheme.tag(g, Component.literal(String.valueOf(step + 1)), x, y, indicator) + 6;
            String key = "gui.realmcontrol.structure.guide.step" + (step + 1);
            g.scrollingText(KineticI18n.translatable(key), textX, y + 2, x + w - textX, step == next ? palette.text() : palette.mutedText(), false);
            g.wrappedText(step == 2 ? KineticI18n.translatable(key + ".detail", ControllerClient.editorKeyName()) : KineticI18n.translatable(key + ".detail"),
                    textX, y + 14, x + w - textX, palette.mutedText());
            y += GUIDE_STEP_H;
        }
    }

    /** One figure in a tile {@link #STAT_H} high: what it is in muted text, and its value below. */
    static void statTile(KineticGraphics g, int x, int y, int width, Component label, Component value, int valueColor) {
        KineticTheme.surface(g, x, y, width, STAT_H, KineticTheme.Surface.PANEL_ALT);
        g.scrollingText(label, x + 4, y + 3, width - 8, KineticTheme.current().mutedText(), false);
        g.scrollingText(value, x + 4, y + 13, width - 8, valueColor, false);
    }

    /** The tier a region of this footprint becomes: small, medium or mega. */
    static String tierOf(Selection.Box region) {
        int footprint = Math.max(region.sizeX(), region.sizeZ());
        return footprint <= StructureProject.SMALL_LIMIT ? "small" : footprint <= StructureProject.MEDIUM_LIMIT ? "medium" : "mega";
    }

    static String missingText(StructureProject project) {
        return project.missing.entrySet().stream().map(e -> e.getKey() + " ×" + e.getValue()).collect(Collectors.joining("、"));
    }

    /** What a running task of a draft is doing, for its row; {@code null} when none runs. */
    @Nullable
    static Component taskText(StructureProject project) {
        StructureEditorNetwork.ScanProgressPacket task = EditorClient.task(project.id);
        if (task == null) return null;
        if (task.waiting() > 0) return KineticI18n.translatable("gui.realmcontrol.structure.row.waiting", task.waiting());
        String key = switch (task.waiting()) {
            case 0 -> "scanning";
            case -1 -> "publishing";
            case TestPlacement.PLACING -> "placing";
            default -> "undoing";
        };
        return KineticI18n.translatable("gui.realmcontrol.structure.row." + key, Math.round(task.share() * 100F));
    }

    /** How a draft's state is shown: a plain draft blue, changes not yet published yellow, published green. */
    private static KineticTheme.Indicator statusIndicator(String status) {
        return switch (status) {
            case "published" -> KineticTheme.Indicator.SUCCESS;
            case "modified" -> KineticTheme.Indicator.WARNING;
            default -> KineticTheme.Indicator.INFO;
        };
    }

    /**
     * Draft rows: a small picture, then the name with the tier and state as tags at the right, and below it the id
     * and size, or what a running task is doing. Red outline when blocks are missing, yellow when selected.
     */
    private final class ProjectList extends KineticRowList<EditorClient.ProjectEntry> {
        ProjectList(int x, int y, int width, int height) {
            super(x, y, width, height, ROW_HEIGHT);
        }

        @Override
        protected void renderRowBackground(KineticGraphics g, int index, int x, int y, int width, int height, boolean hovered, boolean selected) {
        }

        @Override
        protected void renderRow(KineticGraphics g, EditorClient.ProjectEntry entry, int index, int x, int t, int rw, int rh,
                                 boolean hovered, boolean rowSelected) {
            int l = controlX() + 2, w = controlWidth() - 12, h = ROW_HEIGHT - ROW_GAP;
            StructureProject project = entry.project();
            KineticTheme.surface(g, l, t, w, h, index % 2 == 0 ? KineticTheme.Surface.PANEL : KineticTheme.Surface.PANEL_ALT);
            if (project.id.equals(selectedId)) {
                KineticTheme.stateOutline(g, l, t, w, h, true, false, false);
            } else if (hovered) {
                KineticTheme.stateOutline(g, l, t, w, h, false, true, false);
            } else if (!project.missing.isEmpty()) {
                KineticTheme.indicatorOutline(g, l, t, w, h, KineticTheme.Indicator.DANGER);
            } else {
                KineticTheme.indicatorOutline(g, l, t, w, h, KineticTheme.Indicator.MUTED);
            }

            int thumbX = l + 2, thumbY = t + (h - THUMBNAIL) / 2;
            String pictureId = PREVIEW_PREFIX + project.id;
            KineticTexture thumbnail = StructureThumbnailClient.get(pictureId);
            if (thumbnail != null) {
                g.texture(thumbnail, thumbX, thumbY, THUMBNAIL, THUMBNAIL, 0, 0, thumbnail.textureWidth(), thumbnail.textureHeight());
            } else {
                g.fill(thumbX, thumbY, thumbX + THUMBNAIL, thumbY + THUMBNAIL, 0x22FFFFFF);
                if (!StructureThumbnailClient.none(pictureId)) {
                    g.scrollingTextCentered(Component.literal("…"), thumbX + THUMBNAIL / 2, thumbY + (THUMBNAIL - g.lineHeight()) / 2, THUMBNAIL, 0xFF888888, false);
                }
            }

            int textX = thumbX + THUMBNAIL + 4, right = l + w - 4, lineH = g.lineHeight();
            // Two lines 3 apart: the tags on the first one are a line and 3 high.
            int firstY = t + (h - (lineH * 2 + 3)) / 2, secondY = firstY + lineH + 3;
            int tagY = firstY - 2, tagX = right;
            if (!project.missing.isEmpty()) {
                Component missing = KineticI18n.translatable("gui.realmcontrol.structure.row.missing");
                tagX -= KineticTheme.tagWidth(g, missing);
                KineticTheme.tag(g, missing, tagX, tagY, KineticTheme.Indicator.DANGER);
                tagX -= 2;
            }
            Component status = KineticI18n.translatable("gui.realmcontrol.structure.status." + project.status);
            tagX -= KineticTheme.tagWidth(g, status);
            KineticTheme.tag(g, status, tagX, tagY, statusIndicator(project.status));
            tagX -= 2;
            Component tier = KineticI18n.translatable("gui.realmcontrol.structure.tier." + project.tier());
            tagX -= KineticTheme.tagWidth(g, tier);
            KineticTheme.tag(g, tier, tagX, tagY, KineticTheme.Indicator.MUTED);
            g.scrollingText(Component.literal(project.name), textX, firstY, Math.max(1, tagX - 4 - textX), 0xFFFFFFFF, false);

            Component task = taskText(project);
            if (task != null) {
                g.scrollingText(task, textX, secondY, right - textX, TASK_COLOR, false);
            } else {
                g.scrollingText(Component.literal(project.id).withStyle(style -> style.withColor(ID_COLOR))
                                .append(Component.literal("  " + project.size[0] + "×" + project.size[1] + "×" + project.size[2])
                                        .withStyle(style -> style.withColor(0xCCCCCC))),
                        textX, secondY, right - textX, 0xFFFFFFFF, false);
            }
            StructureEditorNetwork.ScanProgressPacket running = EditorClient.task(project.id);
            if (running != null && running.waiting() <= 0) {
                g.fill(l + 1, t + h - 3, l + 1 + Math.round((w - 2) * running.share()), t + h - 1, TASK_COLOR);
            }
        }

        @Override
        protected boolean onRowClick(EditorClient.ProjectEntry entry, int index, MouseInput input) {
            if (!input.isLeft()) return false;
            if (!entry.project().id.equals(selectedId)) StructureEditorPage.this.select(entry.project().id);
            long now = System.nanoTime();
            boolean doubleClick = index == lastClickIndex && now - lastClick < 350_000_000L;
            lastClickIndex = index;
            lastClick = doubleClick ? 0L : now;
            if (doubleClick && settingsButton != null && settingsButton.isEnabled()) {
                playClickSound();
                action("settings");
            }
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable(EditorClient.projects().isEmpty() ? "gui.realmcontrol.structure.empty" : "gui.realmcontrol.structure.no_match");
        }
    }

}
