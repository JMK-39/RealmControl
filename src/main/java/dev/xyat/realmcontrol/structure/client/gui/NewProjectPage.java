package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.selection.Selection;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** Names a new draft and starts scanning the controller's selection into it. */
public final class NewProjectPage extends KineticPage {
    private static final int LABEL_X = 12, FIELD_X = 120, FIELD_W = 260, ROW_Y = 36, ROW_GAP = 22;
    private final StructureEditorPage parent;
    private KineticTextField nameField;
    private KineticTextField idField;
    private KineticButton startButton;
    private String name = "";
    private String id = "";
    private boolean idEdited;

    public NewProjectPage(StructureEditorPage parent) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.new.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.parent = parent;
        this.id = freeId("structure");
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::navigateBack).build();
        int startW = 64;
        startButton = ui().button(width() - 4 - startW, 4, startW).text(KineticI18n.translatable("gui.realmcontrol.structure.new.start"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.new.start.tooltip")).onClick(this::start).build();
        nameField = ui().textField(FIELD_X, ROW_Y, FIELD_W).placeholder(KineticI18n.translatable("gui.realmcontrol.structure.new.name.placeholder")).build();
        nameField.limitTextLength(64);
        nameField.setTextValue(name);
        nameField.onTextChange(value -> {
            name = value == null ? "" : value;
            if (!idEdited && idField != null) {
                String slug = slug(name);
                if (!slug.isEmpty()) {
                    id = freeId(slug);
                    idField.setTextValue(id);
                    idField.setDefaultText(id);
                    idEdited = false;
                }
            }
            updateStart();
        });
        idField = ui().textField(FIELD_X, ROW_Y + ROW_GAP, FIELD_W).placeholder(Component.literal("realmcontrol:my_house")).build();
        idField.limitTextLength(160);
        idField.setTextValue(id);
        idField.setDefaultText(id);
        idField.setValidator(StructureProject::validId);
        idField.onTextChange(value -> {
            id = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            idEdited = true;
            updateStart();
        });
        idField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.new.id.tooltip"));
        updateStart();
    }

    private void updateStart() {
        if (startButton == null) return;
        Selection.Box region = EditorClient.selection().region();
        startButton.setEnabled(Selection.problem(region) == null && StructureProject.validId(id) && taken(id) == false);
    }

    private static boolean taken(String id) {
        return EditorClient.projects().stream().anyMatch(entry -> entry.project().id.equals(id));
    }

    /** {@code realmcontrol:<base>}, numbered if a draft already has that id. */
    private static String freeId(String base) {
        String id = "realmcontrol:" + base;
        for (int n = 2; taken(id); n++) id = "realmcontrol:" + base + "_" + n;
        return id;
    }

    /** Lower-case letters, digits and underscores of a name; empty for names in other scripts. */
    private static String slug(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        return slug.length() > 48 ? slug.substring(0, 48) : slug;
    }

    private void start() {
        StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.CREATE, id, name.isBlank() ? id : name.trim());
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
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - 64 - 4 - width() / 2));
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.new.title"), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        int labelW = FIELD_X - 8 - LABEL_X;
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.new.name"), LABEL_X, ROW_Y + 4, labelW, 0xFFFFFFFF, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.new.id"), LABEL_X, ROW_Y + ROW_GAP + 4, labelW, 0xFFFFFFFF, false);
        if (taken(id)) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.new.id_taken"), FIELD_X + FIELD_W + 8, ROW_Y + ROW_GAP + 4,
                    width() - 12 - FIELD_X - FIELD_W - 8, 0xFFFF5555, false);
        }
        int y = ROW_Y + 2 * ROW_GAP + 8;
        Selection selection = EditorClient.selection();
        List<Component> facts = StructureEditorPage.selectionFacts(selection);
        String problem = Selection.problem(selection.region());
        for (Component fact : facts) {
            g.scrollingText(fact, LABEL_X, y, width() - 2 * LABEL_X, 0xFFCCCCCC, false);
            y += 12;
        }
        if (problem != null && selection.region() != null) {
            g.scrollingText(KineticI18n.translatable(problem, Selection.MAX_FOOTPRINT), LABEL_X, y, width() - 2 * LABEL_X, 0xFFFF5555, false);
            y += 12;
        }
        y += 6;
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.new.help"), LABEL_X, y, width() - 2 * LABEL_X, 0xFF9AA4B2);
    }
}
