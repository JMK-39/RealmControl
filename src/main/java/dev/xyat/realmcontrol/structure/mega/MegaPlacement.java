package dev.xyat.realmcontrol.structure.mega;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Vanilla's random spread for a mega structure: one instance per region, at the chunk vanilla's own code picks (the
 * anchor), with the same frequency and exclusion rules. On top, every group start of the instance counts as a
 * structure chunk, so vanilla starts each group there. /locate searches the anchors, which hold the middle group.
 * {@code size} is the structure's footprint in blocks, which decides where the group starts are.
 */
public class MegaPlacement extends RandomSpreadStructurePlacement {
    public static final MapCodec<MegaPlacement> CODEC = RecordCodecBuilder.mapCodec(i -> placementCodec(i).and(i.group(
            Codec.intRange(0, 4096).fieldOf("spacing").forGetter(MegaPlacement::spacing),
            Codec.intRange(0, 4096).fieldOf("separation").forGetter(MegaPlacement::separation),
            Codec.intRange(1, MegaLayout.MAX_DIMENSION).listOf().fieldOf("size")
                    .forGetter(placement -> List.of(placement.layout.sizeX, placement.layout.sizeZ))
    )).apply(i, MegaPlacement::new));

    private final MegaLayout layout;

    /** An instance's anchor chunk, its turn, and which of its groups a chunk starts. */
    public record Match(ChunkPos anchor, Rotation rotation, int groupX, int groupZ) {
    }

    public MegaPlacement(Vec3i locateOffset, FrequencyReductionMethod frequencyReductionMethod, float frequency, int salt,
                         Optional<ExclusionZone> exclusionZone, int spacing, int separation, List<Integer> size) {
        // Vanilla's random spread needs a separation below the spacing.
        super(locateOffset, frequencyReductionMethod, frequency, salt, exclusionZone, Math.max(1, spacing), Math.max(0, Math.min(separation, Math.max(1, spacing) - 1)),
                RandomSpreadType.LINEAR);
        this.layout = new MegaLayout(size.isEmpty() ? 1 : size.get(0), 1, size.size() < 2 ? 1 : size.get(1));
    }

    /** The same placement with other values, as the structure panel's rules make them. */
    public MegaPlacement with(float frequency, int salt, int spacing, int separation) {
        return new MegaPlacement(locateOffset(), frequencyReductionMethod(), frequency, salt, exclusionZone(), spacing, separation,
                List.of(layout.sizeX, layout.sizeZ));
    }

    /** The instance and group whose start is chunk ({@code x}, {@code z}), if any; by vanilla's anchors alone, before frequency and exclusion. */
    @Nullable
    public Match match(long seed, int x, int z) {
        int spacing = spacing(), reach = layout.maxGroupOffset();
        int lastRegionX = Math.floorDiv(x + reach, spacing), lastRegionZ = Math.floorDiv(z + reach, spacing);
        for (int rx = Math.floorDiv(x - reach, spacing); rx <= lastRegionX; rx++) {
            for (int rz = Math.floorDiv(z - reach, spacing); rz <= lastRegionZ; rz++) {
                ChunkPos anchor = getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                int offsetX = x - MegaLayout.chunkX(anchor), offsetZ = z - MegaLayout.chunkZ(anchor);
                if (Math.abs(offsetX) > reach || Math.abs(offsetZ) > reach) continue;
                Rotation rotation = MegaLayout.rotation(seed, MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor));
                BlockPos group = layout.groupAtOffset(offsetX, offsetZ, rotation);
                if (group != null) return new Match(anchor, rotation, group.getX(), group.getZ());
            }
        }
        return null;
    }

    /** The anchors of the instances whose footprint, grown by {@code margin} blocks, may reach chunk ({@code x}, {@code z}). */
    public List<ChunkPos> anchorsNear(long seed, int x, int z, int margin) {
        int spacing = spacing(), reach = layout.anchorReach(margin);
        int lastRegionX = Math.floorDiv(x + reach, spacing), lastRegionZ = Math.floorDiv(z + reach, spacing);
        List<ChunkPos> anchors = new ArrayList<>();
        for (int rx = Math.floorDiv(x - reach, spacing); rx <= lastRegionX; rx++) {
            for (int rz = Math.floorDiv(z - reach, spacing); rz <= lastRegionZ; rz++) {
                ChunkPos anchor = getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                if (Math.abs(x - MegaLayout.chunkX(anchor)) <= reach && Math.abs(z - MegaLayout.chunkZ(anchor)) <= reach) anchors.add(anchor);
            }
        }
        return anchors;
    }

    public MegaLayout layout() {
        return layout;
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int x, int z) {
        return match(state.getLevelSeed(), x, z) != null;
    }

    /** A group start of an instance whose anchor passes vanilla's frequency and exclusion checks. */
    @Override
    public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) {
        Match match = match(state.getLevelSeed(), x, z);
        return match != null && super.isStructureChunk(state, MegaLayout.chunkX(match.anchor()), MegaLayout.chunkZ(match.anchor()));
    }

    @Override
    public StructurePlacementType<?> type() {
        return MegaTypes.PLACEMENT.get();
    }
}
