package dev.xyat.realmcontrol.beacon.config;

import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticEntityGrid;
import dev.xyat.kineticcore.api.client.gui.widget.list.ToggleItem;
import dev.xyat.kineticcore.api.client.search.KineticSearch;
import dev.xyat.kineticcore.api.registry.KineticRegistries;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.kineticcore.api.text.KineticI18n;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Entity cards choose a mob; the right panel edits blocked reasons in the config page's unsaved draft. */
public final class BeaconSpawnRuleEditor extends KineticPage {
    private static final String CODES = BeaconSpawnRuleDraft.CODES;
    private static final int GRID_X = 6, GRID_Y = 48, GRID_W = 316, GRID_H = 306, FORM_X = 334, FORM_W = 300;
    private final BeaconSpawnRuleDraft draft;
    private final List<String> allEntities;
    private final Map<String, KineticSearch.PreparedText> search = new LinkedHashMap<>();
    private final Consumer<List<String>> apply;
    private String selected;
    private String query = "";
    private int gridScroll;
    private KineticEntityGrid grid;
    private KineticTextField searchBox;

    private BeaconSpawnRuleEditor(Component title, List<String> initial, Consumer<List<String>> apply) {
        super(title);
        this.draft = new BeaconSpawnRuleDraft(initial);
        this.apply = apply;
        allEntities = draft.entityIds(KineticRegistries.entityTypes().ids().stream().map(Object::toString).toList());
        for (String id : allEntities) search.put(id, KineticSearch.prepare(id + " " + entityName(id).getString()));
        if (!allEntities.isEmpty()) selected = allEntities.get(0);
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        bindDeleteAction(() -> selected != null && draft.hasRule(selected), this::removeSelected);
    }

    public static void open(Component title, List<String> initial, Consumer<List<String>> apply) {
        KineticGui.openChild(new BeaconSpawnRuleEditor(title, initial, apply));
    }

    private static Component tr(String key, Object... args) {
        return KineticI18n.translatable("gui.realmcontrol.beacon.rules." + key, args);
    }

    @Override protected void build(KineticUi ui) {
        ui.button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldblock.config.back")).onClick(this::close).build();
        // Delete and Apply sit at the top right, each as wide as its label.
        ui.button(596, 4, 40).text(tr("apply")).onClick(this::applyAndClose).build();
        ui.button(596 - 4 - 56, 4, 56).text(tr("remove")).enabled(draft.hasRule(selected)).onClick(this::removeSelected).build();
        searchBox = ui.textField(GRID_X, 28, GRID_W).value(query).label(tr("search")).placeholder(tr("search"))
                .maxLength(256).onChange(this::setQuery).build();

        grid = ui.add(new KineticEntityGrid(GRID_X, GRID_Y, GRID_W, GRID_H));
        refreshGrid();
        grid.setScrollOffset(gridScroll);
        grid.onClick((index, input) -> { if (input.isLeft()) selectRule(index); });

        if (selected == null) return;
        String codes = draft.codes(selected);
        var rows = new ArrayList<ToggleItem>();
        for (char code : CODES.toCharArray()) {
            Component label = Component.literal(code + " — ").append(tr("code." + Character.toLowerCase(code)));
            rows.add(new ToggleItem(label, tr("code." + Character.toLowerCase(code) + ".tooltip"), codes.indexOf(code) >= 0, !selected.isBlank()));
        }
        ui.toggleList(FORM_X, 70, FORM_W, 8 * 16 + 4, rows).textRows()
                .onToggle((index, checked) -> setBlockedCode(CODES.charAt(index), checked)).build();
    }

    private void refreshGrid() {
        var entries = new ArrayList<KineticEntityGrid.Entry>();
        for (String id : allEntities) {
            if (!search.get(id).matches(query)) continue;
            entries.add(new KineticEntityGrid.Entry(id, ruleTooltip(id), draft.configured(id), id.equals(selected),
                    !knownEntity(id) || draft.invalid(id)));
        }
        grid.setItems(entries);
    }

