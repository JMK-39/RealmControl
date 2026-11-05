package dev.xyat.realmcontrol.structure.client.gui;

import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.KineticToggle;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A draft's markers: containers, spawners and entity types as text rows on the left, the current one's settings on
 * the right. Click selects one (yellow), Ctrl + click adds or removes rows and Shift + click a range (green); a
 * change applies to every selected row of the current row's kind. Containers keep their items or get a loot table,
 * which can be picked, created, or edited in ContentStudio; spawners keep what they spawn or get new mobs; entity
 * types and single entities are kept or left out.
 */
public final class MarkersPage extends KineticPage implements EditorClient.Listener {
    private static final int LIST_X = 4, LIST_Y = 46, LIST_W = 300, ROW_HEIGHT = 34, ROW_GAP = 2;
    private static final int DETAIL_X = LIST_X + LIST_W + 8, LABEL_W = 76, FIELD_X = DETAIL_X + LABEL_W + 4, ROW_STEP = 22;
    private static final String CONTENTSTUDIO = "contentstudio";

    /** What a list row stands for. */
    enum Kind {
        CONTAINER, SPAWNER, ENTITY
    }

    /** One list row: a container or spawner by position, or an entity type with its entities. */
    record Row(Kind kind, String key, @Nullable Markers.Container container, @Nullable Markers.Spawner spawner, String type, int count) {
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
    private RowList list;
    private EntityList entityList;
    private KineticDropdown containerMode, spawnerMode;
    private KineticTextField tableField;
    private KineticButton pickButton, newTableButton, editTableButton, spawnerButton;
    private KineticToggle typeToggle;

    /** {@code focus}: marker keys separated by {@code ;}, the first one current, all of them selected. */
    public MarkersPage(StructureProject project, Markers markers, JsonObject tables, String focus) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.markers.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.project = project;
        this.markers = markers.copy();
        this.saved = markers.copy();
        this.tables = tables;
        for (String key : focus.split(";")) {
            if (key.isBlank()) continue;
            if (current == null) current = key;
            selected.add(key);
        }
    }

