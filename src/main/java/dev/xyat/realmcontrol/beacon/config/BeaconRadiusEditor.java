package dev.xyat.realmcontrol.beacon.config;

import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.text.KineticI18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Four fixed beacon levels; applying replaces only the config page's draft. */
public final class BeaconRadiusEditor extends KineticPage {
    private static final int PANEL_W = 480, PANEL_H = 132, FIELD_X = 140, FIELD_W = 80, ROW_Y = 42, ROW_PITCH = 22;
    private final List<String> initial;
    private final Consumer<List<String>> apply;
    private final String[] radiusText = new String[4];
    private final KineticNumberField[] radiusFields = new KineticNumberField[4];
    private int panelX, panelY;

    private BeaconRadiusEditor(Component title, List<String> initial, Consumer<List<String>> apply) {
        super(title);
        this.initial = List.copyOf(initial);
        this.apply = apply;
        // Older short lists repeat their final radius; an empty list disables every level.
        for (int level = 0; level < radiusText.length; level++) {
            radiusText[level] = initial.isEmpty() ? "-1" : initial.get(Math.min(level, initial.size() - 1));
        }
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
    }

    public static void open(Component title, List<String> initial, Consumer<List<String>> apply) {
        KineticGui.openChild(new BeaconRadiusEditor(title, initial, apply));
    }

    private static Component tr(String key, Object... args) {
        return KineticI18n.translatable("gui.realmcontrol.beacon.radii." + key, args);
    }

    @Override protected void build(KineticUi ui) {
        // Match the existing compact biome editor while retaining the shared 640 x 360 canvas.
        panelX = (width() - PANEL_W) / 2;
        panelY = (height() - PANEL_H) / 2;
        ui.button(panelX + 4, panelY + 4, 40)
                .text(KineticI18n.translatable("gui.realmcontrol.worldblock.config.back")).onClick(this::close).build();
        ui.button(panelX + PANEL_W - 44, panelY + 4, 40)
                .text(KineticI18n.translatable("gui.realmcontrol.beacon.rules.apply")).onClick(this::applyAndClose).build();
        for (int level = 0; level < radiusFields.length; level++) {
            final int index = level;
            radiusFields[level] = ui.numberField(panelX + FIELD_X, panelY + ROW_Y + level * ROW_PITCH, FIELD_W, NumberType.INT)
                    .label(tr("level", level + 1)).range(-1, 16).defaultValue(level)
                    .tooltip(tr("range")).onChange(value -> radiusText[index] = value).build();
            radiusFields[level].setTextValue(radiusText[level]);
        }
    }

    private void applyAndClose() {
        var draft = new ArrayList<String>();
        boolean valid = true;
        for (var field : radiusFields) {
            Integer radius = field.getIntValue();
            if (radius == null) {
                field.flashValidationError();
                valid = false;
            } else draft.add(Integer.toString(radius));
        }
        if (!valid) return;
        // Keep legacy extra entries intact; they are not additional beacon levels or editable slots.
        if (initial.size() > 4) draft.addAll(initial.subList(4, initial.size()));
        apply.accept(draft);
        close();
    }

    @Override protected void renderBackground(KineticGraphics g, int mouseX, int mouseY, float partialTick) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, panelX, panelY, PANEL_W, PANEL_H);
        g.scrollingTextCentered(title(), panelX + PANEL_W / 2, panelY + 8, PANEL_W - 2 * 48, KineticTheme.current().text(), false);
        KineticTheme.separator(g, panelX + 4, panelY + 24, PANEL_W - 8);
        g.scrollingText(tr("radius"), panelX + FIELD_X, panelY + 29, FIELD_W, KineticTheme.current().mutedText(), false);
        g.scrollingText(tr("coverage"), panelX + 234, panelY + 29, PANEL_W - 246, KineticTheme.current().mutedText(), false);
        for (int level = 0; level < radiusFields.length; level++) {
            int y = panelY + ROW_Y + 4 + level * ROW_PITCH;
            g.scrollingText(tr("level", level + 1), panelX + 16, y, FIELD_X - 28, KineticTheme.current().text(), false);
            Integer radius = radiusFields[level].getIntValue();
            Component coverage = radius == null ? tr("invalid") : radius < 0 ? tr("disabled")
                    : tr("chunks", radius * 2 + 1, radius * 2 + 1, (radius * 2 + 1) * (radius * 2 + 1));
            g.scrollingText(coverage, panelX + 234, y, PANEL_W - 246, KineticTheme.current().text(), false);
        }
    }
}
