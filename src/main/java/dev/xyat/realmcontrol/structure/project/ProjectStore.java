package dev.xyat.realmcontrol.structure.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Drafts on disk, under {@code <game directory>/kineticcore/structure_projects/<namespace>/<path>/}: outside
 * {@code config} so they do not travel with a mod pack. Each holds {@code project.json}, {@code cutouts.json},
 * {@code markers.json} and {@code tiles/t_<x>_<z>.bin}. Every file is written to a temporary file first and then moved over the old one.
 */
public final class ProjectStore {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ProjectStore() {
    }

    public static Path root() {
        return KineticPlatform.gameDirectory().resolve("kineticcore").resolve("structure_projects");
    }

    public static Path folder(String id) {
        int colon = id.indexOf(':');
        Path folder = root().resolve(id.substring(0, colon));
        for (String part : id.substring(colon + 1).split("/")) folder = folder.resolve(part);
        return folder;
    }

    public static Path tile(Path tiles, int x, int z) {
        return tiles.resolve("t_" + x + "_" + z + ".bin");
    }

    public static boolean exists(String id) {
        return StructureProject.validId(id) && Files.isRegularFile(folder(id).resolve("project.json"));
    }

    /** Every project with a readable {@code project.json}, by id. */
    public static List<StructureProject> list() {
        List<StructureProject> projects = new ArrayList<>();
        Path root = root();
        if (!Files.isDirectory(root)) return projects;
        try (Stream<Path> files = Files.walk(root, 8)) {
            files.filter(file -> file.getFileName().toString().equals("project.json")).forEach(file -> {
                StructureProject project = read(file);
                if (project != null && StructureProject.validId(project.id) && folder(project.id).equals(file.getParent())) {
                    projects.add(project);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Could not list structure projects in {}", root, e);
        }
        projects.sort(Comparator.comparing(project -> project.id));
        return projects;
    }

    @Nullable
    public static StructureProject load(String id) {
        return exists(id) ? read(folder(id).resolve("project.json")) : null;
    }

    @Nullable
    private static StructureProject read(Path file) {
        try {
            return StructureProject.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read structure project {}", file, e);
            return null;
        }
    }

    public static Cutouts cutouts(String id) {
        Path file = folder(id).resolve("cutouts.json");
        if (!Files.isRegularFile(file)) return new Cutouts();
        try {
            return Cutouts.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read cut-outs {}", file, e);
            return new Cutouts();
        }
    }

    public static Markers markers(String id) {
        Path file = folder(id).resolve("markers.json");
        if (!Files.isRegularFile(file)) return new Markers();
        try {
            return Markers.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read markers {}", file, e);
            return new Markers();
        }
    }

    public static void saveMarkers(String id, Markers markers) throws IOException {
        writeJson(folder(id).resolve("markers.json"), markers.toJson());
    }

    public static void save(StructureProject project) throws IOException {
        writeJson(folder(project.id).resolve("project.json"), project.toJson());
    }

    public static void saveCutouts(String id, Cutouts cutouts) throws IOException {
        writeJson(folder(id).resolve("cutouts.json"), cutouts.toJson());
    }

    @Nullable
    public static Tile readTile(String id, int x, int z) throws IOException {
        Path file = tile(folder(id).resolve("tiles"), x, z);
        return Files.isRegularFile(file) ? Tile.read(file) : null;
    }

    public static void delete(String id) throws IOException {
        if (!StructureProject.validId(id)) return;
        Path folder = folder(id);
        deleteTree(folder);
        // Namespace and path folders left empty go too, up to the drafts folder itself.
        for (Path parent = folder.getParent(); parent != null && parent.startsWith(root()) && !parent.equals(root().getParent()); parent = parent.getParent()) {
            try (Stream<Path> entries = Files.list(parent)) {
                if (entries.findAny().isPresent()) break;
            } catch (IOException e) {
                break;
            }
            Files.deleteIfExists(parent);
        }
    }

    public static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    static void writeJson(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
