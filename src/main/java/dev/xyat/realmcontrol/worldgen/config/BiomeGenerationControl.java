package dev.xyat.realmcontrol.worldgen.config;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import dev.xyat.realmcontrol.worldgen.mixin.BiomeSourceAccessor;
import dev.xyat.realmcontrol.worldgen.mixin.MultiNoiseBiomeSourceAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BiomeGenerationControl {
    private BiomeGenerationControl() {
    }

    public static void applyBeforeWorldPreparation(MinecraftServer server) {
        if (server == null || !WorldGenConfig.enableBiomeControl || WorldGenConfig.biomeReplacementRules.isEmpty()) {
            return;
        }

        Registry<Biome> biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        Registry<LevelStem> levelStemRegistry = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);

        for (ResourceKey<LevelStem> dimensionKey : levelStemRegistry.registryKeySet()) {
            LevelStem stem = levelStemRegistry.get(dimensionKey);
            if (stem == null) {
                continue;
            }
            BiomeSource source = stem.generator().getBiomeSource();
            if (!(source instanceof MultiNoiseBiomeSource multiNoise)) {
                continue;
            }
            applyToMultiNoiseSource(dimensionKey.location().toString(), multiNoise, biomeRegistry);
        }
    }

    private static void applyToMultiNoiseSource(String dimensionId, MultiNoiseBiomeSource source, Registry<Biome> biomeRegistry) {
        Map<Holder<Biome>, Holder<Biome>> replacements = new LinkedHashMap<>();
        Set<Holder<Biome>> removals = new LinkedHashSet<>();

        for (BiomeReplacementRule rule : WorldGenConfig.biomeReplacementRules) {
            if (rule == null || rule.isEmpty() || !rule.matchesDimension(dimensionId)) {
                continue;
            }

            Set<Holder<Biome>> sources = resolveSources(rule.source(), biomeRegistry);
            if (sources.isEmpty()) {
                WorldGenModule.LOGGER.warn("Biome rule source '{}' did not resolve in dimension {}", rule.source(), dimensionId);
                continue;
            }

            if (rule.isRemoval()) {
                for (Holder<Biome> holder : sources) {
                    replacements.remove(holder);
                    removals.add(holder);
                }
                continue;
            }

            Holder<Biome> target = resolveBiome(rule.target(), biomeRegistry).orElse(null);
            if (target == null) {
                WorldGenModule.LOGGER.warn("Biome rule target '{}' did not resolve in dimension {}", rule.target(), dimensionId);
                continue;
            }
            for (Holder<Biome> holder : sources) {
                removals.remove(holder);
                replacements.put(holder, target);
            }
        }

        if (replacements.isEmpty() && removals.isEmpty()) {
            return;
        }

        Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> rawParameters =
                ((MultiNoiseBiomeSourceAccessor) source).realmcontrol$getParameters();
        Climate.ParameterList<Holder<Biome>> original = rawParameters.map(
                parameterList -> parameterList,
                holder -> holder.value().parameters()
        );
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> transformed = new ArrayList<>(original.values().size());
        int changed = 0;
        int removed = 0;

        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : original.values()) {
            Holder<Biome> biome = entry.getSecond();
            if (removals.contains(biome)) {
                removed++;
                continue;
            }
            Holder<Biome> replacement = replacements.getOrDefault(biome, biome);
            if (replacement != biome) {
                changed++;
            }
            transformed.add(Pair.of(entry.getFirst(), replacement));
        }

        if (transformed.isEmpty()) {
            WorldGenModule.LOGGER.error("Biome rules for {} would remove every climate entry; rules were not applied", dimensionId);
            return;
        }

        Climate.ParameterList<Holder<Biome>> parameterList = new Climate.ParameterList<>(transformed);
        ((MultiNoiseBiomeSourceAccessor) source).realmcontrol$setParameters(Either.left(parameterList));
        Set<Holder<Biome>> possible = new LinkedHashSet<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : transformed) {
            possible.add(entry.getSecond());
        }
        ((BiomeSourceAccessor) source).realmcontrol$setPossibleBiomes(() -> possible);
        WorldGenModule.LOGGER.info("Applied biome control to {}: {} entries replaced, {} entries removed", dimensionId, changed, removed);
    }

    private static Set<Holder<Biome>> resolveSources(String source, Registry<Biome> registry) {
        Set<Holder<Biome>> result = new LinkedHashSet<>();
        if (source == null || source.isBlank()) {
            return result;
        }
        String trimmed = source.trim();
        if (trimmed.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(trimmed.substring(1));
            if (tagId == null) {
                return result;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
            registry.getTag(tag).ifPresent(named -> named.forEach(result::add));
            return result;
        }
        resolveBiome(trimmed, registry).ifPresent(result::add);
        return result;
    }

    private static Optional<Holder<Biome>> resolveBiome(String id, Registry<Biome> registry) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null) {
            return Optional.empty();
        }
        return registry.getHolder(ResourceKey.create(Registries.BIOME, location)).map(holder -> (Holder<Biome>) holder);
    }
}
