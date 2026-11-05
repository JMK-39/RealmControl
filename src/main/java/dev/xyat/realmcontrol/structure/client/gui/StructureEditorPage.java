package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticStructureView;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.CutoutSnapshots;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork.ProjectRequestPacket.Kind;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.publish.TestPlacement;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The structure editor's first page: the drafts as text rows on the left, the selected one's 3D preview (cut-outs
 * hidden, as it will generate) and facts on the right, and its actions above them. New drafts come from the
 * controller's selection. Test Place puts a small or medium draft around the player and turns into Undo Test for the
 * draft that has the world's test placement.
 */
public final class StructureEditorPage extends KineticPage implements EditorClient.Listener {
    static final int ROW_HEIGHT = 34, ROW_GAP = 2;
    private static final int LIST_X = 4, LIST_Y = 46, LIST_W = 300, PREVIEW_X = LIST_X + LIST_W + 4;
    private static final int FACT_LINES = 3, FACT_LINE_H = 12;
    private static final int[] ACTION_W = {48, 48, 48, 56, 52, 48};
    private static final int NEW_W = 72, DELETE_W = 48, TEST_W = 56;

    private String search = "";
    @Nullable private String selectedId;
    private ProjectList list;
    private KineticStructureView preview;
    private StructurePreviewClient.Listener previewListener;
    @Nullable private StructurePreviewClient.Result previewResult;
    @Nullable private Cutouts selectedCutouts;
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
        newButton = ui().button(width() - 4 - NEW_W, 4, NEW_W).text(KineticI18n.translatable("gui.realmcontrol.structure.new"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.new.tooltip"))
                .onClick(() -> openChild(new NewProjectPage(this))).build();
        deleteButton = ui().button(width() - 4 - NEW_W - 4 - DELETE_W, 4, DELETE_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.delete"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action.delete.tooltip"))
                .onClick(() -> action("delete")).build();
        testButton = ui().button(width() - 4 - NEW_W - 4 - DELETE_W - 4 - TEST_W, 4, TEST_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.test"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action.test.tooltip", StructureProject.MEDIUM_LIMIT, StructureProject.MEDIUM_LIMIT))
                .onClick(() -> action("test")).build();

        KineticTextField searchField = ui().textField(LIST_X, 28, LIST_W).placeholder(KineticI18n.translatable("gui.realmcontrol.structure.search")).build();
        searchField.setTextValue(search);
        searchField.onTextChange(value -> {
            search = value == null ? "" : value;
            refreshList();
        });

        int x = PREVIEW_X;
        String[] keys = {"settings", "cutouts", "markers", "generation", "rescan", "publish"};
        KineticButton[] buttons = new KineticButton[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            buttons[i] = ui().button(x, 28, ACTION_W[i]).text(KineticI18n.translatable("gui.realmcontrol.structure.action." + key))
                    .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.action." + key + ".tooltip"))
                    .onClick(() -> action(key)).build();
            x += ACTION_W[i] + 4;
        }
        settingsButton = buttons[0];
        cutoutButton = buttons[1];
        markersButton = buttons[2];
        generationButton = buttons[3];
        rescanButton = buttons[4];
        publishButton = buttons[5];

