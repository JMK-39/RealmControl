package dev.xyat.realmcontrol.worldgen.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
    @Accessor("locateOffset")
    Vec3i realmcontrol_worldgen$getLocateOffset();

    @Accessor("frequencyReductionMethod")
    StructurePlacement.FrequencyReductionMethod realmcontrol_worldgen$getFrequencyReductionMethod();

    @Accessor("frequency")
    float realmcontrol_worldgen$getFrequency();

    @Mutable
    @Accessor("frequency")
    void realmcontrol_worldgen$setFrequency(float frequency);

    @Accessor("salt")
    int realmcontrol_worldgen$getSalt();

    @Accessor("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> realmcontrol_worldgen$getExclusionZone();
}
