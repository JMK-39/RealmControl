package dev.xyat.realmcontrol.structure.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A structure draft: where it was scanned from, how it is saved (embedding, air, ignored blocks) and how it generates.
 * The same object travels to the client for editing, as JSON.
 */
public final class StructureProject {
    /** Footprint up to which a project publishes as a single vanilla jigsaw piece. */
    public static final int SMALL_LIMIT = 64;
    /** Footprint up to which a project publishes as vanilla jigsaw tiles. */
    public static final int MEDIUM_LIMIT = 256;

    public String id = "";
    public String name = "";
    public String createdVersion = "";
    public int dataVersion;
    public String dimension = "minecraft:overworld";
    public int[] origin = new int[3];
    public int[] size = new int[3];
    public int tileSize = 64;
    /** Blocks of the structure below the ground: how deep it is set into the terrain. */
    public int embed;
    /** {@code auto}: air open to the outside is left to the world, enclosed air carves; {@code keep}; {@code none}. */
    public String air = "auto";
    public List<String> ignoreBlocks = new ArrayList<>(List.of("minecraft:structure_void", "minecraft:structure_block", "minecraft:jigsaw"));
    /** {@code draft}, {@code published} or {@code modified} (published, changed since). */
    public String status = "draft";
    public int blocks;
    public int blockEntities;
    public int containers;
    public int entities;
    /** Chunks whose entities were not loaded during the scan, so their entities are missing. */
    public int entityChunksSkipped;
    /** Blocks this game does not know, by block id, with how often they occur. */
    public Map<String, Integer> missing = new LinkedHashMap<>();
    public long scannedAt;
    public long publishedAt;
    /** The pack folder the last publish wrote into; empty for a ZIP or before the first publish. */
    public String publishedRoot = "";
    public Generation generation = new Generation();
    public SaveOptions save = new SaveOptions();

    /** What publishing writes and where: the save options page. */
    public static final class SaveOptions {
        public boolean blockEntities = true;
        /** Containers marked with a loot table get it instead of their items. */
        public boolean loot = true;
        /** Spawners marked as custom get their mobs and timings. */
        public boolean spawners = true;
        public boolean entities = true;
        /** Mobs do not despawn, as in vanilla structures. */
        public boolean persistent = true;
        /** Entities lose their owner, leash, memories and villager gossip. */
        public boolean cleanEntities = true;
        public boolean cutouts = true;
        /** {@code kineticcore}: the KineticCore data pack; {@code world}: this world's datapacks folder; {@code zip}: a file. */
        public String target = "kineticcore";
        /** Also writes the folders of the other format: 1.20.1's {@code structures} and {@code loot_tables}, 1.21's {@code structure} and {@code loot_table}. */
        public boolean bothFormats;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("block_entities", blockEntities);
            json.addProperty("loot", loot);
            json.addProperty("spawners", spawners);
            json.addProperty("entities", entities);
            json.addProperty("persistent", persistent);
            json.addProperty("clean_entities", cleanEntities);
            json.addProperty("cutouts", cutouts);
            json.addProperty("target", target);
            json.addProperty("both_formats", bothFormats);
            return json;
        }