        list = ui.add(new ProjectList(LIST_X, LIST_Y + 2, LIST_W, height() - LIST_Y - 6));
        preview = ui.add(new KineticStructureView(PREVIEW_X, LIST_Y, width() - 4 - PREVIEW_X, factsY() - 4 - LIST_Y));
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
        select(selectedId);
        updateButtons();
    }

    private int factsY() {
        return height() - 4 - FACT_LINES * FACT_LINE_H;
    }

    @Nullable
    private EditorClient.ProjectEntry selected() {
        if (selectedId == null) return null;
        for (EditorClient.ProjectEntry entry : EditorClient.projects()) {
            if (entry.project().id.equals(selectedId)) return entry;
        }
        return null;
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
        if (preview == null) return;
        preview.setSnapshot(null);
        preview.setMessage(null);
        EditorClient.ProjectEntry entry = selected();
        if (entry == null) {
            preview.setMessage(KineticI18n.translatable(EditorClient.projects().isEmpty()
                    ? "gui.realmcontrol.structure.empty" : "gui.realmcontrol.structure.select"));
            StructurePreviewClient.cancel(previewListener);
        } else {
            preview.setLoading(0F);
            StructurePreviewClient.request(PREVIEW_PREFIX + entry.project().id, 0, previewListener);
            StructureEditorNetwork.request(Kind.DETAIL, entry.project().id, "");
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
        for (KineticButton button : new KineticButton[]{settingsButton, cutoutButton, markersButton, generationButton, deleteButton}) {
            if (button != null) button.setEnabled(has && !busy);
        }
        if (publishButton != null) publishButton.setEnabled(has && !busy);
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
            select(null);
        }
        updateButtons();
    }

    @Override
    public void detailArrived(StructureProject project, Cutouts cutouts) {
        if (!project.id.equals(selectedId)) return;
        selectedCutouts = cutouts;
        showPreview();
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
        if (preview != null) preview.release();
        super.onRemoved();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
        KineticTheme.surface(g, LIST_X, LIST_Y, LIST_W, height() - LIST_Y - 4, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - NEW_W - 4 - DELETE_W - 4 - TEST_W - 4 - width() / 2));
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.title"), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        EditorClient.ProjectEntry entry = selected();
        if (entry == null) return;
        int w = width() - 4 - PREVIEW_X, y = factsY() + 2;
        StructureProject project = entry.project();
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.facts.size", project.size[0], project.size[1], project.size[2],
                KineticI18n.translatable("gui.realmcontrol.structure.tier." + project.tier())), PREVIEW_X, y, w, 0xFFFFFFFF, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.facts.counts", project.blocks, project.containers,
                project.entities, entry.cutoutCount()), PREVIEW_X, y + FACT_LINE_H, w, 0xFFCCCCCC, false);
        Component third;
        int color;
        if (!project.missing.isEmpty()) {
            third = KineticI18n.translatable("gui.realmcontrol.worldgen.preview.missing", missingText(project));
            color = 0xFFFF5555;
        } else if (project.entityChunksSkipped > 0) {
            third = KineticI18n.translatable("gui.realmcontrol.structure.facts.entities_skipped", project.entityChunksSkipped);
            color = 0xFFFFAA00;
        } else {
            third = KineticI18n.translatable("gui.realmcontrol.structure.facts.origin", GenerationPage.dimensionName(project.dimension),
                    project.origin[0], project.origin[1], project.origin[2]);
            color = 0xFFCCCCCC;
        }
        g.scrollingText(third, PREVIEW_X, y + 2 * FACT_LINE_H, w, color, false);
    }

    static String missingText(StructureProject project) {
        return project.missing.entrySet().stream().map(e -> e.getKey() + " ×" + e.getValue()).collect(Collectors.joining("、"));
    }

    /** The second line of a row: tier, state, size and counts, or the progress of a running task. */
    static Component rowSummary(EditorClient.ProjectEntry entry) {
        StructureProject project = entry.project();
        StructureEditorNetwork.ScanProgressPacket task = EditorClient.task(project.id);
        if (task != null) {
            if (task.waiting() > 0) return KineticI18n.translatable("gui.realmcontrol.structure.row.waiting", task.waiting());
            String key = switch (task.waiting()) {
                case 0 -> "scanning";
                case -1 -> "publishing";
                case TestPlacement.PLACING -> "placing";
                default -> "undoing";
            };
            return KineticI18n.translatable("gui.realmcontrol.structure.row." + key, Math.round(task.share() * 100F));
        }
        MutableComponent text = KineticI18n.translatable("gui.realmcontrol.structure.tier." + project.tier()).copy()
                .append(Component.literal(" "))
                .append(KineticI18n.translatable("gui.realmcontrol.structure.status." + project.status))
                .append(Component.literal("  " + project.size[0] + "×" + project.size[1] + "×" + project.size[2]));
        if (entry.cutoutCount() > 0) {
            text.append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.row.cutouts", entry.cutoutCount()));
        }
        if (!project.missing.isEmpty()) {
            text.append(Component.literal("  ")).append(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_missing_tag"));
        }
        return text;
    }

    /** Draft rows: name and id, then the summary; red outline when blocks are missing, yellow when selected. */
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
            int textX = l + 6, maxW = w - 12, lineH = g.lineHeight();
            int firstY = t + (h - (lineH * 2 + 1)) / 2;
            g.scrollingText(Component.literal(project.name + "  ").append(Component.literal(project.id).withStyle(style -> style.withColor(0x9AA4B2))),
                    textX, firstY, maxW, 0xFFFFFFFF, false);
            g.scrollingText(rowSummary(entry), textX, firstY + lineH + 1, maxW, 0xFFCCCCCC, false);
            StructureEditorNetwork.ScanProgressPacket task = EditorClient.task(project.id);
            if (task != null && task.waiting() <= 0) {
                g.fill(l + 1, t + h - 3, l + 1 + Math.round((w - 2) * task.share()), t + h - 1, 0xFF55AAFF);
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
            return KineticI18n.translatable("gui.realmcontrol.structure.empty");
        }
    }

    /** The selection's facts for the new draft page: size, blocks, tier and cut-out boxes. */
    static List<Component> selectionFacts(Selection selection) {
        Selection.Box region = selection.region();
        if (region == null) return List.of(KineticI18n.translatable("gui.realmcontrol.structure.new.no_selection"));
        int footprint = Math.max(region.sizeX(), region.sizeZ());
        String tier = footprint <= StructureProject.SMALL_LIMIT ? "small" : footprint <= StructureProject.MEDIUM_LIMIT ? "medium" : "mega";
        return List.of(
                KineticI18n.translatable("gui.realmcontrol.structure.facts.size", region.sizeX(), region.sizeY(), region.sizeZ(),
                        KineticI18n.translatable("gui.realmcontrol.structure.tier." + tier)),
                KineticI18n.translatable("gui.realmcontrol.structure.new.blocks", region.volume(), selection.cutouts.size()),
                KineticI18n.translatable("gui.realmcontrol.structure.new.corners", region.min().getX(), region.min().getY(), region.min().getZ(),
                        region.max().getX(), region.max().getY(), region.max().getZ()));
    }
}
