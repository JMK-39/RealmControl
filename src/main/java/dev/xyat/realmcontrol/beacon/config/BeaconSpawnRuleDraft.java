package dev.xyat.realmcontrol.beacon.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Lossless client draft: registry browsing never adds a stored rule. */
final class BeaconSpawnRuleDraft {
    static final String CODES = "ABCDEFGH";
    private final List<String> initial;
    private final Map<String, String> rules = new LinkedHashMap<>();
    private final Set<String> edited = new LinkedHashSet<>();
    private final Set<String> removed = new HashSet<>();

    BeaconSpawnRuleDraft(List<String> initial) {
        this.initial = List.copyOf(initial);
        for (String line : initial) {
            String id = ruleId(line);
            if (id != null) rules.put(id, line.substring(line.indexOf(';') + 1));
        }
    }

    List<String> entityIds(Collection<String> registered) {
        var ids = new LinkedHashSet<String>();
        // Saved entries stay easy to find, including malformed lines and unavailable mods.
        for (String line : initial) ids.add(lineId(line));
        registered.stream().sorted(String::compareToIgnoreCase).forEach(ids::add);
        ids.addAll(edited);
        return List.copyOf(ids);
    }

    boolean hasRule(String id) {
        return !removed.contains(id) && (rules.containsKey(id) || initial.stream().anyMatch(line -> lineId(line).equals(id)));
    }

    boolean configured(String id) { return hasRule(id) || edited.contains(id); }

    String storedCodes(String id) { return rules.getOrDefault(id, ""); }

    String codes(String id) {
        String stored = storedCodes(id);
        return stored.isEmpty() ? "A" : ruleCodes(stored).toUpperCase(Locale.ROOT);
    }

    void setBlocked(String id, char code, boolean blocked) {
        String current = storedCodes(id);
        if (current.isEmpty()) current = "A";
        String codes = ruleCodes(current);
        var updated = new StringBuilder();
        for (char stored : codes.toCharArray()) {
            if (Character.toUpperCase(stored) != code) updated.append(stored);
        }
        if (blocked) updated.append(code);
        // Only the first field is interpreted by the runtime. Keep opaque legacy fields verbatim.
        updated.append(current.substring(codes.length()));
        rules.put(id, updated.toString());
        edited.add(id);
        removed.remove(id);
    }

    void remove(String id) {
        rules.remove(id);
        edited.add(id);
        removed.add(id);
    }

    boolean invalid(String id) {
        for (String line : lines()) {
            if (!lineId(line).equals(id)) continue;
            if (ruleId(line) == null) return true;
            String codes = ruleCodes(line.substring(line.indexOf(';') + 1)).trim().toUpperCase(Locale.ROOT);
            for (char code : codes.toCharArray()) if (CODES.indexOf(code) < 0) return true;
        }
        return false;
    }

    List<String> lines() {
        var result = new ArrayList<String>();
        var remaining = new LinkedHashMap<>(rules);
        var lastLine = new LinkedHashMap<String, Integer>();
        for (int index = 0; index < initial.size(); index++) {
            String id = ruleId(initial.get(index));
            if (id != null) lastLine.put(id, index);
        }
        var earlier = new HashSet<String>();
        for (int index = 0; index < initial.size(); index++) {
            String line = initial.get(index);
            if (removed.contains(lineId(line))) continue;
            String id = ruleId(line);
            if (id == null) { result.add(line); continue; }
            if (!rules.containsKey(id)) continue;
            if (!edited.contains(id) || lastLine.get(id) != index) {
                result.add(line);
                if (lastLine.get(id) == index) remaining.remove(id);
            } else {
                String codes = remaining.remove(id);
                if (!codes.isEmpty()) result.add(id + ";" + codes);
                // Clearing the last duplicate must not reactivate an earlier opaque rule.
                else if (earlier.contains(id)) result.add(id + ";A");
            }
            earlier.add(id);
        }
        remaining.forEach((id, codes) -> { if (!codes.isEmpty()) result.add(id + ";" + codes); });
        return result;
    }

    private static String lineId(String line) {
        int separator = line.indexOf(';');
        return (separator < 0 ? line : line.substring(0, separator)).trim();
    }

    private static String ruleId(String line) {
        // Match the runtime: trailing empty fields alone do not form a saved rule.
        return line.indexOf(';') < 0 || line.split(";").length < 2 || lineId(line).isEmpty() ? null : lineId(line);
    }

    static String ruleCodes(String stored) {
        int separator = stored.indexOf(';');
        return separator < 0 ? stored : stored.substring(0, separator);
    }
}
