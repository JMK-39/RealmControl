package dev.xyat.realmcontrol.structure.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where and how often a draft generates: the dimension, the biomes, the vanilla random-spread values, the height and
 * the terrain adaptation, and a switch at the top to stop it generating. The right column estimates how common it
 * will be and which biomes it ends up in. The structure panel opens this page for a custom structure, so both edit
 * the same values; saving a published draft's rules writes them into its published files at once. A mega draft has
 * no vanilla terrain adaptation: it gets its foundation, the foundation block and whether other structures keep out
 * instead, and the right column says the least separation that keeps two of them apart.
 */
public final class GenerationPage extends ProjectPage {
    private static final int LABEL_X = 12, FIELD_X = 120, FIELD_W = 150, ROW_GAP = 22, INFO_X = 336, ENABLE_W = 56;
    private KineticDropdown dimensionDropdown, heightDropdown, terrainDropdown, foundationDropdown;
    private KineticNumberField spacingField, separationField, saltField, offsetField, exclusionChunksField;
    private KineticTextField frequencyField, exclusionField, foundationBlockField;
    private dev.xyat.kineticcore.api.client.gui.widget.KineticToggle suppressToggle;
    private KineticButton biomeButton;

    public GenerationPage(StructureProject project) {
        super("gui.realmcontrol.structure.generation.title", project);
    }

    @Override
    protected int previewX() {
        return Integer.MAX_VALUE;
    }

    @Override
    protected int headerRightWidth() {
        return SAVE_W + 4 + ENABLE_W;
    }

    StructureProject.Generation generation() {
        return project.generation;
    }