    private void setQuery(String query) {
        if (this.query.equals(query)) return;
        this.query = query;
        gridScroll = 0;
        if (grid != null) { refreshGrid(); grid.setScrollOffset(0); }
    }

    private void selectRule(int index) {
        if (index < 0 || index >= grid.items().size()) return;
        gridScroll = grid.scrollOffset();
        selected = grid.items().get(index).entityId();
        rebuild();
    }

    private void setBlockedCode(char code, boolean checked) {
        if (selected == null || selected.isBlank()) return;
        draft.setBlocked(selected, code, checked);
        gridScroll = grid.scrollOffset();
        rebuild();
    }

    private void removeSelected() {
        if (selected == null || !draft.hasRule(selected)) return;
        gridScroll = grid.scrollOffset();
        draft.remove(selected);
        rebuild();
    }

    private void applyAndClose() {
        apply.accept(draft.lines());
        close();
    }

    private static boolean knownEntity(String id) {
        var key = KineticResourceIds.tryParse(id);
        return key != null && KineticRegistries.entityTypes().contains(key);
    }

    private static Component entityName(String id) {
        var key = KineticResourceIds.tryParse(id);
        var type = key == null || !KineticRegistries.entityTypes().contains(key) ? null : KineticRegistries.entityTypes().get(key);
        return type == null ? id.isBlank() ? tr("invalid_id") : Component.literal(id) : type.getDescription();
    }

    private Component ruleTooltip(String id) {
        var tooltip = entityName(id).copy().withStyle(ChatFormatting.GOLD)
                .append(Component.literal("\n" + id).withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n")).append(tr("blocked").copy().withStyle(ChatFormatting.WHITE));
        String codeField = BeaconSpawnRuleDraft.ruleCodes(draft.storedCodes(id));
        String codes = draft.codes(id);
        boolean hasKnownReason = false;
        for (char code : CODES.toCharArray()) if (codes.indexOf(code) >= 0) {
            hasKnownReason = true;
            tooltip.append(Component.literal("\n" + code + " — ").withStyle(ChatFormatting.AQUA))
                    .append(tr("code." + Character.toLowerCase(code)).copy().withStyle(ChatFormatting.WHITE));
        }
        if (!hasKnownReason) tooltip.append(Component.literal("\n")).append(tr("none").copy().withStyle(ChatFormatting.GRAY));
        StringBuilder unknown = new StringBuilder();
        for (char code : codeField.toCharArray()) if (CODES.indexOf(Character.toUpperCase(code)) < 0) unknown.append(code);
        if (!unknown.toString().isBlank()) tooltip.append(Component.literal("\n")).append(tr("other_codes", unknown.toString()).copy().withStyle(ChatFormatting.GRAY));
        tooltip.append(Component.literal("\n")).append(tr(draft.configured(id) ? "configured" : "default_rule").copy().withStyle(ChatFormatting.GREEN));
        if (!knownEntity(id) || draft.invalid(id)) tooltip.append(Component.literal("\n")).append(tr("invalid_rule").copy().withStyle(ChatFormatting.RED));
        return tooltip;
    }

    @Override protected void renderBackground(KineticGraphics g, int mouseX, int mouseY, float partialTick) {
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
        KineticTheme.verticalSeparator(g, 326, 28, height() - 32);
        g.scrollingTextCentered(title(), 320, 8, 2 * (320 - 48), 0xFFFFAA00, false);
        if (grid.items().isEmpty()) g.wrappedText(tr("empty_list"), GRID_X + 8, GRID_Y + 8, GRID_W - 16, KineticTheme.current().mutedText());
        if (selected == null) {
            g.wrappedText(tr("pick"), FORM_X, 40, FORM_W, KineticTheme.current().mutedText());
        } else {
            g.scrollingText(entityName(selected), FORM_X, 30, FORM_W, KineticTheme.current().text(), false);
            g.scrollingText(Component.literal(selected), FORM_X, 42, FORM_W, KineticTheme.current().mutedText(), false);
            g.scrollingText(tr("blocked"), FORM_X, 58, FORM_W, KineticTheme.current().text(), false);
        }
    }
}
