package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How a draft is saved: its name, how deep it sits in the ground, what happens to air and which blocks are left
 * out. Where it was scanned from and its size are shown read-only; Rescan on the first page reads it again.
 */
public final class ProjectSettingsPage extends ProjectPage {
    private static final int LABEL_X = 12, FIELD_X = 140, FIELD_W = 180, ROW_GAP = 22, PREVIEW_X = 336;
    private KineticTextField nameField;
    private KineticNumberField embedField;
    private KineticDropdown airDropdown;
    private KineticTextField ignoreField;

    public ProjectSettingsPage(StructureProject project) {
        super("gui.realmcontrol.structure.settings.title", project);
    }

    @Override
    protected int previewX() {
        return PREVIEW_X;
    }

    @Override
    protected void buildFields(KineticUi ui) {
        int y = TOP + 4 * 12 + 6;
        nameField = ui().textField(FIELD_X, y, FIELD_W).build();
        nameField.limitTextLength(64);
        nameField.setTextValue(project.name);
        nameField.setDefaultText(project.name);
        y += ROW_GAP;
        embedField = ui().numberField(FIELD_X, y, FIELD_W, NumberType.INT).range(0, Math.max(0, project.size[1] - 1)).value(project.embed).defaultValue(project.embed).build();
        embedField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.settings.embed.tooltip"));
        y += ROW_GAP;
        List<KineticDropdown.Option> airOptions = new ArrayList<>();
        for (String mode : List.of("auto", "keep", "none")) {
            airOptions.add(new KineticDropdown.Option(mode, KineticI18n.translatable("gui.realmcontrol.structure.settings.air." + mode),
                    KineticI18n.translatable("gui.realmcontrol.structure.settings.air." + mode + ".tooltip")));
        }
        airDropdown = ui().dropdown(FIELD_X, y, FIELD_W, airOptions).selected(project.air).build();
        y += ROW_GAP;
        ignoreField = ui().textField(FIELD_X, y, FIELD_W).build();
        ignoreField.limitTextLength(2048);
        ignoreField.setTextValue(String.join(", ", project.ignoreBlocks));
        ignoreField.setDefaultText(String.join(", ", project.ignoreBlocks));
        ignoreField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.settings.ignore.tooltip"));
    }

    @Override
    protected void save() {
        project.name = nameField.textValue().isBlank() ? project.id : nameField.textValue().trim();
        Integer embed = embedField.getIntValue();
        if (embed == null) {
            embedField.flashValidationError();
            return;
        }
        project.embed = embed;
        project.air = airDropdown.selectedValue();
        List<String> ignore = new ArrayList<>();
        Arrays.stream(ignoreField.textValue().split("[,，\\s]+")).map(String::trim).filter(id -> !id.isEmpty()).forEach(ignore::add);
        project.ignoreBlocks = ignore;
        StructureEditorNetwork.save(project, null);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        super.renderForeground(g, mx, my, pt);
        int w = PREVIEW_X - 8 - LABEL_X;
        int y = TOP;
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.settings.id", project.id), LABEL_X, y, w, 0xFFFFFFFF, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.facts.origin", GenerationPage.dimensionName(project.dimension),
                project.origin[0], project.origin[1], project.origin[2]), LABEL_X, y + 12, w, 0xFFCCCCCC, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.facts.size", project.size[0], project.size[1], project.size[2],
                KineticI18n.translatable("gui.realmcontrol.structure.tier." + project.tier())), LABEL_X, y + 24, w, 0xFFCCCCCC, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.settings.scanned", project.createdVersion, project.tilesX() * project.tilesZ(),
                project.tileSize), LABEL_X, y + 36, w, 0xFFCCCCCC, false);
        int rowY = TOP + 4 * 12 + 6 + 4, labelW = FIELD_X - 8 - LABEL_X;
        for (String key : List.of("name", "embed", "air", "ignore")) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.settings." + key), LABEL_X, rowY, labelW, 0xFFFFFFFF, false);
            rowY += ROW_GAP;
        }
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.settings.help"), LABEL_X, rowY + 6, w, 0xFF9AA4B2);
    }
}
