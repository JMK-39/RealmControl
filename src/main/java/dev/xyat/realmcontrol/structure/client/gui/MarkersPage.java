package dev.xyat.realmcontrol.structure.client.gui;

import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.selector.KineticSelectors;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticEntityPreview;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.client.gui.widget.KineticStructureView;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.KineticToggle;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.inventory.KineticItemText;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.realmcontrol.structure.client.CutoutSnapshots;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.client.EntityModels;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork.ProjectRequestPacket.Kind;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.EntityEdits;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The 3D editor of a draft: the structure in a view on the left, handled as in a modelling program, and an inspector
 * on the right. Containers, spawners and entities are outlined under the pointer; a click selects one (yellow),
 * Ctrl + click adds or removes one (green), a double-click brings the camera to it and a click on empty space goes
 * back to the list. With nothing selected the inspector lists them all as text rows (click selects, Ctrl + click and
 * Shift + click select several); with one selected it edits it: a container keeps its items or gets a loot table
 * (picked, created, or edited in ContentStudio), a spawner keeps what it spawns or gets new mobs, an entity is kept or
 * left out and gets a name, health, AI, staying and equipment, or any data in the NBT editor. A change applies to
 * every selected marker of the current one's kind; entity data to one entity at a time. Changes are kept until Save.
 */
public final class MarkersPage extends KineticPage implements EditorClient.Listener {
    private static final int TOP = 28, INSPECTOR_W = 228, ROW_HEIGHT = 34, ROW_GAP = 2, ROW_STEP = 22, LABEL_W = 56;
    private static final int SLOT = 18, MODEL_W = 60, MODEL_H = 86;
    /** How long name and health typing rests before it is applied. */
    private static final long TYPING_NANOS = 500_000_000L;
    private static final String CONTENTSTUDIO = "contentstudio";
    private static final int CONFIGURED = KineticTheme.indicatorColor(KineticTheme.Indicator.INFO);
    private static final int LEFT_OUT = KineticTheme.indicatorColor(KineticTheme.Indicator.DANGER);

    /** What a marker stands for. */
    enum MarkerKind {
        CONTAINER, SPAWNER, ENTITY
    }

    /** A container, spawner or entity, by its marker key. */
    record Row(MarkerKind kind, String key, @Nullable Markers.Container container, @Nullable Markers.Spawner spawner, @Nullable Markers.EntityMark entity) {
    }

    private final StructureProject project;
    private Markers markers;
    private Markers saved;
    private JsonObject tables;
    private boolean dirty;
    private boolean syncing;
    private String filter = "all";
    private String search = "";
    @Nullable private String current;
    private final Set<String> selected = new LinkedHashSet<>();

    private KineticStructureView view;
    private RowList list;
    private KineticDropdown filterBox;
    private KineticTextField searchField;
    private KineticButton listButton;
    private KineticDropdown containerMode, spawnerMode;
    private KineticTextField tableField;
    private KineticButton pickButton, newTableButton, editTableButton, spawnerButton;
    private KineticToggle keepToggle, aiToggle, stayToggle;
    private KineticTextField nameField;
    private KineticNumberField healthField;
    private KineticButton dataButton, revertButton;

    private StructurePreviewClient.Listener previewListener;
    @Nullable private StructurePreviewClient.Result previewResult;
    @Nullable private Cutouts cutouts;
    @Nullable private KineticBlockSnapshot shown;
    private int previewRequest;

    // Entity data the server sent: what the scan saw, by key. Edited data lives in the markers until Save.
    private final Map<String, String> scannedData = new HashMap<>();
    // Requests in flight, by key, and field changes waiting for them.
    private final Set<String> inFlight = new LinkedHashSet<>();
    private final Map<String, JsonObject> queued = new HashMap<>();
    private final KineticEntityPreview modelPreview = KineticEntityPreview.create();
    @Nullable private Entity model;
    @Nullable private String modelFor;
    private boolean nameTyped, healthTyped;
    private long typedAt;

