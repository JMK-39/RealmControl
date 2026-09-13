package dev.xyat.realmcontrol.worldgen.config;

public final class BiomeRuleCodec {
    private static final String SEP = "|";

    private BiomeRuleCodec() {
    }

    public static String encode(BiomeReplacementRule rule) {
        if (rule == null) {
            return "";
        }
        return rule.dimensionId() + SEP + rule.source() + SEP + rule.target();
    }

    public static BiomeReplacementRule decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }
        BiomeReplacementRule rule = new BiomeReplacementRule(parts[0], parts[1], parts[2]);
        return rule.isEmpty() ? null : rule;
    }
}
