package dev.xyat.realmcontrol.structure.project;

import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Previews of drafts: the structure panel's preview service shows {@code project:<id>} by reading the draft's tiles
 * into a snapshot, without cut-outs (the editor draws those itself), coarser when the draft is too big for one.
 */
public final class ProjectPreview {
    public static final String PREFIX = "project:";

    /** A draft's snapshot, its size in blocks and the blocks this game does not know. */
    public record Result(KineticBlockSnapshot snapshot, int tiles, int sizeX, int sizeY, int sizeZ, Map<String, Long> missing) {
    }

    private ProjectPreview() {
    }

    /** Reads a draft for its preview on the calling thread; {@code null} if it does not exist or was cancelled. */
    @Nullable
    public static Result read(String id, Consumer<Float> progress, BooleanSupplier cancelled) throws IOException {
        return read(id, progress, cancelled, false);
    }

    /**
     * As {@link #read(String, Consumer, BooleanSupplier)}; with {@code generated} the cut-outs are left out, as the
     * structure generates (the structure panel shows it so; the editor draws cut-outs itself).
     */
    @Nullable
    public static Result read(String id, Consumer<Float> progress, BooleanSupplier cancelled, boolean generated) throws IOException {
        try (ProjectStore.Draft draft = ProjectStore.openDraft(id)) {
            if (draft == null) return null;
            return read(draft, progress, cancelled, generated);
        }
    }

    private static Result read(ProjectStore.Draft draft, Consumer<Float> progress, BooleanSupplier cancelled,
                               boolean generated) throws IOException {
        StructureProject project = draft.project();
        Cutouts cutouts = generated && project.save.cutouts ? draft.cutouts() : new Cutouts();
        int sizeX = project.size[0], sizeY = project.size[1], sizeZ = project.size[2];
        int factor = KineticBlockSnapshot.factorFor(sizeX, sizeY, sizeZ, KineticBlockSnapshot.MAX_VOLUME);
        KineticBlockSnapshot.Builder builder = KineticBlockSnapshot.builder((sizeX + factor - 1) / factor,
                (sizeY + factor - 1) / factor, (sizeZ + factor - 1) / factor).scale(factor);
        Map<String, Long> missing = new LinkedHashMap<>();
        int tiles = project.tilesX() * project.tilesZ(), read = 0;
        for (int tz = 0; tz < project.tilesZ(); tz++) {
            for (int tx = 0; tx < project.tilesX(); tx++) {
                if (cancelled.getAsBoolean()) return null;
                progress.accept(++read / (float) tiles);
                if (draft.isEmptyTile(tx, tz)) continue;
                Tile tile = draft.readTile(tx, tz);
                if (tile == null) throw new IOException("Missing structure tile " + tx + "," + tz);
                int ox = tx * project.tileSize, oz = tz * project.tileSize;
                BitSet cut = cutouts.tileMask(ox, oz, tile.sizeX, tile.sizeY, tile.sizeZ);
                BlockState[] states = new BlockState[tile.palette.size()];
                boolean unknown = false;
                for (int i = 1; i < states.length; i++) {
                    states[i] = BlockStates.parse(tile.palette.get(i));
                    unknown |= states[i] == null;
                }
                if (factor > 1 && !unknown) {
                    if (!reduceTile(tile, ox, oz, factor, cut, cancelled,
                            (x, y, z, index) -> builder.set(x, y, z, states[index]))) return null;
                } else {
                    // Count every missing source block, even when several fall in the same preview cell.
                    long[] counts = new long[states.length];
                    for (int y = 0; y < tile.sizeY; y++) {
                        if (cancelled.getAsBoolean()) return null;
                        for (int z = 0; z < tile.sizeZ; z++) {
                            for (int x = 0; x < tile.sizeX; x++) {
                                int index = tile.cells[tile.cell(x, y, z)] & 0xFFFF;
                                if (index == 0 || cut.get((x + tile.sizeX * z) * tile.sizeY + y)) continue;
                                int cx = (ox + x) / factor, cy = y / factor, cz = (oz + z) / factor;
                                if (states[index] == null) {
                                    builder.setMissing(cx, cy, cz, tile.palette.get(index));
                                    counts[index]++;
                                } else {
                                    builder.set(cx, cy, cz, states[index]);
                                }
                            }
                        }
                    }
                    for (int i = 1; i < counts.length; i++) {
                        if (counts[i] > 0) missing.merge(BlockStates.blockId(tile.palette.get(i)), counts[i], Long::sum);
                    }
                }
                if (factor == 1) {
                    tile.blockEntities.forEach((cell, data) -> {
                        int x = cell % tile.sizeX, z = (cell / tile.sizeX) % tile.sizeZ, y = cell / (tile.sizeX * tile.sizeZ);
                        builder.blockEntity(ox + x, y, oz + z, data);
                    });
                    for (Tile.EntityData entity : tile.entities) builder.entity(ox + entity.x(), entity.y(), oz + entity.z(), entity.tag());
                }
            }
        }
        builder.containers((int) Math.min(Integer.MAX_VALUE, project.containers));
        if (cancelled.getAsBoolean()) return null;
        if (!missing.equals(project.missing)) ProjectStore.updateIfCurrent(draft, current -> current.missing = missing);
        return new Result(builder.build(), tiles, sizeX, sizeY, sizeZ, missing);
    }

    @FunctionalInterface
    interface SampleCell {
        void set(int x, int y, int z, int index);
    }

    /** The last visible non-air block in scan order represents its reduced cell. Dense cells stop after one read. */
    static boolean reduceTile(Tile tile, int ox, int oz, int factor, BitSet cut, BooleanSupplier cancelled, SampleCell result) {
        int endX = ox + tile.sizeX, endZ = oz + tile.sizeZ;
        for (int y0 = 0; y0 < tile.sizeY; y0 += factor) {
            if (cancelled.getAsBoolean()) return false;
            for (int cz = oz / factor; cz <= (endZ - 1) / factor; cz++) {
                int z0 = Math.max(0, cz * factor - oz), z1 = Math.min(tile.sizeZ, (cz + 1) * factor - oz);
                for (int cx = ox / factor; cx <= (endX - 1) / factor; cx++) {
                    int x0 = Math.max(0, cx * factor - ox), x1 = Math.min(tile.sizeX, (cx + 1) * factor - ox);
                    search:
                    for (int y = Math.min(tile.sizeY, y0 + factor) - 1; y >= y0; y--) {
                        for (int z = z1 - 1; z >= z0; z--) {
                            for (int x = x1 - 1; x >= x0; x--) {
                                int index = tile.cells[tile.cell(x, y, z)] & 0xFFFF;
                                if (index != 0 && !cut.get((x + tile.sizeX * z) * tile.sizeY + y)) {
                                    result.set(cx, y0 / factor, cz, index);
                                    break search;
                                }
                            }
                        }
                    }
                }
            }
        }
        return true;
    }
}
