package dev.xyat.realmcontrol.worldgen.preview;

import dev.xyat.realmcontrol.util.Nbt;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Remembers which blocks of a structure template this game does not know. Vanilla turns those into air while it
 * loads the template and says nothing; the preview reports them instead. A preview also records which templates
 * its pieces placed, so it can list the missing blocks of exactly those.
 */
public final class TemplateMissingTracker {
    private static final Map<StructureTemplate, Map<String, Integer>> MISSING = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Set<StructureTemplate>> PLACED = new ThreadLocal<>();

    private TemplateMissingTracker() {
    }

    /** Called when a template has loaded: counts the blocks whose palette entry names an unregistered block. */
    public static void recordLoaded(StructureTemplate template, CompoundTag tag) {
        try {
            ListTag palette = Nbt.compoundList(tag, "palette");
            if (palette.isEmpty()) {
                ListTag palettes = tag.get("palettes") instanceof ListTag list ? list : new ListTag();
                if (!palettes.isEmpty() && palettes.get(0) instanceof ListTag first) palette = first;
            }
            Map<Integer, String> unknown = new LinkedHashMap<>();
            for (int i = 0; i < palette.size(); i++) {
                String name = Nbt.string(Nbt.compoundAt(palette, i), "Name");
                ResourceLocation id = ResourceLocation.tryParse(name);
                if (id != null && !BuiltInRegistries.BLOCK.containsKey(id)) unknown.put(i, name);
            }
            if (unknown.isEmpty()) return;
            Map<String, Integer> counts = new LinkedHashMap<>();
            ListTag blocks = Nbt.compoundList(tag, "blocks");
            for (int i = 0; i < blocks.size(); i++) {
                String name = unknown.get(Nbt.intValue(Nbt.compoundAt(blocks, i), "state"));
                if (name != null) counts.merge(name, 1, Integer::sum);
            }
            if (!counts.isEmpty()) MISSING.put(template, counts);
        } catch (RuntimeException ignored) {
            // A template this cannot read is reported by vanilla when it loads.
        }
    }

    /** Called when a template is placed; recorded only while a preview runs on this thread. */
    public static void notePlaced(StructureTemplate template) {
        Set<StructureTemplate> placed = PLACED.get();
        if (placed != null) placed.add(template);
    }

    /** Starts recording the templates placed on this thread. */
    static void startRecording() {
        PLACED.set(Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    /** Stops recording and returns the missing blocks of the templates placed since {@link #startRecording()}. */
    static Map<String, Integer> stopRecording() {
        Set<StructureTemplate> placed = PLACED.get();
        PLACED.remove();
        Map<String, Integer> result = new LinkedHashMap<>();
        if (placed == null) return result;
        for (StructureTemplate template : new HashSet<>(placed)) {
            Map<String, Integer> counts = MISSING.get(template);
            if (counts != null) counts.forEach((name, count) -> result.merge(name, count, Integer::sum));
        }
        return result;
    }
}
