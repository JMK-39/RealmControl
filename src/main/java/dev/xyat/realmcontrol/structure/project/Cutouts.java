package dev.xyat.realmcontrol.structure.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The cut-outs of a project, in coordinates relative to its minimum corner. Positions that end up cut out are left
 * to the world on generation (written as structure void). Boxes apply in order, each one cutting out or adding back
 * what it covers; the top-down mask then cuts out a height range of single columns.
 */
public final class Cutouts {
    public static final int MAX_BOXES = 256;

    public final List<Box> boxes = new ArrayList<>();
    /** Cut-out height range per column, keyed by {@link #column(int, int)}. */
    public final Map<Long, int[]> mask = new HashMap<>();

    /** A box, both corners included; {@code add} puts back what earlier boxes cut out. */
    public record Box(String name, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean add) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public boolean isEmpty() {
        return boxes.isEmpty() && mask.isEmpty();
    }

    public boolean isCut(int x, int y, int z) {
        boolean cut = false;
        for (Box box : boxes) {
            if (box.contains(x, y, z)) cut = !box.add();
        }
        if (!cut && !mask.isEmpty()) {
            int[] range = mask.get(column(x, z));
            cut = range != null && y >= range[0] && y <= range[1];
        }
        return cut;
    }

    /** Number of cut-out entries, as shown in lists: each box plus the mask as one. */
    public int count() {
        return boxes.size() + (mask.isEmpty() ? 0 : 1);
    }

    public Cutouts copy() {
        Cutouts copy = new Cutouts();
        copy.boxes.addAll(boxes);
        mask.forEach((key, range) -> copy.mask.put(key, range.clone()));
        return copy;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray boxArray = new JsonArray();
        for (Box box : boxes) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", box.name());
            entry.add("min", ints(box.minX(), box.minY(), box.minZ()));
            entry.add("max", ints(box.maxX(), box.maxY(), box.maxZ()));
            entry.addProperty("add", box.add());
            boxArray.add(entry);
        }
        json.add("boxes", boxArray);
        // Mask columns as [x, z, minY, maxY].
        JsonArray maskArray = new JsonArray();
        mask.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            long key = entry.getKey();
            maskArray.add(ints((int) (key >> 32), (int) key, entry.getValue()[0], entry.getValue()[1]));
        });
        json.add("mask", maskArray);
        return json;
    }

    public static Cutouts fromJson(JsonObject json) {
        Cutouts cutouts = new Cutouts();
        if (json.has("boxes")) {
            for (JsonElement element : json.getAsJsonArray("boxes")) {
                if (cutouts.boxes.size() >= MAX_BOXES) break;
                JsonObject entry = element.getAsJsonObject();
                JsonArray min = entry.getAsJsonArray("min"), max = entry.getAsJsonArray("max");
                cutouts.boxes.add(new Box(entry.has("name") ? entry.get("name").getAsString() : "",
                        Math.min(min.get(0).getAsInt(), max.get(0).getAsInt()), Math.min(min.get(1).getAsInt(), max.get(1).getAsInt()),
                        Math.min(min.get(2).getAsInt(), max.get(2).getAsInt()), Math.max(min.get(0).getAsInt(), max.get(0).getAsInt()),
                        Math.max(min.get(1).getAsInt(), max.get(1).getAsInt()), Math.max(min.get(2).getAsInt(), max.get(2).getAsInt()),
                        entry.has("add") && entry.get("add").getAsBoolean()));
            }
        }
        if (json.has("mask")) {
            for (JsonElement element : json.getAsJsonArray("mask")) {
                JsonArray entry = element.getAsJsonArray();
                int minY = entry.get(2).getAsInt(), maxY = entry.get(3).getAsInt();
                cutouts.mask.put(column(entry.get(0).getAsInt(), entry.get(1).getAsInt()),
                        new int[]{Math.min(minY, maxY), Math.max(minY, maxY)});
            }
        }
        return cutouts;
    }

    private static JsonArray ints(int... values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }
}
