package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.client.text.KineticText;
import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete;
import dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.AutoCompleteBox;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import dev.xyat.kineticcore.api.client.widget.KineticControl;


public final class BiomeRuleEditScreen extends KineticScreen {
    private final BiomeControlScreen parent;
    private final int ruleIndex;
    private final BiomeReplacementRule original;
    private AutoCompleteBox dimensionBox;
    private AutoCompleteBox sourceBox;
    private AutoCompleteBox targetBox;
    private boolean removeTarget;

    BiomeRuleEditScreen(BiomeControlScreen parent, int ruleIndex, BiomeReplacementRule original) {
        super(Component.translatable("gui.realmcontrol.worldgen.biome.editor.title"));
        this.parent = parent;
        setParentScreen(parent);
        this.ruleIndex = ruleIndex;
        this.original = original;
        this.removeTarget = original != null && original.isRemoval();
        useCanvas(STANDARD_CANVAS_WIDTH, STANDARD_CANVAS_HEIGHT, STANDARD_SAFE_MARGIN);
    }

    @Override
    protected void buildUi() {
        int x = 120;
        int width = 400;
        int y = 70;

        dimensionBox = addAutoCompleteField(x, y, width, Component.empty(), Component.translatable("gui.realmcontrol.worldgen.biome.dimension.placeholder"), parent::dimensionSuggestions, null);
        sourceBox = addAutoCompleteField(x, y + 54, width, Component.empty(), Component.translatable("gui.realmcontrol.worldgen.biome.source.placeholder"), parent::sourceSuggestions, null);
        targetBox = addAutoCompleteField(x, y + 108, width, Component.empty(), Component.translatable("gui.realmcontrol.worldgen.biome.target.placeholder"), parent::targetSuggestions, null);

        dimensionBox.setValue(original == null ? BiomeReplacementRule.ALL_DIMENSIONS : original.dimensionId());
        sourceBox.setValue(original == null ? "" : original.source());
        targetBox.setValue(original == null || original.isRemoval() ? "" : original.target());
        setControlEnabled(targetBox, !removeTarget);

        addToggleButton(x, y + 152, 150, removeTarget,
                Component.translatable("gui.realmcontrol.worldgen.biome.remove.on"),
                Component.translatable("gui.realmcontrol.worldgen.biome.remove.off"),
                Component.translatable("gui.realmcontrol.worldgen.biome.remove.tooltip"),
                value -> {
                    removeTarget = value;
                    setControlEnabled(targetBox, !removeTarget);
                    if (removeTarget) blurControl(targetBox);
                });

        addButton(x + 240, y + 202, 80, Component.translatable("gui.realmcontrol.worldgen.biome.editor.save"), null, () -> saveRule());
        addButton(x + 325, y + 202, 75, Component.translatable("gui.realmcontrol.worldgen.config.back"), null, () -> onClose());
    }

    private void saveRule() {
        String dimension = dimensionBox.getValue().trim();
        String source = sourceBox.getValue().trim();
        String target = removeTarget ? BiomeReplacementRule.REMOVE_TARGET : targetBox.getValue().trim();
        if (!parent.validDimension(dimension)) {
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.biome.invalid_dimension"));
            return;
        }
        if (!parent.validSource(source)) {
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.biome.invalid_source"));
            return;
        }
        if (!removeTarget && !parent.validTarget(target)) {
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.biome.invalid_target"));
            return;
        }
        parent.applyRule(ruleIndex, new BiomeReplacementRule(dimension, source, target));
        onClose();
    }

    @Override
    protected boolean handleCloseRequest() {
        navigateBack();
        return true;
    }

    @Override
    protected void renderCanvasBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiTheme.shadow(graphics, canvasWidth(), canvasHeight());
        GuiTheme.panel(graphics, 80, 35, 480, 290);
    }

    @Override
    protected void renderCanvasForeground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        KineticText.drawScrollingLeft(graphics, font, Component.translatable("gui.realmcontrol.worldgen.biome.editor.title"), 120, 48, 400, 0xFFFFFFFF, false);
        KineticText.drawScrollingLeft(graphics, font, Component.translatable("gui.realmcontrol.worldgen.biome.dimension"), 120, 60, 400, 0xFFCCCCCC, false);
        KineticText.drawScrollingLeft(graphics, font, Component.translatable("gui.realmcontrol.worldgen.biome.source"), 120, 114, 400, 0xFFCCCCCC, false);
        KineticText.drawScrollingLeft(graphics, font, Component.translatable("gui.realmcontrol.worldgen.biome.target"), 120, 168, 400, 0xFFCCCCCC, false);
    }
    private static boolean isControlVisible(KineticControl control) {
        return control != null && control.isVisible();
    }

    private static boolean isControlEnabled(KineticControl control) {
        return control != null && control.isEnabled();
    }

    private static void setControlVisible(KineticControl control, boolean visible) {
        if (control != null) control.setVisible(visible);
    }

    private static void setControlEnabled(KineticControl control, boolean enabled) {
        if (control != null) control.setEnabled(enabled);
    }

}
