package dev.xyat.realmcontrol.worldgen.config;

import java.util.Locale;
import java.util.Objects;

public record StructurePlacementRule(
        String structureSetId,
        Float frequency,
        Integer salt,
        Integer spacing,
        Integer separation,
        String spreadType,
        Integer distance,
        Integer spread,
        Integer count
) {
    public StructurePlacementRule {
        structureSetId = Objects.requireNonNull(structureSetId, "structureSetId").trim();
        if (spreadType != null) spreadType = spreadType.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isEmpty() {
        return frequency == null
                && salt == null
                && spacing == null
                && separation == null
                && spreadType == null
                && distance == null
                && spread == null
                && count == null;
    }
}
