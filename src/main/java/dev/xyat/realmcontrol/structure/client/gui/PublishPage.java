package dev.xyat.realmcontrol.structure.client.gui;

import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticToggle;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.client.WorldRejoin;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork.ProjectRequestPacket.Kind;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The save options: what publishing writes (block entities, loot tables and spawners by their markers, entities,
 * cut-outs) and where (the KineticCore data pack, this world's datapacks folder or a ZIP file), with a summary of how
 * the tier is written, the markers and the problems found. Save keeps the options in the draft; Publish saves them
 * and writes the data pack; Publish and Rejoin then leaves this single-player world and opens it again, which is when
 * structure data packs load. A mega draft goes only into the KineticCore pack and needs its separation large enough;
 * the summary says so in red otherwise.
 */
public final class PublishPage extends ProjectPage {
    private static final int PUBLISH_W = 48, REJOIN_W = 72, LABEL_X = 12, COLUMN_B = 332, LABEL_W = 140, TOGGLE_W = 64, ROW_STEP = 22;
    private static final String[] OPTIONS = {"block_entities", "loot", "spawners", "entities", "persistent", "clean_entities", "cutouts"};
    @Nullable private Markers markers;
    @Nullable private JsonObject tables;
    private KineticDropdown targetDropdown;
    private KineticToggle bothToggle;
    private KineticButton rejoinButton;

    public PublishPage(StructureProject project) {
        super("gui.realmcontrol.structure.save.title", project);
    }

    @Override
    protected int previewX() {
        return Integer.MAX_VALUE;
    }

    @Override
    protected int headerRightWidth() {
        return SAVE_W + 4 + PUBLISH_W + 4 + REJOIN_W;
    }

    @Override
    protected void buildFields(KineticUi ui) {
        StructureEditorNetwork.request(Kind.MARKERS, project.id, "");
        ui().button(width() - 4 - SAVE_W - 4 - PUBLISH_W, 4, PUBLISH_W).text(KineticI18n.translatable("gui.realmcontrol.structure.action.publish"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.save.publish.tooltip")).onClick(this::publish).build();
        rejoinButton = ui().button(width() - 4 - SAVE_W - 4 - PUBLISH_W - 4 - REJOIN_W, 4, REJOIN_W)
                .text(KineticI18n.translatable("gui.realmcontrol.structure.save.rejoin"))
                .tooltip(KineticI18n.translatable(WorldRejoin.available() ? "gui.realmcontrol.structure.save.rejoin.tooltip"
                        : "gui.realmcontrol.structure.save.rejoin.unavailable"))
                .onClick(this::publishAndRejoin).build();
        StructureProject.SaveOptions options = project.save;
        List<Supplier<Boolean>> getters = List.of(() -> options.blockEntities, () -> options.loot, () -> options.spawners, () -> options.entities,
                () -> options.persistent, () -> options.cleanEntities, () -> options.cutouts);
        List<Consumer<Boolean>> setters = List.of(v -> options.blockEntities = v, v -> options.loot = v, v -> options.spawners = v, v -> options.entities = v,
                v -> options.persistent = v, v -> options.cleanEntities = v, v -> options.cutouts = v);
        for (int i = 0; i < OPTIONS.length; i++) {
            int x = (i % 2 == 0 ? LABEL_X : COLUMN_B) + LABEL_W + 4, y = TOP + 14 + (i / 2) * ROW_STEP;
            Consumer<Boolean> setter = setters.get(i);
            ui().toggle(x, y, TOGGLE_W).value(getters.get(i).get()).labels(KineticI18n.translatable("gui.realmcontrol.structure.save.on"),
                    KineticI18n.translatable("gui.realmcontrol.structure.save.off"))
                    .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.save." + OPTIONS[i] + ".tooltip")).onChange(setter).build();
        }
        int y = outputY();
        List<KineticDropdown.Option> targets = new ArrayList<>();
        for (String target : List.of("kineticcore", "world", "zip")) {
            targets.add(new KineticDropdown.Option(target, KineticI18n.translatable("gui.realmcontrol.structure.save.target." + target),
                    KineticI18n.translatable("gui.realmcontrol.structure.save.target." + target + ".tooltip")));
        }
        targetDropdown = ui().dropdown(LABEL_X + LABEL_W + 4, y, 240, targets).selected(options.target).onChange(value -> {
            options.target = value;
            updateRejoin();
        }).build();
        bothToggle = ui().toggle(LABEL_X + LABEL_W + 4, y + ROW_STEP, TOGGLE_W).value(options.bothFormats)
                .labels(KineticI18n.translatable("gui.realmcontrol.structure.save.on"), KineticI18n.translatable("gui.realmcontrol.structure.save.off"))
                .tooltip(KineticI18n.translatable(bothFormatsAvailable() ? "gui.realmcontrol.structure.save.both_formats.tooltip"
                        : "gui.realmcontrol.structure.save.both_formats.unavailable"))
                .onChange(value -> options.bothFormats = value).build();
        bothToggle.setEnabled(bothFormatsAvailable());
        updateRejoin();
    }

    /** Re-entering helps only in a single-player world, and not for a ZIP, which no world loads. */
    private void updateRejoin() {
        if (rejoinButton != null) rejoinButton.setEnabled(WorldRejoin.available() && !"zip".equals(project.save.target));
    }

    private int outputY() {
        return TOP + 14 + 4 * ROW_STEP + 18;
    }

    /** Only 1.20.1 writes both formats: newer templates cannot load in older versions. */
    private static boolean bothFormatsAvailable() {
        //? if >=1.21 {
        /*return false;
        *///?} else {
        return true;
        //?}
    }

    @Override
    protected void save() {
        if (!bothFormatsAvailable()) project.save.bothFormats = false;
        StructureEditorNetwork.save(project, null);
    }

    private void publish() {
        save();
        StructureEditorNetwork.request(Kind.PUBLISH, project.id, "");
        navigateBack();
    }

    private void publishAndRejoin() {
        EditorClient.rejoinAfterPublish(project.id);
        publish();
    }

    @Override
    public void markersArrived(StructureProject arrived, Markers arrivedMarkers, JsonObject arrivedTables, String focus) {
        if (!arrived.id.equals(project.id)) return;
        markers = arrivedMarkers;
        tables = arrivedTables;
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        super.renderForeground(g, mx, my, pt);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.save.content"), LABEL_X, TOP + 2, 200, 0xFFFFAA00, false);
        for (int i = 0; i < OPTIONS.length; i++) {
            int x = i % 2 == 0 ? LABEL_X : COLUMN_B, y = TOP + 14 + (i / 2) * ROW_STEP + 4;
            Component label = OPTIONS[i].equals("cutouts") && cutouts != null
                    ? KineticI18n.translatable("gui.realmcontrol.structure.save.cutouts_count", cutouts.count())
                    : KineticI18n.translatable("gui.realmcontrol.structure.save." + OPTIONS[i]);
            g.scrollingText(label, x, y, LABEL_W, 0xFFFFFFFF, false);
        }
        int y = outputY();
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.save.output"), LABEL_X, y - 14, 200, 0xFFFFAA00, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.save.target"), LABEL_X, y + 4, LABEL_W, 0xFFFFFFFF, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.save.both_formats"), LABEL_X, y + ROW_STEP + 4, LABEL_W,
                bothFormatsAvailable() ? 0xFFFFFFFF : 0xFF9AA4B2, false);
        int w = width() - 4 - LABEL_X;
        y += 2 * ROW_STEP + 8;
        for (Line line : summary()) {
            g.scrollingText(line.text(), LABEL_X, y, w, line.color(), false);
            y += 12;
        }
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.save.help"), LABEL_X, y + 4, w, 0xFF9AA4B2);
    }

    private record Line(Component text, int color) {
    }

    /** How the tier is written, what the markers will do, then the problems: unknown tables stop the publish, the rest only warn. */
    private List<Line> summary() {
        List<Line> lines = new ArrayList<>();
        String tier = project.tier();
        Component tierLine = switch (tier) {
            case "medium" -> KineticI18n.translatable("gui.realmcontrol.structure.save.tier.medium", project.tilesX(), project.tilesZ(), project.tileSize, project.tileSize,
                    templateMegabytes());
            case "mega" -> KineticI18n.translatable("gui.realmcontrol.structure.save.tier.mega", project.tilesX(), project.tilesZ(), project.tileSize, project.tileSize);
            default -> KineticI18n.translatable("gui.realmcontrol.structure.save.tier.small");
        };
        lines.add(new Line(tierLine, 0xFFCCCCCC));
        if ("mega".equals(tier) && !"kineticcore".equals(project.save.target)) {
            lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.structure.save.mega_target"), 0xFFFF5555));
        }
        if ("mega".equals(tier) && project.generation.separation < project.megaSeparation()) {
            lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.structure.generation.mega_separation", project.megaSeparation()), 0xFFFF5555));
        }
        if (markers == null) {
            lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.structure.save.loading"), 0xFFCCCCCC));
            return lines;
        }
        int tabled = 0, custom = 0;
        for (Markers.Container container : markers.containers) if (container.usesTable()) tabled++;
        for (Markers.Spawner spawner : markers.spawners) if (spawner.custom()) custom++;
        int kept = markers.keptEntities();
        lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.structure.save.summary", tabled, markers.containers.size() - tabled, custom,
                markers.spawners.size() - custom, kept, markers.entities.size() - kept), 0xFFCCCCCC));
        if (tables != null) {
            List<String> unknown = new ArrayList<>(), partial = new ArrayList<>();
            for (String table : tables.keySet()) {
                JsonObject state = tables.getAsJsonObject(table);
                if (!state.get("exists").getAsBoolean()) unknown.add(table);
                else if (!state.getAsJsonArray("missing").isEmpty()) partial.add(table);
            }
            if (!unknown.isEmpty()) lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.structure.save.unknown_tables", String.join("、", unknown)), 0xFFFF5555));
            if (!partial.isEmpty()) lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.structure.save.partial_tables", String.join("、", partial)), 0xFFFFAA00));
        }
        if (!project.missing.isEmpty()) {
            lines.add(new Line(KineticI18n.translatable("gui.realmcontrol.worldgen.preview.missing", StructureEditorPage.missingText(project)), 0xFFFFAA00));
        }
        return lines;
    }

    /**
     * Roughly what the templates take once vanilla has loaded them, which it keeps until the world is left: about
     * 56 bytes for every block a template lists (indoor air included with air {@code auto}).
     */
    private int templateMegabytes() {
        long cells = (long) project.size[0] * project.size[1] * project.size[2];
        long listed = switch (project.air) {
            case "keep" -> cells;
            case "none" -> project.blocks;
            default -> Math.min(cells, project.blocks * 3L / 2);
        };
        return (int) Math.max(1L, Math.round(listed * 56 / 1048576.0D));
    }
}
