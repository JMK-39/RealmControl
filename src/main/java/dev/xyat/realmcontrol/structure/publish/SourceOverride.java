package dev.xyat.realmcontrol.structure.publish;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.xyat.realmcontrol.structure.mega.MegaStructure;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Owns only the original-ID overlay files written by a RealmControl project. */
final class SourceOverride {
    private static final List<String> SOURCE_SETTINGS = List.of("biomes", "step", "spawn_overrides", "terrain_adaptation");
    //? if >=1.21 {
    /*private static final List<String> JIGSAW_SITE = List.of("start_height", "project_start_to_heightmap", "dimension_padding", "liquid_settings");
    *///?} else {
    private static final List<String> JIGSAW_SITE = List.of("start_height", "project_start_to_heightmap");
    //?}

    private SourceOverride() {
    }

    static final class Conflict extends IOException {
        Conflict(String message) {
            super(message);
        }
    }

    record State(Path root, ResourceLocation source, String owner, JsonObject settings, String definitionHash, String setHash) {
        Path megaSet() {
            ResourceLocation project = Objects.requireNonNull(ResourceLocation.tryParse(owner));
            return resource(root, project, "structure_set");
        }

        void verify() throws IOException {
            if (!equals(read(root, source))) throw new Conflict(receipt(root, source).toString());
            checkTarget(definition(root, source), definitionHash);
            if (!setHash.isEmpty()) checkTarget(megaSet(), setHash);
        }

        List<Path> artifacts() {
            List<Path> files = new ArrayList<>(3);
            files.add(definition(root, source));
            if (!setHash.isEmpty()) files.add(megaSet());
            files.add(receipt(root, source));
            return files;
        }
    }

    record Plan(@Nullable ResourceLocation source, @Nullable JsonObject original, int biomeCount, List<State> previous) {
        boolean enabled() {
            return original != null;
        }

        @Nullable State at(Path root) {
            return previous.stream().filter(state -> state.root().equals(root)).findFirst().orElse(null);
        }

        void verify(Path root) throws IOException {
            for (State state : previous) state.verify();
            if (enabled() && at(root) == null) checkTarget(definition(root, source), null);
        }

        List<Path> stale(List<Path[]> moves) {
            List<Path> files = new ArrayList<>();
            for (State state : previous) files.addAll(state.artifacts());
            for (Path[] move : moves) files.remove(move[1]);
            return files;
        }
    }

    static List<Path> installedRoots(MinecraftServer server) {
        return List.of(Publisher.packFolder().toAbsolutePath().normalize(),
                server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(Publisher.PACK).toAbsolutePath().normalize());
    }

    @Nullable
    static Plan prepare(MinecraftServer server, StructureProject project, Path root, boolean archive) throws IOException {
        if (!StructureProject.validId(project.sourceStructure)) return new Plan(null, null, 0, List.of());
        ResourceLocation source = Objects.requireNonNull(ResourceLocation.tryParse(project.sourceStructure));
        List<State> previous = new ArrayList<>();
        JsonObject original = null;
        if (!archive || project.save.replaceSource) {
            for (Path installed : installedRoots(server)) {
                State state = read(installed, source);
                if (state == null) continue;
                if (archive) {
                    if (original == null) original = state.settings();
                    continue;
                }
                if (state.owner().equals(project.id)) {
                    previous.add(state);
                    if (original == null || installed.equals(root)) original = state.settings();
                } else if (project.save.replaceSource) {
                    throw new Conflict(project.sourceStructure + " → " + state.owner());
                }
            }
        }
        if (!project.save.replaceSource) return new Plan(source, null, 0, previous);
        var holder = server.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(ResourceKey.create(Registries.STRUCTURE, source));
        if (holder.isEmpty()) return null;
        Structure structure = holder.get().value();
        if (original == null) {
            if (structure instanceof MegaStructure) throw new Conflict(project.sourceStructure + " (realmcontrol:mega)");
            var settings = new Structure.StructureSettings(structure.biomes(), structure.spawnOverrides(), structure.step(), structure.terrainAdaptation());
            var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            var encoded = Structure.StructureSettings.CODEC.codec().encodeStart(ops, settings);
            JsonElement value = encoded.result().orElse(null);
            if (value == null || !value.isJsonObject()) throw new Conflict(project.sourceStructure + " (settings)");
            original = value.getAsJsonObject();
            if (structure.type() == StructureType.JIGSAW) {
                JsonElement layout = Structure.DIRECT_CODEC.encodeStart(ops, structure).result().orElse(null);
                if (layout instanceof JsonObject definition && definition.has("start_height")) {
                    JsonObject site = new JsonObject();
                    for (String key : JIGSAW_SITE) if (definition.has(key)) site.add(key, definition.get(key));
                    original.add("jigsaw_site", site);
                }
            }
        }
        return new Plan(source, original, structure.biomes().size(), previous);
    }