    @Override
    protected void buildFields(KineticUi ui) {
        if (EditorClient.biomeCatalog() == null) StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.BIOMES, "", "");
        StructureProject.Generation generation = project.generation;
        ui().button(width() - 4 - SAVE_W - 4 - ENABLE_W, 4, ENABLE_W).text(enabledText())
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.enabled.tooltip")).onClick(button -> {
                    generation.enabled = !generation.enabled;
                    button.setText(enabledText());
                }).build();
        int y = TOP;
        dimensionDropdown = ui().dropdown(FIELD_X, y, FIELD_W, dimensionOptions()).selected(generation.dimensions.isEmpty()
                ? "minecraft:overworld" : generation.dimensions.get(0)).onChange(value -> generation.dimensions = new ArrayList<>(List.of(value))).build();
        y += ROW_GAP;
        biomeButton = ui().button(FIELD_X, y, FIELD_W).text(biomeText()).tooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.biomes.tooltip"))
                .onClick(() -> openChild(new BiomePickerPage(this))).build();
        y += ROW_GAP;
        spacingField = ui().numberField(FIELD_X, y, FIELD_W, NumberType.INT).range(1, 4096).value(generation.spacing).defaultValue(generation.spacing).build();
        spacingField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.spacing.tooltip"));
        y += ROW_GAP;
        separationField = ui().numberField(FIELD_X, y, FIELD_W, NumberType.INT).range(0, 4095).value(generation.separation).defaultValue(generation.separation).build();
        separationField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.separation.tooltip"));
        y += ROW_GAP;
        frequencyField = ui().textField(FIELD_X, y, FIELD_W).build();
        frequencyField.setTextValue(format(generation.frequency));
        frequencyField.setDefaultText(format(generation.frequency));
        frequencyField.setValidator(value -> parseFrequency(value) != null);
        frequencyField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.frequency.tooltip"));
        y += ROW_GAP;
        int saltW = FIELD_W - 44;
        saltField = ui().numberField(FIELD_X, y, saltW, NumberType.INT).allowNegative(true).value(generation.salt).defaultValue(generation.salt).build();
        saltField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.salt.tooltip"));
        ui().button(FIELD_X + saltW + 4, y, 40).text(KineticI18n.translatable("gui.realmcontrol.structure.generation.random"))
                .onClick(() -> saltField.setIntValue(new java.util.Random().nextInt(1_000_000_000))).build();
        y += ROW_GAP;
        List<KineticDropdown.Option> heights = new ArrayList<>();
        for (String mode : List.of("surface", "absolute")) {
            heights.add(new KineticDropdown.Option(mode, KineticI18n.translatable("gui.realmcontrol.structure.generation.height." + mode),
                    KineticI18n.translatable("gui.realmcontrol.structure.generation.height." + mode + ".tooltip")));
        }
        int heightW = FIELD_W - 52;
        heightDropdown = ui().dropdown(FIELD_X, y, heightW, heights).selected(generation.height).build();
        offsetField = ui().numberField(FIELD_X + heightW + 4, y, 48, NumberType.INT).allowNegative(true).range(-4096, 4096).value(generation.offset).defaultValue(generation.offset).build();
        offsetField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.offset.tooltip"));
        y += ROW_GAP;
        if (mega()) {
            List<KineticDropdown.Option> foundations = new ArrayList<>();
            for (String mode : List.of("fill_clear", "fill", "none")) {
                foundations.add(new KineticDropdown.Option(mode, KineticI18n.translatable("gui.realmcontrol.structure.generation.foundation." + mode),
                        KineticI18n.translatable("gui.realmcontrol.structure.generation.foundation." + mode + ".tooltip")));
            }
            foundationDropdown = ui().dropdown(FIELD_X, y, FIELD_W, foundations).selected(generation.foundation).build();
            y += ROW_GAP;
            foundationBlockField = ui().textField(FIELD_X, y, FIELD_W).build();
            foundationBlockField.setTextValue(generation.foundationBlock);
            foundationBlockField.setDefaultText(generation.foundationBlock);
            foundationBlockField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.foundation_block.tooltip"));
            y += ROW_GAP;
            suppressToggle = ui().toggle(FIELD_X, y, 64).value(generation.suppressOthers)
                    .labels(KineticI18n.translatable("gui.realmcontrol.structure.generation.suppress.on"), KineticI18n.translatable("gui.realmcontrol.structure.generation.suppress.off"))
                    .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.suppress.tooltip")).build();
        } else {
            List<KineticDropdown.Option> terrains = new ArrayList<>();
            for (String mode : List.of("none", "bury", "beard_thin", "beard_box")) {
                terrains.add(new KineticDropdown.Option(mode, KineticI18n.translatable("gui.realmcontrol.structure.generation.terrain." + mode),
                        KineticI18n.translatable("gui.realmcontrol.structure.generation.terrain." + mode + ".tooltip")));
            }
            terrainDropdown = ui().dropdown(FIELD_X, y, FIELD_W, terrains).selected(generation.terrain).build();
        }
        y += ROW_GAP;
        int chunksW = 40;
        exclusionField = ui().textField(FIELD_X, y, FIELD_W - chunksW - 4).placeholder(KineticI18n.translatable("gui.realmcontrol.structure.generation.exclusion.placeholder")).build();
        exclusionField.setTextValue(generation.exclusionSet);
        exclusionField.setDefaultText(generation.exclusionSet);
        exclusionField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.generation.exclusion.tooltip"));
        exclusionChunksField = ui().numberField(FIELD_X + FIELD_W - chunksW, y, chunksW, NumberType.INT).range(1, 16).value(generation.exclusionChunks).defaultValue(generation.exclusionChunks).build();
    }

    private List<KineticDropdown.Option> dimensionOptions() {
        Set<String> dimensions = new TreeSet<>(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"));
        JsonObject catalog = EditorClient.biomeCatalog();
        if (catalog != null) dimensions.addAll(catalog.getAsJsonObject("dimensions").keySet());
        dimensions.addAll(project.generation.dimensions);
        List<KineticDropdown.Option> options = new ArrayList<>();
        for (String dimension : dimensions) options.add(new KineticDropdown.Option(dimension, dimensionName(dimension)));
        return options;
    }

    /** A dimension's name without the colours the structure panel gives it. */
    static Component dimensionName(String dimension) {
        Component name = switch (dimension) {
            case "minecraft:overworld" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.minecraft.overworld");
            case "minecraft:the_nether" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_nether");
            case "minecraft:the_end" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_end");
            default -> Component.literal(dimension);
        };
        return Component.literal(net.minecraft.ChatFormatting.stripFormatting(name.getString()));
    }

    private boolean mega() {
        return "mega".equals(project.tier());
    }

    /** The labels of the rows, top to bottom. */
    private List<String> rows() {
        return mega() ? List.of("dimension", "biomes", "spacing", "separation", "frequency", "salt", "height", "foundation", "foundation_block", "suppress", "exclusion")
                : List.of("dimension", "biomes", "spacing", "separation", "frequency", "salt", "height", "terrain", "exclusion");
    }

    private Component enabledText() {
        return KineticI18n.translatable(project.generation.enabled ? "gui.realmcontrol.worldgen.structure_edit.enabled" : "gui.realmcontrol.worldgen.structure_edit.disabled");
    }

    Component biomeText() {
        StructureProject.Generation generation = project.generation;
        if (generation.include.isEmpty() && generation.exclude.isEmpty()) {
            return KineticI18n.translatable("gui.realmcontrol.structure.generation.biomes.all");
        }
        return KineticI18n.translatable("gui.realmcontrol.structure.generation.biomes.some", generation.include.size(), generation.exclude.size());
    }

    void biomesChanged() {
        if (biomeButton != null) biomeButton.setText(biomeText());
    }

    @Override
    public void biomesArrived(JsonObject catalog) {
        rebuild();
    }

    @Nullable
    private static Float parseFrequency(String value) {
        try {
            float frequency = Float.parseFloat(value.trim());
            return Float.isFinite(frequency) && frequency >= 0F && frequency <= 1F ? frequency : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override
    protected void save() {
        StructureProject.Generation generation = project.generation;
        Integer spacing = spacingField.getIntValue(), separation = separationField.getIntValue(), salt = saltField.getIntValue();
        Integer offset = offsetField.getIntValue(), chunks = exclusionChunksField.getIntValue();
        Float frequency = parseFrequency(frequencyField.textValue());
        if (spacing == null || separation == null || separation >= spacing) {
            separationField.flashValidationError();
            return;
        }
        if (frequency == null) {
            frequencyField.flashValidationError();
            return;
        }
        generation.spacing = spacing;
        generation.separation = separation;
        generation.frequency = frequency;
        generation.salt = salt == null ? generation.salt : salt;
        if (mega() && separation < project.megaSeparation()) {
            separationField.flashValidationError();
            return;
        }
        generation.height = heightDropdown.selectedValue();
        generation.offset = offset == null ? 0 : offset;
        if (mega()) {
            String block = foundationBlockField.textValue().trim().toLowerCase(Locale.ROOT);
            if (!StructureProject.validId(dev.xyat.realmcontrol.structure.project.BlockStates.blockId(block))) {
                foundationBlockField.flashValidationError();
                return;
            }
            generation.foundation = foundationDropdown.selectedValue();
            generation.foundationBlock = block;
            generation.suppressOthers = suppressToggle.value();
        } else {
            generation.terrain = terrainDropdown.selectedValue();
        }
        generation.dimensions = new ArrayList<>(List.of(dimensionDropdown.selectedValue()));
        generation.exclusionSet = exclusionField.textValue().trim().toLowerCase(Locale.ROOT);
        generation.exclusionChunks = chunks == null ? 5 : chunks;
        StructureEditorNetwork.save(project, null);
    }

    /** The biomes the structure can start in now, as the server will compute them: dimensions, includes, excludes. */
    Set<String> finalBiomes() {
        JsonObject catalog = EditorClient.biomeCatalog();
        Set<String> result = new TreeSet<>();
        if (catalog == null) return result;
        StructureProject.Generation generation = project.generation;
        Set<String> dimensionBiomes = new TreeSet<>();
        JsonObject dimensions = catalog.getAsJsonObject("dimensions");
        for (String dimension : generation.dimensions) {
            if (dimensions.has(dimension)) dimensions.getAsJsonArray(dimension).forEach(e -> dimensionBiomes.add(e.getAsString()));
        }
        if (generation.include.isEmpty()) result.addAll(dimensionBiomes);
        else result.addAll(expand(catalog, generation.include));
        result.retainAll(dimensionBiomes);
        result.removeAll(expand(catalog, generation.exclude));
        return result;
    }

    static Set<String> expand(JsonObject catalog, List<String> entries) {
        Set<String> biomes = new TreeSet<>();
        JsonObject tags = catalog.getAsJsonObject("tags");
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                JsonArray members = tags.has(entry.substring(1)) ? tags.getAsJsonArray(entry.substring(1)) : new JsonArray();
                for (JsonElement member : members) biomes.add(member.getAsString());
            } else {
                biomes.add(entry);
            }
        }
        return biomes;
    }

    static String biomeName(String id) {
        int colon = id.indexOf(':');
        String key = "biome." + (colon < 0 ? "minecraft." + id : id.substring(0, colon) + "." + id.substring(colon + 1));
        return I18n.exists(key) ? I18n.get(key) : "";
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        super.renderForeground(g, mx, my, pt);
        int labelW = FIELD_X - 8 - LABEL_X, y = TOP + 4;
        for (String key : rows()) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation." + key), LABEL_X, y, labelW, 0xFFFFFFFF, false);
            y += ROW_GAP;
        }
        int w = width() - 8 - INFO_X;
        y = TOP + 4;
        Integer spacing = spacingField.getIntValue();
        Float frequency = parseFrequency(frequencyField.textValue());
        if (!project.generation.enabled) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation.off"), INFO_X, y, w, 0xFFFF5555, false);
            y += 12;
        } else if (spacing != null && frequency != null && frequency > 0F) {
            int side = Math.round(spacing * 16 / (float) Math.sqrt(frequency));
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation.estimate", side, side), INFO_X, y, w, 0xFFFFFFFF, false);
            y += 12;
        }
        JsonObject catalog = EditorClient.biomeCatalog();
        if (catalog == null) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation.loading_biomes"), INFO_X, y, w, 0xFFCCCCCC, false);
            return;
        }
        Set<String> biomes = finalBiomes();
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation.biome_count", biomes.size()), INFO_X, y, w,
                biomes.isEmpty() ? 0xFFFF5555 : 0xFFFFFFFF, false);
        y += 12;
        List<String> names = new ArrayList<>();
        for (String biome : biomes) {
            String name = biomeName(biome);
            names.add(name.isEmpty() ? biome : name);
        }
        g.scrollingText(Component.literal(String.join("、", names)), INFO_X, y, w, 0xFFCCCCCC, false);
        y += 18;
        if (mega()) {
            Integer separation = separationField.getIntValue();
            boolean apart = separation != null && separation >= project.megaSeparation();
            g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.generation.mega_separation", project.megaSeparation()), INFO_X, y, w,
                    apart ? 0xFFCCCCCC : 0xFFFF5555);
            y += 36;
        } else if (!"none".equals(terrainDropdown.selectedValue())) {
            g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.generation.terrain_warning"), INFO_X, y, w, 0xFFFFAA00);
            y += 36;
        }
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.generation.help"), INFO_X, y, w, 0xFF9AA4B2);
    }
}
