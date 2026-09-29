package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.*;
import dev.xyat.kineticcore.api.client.gui.widget.list.*;

import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StructureRuleEditPage extends KineticPage {
    private final WorldGenPage parent;
    private final StructureRuleDescriptor descriptor;
    private final List<FieldRow> rows = new ArrayList<>();

    private boolean disabled;
    private String spreadType;
    private KineticButton spreadTypeButton;

    private KineticTextField weightBox;
    private KineticTextField frequencyBox;
    private KineticTextField saltBox;
    private KineticTextField spacingBox;
    private KineticTextField separationBox;
    private KineticTextField distanceBox;
    private KineticTextField spreadBox;
    private KineticTextField countBox;

    public StructureRuleEditPage(WorldGenPage parent, StructureRuleDescriptor descriptor) {
        super(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.title"));
        this.parent = parent;
        this.descriptor = descriptor;
        useCanvas(520, 360, 6);

        StructureEntryRule entryRule = parent.getEntryRule(descriptor.structureId());
        StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : parent.getPlacementRule(descriptor.structureSetId());
        this.disabled = entryRule != null && entryRule.disabled();
        this.spreadType = placementRule != null && placementRule.spreadType() != null
                ? placementRule.spreadType()
                : descriptor.originalSpreadType();
    }

    @Override
    protected void build(KineticUi ui) {
        rows.clear();
        int fieldX = 230;
        int fieldW = 100;
        int rowY = 84;
        int rowGap = 26;

        KineticButton disabledButton = ui().button(360, 48, 125).text(disabledMessage()).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.disabled.tooltip")).onClick(button -> {
            disabled = !disabled;
            button.setText(disabledMessage());
        }).build();
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

                spreadTypeButton = ui().button(fieldX, rowY, fieldW).text(spreadTypeMessage()).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.spread_type.tooltip")).onClick(button -> {
                            spreadType = "triangular".equals(spreadType) ? "linear" : "triangular";
                            button.setText(spreadTypeMessage());
                        }).build();
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

        int buttonY = this.height() - 34;
        ui().button(158, buttonY, 100).text(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.save_current")).onClick(this::saveCurrent).build();
        ui().button(263, buttonY, 100).text(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.reset_default")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.reset_default.tooltip")).onClick(this::resetDefault).build();
        ui().button(368, buttonY, 72).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::back).build();
    }

    private KineticTextField addField(String labelKey, int x, int y, int width, String value, String original) {
        KineticTextField box = ui().textField(x, y, width).label(KineticI18n.translatable(labelKey)).build();
        box.limitTextLength(32);
        box.setTextValue(value);
        box.setDefaultText(value);
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
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.staged_toast"));
        } catch (RuntimeException ignored) {
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.invalid_toast"));
        }
    }

    private void resetDefault() {
        parent.resetStructureRule(descriptor);
        KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.reset_toast"));
        back();
    }

    private void back() {
        if (isAttached()) this.navigateBack();
    }


    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, this.width(), this.height());
        KineticTheme.panel(g, 10, 8, this.width() - 20, this.height() - 16);
        KineticTheme.panelAlt(g, 20, 76, this.width() - 40, this.height() - 124);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        Component title = KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.title");
        g.scrollingText(title, 26, 20, Math.max(1, width() - 52), 0xFFFFAA00, false);

        Component structureLine = KineticI18n.translatable(
                "gui.realmcontrol.worldgen.structure_edit.structure",
                Component.literal(parent.toDisplayEntry(descriptor.structureId()))
        );
        g.scrollingText(structureLine, 26, 36, Math.max(1, width() - 52), 0xFFFFFFFF, false);

        Component setValue = descriptor.structureSetId().isBlank()
                ? KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.unassigned")
                : Component.literal(descriptor.structureSetId());
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.structure_set", setValue), 26, 52, Math.max(1, width() - 52), 0xFFFFFFFF, false);

        if (descriptor.supportsPlacementEditing()) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.shared_notice"), 26, 68, Math.max(1, width() - 52), 0xFFFFCC55, false);
        } else if ("other".equals(descriptor.placementType())) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.custom_placement_notice"), 26, 68, Math.max(1, width() - 52), 0xFFFFCC55, false);
        } else {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.unassigned_notice"), 26, 68, Math.max(1, width() - 52), 0xFFFF5555, false);
        }

        for (FieldRow row : rows) {
            int y = row.y() + 5;
            g.scrollingText(KineticI18n.translatable(row.labelKey()), 32, y, 190, 0xFFFFFFFF, false);
            Component original = KineticI18n.translatable(
                    "gui.realmcontrol.worldgen.structure_edit.original",
                    Component.literal(row.original())
            );
            g.scrollingText(original, 340, y, Math.max(1, width() - 366), 0xFFCCCCCC, false);
        }
    }

    private Component disabledMessage() {
        return KineticI18n.translatable(
                disabled ? "gui.realmcontrol.worldgen.structure_edit.disabled" : "gui.realmcontrol.worldgen.structure_edit.enabled"
        );
    }

    private Component spreadTypeMessage() {
        String type = "triangular".equals(spreadType) ? "triangular" : "linear";
        return KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.spread_type." + type);
    }

    private int parseInt(KineticTextField box) {
        return Integer.parseInt(box.textValue().trim());
    }

    private int parsePositiveInt(KineticTextField box) {
        int value = parseInt(box);
        if (value <= 0) throw new IllegalArgumentException();
        return value;
    }

    private int parseNonNegativeInt(KineticTextField box) {
        int value = parseInt(box);
        if (value < 0) throw new IllegalArgumentException();
        return value;
    }

    private float parseFrequency(KineticTextField box) {
        float value = Float.parseFloat(box.textValue().trim());
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) throw new IllegalArgumentException();
        return value;
    }

    private boolean nearlyEqual(float a, float b) {
        return Math.abs(a - b) < 0.000001F;
    }

    private String formatFloat(float value) {
        return String.format(Locale.ROOT, "%.6f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public KineticButton getSpreadTypeButton() {
        return spreadTypeButton;
    }

    private record FieldRow(String labelKey, KineticTextField box, String original, int y) {
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
