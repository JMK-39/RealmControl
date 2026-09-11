package dev.xyat.realmcontrol.worldgen.config;

import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import dev.xyat.realmcontrol.worldgen.mixin.ChunkGeneratorStructureStateAccessor;
import dev.xyat.realmcontrol.worldgen.mixin.StructurePlacementAccessor;
import dev.xyat.realmcontrol.worldgen.mixin.StructureSetAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(modid = WorldGenModule.MODID)
public final class StructureGenerationControl {
    private static final Map<StructureSet, Snapshot> SNAPSHOTS = new IdentityHashMap<>();
    private static final Map<String, Snapshot> SNAPSHOTS_BY_ID = new LinkedHashMap<>();

    private static volatile boolean enabled = true;
    private static volatile Map<String, StructureEntryRule> entryRules = Map.of();
    private static volatile Map<String, StructurePlacementRule> placementRules = Map.of();

    private StructureGenerationControl() {
    }

    public static synchronized void refresh(
            Map<String, StructureEntryRule> newEntryRules,
            Map<String, StructurePlacementRule> newPlacementRules,
            boolean newEnabled
    ) {
        entryRules = copyEntryRules(newEntryRules);
        placementRules = copyPlacementRules(newPlacementRules);
        enabled = newEnabled;
    }

    public static synchronized void applyBeforeWorldPreparation(MinecraftServer server) {
        if (server == null) return;
        captureOriginalValues(server.registryAccess());
        applyToRegistry(server.registryAccess());
        invalidateStructureStateCaches(server);
    }

