package dev.xyat.realmcontrol.worldgen.mixin;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkGeneratorStructureState.class)
public interface ChunkGeneratorStructureStateAccessor {
    @Accessor("placementsForStructure")
    Map<Structure, List<StructurePlacement>> realmcontrol_worldgen$getPlacementsForStructure();

    @Accessor("ringPositions")
    Map<ConcentricRingsStructurePlacement, CompletableFuture<List<ChunkPos>>> realmcontrol_worldgen$getRingPositions();

    @Accessor("hasGeneratedPositions")
    void realmcontrol_worldgen$setHasGeneratedPositions(boolean value);
}
