package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.selector.KineticSelectors;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.project.Markers;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * What custom spawners spawn: mobs with spawn weights on the left (picked in the entity selector), vanilla's spawner
 * timings on the right. Apply sets every selected spawner of the markers page to these values.
 */
public final class SpawnerPage extends KineticPage {
    private static final int LIST_X = 4, LIST_Y = 46, LIST_W = 300, ROW_HEIGHT = 22;
    private static final int LABEL_X = LIST_X + LIST_W + 12, FIELD_X = LABEL_X + 140, FIELD_W = 64, ROW_STEP = 22;
    private static final String[] NUMBERS = {"min_delay", "max_delay", "count", "max_nearby", "player_range", "spawn_range"};
    private final MarkersPage parent;
    private final List<Markers.Spawner> targets;
    private final List<Markers.Mob> mobs;
    private final int[] values;
    private int current;
    private MobList list;
    private KineticNumberField weightField;
    private KineticButton removeButton;
    private final KineticNumberField[] fields = new KineticNumberField[NUMBERS.length];

    public SpawnerPage(MarkersPage parent, List<Markers.Spawner> targets) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.spawner.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.parent = parent;
        this.targets = targets;
        Markers.Spawner first = targets.get(0);
        this.mobs = new ArrayList<>(first.mobs);
        if (mobs.isEmpty() && !first.entity.isBlank()) mobs.add(new Markers.Mob(first.entity, 1));
        this.values = new int[]{first.minDelay, first.maxDelay, first.count, first.maxNearby, first.playerRange, first.spawnRange};
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::navigateBack).build();
        ui().button(width() - 4 - 48, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.structure.biomes.apply")).onClick(this::apply).build();
        int addW = 72, removeW = 48;
        ui().button(LIST_X, 28, addW).text(KineticI18n.translatable("gui.realmcontrol.structure.spawner.choose"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.spawner.choose.tooltip"))
                .onClick(this::chooseMobs).build();
        removeButton = ui().button(LIST_X + addW + 4, 28, removeW).text(KineticI18n.translatable("gui.realmcontrol.structure.spawner.remove"))
                .onClick(() -> {
                    if (current >= 0 && current < mobs.size()) mobs.remove(current);
                    current = Math.min(current, mobs.size() - 1);
                    refresh();
                }).build();
        int weightX = LIST_X + addW + 4 + removeW + 8 + 44;
        weightField = ui().numberField(weightX, 28, LIST_X + LIST_W - weightX, NumberType.INT).range(1, 1000).value(1).onChange(text -> {
            Integer weight = weightField == null ? null : weightField.getIntValue();
            if (weight != null && current >= 0 && current < mobs.size()) mobs.set(current, new Markers.Mob(mobs.get(current).id(), weight));
        }).build();
        weightField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.spawner.weight.tooltip"));
        list = ui.add(new MobList(LIST_X, LIST_Y + 2, LIST_W, height() - LIST_Y - 6));
        int y = LIST_Y;
        int[] max = {32767, 32767, 64, 256, 128, 64};
        for (int i = 0; i < NUMBERS.length; i++) {
            fields[i] = ui().numberField(FIELD_X, y, FIELD_W, NumberType.INT).range(i < 2 ? 0 : 1, max[i]).value(values[i]).defaultValue(values[i]).build();
            fields[i].setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.spawner." + NUMBERS[i] + ".tooltip"));
            y += ROW_STEP;
        }
        refresh();
    }

    private void refresh() {
        list.setItems(mobs);
        removeButton.setEnabled(current >= 0 && current < mobs.size());
        weightField.setEnabled(current >= 0 && current < mobs.size());
        if (current >= 0 && current < mobs.size()) weightField.setIntValue(mobs.get(current).weight());
    }

    /** The entity selector with the current mobs ticked; new mobs come in with weight 1. */
    private void chooseMobs() {
        List<String> ids = mobs.stream().map(Markers.Mob::id).toList();
        KineticSelectors.openEntitySelector(KineticI18n.translatable("gui.realmcontrol.structure.spawner.choose"), ids, chosen -> {
            List<Markers.Mob> next = new ArrayList<>();
            for (String id : chosen) {
                Markers.Mob old = mobs.stream().filter(mob -> mob.id().equals(id)).findFirst().orElse(null);
                next.add(old != null ? old : new Markers.Mob(id, 1));
            }
            mobs.clear();
            mobs.addAll(next);
            current = mobs.isEmpty() ? -1 : 0;
            if (list != null) refresh();
        });
    }

    private void apply() {
        for (int i = 0; i < fields.length; i++) {
            Integer value = fields[i].getIntValue();
            if (value == null) {
                fields[i].flashValidationError();
                return;
            }
            values[i] = value;
        }
        if (values[1] < values[0]) {
            fields[1].flashValidationError();
            return;
        }
        for (Markers.Spawner spawner : targets) {
            spawner.mode = mobs.isEmpty() ? "keep" : "custom";
            spawner.mobs = new ArrayList<>(mobs);
            spawner.minDelay = values[0];
            spawner.maxDelay = values[1];
            spawner.count = values[2];
            spawner.maxNearby = values[3];
            spawner.playerRange = values[4];
            spawner.spawnRange = values[5];
        }
        parent.changed();
        navigateBack();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
        KineticTheme.surface(g, LIST_X, LIST_Y, LIST_W, height() - LIST_Y - 4, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - 48 - 4 - width() / 2));
        Component title = targets.size() > 1 ? KineticI18n.translatable("gui.realmcontrol.structure.spawner.title_batch", targets.size()) : title();
        g.scrollingTextCentered(title, width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        int weightLabelX = LIST_X + 72 + 4 + 48 + 8;
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.spawner.weight"), weightLabelX, 32, 40, 0xFFFFFFFF, false);
        int labelW = FIELD_X - 8 - LABEL_X, y = LIST_Y + 4;
        for (String key : NUMBERS) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.spawner." + key), LABEL_X, y, labelW, 0xFFFFFFFF, false);
            y += ROW_STEP;
        }
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.spawner.help"), LABEL_X, y + 6, width() - 8 - LABEL_X, 0xFF9AA4B2);
    }

    private final class MobList extends KineticRowList<Markers.Mob> {
        MobList(int x, int y, int width, int height) {
            super(x, y, width, height, ROW_HEIGHT);
        }

        @Override
        protected void renderRow(KineticGraphics g, Markers.Mob mob, int index, int x, int y, int width, int height, boolean hovered, boolean selected) {
            if (index == current) KineticTheme.stateOutline(g, x, y, width, height, true, false, false);
            else if (hovered) KineticTheme.stateOutline(g, x, y, width, height, false, true, false);
            int textY = y + (height - g.lineHeight()) / 2 + 1, weightW = 64;
            g.scrollingText(Component.literal(MarkersPage.entityName(mob.id()).getString() + "  ").append(Component.literal(mob.id())
                    .withStyle(style -> style.withColor(0x9AA4B2))), x + 4, textY, width - 12 - weightW, 0xFFFFFFFF, false);
            g.scrollingTextRight(KineticI18n.translatable("gui.realmcontrol.structure.spawner.weight_value", mob.weight()), x + width - 4, textY, weightW, 0xFFCCCCCC, false);
        }

        @Override
        protected boolean onRowClick(Markers.Mob mob, int index, MouseInput input) {
            if (!input.isLeft()) return false;
            current = index;
            refresh();
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable("gui.realmcontrol.structure.spawner.empty");
        }
    }
}
