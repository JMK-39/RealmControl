package dev.xyat.realmcontrol.structure.project;

import dev.xyat.realmcontrol.structure.selection.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.BitSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

public final class StructureScanRegression {
    public static void main(String[] args) throws IOException {
        selectionBounds();
        scanGenerations();
        markerCarryOver();
        importedStructureDraft();
        System.out.println("StructureScanRegression passed");
    }

    private static void importedStructureDraft() throws IOException {
        Path folder = Files.createTempDirectory("realmcontrol-import-regression");
        try {
            StructureProject project = new StructureProject();
            project.id = "regression:imported";
            project.size = new int[]{130, 3, 2};
            project.tileSize = 64;
            ImportedStructureDraft draft = new ImportedStructureDraft(project);
            draft.putBlock(0, 1, 0, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=true]");
            draft.putBlock(129, 0, 0, "minecraft:chest[facing=north,type=single,waterlogged=false]");
            CompoundTag chest = new CompoundTag();
            chest.putString("id", "minecraft:chest");
            chest.putString("LootTable", "minecraft:chests/simple_dungeon");
            chest.putLong("LootTableSeed", 9007L);
            draft.putBlockEntity(129, 0, 0, chest);
            CompoundTag entity = new CompoundTag();
            entity.putString("id", "minecraft:armor_stand");
            entity.putBoolean("Invisible", true);
            draft.putEntity(128.5, 0.25, 0.75, entity, "Imported stand");
            try (var staged = draft.stage(folder, () -> false)) {
                require(ProjectStore.openDraft(folder) == null, "an import is hidden until it commits");
                require(staged.commit(), "first import installs a fresh state");
            }
            try (var reopened = ProjectStore.openDraft(folder)) {
                require(reopened.project().blocks == 2 && reopened.project().entities == 1 && reopened.project().blockEntities == 1,
                        "import metadata reflects the intact snapshot");
                require(reopened.readTile(0, 0).state(0, 1, 0).contains("waterlogged=true"),
                        "full block and state properties survive the tile boundary");
                require(reopened.isEmptyTile(1, 0), "uncaptured middle tile is explicitly empty");
                Tile last = reopened.readTile(2, 0);
                require(chest.equals(last.blockEntities.get(last.cell(1, 0, 0))), "chest loot id and seed are kept verbatim");
                require(last.entities.get(0).x() == 0.5 && entity.equals(last.entities.get(0).tag()), "entity retains relative position and full data");
                require(reopened.markers().entities.get(0).name.equals("Imported stand"), "entity is visible in the editor");
                require(reopened.markers().entities.get(0).x == 128.5, "marker uses the draft-relative position");
            }
            StructureProject stale = new StructureProject();
            stale.id = project.id;
            stale.size = new int[]{1, 1, 1};
            ImportedStructureDraft duplicate = new ImportedStructureDraft(stale);
            duplicate.putBlock(0, 0, 0, "minecraft:dirt");
            try (var staged = duplicate.stage(folder, () -> false)) {
                require(!staged.commit(), "a concurrent import may not overwrite an already committed draft");
            }
            try (var original = ProjectStore.openDraft(folder)) {
                require(original.project().size[0] == 130, "the winning draft is unchanged");
            }
            try {
                duplicate.putEntity(1.0, 0.0, 0.0, entity, "");
                throw new AssertionError("outside the draft cannot be dropped silently");
            } catch (IllegalArgumentException expected) {
                // Right boundary is exclusive for entities.
            }
        } finally {
            ProjectStore.deleteTree(folder);
        }
    }

