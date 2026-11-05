package dev.xyat.realmcontrol.structure.client;

import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;

/** Copies of a draft's preview with its cut-outs applied: hidden, or shown as red see-through blocks. */
public final class CutoutSnapshots {
    /** Palette name that marks cut-out cells; the 3D view draws names it does not know in red. */
    private static final String CUT = "realmcontrol:cut_out";

    private CutoutSnapshots() {
    }

    public static KineticBlockSnapshot apply(KineticBlockSnapshot base, Cutouts cutouts, boolean red) {
        if (cutouts.isEmpty()) return base;
        int scale = base.scale();
        KineticBlockSnapshot.Builder builder = KineticBlockSnapshot.builder(base.sizeX(), base.sizeY(), base.sizeZ()).scale(scale);
        int half = scale / 2;
        for (int y = 0; y < base.sizeY(); y++) {
            for (int z = 0; z < base.sizeZ(); z++) {
                for (int x = 0; x < base.sizeX(); x++) {
                    int index = base.index(x, y, z);
                    if (index == 0) continue;
                    if (cutouts.isCut(x * scale + half, y * scale + half, z * scale + half)) {
                        if (red) builder.setMissing(x, y, z, CUT);
                    } else if (base.isMissing(index)) {
                        builder.setMissing(x, y, z, base.paletteName(index));
                    } else {
                        builder.set(x, y, z, base.paletteState(index));
                    }
                }
            }
        }
        for (Map.Entry<Integer, CompoundTag> entry : base.blockEntities().entrySet()) {
            int cell = entry.getKey();
            int x = cell % base.sizeX(), z = (cell / base.sizeX()) % base.sizeZ(), y = cell / (base.sizeX() * base.sizeZ());
            if (!cutouts.isCut(x * scale, y * scale, z * scale)) builder.blockEntity(x, y, z, entry.getValue());
        }
        for (KineticBlockSnapshot.EntityEntry entity : base.entities()) {
            if (!cutouts.isCut((int) Math.floor(entity.x()), (int) Math.floor(entity.y()), (int) Math.floor(entity.z()))) {
                builder.entity(entity.x(), entity.y(), entity.z(), entity.tag());
            }
        }
        builder.containers(base.containerCount());
        return builder.build();
    }
}
