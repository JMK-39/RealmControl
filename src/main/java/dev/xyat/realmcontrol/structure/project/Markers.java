package dev.xyat.realmcontrol.structure.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a draft does with its containers, spawners and entities when it is published: a container keeps its items
 * or gets a loot table, a spawner stays as it is or gets new mobs and timings, an entity is kept or left out (by
 * type, or one by one). The scan lists them; the editor's settings survive a new scan where the same thing is
 * still in the same place. Positions are relative to the draft's minimum corner.
 */
public final class Markers {
    /** Most entities one draft lists; more are kept by their type's setting. */
    public static final int MAX_ENTITIES = 4096;

    public List<Container> containers = new ArrayList<>();
    public List<Spawner> spawners = new ArrayList<>();
    /** Entity types that are left out; every other type is kept. */
    public Map<String, Boolean> entityTypes = new LinkedHashMap<>();
    public List<EntityMark> entities = new ArrayList<>();

    /** A container: {@code keep} its items, or {@code table} for a loot table filled when first opened. */
    public static final class Container {
        public int x, y, z;
        public String block = "";
        /** Whether vanilla can fill it from a loot table; the others can only keep their items. */
        public boolean lootable;
        /** Item stacks it held when scanned. */
        public int items;
        public String mode = "keep";
        public String table = "";

        public boolean usesTable() {
            return lootable && "table".equals(mode) && !table.isBlank();
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.add("pos", StructureProject.ints(new int[]{x, y, z}));
            json.addProperty("block", block);
            json.addProperty("lootable", lootable);
            json.addProperty("items", items);
            json.addProperty("mode", mode);
            json.addProperty("table", table);
            return json;
        }

        static Container fromJson(JsonObject json) {
            Container container = new Container();
            int[] pos = StructureProject.intArray(json.getAsJsonArray("pos"), 3);
            container.x = pos[0];
            container.y = pos[1];
            container.z = pos[2];
            container.block = StructureProject.stringOf(json, "block", "");
            container.lootable = json.has("lootable") && json.get("lootable").getAsBoolean();
            container.items = StructureProject.intOf(json, "items", 0);
            container.mode = StructureProject.oneOf(StructureProject.stringOf(json, "mode", "keep"), "keep", "table");
            container.table = StructureProject.stringOf(json, "table", "");
            return container;
        }
    }

    /** A spawn weight of one mob in a spawner. */
    public record Mob(String id, int weight) {
    }

    /** A mob spawner: {@code keep} as scanned, or {@code custom} with the mobs and timings below (vanilla's fields). */
    public static final class Spawner {
        public int x, y, z;
        public String block = "";
        /** The mob it spawned when scanned. */
        public String entity = "";
        public String mode = "keep";
        public List<Mob> mobs = new ArrayList<>();
        public int minDelay = 200, maxDelay = 800, count = 4, maxNearby = 6, playerRange = 16, spawnRange = 4;

        public boolean custom() {
            return "custom".equals(mode) && !mobs.isEmpty();
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.add("pos", StructureProject.ints(new int[]{x, y, z}));
            json.addProperty("block", block);
            json.addProperty("entity", entity);
            json.addProperty("mode", mode);
            JsonArray list = new JsonArray();
            for (Mob mob : mobs) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", mob.id());
                entry.addProperty("weight", mob.weight());
                list.add(entry);
            }
            json.add("mobs", list);
            json.addProperty("min_delay", minDelay);
            json.addProperty("max_delay", maxDelay);
            json.addProperty("count", count);
            json.addProperty("max_nearby", maxNearby);
            json.addProperty("player_range", playerRange);
            json.addProperty("spawn_range", spawnRange);
            return json;
        }

