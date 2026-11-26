package dev.xyat.realmcontrol.structure.project;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Editor links for completed placements and world captures. Stored in the world that owns those coordinates. */
public final class PlacedDrafts {
    public record Instance(String id, BlockPos min) {}

    public static String worldKey(ServerLevel level) {
        String identity = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize() + "|" + level.getSeed();
        return java.util.UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static void record(ServerLevel level, StructureProject project, BlockPos min) {
        String dimension = level.dimension().location().toString();
        Path file = file(level);
        try {
            JsonArray entries = read(file);
            for (int i = entries.size() - 1; i >= 0; i--) {
                JsonObject entry = entries.get(i).getAsJsonObject();
                if (entry.get("dimension").getAsString().equals(dimension) && pos(entry).equals(min)
                        && entry.get("id").getAsString().equals(project.id)) entries.remove(i);
            }
            JsonObject e = new JsonObject();
            e.addProperty("id", project.id);
            e.addProperty("dimension", dimension);
            e.addProperty("x", min.getX());
            e.addProperty("y", min.getY());
            e.addProperty("z", min.getZ());
            e.addProperty("width", project.size[0]);
            e.addProperty("height", project.size[1]);
            e.addProperty("depth", project.size[2]);
            entries.add(e);
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "placed-drafts-", ".tmp");
            try {
                Files.writeString(temp, entries.toString(), StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (RuntimeException | IOException e) {
            com.mojang.logging.LogUtils.getLogger().warn("Could not save editor link for placed draft {}", project.id, e);
        }
    }

    @Nullable
    public static Instance at(ServerLevel level, BlockPos target) {
        try {
            JsonArray entries = read(file(level));
            for (int i = entries.size() - 1; i >= 0; i--) {
                JsonObject e = entries.get(i).getAsJsonObject();
                if (!level.dimension().location().toString().equals(e.get("dimension").getAsString())) continue;
                BlockPos min = pos(e);
                int dx = target.getX() - min.getX(), dy = target.getY() - min.getY(), dz = target.getZ() - min.getZ();
                if (dx >= 0 && dy >= 0 && dz >= 0 && dx < e.get("width").getAsInt() && dy < e.get("height").getAsInt()
                        && dz < e.get("depth").getAsInt() && ProjectStore.exists(e.get("id").getAsString()))
                    return new Instance(e.get("id").getAsString(), min);
            }
        } catch (RuntimeException | IOException e) {
            com.mojang.logging.LogUtils.getLogger().warn("Could not read placed draft links", e);
        }
        return null;
    }

    private static BlockPos pos(JsonObject e) {
        return new BlockPos(e.get("x").getAsInt(), e.get("y").getAsInt(), e.get("z").getAsInt());
    }

    private static JsonArray read(Path file) throws IOException {
        if (!Files.exists(file)) return new JsonArray();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonArray();
        }
    }

    private static Path file(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("realmcontrol/placed_drafts.json");
    }

    private PlacedDrafts() {}
}
