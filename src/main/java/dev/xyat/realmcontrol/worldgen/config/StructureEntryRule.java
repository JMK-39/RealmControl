package dev.xyat.realmcontrol.worldgen.config;

import java.util.Objects;

public record StructureEntryRule(String structureId, boolean disabled, Integer weight) {
    public StructureEntryRule {
        structureId = Objects.requireNonNull(structureId, "structureId").trim();
    }

    public boolean isEmpty() {
        return !disabled && weight == null;
    }

}
