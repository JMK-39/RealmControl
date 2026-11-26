package dev.xyat.realmcontrol.structure.project;

import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import net.minecraft.core.BlockPos;
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
    private static final int WINDOW = 32;

    /** A draft's snapshot, its size in blocks and the blocks this game does not know. */
    public record Result(KineticBlockSnapshot snapshot, int tiles, int sizeX, int sizeY, int sizeZ, Map<String, Long> missing) {
    }

    /** A small 1:1 section around a point in the full draft, expressed in full-draft coordinates. */
    public static BlockPos windowOrigin(StructureProject project, BlockPos center) {
        return new BlockPos(windowAxis(project.size[0], center.getX()), windowAxis(project.size[1], center.getY()),
                windowAxis(project.size[2], center.getZ()));
    }

    private static int windowAxis(int size, int center) {
        return Math.max(0, Math.min(size - WINDOW, center - WINDOW / 2));
    }

    public static String windowId(String id, BlockPos origin) {
        return PREFIX + id + "@" + origin.getX() + "," + origin.getY() + "," + origin.getZ();
    }

    /** Only an exact, in-bounds block coordinate is accepted for an editor focus or a preview window. */
    @Nullable
    public static BlockPos position(String text, StructureProject project) {
        String[] axes = text.split(",", -1);
        if (axes.length != 3) return null;
        try {
            int x = Integer.parseInt(axes[0]), y = Integer.parseInt(axes[1]), z = Integer.parseInt(axes[2]);
            if (x < 0 || y < 0 || z < 0 || x >= project.size[0] || y >= project.size[1] || z >= project.size[2]) return null;
            return new BlockPos(x, y, z);
        } catch (NumberFormatException invalid) {
            return null;
        }
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
        int window = id.indexOf('@');
        try (ProjectStore.Draft draft = ProjectStore.openDraft(window < 0 ? id : id.substring(0, window))) {
            if (draft == null) return null;
            if (window >= 0) {
                BlockPos origin = position(id.substring(window + 1), draft.project());
                return origin == null || generated ? null : readWindow(draft, origin, progress, cancelled);
            }
            return read(draft, progress, cancelled, generated);
        }
    }

    @Nullable
    static Result readWindow(ProjectStore.Draft draft, BlockPos origin, Consumer<Float> progress, BooleanSupplier cancelled) throws IOException {
        StructureProject project = draft.project();
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        int sx = Math.min(WINDOW, project.size[0] - ox), sy = Math.min(WINDOW, project.size[1] - oy), sz = Math.min(WINDOW, project.size[2] - oz);
        KineticBlockSnapshot.Builder builder = KineticBlockSnapshot.builder(sx, sy, sz);
        Cutouts cutouts = draft.cutouts();
        Map<String, Long> missing = new LinkedHashMap<>();
        int size = project.tileSize, startX = ox / size, startZ = oz / size;
        int endX = (ox + sx - 1) / size, endZ = (oz + sz - 1) / size;
        int tiles = (endX - startX + 1) * (endZ - startZ + 1), read = 0;
        for (int tz = startZ; tz <= endZ; tz++) {
            for (int tx = startX; tx <= endX; tx++) {
                if (cancelled.getAsBoolean()) return null;
                progress.accept(++read / (float) tiles);
                if (draft.isEmptyTile(tx, tz)) continue;
                Tile tile = draft.readTile(tx, tz);
                if (tile == null) throw new IOException("Missing structure tile " + tx + "," + tz);
                int baseX = tx * size, baseZ = tz * size;
                BlockState[] palette = new BlockState[tile.palette.size()];
                BitSet parsed = new BitSet(palette.length);
                for (int y = oy; y < oy + sy; y++) {
                    if (cancelled.getAsBoolean()) return null;
                    for (int z = Math.max(oz, baseZ); z < Math.min(oz + sz, baseZ + tile.sizeZ); z++) {
                        for (int x = Math.max(ox, baseX); x < Math.min(ox + sx, baseX + tile.sizeX); x++) {
                            int cell = tile.cell(x - baseX, y, z - baseZ);
                            int index = tile.cells[cell] & 0xFFFF;
                            if (index == 0 || cutouts.isCut(x, y, z)) continue;
                            String name = tile.palette.get(index);
                            if (!parsed.get(index)) {
                                palette[index] = BlockStates.parse(name);
                                parsed.set(index);
                            }
                            if (palette[index] == null) {
                                builder.setMissing(x - ox, y - oy, z - oz, name);
                                missing.merge(BlockStates.blockId(name), 1L, Long::sum);
                            } else {
                                builder.set(x - ox, y - oy, z - oz, palette[index]);
                                var data = tile.blockEntities.get(cell);
                                if (data != null) builder.blockEntity(x - ox, y - oy, z - oz, data);
                            }
                        }
                    }
                }
                for (Tile.EntityData entity : tile.entities) {
                    double x = baseX + entity.x(), y = entity.y(), z = baseZ + entity.z();
                    if (x < ox || x >= ox + sx || y < oy || y >= oy + sy || z < oz || z >= oz + sz
                            || cutouts.isCut((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) continue;
                    builder.entity(x - ox, y - oy, z - oz, entity.tag());
                }
            }
        }
        return new Result(builder.build(), tiles, sx, sy, sz, missing);
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
