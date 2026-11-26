package dev.xyat.realmcontrol.structure.publish;

import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.StructureProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

/** Publication retains the previous files and metadata until every change succeeds. */
public final class PublishFilesRegression {
    public static void run() throws IOException {
        Path root = Files.createTempDirectory("realm-publish-files");
        try {
            replacementFailure(root.resolve("blocked"));
            sparseRepublish(root.resolve("sparse"));
            zipRollback(root.resolve("zip"));
        } finally {
            ProjectStore.deleteTree(root);
        }
    }

    private static void replacementFailure(Path root) throws IOException {
        Files.createDirectories(root);
        Path existing = root.resolve("old.json"), added = root.resolve("new.nbt"), blocked = root.resolve("blocked");
        Files.writeString(existing, "old");
        Files.createDirectory(blocked);
        List<Path[]> moves = new ArrayList<>();
        try {
            stage(existing, "replaced", moves);
            stage(added, "added", moves);
            stage(blocked, "cannot install", moves);
            expectFailure(() -> Publisher.install(moves, List.of(), () -> { throw new AssertionError("metadata ran after a failed replacement"); }));
            require(Files.readString(existing).equals("old") && !Files.exists(added) && Files.isDirectory(blocked),
                    "partial pack survived a replacement failure");
        } finally {
            Publisher.discard(moves);
        }
        assertStagingRemoved(root);
    }

    private static void sparseRepublish(Path root) throws IOException {
        Path data = root.resolve("data/realmcontrol");
        Path definition = data.resolve("worldgen/structure/city.json"), metadata = root.resolve("project.json");
        Path tile = data.resolve("structures/city/t_0_0.nbt"), stale = data.resolve("structures/city/t_1_0.nbt");
        Path blocked = data.resolve("structure/city/t_2_0.nbt"), next = data.resolve("structures/city/t_3_0.nbt");
        Files.createDirectories(definition.getParent());
        Files.createDirectories(tile.getParent());
        Files.createDirectories(blocked);
        Files.writeString(tile, "old tile");
        Files.writeString(stale, "removed tile");
        Files.writeString(definition, "{\"absent\":[\"2_0\"]}");
        Files.writeString(metadata, "first publish");
        List<Path[]> moves = new ArrayList<>();
        try {
            stage(tile, "new tile", moves);
            stage(next, "new sparse tile", moves);
            stage(definition, "{\"absent\":[\"1_0\",\"2_0\"]}", moves);
            expectFailure(() -> Publisher.install(moves, List.of(stale, blocked), () -> {
                throw new AssertionError("metadata ran after a failed stale removal");
            }));
            assertOldPublish(definition, metadata, tile, stale, next);
            require(Files.isDirectory(blocked), "stale removal must never delete a directory target");
        } finally {
            Publisher.discard(moves);
            moves.clear();
        }
        Files.delete(blocked);

        try {
            stage(tile, "new tile", moves);
            stage(next, "new sparse tile", moves);
            stage(definition, "{\"absent\":[\"1_0\",\"2_0\"]}", moves);
            Path blockedMetadata = root.resolve("project.json.tmp");
            Files.createDirectory(blockedMetadata);
            expectFailure(() -> Publisher.installPack(data, "city", moves, () -> {
                require(!Files.exists(stale), "stale tiles move before metadata finalization");
                Files.writeString(blockedMetadata, "second publish");
            }));
            assertOldPublish(definition, metadata, tile, stale, next);
            Files.delete(blockedMetadata);
        } finally {
            Publisher.discard(moves);
            moves.clear();
        }

        try {
            stage(tile, "new tile", moves);
            stage(next, "new sparse tile", moves);
            stage(definition, "{\"absent\":[\"1_0\",\"2_0\"]}", moves);
            Publisher.installPack(data, "city", moves, () -> {
                Path prepared = root.resolve("project.json.tmp");
                Files.writeString(prepared, "second publish");
                Files.move(prepared, metadata, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            });
            require(Files.readString(metadata).equals("second publish") && !Files.exists(stale), "sparse repeat publish did not commit");
            require(Files.readString(tile).equals("new tile") && Files.readString(next).equals("new sparse tile"),
                    "sparse repeat publish did not install new tile data");
            // A manually restored orphan must stay out when editing generation settings.
            Files.writeString(stale, "orphan");
            StructureProject city = new StructureProject();
            city.id = "realmcontrol:city";
            city.size = new int[]{288, 1, 32};
            city.tileSize = 32;
            require(Publisher.publishedAbsent(city, data).equals(List.of("1_0", "2_0")), "explicit absent must override orphan files");
            Files.writeString(definition, "{\"absent\":[]}");
            require(Publisher.publishedAbsent(city, data).isEmpty(), "explicit empty absent must remain authoritative");
            Files.writeString(definition, "{}");
            Path modern = data.resolve("structure/city/t_1_0.nbt");
            Files.createDirectories(modern.getParent());
            Files.writeString(modern, "legacy modern template");
            require(!Publisher.publishedAbsent(city, data).contains("1_0") && Publisher.publishedAbsent(city, data).contains("2_0"),
                    "legacy definitions lacking absent infer it from existing tiles");
        } finally {
            Publisher.discard(moves);
        }
        assertStagingRemoved(root);
    }

    private static void assertOldPublish(Path definition, Path metadata, Path tile, Path stale, Path next) throws IOException {
        require(Files.readString(metadata).equals("first publish"), "failed repeat publish changed draft metadata");
        require(Files.readString(definition).equals("{\"absent\":[\"2_0\"]}"), "failed repeat publish changed its definition");
        require(Files.readString(tile).equals("old tile") && Files.readString(stale).equals("removed tile") && !Files.exists(next),
                "failed repeat publish did not restore both prior tiles");
    }

    private static void zipRollback(Path root) throws IOException {
        Path pack = root.resolve("pack"), archive = root.resolve("city.zip");
        List<Path[]> previousPack = new ArrayList<>(), nextPack = new ArrayList<>(), archiveMove = new ArrayList<>();
        try {
            Path entry = pack.resolve("data/realmcontrol/structure/city.nbt");
            stage(entry, "previous ZIP", previousPack);
            Publisher.writeArchive(pack, archive, previousPack);
            byte[] previous = Files.readAllBytes(archive);
            stage(entry, "replacement ZIP", nextPack);
            Publisher.writeArchive(pack, Publisher.staged(archive, archiveMove), nextPack);
            expectFailure(() -> Publisher.install(archiveMove, List.of(), () -> { throw new IOException("metadata write refused"); }));
            require(java.util.Arrays.equals(Files.readAllBytes(archive), previous), "failed ZIP publish changed the existing archive");
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                require(zip.size() == 1 && new String(zip.getInputStream(zip.getEntry("data/realmcontrol/structure/city.nbt"))
                        .readAllBytes(), StandardCharsets.UTF_8).equals("previous ZIP"), "ZIP entry list includes staging files or lost original data");
            }
        } finally {
            Publisher.discard(previousPack);
            Publisher.discard(nextPack);
            Publisher.discard(archiveMove);
        }
        assertStagingRemoved(root);
    }

    private static void stage(Path target, String text, List<Path[]> moves) throws IOException {
        Files.writeString(Publisher.staged(target, moves), text);
    }

    private static void assertStagingRemoved(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            require(files.noneMatch(file -> file.getFileName().toString().startsWith(".realm-")), "publish staging files survived");
        }
    }

    private static void expectFailure(Publisher.Commit task) throws IOException {
        try {
            task.run();
            throw new AssertionError("publication was expected to fail");
        } catch (IOException expected) {
            // The caller verifies the exact retained state.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
