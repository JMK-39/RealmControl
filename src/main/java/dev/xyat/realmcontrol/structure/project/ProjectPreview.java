package dev.xyat.realmcontrol.structure.project;

import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.io.IOException;
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
    public record Result(KineticBlockSnapshot snapshot, int tiles, int sizeX, int sizeY, int sizeZ, Map<String, Integer> missing) {
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
        StructureProject project = ProjectStore.load(id);
        if (project == null) return null;
        Cutouts cutouts = generated && project.save.cutouts ? ProjectStore.cutouts(id) : null;
        int sizeX = project.size[0], sizeY = project.size[1], sizeZ = project.size[2];
        int factor = KineticBlockSnapshot.factorFor(sizeX, sizeY, sizeZ, KineticBlockSnapshot.MAX_VOLUME);
        KineticBlockSnapshot.Builder builder = KineticBlockSnapshot.builder((sizeX + factor - 1) / factor,
                (sizeY + factor - 1) / factor, (sizeZ + factor - 1) / factor).scale(factor);
        Map<String, Integer> missing = new LinkedHashMap<>();
        int tiles = project.tilesX() * project.tilesZ(), read = 0;
        for (int tz = 0; tz < project.tilesZ(); tz++) {
            for (int tx = 0; tx < project.tilesX(); tx++) {
                if (cancelled.getAsBoolean()) return null;
                Tile tile = ProjectStore.readTile(id, tx, tz);
                progress.accept(++read / (float) tiles);
                if (tile == null) continue;
                int offsetX = tx * project.tileSize, offsetZ = tz * project.tileSize;
                BlockState[] states = new BlockState[tile.palette.size()];
                boolean[] known = new boolean[tile.palette.size()];
                for (int i = 1; i < states.length; i++) {
                    states[i] = BlockStates.parse(tile.palette.get(i));
                    known[i] = states[i] != null;
                }
                for (int y = 0; y < tile.sizeY; y++) {
                    for (int z = 0; z < tile.sizeZ; z++) {
                        for (int x = 0; x < tile.sizeX; x++) {
                            int index = tile.cells[tile.cell(x, y, z)] & 0xFFFF;
                            if (index == 0 || cutouts != null && cutouts.isCut(offsetX + x, y, offsetZ + z)) continue;
                            int cx = (offsetX + x) / factor, cy = y / factor, cz = (offsetZ + z) / factor;
                            if (known[index]) {
                                builder.set(cx, cy, cz, states[index]);
                            } else {
                                builder.setMissing(cx, cy, cz, tile.palette.get(index));
                                missing.merge(BlockStates.blockId(tile.palette.get(index)), 1, Integer::sum);
                            }
                        }
                    }
                }
                if (factor == 1) {
                    tile.blockEntities.forEach((cell, data) -> {
                        int x = cell % tile.sizeX, z = (cell / tile.sizeX) % tile.sizeZ, y = cell / (tile.sizeX * tile.sizeZ);
                        builder.blockEntity(offsetX + x, y, offsetZ + z, data);
                    });
                    for (Tile.EntityData entity : tile.entities) {
                        builder.entity(offsetX + entity.x(), entity.y(), offsetZ + entity.z(), entity.tag());
                    }
                }
            }
        }
        builder.containers(project.containers);
        // The list shows the missing blocks the last preview found.
        if (!missing.equals(project.missing)) {
            project.missing = missing;
            ProjectStore.save(project);
        }
        return new Result(builder.build(), tiles, sizeX, sizeY, sizeZ, missing);
    }
}
