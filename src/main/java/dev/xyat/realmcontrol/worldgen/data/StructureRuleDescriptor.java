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
        StructurePlacementRule placementRule
) {
    public StructureRuleDescriptor {
        dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
    }

    public boolean supportsPlacementEditing() {
        return "random_spread".equals(placementType) || "concentric_rings".equals(placementType);
    }
}
