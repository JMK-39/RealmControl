package dev.xyat.realmcontrol.structure.project;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.realmcontrol.structure.selection.Selection;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drafts on disk, under {@code <game directory>/kineticcore/structure_projects/<namespace>/<path>/}: outside
 * {@code config} so they do not travel with a mod pack. The project JSON points to a completed scan containing its
 * tiles, markers, cut-outs and empty-tile index. Legacy drafts have those files next to the project JSON.
 */
public final class ProjectStore {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String SCANS = "@scans";
    private static final Map<Path, Integer> READERS = new HashMap<>();
    private static final Map<Path, Long> REVISIONS = new HashMap<>();
    private static final Map<Path, Draft> INSTALLING = new HashMap<>();
    private static final Map<Path, Path> RETIRED = new HashMap<>();
    private static final ExecutorService CLEANER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RealmControl retired structure scans");
        thread.setDaemon(true);
        return thread;
    });

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
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 8, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                    return directory.getFileName().toString().equals(SCANS)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals("project.json")) {
                        StructureProject project = read(file);
                        if (project != null && StructureProject.validId(project.id) && folder(project.id).equals(file.getParent())) {
                            projects.add(project);
                        }
                    }
                    return FileVisitResult.CONTINUE;
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
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            StructureProject project = StructureProject.fromJson(json);
            project.scanGeneration = StructureProject.stringOf(json, "scan_generation", "");
            if (!project.scanGeneration.isEmpty() && !UUID.fromString(project.scanGeneration).toString().equals(project.scanGeneration)) {
                throw new IOException("Invalid scan generation");
            }
            return project;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read structure project {}", file, e);
            return null;
        }
    }

    public static synchronized Cutouts cutouts(String id) {
        StructureProject project = load(id);
        return project == null ? new Cutouts() : cutouts(project);
    }

    static Cutouts cutouts(StructureProject project) {
        return readCutouts(scanFolder(folder(project.id), project));
    }

    private static Cutouts readCutouts(Path scan) {
        Path file = scan.resolve("cutouts.json");
        if (!Files.isRegularFile(file)) return new Cutouts();
        try {
            return Cutouts.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read cut-outs {}", file, e);
            return new Cutouts();
        }
    }

    public static synchronized Markers markers(String id) {
        StructureProject project = load(id);
        return project == null ? new Markers() : markers(project);
    }

    static Markers markers(StructureProject project) {
        return readMarkers(scanFolder(folder(project.id), project));
    }

    private static Markers readMarkers(Path scan) {
        Path file = scan.resolve("markers.json");
        if (!Files.isRegularFile(file)) return new Markers();
        try {
            return Markers.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read markers {}", file, e);
            return new Markers();
        }
    }

    public static synchronized void saveMarkers(String id, Markers markers) throws IOException {
        StructureProject project = load(id);
        if (project == null) throw new IOException("No draft for " + id);
        Path folder = folder(id), scan = scanFolder(folder, project);
        requireEditable(folder);
        writeJson(scan.resolve("markers.json"), markers.toJson());
        changed(scan);
    }

    public static synchronized void save(StructureProject project) throws IOException {
        Path folder = folder(project.id);
        requireEditable(folder);
        Path document = folder.resolve("project.json");
        StructureProject current = read(document);
        boolean currentScan = current == null
                ? project.scanGeneration.isEmpty() && !Files.exists(document) && !RETIRED.containsKey(folder)
                : project.scanGeneration.equals(current.scanGeneration);
        if (!currentScan) {
            throw new IOException("The scan of " + project.id + " has changed");
        }
        writeProject(folder, project);
        changed(scanFolder(folder, project));
    }

    public static synchronized void saveCutouts(String id, Cutouts cutouts) throws IOException {
        StructureProject project = load(id);
        if (project == null) throw new IOException("No draft for " + id);
        Path folder = folder(id), scan = scanFolder(folder, project);
        requireEditable(folder);
        writeJson(scan.resolve("cutouts.json"), cutouts.toJson());
        changed(scan);
    }

    /** Read one tile, synthesizing air for callers of the legacy single-tile API. */
    @Nullable
    public static Tile readTile(String id, int x, int z) throws IOException {
        try (Draft draft = openDraft(id)) {
            if (draft == null) return null;
            if (draft.isEmptyTile(x, z)) return new Tile(draft.tileWidth(x), draft.project.size[1], draft.tileDepth(z));
            return draft.readTile(x, z);
        }
    }

    /** Pins the active scan so subsequent rescans cannot replace or remove its tile files. */
    @Nullable
    public static Draft openDraft(String id) throws IOException {
        return StructureProject.validId(id) ? openDraft(folder(id)) : null;
    }

    @Nullable
    static synchronized Draft openDraft(Path folder) throws IOException {
        StructureProject project = read(folder.resolve("project.json"));
        if (project == null) return null;
        if (Selection.problem(Selection.fromOrigin(project.origin, project.size)) != null) {
            throw new IOException("Invalid bounds for draft " + project.id);
        }
        Path scan = scanFolder(folder, project);
        BitSet empty = new BitSet();
        if (!project.scanGeneration.isEmpty()) {
            Path index = scan.resolve("empty-tiles.bin");
            int tiles = project.tilesX() * project.tilesZ();
            if (Files.size(index) > (tiles + 7) / 8) throw new IOException("Invalid tile index for " + project.id);
            empty = BitSet.valueOf(Files.readAllBytes(index));
            if (empty.length() > tiles) throw new IOException("Invalid tile index for " + project.id);
        }
        READERS.merge(scan, 1, Integer::sum);
        return new Draft(folder, scan, project, empty, REVISIONS.computeIfAbsent(scan, unused -> 0L));
    }

    public static final class Draft implements AutoCloseable {
        private final Path folder, scan;
        private final StructureProject project;
        private final BitSet empty;
        private final long revision;
        private boolean closed;

        private Draft(Path folder, Path scan, StructureProject project, BitSet empty, long revision) {
            this.revision = revision;
            this.folder = folder;
            this.scan = scan;
            this.project = project;
            this.empty = empty;
        }

        public StructureProject project() { return project; }

        public Cutouts cutouts() { return readCutouts(scan); }

        public Markers markers() { return readMarkers(scan); }

        public boolean isEmptyTile(int x, int z) {
            return containsTile(x, z) && empty.get(z * project.tilesX() + x);
        }

        @Nullable
        public Tile readTile(int x, int z) throws IOException {
            if (!containsTile(x, z) || isEmptyTile(x, z)) return null;
            Path file = tile(scan.resolve("tiles"), x, z);
            if (!Files.isRegularFile(file)) return null;
            Tile loaded = Tile.read(file);
            if (loaded.sizeX != tileWidth(x) || loaded.sizeY != project.size[1] || loaded.sizeZ != tileDepth(z)) {
                throw new IOException("Tile bounds do not match draft: " + file);
            }
            return loaded;
        }

        private boolean containsTile(int x, int z) {
            return x >= 0 && x < project.tilesX() && z >= 0 && z < project.tilesZ();
        }

        private int tileWidth(int x) { return Math.min(project.tileSize, project.size[0] - x * project.tileSize); }

        private int tileDepth(int z) { return Math.min(project.tileSize, project.size[2] - z * project.tileSize); }

        @Override
        public void close() {
            synchronized (ProjectStore.class) {
                if (closed) return;
                closed = true;
                INSTALLING.remove(folder, this);
                int readers = READERS.get(scan) - 1;
                if (readers == 0) {
                    READERS.remove(scan);
                    REVISIONS.remove(scan);
                    Path retired = RETIRED.remove(scan);
                    if (retired != null) removeRetired(scan, retired);
                } else {
                    READERS.put(scan, readers);
                }
            }
        }
    }

    /** Refuse stale staged output before moving any files. The lease ends when the pinned draft closes. */
    public static synchronized boolean beginInstall(Draft draft) {
        if (!isCurrent(draft) || INSTALLING.containsKey(draft.folder)) return false;
        INSTALLING.put(draft.folder, draft);
        return true;
    }

    private static boolean isCurrent(Draft draft) {
        if (draft.closed || !Long.valueOf(draft.revision).equals(REVISIONS.get(draft.scan))) return false;
        StructureProject current = read(draft.folder.resolve("project.json"));
        return current != null && current.scanGeneration.equals(draft.project.scanGeneration);
    }

    private static void requireEditable(Path folder) throws IOException {
        if (INSTALLING.containsKey(folder)) throw new IOException("Structure publication is finishing");
    }

    private static void changed(Path scan) {
        REVISIONS.computeIfPresent(scan, (key, value) -> value + 1);
    }

    @FunctionalInterface
    public interface DraftUpdate {
        void apply(StructureProject current) throws IOException;
    }

    /** Apply a small metadata change if this is still the active scan. The callback runs under the commit lock. */
    public static synchronized boolean updateIfCurrent(Draft draft, DraftUpdate update) throws IOException {
        if (draft.closed || !Long.valueOf(draft.revision).equals(REVISIONS.get(draft.scan))) return false;
        StructureProject current = read(draft.folder.resolve("project.json"));
        if (current == null || !current.scanGeneration.equals(draft.project.scanGeneration)) return false;
        update.apply(current);
        writeProject(draft.folder, current);
        return true;
    }

    static Path newScan(String id) {
        return folder(id).resolve(SCANS).resolve(UUID.randomUUID().toString());
    }

    private static Path scanFolder(Path folder, StructureProject project) {
        return project.scanGeneration.isEmpty() ? folder : folder.resolve(SCANS).resolve(project.scanGeneration);
    }

    static void prepareScan(Path scan, Cutouts cutouts, Markers markers, BitSet empty) throws IOException {
        writeJson(scan.resolve("cutouts.json"), cutouts.toJson());
        writeJson(scan.resolve("markers.json"), markers.toJson());
        Files.write(scan.resolve("empty-tiles.bin"), empty.toByteArray());
    }

    static synchronized boolean activateScan(Path scan, StructureProject project) throws IOException {
        Path folder = scan.getParent().getParent();
        if (INSTALLING.containsKey(folder)) return false;
        StructureProject previous = read(folder.resolve("project.json"));
        if (previous == null && !project.scanGeneration.isEmpty()) throw new IOException("The draft " + project.id + " was deleted");
        if (previous != null) {
            if (!project.scanGeneration.equals(previous.scanGeneration)) throw new IOException("The scan of " + project.id + " has changed");
            // A publish may have finished during the scan. Retain its destination while marking its contents stale.
            project.publishedAt = previous.publishedAt;
            project.publishedRoot = previous.publishedRoot;
            if (!"draft".equals(previous.status)) project.status = "modified";
        }
        project.scanGeneration = scan.getFileName().toString();
        writeProject(folder, project);
        if (previous != null) {
            Path outdated = scanFolder(folder, previous);
            retire(outdated, previous.scanGeneration.isEmpty() ? outdated.resolve("tiles") : outdated);
        }
        return true;
    }

    private static void writeProject(Path folder, StructureProject project) throws IOException {
        JsonObject json = project.toJson();
        if (!project.scanGeneration.isEmpty()) json.addProperty("scan_generation", project.scanGeneration);
        writeJson(folder.resolve("project.json"), json);
    }

    private static void retire(Path scan, Path files) {
        if (READERS.containsKey(scan)) RETIRED.put(scan, files);
        else removeRetired(scan, files);
    }

    private static void removeRetired(Path scan, Path files) {
        if (scan.equals(files)) {
            removeInBackground(files);
            return;
        }
        // A legacy path can be reused by another legacy writer. Detach it before cleaning up off-thread.
        try {
            if (Files.exists(files)) {
                Path abandoned = scan.resolve(SCANS).resolve(UUID.randomUUID().toString());
                Files.createDirectories(abandoned.getParent());
                Files.move(files, abandoned, StandardCopyOption.ATOMIC_MOVE);
                removeInBackground(abandoned);
            }
            Files.deleteIfExists(scan.resolve("markers.json"));
            Files.deleteIfExists(scan.resolve("cutouts.json"));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not retire legacy structure tiles {}", files, e);
        }
    }

    static void removeInBackground(Path files) {
        CLEANER.execute(() -> {
            try {
                deleteTree(files);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not clean structure scan {}", files, e);
            }
        });
    }

    public static synchronized void delete(String id) throws IOException {
        if (!StructureProject.validId(id)) return;
        Path folder = folder(id);
        requireEditable(folder);
        StructureProject project = load(id);
        Files.deleteIfExists(folder.resolve("project.json"));
        if (project != null && !project.scanGeneration.isEmpty()) {
            Path scan = scanFolder(folder, project);
            retire(scan, scan);
        }
        retire(folder, folder.resolve("tiles"));
    }

    public static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                // The same retired scan may already be disappearing in another cleanup.
                if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw error;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException error) throws IOException {
                if (error != null && !(error instanceof NoSuchFileException)) throw error;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void writeJson(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
