package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.*;
import dev.xyat.kineticcore.api.client.gui.widget.list.*;
import dev.xyat.kineticcore.api.client.search.KineticSuggestion;

import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BiomeControlPage extends KineticPage {
    private static final int ROW_HEIGHT = 32;
    private boolean enabled;
    private final List<BiomeReplacementRule> rules;
    private final List<String> biomes;
    private final List<String> biomeTags;
    private final List<String> dimensions;
    private RuleListWidget ruleList;
    private String search = "";
    private int scroll;
    private int selectedRule = -1;

    private record Snapshot(boolean enabled, List<BiomeReplacementRule> rules) {
    }

    public BiomeControlPage(WorldGenNetwork.OpenBiomeControlPacket packet) {
        super(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.title"));
        this.enabled = packet.enabled();
        this.rules = new ArrayList<>(packet.rules() == null ? List.of() : packet.rules());
        this.biomes = new ArrayList<>(packet.biomes() == null ? List.of() : packet.biomes());
        this.biomeTags = new ArrayList<>(packet.biomeTags() == null ? List.of() : packet.biomeTags());
        this.dimensions = new ArrayList<>(packet.dimensions() == null ? List.of() : packet.dimensions());
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        configureStandaloneDraft(
                () -> new Snapshot(this.enabled, new ArrayList<>(this.rules)),
                snapshot -> {
                    this.enabled = snapshot.enabled();
                    this.rules.clear();
                    this.rules.addAll(snapshot.rules());
                }
        );
    }

    private static final int SWITCH_W = 80, LIST_Y = 46;

    @Override
    protected void build(KineticUi ui) {
        bindDeleteAction(() -> selectedRule >= 0 && selectedRule < rules.size(), () -> removeRule(selectedRule));
        KineticTextField searchBox;
        ruleList = null;
        int startX = 4;
        int panelW = width() - 8;
        int topY = 4;

        // Header: Back, the title, then the switch, Add and Save at the top right; the search row right under it.
        int saveX = startX + panelW - 40;
        int addX = saveX - 4 - 56;
        int switchX = addX - 4 - SWITCH_W;
        ui().button(switchX, topY, SWITCH_W).text(KineticI18n.translatable(enabled ? "gui.realmcontrol.worldgen.biome.enabled" : "gui.realmcontrol.worldgen.biome.disabled")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.enable.tooltip")).onClick(button -> {
                    enabled = !enabled;
                    button.setText(KineticI18n.translatable(enabled ? "gui.realmcontrol.worldgen.biome.enabled" : "gui.realmcontrol.worldgen.biome.disabled"));
                }).build();
        ui().button(addX, topY, 56).text(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.add")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.add.tooltip")).onClick(() -> openEditor(-1)).build();

        int backX = startX;
        ui().button(saveX, topY, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_all")).onClick(() -> WorldGenNetwork.CHANNEL.sendToServer(new WorldGenNetwork.SaveBiomeControlPacket(enabled, new ArrayList<>(rules)))).build();
        ui().button(backX, topY, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();

        searchBox = ui().textField(startX, 28, panelW).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.search")).build();
        searchBox.setTextValue(search);
        searchBox.setDefaultText(search);
        searchBox.onTextChange(value -> {
            search = value == null ? "" : value;
            if (ruleList != null) ruleList.refresh();
        });

        int listY = LIST_Y;
        int listH = height() - listY - 4;
        ruleList = ui.add(new RuleListWidget(startX, listY + 2, panelW, listH - 2));
        ruleList.setScrollOffset(scroll);
    }

    @Override
    protected boolean onCloseRequested() {
        navigateBack();
        return true;
    }

    public void handleSaveResult(boolean success) {
        if (success) {
            commitDraft();
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.save_success"));
        } else {
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.save_invalid"));
        }
    }

    int applyRule(int index, BiomeReplacementRule rule) {
        int appliedIndex = index;
        if (index >= 0 && index < rules.size()) {
            rules.set(index, rule);
        } else {
            rules.add(rule);
            appliedIndex = rules.size() - 1;
        }
        if (ruleList != null) ruleList.refresh();
        return appliedIndex;
    }

    List<dev.xyat.kineticcore.api.client.search.KineticSuggestion> dimensionSuggestions() {
        List<dev.xyat.kineticcore.api.client.search.KineticSuggestion> result = new ArrayList<>();
        result.add(new dev.xyat.kineticcore.api.client.search.KineticSuggestion(
                BiomeReplacementRule.ALL_DIMENSIONS, Component.empty()));
        for (String value : dimensions) result.add(localizedSuggestion(value, "dimension"));
        return result;
    }

    List<dev.xyat.kineticcore.api.client.search.KineticSuggestion> sourceSuggestions() {
        List<dev.xyat.kineticcore.api.client.search.KineticSuggestion> result = new ArrayList<>(biomes.size() + biomeTags.size());
        for (String value : biomes) result.add(localizedSuggestion(value, "biome"));
        for (String value : biomeTags) result.add(localizedSuggestion(value, "tag.biome"));
        return result;
    }

    List<dev.xyat.kineticcore.api.client.search.KineticSuggestion> targetSuggestions() {
        return biomes.stream().map(value -> localizedSuggestion(value, "biome")).toList();
    }

    private dev.xyat.kineticcore.api.client.search.KineticSuggestion localizedSuggestion(String raw, String prefix) {
        String value = raw == null ? "" : raw;
        String id = value.startsWith("#") ? value.substring(1) : value;
        net.minecraft.resources.ResourceLocation location = dev.xyat.kineticcore.api.resource.KineticResourceIds.tryParse(id);
        if (location == null) {
            return new dev.xyat.kineticcore.api.client.search.KineticSuggestion(value, Component.empty());
        }
        String translated = dev.xyat.kineticcore.api.client.search.KineticSearch.resolveTranslation(
                prefix + "." + location.getNamespace() + "." + location.getPath(),
                prefix.startsWith("tag.") ? "tag." + location.getNamespace() + "." + location.getPath() : ""
        );
        return new dev.xyat.kineticcore.api.client.search.KineticSuggestion(
                value, translated == null ? Component.empty() : Component.literal(translated));
    }

    boolean validDimension(String value) {
        return BiomeReplacementRule.ALL_DIMENSIONS.equals(value) || dimensions.contains(value);
    }

    boolean validSource(String value) {
        return biomes.contains(value) || biomeTags.contains(value);
    }

    boolean validTarget(String value) {
        return biomes.contains(value);
    }

    private void openEditor(int index) {
        if (ruleList != null) scroll = ruleList.scrollOffset();
        BiomeReplacementRule existing = index >= 0 && index < rules.size() ? rules.get(index) : null;
        openChild(new BiomeRuleEditPage(this, index, existing));
    }

    private void removeRule(int index) {
        if (index < 0 || index >= rules.size()) return;
        rules.remove(index);
        selectedRule = -1;
        if (ruleList != null) ruleList.refresh();
    }

    @Override
    protected void renderBackground(KineticGraphics graphics, int mouseX, int mouseY, float partialTick) {
        KineticTheme.shadow(graphics, width(), height());
        KineticTheme.panel(graphics, 0, 0, width(), height());
        KineticTheme.separator(graphics, 4, 24, width() - 8);
        int listY = LIST_Y;
        int listH = height() - listY - 4;
        KineticTheme.surface(graphics, 4, listY, width() - 8, listH, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int titleRight = width() - 4 - 40 - 4 - 56 - 4 - SWITCH_W - 4;
        // Centred on the screen, clear of Back and the header buttons.
        graphics.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.title"), width() / 2, 8, Math.max(1, 2 * Math.min(width() / 2 - 48, titleRight - width() / 2)), 0xFFFFAA00, false);
    }

    /**
     * 规则列表（原 SmoothSelectionList）：行内“编辑”按钮由 KineticTheme.button 绘制，点击在 onRowClick 中判定。
     * Rule list (formerly SmoothSelectionList): the inline edit button is painted with KineticTheme.button and hit-tested
     * in onRowClick.
     */
    private final class RuleListWidget extends KineticRowList<RuleRow> {
        private static final int EDIT_W = 40;
        private static final int EDIT_H = CONTROL_HEIGHT;

        RuleListWidget(int x, int y, int width, int height) {
            super(x, y, width, height, ROW_HEIGHT);
            refresh();
        }

        void refresh() {
            List<RuleRow> rows = new ArrayList<>();
            String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < rules.size(); i++) {
                BiomeReplacementRule rule = rules.get(i);
                String text = rule.dimensionId() + " " + rule.source() + " " + rule.target();
                if (query.isEmpty() || text.toLowerCase(Locale.ROOT).contains(query)) {
                    rows.add(new RuleRow(i, rule));
                }
            }
            setItems(rows);
        }

        // 原行布局：左缩进 2，宽度 = 列表宽 - 12 / Former row layout: 2 px left inset, width = list width - 12.
        private int rowLeft() {
            return controlX() + 2;
        }

        private int rowWidth() {
            return controlWidth() - 12;
        }

        private int editX() {
            return rowLeft() + rowWidth() - EDIT_W - 4;
        }

        private int editY(int top) {
            return top + Math.max(0, (ROW_HEIGHT - 2 - EDIT_H) / 2);
        }

        @Override
        protected void renderRowBackground(KineticGraphics graphics, int index, int x, int y, int width, int height,
                                           boolean hovered, boolean selected) {
            // 背景在 renderRow 中按原样绘制 / The background is drawn in renderRow exactly as before.
        }

        @Override
        protected void renderRow(KineticGraphics graphics, RuleRow row, int index, int x, int top, int w, int h,
                                 boolean hovered, boolean selected) {
            int left = rowLeft();
            int width = rowWidth();
            int contentH = ROW_HEIGHT - 2;
            KineticTheme.surface(
                    graphics,
                    left,
                    top,
                    width,
                    contentH,
                    index % 2 == 0 ? KineticTheme.Surface.PANEL : KineticTheme.Surface.PANEL_ALT
            );
            if (hovered || row.ruleIndex() == selectedRule) {
                KineticTheme.stateOutline(graphics, left, top, width, contentH, row.ruleIndex() == selectedRule, hovered, false);
            } else {
                KineticTheme.indicatorOutline(graphics, left, top, width, contentH, KineticTheme.Indicator.MUTED);
            }

            int editX = editX();
            int editY = editY(top);
            boolean editHovered = mouseX() >= editX && mouseX() < editX + EDIT_W
                    && mouseY() >= editY && mouseY() < editY + EDIT_H;
            KineticTheme.button(graphics, editX, editY, EDIT_W, EDIT_H,
                    KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.edit"), editHovered, true, false);

            BiomeReplacementRule rule = row.rule();
            Component dimension = BiomeReplacementRule.ALL_DIMENSIONS.equals(rule.dimensionId())
                    ? KineticI18n.translatable("gui.realmcontrol.worldgen.biome.dimension.all")
                    : KineticI18n.translatable("gui.realmcontrol.worldgen.biome.dimension.value", rule.dimensionId());
            Component target = rule.isRemoval()
                    ? KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove_target")
                    : KineticI18n.translatable("gui.realmcontrol.worldgen.biome.target.value", rule.target());
            int textX = left + 6;
            int textWidth = Math.max(1, editX - textX - 4);
            graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.row", rule.source(), target), textX, top + 6, textWidth, 0xFFFFFFFF, false);
            graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.row.dimension", dimension), textX, top + 17, textWidth, 0xFFCCCCCC, false);
        }

        @Override
        protected boolean onRowClick(RuleRow row, int index, MouseInput input) {
            selectedRule = row.ruleIndex();
            // 原行为：左键（含编辑按钮）打开编辑器，右键打开菜单 / Former behaviour: left click (edit button included) opens the editor, right click opens the menu.
            if (input.isLeft()) {
                if (input.inside(editX(), editY(rowTop(index)), EDIT_W, EDIT_H)) {
                    playClickSound(); // 与原行内按钮一致 / Same click sound as the former inline button.
                }
                openEditor(row.ruleIndex());
                return true;
            }
            if (input.isRight()) {
                openContextMenu(input.x(), input.y(), List.of(
                        KineticOverlays.MenuItem.action(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.edit"), () -> openEditor(row.ruleIndex())),
                        KineticOverlays.MenuItem.danger(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.delete"), () -> removeRule(row.ruleIndex()))
                ));
                return true;
            }
            return false;
        }
    }

    private record RuleRow(int ruleIndex, BiomeReplacementRule rule) {
    }
}
