package dev.xyat.realmcontrol.structure.publish;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The pack replacement keeps source settings and never claims a file it did not write. */
public final class SourceOverrideRegression {
    private SourceOverrideRegression() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject draft = JsonParser.parseString("""
                {"type":"minecraft:jigsaw","biomes":"#custom:new","step":"surface_structures",
                 "spawn_overrides":{},"terrain_adaptation":"none","start_pool":"custom:new/start"}
                """).getAsJsonObject();
        JsonObject original = JsonParser.parseString("""
                {"biomes":"#external:valid","step":"underground_structures","terrain_adaptation":"bury",
                 "spawn_overrides":{"monster":{"bounding_box":"piece","spawns":[{"type":"minecraft:zombie","weight":2,"minCount":1,"maxCount":1}]}}}
                """).getAsJsonObject();
        JsonObject small = SourceOverride.merge(draft, original, false);
        check(small.get("biomes").equals(original.get("biomes")), "source biome tag was lost");
        check(small.get("step").equals(original.get("step")), "source generation step was lost");
        check(small.get("spawn_overrides").equals(original.get("spawn_overrides")), "source spawning constraints were lost");
        check(small.get("terrain_adaptation").equals(original.get("terrain_adaptation")), "source jigsaw terrain was lost");
        check(small.get("start_pool").equals(draft.get("start_pool")), "replacement lost the edited template");
        check(SourceOverride.merge(draft, original, true).get("terrain_adaptation").getAsString().equals("none"),
                "giant structure cannot inherit vanilla terrain adaptation");
        JsonObject noAdaptation = original.deepCopy();
        noAdaptation.remove("terrain_adaptation");
        draft.addProperty("terrain_adaptation", "beard_box");
        check(SourceOverride.merge(draft, noAdaptation, false).get("terrain_adaptation").getAsString().equals("none"),
                "source's default terrain rule was lost when the codec omitted it");
        verifyJigsawSite(draft, original);

        ResourceLocation source = KineticResourceIds.of("external", "cave/home");
        Path root = Files.createTempDirectory("realm-source-override");
        Path definition = SourceOverride.definition(root, source);
        Files.createDirectories(definition.getParent());
        Files.writeString(definition, "another pack owner's file");
        boolean conflict = false;
        try {
            SourceOverride.checkTarget(definition, null);
        } catch (SourceOverride.Conflict expected) {
            conflict = true;
        }
        check(conflict, "an unowned structure definition would be overwritten");
        check(Files.readString(definition).equals("another pack owner's file"), "unowned contents changed");
        Files.delete(definition);
        List<Path[]> moves = new ArrayList<>();
        SourceOverride.stage(root, source, "custom:new", original, small, null, moves);
        Publisher.install(moves, List.of(), () -> {});
        SourceOverride.State state = SourceOverride.read(root, source);
        check(state != null && state.owner().equals("custom:new"), "source ownership was not recorded");
        state.verify();
        try {
            Publisher.install(List.of(), state.artifacts(), () -> { throw new java.io.IOException("simulate failed project save"); });
            throw new AssertionError("failed metadata did not fail artifact removal");
        } catch (java.io.IOException expected) {
            check(Files.isRegularFile(definition), "failed withdrawal lost the original-ID overlay");
        }
        state.verify();
        String previous = Files.readString(definition);
        Files.writeString(definition, "an external change");
        boolean edited = false;
        try {
            state.verify();
        } catch (SourceOverride.Conflict expected) {
            edited = true;
        }
        check(edited && Files.readString(definition).equals("an external change"),
                "an externally edited overlay was still treated as owned");
        Files.writeString(definition, previous);
        Publisher.install(List.of(), state.artifacts(), () -> {});
        check(!Files.exists(definition) && SourceOverride.read(root, source) == null, "withdrawal left owned files");
        System.out.println("REALM_SOURCE_OVERRIDE_REGRESSION_PASS");
    }

    private static void verifyJigsawSite(JsonObject draft, JsonObject original) {
        JsonObject originalSite = original.deepCopy();
        originalSite.add("jigsaw_site", JsonParser.parseString("""
                {"start_height":{"absolute":-48},"dimension_padding":{"bottom":12,"top":32},
                 "liquid_settings":"ignore_waterlogging"}
                """));
        JsonObject draftSite = draft.deepCopy();
        draftSite.add("start_height", JsonParser.parseString("{\"absolute\":1}"));
        draftSite.addProperty("project_start_to_heightmap", "WORLD_SURFACE_WG");
        JsonObject replacement = SourceOverride.merge(draftSite, originalSite, false);
        check(replacement.getAsJsonObject("start_height").get("absolute").getAsInt() == -48,
                "the underground jigsaw's height was lost");
        check(!replacement.has("project_start_to_heightmap"), "the underground jigsaw was moved to the surface");
        JsonObject site = originalSite.getAsJsonObject("jigsaw_site");
        site.addProperty("project_start_to_heightmap", "OCEAN_FLOOR_WG");
        replacement = SourceOverride.merge(draftSite, originalSite, false);
        check(replacement.get("project_start_to_heightmap").equals(site.get("project_start_to_heightmap")),
                "the original surface heightmap was lost");
        //? if >=1.21 {
        /*check(replacement.get("dimension_padding").equals(site.get("dimension_padding")), "dimension padding lost");
        check(replacement.get("liquid_settings").equals(site.get("liquid_settings")), "liquid settings lost");
        *///?} else {
        check(!replacement.has("dimension_padding") && !replacement.has("liquid_settings"),
                "the Forge format gained unsupported jigsaw fields");
        //?}
        check(SourceOverride.merge(draftSite, originalSite, true).get("start_height").equals(draftSite.get("start_height")),
                "the mega path inherited an unsupported source height provider");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