    static JsonObject merge(JsonObject draft, JsonObject original, boolean mega) {
        JsonObject definition = draft.deepCopy();
        for (String key : SOURCE_SETTINGS) {
            if (mega && "terrain_adaptation".equals(key)) continue;
            if (original.has(key)) definition.add(key, original.get(key).deepCopy());
            else if ("terrain_adaptation".equals(key)) definition.addProperty(key, "none");
        }
        if (!mega && original.get("jigsaw_site") instanceof JsonObject site && site.has("start_height")) {
            for (String key : JIGSAW_SITE) {
                if (site.has(key)) definition.add(key, site.get(key).deepCopy());
                else definition.remove(key);
            }
        }
        return definition;
    }

    static Path definition(Path root, ResourceLocation source) {
        return resource(root, source, "structure");
    }

    static Path resource(Path root, ResourceLocation id, String kind) {
        return root.resolve("data").resolve(id.getNamespace()).resolve("worldgen").resolve(kind).resolve(id.getPath() + ".json");
    }

    private static Path receipt(Path root, ResourceLocation source) {
        return root.resolve(".realmcontrol-source-overrides").resolve(source.getNamespace()).resolve(source.getPath() + ".json");
    }

    @Nullable
    static State read(Path root, ResourceLocation source) throws IOException {
        Path path = receipt(root, source);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new Conflict(path.toString());
        try (var reader = Files.newBufferedReader(path)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            String owner = json.get("project").getAsString();
            String definitionHash = json.get("definition").getAsString();
            String setHash = json.get("mega_set").getAsString();
            JsonObject settings = json.getAsJsonObject("source_settings");
            if (!StructureProject.validId(owner) || !validHash(definitionHash)
                    || (!setHash.isEmpty() && !validHash(setHash)) || settings == null || !settings.has("biomes")) {
                throw new Conflict(path.toString());
            }
            return new State(root, source, owner, settings, definitionHash, setHash);
        } catch (JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException error) {
            throw new Conflict(path.toString());
        }
    }

    private static boolean validHash(String hash) {
        return hash.matches("[a-f0-9]{64}");
    }

    static void stage(Path root, ResourceLocation source, String owner, JsonObject original, JsonObject definition,
                      @Nullable Path stagedMegaSet, List<Path[]> moves) throws IOException {
        Path target = definition(root, source);
        Path stagedDefinition = Publisher.staged(target, moves);
        Publisher.writeJson(stagedDefinition, definition);
        JsonObject ownerRecord = new JsonObject();
        ownerRecord.addProperty("project", owner);
        ownerRecord.addProperty("definition", hash(stagedDefinition));
        ownerRecord.addProperty("mega_set", stagedMegaSet == null ? "" : hash(stagedMegaSet));
        ownerRecord.add("source_settings", original);
        Publisher.writeJson(Publisher.staged(receipt(root, source), moves), ownerRecord);
    }

    static void checkTarget(Path target, @Nullable String previous) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        if (previous == null || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !previous.equals(hash(target))) {
            throw new Conflict(target.toString());
        }
    }

    private static String hash(Path file) throws IOException {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("Missing SHA-256", unavailable);
        }
        try (var input = new DigestInputStream(Files.newInputStream(file), sha256)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(sha256.digest());
    }
}
