package dev.xyat.realmcontrol.structure.publish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.project.Tile;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Publishes a draft as vanilla data pack files, by default into KineticCore's data pack folder, pack
 * {@code realmcontrol} ({@code config/kineticcore/datapack/data/realmcontrol/}); the save options can choose the
 * world's {@code datapacks} folder or a ZIP file instead. Every tier up to medium is plain vanilla, so the structure
 * keeps generating without RealmControl:
 * <ul>
 * <li>small (footprint up to 64×64): one jigsaw piece, its template and a one-entry template pool;</li>
 * <li>medium (up to 256×256): the tiles of the draft as templates, each in a pool of its own, and a start piece the
 * size of the whole structure that holds nothing but jigsaw blocks. Its anchor jigsaw sits in the middle, so the
 * structure is centred on its start chunk as vanilla requires; every tile hangs directly off it (depth 1) and lands
 * where it was scanned, because the jigsaw pairs line up exactly and only one rotation fits.</li>
 * </ul>
 * Both come with a jigsaw structure, a random-spread structure set and the biome tag {@code has_structure/<path>}.
 * A mega structure (up to 1024×1024) needs RealmControl: its 32×32 tiles become templates, its structure has the type
 * {@code realmcontrol:mega} and its set the placement {@code realmcontrol:mega_spread}; it is published only into the
 * KineticCore pack, which leaves those files out (and the world loads) when RealmControl is not installed.
 * Markers apply as it is written: containers get their loot tables, custom spawners their mobs, and left-out entities
 * are dropped. All files are written beside their targets first and moved into place at the end, so a failure leaves
 * the previous files untouched; files an earlier publish wrote that this one no longer needs are removed. Structures
 * load when a world is entered, and only new chunks get them.
 */
public final class Publisher {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final String PACK = "realmcontrol";
    private static final Pattern TILE_FILE = Pattern.compile("t_\\d+_\\d+\\.(nbt|json)");
    private static final String STRUCTURE_VOID = "minecraft:structure_void", EMPTY = "minecraft:empty";

    /** What came of a publish: the message for the player and whether the files were written. */
    public record Outcome(boolean success, Component message) {
    }

    /** A jigsaw block a template holds in place of one of its cells. */
    private record Jigsaw(int x, int y, int z, String state, CompoundTag nbt) {
    }

    private Publisher() {
    }

    public static Path packFolder() {
        return KineticPlatform.configDirectory().resolve("kineticcore").resolve("datapack").resolve("data").resolve(PACK);
    }

    /** Where ZIP exports go: {@code <game directory>/kineticcore/structure_exports/}. */
    public static Path exportFolder() {
        return KineticPlatform.gameDirectory().resolve("kineticcore").resolve("structure_exports");
    }

