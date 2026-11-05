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
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
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
        useCanvas(640, 360, 4);

        StructureEntryRule entryRule = parent.getEntryRule(descriptor.structureId());
        StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : parent.getPlacementRule(descriptor.structureSetId());
        this.disabled = entryRule != null && entryRule.disabled();
        this.spreadType = placementRule != null && placementRule.spreadType() != null
                ? placementRule.spreadType()
                : descriptor.originalSpreadType();
    }

    private static final int SAVE_X = 636 - 48, RESET_X = SAVE_X - 4 - 56, ENABLE_X = RESET_X - 4 - 56;
    private static final int ROWS_Y = 72, FIELD_X = 200, ORIGINAL_X = FIELD_X + 108;
    /** The structure's preview fills the right of the field area, its facts underneath. */
    private static final int PREVIEW_X = 424, PREVIEW_RIGHT = 632, FACT_LINES = 3, FACT_LINE_H = 12;

    private KineticStructureView previewView;
    private StructurePreviewClient.Result previewResult;
    private StructurePreviewClient.Listener previewListener;

    @Override
    protected void build(KineticUi ui) {
        rows.clear();
        previewResult = null;
        previewView = ui.add(new KineticStructureView(PREVIEW_X, ROWS_Y, PREVIEW_RIGHT - PREVIEW_X, factsY() - 4 - ROWS_Y));
        previewView.setLoading(0F);
        previewListener = new StructurePreviewClient.Listener() {
            @Override
            public void progress(float share) {
                if (previewView != null && previewView.snapshot() == null) previewView.setLoading(share);
            }

            @Override
            public void finished(StructurePreviewClient.Result result) {
                previewResult = result;
                if (previewView != null) previewView.setSnapshot(result.snapshot());
            }

            @Override
            public void failed(Component reason) {
                if (previewView != null) previewView.setMessage(reason);
            }
        };
        StructurePreviewClient.request(descriptor.structureId(), parent.previewVariantOf(descriptor.structureId()), previewListener);
        // Header: Back, the title, then Enable, Reset and Save at the top right. Under it three short info lines and
        // the fields, one 22 px row each: label, value and the original value.
        int fieldX = FIELD_X;
        int fieldW = 100;
        int rowY = ROWS_Y;
        int rowGap = 22;

        KineticButton disabledButton = ui().button(ENABLE_X, 4, 56).text(disabledMessage()).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.disabled.tooltip")).onClick(button -> {
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

        ui().button(SAVE_X, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.save_current")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.shared_notice")).onClick(this::saveCurrent).build();
        ui().button(RESET_X, 4, 56).text(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.reset_default")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.reset_default.tooltip")).onClick(this::resetDefault).build();
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::back).build();
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

    private int factsY() {
        return this.height() - 6 - FACT_LINES * FACT_LINE_H;
    }

    @Override
    protected void onRemoved() {
        if (previewListener != null) StructurePreviewClient.cancel(previewListener);
        if (previewView != null) previewView.release();
        super.onRemoved();
    }


    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, this.width(), this.height());
        KineticTheme.panel(g, 0, 0, this.width(), this.height());
        // Header row like the other editors: Back at 4,4 and the title beside it, then the header divider.
        KineticTheme.separator(g, 4, 24, this.width() - 8);
        KineticTheme.surface(g, 4, ROWS_Y - 4, this.width() - 8, this.height() - ROWS_Y, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        Component title = KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.title");
        g.scrollingTextCentered(title, width() / 2, 8, Math.max(1, 2 * Math.min(width() / 2 - 48, ENABLE_X - 4 - width() / 2)), 0xFFFFAA00, false);

        Component structureLine = KineticI18n.translatable(
                "gui.realmcontrol.worldgen.structure_edit.structure",
                Component.literal(parent.toDisplayEntry(descriptor.structureId()))
        );
        g.scrollingText(structureLine, 8, 30, Math.max(1, width() - 16), 0xFFFFFFFF, false);

        Component setValue = descriptor.structureSetId().isBlank()
                ? KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.unassigned")
                : Component.literal(descriptor.structureSetId());
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.structure_set", setValue), 8, 42, Math.max(1, width() - 16), 0xFFFFFFFF, false);

        // Editable placements need no note (the shared-set warning is in the field tooltips); the others say why not.
        if (descriptor.supportsPlacementEditing()) {
            // nothing to add
        } else if ("other".equals(descriptor.placementType())) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.custom_placement_notice"), 8, 54, Math.max(1, width() - 16), 0xFFFFCC55, false);
        } else {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.structure_edit.unassigned_notice"), 8, 54, Math.max(1, width() - 16), 0xFFFF5555, false);
        }

        for (FieldRow row : rows) {
            int y = row.y() + 4;
            g.scrollingText(KineticI18n.translatable(row.labelKey()), 12, y, FIELD_X - 8 - 12, 0xFFFFFFFF, false);
            Component original = KineticI18n.translatable(
                    "gui.realmcontrol.worldgen.structure_edit.original",
                    Component.literal(row.original())
            );
            g.scrollingText(original, ORIGINAL_X, y, PREVIEW_X - 8 - ORIGINAL_X, 0xFFCCCCCC, false);
        }
        if (previewResult != null) {
            int w = PREVIEW_RIGHT - PREVIEW_X;
            WorldGenPage.drawPreviewFacts(g, previewResult, PREVIEW_X, factsY(), w, w);
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
