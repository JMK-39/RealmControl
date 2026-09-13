package dev.xyat.realmcontrol.worldgen.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;
import java.util.function.Supplier;

@Mixin(BiomeSource.class)
public interface BiomeSourceAccessor {
    @Mutable
    @Accessor("possibleBiomes")
    void realmcontrol$setPossibleBiomes(Supplier<Set<Holder<Biome>>> possibleBiomes);
}
