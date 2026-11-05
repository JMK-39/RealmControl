package dev.xyat.realmcontrol.structure.client.gui;

import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picks the biomes a draft may start in: biome tags first, then biomes, as text rows. Left click includes an entry
 * (green), right click excludes it (red), a second click of the same kind clears it. The right column shows the
 * biomes that result for the chosen dimension. Apply hands the choice back to the generation page.
 */
public final class BiomePickerPage extends KineticPage implements EditorClient.Listener {
    private static final int LIST_X = 4, LIST_Y = 46, LIST_W = 380, RESULT_X = LIST_X + LIST_W + 8;
    private final GenerationPage parent;
    private final List<String> include;
    private final List<String> exclude;
    private String search = "";
    private String dimensionFilter;
    private EntryList list;

    public BiomePickerPage(GenerationPage parent) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.biomes.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.parent = parent;
        StructureProject.Generation generation = parent.generation();
        this.include = new ArrayList<>(generation.include);
        this.exclude = new ArrayList<>(generation.exclude);
        this.dimensionFilter = generation.dimensions.isEmpty() ? "" : generation.dimensions.get(0);
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::navigateBack).build();
        ui().button(width() - 4 - 48, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.structure.biomes.apply")).onClick(this::apply).build();
        int filterW = 120;
        KineticTextField searchField = ui().textField(LIST_X, 28, LIST_W - filterW - 4)
                .placeholder(KineticI18n.translatable("gui.realmcontrol.structure.biomes.search")).build();
        searchField.setTextValue(search);
        searchField.onTextChange(value -> {
            search = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            refresh();
        });
        List<KineticDropdown.Option> filters = new ArrayList<>();
        filters.add(new KineticDropdown.Option("*", KineticI18n.translatable("gui.realmcontrol.structure.biomes.all_dimensions")));
        JsonObject catalog = EditorClient.biomeCatalog();
        if (catalog != null) {
            for (String dimension : new TreeSet<>(catalog.getAsJsonObject("dimensions").keySet())) {
                filters.add(new KineticDropdown.Option(dimension, KineticI18n.translatable("gui.realmcontrol.structure.biomes.only", GenerationPage.dimensionName(dimension))));
            }
        }
        ui().dropdown(LIST_X + LIST_W - filterW, 28, filterW, filters).selected(dimensionFilter.isEmpty() ? "*" : dimensionFilter)
                .onChange(value -> {
                    dimensionFilter = "*".equals(value) ? "" : value;
                    refresh();
                }).build();
        list = ui.add(new EntryList(LIST_X, LIST_Y + 2, LIST_W, height() - LIST_Y - 6));
        refresh();
    }

    private void refresh() {
        if (list == null) return;
        JsonObject catalog = EditorClient.biomeCatalog();
        List<String> entries = new ArrayList<>();
        if (catalog != null) {
            Set<String> inDimension = new TreeSet<>();
            if (!dimensionFilter.isEmpty() && catalog.getAsJsonObject("dimensions").has(dimensionFilter)) {
                catalog.getAsJsonObject("dimensions").getAsJsonArray(dimensionFilter).forEach(e -> inDimension.add(e.getAsString()));
            }
            for (String tag : new TreeSet<>(catalog.getAsJsonObject("tags").keySet())) {
                Set<String> members = GenerationPage.expand(catalog, List.of("#" + tag));
                if (!dimensionFilter.isEmpty() && members.stream().noneMatch(inDimension::contains)) continue;
                if (matches("#" + tag, "")) entries.add("#" + tag);
            }
            catalog.getAsJsonArray("biomes").forEach(e -> {
                String biome = e.getAsString();
                if ((dimensionFilter.isEmpty() || inDimension.contains(biome)) && matches(biome, GenerationPage.biomeName(biome))) entries.add(biome);
            });
        }
        list.setItems(entries);
    }

    private boolean matches(String id, String name) {
        return search.isEmpty() || id.toLowerCase(Locale.ROOT).contains(search) || name.toLowerCase(Locale.ROOT).contains(search);
    }

    private void apply() {
        StructureProject.Generation generation = parent.generation();
        generation.include = new ArrayList<>(include);
        generation.exclude = new ArrayList<>(exclude);
        parent.biomesChanged();
        navigateBack();
    }

    @Override
    public void biomesArrived(JsonObject catalog) {
        rebuild();
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
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.biomes.title"), width() / 2, 8, titleRoom, 0xFFFFAA00, false);
        JsonObject catalog = EditorClient.biomeCatalog();
        int w = width() - 4 - RESULT_X, y = LIST_Y + 2;
        if (catalog == null) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation.loading_biomes"), RESULT_X, y, w, 0xFFCCCCCC, false);
            return;
        }
        // The same rule as the server: chosen dimension, then includes (all of it when none), minus excludes.
        Set<String> dimensionBiomes = new TreeSet<>();
        for (String dimension : parent.generation().dimensions) {
            if (catalog.getAsJsonObject("dimensions").has(dimension)) {
                catalog.getAsJsonObject("dimensions").getAsJsonArray(dimension).forEach(e -> dimensionBiomes.add(e.getAsString()));
            }
        }
        Set<String> result = include.isEmpty() ? new TreeSet<>(dimensionBiomes) : GenerationPage.expand(catalog, include);
        result.retainAll(dimensionBiomes);
        result.removeAll(GenerationPage.expand(catalog, exclude));
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generation.biome_count", result.size()), RESULT_X, y, w,
                result.isEmpty() ? 0xFFFF5555 : 0xFFFFFFFF, false);
        y += 14;
        int lines = 0;
        for (String biome : result) {
            if (y > height() - 40) {
                g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.biomes.more", result.size() - lines), RESULT_X, y, w, 0xFFCCCCCC, false);
                break;
            }
            String name = GenerationPage.biomeName(biome);
            g.scrollingText(Component.literal(name.isEmpty() ? biome : name + "  " + biome), RESULT_X, y, w, 0xFFCCCCCC, false);
            y += 11;
            lines++;
        }
        boolean dynamic = exclude.isEmpty();
        g.scrollingText(KineticI18n.translatable(dynamic ? "gui.realmcontrol.structure.biomes.mode_dynamic" : "gui.realmcontrol.structure.biomes.mode_static"),
                RESULT_X, height() - 28, w, 0xFF9AA4B2, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.biomes.hint"), RESULT_X, height() - 16, w, 0xFF9AA4B2, false);
    }

    private final class EntryList extends KineticRowList<String> {
        EntryList(int x, int y, int width, int height) {
            super(x, y, width, height, 16);
        }

        @Override
        protected void renderRow(KineticGraphics g, String entry, int index, int x, int y, int width, int height, boolean hovered, boolean selected) {
            boolean included = include.contains(entry), excluded = exclude.contains(entry);
            if (included) g.fill(x, y, x + width, y + height, 0x3055DD55);
            else if (excluded) g.fill(x, y, x + width, y + height, 0x30FF5555);
            if (hovered) KineticTheme.stateOutline(g, x, y, width, height, false, true, false);
            Component text;
            if (entry.startsWith("#")) {
                JsonObject catalog = EditorClient.biomeCatalog();
                int count = catalog == null ? 0 : GenerationPage.expand(catalog, List.of(entry)).size();
                text = KineticI18n.translatable("gui.realmcontrol.structure.biomes.tag_row", entry, count);
            } else {
                String name = GenerationPage.biomeName(entry);
                text = Component.literal(name.isEmpty() ? entry : name + "  ").append(Component.literal(name.isEmpty() ? "" : entry)
                        .withStyle(style -> style.withColor(0x9AA4B2)));
            }
            int color = included ? 0xFF77DD77 : excluded ? 0xFFFF7777 : 0xFFFFFFFF;
            int stateW = included || excluded ? 40 : 0;
            g.scrollingText(text, x + 4, y + (height - g.lineHeight()) / 2 + 1, width - 8 - stateW, color, false);
            if (stateW > 0) {
                g.scrollingTextRight(KineticI18n.translatable(included ? "gui.realmcontrol.structure.biomes.included" : "gui.realmcontrol.structure.biomes.excluded"),
                        x + width - 4, y + (height - g.lineHeight()) / 2 + 1, stateW - 4, color, false);
            }
        }

        @Override
        protected boolean onRowClick(String entry, int index, MouseInput input) {
            if (input.isLeft()) {
                exclude.remove(entry);
                if (!include.remove(entry)) include.add(entry);
            } else if (input.isRight()) {
                include.remove(entry);
                if (!exclude.remove(entry)) exclude.add(entry);
            } else {
                return false;
            }
            playClickSound();
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable(EditorClient.biomeCatalog() == null ? "gui.realmcontrol.structure.generation.loading_biomes"
                    : "gui.realmcontrol.structure.biomes.none");
        }
    }
}