    private static void selectionBounds() {
        for (int width : new int[]{4095, 4096, 4097}) {
            Selection.Box box = Selection.Box.of(new BlockPos(107 + width - 1, 319, 4088), new BlockPos(107, -64, -7));
            require((Selection.problem(box, -64, 320) == null) == (width <= 4096), "width " + width);
            if (width == 4096) require(box.volume() == 6_442_450_944L, "large volume uses long arithmetic");
        }
        require(Selection.problem(Selection.Box.of(new BlockPos(0, -65, 0), BlockPos.ZERO), -64, 320) != null,
                "reject below the build height");
        require(Selection.problem(Selection.Box.of(new BlockPos(0, 320, 0), BlockPos.ZERO), -64, 320) != null,
                "reject the exclusive top");
        require(Selection.problem(Selection.Box.of(new BlockPos(0, 300, -5), BlockPos.ZERO), 0, 256) != null,
                "use the target dimension's build height");
        require(Selection.problem(Selection.Box.of(new BlockPos(Integer.MAX_VALUE, 0, 0),
                new BlockPos(Integer.MIN_VALUE, 0, 0))) != null, "reject an unrepresentable width");
        require(Selection.fromOrigin(new int[]{0, 0, 0}, new int[]{0, 1, 1}) == null, "reject a zero-size draft");
        require(Selection.fromOrigin(new int[]{Integer.MAX_VALUE, 0, 0}, new int[]{2, 1, 1}) == null,
                "reject an overflowing draft source");
        require(Selection.problem(Selection.fromOrigin(new int[]{29_999_999, 0, 0}, new int[]{2, 1, 1})) != null,
                "reject the exclusive horizontal world limit");
        require(!StructureProject.validId("..:outside") && !StructureProject.validId("test:a/./b"),
                "reject path segments that escape or alias another draft");
    }

    private static void scanGenerations() throws IOException {
        Path folder = Files.createTempDirectory("realmcontrol-scan-regression");
        try {
            StructureProject project = new StructureProject();
            project.id = "test:scan";
            project.size = new int[]{96, 1, 1};
            project.tileSize = 32;
            ProjectStore.writeJson(folder.resolve("project.json"), project.toJson());
            Tile original = new Tile(32, 1, 1);
            original.cells[0] = (short) original.index("minecraft:stone");
            original.write(ProjectStore.tile(folder.resolve("tiles"), 0, 0));
            Cutouts originalCutouts = new Cutouts();
            originalCutouts.boxes.add(new Cutouts.Box("original", 0, 0, 0, 0, 0, 0, false));
            ProjectStore.writeJson(folder.resolve("cutouts.json"), originalCutouts.toJson());
            corruptTile(folder);
            try (ProjectStore.Draft before = ProjectStore.openDraft(folder)) {
                require(before != null && !before.isEmptyTile(1, 0), "missing legacy tile is not declared empty");
                require(before.readTile(0, 0).state(0, 0, 0).equals("minecraft:stone"), "legacy RCT1 still reads");
                Path staged = folder.resolve("@scans").resolve(UUID.randomUUID().toString());
                Files.createDirectories(staged);
                Tile replacement = new Tile(32, 1, 1);
                replacement.cells[0] = (short) replacement.index("minecraft:dirt");
                replacement.write(ProjectStore.tile(staged.resolve("tiles"), 0, 0));
                project.blocks = 6_442_450_944L;
                project.missing.put("example:missing", 2_147_483_648L);
                BitSet empty = new BitSet();
                empty.set(1);
                try (ProjectStore.Draft active = ProjectStore.openDraft(folder)) {
                    require(active.project().blocks == 0, "staging a scan does not overwrite existing metadata");
                }
                // Block the metadata write as a disk failure while the old draft remains readable.
                Files.createDirectory(staged.resolve("markers.json.tmp"));
                try {
                    ProjectStore.prepareScan(staged, new Cutouts(), new Markers(), empty);
                    throw new AssertionError("blocked scan staging should fail");
                } catch (IOException expected) {
                    try (ProjectStore.Draft current = ProjectStore.openDraft(folder)) {
                        require(current.readTile(0, 0).state(0, 0, 0).equals("minecraft:stone"),
                                "failed metadata staging keeps the old scan");
                    }
                }
                Files.delete(staged.resolve("markers.json.tmp"));
                ProjectStore.prepareScan(staged, new Cutouts(), new Markers(), empty);
                try (ProjectStore.Draft installing = ProjectStore.openDraft(folder)) {
                    require(ProjectStore.beginInstall(installing), "current draft can install");
                    require(!ProjectStore.activateScan(staged, project), "rescan yields while live files are installed");
                    require(!ProjectStore.beginInstall(before), "one pack commit per draft");
                }
                require(ProjectStore.activateScan(staged, project), "rescan resumes on the next tick");
                require(!ProjectStore.beginInstall(before), "retired output must never reach the live pack");
                require(before.readTile(0, 0).state(0, 0, 0).equals("minecraft:stone"), "pin retains previous tile generation");
                require(before.cutouts().count() == 1, "pin retains previous cut-outs");
                require(!ProjectStore.updateIfCurrent(before, latest -> latest.name = "stale"), "stale update is rejected");
                try (ProjectStore.Draft after = ProjectStore.openDraft(folder)) {
                    require(after.project().blocks == 6_442_450_944L, "wide block count round-trips");
                    require(after.project().missing.get("example:missing") == 2_147_483_648L, "wide missing count round-trips");
                    require(after.readTile(0, 0).state(0, 0, 0).equals("minecraft:dirt"), "active generation changes together");
                    require(after.cutouts().count() == 0, "new generation gets its own cut-outs");
                    require(after.isEmptyTile(1, 0) && after.readTile(1, 0) == null, "known empty tile does not require an RCT1 file");
                    require(!after.isEmptyTile(2, 0) && after.readTile(2, 0) == null, "missing content differs from known empty");
                    require(ProjectStore.updateIfCurrent(after, latest -> latest.name = "accepted"), "current update succeeds");
                }
            }
            try (ProjectStore.Draft reopened = ProjectStore.openDraft(folder)) {
                require(reopened.project().name.equals("accepted"), "current update persists on disk");
            }
        } finally {
            ProjectStore.deleteTree(folder);
        }
    }

