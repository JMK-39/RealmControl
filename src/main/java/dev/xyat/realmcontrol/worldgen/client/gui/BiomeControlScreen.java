package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.client.text.KineticText;
import dev.xyat.kineticcore.api.client.overlay.GuiOverlay;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets;
import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
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
    private EditBox searchBox;
    private RuleListWidget ruleList;
    private String search = "";
    private double scroll;

    private record Snapshot(boolean enabled, List<BiomeReplacementRule> rules) {
    }

    public BiomeControlScreen(WorldGenNetwork.OpenBiomeControlPacket packet, net.minecraft.client.gui.screens.Screen parent) {
        super(Component.translatable("gui.realmcontrol.worldgen.biome.title"));
        this.parent = parent;
        this.enabled = packet.enabled();
        this.rules = new ArrayList<>(packet.rules() == null ? List.of() : packet.rules());
        this.biomes = new ArrayList<>(packet.biomes() == null ? List.of() : packet.biomes());
        this.biomeTags = new ArrayList<>(packet.biomeTags() == null ? List.of() : packet.biomeTags());
        this.dimensions = new ArrayList<>(packet.dimensions() == null ? List.of() : packet.dimensions());
        useStandardCanvas();
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
        searchBox = null;
        ruleList = null;
        int startX = 20;
        int panelW = canvasWidth() - 40;
        int topY = 16;

        addButton(startX, topY, 90,
                Component.translatable(enabled ? "gui.realmcontrol.worldgen.biome.enabled" : "gui.realmcontrol.worldgen.biome.disabled"),
                Component.translatable("gui.realmcontrol.worldgen.biome.enable.tooltip"),
                button -> {
                    enabled = !enabled;
                    button.setMessage(Component.translatable(enabled ? "gui.realmcontrol.worldgen.biome.enabled" : "gui.realmcontrol.worldgen.biome.disabled"));
                });
        addButton(startX + 95, topY, 80,
                Component.translatable("gui.realmcontrol.worldgen.biome.add"),
                Component.translatable("gui.realmcontrol.worldgen.biome.add.tooltip"),
                button -> openEditor(-1));

        int backW = 60;
        int saveW = 80;
        int backX = startX + panelW - backW;
        int saveX = backX - 5 - saveW;
        addButton(saveX, topY, saveW,
                Component.translatable("gui.realmcontrol.worldgen.worldgen.save_all"), null,
                button -> WorldGenNetwork.CHANNEL.sendToServer(new WorldGenNetwork.SaveBiomeControlPacket(enabled, new ArrayList<>(rules))));
        addButton(backX, topY, backW,
                Component.translatable("gui.realmcontrol.worldgen.config.back"), null,
                button -> onClose());

        searchBox = addTextField(startX, 47, panelW, Component.empty(), null);
        searchBox.setValue(search);
        searchBox.setResponder(value -> {
            search = value == null ? "" : value;
            if (ruleList != null) ruleList.refresh();
        });

        int listY = 76;
        int listH = canvasHeight() - listY - 16;
        ruleList = new RuleListWidget(Minecraft.getInstance(), panelW, listH, listY, listY + listH, ROW_HEIGHT);
        ruleList.setLeftPos(startX);
        ruleList.setScrollAmount(scroll);
        addEventListWidget(ruleList);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    public void handleSaveResult(boolean success) {
        if (success) {
            commitDraft();
            GuiOverlay.toast(Component.translatable("gui.realmcontrol.worldgen.biome.save_success"));
        } else {
            GuiOverlay.toast(Component.translatable("gui.realmcontrol.worldgen.biome.save_invalid"));
        }
    }

    void applyRule(int index, BiomeReplacementRule rule) {
        if (index >= 0 && index < rules.size()) {
            rules.set(index, rule);
        } else {
            rules.add(rule);
        }
        if (ruleList != null) ruleList.refresh();
    }

    List<String> dimensionSuggestions() {
        List<String> result = new ArrayList<>();
        result.add(BiomeReplacementRule.ALL_DIMENSIONS);
        result.addAll(dimensions);
        return result;
    }

    List<String> sourceSuggestions() {
        List<String> result = new ArrayList<>(biomes.size() + biomeTags.size());
        result.addAll(biomes);
        result.addAll(biomeTags);
        return result;
    }

    List<String> targetSuggestions() {
        return biomes;
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
        if (minecraft != null) minecraft.setScreen(new BiomeRuleEditScreen(this, index, existing));
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
        graphics.fill(16, 40, canvasWidth() - 16, 41, 0xFF444444);
        graphics.fill(16, 71, canvasWidth() - 16, 72, 0xFF444444);
        int listY = 76;
        int listH = canvasHeight() - listY - 16;
        GuiTheme.panelAlt(graphics, 18, listY - 2, canvasWidth() - 36, listH + 4);
        renderScaledList(ruleList, graphics, mouseX, mouseY, partialTick);
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
        if (searchBox != null) {
            renderTextFieldPlaceholder(graphics, searchBox, Component.translatable("gui.realmcontrol.worldgen.biome.search"));
        }
    }

    private final class RuleListWidget extends KineticWidgets.SmoothSelectionList<RuleListWidget.Entry> {
        RuleListWidget(Minecraft minecraft, int width, int height, int top, int bottom, int itemHeight) {
            super(minecraft, width, height, top, bottom, itemHeight);
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

        final class Entry extends ObjectSelectionList.Entry<Entry> {
            private final int ruleIndex;
            private final BiomeReplacementRule rule;
            private final Button editButton;

            Entry(int ruleIndex, BiomeReplacementRule rule) {
                this.ruleIndex = ruleIndex;
                this.rule = rule;
                this.editButton = KineticWidgets.createCompactButton(0, 0, 54,
                        Component.translatable("gui.realmcontrol.worldgen.worldgen.edit"), null,
                        button -> openEditor(ruleIndex));
            }

            @Override
            public void render(@NotNull GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
                int contentH = ROW_HEIGHT - 2;
                graphics.fill(left, top, left + width, top + contentH, index % 2 == 0 ? 0x88333333 : 0x881C1C1C);
                graphics.renderOutline(left, top, width, contentH, hovered ? 0xFF55AAFF : 0xFF555555);

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
                if (button == 0) {
                    if (editButton.mouseClicked(mouseX, mouseY, button)) return true;
                    openEditor(ruleIndex);
                    return true;
                }
                if (button == 1) {
                    openContextMenu(mouseX, mouseY, List.of(
                            GuiOverlay.MenuItem.action(Component.translatable("gui.realmcontrol.worldgen.biome.edit"), () -> openEditor(ruleIndex)),
                            GuiOverlay.MenuItem.danger(Component.translatable("gui.realmcontrol.worldgen.biome.delete"), () -> removeRule(ruleIndex))
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
