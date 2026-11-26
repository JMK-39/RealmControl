package dev.xyat.realmcontrol.structure.project;

import java.util.BitSet;
import java.util.Random;

/** Compares the tile-local cut-out raster and reduced preview to the original per-cell order. */
public final class GiantDraftRegression {
    public static void main(String[] args) throws Exception {
        dev.xyat.realmcontrol.structure.publish.PublishFilesRegression.run();
        dev.xyat.realmcontrol.structure.mega.MegaTemplateCacheRegression.run();
        Cutouts cuts = new Cutouts();
        cuts.boxes.add(new Cutouts.Box("base", 31, -9, 29, 95, 380, 67, false));
        cuts.boxes.add(new Cutouts.Box("restore", 33, 13, 33, 102, 312, 75, true));
        cuts.boxes.add(new Cutouts.Box("shaft", 49, 128, 63, 51, 383, 65, false));
        cuts.mask.put(Cutouts.column(34, 34), new int[]{12, 200});
        cuts.mask.put(Cutouts.column(68, 56), new int[]{404, 420});
        for (int ox : new int[]{0, 32, 64, 1000, 4064}) {
            for (int oz : new int[]{0, 32, 64, 4064}) {
                int sx = 31, sy = 384, sz = 32;
                BitSet mask = cuts.tileMask(ox, oz, sx, sy, sz);
                for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) for (int y = 0; y < sy; y++) {
                    if (mask.get((x + z * sx) * sy + y) != cuts.isCut(ox + x, y, oz + z)) {
                        throw new AssertionError("cut-out disagreement at " + (ox + x) + "," + y + "," + (oz + z));
                    }
                }
            }
        }
        Random random = new Random(255910L);
        Tile tile = new Tile(31, 36, 32);
        for (int i = 0; i < tile.cells.length; i++) tile.cells[i] = (short) (random.nextInt(5) == 0 ? random.nextInt(8) : 0);
        for (int factor : new int[]{1, 2, 3, 12, 32}) {
            int ox = 32, oz = 32;
            BitSet cut = cuts.tileMask(ox, oz, tile.sizeX, tile.sizeY, tile.sizeZ);
            int[] expected = new int[64 * 36 * 64], actual = expected.clone();
            for (int y = 0; y < tile.sizeY; y++) for (int z = 0; z < tile.sizeZ; z++) for (int x = 0; x < tile.sizeX; x++) {
                int state = tile.cells[tile.cell(x, y, z)];
                if (state != 0 && !cuts.isCut(ox + x,y,oz + z)) {
                    expected[(ox+x)/factor + 64*((oz+z)/factor + 64*(y/factor))] = state;
                }
            }
            if (!ProjectPreview.reduceTile(tile, ox, oz, factor, cut, () -> false,
                    (x,y,z,state) -> actual[x + 64*(z + 64*y)] = state) || !java.util.Arrays.equals(expected, actual)) {
                throw new AssertionError("preview sampling changed at factor " + factor);
            }
            if (ProjectPreview.reduceTile(tile, ox, oz, factor, cut, () -> true, (x,y,z,state) -> {})) {
                throw new AssertionError("preview ignored cancellation");
            }
        }
    }
}
