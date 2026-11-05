package dev.xyat.realmcontrol.structure.mega;

import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.registry.KineticRegistryHandle;
import dev.xyat.kineticcore.api.registry.KineticWorldgenTypes;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

/**
 * The world generation types of mega structures: the structure type {@code realmcontrol:mega}, the placement type
 * {@code realmcontrol:mega_spread} and the tile piece {@code realmcontrol:mega_tile} that saved structure starts name.
 */
public final class MegaTypes {
    public static final KineticRegistryHandle<StructureType<MegaStructure>> STRUCTURE = KineticWorldgenTypes.structureType(
            KineticResourceIds.of("realmcontrol", "mega"), MegaTypes::structureType);
    public static final KineticRegistryHandle<StructurePlacementType<MegaPlacement>> PLACEMENT = KineticWorldgenTypes.placementType(
            KineticResourceIds.of("realmcontrol", "mega_spread"), MegaTypes::placementType);
    public static final KineticRegistryHandle<StructurePieceType> PIECE = KineticWorldgenTypes.pieceType(
            KineticResourceIds.of("realmcontrol", "mega_tile"), () -> (StructurePieceType.ContextlessType) MegaTilePiece::new);

    private MegaTypes() {
    }

    /** Registers the types (by loading this class) and lets go of cached tiles when a server stops. */
    public static void install() {
        KineticServerEvents.onStopped(KineticEventPriority.NORMAL, server -> MegaTemplates.clear());
    }

    private static StructureType<MegaStructure> structureType() {
        //? if >=1.21 {
        /*return () -> MegaStructure.CODEC;
        *///?} else {
        return () -> MegaStructure.CODEC.codec();
        //?}
    }

    private static StructurePlacementType<MegaPlacement> placementType() {
        //? if >=1.21 {
        /*return () -> MegaPlacement.CODEC;
        *///?} else {
        return () -> MegaPlacement.CODEC.codec();
        //?}
    }
}