        static SaveOptions fromJson(JsonObject json) {
            SaveOptions options = new SaveOptions();
            options.blockEntities = flag(json, "block_entities", true);
            options.loot = flag(json, "loot", true);
            options.spawners = flag(json, "spawners", true);
            options.entities = flag(json, "entities", true);
            options.persistent = flag(json, "persistent", true);
            options.cleanEntities = flag(json, "clean_entities", true);
            options.cutouts = flag(json, "cutouts", true);
            options.target = oneOf(stringOf(json, "target", options.target), "kineticcore", "world", "zip");
            options.bothFormats = flag(json, "both_formats", false);
            return options;
        }
    }

    /** How and where the structure generates; field names follow the vanilla structure set and structure files. */
    public static final class Generation {
        /** Off: the structure set is published with a chance of 0, so the structure does not generate. */
        public boolean enabled = true;
        public List<String> dimensions = new ArrayList<>(List.of("minecraft:overworld"));
        /** Biomes and {@code #}tags it may start in; empty means every biome of the chosen dimensions. */
        public List<String> include = new ArrayList<>();
        public List<String> exclude = new ArrayList<>();
        public int spacing = 32;
        public int separation = 12;
        public float frequency = 0.6F;
        public int salt;
        /** {@code surface}: on the ground, lowered by the embedding; {@code absolute}: bottom at {@link #offset}. */
        public String height = "surface";
        /** Extra blocks up (or down) for {@code surface}, the bottom Y for {@code absolute}. */
        public int offset;
        public boolean rotate = true;
        /** Vanilla terrain adaptation: {@code none}, {@code bury}, {@code beard_thin}, {@code beard_box}. */
        public String terrain = "none";
        public String step = "surface_structures";
        /** Keep this many chunks away from another structure set; empty for none. */
        public String exclusionSet = "";
        public int exclusionChunks = 5;
        /** Mega structures: {@code fill_clear} fills a foundation down to the ground and clears the ground above, {@code fill}, {@code none}. */
        public String foundation = "fill_clear";
        public String foundationBlock = "minecraft:stone";
        /** Mega structures: other structures do not start inside the footprint. */
        public boolean suppressOthers = true;

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("enabled", enabled);
            json.add("dimensions", strings(dimensions));
            json.add("include", strings(include));
            json.add("exclude", strings(exclude));
            json.addProperty("spacing", spacing);
            json.addProperty("separation", separation);
            json.addProperty("frequency", frequency);
            json.addProperty("salt", salt);
            json.addProperty("height", height);
            json.addProperty("offset", offset);
            json.addProperty("rotate", rotate);
            json.addProperty("terrain", terrain);
            json.addProperty("step", step);
            json.addProperty("exclusion_set", exclusionSet);
            json.addProperty("exclusion_chunks", exclusionChunks);
            json.addProperty("foundation", foundation);
            json.addProperty("foundation_block", foundationBlock);
            json.addProperty("suppress_others", suppressOthers);
            return json;
        }

        static Generation fromJson(JsonObject json) {
            Generation generation = new Generation();
            generation.enabled = flag(json, "enabled", true);
            if (json.has("dimensions")) generation.dimensions = stringList(json.getAsJsonArray("dimensions"));
            if (json.has("include")) generation.include = stringList(json.getAsJsonArray("include"));
            if (json.has("exclude")) generation.exclude = stringList(json.getAsJsonArray("exclude"));
            generation.spacing = clamp(intOf(json, "spacing", generation.spacing), 1, 4096);
            generation.separation = clamp(intOf(json, "separation", generation.separation), 0, generation.spacing - 1);
            generation.frequency = Math.max(0F, Math.min(1F, json.has("frequency") ? json.get("frequency").getAsFloat() : generation.frequency));
            generation.salt = intOf(json, "salt", generation.salt);
            generation.height = oneOf(stringOf(json, "height", generation.height), "surface", "absolute");
            generation.offset = clamp(intOf(json, "offset", generation.offset), -4096, 4096);
            generation.rotate = !json.has("rotate") || json.get("rotate").getAsBoolean();
            generation.terrain = oneOf(stringOf(json, "terrain", generation.terrain), "none", "bury", "beard_thin", "beard_box");
            generation.step = stringOf(json, "step", generation.step);
            generation.exclusionSet = stringOf(json, "exclusion_set", "");
            generation.exclusionChunks = clamp(intOf(json, "exclusion_chunks", generation.exclusionChunks), 1, 16);
            generation.foundation = oneOf(stringOf(json, "foundation", generation.foundation), "fill_clear", "fill", "none");
            generation.foundationBlock = stringOf(json, "foundation_block", generation.foundationBlock);
            generation.suppressOthers = flag(json, "suppress_others", true);
            return generation;
        }
    }

    /**
     * The least separation, in chunks, at which two instances of a mega structure of this footprint cannot overlap:
     * vanilla's random spread puts two neighbouring instances at least the separation plus one chunk apart.
     */
    public int megaSeparation() {
        return (Math.max(size[0], size[2]) + 15) / 16;
    }

    /** {@code small}, {@code medium} or {@code mega}, by footprint. */
    public String tier() {
        int footprint = Math.max(size[0], size[2]);
        return footprint <= SMALL_LIMIT ? "small" : footprint <= MEDIUM_LIMIT ? "medium" : "mega";
    }

    public String namespace() {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    public String path() {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    public int tilesX() {
        return (size[0] + tileSize - 1) / tileSize;
    }

    public int tilesZ() {
        return (size[2] + tileSize - 1) / tileSize;
    }

    /** Whether a string is a usable structure id: lower-case namespace and path as vanilla allows. */
    public static boolean validId(String id) {
        if (id == null) return false;
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) return false;
        String namespace = id.substring(0, colon), path = id.substring(colon + 1);
        return namespace.matches("[a-z0-9_.-]{1,64}") && path.matches("[a-z0-9_.-]+(/[a-z0-9_.-]+)*") && path.length() <= 128
                && !path.contains("..");
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("name", name);
        json.addProperty("created_version", createdVersion);
        json.addProperty("data_version", dataVersion);
        JsonObject source = new JsonObject();
        source.addProperty("dimension", dimension);
        source.add("min", ints(origin));
        json.add("source", source);
        json.add("size", ints(size));
        json.addProperty("tile_size", tileSize);
        json.addProperty("embed", embed);
        json.addProperty("air", air);
        json.add("ignore_blocks", strings(ignoreBlocks));
        json.addProperty("status", status);
        JsonObject counts = new JsonObject();
        counts.addProperty("blocks", blocks);
        counts.addProperty("block_entities", blockEntities);
        counts.addProperty("containers", containers);
        counts.addProperty("entities", entities);
        counts.addProperty("entity_chunks_skipped", entityChunksSkipped);
        json.add("counts", counts);
        JsonObject missingJson = new JsonObject();
        missing.forEach(missingJson::addProperty);
        json.add("missing", missingJson);
        json.addProperty("scanned_at", scannedAt);
        json.addProperty("published_at", publishedAt);
        json.addProperty("published_root", publishedRoot);
        json.add("generation", generation.toJson());
        json.add("save_options", save.toJson());
        return json;
    }

    public static StructureProject fromJson(JsonObject json) {
        StructureProject project = new StructureProject();
        project.id = stringOf(json, "id", "").toLowerCase(Locale.ROOT);
        project.name = stringOf(json, "name", project.id);
        project.createdVersion = stringOf(json, "created_version", "");
        project.dataVersion = intOf(json, "data_version", 0);
        if (json.has("source")) {
            JsonObject source = json.getAsJsonObject("source");
            project.dimension = stringOf(source, "dimension", project.dimension);
            if (source.has("min")) project.origin = intArray(source.getAsJsonArray("min"), 3);
        }
        if (json.has("size")) project.size = intArray(json.getAsJsonArray("size"), 3);
        project.tileSize = clamp(intOf(json, "tile_size", project.tileSize), 16, 64);
        project.embed = Math.max(0, intOf(json, "embed", 0));
        project.air = oneOf(stringOf(json, "air", project.air), "auto", "keep", "none");
        if (json.has("ignore_blocks")) project.ignoreBlocks = stringList(json.getAsJsonArray("ignore_blocks"));
        project.status = oneOf(stringOf(json, "status", project.status), "draft", "published", "modified");
        if (json.has("counts")) {
            JsonObject counts = json.getAsJsonObject("counts");
            project.blocks = intOf(counts, "blocks", 0);
            project.blockEntities = intOf(counts, "block_entities", 0);
            project.containers = intOf(counts, "containers", 0);
            project.entities = intOf(counts, "entities", 0);
            project.entityChunksSkipped = intOf(counts, "entity_chunks_skipped", 0);
        }
        if (json.has("missing")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("missing").entrySet()) {
                project.missing.put(entry.getKey(), entry.getValue().getAsInt());
            }
        }
        project.scannedAt = json.has("scanned_at") ? json.get("scanned_at").getAsLong() : 0L;
        project.publishedAt = json.has("published_at") ? json.get("published_at").getAsLong() : 0L;
        project.publishedRoot = stringOf(json, "published_root", "");
        if (json.has("generation")) project.generation = Generation.fromJson(json.getAsJsonObject("generation"));
        if (json.has("save_options")) project.save = SaveOptions.fromJson(json.getAsJsonObject("save_options"));
        return project;
    }

    static int intOf(JsonObject json, String key, int fallback) {
        try {
            return json.has(key) ? json.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static boolean flag(JsonObject json, String key, boolean fallback) {
        try {
            return json.has(key) ? json.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static String stringOf(JsonObject json, String key, String fallback) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
    }

    static String oneOf(String value, String... allowed) {
        for (String option : allowed) if (option.equals(value)) return value;
        return allowed[0];
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    static List<String> stringList(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) values.add(element.getAsString());
        return values;
    }

    static JsonArray ints(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) array.add(value);
        return array;
    }

    static int[] intArray(JsonArray array, int length) {
        int[] values = new int[length];
        for (int i = 0; i < length && i < array.size(); i++) values[i] = array.get(i).getAsInt();
        return values;
    }
}