    /** The pack folder a publish writes into. */
    static Path target(MinecraftServer server, StructureProject project) {
        return switch (project.save.target) {
            case "world" -> server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK);
            case "zip" -> exportFolder().resolve(".staging").resolve(project.namespace() + "_" + project.path().replace('/', '_'));
            default -> packFolder();
        };
    }

    /** Publishes project {@code id}; runs off the server thread, {@code progress} gets 0 to 1. */
    public static Outcome publish(MinecraftServer server, String id, Consumer<Float> progress) throws IOException {
        StructureProject project = ProjectStore.load(id);
        if (project == null) return failure("msg.realmcontrol.structure.publish.unknown", id);
        boolean medium = "medium".equals(project.tier()), mega = "mega".equals(project.tier());
        StructureProject.Generation generation = project.generation;
        if (generation.separation >= generation.spacing) return failure("msg.realmcontrol.structure.publish.spacing");
        if (mega && !"kineticcore".equals(project.save.target)) return failure("msg.realmcontrol.structure.publish.mega_target");
        if (mega && generation.separation < project.megaSeparation()) {
            return failure("msg.realmcontrol.structure.publish.mega_separation", project.megaSeparation(), Math.max(project.size[0], project.size[2]));
        }
        BiomeFilter.Result biomes = BiomeFilter.resolve(server, generation);
        if (biomes.values().isEmpty()) return failure("msg.realmcontrol.structure.publish.no_biomes");
        StructureProject.SaveOptions options = project.save;
        Markers markers = ProjectStore.markers(id);
        List<String> unknownTables = options.loot ? LootTables.unknownTables(server, markers) : List.of();
        if (!unknownTables.isEmpty()) return failure("msg.realmcontrol.structure.publish.unknown_tables", String.join("、", unknownTables));
        progress.accept(0.05F);

        Cutouts cutouts = options.cutouts ? ProjectStore.cutouts(id) : new Cutouts();
        String namespace = project.namespace(), path = project.path();
        Path root = target(server, project);
        Path data = root.resolve("data").resolve(namespace);
        List<Path[]> moves = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        if (mega) {
            Outcome problem = stageMegaTiles(project, cutouts, markers, data, moves, progress, absent);
            if (problem != null) return problem;
        } else if (medium) {
            Outcome problem = stageTiles(project, cutouts, markers, data, moves, progress);
            if (problem != null) return problem;
        } else {
            Tile tile = ProjectStore.readTile(id, 0, 0);
            if (tile == null) return failure("msg.realmcontrol.structure.publish.no_tiles");
            boolean[] keep = cellsToWrite(project, tile, cutouts, 0, 0);
            progress.accept(0.25F);
            Path template = stageTemplate(data, path + ".nbt", moves);
            writeTemplate(template, project, tile, keep, cutouts, markers, 0, 0, null);
            copyToOtherFormat(data, path + ".nbt", options, moves);
            writeJson(staged(data.resolve("worldgen/template_pool/" + path + "/start.json"), moves), pool(project.id));
        }
        progress.accept(0.8F);

        stageGeneration(project, biomes, data, moves, absent);
        if (options.loot) stageTables(root, markers, options, moves);
        Path meta = root.resolve("pack.mcmeta");
        if (!Files.exists(meta) || options.bothFormats) writeJson(staged(meta, moves), packMeta(options.bothFormats));
        for (Path[] move : moves) Files.move(move[0], move[1], StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Set<Path> written = new HashSet<>();
        for (Path[] move : moves) written.add(move[1]);
        removeStale(data, path, written);
        Path published = root;
        if ("zip".equals(options.target)) published = zip(root, exportFolder().resolve(root.getFileName() + ".zip"));
        progress.accept(0.95F);

        project.status = "published";
        project.publishedAt = System.currentTimeMillis();
        // A ZIP is not loaded from where it was written, so later rule changes have nothing to update.
        project.publishedRoot = "zip".equals(options.target) ? "" : root.toAbsolutePath().toString();
        ProjectStore.save(project);
        dev.xyat.realmcontrol.worldgen.preview.StructureThumbnailService.forget(server, id);
        String doneKey = switch (options.target) {
            case "world" -> "msg.realmcontrol.structure.publish.done_world";
            case "zip" -> "msg.realmcontrol.structure.publish.done_zip";
            default -> "msg.realmcontrol.structure.publish.done";
        };
        return new Outcome(true, KineticI18n.translatable(doneKey, id, biomes.count(), published.toString()));
    }

    /**
     * Rewrites the generation files of a published structure where it was published (its structure, structure set
     * and biome tag); templates stay as they are. This is how a rule change from the editor or the structure panel
     * reaches the files, which the game reads the next time a world is entered. Returns {@code null} when the
     * structure was not published into a folder that still holds it.
     */
    @Nullable
    public static Outcome syncGeneration(MinecraftServer server, StructureProject project) throws IOException {
        if (project.publishedRoot.isBlank() || "draft".equals(project.status)) return null;
        Path data = Path.of(project.publishedRoot).resolve("data").resolve(project.namespace());
        if (!Files.isRegularFile(data.resolve("worldgen/structure_set/" + project.path() + ".json"))) return null;
        StructureProject.Generation generation = project.generation;
        if (generation.separation >= generation.spacing) return failure("msg.realmcontrol.structure.publish.spacing");
        if ("mega".equals(project.tier()) && generation.separation < project.megaSeparation()) {
            return failure("msg.realmcontrol.structure.publish.mega_separation", project.megaSeparation(), Math.max(project.size[0], project.size[2]));
        }
        BiomeFilter.Result biomes = BiomeFilter.resolve(server, generation);
        if (biomes.values().isEmpty()) return failure("msg.realmcontrol.structure.publish.no_biomes");
        List<Path[]> moves = new ArrayList<>();
        stageGeneration(project, biomes, data, moves, publishedAbsent(project, data));
        for (Path[] move : moves) Files.move(move[0], move[1], StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Outcome(true, KineticI18n.translatable("msg.realmcontrol.structure.generation.synced", project.name, biomes.count()));
    }

    /** The structure, the structure set and the biome tag; {@code absent} lists a mega structure's tiles without a template. */
    private static void stageGeneration(StructureProject project, BiomeFilter.Result biomes, Path data, List<Path[]> moves, List<String> absent) throws IOException {
        String path = project.path();
        writeJson(staged(data.resolve("worldgen/structure/" + path + ".json"), moves),
                "mega".equals(project.tier()) ? megaStructure(project, absent) : structure(project));
        writeJson(staged(data.resolve("worldgen/structure_set/" + path + ".json"), moves), structureSet(project));
        JsonObject tag = new JsonObject();
        tag.addProperty("replace", false);
        tag.add("values", biomes.values());
        writeJson(staged(data.resolve("tags/worldgen/biome/has_structure/" + path + ".json"), moves), tag);
    }

    /**
     * A medium structure: every tile that keeps anything becomes a template with one jigsaw block and a pool of its
     * own, and the frame template gets the anchor and one jigsaw per tile. A frame jigsaw at {@code P} faces east
     * (south when the structure is one block wide) and its tile's jigsaw sits at the next cell {@code P + facing},
     * facing back; that is where vanilla puts the tile, so it lands exactly where it was scanned. The tile's jigsaw
     * turns into what that cell held: nothing for a cell the template leaves to the world, air, or the block itself.
     */
    @Nullable
    private static Outcome stageTiles(StructureProject project, Cutouts cutouts, Markers markers, Path data, List<Path[]> moves,
                                      Consumer<Float> progress) throws IOException {
        StructureProject.SaveOptions options = project.save;
        String path = project.path(), prefix = project.id + "/";
        int size = project.tileSize, tilesX = project.tilesX(), tilesZ = project.tilesZ();
        boolean alongX = project.size[0] >= 2;
        int anchorX = project.size[0] / 2, anchorZ = project.size[2] / 2;
        // The frame jigsaw of the cell east (or south) of the anchor would share the anchor's place.
        int skipX = anchorX + (alongX ? 1 : 0), skipZ = anchorZ + (alongX ? 0 : 1);
        List<Jigsaw> frame = new ArrayList<>();
        frame.add(new Jigsaw(anchorX, 0, anchorZ, "minecraft:jigsaw[orientation=up_north]", jigsawData(prefix + "anchor", EMPTY, EMPTY, STRUCTURE_VOID)));
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                Tile tile = ProjectStore.readTile(project.id, tx, tz);
                if (tile == null) return failure("msg.realmcontrol.structure.publish.no_tiles");
                int ox = tx * size, oz = tz * size;
                boolean[] keep = cellsToWrite(project, tile, cutouts, ox, oz);
                boolean any = false;
                for (boolean cell : keep) any |= cell;
                if (!any && entitiesToWrite(tile, cutouts, markers, options, ox, oz).isEmpty()) continue;
                int cell = jigsawCell(tile, keep, alongX, ox, oz, skipX, skipZ);
                if (cell < 0) return failure("msg.realmcontrol.structure.publish.no_jigsaw", tx + "," + tz);
                int x = cell % tile.sizeX, z = cell / tile.sizeX % tile.sizeZ, y = cell / (tile.sizeX * tile.sizeZ);
                String name = prefix + "t_" + tx + "_" + tz;
                String state = tile.palette.get(tile.cells[cell] & 0xFFFF);
                String finalState = !keep[cell] ? STRUCTURE_VOID : state;
                Jigsaw own = new Jigsaw(x, y, z, alongX ? "minecraft:jigsaw[orientation=west_up]" : "minecraft:jigsaw[orientation=north_up]",
                        jigsawData(name, EMPTY, EMPTY, finalState));
                String file = path + "/t_" + tx + "_" + tz + ".nbt";
                writeTemplate(stageTemplate(data, file, moves), project, tile, keep, cutouts, markers, ox, oz, own);
                copyToOtherFormat(data, file, options, moves);
                writeJson(staged(data.resolve("worldgen/template_pool/" + path + "/t_" + tx + "_" + tz + ".json"), moves), pool(name));
                frame.add(new Jigsaw(ox + x - (alongX ? 1 : 0), y, oz + z - (alongX ? 0 : 1),
                        alongX ? "minecraft:jigsaw[orientation=east_up]" : "minecraft:jigsaw[orientation=south_up]", jigsawData(EMPTY, name, name, STRUCTURE_VOID)));
                progress.accept(0.05F + 0.7F * (tz * tilesX + tx + 1) / (tilesX * tilesZ));
            }
        }
        if (frame.size() == 1) return failure("msg.realmcontrol.structure.publish.no_tiles");
        String file = path + "/frame.nbt";
        writeFrame(stageTemplate(data, file, moves), project.size, frame);
        copyToOtherFormat(data, file, options, moves);
        writeJson(staged(data.resolve("worldgen/template_pool/" + path + "/start.json"), moves), pool(prefix + "frame"));
        return null;
    }

    /**
     * A mega structure: every 32×32 tile that keeps anything becomes a template; tiles cut out whole are listed in
     * {@code absent} instead.
     */
    @Nullable
    private static Outcome stageMegaTiles(StructureProject project, Cutouts cutouts, Markers markers, Path data, List<Path[]> moves,
                                          Consumer<Float> progress, List<String> absent) throws IOException {
        StructureProject.SaveOptions options = project.save;
        String path = project.path();
        int size = project.tileSize, tilesX = project.tilesX(), tilesZ = project.tilesZ();
        if (size != dev.xyat.realmcontrol.structure.mega.MegaLayout.TILE) return failure("msg.realmcontrol.structure.publish.no_tiles");
        int written = 0;
        for (int tz = 0; tz < tilesZ; tz++) {
            for (int tx = 0; tx < tilesX; tx++) {
                Tile tile = ProjectStore.readTile(project.id, tx, tz);
                if (tile == null) return failure("msg.realmcontrol.structure.publish.no_tiles");
                int ox = tx * size, oz = tz * size;
                boolean[] keep = cellsToWrite(project, tile, cutouts, ox, oz);
                boolean any = false;
                for (boolean cell : keep) any |= cell;
                if (!any && entitiesToWrite(tile, cutouts, markers, options, ox, oz).isEmpty()) {
                    absent.add(tx + "_" + tz);
                    continue;
                }
                String file = path + "/t_" + tx + "_" + tz + ".nbt";
                writeTemplate(stageTemplate(data, file, moves), project, tile, keep, cutouts, markers, ox, oz, null);
                copyToOtherFormat(data, file, options, moves);
                written++;
                progress.accept(0.05F + 0.7F * (tz * tilesX + tx + 1) / (tilesX * tilesZ));
            }
        }
        return written == 0 ? failure("msg.realmcontrol.structure.publish.no_tiles") : null;
    }

    /** The tiles of a published mega structure that have no template, read from the folder it was published to. */
    private static List<String> publishedAbsent(StructureProject project, Path data) {
        List<String> absent = new ArrayList<>();
        if (!"mega".equals(project.tier())) return absent;
        Path tiles = data.resolve(templateFolder()).resolve(project.path());
        for (int tz = 0; tz < project.tilesZ(); tz++) {
            for (int tx = 0; tx < project.tilesX(); tx++) {
                if (!Files.isRegularFile(tiles.resolve("t_" + tx + "_" + tz + ".nbt"))) absent.add(tx + "_" + tz);
            }
        }
        return absent;
    }

    /**
     * Where a tile's jigsaw goes: a cell without a block entity whose frame partner (one cell west, or north) is
     * inside the structure and not the anchor. Best is a cell the template leaves to the world, then air, then a
     * vanilla block: the jigsaw's final state must parse even if a mod is removed later, or placing it would fail.
     */
    private static int jigsawCell(Tile tile, boolean[] keep, boolean alongX, int ox, int oz, int skipX, int skipZ) {
        int air = -1, plain = -1;
        for (int y = tile.sizeY - 1; y >= 0; y--) {
            for (int z = 0; z < tile.sizeZ; z++) {
                for (int x = 0; x < tile.sizeX; x++) {
                    if ((alongX ? ox + x : oz + z) < 1 || ox + x == skipX && oz + z == skipZ && y == 0) continue;
                    int cell = tile.cell(x, y, z);
                    if (tile.blockEntities.containsKey(cell)) continue;
                    if (!keep[cell]) return cell;
                    String state = tile.palette.get(tile.cells[cell] & 0xFFFF);
                    if (air < 0 && BlockStates.AIR.equals(state)) air = cell;
                    else if (plain < 0 && state.startsWith("minecraft:")) plain = cell;
                }
            }
        }
        return air >= 0 ? air : plain;
    }

    /** What a jigsaw block keeps: its name, the pool and jigsaw name it connects to, and what it turns into. */
    private static CompoundTag jigsawData(String name, String target, String pool, String finalState) {
        CompoundTag data = new CompoundTag();
        data.putString("id", "minecraft:jigsaw");
        data.putString("name", name);
        data.putString("target", target);
        data.putString("pool", pool);
        data.putString("final_state", finalState);
        data.putString("joint", "aligned");
        data.putInt("placement_priority", 0);
        data.putInt("selection_priority", 0);
        return data;
    }

    /** The start piece of a medium structure: the structure's size, nothing in it but the jigsaw blocks. */
    private static void writeFrame(Path file, int[] size, List<Jigsaw> jigsaws) throws IOException {
        List<String> palette = new ArrayList<>();
        for (Jigsaw jigsaw : jigsaws) if (!palette.contains(jigsaw.state())) palette.add(jigsaw.state());
        try (TemplateWriter writer = new TemplateWriter(file, size[0], size[1], size[2], palette, jigsaws.size())) {
            for (Jigsaw jigsaw : jigsaws) writer.block(jigsaw.x(), jigsaw.y(), jigsaw.z(), palette.indexOf(jigsaw.state()), jigsaw.nbt());
            writer.startEntities(0);
            writer.finish(SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        }
    }

    /** A staged template file in this version's template folder. */
    private static Path stageTemplate(Path data, String file, List<Path[]> moves) throws IOException {
        return staged(data.resolve(templateFolder()).resolve(file), moves);
    }

    /** When both formats are written, a copy of the template just staged goes into the other version's folder. */
    private static void copyToOtherFormat(Path data, String file, StructureProject.SaveOptions options, List<Path[]> moves) throws IOException {
        if (!options.bothFormats) return;
        Path staged = moves.get(moves.size() - 1)[0];
        Files.copy(staged, staged(data.resolve(otherTemplateFolder()).resolve(file), moves), StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Removes the templates and tile pools of this structure that the publish did not write: those of the other
     * tier, of tiles that are now cut out entirely, and of the other format when it is no longer written.
     */
    private static void removeStale(Path data, String path, Set<Path> written) throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (String folder : List.of(templateFolder(), otherTemplateFolder())) {
            Path templates = data.resolve(folder);
            candidates.add(templates.resolve(path + ".nbt"));
            candidates.add(templates.resolve(path).resolve("frame.nbt"));
            candidates.addAll(tileFiles(templates.resolve(path)));
        }
        candidates.addAll(tileFiles(data.resolve("worldgen/template_pool").resolve(path)));
        candidates.add(data.resolve("worldgen/template_pool").resolve(path).resolve("start.json"));
        for (Path file : candidates) {
            if (!written.contains(file)) Files.deleteIfExists(file);
        }
        for (String folder : List.of(templateFolder(), otherTemplateFolder())) {
            Path tiles = data.resolve(folder).resolve(path);
            if (Files.isDirectory(tiles)) {
                try (var entries = Files.list(tiles)) {
                    if (entries.findAny().isEmpty()) Files.delete(tiles);
                }
            }
        }
    }

    private static List<Path> tileFiles(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) return List.of();
        try (var files = Files.list(folder)) {
            return files.filter(file -> TILE_FILE.matcher(file.getFileName().toString()).matches()).toList();
        }
    }

    /**
     * The loot tables the containers use that are files of the KineticCore pack, copied into the target pack when it
     * is another one, and into the other format's folder when both are written. Tables from elsewhere stay where they
     * are.
     */
    private static void stageTables(Path root, Markers markers, StructureProject.SaveOptions options, List<Path[]> moves) throws IOException {
        boolean elsewhere = !root.equals(packFolder());
        for (String table : markers.tables()) {
            ResourceLocation id = ResourceLocation.tryParse(table);
            if (id == null || !Files.isRegularFile(LootTables.packFile(id))) continue;
            JsonObject json = LootTables.readFile(LootTables.packFile(id));
            Path tables = root.resolve("data").resolve(id.getNamespace());
            if (elsewhere) writeJson(staged(tables.resolve(LootTables.FOLDER).resolve(id.getPath() + ".json"), moves), json);
            if (options.bothFormats) writeJson(staged(tables.resolve(LootTables.OTHER_FOLDER).resolve(id.getPath() + ".json"), moves), LootTables.otherFormat(json));
        }
    }

    /** Packs the staged folder into {@code zip}, then removes the folder. */
    private static Path zip(Path folder, Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        Path temp = zip.resolveSibling(zip.getFileName() + ".tmp");
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(temp));
             java.util.stream.Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(folder.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        Files.move(temp, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        ProjectStore.deleteTree(folder);
        return zip;
    }

    /** Writes {@code pack.mcmeta} into a pack folder that has none yet. */
    static void ensurePackMeta(Path root, boolean bothFormats) throws IOException {
        Path meta = root.resolve("pack.mcmeta");
        if (Files.exists(meta)) return;
        Files.createDirectories(root);
        writeJson(meta, packMeta(bothFormats));
    }

    /**
     * Which cells of a tile its template lists; {@code ox}, {@code oz} place the tile in the structure. Cut-out
     * cells, ignored blocks and air left to the world are left out, so generation does not touch those positions.
     * With air {@code auto}, air with a block of the structure somewhere above it (indoors) is listed and carves,
     * open-sky air is not.
     */
    public static boolean[] cellsToWrite(StructureProject project, Tile tile, Cutouts cutouts, int ox, int oz) {
        boolean[] keep = new boolean[tile.cells.length];
        Set<Integer> ignored = new HashSet<>();
        Set<String> ignoredIds = new HashSet<>(project.ignoreBlocks);
        for (int i = 0; i < tile.palette.size(); i++) {
            if (ignoredIds.contains(BlockStates.blockId(tile.palette.get(i)))) ignored.add(i);
        }
        for (int z = 0; z < tile.sizeZ; z++) {
            for (int x = 0; x < tile.sizeX; x++) {
                boolean covered = false;
                for (int y = tile.sizeY - 1; y >= 0; y--) {
                    int cell = tile.cell(x, y, z);
                    int index = tile.cells[cell] & 0xFFFF;
                    if (cutouts.isCut(ox + x, y, oz + z) || ignored.contains(index)) continue;
                    if (index != 0) {
                        keep[cell] = true;
                        covered = true;
                    } else {
                        keep[cell] = switch (project.air) {
                            case "keep" -> true;
                            case "none" -> false;
                            default -> covered;
                        };
                    }
                }
            }
        }
        return keep;
    }

    /** The entities of a tile that are published: not in a cut-out and not left out by the markers. */
    static List<Tile.EntityData> entitiesToWrite(Tile tile, Cutouts cutouts, Markers markers, StructureProject.SaveOptions options, int ox, int oz) {
        List<Tile.EntityData> entities = new ArrayList<>();
        if (!options.entities) return entities;
        for (Tile.EntityData entity : tile.entities) {
            double x = ox + entity.x(), z = oz + entity.z();
            if (cutouts.isCut((int) Math.floor(x), (int) Math.floor(entity.y()), (int) Math.floor(z))) continue;
            CompoundTag data = entity.tag();
            if (markers.keeps(data.getString("id"), x, entity.y(), z)) entities.add(entity);
        }
        return entities;
    }

    private static void writeTemplate(Path file, StructureProject project, Tile tile, boolean[] keep, Cutouts cutouts, Markers markers,
                                      int ox, int oz, @Nullable Jigsaw jigsaw) throws IOException {
        StructureProject.SaveOptions options = project.save;
        int jigsawCell = jigsaw == null ? -1 : tile.cell(jigsaw.x(), jigsaw.y(), jigsaw.z());
        Map<Integer, Integer> remap = new HashMap<>();
        List<String> palette = new ArrayList<>();
        int blocks = 0;
        for (int cell = 0; cell < keep.length; cell++) {
            if (!keep[cell] || cell == jigsawCell) continue;
            blocks++;
            remap.computeIfAbsent(tile.cells[cell] & 0xFFFF, index -> {
                palette.add(tile.palette.get(index));
                return palette.size() - 1;
            });
        }
        if (jigsaw != null) {
            blocks++;
            palette.add(jigsaw.state());
        }
        List<Tile.EntityData> entities = entitiesToWrite(tile, cutouts, markers, options, ox, oz);
        try (TemplateWriter writer = new TemplateWriter(file, tile.sizeX, tile.sizeY, tile.sizeZ, palette, blocks)) {
            for (int y = 0; y < tile.sizeY; y++) {
                for (int z = 0; z < tile.sizeZ; z++) {
                    for (int x = 0; x < tile.sizeX; x++) {
                        int cell = tile.cell(x, y, z);
                        if (cell == jigsawCell) writer.block(x, y, z, palette.size() - 1, jigsaw.nbt());
                        else if (keep[cell]) writer.block(x, y, z, remap.get(tile.cells[cell] & 0xFFFF),
                                blockEntity(tile.blockEntities.get(cell), ox + x, y, oz + z, markers, options));
                    }
                }
            }
            writer.startEntities(entities.size());
            for (Tile.EntityData entity : entities) writer.entity(entity.x(), entity.y(), entity.z(), entityData(entity.tag(), options));
            writer.finish(SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        }
    }

    /**
     * A block entity as published: a container marked with a loot table is emptied and names its table (vanilla
     * fills it when it is first opened, with a seed of its own for every placement), a custom spawner gets its mobs
     * and timings. {@code x}, {@code y}, {@code z} are the cell in the structure.
     */
    @Nullable
    static CompoundTag blockEntity(@Nullable CompoundTag original, int x, int y, int z, Markers markers, StructureProject.SaveOptions options) {
        if (original == null || !options.blockEntities) return null;
        Markers.Container container = options.loot ? markers.container(x, y, z) : null;
        if (container != null && container.usesTable()) {
            CompoundTag data = original.copy();
            data.remove("Items");
            data.remove("item");
            data.remove("LootTableSeed");
            data.putString("LootTable", container.table);
            return data;
        }
        Markers.Spawner spawner = options.spawners ? markers.spawner(x, y, z) : null;
        if (spawner != null && spawner.custom()) {
            CompoundTag data = original.copy();
            ListTag potentials = new ListTag();
            for (Markers.Mob mob : spawner.mobs) {
                CompoundTag entry = new CompoundTag();
                entry.putInt("weight", mob.weight());
                entry.put("data", spawnData(mob.id()));
                potentials.add(entry);
            }
            data.put("SpawnPotentials", potentials);
            data.put("SpawnData", spawnData(spawner.mobs.get(0).id()));
            data.putShort("Delay", (short) 20);
            data.putShort("MinSpawnDelay", (short) spawner.minDelay);
            data.putShort("MaxSpawnDelay", (short) spawner.maxDelay);
            data.putShort("SpawnCount", (short) spawner.count);
            data.putShort("MaxNearbyEntities", (short) spawner.maxNearby);
            data.putShort("RequiredPlayerRange", (short) spawner.playerRange);
            data.putShort("SpawnRange", (short) spawner.spawnRange);
            return data;
        }
        return original;
    }

    private static CompoundTag spawnData(String entity) {
        CompoundTag id = new CompoundTag();
        id.putString("id", entity);
        CompoundTag data = new CompoundTag();
        data.put("entity", id);
        return data;
    }

    /** Keys that tie an entity to players, places or other entities of the world it was scanned in. */
    private static final List<String> TIES = List.of("Owner", "Leash", "leash", "Gossips", "Brain", "AngryAt", "Trusted", "LoveCause", "ConversionPlayer");

    /** An entity as published: without its ties to the source world when asked, and persistent when asked. */
    static CompoundTag entityData(CompoundTag original, StructureProject.SaveOptions options) {
        if (!options.cleanEntities && !options.persistent) return original;
        CompoundTag data = original.copy();
        if (options.cleanEntities) TIES.forEach(data::remove);
        // Only mobs read this; other entities ignore it.
        if (options.persistent) data.putBoolean("PersistenceRequired", true);
        return data;
    }

    /** A template pool holding one template, rigid, with the empty pool as fallback. */
    private static JsonObject pool(String template) {
        JsonObject element = new JsonObject();
        element.addProperty("element_type", "minecraft:single_pool_element");
        element.addProperty("location", template);
        element.addProperty("projection", "rigid");
        element.addProperty("processors", "minecraft:empty");
        JsonObject entry = new JsonObject();
        entry.addProperty("weight", 1);
        entry.add("element", element);
        JsonArray elements = new JsonArray();
        elements.add(entry);
        JsonObject pool = new JsonObject();
        pool.addProperty("fallback", EMPTY);
        pool.add("elements", elements);
        return pool;
    }

    /**
     * The jigsaw structure. Vanilla puts a start piece's bottom one block below the first free block of the
     * ground, plus the start height; so {@code 1 - embed} sets the draft's ground layer onto the surface. A medium
     * structure starts at its frame's anchor, at the bottom of the frame, which leaves the height the same. The
     * maximum distance only limits pieces outside the start piece, which neither tier has.
     */
    private static JsonObject structure(StructureProject project) {
        StructureProject.Generation generation = project.generation;
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:jigsaw");
        json.addProperty("biomes", "#" + project.namespace() + ":has_structure/" + project.path());
        json.addProperty("step", generation.step);
        json.add("spawn_overrides", new JsonObject());
        json.addProperty("terrain_adaptation", generation.terrain);
        json.addProperty("start_pool", project.id + "/start");
        if ("medium".equals(project.tier())) json.addProperty("start_jigsaw_name", project.id + "/anchor");
        json.addProperty("size", 1);
        JsonObject height = new JsonObject();
        boolean surface = "surface".equals(generation.height);
        height.addProperty("absolute", surface ? 1 - project.embed + generation.offset : generation.offset + 1);
        json.add("start_height", height);
        if (surface) json.addProperty("project_start_to_heightmap", "WORLD_SURFACE_WG");
        json.addProperty("max_distance_from_center", 80);
        json.addProperty("use_expansion_hack", false);
        return json;
    }

    /**
     * A mega structure: its tiles, size and the tiles without a template, its height (as the jigsaw tiers place
     * theirs: on the ground lowered by the embedding, or a fixed bottom), the foundation and whether other structures
     * keep out.
     */
    private static JsonObject megaStructure(StructureProject project, List<String> absent) {
        StructureProject.Generation generation = project.generation;
        JsonObject json = new JsonObject();
        json.addProperty("type", "realmcontrol:mega");
        json.addProperty("biomes", "#" + project.namespace() + ":has_structure/" + project.path());
        json.addProperty("step", generation.step);
        json.add("spawn_overrides", new JsonObject());
        json.addProperty("terrain_adaptation", "none");
        json.addProperty("tiles", project.id);
        JsonArray size = new JsonArray();
        for (int value : project.size) size.add(value);
        json.add("size", size);
        JsonArray missing = new JsonArray();
        absent.forEach(missing::add);
        json.add("absent", missing);
        json.addProperty("height", generation.height);
        json.addProperty("offset", generation.offset);
        json.addProperty("embed", project.embed);
        json.addProperty("foundation", generation.foundation);
        json.addProperty("foundation_block", generation.foundationBlock);
        json.addProperty("suppress_other_structures", generation.suppressOthers);
        return json;
    }

    /** The structure set; a structure switched off keeps its set with a chance of 0, as the structure panel does. */
    private static JsonObject structureSet(StructureProject project) {
        StructureProject.Generation generation = project.generation;
        JsonObject entry = new JsonObject();
        entry.addProperty("structure", project.id);
        entry.addProperty("weight", 1);
        JsonArray structures = new JsonArray();
        structures.add(entry);
        JsonObject placement = new JsonObject();
        boolean mega = "mega".equals(project.tier());
        placement.addProperty("type", mega ? "realmcontrol:mega_spread" : "minecraft:random_spread");
        if (mega) {
            JsonArray size = new JsonArray();
            size.add(project.size[0]);
            size.add(project.size[2]);
            placement.add("size", size);
        }
        placement.addProperty("spacing", generation.spacing);
        placement.addProperty("separation", generation.separation);
        placement.addProperty("salt", generation.salt);
        float frequency = generation.enabled ? generation.frequency : 0F;
        if (frequency < 1F) {
            placement.addProperty("frequency", frequency);
            placement.addProperty("frequency_reduction_method", "default");
        }
        if (!generation.exclusionSet.isBlank()) {
            JsonObject exclusion = new JsonObject();
            exclusion.addProperty("other_set", generation.exclusionSet.toLowerCase(Locale.ROOT));
            exclusion.addProperty("chunk_count", generation.exclusionChunks);
            placement.add("exclusion_zone", exclusion);
        }
        JsonObject json = new JsonObject();
        json.add("structures", structures);
        json.add("placement", placement);
        return json;
    }

    private static JsonObject packMeta(boolean bothFormats) {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "RealmControl structures");
        if (bothFormats) {
            // 1.20.1 reads only pack_format; later versions accept the range.
            JsonObject formats = new JsonObject();
            formats.addProperty("min_inclusive", 15);
            formats.addProperty("max_inclusive", 999);
            pack.add("supported_formats", formats);
        }
        //? if >=26.1 {
        /*net.minecraft.server.packs.metadata.pack.PackFormat format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
        JsonArray version = new JsonArray();
        version.add(format.major());
        version.add(format.minor());
        pack.add("min_format", version);
        pack.add("max_format", version.deepCopy());
        *///?} else {
        pack.addProperty("pack_format", SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA));
        //?}
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        return root;
    }

    private static String templateFolder() {
        //? if >=1.21 {
        /*return "structure";
        *///?} else {
        return "structures";
        //?}
    }

    private static String otherTemplateFolder() {
        //? if >=1.21 {
        /*return "structures";
        *///?} else {
        return "structure";
        //?}
    }

    /** A temporary file beside {@code target}, noted to be moved onto it once everything is written. */
    private static Path staged(Path target, List<Path[]> moves) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        moves.add(new Path[]{temp, target});
        return temp;
    }

    private static void writeJson(Path file, JsonObject json) throws IOException {
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    private static Outcome failure(String key, Object... args) {
        return new Outcome(false, KineticI18n.translatable(key, args));
    }
}
