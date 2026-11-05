package dev.xyat.realmcontrol.worldgen.data;

import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;

import java.util.List;

public record StructureRuleDescriptor(
        String structureId,
        String structureSetId,
        String placementType,
        int originalWeight,
        float originalFrequency,
        int originalSalt,
        Integer originalSpacing,
        Integer originalSeparation,
        String originalSpreadType,
        Integer originalDistance,
        Integer originalSpread,
        Integer originalCount,
        List<String> dimensionIds,
        StructureEntryRule entryRule,
        StructurePlacementRule placementRule,
        /** Published by the structure editor: its rules are its draft's, edited on the draft's generation page. */
        boolean custom
) {
    public StructureRuleDescriptor {
        dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
    }

    public boolean supportsPlacementEditing() {
        return "random_spread".equals(placementType) || "concentric_rings".equals(placementType);
    }
}
