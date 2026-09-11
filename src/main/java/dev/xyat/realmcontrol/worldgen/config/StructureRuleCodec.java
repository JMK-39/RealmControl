package dev.xyat.realmcontrol.worldgen.config;

public final class StructureRuleCodec {
    private static final String NULL = "~";
    private static final String VERSION = "1";

    private StructureRuleCodec() {
    }

    public static String encodeEntry(StructureEntryRule rule) {
        return String.join("|",
                VERSION,
                rule.structureId(),
                Boolean.toString(rule.disabled()),
                encodeInteger(rule.weight())
        );
    }

    public static StructureEntryRule decodeEntry(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || parts[1].isBlank()) return null;
        try {
            boolean disabled = Boolean.parseBoolean(parts[2]);
            return new StructureEntryRule(parts[1], disabled, decodeInteger(parts[3]));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String encodePlacement(StructurePlacementRule rule) {
        return String.join("|",
                VERSION,
                rule.structureSetId(),
                encodeFloat(rule.frequency()),
                encodeInteger(rule.salt()),
                encodeInteger(rule.spacing()),
                encodeInteger(rule.separation()),
                encodeString(rule.spreadType()),
                encodeInteger(rule.distance()),
                encodeInteger(rule.spread()),
                encodeInteger(rule.count())
        );
    }

    public static StructurePlacementRule decodePlacement(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        String[] parts = encoded.split("\\|", -1);
        if (parts.length != 10 || !VERSION.equals(parts[0]) || parts[1].isBlank()) return null;
        try {
            return new StructurePlacementRule(
                    parts[1],
                    decodeFloat(parts[2]),
                    decodeInteger(parts[3]),
                    decodeInteger(parts[4]),
                    decodeInteger(parts[5]),
                    decodeString(parts[6]),
                    decodeInteger(parts[7]),
                    decodeInteger(parts[8]),
                    decodeInteger(parts[9])
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String encodeInteger(Integer value) {
        return value == null ? NULL : Integer.toString(value);
    }

    private static Integer decodeInteger(String value) {
        return NULL.equals(value) || value.isBlank() ? null : Integer.valueOf(value);
    }

    private static String encodeFloat(Float value) {
        return value == null ? NULL : Float.toString(value);
    }

    private static Float decodeFloat(String value) {
        return NULL.equals(value) || value.isBlank() ? null : Float.valueOf(value);
    }

    private static String encodeString(String value) {
        return value == null || value.isBlank() ? NULL : value;
    }

    private static String decodeString(String value) {
        return NULL.equals(value) || value.isBlank() ? null : value;
    }
}