    @SubscribeEvent
    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        restoreOriginalValues();
        SNAPSHOTS.clear();
        SNAPSHOTS_BY_ID.clear();
    }

    public static synchronized List<StructureRuleDescriptor> getDescriptors(MinecraftServer server) {
        if (server == null) return List.of();
        captureOriginalValues(server.registryAccess());

        Registry<Structure> structureRegistry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Map<String, List<String>> structureDimensions = collectStructureDimensions(server, structureRegistry);
        List<StructureRuleDescriptor> result = new ArrayList<>();
        Set<String> described = new HashSet<>();

        for (Snapshot snapshot : SNAPSHOTS_BY_ID.values()) {
            StructurePlacement placement = snapshot.originalPlacement();
            for (StructureSet.StructureSelectionEntry entry : snapshot.originalEntries()) {
                String structureId = structureId(structureRegistry, entry.structure());
                if (structureId == null) continue;
                described.add(structureId);
                result.add(toDescriptor(structureId, snapshot, entry, structureDimensions.getOrDefault(structureId, List.of())));
            }
        }

        for (ResourceLocation id : structureRegistry.keySet()) {
            String structureId = id.toString();
            if (described.contains(structureId)) continue;
            StructureEntryRule entryRule = entryRules.get(structureId);
            result.add(new StructureRuleDescriptor(
                    structureId,
                    "",
                    "unassigned",
                    1,
                    0.0F,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    structureDimensions.getOrDefault(structureId, List.of()),
                    entryRule,
                    null
            ));
        }

        result.sort(Comparator.comparing(StructureRuleDescriptor::structureId)
                .thenComparing(StructureRuleDescriptor::structureSetId));
        return List.copyOf(result);
    }

    public static synchronized List<String> getStructureDimensions(MinecraftServer server, String structureId) {
        if (server == null || structureId == null || structureId.isBlank()) {
            return List.of();
        }
        captureOriginalValues(server.registryAccess());
        Registry<Structure> structureRegistry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return collectStructureDimensions(server, structureRegistry).getOrDefault(structureId, List.of());
    }

    private static Map<String, List<String>> collectStructureDimensions(MinecraftServer server, Registry<Structure> structureRegistry) {
        Map<String, LinkedHashSet<String>> dimensions = new LinkedHashMap<>();
        Registry<StructureSet> structureSetRegistry = server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        levels.sort(Comparator
                .comparingInt((ServerLevel level) -> dimensionPriority(level.dimension().location().toString()))
                .thenComparing(level -> level.dimension().location().toString()));

        for (ServerLevel level : levels) {
            String dimensionId = level.dimension().location().toString();
            for (Holder<StructureSet> holder : level.getChunkSource().getGeneratorState().possibleStructureSets()) {
                ResourceLocation setLocation = holder.unwrapKey()
                        .map(key -> key.location())
                        .orElseGet(() -> structureSetRegistry.getKey(holder.value()));
                if (setLocation == null) {
                    continue;
                }

                Snapshot snapshot = SNAPSHOTS_BY_ID.get(setLocation.toString());
                List<StructureSet.StructureSelectionEntry> entries = snapshot != null
                        ? snapshot.originalEntries()
                        : holder.value().structures();

                for (StructureSet.StructureSelectionEntry entry : entries) {
                    String structureId = structureId(structureRegistry, entry.structure());
                    if (structureId != null) {
                        dimensions.computeIfAbsent(structureId, ignored -> new LinkedHashSet<>()).add(dimensionId);
                    }
                }
            }
        }

        for (ResourceLocation id : structureRegistry.keySet()) {
            String structureId = id.toString();
            if (dimensions.containsKey(structureId)) {
                continue;
            }
            Structure structure = structureRegistry.get(id);
            if (structure == null) {
                continue;
            }
            for (ServerLevel level : levels) {
                Set<Holder<Biome>> possibleBiomes = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
                boolean compatible = structure.biomes().stream().anyMatch(possibleBiomes::contains);
                if (compatible) {
                    dimensions.computeIfAbsent(structureId, ignored -> new LinkedHashSet<>())
                            .add(level.dimension().location().toString());
                }
            }
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashSet<String>> entry : dimensions.entrySet()) {
            List<String> ids = new ArrayList<>(entry.getValue());
            ids.sort(Comparator.comparingInt(StructureGenerationControl::dimensionPriority).thenComparing(value -> value));
            result.put(entry.getKey(), List.copyOf(ids));
        }
        return result;
    }

    private static int dimensionPriority(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> 0;
            case "minecraft:the_nether" -> 1;
            case "minecraft:the_end" -> 2;
            case "twilightforest:twilight_forest" -> 3;
            default -> 100;
        };
    }

    public static synchronized boolean isKnownStructureSet(String structureSetId) {
        return structureSetId != null && SNAPSHOTS_BY_ID.containsKey(structureSetId);
    }

    public static synchronized String getPlacementType(String structureSetId) {
        Snapshot snapshot = SNAPSHOTS_BY_ID.get(structureSetId);
        return snapshot == null ? "unassigned" : placementType(snapshot.originalPlacement());
    }

    private static StructureRuleDescriptor toDescriptor(
            String structureId,
            Snapshot snapshot,
            StructureSet.StructureSelectionEntry entry,
            List<String> dimensionIds
    ) {
        StructurePlacement placement = snapshot.originalPlacement();
        Integer spacing = null;
        Integer separation = null;
        String spreadType = null;
        Integer distance = null;
        Integer spread = null;
        Integer count = null;

        if (placement instanceof RandomSpreadStructurePlacement random) {
            spacing = random.spacing();
            separation = random.separation();
            spreadType = random.spreadType().name().toLowerCase(Locale.ROOT);
        } else if (placement instanceof ConcentricRingsStructurePlacement concentric) {
            distance = concentric.distance();
            spread = concentric.spread();
            count = concentric.count();
        }

        return new StructureRuleDescriptor(
                structureId,
                snapshot.structureSetId(),
                placementType(placement),
                entry.weight(),
                snapshot.originalFrequency(),
                ((StructurePlacementAccessor) placement).realmcontrol_worldgen$getSalt(),
                spacing,
                separation,
                spreadType,
                distance,
                spread,
                count,
                dimensionIds,
                entryRules.get(structureId),
                placementRules.get(snapshot.structureSetId())
        );
    }

    private static void captureOriginalValues(RegistryAccess registryAccess) {
        if (!SNAPSHOTS.isEmpty()) return;
        Registry<StructureSet> structureSetRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE_SET);
        for (ResourceLocation id : structureSetRegistry.keySet()) {
            StructureSet structureSet = structureSetRegistry.get(id);
            if (structureSet == null) continue;
            StructurePlacement placement = structureSet.placement();
            Snapshot snapshot = new Snapshot(
                    id.toString(),
                    structureSet,
                    List.copyOf(structureSet.structures()),
                    placement,
                    ((StructurePlacementAccessor) placement).realmcontrol_worldgen$getFrequency()
            );
            SNAPSHOTS.put(structureSet, snapshot);
            SNAPSHOTS_BY_ID.put(id.toString(), snapshot);
        }
    }

    private static void applyToRegistry(RegistryAccess registryAccess) {
        restoreOriginalValues();
        if (!enabled) return;

        Registry<Structure> structureRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE);
        for (Snapshot snapshot : SNAPSHOTS_BY_ID.values()) {
            List<StructureSet.StructureSelectionEntry> enabledEntries = new ArrayList<>(snapshot.originalEntries().size());
            for (StructureSet.StructureSelectionEntry originalEntry : snapshot.originalEntries()) {
                String id = structureId(structureRegistry, originalEntry.structure());
                StructureEntryRule rule = id == null ? null : entryRules.get(id);
                if (rule != null && rule.disabled()) continue;

                int weight = originalEntry.weight();
                if (rule != null && rule.weight() != null && rule.weight() > 0) {
                    weight = rule.weight();
                }
                enabledEntries.add(new StructureSet.StructureSelectionEntry(originalEntry.structure(), weight));
            }

            boolean allDisabled = enabledEntries.isEmpty() && !snapshot.originalEntries().isEmpty();
            StructureSetAccessor accessor = (StructureSetAccessor) (Object) snapshot.structureSet();
            if (!allDisabled) {
                accessor.realmcontrol_worldgen$setStructures(List.copyOf(enabledEntries));
            }

            StructurePlacementRule placementRule = placementRules.get(snapshot.structureSetId());
            StructurePlacement placement = buildPlacement(snapshot, placementRule, allDisabled);
            accessor.realmcontrol_worldgen$setPlacement(placement);
        }
    }

    private static StructurePlacement buildPlacement(
            Snapshot snapshot,
            StructurePlacementRule rule,
            boolean forceZeroFrequency
    ) {
        StructurePlacement original = snapshot.originalPlacement();
        float frequency = forceZeroFrequency
                ? 0.0F
                : validFrequency(rule == null ? null : rule.frequency(), snapshot.originalFrequency());
        int salt = rule != null && rule.salt() != null ? rule.salt() : ((StructurePlacementAccessor) original).realmcontrol_worldgen$getSalt();

        if (original instanceof RandomSpreadStructurePlacement random) {
            int spacing = positiveOrDefault(rule == null ? null : rule.spacing(), random.spacing());
            int separation = nonNegativeOrDefault(rule == null ? null : rule.separation(), random.separation());
            if (separation >= spacing) {
                spacing = random.spacing();
                separation = random.separation();
            }
            RandomSpreadType spreadType = parseSpreadType(rule == null ? null : rule.spreadType(), random.spreadType());
            return new RandomSpreadStructurePlacement(
                    ((StructurePlacementAccessor) original).realmcontrol_worldgen$getLocateOffset(),
                    ((StructurePlacementAccessor) original).realmcontrol_worldgen$getFrequencyReductionMethod(),
                    frequency,
                    salt,
                    ((StructurePlacementAccessor) original).realmcontrol_worldgen$getExclusionZone(),
                    spacing,
                    separation,
                    spreadType
            );
        }

        if (original instanceof ConcentricRingsStructurePlacement concentric) {
            int distance = positiveOrDefault(rule == null ? null : rule.distance(), concentric.distance());
            int spread = positiveOrDefault(rule == null ? null : rule.spread(), concentric.spread());
            int count = positiveOrDefault(rule == null ? null : rule.count(), concentric.count());
            return new ConcentricRingsStructurePlacement(
                    ((StructurePlacementAccessor) original).realmcontrol_worldgen$getLocateOffset(),
                    ((StructurePlacementAccessor) original).realmcontrol_worldgen$getFrequencyReductionMethod(),
                    frequency,
                    salt,
                    ((StructurePlacementAccessor) original).realmcontrol_worldgen$getExclusionZone(),
                    distance,
                    spread,
                    count,
                    concentric.preferredBiomes()
            );
        }

        ((StructurePlacementAccessor) original).realmcontrol_worldgen$setFrequency(frequency);
        return original;
    }


    private static void invalidateStructureStateCaches(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            ChunkGeneratorStructureStateAccessor accessor = (ChunkGeneratorStructureStateAccessor) (Object) state;
            accessor.realmcontrol_worldgen$getPlacementsForStructure().clear();
            accessor.realmcontrol_worldgen$getRingPositions().clear();
            accessor.realmcontrol_worldgen$setHasGeneratedPositions(false);
        }
    }

    private static void restoreOriginalValues() {
        for (Snapshot snapshot : SNAPSHOTS.values()) {
            ((StructurePlacementAccessor) snapshot.originalPlacement()).realmcontrol_worldgen$setFrequency(snapshot.originalFrequency());
            StructureSetAccessor accessor = (StructureSetAccessor) (Object) snapshot.structureSet();
            accessor.realmcontrol_worldgen$setStructures(snapshot.originalEntries());
            accessor.realmcontrol_worldgen$setPlacement(snapshot.originalPlacement());
        }
    }

    private static Map<String, StructureEntryRule> copyEntryRules(Map<String, StructureEntryRule> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, StructureEntryRule> result = new LinkedHashMap<>();
        for (Map.Entry<String, StructureEntryRule> entry : source.entrySet()) {
            StructureEntryRule rule = entry.getValue();
            if (rule == null || rule.isEmpty()) continue;
            ResourceLocation id = ResourceLocation.tryParse(rule.structureId().toLowerCase(Locale.ROOT));
            if (id != null) result.put(id.toString(), new StructureEntryRule(id.toString(), rule.disabled(), rule.weight()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, StructurePlacementRule> copyPlacementRules(Map<String, StructurePlacementRule> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, StructurePlacementRule> result = new LinkedHashMap<>();
        for (Map.Entry<String, StructurePlacementRule> entry : source.entrySet()) {
            StructurePlacementRule rule = entry.getValue();
            if (rule == null || rule.isEmpty()) continue;
            ResourceLocation id = ResourceLocation.tryParse(rule.structureSetId().toLowerCase(Locale.ROOT));
            if (id != null) {
                result.put(id.toString(), new StructurePlacementRule(
                        id.toString(),
                        rule.frequency(),
                        rule.salt(),
                        rule.spacing(),
                        rule.separation(),
                        rule.spreadType(),
                        rule.distance(),
                        rule.spread(),
                        rule.count()
                ));
            }
        }
        return Map.copyOf(result);
    }

    private static String structureId(Registry<Structure> registry, Holder<Structure> holder) {
        if (holder == null) return null;
        ResourceLocation id = holder.unwrapKey()
                .map(key -> key.location())
                .orElseGet(() -> registry.getKey(holder.value()));
        return id == null ? null : id.toString();
    }

    private static String placementType(StructurePlacement placement) {
        if (placement instanceof RandomSpreadStructurePlacement) return "random_spread";
        if (placement instanceof ConcentricRingsStructurePlacement) return "concentric_rings";
        return "other";
    }

    private static float validFrequency(Float value, float fallback) {
        if (value == null || !Float.isFinite(value) || value < 0.0F || value > 1.0F) return fallback;
        return value;
    }

    private static int positiveOrDefault(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static int nonNegativeOrDefault(Integer value, int fallback) {
        return value != null && value >= 0 ? value : fallback;
    }

    private static RandomSpreadType parseSpreadType(String value, RandomSpreadType fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return RandomSpreadType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private record Snapshot(
            String structureSetId,
            StructureSet structureSet,
            List<StructureSet.StructureSelectionEntry> originalEntries,
            StructurePlacement originalPlacement,
            float originalFrequency
    ) {
    }
}
