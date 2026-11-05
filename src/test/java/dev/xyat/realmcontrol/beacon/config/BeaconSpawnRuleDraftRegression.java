package dev.xyat.realmcontrol.beacon.config;

import java.util.List;

/** Exercises the same draft used by the visual editor without starting a Minecraft client. */
public final class BeaconSpawnRuleDraftRegression {
    public static void main(String[] args) {
        var original = List.of(" minecraft:zombie ;AX;one", "minecraft:zombie;BE;two",
                "validation:missing;Qz", "legacy-unparsed-rule", "minecraft:pig;");
        var draft = new BeaconSpawnRuleDraft(original);
        var all = draft.entityIds(List.of("minecraft:zombie", "minecraft:cow", "minecraft:pig"));
        require(all.containsAll(List.of("minecraft:zombie", "minecraft:cow", "minecraft:pig", "validation:missing", "legacy-unparsed-rule")),
                "the browser must include every registry entity and retain unavailable/malformed saved entries");
        require(all.stream().filter("minecraft:zombie"::equals).count() == 1, "duplicate rules share one entity card");
        require(draft.lines().equals(original), "enumerating and inspecting entries must preserve every raw line");
        require(draft.codes("minecraft:cow").equals("A") && !draft.configured("minecraft:cow"), "unconfigured mobs use natural spawning without creating a rule");
        require(draft.invalid("minecraft:zombie") && draft.invalid("minecraft:pig"), "unknown codes and malformed saved rules are errors");
        require(!draft.invalid("minecraft:cow"), "a registry mob with no rule is valid");

        draft.setBlocked("minecraft:cow", 'H', true);
        require(draft.configured("minecraft:cow") && draft.codes("minecraft:cow").equals("AH"), "first real edit retains default A and creates only the edited rule");
        require(draft.lines().equals(List.of(" minecraft:zombie ;AX;one", "minecraft:zombie;BE;two", "validation:missing;Qz",
                "legacy-unparsed-rule", "minecraft:pig;", "minecraft:cow;AH")), "editing another mob must not rewrite unknown, malformed or duplicate lines");
        draft.setBlocked("minecraft:zombie", 'B', false);
        require(draft.lines().contains(" minecraft:zombie ;AX;one") && draft.lines().contains("minecraft:zombie;E;two"),
                "only the last effective duplicate is changed and its opaque suffix survives");
        require(draft.lines().contains("validation:missing;Qz"), "unavailable IDs survive unrelated edits");

        var cleared = new BeaconSpawnRuleDraft(List.of("minecraft:zombie;EX;one", "minecraft:zombie;B"));
        cleared.setBlocked("minecraft:zombie", 'B', false);
        require(cleared.lines().equals(List.of("minecraft:zombie;EX;one", "minecraft:zombie;A")), "clearing the effective duplicate must not reactivate an older rule");
        var fresh = new BeaconSpawnRuleDraft(List.of());
        fresh.setBlocked("minecraft:cow", 'A', false);
        require(fresh.codes("minecraft:cow").equals("A") && fresh.lines().isEmpty(), "clearing all reasons keeps the existing natural-spawn fallback");
        require(fresh.configured("minecraft:cow"), "a mob the user modified retains its modified card state");
        fresh.setBlocked("minecraft:cow", 'E', true);
        require(fresh.lines().equals(List.of("minecraft:cow;AE")), "editing after clearing starts from the visible natural-spawn fallback");
        draft.remove("validation:missing");
        require(!draft.lines().contains("validation:missing;Qz") && draft.lines().contains("legacy-unparsed-rule"),
                "explicit removal affects only the selected entity");
        require(original.equals(new BeaconSpawnRuleDraft(original).lines()), "discarding a draft leaves the original list intact");
        System.out.println("BeaconSpawnRuleDraftRegression: all-entity browsing, default A, errors and lossless draft edits passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
