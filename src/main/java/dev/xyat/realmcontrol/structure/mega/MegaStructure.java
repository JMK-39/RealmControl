package dev.xyat.realmcontrol.structure.mega;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A structure larger than vanilla's jigsaw can place (up to 1024×1024), published by the structure editor as 32×32
 * tile templates. Each of its groups (see {@link MegaLayout}) is a structure start of its own, so vanilla references
 * and places it chunk by chunk with its usual rules; all of them agree on the instance because its turn and height
 * come from the anchor alone: the height is the median ground height of 25 points over the footprint, from the
 * noise (no chunk needs to exist), lowered by the embedding. Vanilla checks the biome at the instance's middle for
 * every group, so an instance generates whole or not at all. Its placement must be a {@link MegaPlacement}.
 */
public final class MegaStructure extends Structure {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final MapCodec<MegaStructure> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            settingsCodec(i),
            ResourceLocation.CODEC.fieldOf("tiles").forGetter(structure -> structure.tiles),
            Codec.INT.listOf().fieldOf("size").forGetter(structure -> List.of(structure.layout.sizeX, structure.layout.sizeY, structure.layout.sizeZ)),
            Codec.STRING.listOf().optionalFieldOf("absent", List.of()).forGetter(structure -> List.copyOf(structure.absent)),
            Codec.STRING.optionalFieldOf("height", "surface").forGetter(structure -> structure.height),
            Codec.INT.optionalFieldOf("offset", 0).forGetter(structure -> structure.offset),
            Codec.INT.optionalFieldOf("embed", 0).forGetter(structure -> structure.embed),
            Codec.STRING.optionalFieldOf("foundation", "fill_clear").forGetter(structure -> structure.foundation),
            Codec.STRING.optionalFieldOf("foundation_block", "minecraft:stone").forGetter(structure -> structure.foundationBlock),
            Codec.BOOL.optionalFieldOf("suppress_other_structures", true).forGetter(structure -> structure.suppressOthers)
    ).apply(i, MegaStructure::new));

    /** The templates are {@code <tiles>/t_<x>_<z>}. */
    public final ResourceLocation tiles;
    public final MegaLayout layout;
    /** Tiles that were cut out whole and have no template, as {@code x_z}. */
    private final Set<String> absent;
    /** {@code surface}: on the ground, lowered by {@link #embed}, raised by {@link #offset}; {@code absolute}: bottom at {@link #offset}. */
    private final String height;
    private final int offset, embed;
    /** {@code fill_clear}, {@code fill} or {@code none}: foundation down to the ground, and the ground above the structure cleared. */
    public final String foundation;
    public final String foundationBlock;
    /** Other structures do not start inside its footprint. */
    public final boolean suppressOthers;
    private final Map<Key, Integer> bottoms = new ConcurrentHashMap<>();
    @Nullable private volatile StructureSet placementSet;
    @Nullable private volatile MegaPlacement placement;

    private record Key(ChunkGenerator generator, long anchor) {
    }

    private MegaStructure(StructureSettings settings, ResourceLocation tiles, List<Integer> size, List<String> absent, String height, int offset,
                          int embed, String foundation, String foundationBlock, boolean suppressOthers) {
        super(settings);
        this.tiles = tiles;
        this.layout = new MegaLayout(size.isEmpty() ? 1 : size.get(0), size.size() < 2 ? 1 : size.get(1), size.size() < 3 ? 1 : size.get(2));
        this.absent = new HashSet<>(absent);
        this.height = height;
        this.offset = offset;
        this.embed = embed;
        this.foundation = foundation;
        this.foundationBlock = foundationBlock;
        this.suppressOthers = suppressOthers;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        MegaPlacement placement = placement(context.registryAccess());
        if (placement == null) return Optional.empty();
        ChunkPos chunk = context.chunkPos();
        MegaPlacement.Match match = placement.match(context.seed(), MegaLayout.chunkX(chunk), MegaLayout.chunkZ(chunk));
        if (match == null) return Optional.empty();
        ChunkPos anchor = match.anchor();
        Rotation rotation = match.rotation();
        int bottom = bottom(context.chunkGenerator(), context.heightAccessor(), context.randomState(), anchor, rotation);
        BlockPos middle = new BlockPos(anchor.getMinBlockX(), bottom, anchor.getMinBlockZ());
        return Optional.of(new GenerationStub(middle, builder -> addGroup(builder, anchor, rotation, bottom, match.groupX(), match.groupZ())));
    }

    /**
     * The tiles of one group. The middle group of an instance always gets a piece, an empty marker if all its tiles
     * are cut out, so /locate finds the instance at its anchor.
     */
    private void addGroup(StructurePiecesBuilder builder, ChunkPos anchor, Rotation rotation, int bottom, int groupX, int groupZ) {
        boolean added = false;
        for (int tz = 0; tz < layout.tilesZ(); tz++) {
            if (layout.groupZ(tz) != groupZ) continue;
            for (int tx = 0; tx < layout.tilesX(); tx++) {
                if (layout.groupX(tx) != groupX || absent.contains(tx + "_" + tz)) continue;
                BlockPos position = layout.world(anchor, bottom, rotation, tx * MegaLayout.TILE, 0, tz * MegaLayout.TILE);
                ResourceLocation template = KineticResourceIds.of(tiles.getNamespace(), tiles.getPath() + "/t_" + tx + "_" + tz);
                builder.addPiece(new MegaTilePiece(template, position, rotation, layout.tileBox(anchor, bottom, rotation, tx, tz), foundation, foundationBlock));
                added = true;
            }
        }
        if (!added && groupX == 0 && groupZ == 0) {
            BlockPos middle = new BlockPos(anchor.getMinBlockX(), bottom, anchor.getMinBlockZ());
            builder.addPiece(new MegaTilePiece(null, middle, rotation, new BoundingBox(middle), "none", foundationBlock));
        }
    }

    /** The bottom of an instance: the median ground height of 25 points over its footprint, from the noise. */
    public int bottom(ChunkGenerator generator, LevelHeightAccessor heightAccessor, RandomState randomState, ChunkPos anchor, Rotation rotation) {
        if ("absolute".equals(height)) return offset;
        Key key = new Key(generator, ((long) MegaLayout.chunkX(anchor) << 32) ^ (MegaLayout.chunkZ(anchor) & 0xFFFFFFFFL));
        Integer known = bottoms.get(key);
        if (known != null) return known;
        int[] heights = new int[25];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                BlockPos sample = layout.world(anchor, 0, rotation, (layout.sizeX - 1) * i / 4, 0, (layout.sizeZ - 1) * j / 4);
                heights[i * 5 + j] = generator.getBaseHeight(sample.getX(), sample.getZ(), Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState);
            }
        }
        Arrays.sort(heights);
        int bottom = heights[12] - embed + offset;
        if (bottoms.size() > 4096) bottoms.clear();
        bottoms.put(key, bottom);
        return bottom;
    }

    /**
     * Whether an instance covers the chunk (with {@code margin} blocks around its footprint) and would generate: its
     * anchor passes the placement's frequency and exclusion checks and its middle is in one of its biomes.
     */
    public boolean covers(RegistryAccess access, ChunkGeneratorStructureState state, ChunkGenerator generator, LevelHeightAccessor heightAccessor, ChunkPos chunk,
                          int margin) {
        MegaPlacement placement = placement(access);
        if (placement == null) return false;
        long seed = state.getLevelSeed();
        int x = MegaLayout.chunkX(chunk), z = MegaLayout.chunkZ(chunk);
        for (ChunkPos anchor : placement.anchorsNear(seed, x, z, margin)) {
            Rotation rotation = MegaLayout.rotation(seed, MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor));
            BoundingBox footprint = layout.footprint(anchor, 0, rotation).inflatedBy(margin);
            if (!footprint.intersects(chunk.getMinBlockX(), chunk.getMinBlockZ(), chunk.getMaxBlockX(), chunk.getMaxBlockZ())) continue;
            if (!placement.isStructureChunk(state, MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor))) continue;
            int bottom = bottom(generator, heightAccessor, state.randomState(), anchor, rotation);
            Holder<net.minecraft.world.level.biome.Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(anchor.getMinBlockX()),
                    QuartPos.fromBlock(bottom), QuartPos.fromBlock(anchor.getMinBlockZ()), state.randomState().sampler());
            if (biomes().contains(biome)) return true;
        }
        return false;
    }

    /** The placement of the structure set that holds this structure; found once per world, again if the set's placement was replaced. */
    @Nullable
    public MegaPlacement placement(RegistryAccess access) {
        StructureSet set = placementSet;
        if (set != null && set.placement() == placement) return placement;
        for (StructureSet candidate : access.registryOrThrow(Registries.STRUCTURE_SET)) {
            if (!(candidate.placement() instanceof MegaPlacement mega)) continue;
            for (StructureSet.StructureSelectionEntry entry : candidate.structures()) {
                if (entry.structure().value() == this) {
                    placement = mega;
                    placementSet = candidate;
                    return mega;
                }
            }
        }
        LOGGER.warn("Mega structure with tiles {} is not in a structure set with a realmcontrol:mega_spread placement; it does not generate", tiles);
        placementSet = null;
        placement = null;
        return null;
    }

    @Override
    public StructureType<?> type() {
        return MegaTypes.STRUCTURE.get();
    }
}
