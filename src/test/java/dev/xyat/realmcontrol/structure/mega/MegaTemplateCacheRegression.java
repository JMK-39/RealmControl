package dev.xyat.realmcontrol.structure.mega;

import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class MegaTemplateCacheRegression {
    public static void run() {
        // Blocks share a bucket exactly when their rotated world positions land in the same chunk.
        for (Rotation rotation : Rotation.values()) {
            Map<Integer, Long> buckets = new HashMap<>();
            for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                BlockPos turned = MegaLayout.turn(new BlockPos(x, 0, z), rotation);
                int cx = Math.floorDiv(turned.getX() - 8000, 16), cz = Math.floorDiv(turned.getZ() + 3200, 16);
                long chunk = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                int bucket = MegaTilePalettes.bucket(x, z, rotation);
                Long previous = buckets.putIfAbsent(bucket, chunk);
                if (previous != null && previous.longValue() != chunk) throw new AssertionError("rotated chunk bucket");
            }
            if (new java.util.HashSet<>(buckets.values()).size() != buckets.size()) throw new AssertionError("duplicate buckets");
        }
        Map<ResourceLocation, Optional<StructureTemplate>> vanilla = new HashMap<>();
        MegaTemplates.Cache cache = new MegaTemplates.Cache(3, 10);
        ResourceLocation a = KineticResourceIds.of("test", "a"), b = KineticResourceIds.of("test", "b"),
                c = KineticResourceIds.of("test", "c"), d = KineticResourceIds.of("test", "d");
        put(cache, vanilla, a, 4);
        put(cache, vanilla, b, 4);
        if (cache.find(a, vanilla) == null) throw new AssertionError("cache hit");
        put(cache, vanilla, c, 3);
        if (vanilla.containsKey(b) || !vanilla.containsKey(a)) throw new AssertionError("weighted LRU");
        put(cache, vanilla, d, 14);
        if (vanilla.size() != 1 || cache.find(d, vanilla) == null) throw new AssertionError("oversized active tile");
        vanilla.clear();
        if (cache.find(d, vanilla) != null) throw new AssertionError("resource reload left a stale tile");
        put(cache, vanilla, a, 1);
        put(cache, vanilla, b, 1);
        put(cache, vanilla, c, 1);
        put(cache, vanilla, d, 1);
        if (vanilla.size() != 3 || cache.find(a, vanilla) != null) throw new AssertionError("count bound");
    }

    private static void put(MegaTemplates.Cache cache, Map<ResourceLocation, Optional<StructureTemplate>> vanilla,
                            ResourceLocation id, int blocks) {
        StructureTemplate template = new StructureTemplate();
        vanilla.put(id, Optional.of(template));
        cache.remember(id, new MegaTemplates.Entry(template, 1, 1, new int[1], new boolean[1], blocks, null), vanilla);
    }
}
