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
    private final BiomeControlPage parent;
    private int ruleIndex;
    private final BiomeReplacementRule original;
    private KineticAutoCompleteField dimensionBox;
    private KineticAutoCompleteField sourceBox;
    private KineticAutoCompleteField targetBox;
    private boolean removeTarget;

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
        int x = 120;
        int width = 400;
        int y = 70;

        dimensionBox = ui().autoComplete(x, y, width, parent::dimensionSuggestions).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.dimension.placeholder")).build();
        sourceBox = ui().autoComplete(x, y + 54, width, parent::sourceSuggestions).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.source.placeholder")).build();
        targetBox = ui().autoComplete(x, y + 108, width, parent::targetSuggestions).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.target.placeholder")).build();

        dimensionBox.setTextValue(original == null ? BiomeReplacementRule.ALL_DIMENSIONS : original.dimensionId());
        sourceBox.setTextValue(original == null ? "" : original.source());
        targetBox.setTextValue(original == null || original.isRemoval() ? "" : original.target());
        dimensionBox.setDefaultText(dimensionBox.textValue());
        sourceBox.setDefaultText(sourceBox.textValue());
        targetBox.setDefaultText(targetBox.textValue());
        setControlEnabled(targetBox, !removeTarget);

        ui().toggle(x, y + 152, 150).value(removeTarget).labels(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove.on"), KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove.off")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.remove.tooltip")).onChange(value -> {
                    removeTarget = value;
                    setControlEnabled(targetBox, !removeTarget);
                    if (removeTarget) blur(targetBox);
                }).build();

        ui().button(x + 240, y + 202, 80).text(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.editor.save")).onClick(this::saveRule).build();
        ui().button(x + 325, y + 202, 75).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
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
        KineticTheme.panel(graphics, 80, 35, 480, 290);
    }

    @Override
    protected void renderForeground(KineticGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.editor.title"), 120, 48, 400, 0xFFFFFFFF, false);
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.dimension"), 120, 60, 400, 0xFFCCCCCC, false);
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.source"), 120, 114, 400, 0xFFCCCCCC, false);
        graphics.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.biome.target"), 120, 168, 400, 0xFFCCCCCC, false);
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
