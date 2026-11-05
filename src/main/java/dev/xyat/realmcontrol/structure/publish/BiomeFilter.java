package dev.xyat.realmcontrol.structure.publish;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns a draft's dimensions and biome choices into the values of its {@code has_structure} biome tag. Vanilla only
 * checks the biome where a structure starts, so the dimensions are enforced through the biomes too: only biomes
 * those dimensions can generate are kept. When the chosen biomes and tags already stay inside the dimensions and
 * nothing is excluded, the tag refers to them as they are (later additions to those tags count too); otherwise it
 * lists the remaining biomes. Every entry is optional, so a biome whose mod is gone is skipped instead of breaking
 * the world.
 */
public final class BiomeFilter {
    private BiomeFilter() {
    }

    /** The tag values and how many biomes they cover now. */
    public record Result(JsonArray values, int count) {
    }

    public static Result resolve(MinecraftServer server, StructureProject.Generation generation) {
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        Set<String> dimensionBiomes = new TreeSet<>();
        List<String> dimensions = generation.dimensions.isEmpty() ? List.of("minecraft:overworld") : generation.dimensions;
        for (String dimension : dimensions) dimensionBiomes.addAll(biomesOf(server, dimension));
        List<String> include = new ArrayList<>(generation.include);
        if (include.isEmpty()) {
            for (String dimension : dimensions) include.add(defaultTag(dimension));
        }
        Set<String> included = expand(registry, include, dimension -> biomesOf(server, dimension));
        Set<String> excluded = expand(registry, generation.exclude, dimension -> biomesOf(server, dimension));
        JsonArray values = new JsonArray();
        if (excluded.isEmpty() && dimensionBiomes.containsAll(included) && include.stream().noneMatch(entry -> entry.startsWith("@"))) {
            for (String entry : include) values.add(optional(entry));
            return new Result(values, included.size());
        }
        Set<String> result = new TreeSet<>(included);
        result.retainAll(dimensionBiomes);
        result.removeAll(excluded);
        for (String biome : result) values.add(optional(biome));
        return new Result(values, result.size());
    }

    private static JsonObject optional(String entry) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entry);
        json.addProperty("required", false);
        return json;
    }

    /** {@code #tag} for the vanilla dimensions, {@code @dimension} (all its biomes) for any other. */
    private static String defaultTag(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "#minecraft:is_overworld";
            case "minecraft:the_nether" -> "#minecraft:is_nether";
            case "minecraft:the_end" -> "#minecraft:is_end";
            default -> "@" + dimension;
        };
    }

    private static Set<String> expand(Registry<Biome> registry, List<String> entries, java.util.function.Function<String, Set<String>> dimension) {
        Set<String> biomes = new TreeSet<>();
        for (String entry : entries) {
            if (entry.startsWith("@")) {
                biomes.addAll(dimension.apply(entry.substring(1)));
            } else if (entry.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(entry.substring(1));
                if (id != null) biomes.addAll(tagMembers(registry, TagKey.create(Registries.BIOME, id)));
            } else if (ResourceLocation.tryParse(entry) != null) {
                biomes.add(entry);
            }
        }
        return biomes;
    }

    private static Set<String> tagMembers(Registry<Biome> registry, TagKey<Biome> tag) {
        Set<String> members = new TreeSet<>();
        Optional<HolderSet.Named<Biome>> set = registry.getTag(tag);
        set.ifPresent(holders -> holders.forEach(holder -> holder.unwrapKey().ifPresent(key -> members.add(key.location().toString()))));
        return members;
    }

    /** The biomes a dimension's generator can place; empty if the dimension is not loaded. */
    public static Set<String> biomesOf(MinecraftServer server, String dimension) {
        Set<String> biomes = new TreeSet<>();
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return biomes;
        for (Holder<Biome> holder : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
            holder.unwrapKey().ifPresent(key -> biomes.add(key.location().toString()));
        }
        return biomes;
    }

    /**
     * What the biome picker shows: every dimension with its biomes, and every biome tag with its members, as JSON
     * {@code {"dimensions": {id: [biome...]}, "biomes": [id...], "tags": {id: [biome...]}}}.
     */
    public static JsonObject catalog(MinecraftServer server) {
        Registry<Biome> registry = server.registryAccess().registryOrThrow(Registries.BIOME);
        JsonObject json = new JsonObject();
        JsonObject dimensions = new JsonObject();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            JsonArray biomes = new JsonArray();
            biomesOf(server, dimension).forEach(biomes::add);
            dimensions.add(dimension, biomes);
        }
        json.add("dimensions", dimensions);
        JsonArray all = new JsonArray();
        registry.keySet().stream().map(ResourceLocation::toString).sorted().forEach(all::add);
        json.add("biomes", all);
        Map<String, Set<String>> tags = new TreeMap<>();
        registry.getTagNames().forEach(tag -> {
            Set<String> members = tagMembers(registry, tag);
            if (!members.isEmpty()) tags.put(tag.location().toString(), members);
        });
        JsonObject tagJson = new JsonObject();
        tags.forEach((tag, members) -> {
            JsonArray array = new JsonArray();
            members.forEach(array::add);
            tagJson.add(tag, array);
        });
        json.add("tags", tagJson);
        return json;
    }
}
