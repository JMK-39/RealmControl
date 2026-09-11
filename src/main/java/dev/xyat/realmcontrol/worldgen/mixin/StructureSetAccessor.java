package dev.xyat.realmcontrol.worldgen.mixin;

import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(StructureSet.class)
public interface StructureSetAccessor {
    @Mutable
    @Accessor("structures")
    void realmcontrol_worldgen$setStructures(List<StructureSet.StructureSelectionEntry> structures);

    @Mutable
    @Accessor("placement")
    void realmcontrol_worldgen$setPlacement(StructurePlacement placement);
}
