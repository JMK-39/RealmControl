package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.client.input.KineticMouseButtons;
import dev.xyat.kineticcore.api.client.text.KineticText;
import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets;
import dev.xyat.kineticcore.api.client.widget.input.KineticTextFields.KineticEditBox;
import dev.xyat.kineticcore.api.client.widget.button.KineticButtons.StateButton;
import dev.xyat.kineticcore.api.client.widget.scroll.KineticScroll.SmoothEntry;
import dev.xyat.kineticcore.api.client.widget.scroll.KineticScroll.SmoothSelectionList;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BiomeControlScreen extends KineticScreen {
    private static final int ROW_HEIGHT = 32;
    private final net.minecraft.client.gui.screens.Screen parent;
    private boolean enabled;
    private final List<BiomeReplacementRule> rules;
    private final List<String> biomes;
    private final List<String> biomeTags;
    private final List<String> dimensions;
    private RuleListWidget ruleList;
    private String search = "";
    private double scroll;

    public Screen getParent() {
        return parent;
    }

    private record Snapshot(boolean enabled, List<BiomeReplacementRule> rules) {
    }

    public BiomeControlScreen(WorldGenNetwork.OpenBiomeControlPacket packet, net.minecraft.client.gui.screens.Screen parent) {
        super(Component.translatable("gui.realmcontrol.worldgen.biome.title"));
        this.parent = parent;
        setParentScreen(parent);
        this.enabled = packet.enabled();
        this.rules = new ArrayList<>(packet.rules() == null ? List.of() : packet.rules());
        this.biomes = new ArrayList<>(packet.biomes() == null ? List.of() : packet.biomes());
        this.biomeTags = new ArrayList<>(packet.biomeTags() == null ? List.of() : packet.biomeTags());
        this.dimensions = new ArrayList<>(packet.dimensions() == null ? List.of() : packet.dimensions());
        useCanvas(STANDARD_CANVAS_WIDTH, STANDARD_CANVAS_HEIGHT, STANDARD_SAFE_MARGIN);
        configureStandaloneDraft(
                () -> new Snapshot(this.enabled, new ArrayList<>(this.rules)),
                snapshot -> {
                    this.enabled = snapshot.enabled();
                    this.rules.clear();
                    this.rules.addAll(snapshot.rules());
                }
        );
    }

    @Override
    protected void buildUi() {
        KineticEditBox searchBox;
        ruleList = null;
        int startX = 20;
        int panelW = canvasWidth() - 40;
        int topY = 16;

        addButtonWithHandler(startX, topY, 90,
                Component.translatable(enabled ? "gui.realmcontrol.worldgen.biome.enabled" : "gui.realmcontrol.worldgen.biome.disabled"),
                Component.translatable("gui.realmcontrol.worldgen.biome.enable.tooltip"),
                button -> {
                    enabled = !enabled;
                    button.setText(Component.translatable(enabled ? "gui.realmcontrol.worldgen.biome.enabled" : "gui.realmcontrol.worldgen.biome.disabled"));
                });
        addButton(startX + 95, topY, 80,
                Component.translatable("gui.realmcontrol.worldgen.biome.add"),
                Component.translatable("gui.realmcontrol.worldgen.biome.add.tooltip"),
                () -> openEditor(-1));

        int backW = 60;
        int saveW = 80;
        int backX = startX + panelW - backW;
        int saveX = backX - 5 - saveW;
        addButton(saveX, topY, saveW,
                Component.translatable("gui.realmcontrol.worldgen.worldgen.save_all"), null,
                () -> WorldGenNetwork.CHANNEL.sendToServer(new WorldGenNetwork.SaveBiomeControlPacket(enabled, new ArrayList<>(rules))));
        addButton(backX, topY, backW,
                Component.translatable("gui.realmcontrol.worldgen.config.back"), null,
                this::onClose);

        searchBox = addTextField(startX, 47, panelW, Component.empty(), Component.translatable("gui.realmcontrol.worldgen.biome.search"), null, null);
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value == null ? "" : value;
            if (ruleList != null) ruleList.refresh();
        });

        int listY = 76;
        int listH = canvasHeight() - listY - 16;
        ruleList = new RuleListWidget(panelW, listH, listY, listY + listH, ROW_HEIGHT);
        ruleList.setLeftPos(startX);
        ruleList.setScrollAmount(scroll);
        addSmoothSelectionList(ruleList);
    }

    @Override
    protected boolean handleCloseRequest() {
        navigateBack();
        return true;
    }

    public void handleSaveResult(boolean success) {
        if (success) {
            commitDraft();
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.biome.save_success"));
        } else {
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.biome.save_invalid"));
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

    List<dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion> dimensionSuggestions() {
        List<dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion> result = new ArrayList<>();
        result.add(new dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion(
                BiomeReplacementRule.ALL_DIMENSIONS, Component.empty()));
        for (String value : dimensions) result.add(localizedSuggestion(value, "dimension"));
        return result;
    }

    List<dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion> sourceSuggestions() {
        List<dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion> result = new ArrayList<>(biomes.size() + biomeTags.size());
        for (String value : biomes) result.add(localizedSuggestion(value, "biome"));
        for (String value : biomeTags) result.add(localizedSuggestion(value, "tag.biome"));
        return result;
    }

    List<dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion> targetSuggestions() {
        return biomes.stream().map(value -> localizedSuggestion(value, "biome")).toList();
    }

    private dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion localizedSuggestion(String raw, String prefix) {
        String value = raw == null ? "" : raw;
        String id = value.startsWith("#") ? value.substring(1) : value;
        net.minecraft.resources.ResourceLocation location = dev.xyat.kineticcore.api.resource.KineticResourceIds.tryParse(id);
        if (location == null) {
            return new dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion(value, Component.empty());
        }
        String translated = dev.xyat.kineticcore.api.client.search.KineticSearch.resolveTranslation(
                prefix + "." + location.getNamespace() + "." + location.getPath(),
                prefix.startsWith("tag.") ? "tag." + location.getNamespace() + "." + location.getPath() : ""
        );
        return new dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.Suggestion(
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
        if (ruleList != null) scroll = ruleList.getScrollAmount();
        BiomeReplacementRule existing = index >= 0 && index < rules.size() ? rules.get(index) : null;
        KineticClientRuntime.openScreen(new BiomeRuleEditScreen(this, index, existing));
    }

    private void removeRule(int index) {
        if (index < 0 || index >= rules.size()) return;
        rules.remove(index);
        if (ruleList != null) ruleList.refresh();
    }

    @Override
    protected void renderCanvasBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.shadow(graphics, canvasWidth(), canvasHeight());
        GuiTheme.panel(graphics, 10, 8, canvasWidth() - 20, canvasHeight() - 16);
        GuiTheme.separator(graphics, 16, 40, canvasWidth() - 32);
        GuiTheme.separator(graphics, 16, 71, canvasWidth() - 32);
        int listY = 76;
        int listH = canvasHeight() - listY - 16;
        GuiTheme.panelAlt(graphics, 18, listY - 2, canvasWidth() - 36, listH + 4);
        renderSmoothSelectionList(ruleList, graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderCanvasForeground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int titleRight = canvasWidth() - 171;
        KineticText.drawScrollingLeft(
                graphics,
                font,
                Component.translatable("gui.realmcontrol.worldgen.biome.title"),
                210,
                22,
                Math.max(1, titleRight - 210),
                0xFFFFFFFF,
                false
        );
    }

    private final class RuleListWidget extends SmoothSelectionList<RuleListWidget.Entry> {
        RuleListWidget(int width, int height, int top, int bottom, int itemHeight) {
            super(width, height, top, bottom, itemHeight);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            refresh();
        }

        void refresh() {
            clearEntries();
            String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < rules.size(); i++) {
                BiomeReplacementRule rule = rules.get(i);
                String text = rule.dimensionId() + " " + rule.source() + " " + rule.target();
                if (query.isEmpty() || text.toLowerCase(Locale.ROOT).contains(query)) {
                    addEntry(new Entry(i, rule));
                }
            }
        }

        @Override
        public int getRowLeft() {
            return getLeft() + 2;
        }

        @Override
        public int getRowWidth() {
            return width - 12;
        }

        final class Entry extends SmoothEntry<Entry> {
            private final int ruleIndex;
            private final BiomeReplacementRule rule;
            private final StateButton editButton;

            Entry(int ruleIndex, BiomeReplacementRule rule) {
                this.ruleIndex = ruleIndex;
                this.rule = rule;
                this.editButton = KineticWidgets.createCompactButton(0, 0, 54,
                        Component.translatable("gui.realmcontrol.worldgen.worldgen.edit"), null,
                        () -> openEditor(ruleIndex));
            }

            @Override
            public void render(@NotNull GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
                int contentH = ROW_HEIGHT - 2;
                GuiTheme.surface(
                        graphics,
                        left,
                        top,
                        width,
                        contentH,
                        index % 2 == 0 ? GuiTheme.Surface.PANEL : GuiTheme.Surface.PANEL_ALT
                );
                if (hovered) {
                    GuiTheme.stateOutline(graphics, left, top, width, contentH, false, true, false);
                } else {
                    GuiTheme.indicatorOutline(graphics, left, top, width, contentH, GuiTheme.Indicator.MUTED);
                }

                int editX = left + width - editButton.getWidth() - 4;
                int editY = top + Math.max(0, (contentH - editButton.getHeight()) / 2);
                editButton.setX(editX);
                editButton.setY(editY);
                editButton.render(graphics, mouseX, mouseY, partialTick);

                Component dimension = BiomeReplacementRule.ALL_DIMENSIONS.equals(rule.dimensionId())
                        ? Component.translatable("gui.realmcontrol.worldgen.biome.dimension.all")
                        : Component.translatable("gui.realmcontrol.worldgen.biome.dimension.value", rule.dimensionId());
                Component target = rule.isRemoval()
                        ? Component.translatable("gui.realmcontrol.worldgen.biome.remove_target")
                        : Component.translatable("gui.realmcontrol.worldgen.biome.target.value", rule.target());
                int textX = left + 6;
                int textWidth = Math.max(1, editX - textX - 4);
                KineticText.drawScrollingLeft(
                        graphics,
                        font,
                        Component.translatable("gui.realmcontrol.worldgen.biome.row", rule.source(), target),
                        textX,
                        top + 6,
                        textWidth,
                        0xFFFFFFFF,
                        false
                );
                KineticText.drawScrollingLeft(
                        graphics,
                        font,
                        Component.translatable("gui.realmcontrol.worldgen.biome.row.dimension", dimension),
                        textX,
                        top + 17,
                        textWidth,
                        0xFFCCCCCC,
                        false
                );
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (KineticMouseButtons.isPrimary(button)) {
                    if (editButton.mouseClicked(mouseX, mouseY, button)) return true;
                    openEditor(ruleIndex);
                    return true;
                }
                if (KineticMouseButtons.isSecondary(button)) {
                    openContextMenu(mouseX, mouseY, List.of(
                            KineticOverlays.MenuItem.action(Component.translatable("gui.realmcontrol.worldgen.biome.edit"), () -> openEditor(ruleIndex)),
                            KineticOverlays.MenuItem.danger(Component.translatable("gui.realmcontrol.worldgen.biome.delete"), () -> removeRule(ruleIndex))
                    ));
                    return true;
                }
                return false;
            }

            @Override
            public @NotNull Component getNarration() {
                return Component.translatable("gui.realmcontrol.worldgen.biome.row", rule.source(), rule.target());
            }
        }
    }
}