        static Spawner fromJson(JsonObject json) {
            Spawner spawner = new Spawner();
            int[] pos = StructureProject.intArray(json.getAsJsonArray("pos"), 3);
            spawner.x = pos[0];
            spawner.y = pos[1];
            spawner.z = pos[2];
            spawner.block = StructureProject.stringOf(json, "block", "");
            spawner.entity = StructureProject.stringOf(json, "entity", "");
            spawner.mode = StructureProject.oneOf(StructureProject.stringOf(json, "mode", "keep"), "keep", "custom");
            if (json.has("mobs")) {
                for (JsonElement element : json.getAsJsonArray("mobs")) {
                    JsonObject entry = element.getAsJsonObject();
                    String id = StructureProject.stringOf(entry, "id", "");
                    if (!id.isBlank()) spawner.mobs.add(new Mob(id, StructureProject.clamp(StructureProject.intOf(entry, "weight", 1), 1, 1000)));
                }
            }
            spawner.minDelay = StructureProject.clamp(StructureProject.intOf(json, "min_delay", spawner.minDelay), 0, 32767);
            spawner.maxDelay = StructureProject.clamp(StructureProject.intOf(json, "max_delay", spawner.maxDelay), spawner.minDelay, 32767);
            spawner.count = StructureProject.clamp(StructureProject.intOf(json, "count", spawner.count), 1, 64);
            spawner.maxNearby = StructureProject.clamp(StructureProject.intOf(json, "max_nearby", spawner.maxNearby), 1, 256);
            spawner.playerRange = StructureProject.clamp(StructureProject.intOf(json, "player_range", spawner.playerRange), 1, 128);
            spawner.spawnRange = StructureProject.clamp(StructureProject.intOf(json, "spawn_range", spawner.spawnRange), 1, 64);
            return spawner;
        }
    }

    /** One scanned entity; {@code keep} overrides its type's setting when set. */
    public static final class EntityMark {
        public String type = "";
        public double x, y, z;
        public String name = "";
        @Nullable public Boolean keep;

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("type", type);
            JsonArray pos = new JsonArray();
            pos.add(x);
            pos.add(y);
            pos.add(z);
            json.add("pos", pos);
            if (!name.isEmpty()) json.addProperty("name", name);
            if (keep != null) json.addProperty("keep", keep);
            return json;
        }

        static EntityMark fromJson(JsonObject json) {
            EntityMark mark = new EntityMark();
            mark.type = StructureProject.stringOf(json, "type", "");
            JsonArray pos = json.getAsJsonArray("pos");
            mark.x = pos.get(0).getAsDouble();
            mark.y = pos.get(1).getAsDouble();
            mark.z = pos.get(2).getAsDouble();
            mark.name = StructureProject.stringOf(json, "name", "");
            mark.keep = json.has("keep") ? json.get("keep").getAsBoolean() : null;
            return mark;
        }
    }

    /** Whether an entity of {@code type} at a draft-relative position is published. */
    public boolean keeps(String type, double x, double y, double z) {
        EntityMark mark = entity(type, x, y, z);
        if (mark != null && mark.keep != null) return mark.keep;
        return keepsType(type);
    }

    public boolean keeps(EntityMark mark) {
        return mark.keep != null ? mark.keep : keepsType(mark.type);
    }

    public boolean keepsType(String type) {
        return entityTypes.getOrDefault(type, true);
    }

    @Nullable
    public Container container(int x, int y, int z) {
        for (Container container : containers) if (container.x == x && container.y == y && container.z == z) return container;
        return null;
    }

    @Nullable
    public Spawner spawner(int x, int y, int z) {
        for (Spawner spawner : spawners) if (spawner.x == x && spawner.y == y && spawner.z == z) return spawner;
        return null;
    }

    /** The listed entity of {@code type} at this position: the scan and the tiles hold the same coordinates. */
    @Nullable
    public EntityMark entity(String type, double x, double y, double z) {
        for (EntityMark mark : entities) {
            if (mark.type.equals(type) && Math.abs(mark.x - x) < 1.0E-3D && Math.abs(mark.y - y) < 1.0E-3D && Math.abs(mark.z - z) < 1.0E-3D) return mark;
        }
        return null;
    }

    public int keptEntities() {
        int kept = 0;
        for (EntityMark mark : entities) if (keeps(mark)) kept++;
        return kept;
    }

    /** Loot tables the containers use, in order of first use. */
    public List<String> tables() {
        List<String> tables = new ArrayList<>();
        for (Container container : containers) if (container.usesTable() && !tables.contains(container.table)) tables.add(container.table);
        return tables;
    }

    /**
     * This scan's markers with the settings of {@code old} carried over: a container or spawner in the same place
     * with the same block keeps its settings, an entity of the same type within a block keeps its choice, and type
     * settings stay.
     */
    public void carryOver(Markers old) {
        for (Container container : containers) {
            Container before = old.container(container.x, container.y, container.z);
            if (before != null && before.block.equals(container.block)) {
                container.mode = before.mode;
                container.table = before.table;
            }
        }
        for (Spawner spawner : spawners) {
            Spawner before = old.spawner(spawner.x, spawner.y, spawner.z);
            if (before == null || !before.block.equals(spawner.block)) continue;
            spawner.mode = before.mode;
            spawner.mobs = new ArrayList<>(before.mobs);
            spawner.minDelay = before.minDelay;
            spawner.maxDelay = before.maxDelay;
            spawner.count = before.count;
            spawner.maxNearby = before.maxNearby;
            spawner.playerRange = before.playerRange;
            spawner.spawnRange = before.spawnRange;
        }
        entityTypes = new LinkedHashMap<>(old.entityTypes);
        Map<String, List<EntityMark>> byType = new HashMap<>();
        for (EntityMark mark : old.entities) if (mark.keep != null) byType.computeIfAbsent(mark.type, type -> new ArrayList<>()).add(mark);
        for (EntityMark mark : entities) {
            for (EntityMark before : byType.getOrDefault(mark.type, List.of())) {
                double dx = before.x - mark.x, dy = before.y - mark.y, dz = before.z - mark.z;
                if (dx * dx + dy * dy + dz * dz < 1.0D) {
                    mark.keep = before.keep;
                    break;
                }
            }
        }
    }

    public Markers copy() {
        return fromJson(toJson());
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        JsonArray containerList = new JsonArray();
        containers.forEach(container -> containerList.add(container.toJson()));
        json.add("containers", containerList);
        JsonArray spawnerList = new JsonArray();
        spawners.forEach(spawner -> spawnerList.add(spawner.toJson()));
        json.add("spawners", spawnerList);
        JsonObject types = new JsonObject();
        entityTypes.forEach(types::addProperty);
        json.add("entity_types", types);
        JsonArray entityList = new JsonArray();
        entities.forEach(mark -> entityList.add(mark.toJson()));
        json.add("entities", entityList);
        return json;
    }

    public static Markers fromJson(JsonObject json) {
        Markers markers = new Markers();
        if (json.has("containers")) for (JsonElement element : json.getAsJsonArray("containers")) markers.containers.add(Container.fromJson(element.getAsJsonObject()));
        if (json.has("spawners")) for (JsonElement element : json.getAsJsonArray("spawners")) markers.spawners.add(Spawner.fromJson(element.getAsJsonObject()));
        if (json.has("entity_types")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("entity_types").entrySet()) {
                markers.entityTypes.put(entry.getKey(), entry.getValue().getAsBoolean());
            }
        }
        if (json.has("entities")) for (JsonElement element : json.getAsJsonArray("entities")) markers.entities.add(EntityMark.fromJson(element.getAsJsonObject()));
        return markers;
    }
}
