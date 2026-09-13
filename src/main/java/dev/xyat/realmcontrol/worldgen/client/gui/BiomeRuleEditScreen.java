package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.client.text.KineticText;
import dev.xyat.kineticcore.api.client.overlay.GuiOverlay;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets;
import dev.xyat.realmcontrol.worldgen.config.BiomeReplacementRule;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BiomeRuleEditScreen extends KineticScreen {
    private final BiomeControlScreen parent;
    private final int ruleIndex;
    private final BiomeReplacementRule original;
    private KineticWidgets.AutoCompleteBox dimensionBox;
    private KineticWidgets.AutoCompleteBox sourceBox;
    private KineticWidgets.AutoCompleteBox targetBox;
    private KineticWidgets.AutoCompleteBox activeBox;
    private boolean removeTarget;

    BiomeRuleEditScreen(BiomeControlScreen parent, int ruleIndex, BiomeReplacementRule original) {
        super(Component.translatable("gui.realmcontrol.worldgen.biome.editor.title"));
        this.parent = parent;
        this.ruleIndex = ruleIndex;
        this.original = original;
        this.removeTarget = original != null && original.isRemoval();
        useStandardCanvas();
    }

    @Override
    protected void buildUi() {
        int x = 120;
        int width = 400;
        int y = 70;

        dimensionBox = addAutoCompleteField(x, y, width, Component.empty(), parent::dimensionSuggestions, null);
        sourceBox = addAutoCompleteField(x, y + 54, width, Component.empty(), parent::sourceSuggestions, null);
        targetBox = addAutoCompleteField(x, y + 108, width, Component.empty(), parent::targetSuggestions, null);

        dimensionBox.setValue(original == null ? BiomeReplacementRule.ALL_DIMENSIONS : original.dimensionId());
        sourceBox.setValue(original == null ? "" : original.source());
        targetBox.setValue(original == null || original.isRemoval() ? "" : original.target());
        targetBox.active = !removeTarget;

        addToggleButton(x, y + 152, 150, removeTarget,
                Component.translatable("gui.realmcontrol.worldgen.biome.remove.on"),
                Component.translatable("gui.realmcontrol.worldgen.biome.remove.off"),
                Component.translatable("gui.realmcontrol.worldgen.biome.remove.tooltip"),
                value -> {
                    removeTarget = value;
                    targetBox.active = !removeTarget;
                    if (removeTarget) targetBox.setFocused(false);
                });

        addButton(x + 240, y + 202, 80, Component.translatable("gui.realmcontrol.worldgen.biome.editor.save"), null, button -> saveRule());
        addButton(x + 325, y + 202, 75, Component.translatable("gui.realmcontrol.worldgen.config.back"), null, button -> onClose());
    }

    private void saveRule() {
        String dimension = dimensionBox.getValue().trim();
        String source = sourceBox.getValue().trim();
        String target = removeTarget ? BiomeReplacementRule.REMOVE_TARGET : targetBox.getValue().trim();
        if (!parent.validDimension(dimension)) {
            GuiOverlay.toast(Component.translatable("gui.realmcontrol.worldgen.biome.invalid_dimension"));
            return;
        }
        if (!parent.validSource(source)) {
            GuiOverlay.toast(Component.translatable("gui.realmcontrol.worldgen.biome.invalid_source"));
            return;
        }
        if (!removeTarget && !parent.validTarget(target)) {
            GuiOverlay.toast(Component.translatable("gui.realmcontrol.worldgen.biome.invalid_target"));
            return;
        }
        parent.applyRule(ruleIndex, new BiomeReplacementRule(dimension, source, target));
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
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
        renderTextFieldPlaceholder(graphics, dimensionBox, Component.translatable("gui.realmcontrol.worldgen.biome.dimension.placeholder"));
        renderTextFieldPlaceholder(graphics, sourceBox, Component.translatable("gui.realmcontrol.worldgen.biome.source.placeholder"));
        if (!removeTarget) renderTextFieldPlaceholder(graphics, targetBox, Component.translatable("gui.realmcontrol.worldgen.biome.target.placeholder"));
        if (activeBox != null) activeBox.renderSuggestions(graphics, mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeBox != null && activeBox.handleKeyPressed(keyCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected boolean canvasMouseClicked(double mouseX, double mouseY, int button) {
        activeBox = null;
        for (KineticWidgets.AutoCompleteBox box : List.of(dimensionBox, sourceBox, targetBox)) {
            if (!box.active) continue;
            if (box.isMouseOver(mouseX, mouseY)) {
                activeBox = box;
                setFocused(box);
                box.setFocused(true);
                if (box.handleMouseClick(mouseX, mouseY)) return true;
            } else {
                box.setFocused(false);
            }
        }
        return super.canvasMouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean canvasMouseReleased(double mouseX, double mouseY, int button) {
        if (activeBox != null && activeBox.handleMouseReleased(button)) return true;
        return super.canvasMouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected boolean canvasMouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (activeBox != null && activeBox.handleMouseDragged(mouseY)) return true;
        return super.canvasMouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    protected boolean canvasMouseScrolled(double mouseX, double mouseY, double delta) {
        if (activeBox != null && activeBox.handleMouseScrolled(delta)) return true;
        return super.canvasMouseScrolled(mouseX, mouseY, delta);
    }
}
