package dev.xyat.realmcontrol.structure.project;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Writes a full-resolution synthetic capture to ordinary editable draft tiles. All positions are draft-relative. */
public final class ImportedStructureDraft {
    private final StructureProject project;
    private final Map<Integer, Tile> tiles = new TreeMap<>();
    private final Markers markers = new Markers();

    public ImportedStructureDraft(StructureProject project) {
        this.project = project;
    }

    public void putBlock(int x, int y, int z, String state) {
        if (BlockStates.AIR.equals(state)) return;
        Tile tile = tileAt(x, y, z);
        int cell = cell(tile, x, y, z), paletteIndex = tile.index(state);
        if (paletteIndex == 0) throw new IllegalArgumentException("Captured tile has too many distinct block states");
        if (tile.cells[cell] == 0) project.blocks++;
        tile.cells[cell] = (short) paletteIndex;
    }

    void putBlockEntity(int x, int y, int z, CompoundTag tag) {
        Tile tile = tileAt(x, y, z);
        int cell = cell(tile, x, y, z);
        if (tile.cells[cell] == 0) throw new IllegalArgumentException("Captured block entity has no block");
        if (tile.blockEntities.put(cell, tag) == null) project.blockEntities++;
    }

    public void putBlockEntity(int x, int y, int z, BlockEntity entity, CompoundTag tag) {
        putBlockEntity(x, y, z, tag);
        if (entity instanceof Container) project.containers++;
        ScanService.markBlockEntity(markers, entity, x, y, z, tag);
    }

    public void putEntity(double x, double y, double z, CompoundTag tag, String name) {
        if (!(x >= 0.0 && x < project.size[0] && y >= 0.0 && y < project.size[1] && z >= 0.0 && z < project.size[2])) {
            throw new IllegalArgumentException("Captured entity is outside the draft");
        }
        int bx = (int) x, bz = (int) z;
        Tile tile = tileAt(bx, (int) y, bz);
        tile.entities.add(new Tile.EntityData(x - (bx / project.tileSize) * project.tileSize, y,
                z - (bz / project.tileSize) * project.tileSize, tag));
        project.entities++;
        if (markers.entities.size() < Markers.MAX_ENTITIES) {
            Markers.EntityMark mark = new Markers.EntityMark();
            mark.type = tag.getString("id");
            mark.x = x;
            mark.y = y;
            mark.z = z;
            mark.name = name;
            markers.entities.add(mark);
        }
    }

    private Tile tileAt(int x, int y, int z) {
        if (x < 0 || x >= project.size[0] || y < 0 || y >= project.size[1] || z < 0 || z >= project.size[2]) {
            throw new IllegalArgumentException("Captured block is outside the draft");
        }
        int tileSize = project.tileSize, tx = x / tileSize, tz = z / tileSize;
        return tiles.computeIfAbsent(tz * project.tilesX() + tx, index -> new Tile(
                Math.min(tileSize, project.size[0] - tx * tileSize), project.size[1],
                Math.min(tileSize, project.size[2] - tz * tileSize)));
    }

    private int cell(Tile tile, int x, int y, int z) {
        return tile.cell(x % project.tileSize, y, z % project.tileSize);
    }

    public Staged stage(BooleanSupplier cancelled) throws IOException {
        return write(ProjectStore.newScan(project.id), cancelled);
    }

    Staged stage(Path folder, BooleanSupplier cancelled) throws IOException {
        return write(folder.resolve("@scans").resolve(UUID.randomUUID().toString()), cancelled);
    }

    private Staged write(Path scan, BooleanSupplier cancelled) throws IOException {
        Staged staged = new Staged(scan, project);
        boolean ready = false;
        try {
            int rowSize = project.tilesX();
            BitSet empty = new BitSet(rowSize * project.tilesZ());
            empty.set(0, rowSize * project.tilesZ());
            for (var entry : tiles.entrySet()) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                int index = entry.getKey();
                entry.getValue().write(ProjectStore.tile(scan.resolve("tiles"), index % rowSize, index / rowSize));
                empty.clear(index);
            }
            if (cancelled.getAsBoolean()) throw new CancellationException();
            ProjectStore.prepareScan(scan, new Cutouts(), markers, empty);
            ready = true;
            return staged;
        } finally {
            if (!ready) staged.close();
        }
    }

    public static final class Staged implements AutoCloseable {
        private final Path scan;
        private final StructureProject project;
        private boolean committed;
        private boolean closed;

        private Staged(Path scan, StructureProject project) {
            this.scan = scan;
            this.project = project;
        }

        public synchronized boolean commit() throws IOException {
            if (closed) return false;
            if (!committed) committed = ProjectStore.activateNewScan(scan, project);
            return committed;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            if (!committed) ProjectStore.removeInBackground(scan);
        }
    }
}
