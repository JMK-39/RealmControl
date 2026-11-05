package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.*;
import dev.xyat.kineticcore.api.client.gui.widget.list.*;

import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;


public final class BiomeRuleEditPage extends KineticPage {
    private static final int PANEL_WIDTH = 480;
    private static final int PANEL_HEIGHT = 158;
    private final BiomeControlPage parent;
    private int ruleIndex;
    private final BiomeReplacementRule original;
    private KineticAutoCompleteField dimensionBox;
    private KineticAutoCompleteField sourceBox;
    private KineticAutoCompleteField targetBox;
    private boolean removeTarget;
    private int panelX;
    private int panelY;

    BiomeRuleEditPage(BiomeControlPage parent, int ruleIndex, BiomeReplacementRule original) {
        super(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.editor.title"));
        this.parent = parent;
        this.ruleIndex = ruleIndex;
        this.original = original;
        this.removeTarget = original != null && original.isRemoval();
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
    }

    @Override
    protected void build(KineticUi ui) {
        panelX = (width() - PANEL_WIDTH) / 2;
        panelY = (height() - PANEL_HEIGHT) / 2;
        // Header: Back, the title and Apply; then the three fields with their labels and the removal switch.
        int x = panelX + 8;
        int width = PANEL_WIDTH - 16;
        int y = panelY + 39;

        dimensionBox = ui().autoComplete(x, y, width, parent::dimensionSuggestions).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.dimension.placeholder")).build();
        sourceBox = ui().autoComplete(x, y + 36, width, parent::sourceSuggestions).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.source.placeholder")).build();
        targetBox = ui().autoComplete(x, y + 72, width, parent::targetSuggestions).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.target.placeholder")).build();

        dimensionBox.setTextValue(original == null ? BiomeReplacementRule.ALL_DIMENSIONS : original.dimensionId());
        sourceBox.setTextValue(original == null ? "" : original.source());
        targetBox.setTextValue(original == null || original.isRemoval() ? "" : original.target());
        dimensionBox.setDefaultText(dimensionBox.textValue());
        sourceBox.setDefaultText(sourceBox.textValue());
        targetBox.setDefaultText(targetBox.textValue());
        setControlEnabled(targetBox, !removeTarget);

        ui().toggle(x, y + 96, 96).value(removeTarget).labels(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove.on"), KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove.off")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove.tooltip")).onChange(value -> {
                    removeTarget = value;
                    setControlEnabled(targetBox, !removeTarget);
                    if (removeTarget) blur(targetBox);
                }).build();

        ui().button(panelX + PANEL_WIDTH - 4 - 56, panelY + 4, 56).text(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.editor.save")).onClick(this::saveRule).build();
        ui().button(panelX + 4, panelY + 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
    }

    private void saveRule() {
        String dimension = dimensionBox.textValue().trim();
        String source = sourceBox.textValue().trim();
        String target = removeTarget ? BiomeReplacementRule.REMOVE_TARGET : targetBox.textValue().trim();
        if (!parent.validDimension(dimension)) {
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.invalid_dimension"));
            return;
        }
        if (!parent.validSource(source)) {
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.invalid_source"));
            return;
        }
        if (!removeTarget && !parent.validTarget(target)) {
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.invalid_target"));
            return;
        }
        ruleIndex = parent.applyRule(ruleIndex, new BiomeReplacementRule(dimension, source, target));
    }

    @Override
    protected boolean onCloseRequested() {
        navigateBack();
        return true;
    }

    @Override
    protected void renderBackground(KineticGraphics graphics, int mouseX, int mouseY, float partialTick) {
        KineticTheme.shadow(graphics, width(), height());
        KineticTheme.panel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT);
        KineticTheme.separator(graphics, panelX + 4, panelY + 24, PANEL_WIDTH - 8);
    }

    @Override
    protected void renderForeground(KineticGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.editor.title"), panelX + PANEL_WIDTH / 2, panelY + 8, PANEL_WIDTH - 2 * (4 + 56 + 4), 0xFFFFFFFF, false);
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.dimension"), panelX + 8, panelY + 28, PANEL_WIDTH - 16, 0xFFCCCCCC, false);
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.source"), panelX + 8, panelY + 64, PANEL_WIDTH - 16, 0xFFCCCCCC, false);
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.target"), panelX + 8, panelY + 100, PANEL_WIDTH - 16, 0xFFCCCCCC, false);
    }
    private static boolean isControlVisible(KineticControl control) {
        return control != null && control.controlVisible();
    }

    private static boolean isControlEnabled(KineticControl control) {
        return control != null && control.isEnabled();
    }

    private static void setControlVisible(KineticControl control, boolean visible) {
        if (control != null) control.setControlVisible(visible);
    }

    private static void setControlEnabled(KineticControl control, boolean enabled) {
        if (control != null) control.setEnabled(enabled);
    }

}