    StructureProject project() {
        return project;
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
        ui().button(width() - 4 - 48, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_all")).onClick(this::save).build();
        int filterW = 96;
        List<KineticDropdown.Option> filters = new ArrayList<>();
        for (String option : List.of("all", "containers", "spawners", "entities")) {
            filters.add(new KineticDropdown.Option(option, KineticI18n.translatable("gui.realmcontrol.structure.markers.filter." + option)));
        }
        ui().dropdown(LIST_X, 28, filterW, filters).selected(filter).onChange(value -> {
            filter = value;
            refreshList();
        }).build();
        KineticTextField searchField = ui().textField(LIST_X + filterW + 4, 28, LIST_W - filterW - 4)
                .placeholder(KineticI18n.translatable("gui.realmcontrol.structure.markers.search")).build();
        searchField.setTextValue(search);
        searchField.onTextChange(value -> {
            search = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            refreshList();
        });
        list = ui.add(new RowList(LIST_X, LIST_Y + 2, LIST_W, height() - LIST_Y - 6));

        int fieldW = width() - 4 - FIELD_X, y = LIST_Y;
        // Containers.
        containerMode = ui().dropdown(FIELD_X, y, fieldW, List.of(
                new KineticDropdown.Option("keep", KineticI18n.translatable("gui.realmcontrol.structure.markers.container.keep"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.container.keep.tooltip")),
                new KineticDropdown.Option("table", KineticI18n.translatable("gui.realmcontrol.structure.markers.container.table"),
                        KineticI18n.translatable("gui.realmcontrol.structure.markers.container.table.tooltip"))))
                .selected("keep").onChange(value -> {
                    if (syncing) return;
                    forContainers(container -> container.mode = container.lootable ? value : "keep");
                    changed();
                }).build();
        int pickW = 48;
        tableField = ui().textField(FIELD_X, y + ROW_STEP, fieldW - pickW - 4)
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
        pickButton = ui().button(FIELD_X + fieldW - pickW, y + ROW_STEP, pickW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.pick"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.pick.tooltip"))
                .onClick(() -> openChild(new LootTablePickerPage(tableField.textValue().trim(), this::useTable))).build();
        int newW = 72;
        newTableButton = ui().button(FIELD_X, y + 2 * ROW_STEP, newW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.new"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.new.tooltip"))
                .onClick(() -> {
                    Markers.Container container = currentContainer();
                    if (container != null) openChild(new NewLootTablePage(this, container));
                }).build();
        editTableButton = ui().button(FIELD_X + newW + 4, y + 2 * ROW_STEP, fieldW - newW - 4).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.edit"))
                .tooltip(() -> KineticI18n.translatable(KineticPlatform.isModLoaded(CONTENTSTUDIO)
                        ? "gui.realmcontrol.structure.markers.table.edit.tooltip" : "gui.realmcontrol.structure.markers.table.edit.missing"))
                .onClick(this::editTable).build();
        // Spawners.
        spawnerMode = ui().dropdown(FIELD_X, y, fieldW, List.of(
                new KineticDropdown.Option("keep", KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.keep")),
                new KineticDropdown.Option("custom", KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.custom"))))
                .selected("keep").onChange(value -> {
                    if (syncing) return;
                    forSpawners(spawner -> spawner.mode = value);
                    changed();
                }).build();
        spawnerButton = ui().button(FIELD_X, y + ROW_STEP, fieldW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.edit"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.edit.tooltip"))
                .onClick(() -> {
                    List<Markers.Spawner> targets = selectedSpawners();
                    if (!targets.isEmpty()) openChild(new SpawnerPage(this, targets));
                }).build();
        // Entity types.
        typeToggle = ui().toggle(FIELD_X, y, 72).labels(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.kept"),
                KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.dropped"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.type.tooltip"))
                .onChange(value -> {
                    if (syncing) return;
                    for (Row row : selectedRows(Kind.ENTITY)) {
                        if (value) markers.entityTypes.remove(row.type());
                        else markers.entityTypes.put(row.type(), false);
                    }
                    changed();
                }).build();
        entityList = ui.add(new EntityList(DETAIL_X, y + ROW_STEP + 2, width() - 4 - DETAIL_X, height() - (y + ROW_STEP + 2) - 4));
        refreshList();
        syncDetail();
    }

    private void refreshList() {
        if (list == null) return;
        List<Row> rows = new ArrayList<>();
        if (filter.equals("all") || filter.equals("containers")) {
            for (Markers.Container container : markers.containers) {
                Row row = new Row(Kind.CONTAINER, "c:" + container.x + "," + container.y + "," + container.z, container, null, container.block, 1);
                if (matches(row)) rows.add(row);
            }
        }
        if (filter.equals("all") || filter.equals("spawners")) {
            for (Markers.Spawner spawner : markers.spawners) {
                Row row = new Row(Kind.SPAWNER, "s:" + spawner.x + "," + spawner.y + "," + spawner.z, null, spawner, spawner.block, 1);
                if (matches(row)) rows.add(row);
            }
        }
        if (filter.equals("all") || filter.equals("entities")) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Markers.EntityMark mark : markers.entities) counts.merge(mark.type, 1, Integer::sum);
            for (Map.Entry<String, Integer> type : counts.entrySet()) {
                Row row = new Row(Kind.ENTITY, "e:" + type.getKey(), null, null, type.getKey(), type.getValue());
                if (matches(row)) rows.add(row);
            }
        }
        list.setItems(rows);
        if (current == null && !rows.isEmpty()) {
            current = rows.get(0).key();
            selected.add(current);
        }
    }

    private boolean matches(Row row) {
        if (search.isEmpty()) return true;
        String text = row.key() + " " + row.type() + " " + rowTitle(row).getString() + " " + rowSummary(row).getString();
        return text.toLowerCase(Locale.ROOT).contains(search);
    }

    @Nullable
    private Row currentRow() {
        if (current == null || list == null) return null;
        for (Row row : allRows()) if (row.key().equals(current)) return row;
        return null;
    }

    /** Every row, whatever the filter shows. */
    private List<Row> allRows() {
        List<Row> rows = new ArrayList<>();
        markers.containers.forEach(c -> rows.add(new Row(Kind.CONTAINER, "c:" + c.x + "," + c.y + "," + c.z, c, null, c.block, 1)));
        markers.spawners.forEach(s -> rows.add(new Row(Kind.SPAWNER, "s:" + s.x + "," + s.y + "," + s.z, null, s, s.block, 1)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Markers.EntityMark mark : markers.entities) counts.merge(mark.type, 1, Integer::sum);
        counts.forEach((type, count) -> rows.add(new Row(Kind.ENTITY, "e:" + type, null, null, type, count)));
        return rows;
    }

    /** The selected rows of {@code kind}; the current one alone when it is not part of the selection. */
    private List<Row> selectedRows(Kind kind) {
        List<Row> rows = allRows().stream().filter(row -> row.kind() == kind && selected.contains(row.key())).collect(Collectors.toList());
        Row currentRow = currentRow();
        if (rows.isEmpty() && currentRow != null && currentRow.kind() == kind) rows.add(currentRow);
        return rows;
    }

    private void forContainers(java.util.function.Consumer<Markers.Container> change) {
        for (Row row : selectedRows(Kind.CONTAINER)) change.accept(row.container());
    }

    private void forSpawners(java.util.function.Consumer<Markers.Spawner> change) {
        for (Row row : selectedRows(Kind.SPAWNER)) change.accept(row.spawner());
    }

    List<Markers.Spawner> selectedSpawners() {
        return selectedRows(Kind.SPAWNER).stream().map(Row::spawner).collect(Collectors.toList());
    }

    @Nullable
    private Markers.Container currentContainer() {
        Row row = currentRow();
        return row != null && row.kind() == Kind.CONTAINER ? row.container() : null;
    }

    /** Sets the table of the selected containers; from the picker and the new table page. */
    void useTable(String table) {
        forContainers(container -> {
            container.table = table;
            if (container.lootable) container.mode = "table";
        });
        changed();
        syncDetail();
    }

    void changed() {
        dirty = true;
        syncDetail();
    }

    /** Shows the controls of the current row's kind, holding its values. */
    private void syncDetail() {
        if (list == null) return;
        Row row = currentRow();
        Kind kind = row == null ? null : row.kind();
        syncing = true;
        try {
            for (var control : List.of(containerMode, tableField, pickButton, newTableButton, editTableButton)) control.setControlVisible(kind == Kind.CONTAINER);
            for (var control : List.of(spawnerMode, spawnerButton)) control.setControlVisible(kind == Kind.SPAWNER);
            typeToggle.setControlVisible(kind == Kind.ENTITY);
            entityList.setControlVisible(kind == Kind.ENTITY);
            if (kind == Kind.CONTAINER) {
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
            } else if (kind == Kind.SPAWNER) {
                spawnerMode.setSelectedValue(row.spawner().mode);
                spawnerButton.setEnabled(true);
            } else if (kind == Kind.ENTITY) {
                typeToggle.setValue(markers.keepsType(row.type()));
                entityList.setItems(markers.entities.stream().filter(mark -> mark.type.equals(row.type())).collect(Collectors.toList()));
            }
        } finally {
            syncing = false;
        }
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
        StructureEditorNetwork.saveMarkers(project.id, markers);
        saved = markers.copy();
        dirty = false;
        syncDetail();
    }

    @Override
    protected boolean onCloseRequested() {
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
    public void markersArrived(StructureProject arrived, Markers arrivedMarkers, JsonObject arrivedTables, String focus) {
        if (!arrived.id.equals(project.id)) return;
        tables = arrivedTables;
        saved = arrivedMarkers.copy();
        if (!dirty) {
            markers = arrivedMarkers.copy();
            refreshList();
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

    private Component rowTitle(Row row) {
        return switch (row.kind()) {
            case CONTAINER -> KineticI18n.translatable("gui.realmcontrol.structure.markers.row.at", blockName(row.container().block),
                    row.container().x, row.container().y, row.container().z);
            case SPAWNER -> KineticI18n.translatable("gui.realmcontrol.structure.markers.row.at", blockName(row.spawner().block),
                    row.spawner().x, row.spawner().y, row.spawner().z);
            case ENTITY -> KineticI18n.translatable("gui.realmcontrol.structure.markers.row.entities", entityName(row.type()), row.count());
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
                int kept = 0;
                for (Markers.EntityMark mark : markers.entities) if (mark.type.equals(row.type()) && markers.keeps(mark)) kept++;
                return KineticI18n.translatable("gui.realmcontrol.structure.markers.row.entity_counts", kept, row.count() - kept);
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
        KineticTheme.surface(g, LIST_X, LIST_Y, LIST_W, height() - LIST_Y - 4, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - 48 - 4 - width() / 2));
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.page_title", project.name,
                KineticI18n.translatable("gui.realmcontrol.structure.markers.title")), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        Row row = currentRow();
        int w = width() - 4 - DETAIL_X;
        if (row == null) {
            g.scrollingText(KineticI18n.translatable(markers.containers.isEmpty() && markers.spawners.isEmpty() && markers.entities.isEmpty()
                    ? "gui.realmcontrol.structure.markers.empty" : "gui.realmcontrol.structure.markers.select"), DETAIL_X, LIST_Y + 4, w, 0xFFCCCCCC, false);
            return;
        }
        int count = selectedRows(row.kind()).size();
        Component heading = count > 1 ? KineticI18n.translatable("gui.realmcontrol.structure.markers.batch", count, rowTitle(row)) : rowTitle(row);
        g.scrollingText(heading, DETAIL_X, 32, w, 0xFFFFFFFF, false);
        int labelY = LIST_Y + 4;
        switch (row.kind()) {
            case CONTAINER -> {
                Markers.Container container = row.container();
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.container.content"), DETAIL_X, labelY, LABEL_W, 0xFFFFFFFF, false);
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.table"), DETAIL_X, labelY + ROW_STEP, LABEL_W, 0xFFFFFFFF, false);
                int y = LIST_Y + 3 * ROW_STEP + 2;
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
                    g.scrollingText(lines.get(i), DETAIL_X, y, w, colors.get(i), false);
                    y += 12;
                }
                g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.markers.container.help"), DETAIL_X, y + 6, w, 0xFF9AA4B2);
            }
            case SPAWNER -> {
                Markers.Spawner spawner = row.spawner();
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.setting"), DETAIL_X, labelY, LABEL_W, 0xFFFFFFFF, false);
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.mobs"), DETAIL_X, labelY + ROW_STEP, LABEL_W, 0xFFFFFFFF, false);
                int y = LIST_Y + 2 * ROW_STEP + 2;
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.scanned", entityName(spawner.entity)), DETAIL_X, y, w, 0xFFCCCCCC, false);
                y += 12;
                if (spawner.custom()) {
                    g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.will_spawn", mobsText(spawner)), DETAIL_X, y, w, 0xFFFFFFFF, false);
                    y += 12;
                    g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.timing", spawner.minDelay, spawner.maxDelay,
                            spawner.count, spawner.maxNearby, spawner.playerRange, spawner.spawnRange), DETAIL_X, y, w, 0xFFCCCCCC, false);
                    y += 12;
                } else if ("custom".equals(spawner.mode)) {
                    g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.no_mobs"), DETAIL_X, y, w, 0xFFFFAA00, false);
                    y += 12;
                }
                g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.markers.spawner.help"), DETAIL_X, y + 6, w, 0xFF9AA4B2);
            }
            case ENTITY -> g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.type"), DETAIL_X, labelY, LABEL_W, 0xFFFFFFFF, false);
        }
    }

    private void click(Row row, MouseInput input) {
        if (input.hasControl()) {
            if (!selected.remove(row.key())) selected.add(row.key());
            current = row.key();
        } else if (input.hasShift() && current != null) {
            List<Row> rows = list.items();
            int from = -1, to = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).key().equals(current)) from = i;
                if (rows.get(i).key().equals(row.key())) to = i;
            }
            if (from >= 0 && to >= 0) {
                for (int i = Math.min(from, to); i <= Math.max(from, to); i++) selected.add(rows.get(i).key());
            }
            current = row.key();
        } else {
            selected.clear();
            selected.add(row.key());
            current = row.key();
        }
        syncDetail();
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
            if (row.kind() == Kind.CONTAINER && row.container().usesTable()) color = tableColor(row.container().table);
            if (row.kind() == Kind.SPAWNER && "custom".equals(row.spawner().mode) && row.spawner().mobs.isEmpty()) color = 0xFFFFAA00;
            g.scrollingText(rowSummary(row), textX, firstY + lineH + 1, maxW, color, false);
        }

        @Override
        protected boolean onRowClick(Row row, int index, MouseInput input) {
            if (!input.isLeft()) return false;
            click(row, input);
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable("gui.realmcontrol.structure.markers.empty");
        }
    }

    /** The entities of the current type: left click keeps or leaves one out, right click follows the type again. */
    private final class EntityList extends KineticRowList<Markers.EntityMark> {
        EntityList(int x, int y, int width, int height) {
            super(x, y, width, height, 16);
        }

        @Override
        protected void renderRow(KineticGraphics g, Markers.EntityMark mark, int index, int x, int y, int width, int height, boolean hovered, boolean selectedRow) {
            boolean keep = markers.keeps(mark);
            if (!keep) g.fill(x, y, x + width, y + height, 0x30FF5555);
            if (hovered) KineticTheme.stateOutline(g, x, y, width, height, false, true, false);
            Component state = KineticI18n.translatable(mark.keep == null
                    ? (keep ? "gui.realmcontrol.structure.markers.entity.kept_type" : "gui.realmcontrol.structure.markers.entity.dropped_type")
                    : (keep ? "gui.realmcontrol.structure.markers.entity.kept" : "gui.realmcontrol.structure.markers.entity.dropped"));
            int stateW = 72, textY = y + (height - g.lineHeight()) / 2 + 1;
            String where = String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", mark.x, mark.y, mark.z);
            g.scrollingText(Component.literal(mark.name.isEmpty() ? where : where + "  " + mark.name), x + 4, textY, width - 12 - stateW, 0xFFFFFFFF, false);
            g.scrollingTextRight(state, x + width - 4, textY, stateW, keep ? 0xFF77DD77 : 0xFFFF7777, false);
        }

        @Override
        protected boolean onRowClick(Markers.EntityMark mark, int index, MouseInput input) {
            if (input.isLeft()) {
                boolean keep = !markers.keeps(mark);
                mark.keep = keep == markers.keepsType(mark.type) ? null : keep;
            } else if (input.isRight()) {
                mark.keep = null;
            } else {
                return false;
            }
            playClickSound();
            changed();
            return true;
        }

        @Override
        protected Component rowTooltip(Markers.EntityMark mark, int index) {
            return KineticI18n.translatable("gui.realmcontrol.structure.markers.entity.row.tooltip");
        }
    }
}
