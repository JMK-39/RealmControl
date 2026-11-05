package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Picks one loot table from every table the server knows, as text rows: chest tables (those under a
 * {@code chests/} folder) first by default. Click selects (yellow), double click or Apply hands it back.
 */
public final class LootTablePickerPage extends KineticPage implements EditorClient.Listener {
    private static final int LIST_X = 4, LIST_Y = 46, ROW_HEIGHT = 16;
    private final Consumer<String> onApply;
    private String chosen;
    private String search = "";
    private String filter = "chests";
    private TableList list;
    private long lastClick;
    private int lastClickIndex = -1;

    public LootTablePickerPage(String initial, Consumer<String> onApply) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.loot.picker.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.chosen = initial == null ? "" : initial;
        this.onApply = onApply;
        if (!chosen.isEmpty() && !chosen.contains("chests/")) filter = "all";
    }

    @Override
    protected void build(KineticUi ui) {
        if (EditorClient.lootTables() == null) StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.LOOT_TABLES, "", "");
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::navigateBack).build();
        ui().button(width() - 4 - 48, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.structure.biomes.apply")).onClick(this::apply).build();
        int filterW = 120, listW = width() - 8;
        KineticTextField searchField = ui().textField(LIST_X, 28, listW - filterW - 4)
                .placeholder(KineticI18n.translatable("gui.realmcontrol.structure.loot.picker.search")).build();
        searchField.setTextValue(search);
        searchField.onTextChange(value -> {
            search = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            refresh();
        });
        ui().dropdown(LIST_X + listW - filterW, 28, filterW, List.of(
                        new KineticDropdown.Option("chests", KineticI18n.translatable("gui.realmcontrol.structure.loot.picker.chests")),
                        new KineticDropdown.Option("all", KineticI18n.translatable("gui.realmcontrol.structure.loot.picker.all"))))
                .selected(filter).onChange(value -> {
                    filter = value;
                    refresh();
                }).build();
        list = ui.add(new TableList(LIST_X, LIST_Y + 2, listW, height() - LIST_Y - 6));
        refresh();
    }

    private void refresh() {
        if (list == null) return;
        List<String> ids = new ArrayList<>();
        List<String> all = EditorClient.lootTables();
        if (all != null) {
            for (String id : all) {
                if (filter.equals("chests") && !id.contains("chests/")) continue;
                if (!search.isEmpty() && !id.contains(search)) continue;
                ids.add(id);
            }
        }
        list.setItems(ids);
        int index = ids.indexOf(chosen);
        if (index >= 0) list.scrollTo(index);
    }

    private void apply() {
        if (chosen.isEmpty()) return;
        onApply.accept(chosen);
        navigateBack();
    }

    @Override
    public void lootTablesArrived(List<String> ids) {
        refresh();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
        KineticTheme.surface(g, LIST_X, LIST_Y, width() - 8, height() - LIST_Y - 4, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - 48 - 4 - width() / 2));
        Component title = list == null ? title() : KineticI18n.translatable("gui.realmcontrol.structure.loot.picker.count", title(), list.items().size());
        g.scrollingTextCentered(title, width() / 2, 8, titleRoom, 0xFFFFAA00, false);
    }

    private final class TableList extends KineticRowList<String> {
        TableList(int x, int y, int width, int height) {
            super(x, y, width, height, ROW_HEIGHT);
        }

        @Override
        protected void renderRow(KineticGraphics g, String id, int index, int x, int y, int width, int height, boolean hovered, boolean selected) {
            if (id.equals(chosen)) KineticTheme.stateOutline(g, x, y, width, height, true, false, false);
            else if (hovered) KineticTheme.stateOutline(g, x, y, width, height, false, true, false);
            g.scrollingText(Component.literal(id), x + 4, y + (height - g.lineHeight()) / 2 + 1, width - 8, 0xFFFFFFFF, false);
        }

        @Override
        protected boolean onRowClick(String id, int index, MouseInput input) {
            if (!input.isLeft()) return false;
            chosen = id;
            long now = System.nanoTime();
            boolean doubleClick = index == lastClickIndex && now - lastClick < 350_000_000L;
            lastClickIndex = index;
            lastClick = doubleClick ? 0L : now;
            if (doubleClick) {
                playClickSound();
                apply();
            }
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable(EditorClient.lootTables() == null ? "gui.realmcontrol.structure.loot.picker.loading" : "gui.realmcontrol.structure.loot.picker.none");
        }
    }
}
