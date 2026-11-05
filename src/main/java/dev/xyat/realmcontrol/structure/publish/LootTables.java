package dev.xyat.realmcontrol.structure.publish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.project.Tile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Loot tables for structure containers. New tables are files in the KineticCore data pack {@code realmcontrol}
 * ({@code data/<namespace>/loot_tables/...}, {@code loot_table} on 1.21), so they load with the structure when a
 * world is entered and ContentStudio's loot editor edits the file itself. KineticCore loads tables of its data packs
 * without the entries whose item is missing, so a removed mod only takes its own entries away.
 */
public final class LootTables {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    //? if >=1.21 {
    /*public static final String FOLDER = "loot_table", OTHER_FOLDER = "loot_tables";
    *///?} else {
    public static final String FOLDER = "loot_tables", OTHER_FOLDER = "loot_table";
    //?}

    /** How a new table starts. */
    public enum Kind {
        EMPTY, COPY, ITEMS
    }

    private LootTables() {
    }

    /** The table's file in the realmcontrol pack, in this version's folder. */
    public static Path packFile(ResourceLocation id) {
        return Publisher.packFolder().resolve("data").resolve(id.getNamespace()).resolve(FOLDER).resolve(id.getPath() + ".json");
    }

    /** Every loot table the server has loaded, and the pack's table files written since. */
    public static List<String> ids(MinecraftServer server) {
        TreeSet<String> ids = new TreeSet<>();
        loaded(server).forEach(id -> ids.add(id.toString()));
        Path data = Publisher.packFolder().resolve("data");
        if (Files.isDirectory(data)) {
            try (var namespaces = Files.list(data)) {
                for (Path namespace : namespaces.filter(Files::isDirectory).toList()) {
                    Path folder = namespace.resolve(FOLDER);
                    if (!Files.isDirectory(folder)) continue;
                    try (var files = Files.walk(folder)) {
                        files.filter(file -> file.getFileName().toString().endsWith(".json")).forEach(file -> {
                            String path = folder.relativize(file).toString().replace('\\', '/');
                            ids.add(namespace.getFileName() + ":" + path.substring(0, path.length() - 5));
                        });
                    }
                }
            } catch (IOException ignored) {
                // The loaded tables are still listed.
            }
        }
        return new ArrayList<>(ids);
    }

    private static java.util.Collection<ResourceLocation> loaded(MinecraftServer server) {
        //? if >=26.1 {
        /*return ((net.minecraft.core.RegistryAccess) server.reloadableRegistries().lookup()).lookupOrThrow(net.minecraft.core.registries.Registries.LOOT_TABLE).keySet();
        *///?} else if >=1.21 {
        /*return server.reloadableRegistries().getKeys(net.minecraft.core.registries.Registries.LOOT_TABLE);
        *///?} else {
        return server.getLootData().getKeys(net.minecraft.world.level.storage.loot.LootDataType.TABLE);
        //?}
    }

    /** Whether a table is loaded or waits in the pack for the next world load. */
    public static boolean exists(MinecraftServer server, String table) {
        ResourceLocation id = ResourceLocation.tryParse(table);
        return id != null && (loaded(server).contains(id) || Files.isRegularFile(packFile(id)));
    }

    /**
     * Item ids a table of the pack names that this game does not know; these entries are left out when it loads.
     * Tables from elsewhere are not checked: vanilla and mods ship working tables.
     */
    public static List<String> missingItems(String table) {
        ResourceLocation id = ResourceLocation.tryParse(table);
        List<String> missing = new ArrayList<>();
        if (id == null || !Files.isRegularFile(packFile(id))) return missing;
        try {
            collectMissing(JsonParser.parseString(Files.readString(packFile(id), StandardCharsets.UTF_8)), missing);
        } catch (IOException | RuntimeException ignored) {
            // A broken file is reported when it loads.
        }
        return missing;
    }

