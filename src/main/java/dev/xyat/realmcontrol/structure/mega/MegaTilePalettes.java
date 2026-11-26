package dev.xyat.realmcontrol.structure.mega;

import dev.xyat.realmcontrol.worldgen.mixin.StructurePaletteInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla runs processors before clipping to the destination chunk. For dense editor tiles its only processor
 * ignores structure blocks without moving any block. Supply just the intersecting records in their original
 * order. Vanilla still rotates states and entities, applies NBT, handles fluids and clips to the world height.
 */
final class MegaTilePalettes {
    private final StructureTemplate.Palette original;
    private volatile Slices last;

    private record Slices(Rotation rotation, List<StructureTemplate.Palette> palettes) {}

    MegaTilePalettes(StructureTemplate.Palette original) {
        this.original = original;
    }

    StructurePlaceSettings settings(BlockPos origin, Rotation rotation, BoundingBox box, int width, int depth) {
        if ((origin.getX() & 15) != 0 || (origin.getZ() & 15) != 0 || (box.minX() & 15) != 0 || (box.minZ() & 15) != 0
                || box.getXSpan() != 16 || box.getZSpan() != 16) return new StructurePlaceSettings();
        MegaLayout.Bounds bounds = MegaLayout.columnsInBox(origin, rotation, box, width, depth);
        if (bounds.minX() >= bounds.endX() || bounds.minZ() >= bounds.endZ()) return new StructurePlaceSettings();
        Slices slices = last;
        if (slices == null || slices.rotation() != rotation) slices = slice(rotation);
        StructureTemplate.Palette chunk = slices.palettes().get(bucket(bounds.minX(), bounds.minZ(), rotation));
        return new StructurePlaceSettings() {
            @Override
            public StructureTemplate.Palette getRandomPalette(List<StructureTemplate.Palette> palettes, @Nullable BlockPos pos) {
                var selected = super.getRandomPalette(palettes, pos);
                return selected == original ? chunk : selected;
            }
        };
    }

    private synchronized Slices slice(Rotation rotation) {
        if (last != null && last.rotation() == rotation) return last;
        List<List<StructureTemplate.StructureBlockInfo>> buckets = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) buckets.add(new ArrayList<>());
        for (var info : original.blocks()) {
            BlockPos pos = info.pos();
            buckets.get(bucket(pos.getX(), pos.getZ(), rotation)).add(info);
        }
        List<StructureTemplate.Palette> palettes = new ArrayList<>(9);
        for (var rows : buckets) palettes.add(StructurePaletteInvoker.realmcontrol$fromBlocks(rows));
        return last = new Slices(rotation, palettes);
    }

    /** A flipped chunk axis splits a tile at 1 and 17 because the origin itself lies in the next chunk. */
    static int bucket(int x, int z, Rotation rotation) {
        boolean flipX = rotation == Rotation.CLOCKWISE_180 || rotation == Rotation.COUNTERCLOCKWISE_90;
        boolean flipZ = rotation == Rotation.CLOCKWISE_180 || rotation == Rotation.CLOCKWISE_90;
        return ((x + (flipX ? 15 : 0)) >> 4) + 3 * ((z + (flipZ ? 15 : 0)) >> 4);
    }
}
