package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.publish.LootTables;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A new loot table for the selected containers, written into the KineticCore data pack: empty, a copy of another
 * table, or made from the items the current container held (one entry of equal weight per kind of item). The
 * containers use it once it is created; ContentStudio edits it further.
 */
public final class NewLootTablePage extends KineticPage {
    private static final int LABEL_X = 12, FIELD_X = 120, FIELD_W = 320, ROW_STEP = 22, TOP = 30;
    private final MarkersPage parent;
    private final Markers.Container container;
    private String kind;
    private KineticTextField idField, sourceField;
    private KineticButton pickButton;

    public NewLootTablePage(MarkersPage parent, Markers.Container container) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.parent = parent;
        this.container = container;
        this.kind = container.items > 0 ? "items" : "empty";
    }

    @Override
    protected void build(KineticUi ui) {
        if (EditorClient.lootTables() == null) StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.LOOT_TABLES, "", "");
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::navigateBack).build();
        ui().button(width() - 4 - 48, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.create")).onClick(this::create).build();
        int y = TOP;
        idField = ui().textField(FIELD_X, y, FIELD_W).build();
        idField.limitTextLength(256);
        idField.setTextValue(suggestedId());
        idField.setValidator(value -> StructureProject.validId(value.trim().toLowerCase(Locale.ROOT)));
        idField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.id.tooltip"));
        y += ROW_STEP;
        List<KineticDropdown.Option> kinds = new ArrayList<>();
        for (String option : container.items > 0 ? List.of("items", "empty", "copy") : List.of("empty", "copy")) {
            kinds.add(new KineticDropdown.Option(option, KineticI18n.translatable("gui.realmcontrol.structure.loot.new.kind." + option),
                    KineticI18n.translatable("gui.realmcontrol.structure.loot.new.kind." + option + ".tooltip")));
        }
        ui().dropdown(FIELD_X, y, FIELD_W, kinds).selected(kind).onChange(value -> {
            kind = value;
            sync();
        }).build();
        y += ROW_STEP;
        int pickW = 48;
        sourceField = ui().textField(FIELD_X, y, FIELD_W - pickW - 4).placeholder(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.placeholder")).build();
        sourceField.limitTextLength(256);
        pickButton = ui().button(FIELD_X + FIELD_W - pickW, y, pickW).text(KineticI18n.translatable("gui.realmcontrol.structure.markers.table.pick"))
                .onClick(() -> openChild(new LootTablePickerPage(sourceField.textValue().trim(), sourceField::setTextValue))).build();
        sync();
    }

    private void sync() {
        boolean copy = "copy".equals(kind);
        sourceField.setControlVisible(copy);
        pickButton.setControlVisible(copy);
    }

    /** {@code <namespace>:chests/<structure>/loot}, numbered when that is taken. */
    private String suggestedId() {
        StructureProject project = parent.project();
        String base = project.namespace() + ":chests/" + project.path() + "/loot";
        List<String> taken = EditorClient.lootTables() == null ? List.of() : EditorClient.lootTables();
        String id = base;
        for (int n = 2; taken.contains(id); n++) id = base + "_" + n;
        return id;
    }

    private void create() {
        String id = idField.textValue().trim().toLowerCase(Locale.ROOT);
        if (!StructureProject.validId(id)) {
            idField.flashValidationError();
            return;
        }
        String source = sourceField.textValue().trim().toLowerCase(Locale.ROOT);
        if ("copy".equals(kind) && !StructureProject.validId(source)) {
            sourceField.flashValidationError();
            return;
        }
        LootTables.Kind tableKind = switch (kind) {
            case "copy" -> LootTables.Kind.COPY;
            case "items" -> LootTables.Kind.ITEMS;
            default -> LootTables.Kind.EMPTY;
        };
        StructureEditorNetwork.createTable(parent.project().id, id, tableKind, source, new int[]{container.x, container.y, container.z});
        parent.useTable(id);
        navigateBack();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - 48 - 4 - width() / 2));
        g.scrollingTextCentered(title(), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        int labelW = FIELD_X - 8 - LABEL_X, y = TOP + 4;
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.id"), LABEL_X, y, labelW, 0xFFFFFFFF, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.kind"), LABEL_X, y + ROW_STEP, labelW, 0xFFFFFFFF, false);
        if ("copy".equals(kind)) g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.source"), LABEL_X, y + 2 * ROW_STEP, labelW, 0xFFFFFFFF, false);
        int w = width() - 8 - LABEL_X;
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.loot.new.help", LootTables.FOLDER), LABEL_X, TOP + 3 * ROW_STEP + 8, w, 0xFF9AA4B2);
    }
}