    private static void collectMissing(JsonElement element, List<String> missing) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectMissing(child, missing);
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            String type = object.has("type") && object.get("type").isJsonPrimitive() ? object.get("type").getAsString() : "";
            if ((type.equals("minecraft:item") || type.equals("item")) && object.has("name") && object.get("name").isJsonPrimitive()) {
                ResourceLocation item = ResourceLocation.tryParse(object.get("name").getAsString());
                if (item == null || !BuiltInRegistries.ITEM.containsKey(item)) missing.add(object.get("name").getAsString());
            }
            for (Map.Entry<String, JsonElement> child : object.entrySet()) collectMissing(child.getValue(), missing);
        }
    }

    /**
     * Writes a new table {@code table} into the pack: empty, a copy of {@code source}, or one equal-weight entry per
     * kind of item in the container at {@code pos} of draft {@code projectId}. Returns the problem, or {@code null}.
     */
    @Nullable
    public static Component create(MinecraftServer server, String table, Kind kind, String source, String projectId, int[] pos) {
        ResourceLocation id = ResourceLocation.tryParse(table);
        if (id == null || !StructureProject.validId(table)) return KineticI18n.translatable("msg.realmcontrol.structure.loot.bad_id", table);
        if (exists(server, table)) return KineticI18n.translatable("msg.realmcontrol.structure.loot.exists", table);
        JsonObject json;
        try {
            json = switch (kind) {
                case EMPTY -> emptyTable();
                case COPY -> read(server, source);
                case ITEMS -> fromItems(projectId, pos);
            };
        } catch (IOException | RuntimeException e) {
            return KineticI18n.translatable("msg.realmcontrol.structure.loot.read_failed", source, String.valueOf(e.getMessage()));
        }
        if (json == null) return KineticI18n.translatable("msg.realmcontrol.structure.loot.no_source", source);
        try {
            write(packFile(id), json);
            Publisher.ensurePackMeta(Publisher.packFolder(), false);
        } catch (IOException e) {
            return KineticI18n.translatable("msg.realmcontrol.structure.project.save_failed", String.valueOf(e.getMessage()));
        }
        return null;
    }

    private static JsonObject emptyTable() {
        JsonObject pool = new JsonObject();
        pool.addProperty("rolls", 1);
        pool.add("entries", new JsonArray());
        JsonArray pools = new JsonArray();
        pools.add(pool);
        JsonObject json = new JsonObject();
        json.addProperty("type", "minecraft:chest");
        json.add("pools", pools);
        return json;
    }

    /** A table's JSON: the pack's file when there is one, else the resource the server loaded it from. */
    @Nullable
    private static JsonObject read(MinecraftServer server, String table) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(table);
        if (id == null) return null;
        if (Files.isRegularFile(packFile(id))) return JsonParser.parseString(Files.readString(packFile(id), StandardCharsets.UTF_8)).getAsJsonObject();
        Optional<Resource> resource = server.getResourceManager().getResource(
                ResourceLocation.tryParse(id.getNamespace() + ":" + FOLDER + "/" + id.getPath() + ".json"));
        if (resource.isEmpty()) return null;
        try (Reader reader = resource.get().openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    /**
     * One pool with an entry per kind of item the container held, all of the same weight, each with the counts its
     * stacks had and the data of its first stack; as many rolls as the container had stacks.
     */
    private static JsonObject fromItems(String projectId, int[] pos) throws IOException {
        StructureProject project = ProjectStore.load(projectId);
        if (project == null) throw new IOException("no draft " + projectId);
        int tileX = pos[0] / project.tileSize, tileZ = pos[2] / project.tileSize;
        Tile tile = ProjectStore.readTile(projectId, tileX, tileZ);
        if (tile == null) throw new IOException("no tile");
        CompoundTag data = tile.blockEntities.get(tile.cell(pos[0] - tileX * project.tileSize, pos[1], pos[2] - tileZ * project.tileSize));
        if (data == null) throw new IOException("no container");
        ListTag list = data.getList("Items", Tag.TAG_COMPOUND);
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, JsonObject> extras = new LinkedHashMap<>();
        int stacks = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            String item = tag.getString("id");
            int count = itemCount(tag);
            if (item.isEmpty() || count <= 0) continue;
            stacks++;
            int[] range = counts.computeIfAbsent(item, key -> new int[]{count, count});
            range[0] = Math.min(range[0], count);
            range[1] = Math.max(range[1], count);
            if (!extras.containsKey(item)) extras.put(item, itemData(tag));
        }
        JsonArray entries = new JsonArray();
        for (Map.Entry<String, int[]> item : counts.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "minecraft:item");
            entry.addProperty("name", item.getKey());
            entry.addProperty("weight", 1);
            JsonArray functions = new JsonArray();
            int[] range = item.getValue();
            if (range[0] != 1 || range[1] != 1) {
                JsonObject setCount = new JsonObject();
                setCount.addProperty("function", "minecraft:set_count");
                if (range[0] == range[1]) {
                    setCount.addProperty("count", range[0]);
                } else {
                    JsonObject uniform = new JsonObject();
                    uniform.addProperty("type", "minecraft:uniform");
                    uniform.addProperty("min", range[0]);
                    uniform.addProperty("max", range[1]);
                    setCount.add("count", uniform);
                }
                functions.add(setCount);
            }
            JsonObject extra = extras.get(item.getKey());
            if (extra != null) functions.add(extra);
            if (!functions.isEmpty()) entry.add("functions", functions);
            entries.add(entry);
        }
        JsonObject table = emptyTable();
        JsonObject pool = table.getAsJsonArray("pools").get(0).getAsJsonObject();
        pool.addProperty("rolls", Math.max(1, stacks));
        pool.add("entries", entries);
        return table;
    }

    private static int itemCount(CompoundTag tag) {
        //? if >=1.20.5 {
        /*return tag.getInt("count");
        *///?} else {
        return tag.getByte("Count");
        //?}
    }

    /** The stack's extra data (enchantments, names...) as a loot function, or {@code null}. */
    @Nullable
    private static JsonObject itemData(CompoundTag tag) {
        //? if >=1.20.5 {
        /*if (!(tag.get("components") instanceof CompoundTag components) || components.isEmpty()) return null;
        JsonObject function = new JsonObject();
        function.addProperty("function", "minecraft:set_components");
        function.add("components", net.minecraft.nbt.NbtOps.INSTANCE.convertTo(com.mojang.serialization.JsonOps.INSTANCE, components));
        return function;
        *///?} else {
        if (!tag.contains("tag", Tag.TAG_COMPOUND)) return null;
        JsonObject function = new JsonObject();
        function.addProperty("function", "minecraft:set_nbt");
        function.addProperty("tag", tag.getCompound("tag").toString());
        return function;
        //?}
    }

    /**
     * The table for the other format's folder: the item data function the other version does not know is left
     * out ({@code set_nbt} became {@code set_components} in 1.20.5).
     */
    static JsonObject otherFormat(JsonObject table) {
        JsonObject copy = table.deepCopy();
        stripFunctions(copy);
        return copy;
    }

    private static void stripFunctions(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = array.size() - 1; i >= 0; i--) {
                JsonElement child = array.get(i);
                if (child.isJsonObject() && child.getAsJsonObject().has("function")) {
                    String function = child.getAsJsonObject().get("function").getAsString();
                    if (function.endsWith("set_nbt") || function.endsWith("set_components")) {
                        array.remove(i);
                        continue;
                    }
                }
                stripFunctions(child);
            }
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> child : element.getAsJsonObject().entrySet()) stripFunctions(child.getValue());
        }
    }

    static JsonObject readFile(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static void write(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Container markers of a draft that use tables, by table, for the publish check. */
    static List<String> unknownTables(MinecraftServer server, Markers markers) {
        List<String> unknown = new ArrayList<>();
        for (String table : markers.tables()) if (!exists(server, table)) unknown.add(table);
        return unknown;
    }
}
