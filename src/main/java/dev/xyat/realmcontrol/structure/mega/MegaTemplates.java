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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mega tiles in use, loaded through vanilla's template manager (so old templates are upgraded the vanilla way)
 * but not kept for the rest of the game as vanilla keeps templates: the 64 used last stay, and older ones are dropped
 * from vanilla's manager too, so a 1024×1024 structure never sits in memory whole. With each template its columns are
 * noted: the highest block of each and whether it stands on a block.
 */
final class MegaTemplates {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int CAPACITY = 64;
    private static final LinkedHashMap<ResourceLocation, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Set<ResourceLocation> REPORTED = ConcurrentHashMap.newKeySet();

    /** A tile with, per column at {@code x + z * sizeX}, its highest block (-1 for none) and whether its bottom cell holds a block. */
    record Entry(StructureTemplate template, int sizeX, int sizeZ, int[] tops, boolean[] bottoms) {
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
        synchronized (CACHE) {
            Entry known = CACHE.get(id);
            if (known != null) return known;
        }
        Optional<StructureTemplate> loaded = manager.get(id);
        if (loaded.isEmpty()) {
            if (REPORTED.add(id)) LOGGER.warn("Mega structure tile {} is missing; that part of the structure stays empty", id);
            return null;
        }
        Entry entry = entry(loaded.get());
        List<ResourceLocation> dropped = new ArrayList<>();
        synchronized (CACHE) {
            Entry raced = CACHE.putIfAbsent(id, entry);
            if (raced != null) return raced;
            Iterator<ResourceLocation> eldest = CACHE.keySet().iterator();
            while (CACHE.size() > CAPACITY && eldest.hasNext()) {
                dropped.add(eldest.next());
                eldest.remove();
            }
        }
        dropped.forEach(((StructureTemplateManagerAccessor) manager).realmcontrol_worldgen$templates()::remove);
        return entry;
    }

    /** Forgets every tile, when the server stops. */
    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        REPORTED.clear();
    }

    private static Entry entry(StructureTemplate template) {
        Vec3i size = template.getSize();
        int sizeX = Math.max(1, size.getX()), sizeZ = Math.max(1, size.getZ());
        int[] tops = new int[sizeX * sizeZ];
        boolean[] bottoms = new boolean[sizeX * sizeZ];
        Arrays.fill(tops, -1);
        List<StructureTemplate.Palette> palettes = ((StructureTemplateAccessor) template).realmcontrol_worldgen$palettes();
        if (!palettes.isEmpty()) {
            for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
                BlockPos pos = info.pos();
                if (info.state().isAir() || pos.getX() < 0 || pos.getX() >= sizeX || pos.getZ() < 0 || pos.getZ() >= sizeZ) continue;
                int column = pos.getX() + pos.getZ() * sizeX;
                tops[column] = Math.max(tops[column], pos.getY());
                if (pos.getY() == 0 && info.state().getFluidState().isEmpty()) bottoms[column] = true;
            }
        }
        return new Entry(template, sizeX, sizeZ, tops, bottoms);
    }
}
