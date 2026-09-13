package dev.xyat.realmcontrol.worldgen.config;

public record BiomeReplacementRule(String dimensionId, String source, String target) {
    public static final String ALL_DIMENSIONS = "*";
    public static final String REMOVE_TARGET = "null";

    public BiomeReplacementRule {
        dimensionId = normalizeDimension(dimensionId);
        source = source == null ? "" : source.trim();
        target = target == null || target.isBlank() ? REMOVE_TARGET : target.trim();
    }

    public boolean isRemoval() {
        return REMOVE_TARGET.equalsIgnoreCase(target);
    }

    public boolean matchesDimension(String dimension) {
        return ALL_DIMENSIONS.equals(dimensionId) || dimensionId.equals(dimension);
    }

    public boolean isEmpty() {
        return source.isBlank();
    }

    private static String normalizeDimension(String value) {
        if (value == null || value.isBlank()) {
            return ALL_DIMENSIONS;
        }
        return value.trim();
    }
}