    private static void corruptTile(Path folder) throws IOException {
        Path file = folder.resolve("corrupt.bin");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) {
            out.writeInt(0x52435431);
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(1);
            out.writeInt(1);
            out.writeUTF("minecraft:air");
            out.writeByte(0);
            out.writeInt(1);
        }
        try {
            Tile.read(file);
            throw new AssertionError("zero packed width was accepted");
        } catch (IOException expected) {
            require(expected.getMessage().contains("palette width"), "bad RCT1 metadata reports its cause");
        }
    }

    private static void markerCarryOver() {
        Markers old = new Markers();
        Markers current = new Markers();
        final int markers = 1024;
        for (int x = 0; x < markers; x++) {
            old.containers.add(container(x, "minecraft:chest", "previous_" + x));
            current.containers.add(container(x, "minecraft:chest", ""));
            old.spawners.add(spawner(x, "minecraft:spawner", x));
            current.spawners.add(spawner(x, "minecraft:spawner", -1));
        }
        // The first entry for a coordinate wins, even if a later duplicate has a matching block id.
        old.containers.add(0, container(3, "minecraft:barrel", "other_block"));
        old.containers.add(0, container(5, "minecraft:chest", "first_table"));
        old.spawners.add(0, spawner(7, "example:other", 36));
        old.spawners.add(0, spawner(9, "minecraft:spawner", 37));
        old.containers = linearReads(old.containers);
        old.spawners = linearReads(old.spawners);
        current.carryOver(old);
        require(current.containers.get(3).table.isEmpty(), "incompatible first container does not fall through");
        require(current.containers.get(5).table.equals("first_table"), "first duplicate supplies the table");
        require(current.spawners.get(7).count == -1, "incompatible first spawner does not fall through");
        require(current.spawners.get(9).count == 37, "first duplicate supplies spawner settings");
        require(current.containers.get(markers - 1).table.equals("previous_1023"), "container metadata survives at the far end");
        require(current.spawners.get(markers - 1).count == 1023, "spawner metadata survives at the far end");
    }

    private static Markers.Container container(int x, String block, String table) {
        Markers.Container container = new Markers.Container();
        container.x = x;
        container.block = block;
        container.table = table;
        return container;
    }

    private static Markers.Spawner spawner(int x, String block, int count) {
        Markers.Spawner spawner = new Markers.Spawner();
        spawner.x = x;
        spawner.block = block;
        spawner.count = count;
        return spawner;
    }

    private static <T> List<T> linearReads(List<T> values) {
        return new AbstractList<>() {
            private int reads;

            @Override
            public T get(int index) {
                require(++reads <= 2 * values.size(), "rescan marker matching reads old markers repeatedly");
                return values.get(index);
            }

            @Override
            public int size() {
                return values.size();
            }
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
