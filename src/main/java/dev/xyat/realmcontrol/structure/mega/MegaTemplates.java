package dev.xyat.realmcontrol.structure.mega;

import com.mojang.logging.LogUtils;
import dev.xyat.realmcontrol.worldgen.mixin.StructureTemplateAccessor;
import dev.xyat.realmcontrol.worldgen.mixin.StructureTemplateManagerAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Per-world template cache. Dense tiles consume more of its budget than empty courtyards. */
final class MegaTemplates {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int CAPACITY = 64;
    static final long BLOCK_BUDGET = 1_048_576;
    private static final Map<StructureTemplateManager, Cache> CACHES = new WeakHashMap<>();

    /** A tile with, per column at {@code x + z * sizeX}, its highest block (-1 for none) and whether its bottom cell holds a block. */
    record Entry(StructureTemplate template, int sizeX, int sizeZ, int[] tops, boolean[] bottoms, long blockEntries, @Nullable MegaTilePalettes slices) {
        int top(int x, int z) {
            return tops[x + z * sizeX];
        }

        boolean standsOnBlock(int x, int z) {
            return bottoms[x + z * sizeX];
        }
    }

    private MegaTemplates() {
    }

    @Nullable
    static Entry get(StructureTemplateManager manager, ResourceLocation id) {
        Cache cache;
        synchronized (CACHES) {
            cache = CACHES.computeIfAbsent(manager, unused -> new Cache(CAPACITY, BLOCK_BUDGET));
        }
        Map<ResourceLocation, Optional<StructureTemplate>> vanilla =
                ((StructureTemplateManagerAccessor) manager).realmcontrol_worldgen$templates();
        CompletableFuture<Entry> result;
        boolean loader;
        synchronized (cache) {
            Entry known = cache.find(id, vanilla);
            if (known != null) return known;
            result = cache.loading.get(id);
            loader = result == null;
            if (loader) {
                result = new CompletableFuture<>();
                cache.loading.put(id, result);
            }
        }
        if (!loader) return result.join();
        // Only workers requesting this exact tile wait for its decode and column analysis.
        try {
            Entry entry = manager.get(id).map(MegaTemplates::entry).orElse(null);
            if (entry != null) {
                synchronized (cache) {
                    if (vanilla.getOrDefault(id, Optional.empty()).orElse(null) == entry.template()) {
                        cache.remember(id, entry, vanilla);
                    }
                }
            } else if (cache.reported.add(id)) {
                LOGGER.warn("Mega structure tile {} is missing; that part of the structure stays empty", id);
            }
            result.complete(entry);
            return entry;
        } catch (RuntimeException | Error error) {
            result.completeExceptionally(error);
            throw error;
        } finally {
            synchronized (cache) {
                cache.loading.remove(id, result);
            }
        }
    }

    static final class Cache {
        private final LinkedHashMap<ResourceLocation, Entry> recent = new LinkedHashMap<>(16, 0.75F, true);
        private final Map<ResourceLocation, CompletableFuture<Entry>> loading = new HashMap<>();
        private final Set<ResourceLocation> reported = ConcurrentHashMap.newKeySet();
        private final int capacity;
        private final long budget;
        private long residentBlocks;

        Cache(int capacity, long budget) {
            this.capacity = capacity;
            this.budget = budget;
        }

        @Nullable Entry find(ResourceLocation id, Map<ResourceLocation, Optional<StructureTemplate>> vanilla) {
            Entry known = recent.get(id);
            if (known == null || vanilla.getOrDefault(id, Optional.empty()).orElse(null) == known.template()) return known;
            // A data pack reload may already have replaced or removed vanilla's copy.
            recent.remove(id);
            residentBlocks -= known.blockEntries();
            return null;
        }

        void remember(ResourceLocation id, Entry entry, Map<ResourceLocation, Optional<StructureTemplate>> vanilla) {
            Entry replaced = recent.put(id, entry);
            if (replaced != null) residentBlocks -= replaced.blockEntries();
            residentBlocks += entry.blockEntries();
            var eldest = recent.entrySet().iterator();
            // A single exceptionally large tile stays available to its other intersecting chunks.
            while (recent.size() > capacity || recent.size() > 1 && residentBlocks > budget) {
                var evicted = eldest.next();
                residentBlocks -= evicted.getValue().blockEntries();
                vanilla.remove(evicted.getKey(), Optional.of(evicted.getValue().template()));
                eldest.remove();
            }
        }
    }

    static void clear() {
        synchronized (CACHES) {
            CACHES.clear();
        }
    }

    private static Entry entry(StructureTemplate template) {
        Vec3i size = template.getSize();
        int sizeX = Math.max(1, Math.min(MegaLayout.TILE, size.getX()));
        int sizeZ = Math.max(1, Math.min(MegaLayout.TILE, size.getZ()));
        int[] tops = new int[sizeX * sizeZ];
        boolean[] bottoms = new boolean[sizeX * sizeZ];
        Arrays.fill(tops, -1);
        List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).realmcontrol_worldgen$palettes();
        boolean canSlice = palettes.size() == 1 && sizeX == size.getX() && sizeZ == size.getZ()
                && palettes.get(0).blocks().size() >= 1024;
        if (!palettes.isEmpty()) {
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                BlockPos pos = info.pos();
                if (pos.getX() < 0 || pos.getX() >= sizeX || pos.getZ() < 0 || pos.getZ() >= sizeZ) {
                    canSlice = false;
                    continue;
                }
                if (info.state().isAir()) continue;
                int column = pos.getX() + pos.getZ() * sizeX;
                tops[column] = Math.max(tops[column], pos.getY());
                if (pos.getY() == 0 && info.state().getFluidState().isEmpty()) bottoms[column] = true;
            }
        }
        long blockEntries = 0;
        for (StructureTemplate.Palette palette : palettes) blockEntries += palette.blocks().size();
        return new Entry(template, sizeX, sizeZ, tops, bottoms, blockEntries,
                canSlice ? new MegaTilePalettes(palettes.get(0)) : null);
    }
}
