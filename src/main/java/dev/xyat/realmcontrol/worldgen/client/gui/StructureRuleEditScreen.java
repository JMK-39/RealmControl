package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.client.widget.input.KineticTextFields.KineticEditBox;
import dev.xyat.kineticcore.api.client.widget.button.KineticButtons.StateButton;
import dev.xyat.kineticcore.api.client.text.KineticText;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;
import dev.xyat.kineticcore.api.client.widget.KineticControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StructureRuleEditScreen extends KineticScreen {
    private final WorldGenScreen parent;
    private final StructureRuleDescriptor descriptor;
    private final List<FieldRow> rows = new ArrayList<>();

    private boolean disabled;
    private String spreadType;
    private StateButton disabledButton;
    private StateButton spreadTypeButton;

    private KineticEditBox weightBox;
    private KineticEditBox frequencyBox;
    private KineticEditBox saltBox;
    private KineticEditBox spacingBox;
    private KineticEditBox separationBox;
    private KineticEditBox distanceBox;
    private KineticEditBox spreadBox;
    private KineticEditBox countBox;

    public StructureRuleEditScreen(WorldGenScreen parent, StructureRuleDescriptor descriptor) {
        super(Component.translatable("gui.realmcontrol.worldgen.structure_edit.title"));
        this.parent = parent;
        setParentScreen(parent);
        this.descriptor = descriptor;
        useCanvas(520f, 360f, 6);

        StructureEntryRule entryRule = parent.getEntryRule(descriptor.structureId());
        StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : parent.getPlacementRule(descriptor.structureSetId());
        this.disabled = entryRule != null && entryRule.disabled();
        this.spreadType = placementRule != null && placementRule.spreadType() != null
                ? placementRule.spreadType()
                : descriptor.originalSpreadType();
    }

    @Override
    protected void buildUi() {
        rows.clear();
        int fieldX = 230;
        int fieldW = 100;
        int rowY = 84;
        int rowGap = 26;

        disabledButton = addButtonWithHandler(360, 48, 125, disabledMessage(), Component.translatable("gui.realmcontrol.worldgen.structure_edit.disabled.tooltip"), button -> {
                    disabled = !disabled;
                    button.setText(disabledMessage());
                });
StructureEntryRule entryRule = parent.getEntryRule(descriptor.structureId());
        int currentWeight = entryRule != null && entryRule.weight() != null ? entryRule.weight() : descriptor.originalWeight();
        weightBox = addField("gui.realmcontrol.worldgen.structure_edit.weight", fieldX, rowY, fieldW, Integer.toString(currentWeight), Integer.toString(descriptor.originalWeight()));
        rowY += rowGap;

        if (descriptor.supportsPlacementEditing()) {
            StructurePlacementRule rule = parent.getPlacementRule(descriptor.structureSetId());
            float currentFrequency = rule != null && rule.frequency() != null ? rule.frequency() : descriptor.originalFrequency();
            int currentSalt = rule != null && rule.salt() != null ? rule.salt() : descriptor.originalSalt();
            frequencyBox = addField("gui.realmcontrol.worldgen.structure_edit.frequency", fieldX, rowY, fieldW, formatFloat(currentFrequency), formatFloat(descriptor.originalFrequency()));
            rowY += rowGap;
            saltBox = addField("gui.realmcontrol.worldgen.structure_edit.salt", fieldX, rowY, fieldW, Integer.toString(currentSalt), Integer.toString(descriptor.originalSalt()));
            rowY += rowGap;

            if ("random_spread".equals(descriptor.placementType())) {
                int spacing = rule != null && rule.spacing() != null ? rule.spacing() : descriptor.originalSpacing();
                int separation = rule != null && rule.separation() != null ? rule.separation() : descriptor.originalSeparation();
                spacingBox = addField("gui.realmcontrol.worldgen.structure_edit.spacing", fieldX, rowY, fieldW, Integer.toString(spacing), Integer.toString(descriptor.originalSpacing()));
                rowY += rowGap;
                separationBox = addField("gui.realmcontrol.worldgen.structure_edit.separation", fieldX, rowY, fieldW, Integer.toString(separation), Integer.toString(descriptor.originalSeparation()));
                rowY += rowGap;

                spreadTypeButton = addButtonWithHandler(fieldX, rowY, fieldW, spreadTypeMessage(), Component.translatable("gui.realmcontrol.worldgen.structure_edit.spread_type.tooltip"), button -> {
                            spreadType = "triangular".equals(spreadType) ? "linear" : "triangular";
                            button.setText(spreadTypeMessage());
                        });
rows.add(new FieldRow("gui.realmcontrol.worldgen.structure_edit.spread_type", null, descriptor.originalSpreadType(), rowY));
            } else if ("concentric_rings".equals(descriptor.placementType())) {
                int distance = rule != null && rule.distance() != null ? rule.distance() : descriptor.originalDistance();
                int spread = rule != null && rule.spread() != null ? rule.spread() : descriptor.originalSpread();
                int count = rule != null && rule.count() != null ? rule.count() : descriptor.originalCount();
                distanceBox = addField("gui.realmcontrol.worldgen.structure_edit.distance", fieldX, rowY, fieldW, Integer.toString(distance), Integer.toString(descriptor.originalDistance()));
                rowY += rowGap;
                spreadBox = addField("gui.realmcontrol.worldgen.structure_edit.spread", fieldX, rowY, fieldW, Integer.toString(spread), Integer.toString(descriptor.originalSpread()));
                rowY += rowGap;
                countBox = addField("gui.realmcontrol.worldgen.structure_edit.count", fieldX, rowY, fieldW, Integer.toString(count), Integer.toString(descriptor.originalCount()));
            }
        }

        boolean assigned = !descriptor.structureSetId().isBlank();
        setControlEnabled(disabledButton, assigned);
        if (weightBox != null) weightBox.setTextEditable(assigned);

        int buttonY = this.canvasHeight() - 34;
        addButton(158, buttonY, 100, Component.translatable("gui.realmcontrol.worldgen.structure_edit.save_current"), null, () -> saveCurrent());
        addButton(263, buttonY, 100, Component.translatable("gui.realmcontrol.worldgen.structure_edit.reset_default"), Component.translatable("gui.realmcontrol.worldgen.structure_edit.reset_default.tooltip"), () -> resetDefault());
        addButton(368, buttonY, 72, Component.translatable("gui.realmcontrol.worldgen.config.back"), null, () -> back());
    }

    private KineticEditBox addField(String labelKey, int x, int y, int width, String value, String original) {
        KineticEditBox box = addTextField(x, y, width, Component.translatable(labelKey));
        box.setMaxLength(32);
        box.setValue(value);
rows.add(new FieldRow(labelKey, box, original, y));
        return box;
    }

    private void saveCurrent() {
        try {
            int weight = parsePositiveInt(weightBox);
            Integer weightOverride = weight == descriptor.originalWeight() ? null : weight;
            StructureEntryRule entryRule = new StructureEntryRule(descriptor.structureId(), disabled, weightOverride);

            StructurePlacementRule placementRule = null;
            if (descriptor.supportsPlacementEditing()) {
                float frequency = parseFrequency(frequencyBox);
                int salt = parseInt(saltBox);
                Float frequencyOverride = nearlyEqual(frequency, descriptor.originalFrequency()) ? null : frequency;
                Integer saltOverride = salt == descriptor.originalSalt() ? null : salt;

                Integer spacingOverride = null;
                Integer separationOverride = null;
                String spreadTypeOverride = null;
                Integer distanceOverride = null;
                Integer spreadOverride = null;
                Integer countOverride = null;

                if ("random_spread".equals(descriptor.placementType())) {
                    int spacing = parsePositiveInt(spacingBox);
                    int separation = parseNonNegativeInt(separationBox);
                    if (separation >= spacing) throw new IllegalArgumentException();
                    spacingOverride = spacing == descriptor.originalSpacing() ? null : spacing;
                    separationOverride = separation == descriptor.originalSeparation() ? null : separation;
                    spreadTypeOverride = descriptor.originalSpreadType().equals(spreadType) ? null : spreadType;
                } else if ("concentric_rings".equals(descriptor.placementType())) {
                    int distance = parsePositiveInt(distanceBox);
                    int spread = parsePositiveInt(spreadBox);
                    int count = parsePositiveInt(countBox);
                    distanceOverride = distance == descriptor.originalDistance() ? null : distance;
                    spreadOverride = spread == descriptor.originalSpread() ? null : spread;
                    countOverride = count == descriptor.originalCount() ? null : count;
                }

                placementRule = new StructurePlacementRule(
                        descriptor.structureSetId(),
                        frequencyOverride,
                        saltOverride,
                        spacingOverride,
                        separationOverride,
                        spreadTypeOverride,
                        distanceOverride,
                        spreadOverride,
                        countOverride
                );
            }

            parent.saveLocalStructureRules(descriptor, entryRule, placementRule);
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.structure_edit.staged_toast"));
            back();
        } catch (RuntimeException ignored) {
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.structure_edit.invalid_toast"));
        }
    }

    private void resetDefault() {
        parent.resetStructureRule(descriptor);
        KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.structure_edit.reset_toast"));
        back();
    }

    private void back() {
        if (this.minecraft != null) this.navigateBack();
    }


    @Override
    protected void renderCanvasBackground(@NotNull GuiGraphics g, int mx, int my, float pt) {
        GuiTheme.shadow(g, this.canvasWidth(), this.canvasHeight());
        GuiTheme.panel(g, 10, 8, this.canvasWidth() - 20, this.canvasHeight() - 16);
        GuiTheme.panelAlt(g, 20, 76, this.canvasWidth() - 40, this.canvasHeight() - 124);
    }

    @Override
    protected void renderCanvasForeground(@NotNull GuiGraphics g, int mx, int my, float pt) {
        Component title = Component.translatable("gui.realmcontrol.worldgen.structure_edit.title");
        KineticText.drawScrollingLeft(g, this.font, title, 26, 20, Math.max(1, canvasWidth() - 52), 0xFFFFAA00, false);

        Component structureLine = Component.translatable(
                "gui.realmcontrol.worldgen.structure_edit.structure",
                Component.literal(parent.toDisplayEntry(descriptor.structureId())).withStyle(ChatFormatting.GOLD)
        );
        KineticText.drawScrollingLeft(g, this.font, structureLine, 26, 36, Math.max(1, canvasWidth() - 52), 0xFFFFFFFF, false);

        Component setValue = descriptor.structureSetId().isBlank()
                ? Component.translatable("gui.realmcontrol.worldgen.structure_edit.unassigned").withStyle(ChatFormatting.RED)
                : Component.literal(descriptor.structureSetId()).withStyle(ChatFormatting.AQUA);
        KineticText.drawScrollingLeft(g, this.font, Component.translatable("gui.realmcontrol.worldgen.structure_edit.structure_set", setValue), 26, 52, Math.max(1, canvasWidth() - 52), 0xFFFFFFFF, false);

        if (descriptor.supportsPlacementEditing()) {
            KineticText.drawScrollingLeft(g, this.font, Component.translatable("gui.realmcontrol.worldgen.structure_edit.shared_notice"), 26, 68, Math.max(1, canvasWidth() - 52), 0xFFFFCC55, false);
        } else if ("other".equals(descriptor.placementType())) {
            KineticText.drawScrollingLeft(g, this.font, Component.translatable("gui.realmcontrol.worldgen.structure_edit.custom_placement_notice"), 26, 68, Math.max(1, canvasWidth() - 52), 0xFFFFCC55, false);
        } else {
            KineticText.drawScrollingLeft(g, this.font, Component.translatable("gui.realmcontrol.worldgen.structure_edit.unassigned_notice"), 26, 68, Math.max(1, canvasWidth() - 52), 0xFFFF5555, false);
        }

        for (FieldRow row : rows) {
            int y = row.y() + 5;
            KineticText.drawScrollingLeft(
                    g,
                    this.font,
                    Component.translatable(row.labelKey()),
                    32,
                    y,
                    190,
                    0xFFFFFFFF,
                    false
            );
            Component original = Component.translatable(
                    "gui.realmcontrol.worldgen.structure_edit.original",
                    Component.literal(row.original()).withStyle(ChatFormatting.AQUA)
            );
            KineticText.drawScrollingLeft(
                    g,
                    this.font,
                    original,
                    340,
                    y,
                    Math.max(1, canvasWidth() - 366),
                    0xFFCCCCCC,
                    false
            );
        }
    }

    private Component disabledMessage() {
        return Component.translatable(
                disabled ? "gui.realmcontrol.worldgen.structure_edit.disabled" : "gui.realmcontrol.worldgen.structure_edit.enabled"
        ).withStyle(disabled ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    private Component spreadTypeMessage() {
        String type = "triangular".equals(spreadType) ? "triangular" : "linear";
        return Component.translatable("gui.realmcontrol.worldgen.structure_edit.spread_type." + type);
    }

    private int parseInt(KineticEditBox box) {
        return Integer.parseInt(box.getValue().trim());
    }

    private int parsePositiveInt(KineticEditBox box) {
        int value = parseInt(box);
        if (value <= 0) throw new IllegalArgumentException();
        return value;
    }

    private int parseNonNegativeInt(KineticEditBox box) {
        int value = parseInt(box);
        if (value < 0) throw new IllegalArgumentException();
        return value;
    }

    private float parseFrequency(KineticEditBox box) {
        float value = Float.parseFloat(box.getValue().trim());
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) throw new IllegalArgumentException();
        return value;
    }

    private boolean nearlyEqual(float a, float b) {
        return Math.abs(a - b) < 0.000001F;
    }

    private String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private record FieldRow(String labelKey, KineticEditBox box, String original, int y) {
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