    /**
     * {@code focus}: marker keys separated by {@code ;}, the first one current, all of them selected; an entity type
     * ({@code e:<type>}) stands for every entity of it.
     */
    public MarkersPage(StructureProject project, Markers markers, JsonObject tables, String focus) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.markers.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.project = project;
        this.markers = markers.copy();
        this.saved = markers.copy();
        this.tables = tables;
        for (String key : focus.split(";")) {
            if (key.isBlank()) continue;
            List<String> keys = new ArrayList<>();
            if (key.startsWith("e:") && key.indexOf('@') < 0) {
                for (Markers.EntityMark mark : this.markers.entities) if (mark.type.equals(key.substring(2))) keys.add(mark.key());
            } else {
                keys.add(key);
            }
            for (String one : keys) {
                if (current == null) current = one;
                selected.add(one);
            }
        }
    }

    StructureProject project() {
        return project;
    }

    private int inspectorX() {
        return width() - 4 - INSPECTOR_W;
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
        ui().button(width() - 4 - 48, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_all")).onClick(this::save).build();
        view = ui.add(new KineticStructureView(4, TOP, inspectorX() - 8, height() - TOP - 4));
        view.setPickListener(new KineticStructureView.PickListener() {
            @Override
            public void clicked(@Nullable KineticStructureView.Marker marker, MouseInput input) {
                pick(marker, input);
            }

            @Override
            public void activated(KineticStructureView.Marker marker) {
                lookAt(marker);
            }
        });

        int x = inspectorX(), filterW = 76;
        List<KineticDropdown.Option> filters = new ArrayList<>();
        for (String option : List.of("all", "containers", "spawners", "entities")) {
            filters.add(new KineticDropdown.Option(option, KineticI18n.translatable("gui.realmcontrol.structure.markers.filter." + option)));
        }
        filterBox = ui().dropdown(x, TOP, filterW, filters).selected(filter).onChange(value -> {
            filter = value;
            refreshList();
            refreshMarkers();
        }).build();
        searchField = ui().textField(x + filterW + 4, TOP, INSPECTOR_W - filterW - 4)
                .placeholder(KineticI18n.translatable("gui.realmcontrol.structure.markers.search")).build();
        searchField.setTextValue(search);
        searchField.onTextChange(value -> {
            search = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            refreshList();
        });
        list = ui.add(new RowList(x, TOP + 24, INSPECTOR_W, height() - TOP - 24 - 4));
        listButton = ui().button(x, TOP, 40).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.list"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.list.tooltip"))
                .onClick(() -> select(null, false)).build();

        int y = TOP + 24, fieldX = x + LABEL_W + 4, fieldW = INSPECTOR_W - LABEL_W - 4;
        // Containers.
        containerMode = ui().dropdown(fieldX, y, fieldW, List.of(
                new KineticDropdown.Option("keep", KineticI18n.translatable("gui.realmcontrol.structure.markers.container.keep"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.container.keep.tooltip")),
                new KineticDropdown.Option("table", KineticI18n.translatable("gui.realmcontrol.structure.markers.container.table"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.container.table.tooltip"))))
                .selected("keep").onChange(value -> {
                    if (syncing) return;
                    forContainers(container -> container.mode = container.lootable ? value : "keep");
                    changed();
                }).build();
        int pickW = 44;
        tableField = ui().textField(fieldX, y + ROW_STEP, fieldW - pickW - 4)
                .placeholder(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.placeholder")).build();
        tableField.limitTextLength(256);
        tableField.setValidator(value -> value.isBlank() || StructureProject.validId(value.trim().toLowerCase(Locale.ROOT)));
        tableField.onTextChange(value -> {
            if (syncing || value == null) return;
            String table = value.trim().toLowerCase(Locale.ROOT);
            forContainers(container -> {
                container.table = table;
                if (container.lootable && !table.isEmpty()) container.mode = "table";
            });
            changed();
        });
        pickButton = ui().button(fieldX + fieldW - pickW, y + ROW_STEP, pickW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.pick"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.pick.tooltip"))
                .onClick(() -> openChild(new LootTablePickerPage(tableField.textValue().trim(), this::useTable))).build();
        int halfW = (fieldW - 4) / 2;
        newTableButton = ui().button(fieldX, y + 2 * ROW_STEP, halfW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.new"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.new.tooltip"))
                .onClick(() -> {
                    Markers.Container container = currentContainer();
                    if (container != null) openChild(new NewLootTablePage(this, container));
                }).build();
        editTableButton = ui().button(fieldX + halfW + 4, y + 2 * ROW_STEP, fieldW - halfW - 4).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.edit"))
                .tooltip(() -> KineticI18n.translatable(KineticPlatform.isModLoaded(CONTENTSTUDIO)
                        ? "gui.realmcontrol.structure.markers.table.edit.tooltip" : "gui.realmcontrol.structure.markers.table.edit.missing"))
                .onClick(this::editTable).build();
        // Spawners.
        spawnerMode = ui().dropdown(fieldX, y, fieldW, List.of(
                new KineticDropdown.Option("keep", KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.keep")),
                new KineticDropdown.Option("custom", KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.custom"))))
                .selected("keep").onChange(value -> {
                    if (syncing) return;
                    forSpawners(spawner -> spawner.mode = value);
                    changed();
                }).build();
        spawnerButton = ui().button(fieldX, y + ROW_STEP, fieldW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.edit"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.edit.tooltip"))
                .onClick(() -> {
                    List<Markers.Spawner> targets = selectedSpawners();
                    if (!targets.isEmpty()) openChild(new SpawnerPage(this, targets));
                }).build();
        // Entities: the model on the left, its fields beside it.
        int entityX = x + MODEL_W + 4, entityW = INSPECTOR_W - MODEL_W - 4, toggleW = (entityW - 4) / 2;
        keepToggle = ui().toggle(entityX, y, entityW).labels(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.kept"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.dropped"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.keep.tooltip"))
                .onChange(value -> {
                    if (syncing) return;
                    for (Row row : selectedRows(MarkerKind.ENTITY)) {
                        Markers.EntityMark mark = row.entity();
                        mark.keep = value == markers.keepsType(mark.type) ? null : value;
                    }
                    changed();
                }).build();
        nameField = ui().textField(entityX, y + ROW_STEP, entityW)
                .placeholder(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.name")).build();
        nameField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.name.tooltip"));
        nameField.limitTextLength(64);
        nameField.onTextChange(value -> {
            if (syncing) return;
            nameTyped = true;
            typedAt = System.nanoTime();
        });
        healthField = ui().numberField(entityX, y + 2 * ROW_STEP, entityW, NumberType.DECIMAL)
                .range(1, EntityEdits.MAX_HEALTH).optional().build();
        healthField.setPlaceholder(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.health"));
        healthField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.health.tooltip", (int) EntityEdits.MAX_HEALTH));
        healthField.onTextChange(value -> {
            if (syncing) return;
            healthTyped = true;
            typedAt = System.nanoTime();
        });
        aiToggle = ui().toggle(entityX, y + 3 * ROW_STEP, toggleW).labels(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.ai_on"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.ai_off"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.ai.tooltip"))
                .onChange(value -> {
                    if (syncing) return;
                    JsonObject fields = new JsonObject();
                    fields.addProperty("no_ai", !value);
                    applyFields(fields);
                }).build();
        stayToggle = ui().toggle(entityX + toggleW + 4, y + 3 * ROW_STEP, entityW - toggleW - 4)
                .labels(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.stays"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.despawns"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.stay.tooltip"))
                .onChange(value -> {
                    if (syncing) return;
                    JsonObject fields = new JsonObject();
                    fields.addProperty("persistent", value);
                    applyFields(fields);
                }).build();
        int revertW = 52, dataY = slotsY() + SLOT + 4;
        dataButton = ui().button(x, dataY, INSPECTOR_W - revertW - 4).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.data"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.data.tooltip"))
                .onClick(this::editData).build();
        revertButton = ui().button(x + INSPECTOR_W - revertW, dataY, revertW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.revert"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.revert.tooltip"))
                .onClick(this::revertData).build();

        previewListener = new StructurePreviewClient.Listener() {
            @Override
            public void progress(float share) {
                if (view != null && view.snapshot() == null) view.setLoading(share);
            }

            @Override
            public void finished(StructurePreviewClient.Result result) {
                previewResult = result;
                showPreview();
            }

            @Override
            public void failed(Component reason) {
                if (view != null) view.setMessage(reason);
            }
        };
        if (shown != null) {
            view.setSnapshot(shown);
        } else {
            view.setLoading(0F);
            StructurePreviewClient.request(StructureEditorPage.PREVIEW_PREFIX + project.id, 0, previewListener);
            StructureEditorNetwork.request(Kind.DETAIL, project.id, "");
        }
        refreshList();
        refreshMarkers();
        syncDetail();
        Row row = currentRow();
        if (row != null && row.entity() != null) requestEntity(row.entity());
    }

    private int slotsY() {
        return TOP + 24 + MODEL_H + 4;
    }

    private int slotX(int index) {
        return inspectorX() + LABEL_W + 4 + index * (SLOT + 2);
    }

    // ---- the view ----

    /** Shows the draft as it will generate: cut-out cells hidden. */
    private void showPreview() {
        StructurePreviewClient.Result result = previewResult;
        Cutouts cut = cutouts;
        if (result == null || view == null) return;
        if (cut == null || cut.isEmpty()) {
            show(result.snapshot());
            return;
        }
        int request = ++previewRequest;
        CompletableFuture.supplyAsync(() -> CutoutSnapshots.apply(result.snapshot(), cut, false)).thenAccept(snapshot ->
                dev.xyat.kineticcore.api.runtime.KineticClientRuntime.execute(() -> {
                    if (request == previewRequest && view != null && previewResult == result) show(snapshot);
                }));
    }

    private void show(KineticBlockSnapshot snapshot) {
        boolean first = shown == null;
        shown = snapshot;
        view.setSnapshot(snapshot);
        refreshMarkers();
        refreshEntities();
        Row row = currentRow();
        if (first && row != null) lookAt(row);
    }

    /** The markers of the filter, outlined blue when set up and red when left out, none in a cut-out. */
    private void refreshMarkers() {
        if (view == null) return;
        List<KineticStructureView.Marker> list = new ArrayList<>();
        for (Row row : allRows()) {
            if (!shownByFilter(row)) continue;
            if (cutOut(row, cutouts)) continue;
            int color = switch (row.kind()) {
                case CONTAINER -> row.container().usesTable() ? CONFIGURED : 0;
                case SPAWNER -> row.spawner().custom() ? CONFIGURED : 0;
                case ENTITY -> !markers.keeps(row.entity()) ? LEFT_OUT : row.entity().data != null ? CONFIGURED : 0;
            };
            list.add(viewMarker(row, color));
        }
        view.setMarkers(list);
        view.setSelection(current, selected);
        view.setMarkersShown(shown != null && shown.scale() > 1);
    }

    /** Whether a marker is in a cut-out, so it does not generate. */
    static boolean cutOut(Row row, @Nullable Cutouts cutouts) {
        double[] box = box(row);
        return cutouts != null && cutouts.isCut((int) Math.floor((box[0] + box[3]) / 2D), (int) Math.floor(box[1]), (int) Math.floor((box[2] + box[5]) / 2D));
    }

    /** Where a marker is, in blocks: the block, or the entity's standing box. */
    private static double[] box(Row row) {
        return switch (row.kind()) {
            case CONTAINER -> new double[] {row.container().x, row.container().y, row.container().z,
                    row.container().x + 1, row.container().y + 1, row.container().z + 1};
            case SPAWNER -> new double[] {row.spawner().x, row.spawner().y, row.spawner().z,
                    row.spawner().x + 1, row.spawner().y + 1, row.spawner().z + 1};
            case ENTITY -> {
                Markers.EntityMark mark = row.entity();
                float[] size = EntityModels.size(mark.type);
                double half = Math.max(0.25D, size[0] / 2D);
                yield new double[] {mark.x - half, mark.y, mark.z - half, mark.x + half, mark.y + Math.max(0.5D, size[1]), mark.z + half};
            }
        };
    }

    /** The snapshot's entities with the data edited here. */
    private void refreshEntities() {
        if (view == null || shown == null) return;
        List<KineticBlockSnapshot.EntityEntry> entities = new ArrayList<>();
        for (KineticBlockSnapshot.EntityEntry entry : shown.entities()) {
            CompoundTag data = entry.tag();
            Markers.EntityMark mark = markers.entity(data.getString("id"), entry.x(), entry.y(), entry.z());
            CompoundTag edited = mark == null || mark.data == null ? null : EntityEdits.parse(mark.data);
            if (edited != null) edited.putString("id", mark.type);
            entities.add(edited == null ? entry : new KineticBlockSnapshot.EntityEntry(entry.x(), entry.y(), entry.z(), edited));
        }
        view.setEntities(entities);
    }

    private void lookAt(KineticStructureView.Marker marker) {
        view.lookAt((marker.minX() + marker.maxX()) / 2D, (marker.minY() + marker.maxY()) / 2D, (marker.minZ() + marker.maxZ()) / 2D);
    }

    private void lookAt(Row row) {
        double[] box = box(row);
        if (view != null) view.lookAt((box[0] + box[3]) / 2D, (box[1] + box[4]) / 2D, (box[2] + box[5]) / 2D);
    }

    /** A click in the view: on a marker it selects it (Ctrl adds or removes it), on empty space it goes back to the list. */
    private void pick(@Nullable KineticStructureView.Marker marker, MouseInput input) {
        flushTyping();
        if (marker == null) {
            if (!input.hasControl() && !input.hasShift()) select(null, false);
            return;
        }
        if (input.hasControl()) {
            if (!selected.remove(marker.key())) selected.add(marker.key());
            current = selected.contains(marker.key()) ? marker.key() : selected.stream().findFirst().orElse(null);
        } else {
            selected.clear();
            selected.add(marker.key());
            current = marker.key();
        }
        selectionChanged();
    }

    /** Makes {@code key} the only selected marker, or none; {@code fly} brings the camera to it. */
    private void select(@Nullable String key, boolean fly) {
        flushTyping();
        selected.clear();
        current = key;
        if (key != null) selected.add(key);
        selectionChanged();
        Row row = currentRow();
        if (fly && row != null) lookAt(row);
    }

    private void selectionChanged() {
        if (view != null) view.setSelection(current, selected);
        syncDetail();
        Row row = currentRow();
        if (row != null && row.entity() != null) requestEntity(row.entity());
    }

    // ---- the list ----

    private void refreshList() {
        if (list == null) return;
        List<Row> rows = new ArrayList<>();
        for (Row row : allRows()) if (shownByFilter(row) && matches(row)) rows.add(row);
        list.setItems(rows);
    }

    private boolean shownByFilter(Row row) {
        return switch (filter) {
            case "containers" -> row.kind() == MarkerKind.CONTAINER;
            case "spawners" -> row.kind() == MarkerKind.SPAWNER;
            case "entities" -> row.kind() == MarkerKind.ENTITY;
            default -> true;
        };
    }

    private boolean matches(Row row) {
        if (search.isEmpty()) return true;
        String text = row.key() + " " + rowTitle(row).getString() + " " + rowSummary(row).getString();
        return text.toLowerCase(Locale.ROOT).contains(search);
    }

    @Nullable
    private Row currentRow() {
        if (current == null) return null;
        for (Row row : allRows()) if (row.key().equals(current)) return row;
        return null;
    }

    /** Every marker, whatever the filter shows. */
    private List<Row> allRows() {
        return rows(markers);
    }

    static List<Row> rows(Markers markers) {
        List<Row> rows = new ArrayList<>();
        markers.containers.forEach(c -> rows.add(new Row(MarkerKind.CONTAINER, "c:" + c.x + "," + c.y + "," + c.z, c, null, null)));
        markers.spawners.forEach(s -> rows.add(new Row(MarkerKind.SPAWNER, "s:" + s.x + "," + s.y + "," + s.z, null, s, null)));
        markers.entities.forEach(e -> rows.add(new Row(MarkerKind.ENTITY, e.key(), null, null, e)));
        return rows;
    }

    /** The view's marker for a row, outlined in {@code color} at all times unless it is 0. */
    static KineticStructureView.Marker viewMarker(Row row, int color) {
        double[] box = box(row);
        return new KineticStructureView.Marker(row.key(), rowTitle(row), box[0], box[1], box[2], box[3], box[4], box[5], color);
    }

    /** The selected rows of {@code kind}; the current one alone when it is not part of the selection. */
    private List<Row> selectedRows(MarkerKind kind) {
        List<Row> rows = allRows().stream().filter(row -> row.kind() == kind && selected.contains(row.key())).collect(Collectors.toList());
        Row currentRow = currentRow();
        if (rows.isEmpty() && currentRow != null && currentRow.kind() == kind) rows.add(currentRow);
        return rows;
    }

    private void forContainers(java.util.function.Consumer<Markers.Container> change) {
        for (Row row : selectedRows(MarkerKind.CONTAINER)) change.accept(row.container());
    }

    private void forSpawners(java.util.function.Consumer<Markers.Spawner> change) {
        for (Row row : selectedRows(MarkerKind.SPAWNER)) change.accept(row.spawner());
    }

    List<Markers.Spawner> selectedSpawners() {
        return selectedRows(MarkerKind.SPAWNER).stream().map(Row::spawner).collect(Collectors.toList());
    }

    @Nullable
    private Markers.Container currentContainer() {
        Row row = currentRow();
        return row != null && row.kind() == MarkerKind.CONTAINER ? row.container() : null;
    }

    /** Sets the table of the selected containers; from the picker and the new table page. */
    void useTable(String table) {
        forContainers(container -> {
            container.table = table;
            if (container.lootable) container.mode = "table";
        });
        changed();
    }

    void changed() {
        dirty = true;
        refreshMarkers();
        syncDetail();
    }

    // ---- entity data ----

    /** The data shown for an entity: edited here, else what the scan saw; {@code null} until the server sent it. */
    @Nullable
    private String shownData(Markers.EntityMark mark) {
        return mark.data != null ? mark.data : scannedData.get(mark.key());
    }

    private void requestEntity(Markers.EntityMark mark) {
        if (scannedData.containsKey(mark.key()) || inFlight.contains(mark.key())) return;
        inFlight.add(mark.key());
        StructureEditorNetwork.editEntity(project.id, mark.key(), mark.data == null ? "" : mark.data, null, "");
    }

    /**
     * Applies common fields to the current entity on the server, with the name and health typed so far; a change made
     * while one is under way waits for it.
     */
    private void applyFields(JsonObject fields) {
        Row row = currentRow();
        if (row == null || row.entity() == null || selectedRows(MarkerKind.ENTITY).size() > 1) return;
        Markers.EntityMark mark = row.entity();
        String data = shownData(mark);
        if (data == null) return;
        if (nameTyped && nameField != null) fields.addProperty("name", nameField.textValue());
        Double health = healthField == null ? null : healthField.getDoubleValue();
        if (healthTyped && health != null) fields.addProperty("health", health);
        nameTyped = false;
        healthTyped = false;
        if (fields.size() == 0) return;
        if (inFlight.contains(mark.key())) {
            JsonObject waiting = queued.computeIfAbsent(mark.key(), key -> new JsonObject());
            fields.entrySet().forEach(entry -> waiting.add(entry.getKey(), entry.getValue()));
            return;
        }
        inFlight.add(mark.key());
        StructureEditorNetwork.editEntity(project.id, mark.key(), data, fields, "");
    }

    /** Sends the name and health typed so far, for the entity they were typed for: call it before the selection changes. */
    private void flushTyping() {
        if (nameTyped || healthTyped) applyFields(new JsonObject());
    }

    private void editData() {
        Row row = currentRow();
        if (row == null || row.entity() == null) return;
        Markers.EntityMark mark = row.entity();
        String data = shownData(mark);
        if (data == null) return;
        flushTyping();
        KineticSelectors.openNbtEditor(data, value -> EntityEdits.parse(value) == null
                ? KineticI18n.translatable("msg.realmcontrol.structure.entity.invalid").getString() : null, value -> {
            inFlight.add(mark.key());
            StructureEditorNetwork.editEntity(project.id, mark.key(), "", null, value);
        });
    }

    /** Back to the data the scan saw. */
    private void revertData() {
        Row row = currentRow();
        if (row == null || row.entity() == null || row.entity().data == null) return;
        row.entity().data = null;
        changed();
        refreshEntities();
    }

    @Override
    public void entityDataArrived(String projectId, JsonObject answer) {
        if (!projectId.equals(project.id)) return;
        String key = answer.get("key").getAsString();
        inFlight.remove(key);
        if (answer.has("scanned")) scannedData.put(key, answer.get("scanned").getAsString());
        Markers.EntityMark mark = markers.entityByKey(key);
        if (mark != null && answer.has("data") && answer.has("changed") && answer.get("changed").getAsBoolean()) {
            String data = answer.get("data").getAsString();
            mark.data = data.equals(scannedData.get(key)) ? null : data;
            dirty = true;
            refreshMarkers();
            refreshEntities();
        }
        JsonObject waiting = queued.remove(key);
        if (waiting != null && mark != null && shownData(mark) != null) {
            inFlight.add(key);
            StructureEditorNetwork.editEntity(project.id, key, shownData(mark), waiting, "");
        }
        if (key.equals(current)) syncDetail();
    }

    /** The current entity as a model, made again whenever its data changes. */
    @Nullable
    private Entity model(Markers.EntityMark mark) {
        String data = shownData(mark);
        if (data == null) return null;
        String id = mark.key() + "#" + data;
        if (!id.equals(modelFor)) {
            modelFor = id;
            CompoundTag tag = EntityEdits.parse(data);
            model = tag == null ? null : EntityModels.load(mark.type, tag);
        }
        return model;
    }

    // ---- the inspector ----

    /** Shows the controls of the current marker's kind, holding its values. */
    private void syncDetail() {
        if (list == null) return;
        Row row = currentRow();
        MarkerKind kind = row == null ? null : row.kind();
        syncing = true;
        try {
            list.setControlVisible(kind == null);
            filterBox.setControlVisible(kind == null);
            searchField.setControlVisible(kind == null);
            listButton.setControlVisible(kind != null);
            for (var control : List.of(containerMode, tableField, pickButton, newTableButton, editTableButton)) control.setControlVisible(kind == MarkerKind.CONTAINER);
            for (var control : List.of(spawnerMode, spawnerButton)) control.setControlVisible(kind == MarkerKind.SPAWNER);
            for (var control : List.of(keepToggle, nameField, healthField, aiToggle, stayToggle, dataButton, revertButton)) {
                control.setControlVisible(kind == MarkerKind.ENTITY);
            }
            if (kind == MarkerKind.CONTAINER) {
                Markers.Container container = row.container();
                Markers.Container before = saved.container(container.x, container.y, container.z);
                containerMode.setSelectedValue(container.lootable ? container.mode : "keep");
                containerMode.setEnabled(container.lootable);
                if (!tableField.textValue().trim().equalsIgnoreCase(container.table)) tableField.setTextValue(container.table);
                tableField.setDefaultText(before == null ? "" : before.table);
                boolean table = container.lootable;
                tableField.setTextEditable(table);
                pickButton.setEnabled(table);
                newTableButton.setEnabled(table);
                editTableButton.setEnabled(table && KineticPlatform.isModLoaded(CONTENTSTUDIO) && StructureProject.validId(container.table));
            } else if (kind == MarkerKind.SPAWNER) {
                spawnerMode.setSelectedValue(row.spawner().mode);
                spawnerButton.setEnabled(true);
            } else if (kind == MarkerKind.ENTITY) {
                Markers.EntityMark mark = row.entity();
                keepToggle.setValue(markers.keeps(mark));
                boolean single = selectedRows(MarkerKind.ENTITY).size() <= 1;
                Entity entity = single ? model(mark) : null;
                boolean living = entity instanceof LivingEntity, mob = entity instanceof Mob;
                // The entity's own values count as unchanged; typing turns them into the changed colour.
                if (!nameTyped && !nameField.controlFocused()) {
                    Component name = entity == null ? null : entity.getCustomName();
                    nameField.setTextValue(name == null ? "" : name.getString());
                    nameField.setDefaultText(nameField.textValue());
                }
                if (!healthTyped && !healthField.controlFocused()) {
                    healthField.setTextValue(living ? trim(((LivingEntity) entity).getMaxHealth()) : "");
                    healthField.setDefaultText(healthField.textValue());
                }
                aiToggle.setValue(!(mob && ((Mob) entity).isNoAi()));
                stayToggle.setValue(mob && ((Mob) entity).isPersistenceRequired());
                nameField.setEnabled(entity != null);
                healthField.setEnabled(living);
                aiToggle.setEnabled(mob);
                stayToggle.setEnabled(mob);
                dataButton.setEnabled(entity != null || single && shownData(mark) != null);
                revertButton.setEnabled(single && mark.data != null);
            }
        } finally {
            syncing = false;
        }
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    /** Opens ContentStudio's loot editor on the current container's table; it returns here with Back. */
    private void editTable() {
        Markers.Container container = currentContainer();
        if (container == null || !StructureProject.validId(container.table) || !KineticPlatform.isModLoaded(CONTENTSTUDIO)) return;
        try {
            Class.forName("dev.xyat.contentstudio.loot.client.LootClientHandler").getMethod("requestOpenTable", String.class).invoke(null, container.table);
        } catch (ReflectiveOperationException | LinkageError e) {
            dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.edit.missing"));
        }
    }

    private void save() {
        flushTyping();
        StructureEditorNetwork.saveMarkers(project.id, markers);
        saved = markers.copy();
        dirty = false;
        syncDetail();
    }

    /** Esc goes back one level: from a selected marker to the list, then out of the page. */
    @Override
    protected boolean onKeyPress(dev.xyat.kineticcore.api.client.gui.input.KeyInput input) {
        if (!input.isEscape() || current == null) return false;
        flushTyping();
        clearFocus();
        select(null, false);
        return true;
    }

    @Override
    protected void onTick() {
        if ((nameTyped || healthTyped) && System.nanoTime() - typedAt > TYPING_NANOS) flushTyping();
    }

    @Override
    protected boolean onCloseRequested() {
        flushTyping();
        if (!dirty) return false;
        openDialog(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.unsaved.title"),
                KineticI18n.translatable("gui.realmcontrol.structure.markers.unsaved.message"),
                KineticI18n.translatable("gui.realmcontrol.structure.cutouts.unsaved.discard"),
                KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"),
                () -> {
                    dirty = false;
                    navigateBack();
                }, () -> { });
        return true;
    }

    @Override
    protected void onRemoved() {
        StructurePreviewClient.cancel(previewListener);
        super.onRemoved();
    }

    @Override
    public void detailArrived(StructureProject arrived, Cutouts arrivedCutouts) {
        if (!arrived.id.equals(project.id)) return;
        cutouts = arrivedCutouts;
        showPreview();
    }

    @Override
    public void markersArrived(StructureProject arrived, Markers arrivedMarkers, JsonObject arrivedTables, String focus) {
        if (!arrived.id.equals(project.id)) return;
        tables = arrivedTables;
        saved = arrivedMarkers.copy();
        if (!dirty) {
            markers = arrivedMarkers.copy();
            refreshList();
            refreshMarkers();
            refreshEntities();
        }
        syncDetail();
    }

    @Override
    public void lootTablesArrived(List<String> ids) {
        syncDetail();
    }

    // ---- text ----

    static Component blockName(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        return location == null ? Component.literal(id) : BuiltInRegistries.BLOCK.getOptional(location)
                .<Component>map(block -> block.getName()).orElse(Component.literal(id));
    }

    static Component entityName(String id) {
        return EntityType.byString(id).<Component>map(EntityType::getDescription).orElse(Component.literal(id));
    }

    private static String position(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static Component rowTitle(Row row) {
        return switch (row.kind()) {
            case CONTAINER -> KineticI18n.translatable("gui.realmcontrol.structure.markers.row.at", blockName(row.container().block),
                    row.container().x, row.container().y, row.container().z);
            case SPAWNER -> KineticI18n.translatable("gui.realmcontrol.structure.markers.row.at", blockName(row.spawner().block),
                    row.spawner().x, row.spawner().y, row.spawner().z);
            case ENTITY -> {
                Markers.EntityMark mark = row.entity();
                Component name = mark.name.isEmpty() ? entityName(mark.type) : entityName(mark.type).copy().append(Component.literal(" " + mark.name));
                yield KineticI18n.translatable("gui.realmcontrol.structure.markers.row.at", name, position(mark.x), position(mark.y), position(mark.z));
            }
        };
    }

    private Component rowSummary(Row row) {
        switch (row.kind()) {
            case CONTAINER -> {
                Markers.Container container = row.container();
                if (!container.lootable) return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.not_lootable", container.items);
                if (container.usesTable()) return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.table", container.table);
                return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.items", container.items);
            }
            case SPAWNER -> {
                Markers.Spawner spawner = row.spawner();
                if (!spawner.custom()) return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.spawner_keep", entityName(spawner.entity));
                return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.spawner_custom", mobsText(spawner));
            }
            default -> {
                Markers.EntityMark mark = row.entity();
                String state = !markers.keeps(mark) ? "dropped" : mark.data != null ? "edited" : "kept";
                return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.entity_" + state);
            }
        }
    }

    static String mobsText(Markers.Spawner spawner) {
        return spawner.mobs.stream().map(mob -> entityName(mob.id()).getString() + " ×" + mob.weight()).collect(Collectors.joining("、"));
    }

    /** Whether a container's table is a problem: unknown to the server, or naming missing items. */
    private int tableColor(String table) {
        JsonObject state = tables.has(table) ? tables.getAsJsonObject(table) : null;
        boolean known = state != null ? state.get("exists").getAsBoolean() : EditorClient.lootTables() != null && EditorClient.lootTables().contains(table);
        if (!known) return 0xFFFF5555;
        return state != null && !state.getAsJsonArray("missing").isEmpty() ? 0xFFFFAA00 : 0xFFCCCCCC;
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
        if (currentRow() == null) KineticTheme.surface(g, inspectorX(), TOP + 24, INSPECTOR_W, height() - TOP - 24 - 4, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - 48 - 4 - width() / 2));
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.page_title", project.name,
                KineticI18n.translatable("gui.realmcontrol.structure.markers.title")), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        Row row = currentRow();
        int x = inspectorX(), w = INSPECTOR_W;
        if (row == null) return;
        int count = selectedRows(row.kind()).size();
        Component heading = count > 1 ? KineticI18n.translatable("gui.realmcontrol.structure.markers.batch", count, rowTitle(row)) : rowTitle(row);
        g.scrollingText(heading, x + 44, TOP + 6, w - 44, 0xFFFFFFFF, false);
        int labelY = TOP + 24 + 6;
        switch (row.kind()) {
            case CONTAINER -> {
                Markers.Container container = row.container();
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.container.content"), x, labelY, LABEL_W, 0xFFFFFFFF, false);
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.table"), x, labelY + ROW_STEP, LABEL_W, 0xFFFFFFFF, false);
                int y = TOP + 24 + 3 * ROW_STEP + 2;
                List<Component> lines = new ArrayList<>();
                List<Integer> colors = new ArrayList<>();
                if (!container.lootable) {
                    lines.add(KineticI18n.translatable("gui.realmcontrol.structure.markers.container.not_lootable"));
                    colors.add(0xFFFFAA00);
                } else if (container.usesTable()) {
                    int color = tableColor(container.table);
                    JsonObject state = tables.has(container.table) ? tables.getAsJsonObject(container.table) : null;
                    if (color == 0xFFFF5555) {
                        lines.add(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.unknown", container.table));
                    } else if (state != null && !state.getAsJsonArray("missing").isEmpty()) {
                        List<String> missing = new ArrayList<>();
                        state.getAsJsonArray("missing").forEach(element -> missing.add(element.getAsString()));
                        lines.add(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.missing", missing.size(), String.join("、", missing)));
                    } else {
                        lines.add(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.ok"));
                    }
                    colors.add(color);
                }
                lines.add(KineticI18n.translatable("gui.realmcontrol.structure.markers.container.scanned", container.items));
                colors.add(0xFFCCCCCC);
                for (int i = 0; i < lines.size(); i++) {
                    g.scrollingText(lines.get(i), x, y, w, colors.get(i), false);
                    y += 12;
                }
                g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.markers.container.help"), x, y + 6, w, 0xFF9AA4B2);
            }
            case SPAWNER -> {
                Markers.Spawner spawner = row.spawner();
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.setting"), x, labelY, LABEL_W, 0xFFFFFFFF, false);
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.mobs"), x, labelY + ROW_STEP, LABEL_W, 0xFFFFFFFF, false);
                int y = TOP + 24 + 2 * ROW_STEP + 2;
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.scanned", entityName(spawner.entity)), x, y, w, 0xFFCCCCCC, false);
                y += 12;
                if (spawner.custom()) {
                    g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.will_spawn", mobsText(spawner)), x, y, w, 0xFFFFFFFF, false);
                    y += 12;
                    g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.timing", spawner.minDelay, spawner.maxDelay,
                            spawner.count, spawner.maxNearby, spawner.playerRange, spawner.spawnRange), x, y, w, 0xFFCCCCCC, false);
                    y += 12;
                } else if ("custom".equals(spawner.mode)) {
                    g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.no_mobs"), x, y, w, 0xFFFFAA00, false);
                    y += 12;
                }
                g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.help"), x, y + 6, w, 0xFF9AA4B2);
            }
            case ENTITY -> renderEntity(g, row.entity(), mx, my);
        }
    }

    private void renderEntity(KineticGraphics g, Markers.EntityMark mark, int mx, int my) {
        int x = inspectorX(), w = INSPECTOR_W, y = TOP + 24;
        boolean single = selectedRows(MarkerKind.ENTITY).size() <= 1;
        Entity entity = single ? model(mark) : null;
        KineticTheme.itemSlot(g, x, y, MODEL_W, MODEL_H, 4, false, false, false);
        if (entity != null) {
            modelPreview.render(g, entity, "structure_entity", x + 1, y + 1, MODEL_W - 2, MODEL_H - 2, KineticTheme.hovering(mx, my, x, y, MODEL_W, MODEL_H));
        } else {
            modelPreview.render(g, mark.type, "structure_entity", x + 1, y + 1, MODEL_W - 2, MODEL_H - 2, false);
        }
        int slotsY = slotsY();
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.equipment"), x, slotsY + 5, LABEL_W, 0xFFFFFFFF, false);
        LivingEntity living = entity instanceof LivingEntity l ? l : null;
        for (int i = 0; i < EntityEdits.SLOTS.size(); i++) {
            int sx = slotX(i);
            boolean hovered = living != null && KineticTheme.hovering(mx, my, sx, slotsY, SLOT, SLOT);
            KineticTheme.itemSlot(g, sx, slotsY, SLOT, hovered);
            if (living != null) {
                ItemStack stack = living.getItemBySlot(EntityEdits.SLOTS.get(i));
                if (!stack.isEmpty()) KineticTheme.item(g, stack, sx, slotsY, SLOT, 1.0F, false);
            }
        }
        int lineY = slotsY + SLOT + 4 + 22;
        Component state;
        int color;
        if (!single) {
            state = KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.batch_only");
            color = 0xFFFFAA00;
        } else if (shownData(mark) == null) {
            state = KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.loading");
            color = 0xFFCCCCCC;
        } else if (entity == null) {
            state = KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.unavailable");
            color = 0xFFFFAA00;
        } else {
            state = KineticI18n.translatable(mark.data != null ? "gui.realmcontrol.structure.markers.entity.edited" : "gui.realmcontrol.structure.markers.entity.as_scanned");
            color = mark.data != null ? 0xFF55AAFF : 0xFFCCCCCC;
        }
        g.scrollingText(state, x, lineY, w, color, false);
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.help"), x, lineY + 14, w, 0xFF9AA4B2);
    }

    @Override
    protected void renderTooltips(int mouseX, int mouseY) {
        Row row = currentRow();
        if (row == null || row.entity() == null || selectedRows(MarkerKind.ENTITY).size() > 1) return;
        int slotsY = slotsY();
        for (int i = 0; i < EntityEdits.SLOTS.size(); i++) {
            if (!KineticTheme.hovering(mouseX, mouseY, slotX(i), slotsY, SLOT, SLOT)) continue;
            Entity entity = model(row.entity());
            ItemStack stack = entity instanceof LivingEntity living ? living.getItemBySlot(EntityEdits.SLOTS.get(i)) : ItemStack.EMPTY;
            List<Component> hints = List.of(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.slot." + EntityEdits.SLOTS.get(i).getName()),
                    KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.slot.hint"));
            if (stack.isEmpty()) showTooltip(hints);
            else showItemTooltip(stack, hints);
            tooltipAnchor(slotX(i), slotsY, SLOT, SLOT);
            return;
        }
    }

    @Override
    protected boolean onMouseClick(MouseInput input) {
        Row row = currentRow();
        if (row == null || row.entity() == null || selectedRows(MarkerKind.ENTITY).size() > 1) return false;
        if (!(model(row.entity()) instanceof LivingEntity)) return false;
        int slotsY = slotsY();
        for (int i = 0; i < EntityEdits.SLOTS.size(); i++) {
            if (!input.inside(slotX(i), slotsY, SLOT, SLOT)) continue;
            EquipmentSlot slot = EntityEdits.SLOTS.get(i);
            if (input.isRight()) {
                applyEquipment(slot, "");
                return true;
            }
            if (input.isLeft()) {
                KineticSelectors.openItemSelector(selection -> {
                    if (selection.isItem()) applyEquipment(slot, KineticItemText.format(selection.stack()));
                });
                return true;
            }
        }
        return false;
    }

    private void applyEquipment(EquipmentSlot slot, String item) {
        JsonObject equipment = new JsonObject();
        equipment.addProperty(slot.getName(), item);
        JsonObject fields = new JsonObject();
        fields.add("equipment", equipment);
        applyFields(fields);
    }

    /** Marker rows: what and where, then what publishing does with it. */
    private final class RowList extends KineticRowList<Row> {
        RowList(int x, int y, int width, int height) {
            super(x, y, width, height, ROW_HEIGHT);
        }

        @Override
        protected void renderRowBackground(KineticGraphics g, int index, int x, int y, int width, int height, boolean hovered, boolean selectedRow) {
        }

        @Override
        protected void renderRow(KineticGraphics g, Row row, int index, int x, int t, int rw, int rh, boolean hovered, boolean selectedRow) {
            int l = controlX() + 2, w = controlWidth() - 12, h = ROW_HEIGHT - ROW_GAP;
            KineticTheme.surface(g, l, t, w, h, index % 2 == 0 ? KineticTheme.Surface.PANEL : KineticTheme.Surface.PANEL_ALT);
            boolean isCurrent = row.key().equals(current), isSelected = selected.contains(row.key());
            if (isSelected && !isCurrent) g.fill(l, t, l + w, t + h, 0x3055DD55);
            if (isCurrent) KineticTheme.stateOutline(g, l, t, w, h, true, false, false);
            else if (hovered) KineticTheme.stateOutline(g, l, t, w, h, false, true, false);
            else KineticTheme.indicatorOutline(g, l, t, w, h, KineticTheme.Indicator.MUTED);
            int textX = l + 6, maxW = w - 12, lineH = g.lineHeight();
            int firstY = t + (h - (lineH * 2 + 1)) / 2;
            g.scrollingText(rowTitle(row), textX, firstY, maxW, 0xFFFFFFFF, false);
            int color = 0xFFCCCCCC;
            if (row.kind() == MarkerKind.CONTAINER && row.container().usesTable()) color = tableColor(row.container().table);
            if (row.kind() == MarkerKind.SPAWNER && "custom".equals(row.spawner().mode) && row.spawner().mobs.isEmpty()) color = 0xFFFFAA00;
            if (row.kind() == MarkerKind.ENTITY && !markers.keeps(row.entity())) color = 0xFFFF7777;
            g.scrollingText(rowSummary(row), textX, firstY + lineH + 1, maxW, color, false);
        }

        @Override
        protected boolean onRowClick(Row row, int index, MouseInput input) {
            if (!input.isLeft()) return false;
            flushTyping();
            if (input.hasControl()) {
                if (!selected.remove(row.key())) selected.add(row.key());
                current = row.key();
                selectionChanged();
            } else if (input.hasShift() && !selected.isEmpty()) {
                List<Row> rows = items();
                String anchor = selected.iterator().next();
                int from = -1, to = -1;
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).key().equals(anchor)) from = i;
                    if (rows.get(i).key().equals(row.key())) to = i;
                }
                if (from >= 0 && to >= 0) {
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selected.add(rows.get(i).key());
                }
                current = row.key();
                selectionChanged();
            } else {
                MarkersPage.this.select(row.key(), true);
            }
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable("gui.realmcontrol.structure.markers.empty");
        }
    }
}
