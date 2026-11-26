package dev.xyat.realmcontrol.worldgen.preview;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.ticks.BlackholeTickAccess;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A stand-in world that structure pieces place themselves into for a preview. It records every block, block entity
 * and entity a piece writes and answers reads at positions nobody wrote with the generator's own noise terrain, so
 * pieces that follow the surface or fill down to the ground behave as in a real world. Nothing reaches the real
 * level: ticks go nowhere, no chunk is loaded or changed and sounds and events are dropped.
 *
 * <p>Built as a dynamic {@link WorldGenLevel} so the same code serves every game version; calls it does not
 * handle run the interface's default method or return an empty value.
 */
final class CaptureLevel implements InvocationHandler {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final RandomState randomState;
    private final RandomSource random;
    private final long seed;
    private final boolean forImport;
    private final Long2ObjectOpenHashMap<BlockState> written = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<NoiseColumn> terrain = new Long2ObjectOpenHashMap<>();
    private final Map<Long, BlockEntity> blockEntities = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Map<ChunkPos, ChunkAccess> chunks = new HashMap<>();
    private final WorldGenLevel proxy;
    private final BiomeManager biomes;

    CaptureLevel(ServerLevel level, ChunkGenerator generator, RandomState randomState, long seed, boolean forImport) {
        this.level = level;
        this.generator = generator;
        this.randomState = randomState;
        this.seed = seed;
        this.forImport = forImport;
        this.random = RandomSource.create(seed);
        this.proxy = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(),
                new Class<?>[]{WorldGenLevel.class}, this);
        this.biomes = new BiomeManager(proxy, BiomeManager.obfuscateSeed(seed));
    }

    WorldGenLevel level() { return proxy; }

    /** Blocks the pieces wrote, air included, by packed position. */
    Long2ObjectOpenHashMap<BlockState> written() { return written; }

    /** Block entity ids by packed position; containers are marked. */
    Map<Long, BlockEntity> blockEntities() { return blockEntities; }

    List<Entity> entities() { return entities; }

    static boolean isContainer(BlockEntity blockEntity) {
        return blockEntity instanceof Container;
    }

    static CompoundTag idTag(BlockEntity blockEntity) {
        CompoundTag tag = new CompoundTag();
        var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        if (id != null) tag.putString("id", id.toString());
        return tag;
    }

    static CompoundTag idTag(Entity entity) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        return tag;
    }

    private BlockState state(BlockPos pos) {
        BlockState placed = written.get(pos.asLong());
        if (placed != null) return placed;
        long column = BlockPos.asLong(pos.getX(), 0, pos.getZ());
        NoiseColumn noise = terrain.get(column);
        if (noise == null) {
            if (forImport && terrain.size() >= 262_144) throw new StructureSampler.ImportFailure("limit");
            noise = generator.getBaseColumn(pos.getX(), pos.getZ(), level, randomState);
            terrain.put(column, noise);
        }
        return noise.getBlock(pos.getY());
    }

    private boolean set(BlockPos pos, BlockState state) {
        if (forImport && (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()
                || BlockPos.getY(pos.asLong()) != pos.getY() || BlockPos.getX(pos.asLong()) != pos.getX()
                || BlockPos.getZ(pos.asLong()) != pos.getZ())) throw new StructureSampler.ImportFailure("limit");
        long key = pos.asLong();
        if (forImport && written.size() >= StructureSampler.MAX_CAPTURED_BLOCKS && !written.containsKey(key)) {
            throw new StructureSampler.ImportFailure("limit");
        }
        BlockState previous = written.put(key, state);
        BlockEntity kept = blockEntities.get(key);
        if (kept != null) {
            if (previous != null && previous.is(state.getBlock()) && state.hasBlockEntity()) kept.setBlockState(state);
            else blockEntities.remove(key);
        }
        return true;
    }

    BlockEntity blockEntity(BlockPos pos) {
        long key = pos.asLong();
        BlockEntity existing = blockEntities.get(key);
        if (existing != null) return existing;
        BlockState state = written.get(key);
        if (state == null || !state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock block)) return null;
        BlockEntity created = block.newBlockEntity(pos.immutable(), state);
        if (created != null) putBlockEntity(created);
        return created;
    }

    private void putBlockEntity(BlockEntity entity) {
        long key = entity.getBlockPos().asLong();
        if (forImport && blockEntities.size() >= StructureSampler.MAX_CAPTURED_BLOCK_ENTITIES && !blockEntities.containsKey(key)) {
            throw new StructureSampler.ImportFailure("limit");
        }
        blockEntities.put(key, entity);
    }

    private ChunkAccess chunk(ChunkPos pos) {
        if (forImport && chunks.size() >= 2_048 && !chunks.containsKey(pos)) throw new StructureSampler.ImportFailure("limit");
        return chunks.computeIfAbsent(pos, CapturedChunk::new);
    }

    /** Supports piece post-processing and chunk-local block calls without resolving a real chunk. */
    private final class CapturedChunk extends ProtoChunk {
        CapturedChunk(ChunkPos pos) {
            //? if >=26.1 {
            /*super(pos, UpgradeData.EMPTY, level, level.palettedContainerFactory(), null);
            *///?} else {
            super(pos, UpgradeData.EMPTY, level, level.registryAccess().registryOrThrow(Registries.BIOME), null);
            //?}
        }

        @Override public BlockState getBlockState(BlockPos pos) { return state(pos); }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) { return state(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return blockEntity(pos); }
        @Override public void setBlockEntity(BlockEntity entity) { putBlockEntity(entity); }
        @Override public void removeBlockEntity(BlockPos pos) { blockEntities.remove(pos.asLong()); }
        @Override public void markPosForPostprocessing(BlockPos pos) { }

        //? if >=26.1 {
        /*@Override public BlockState setBlockState(BlockPos pos, BlockState state, int flags) {
        *///?} else {
        @Override public BlockState setBlockState(BlockPos pos, BlockState state, boolean moved) {
        //?}
            BlockState previous = state(pos);
            set(pos, state);
            return previous;
        }

        @Override public void setBlockEntityNbt(CompoundTag entityTag) {
            if (forImport) throw new StructureSampler.ImportFailure("pieces");
        }
    }

    private int height(Heightmap.Types type, int x, int z) {
        int top = generator.getBaseHeight(x, z, type, level, randomState);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int max = top + 128;
        for (int y = max; y >= top; y--) {
            BlockState placed = written.get(pos.set(x, y, z).asLong());
            if (placed != null && !placed.isAir()) return y + 1;
        }
        return top;
    }

    /*
     * Calls are told apart by their parameter and return types, not by name: on Forge 1.20.1 the game runs with
     * obfuscated method names, so a name such as "getBlockState" never arrives.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object invoke(Object self, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> "RealmControl capture level";
            };
        }
        Class<?>[] params = method.getParameterTypes();
        Class<?> result = method.getReturnType();
        int count = params.length;
        if (count == 0) {
            // Kept apart from the real world: randomness, biome lookups that would read loaded chunks, scheduled
            // ticks and the player list.
            if (RandomSource.class.isAssignableFrom(result)) return random;
            if (result == long.class) return seed; // The isolated World's getSeed().
            if (result == BiomeManager.class) return biomes;
            if (net.minecraft.world.ticks.LevelTickAccess.class.isAssignableFrom(result)) return BlackholeTickAccess.emptyLevelList();
            if (List.class.isAssignableFrom(result)) return List.of();
            if (forImport && (net.minecraft.world.level.chunk.ChunkSource.class.isAssignableFrom(result)
                    || result == net.minecraft.server.MinecraftServer.class || result == void.class)) {
                throw new StructureSampler.ImportFailure("pieces");
            }
            // Everything else without arguments is a plain getter (height limits, registries, sea level, seed): the
            // real level answers it without loading or changing a chunk.
            return method.invoke(level);
        }
        Class<?> first = params[0];
        if (ChunkAccess.class.isAssignableFrom(result)) {
            if (first == BlockPos.class) return chunk(new ChunkPos((BlockPos) args[0]));
            if (count >= 2 && first == int.class && params[1] == int.class) {
                return chunk(new ChunkPos((Integer) args[0], (Integer) args[1]));
            }
        }
        if (first == BlockPos.class) {
            BlockPos pos = (BlockPos) args[0];
            if (count == 1) {
                if (result == BlockState.class) return state(pos);
                if (result == net.minecraft.world.level.material.FluidState.class) return state(pos).getFluidState();
                if (result == BlockEntity.class) return blockEntity(pos);
                if (result == DifficultyInstance.class) return new DifficultyInstance(Difficulty.NORMAL, 0L, 0L, 0F);
            }
            if (count >= 2 && params[1] == BlockState.class && result == boolean.class) return set(pos, (BlockState) args[1]);
            if (count >= 2 && params[1] == boolean.class && result == boolean.class) return set(pos, AIR); // remove / destroy
            if (count == 2 && params[1] == Predicate.class) {
                // The state and fluid tests share one erased signature; the generic type tells them apart.
                var generic = method.getGenericParameterTypes()[1];
                boolean fluid = generic instanceof java.lang.reflect.ParameterizedType type
                        && type.getActualTypeArguments()[0] == net.minecraft.world.level.material.FluidState.class;
                return ((Predicate<Object>) args[1]).test(fluid ? state(pos).getFluidState() : state(pos));
            }
            if (count == 2 && params[1] == net.minecraft.world.level.ColorResolver.class) return 0xFFFFFF;
            if (count == 2 && params[1] == int.class && result == int.class) return 15; // raw brightness
        }
        if (first == Heightmap.Types.class) {
            if (count == 3) return height((Heightmap.Types) args[0], (Integer) args[1], (Integer) args[2]);
            if (count == 2 && args[1] instanceof BlockPos pos) {
                return new BlockPos(pos.getX(), height((Heightmap.Types) args[0], pos.getX(), pos.getZ()), pos.getZ());
            }
        }
        if (count == 1 && first == Entity.class && result == boolean.class) {
            if (forImport && entities.size() >= StructureSampler.MAX_CAPTURED_ENTITIES) throw new StructureSampler.ImportFailure("limit");
            entities.add((Entity) args[0]);
            return true;
        }
        if (count == 1 && first == BlockEntity.class && result == void.class) {
            putBlockEntity((BlockEntity) args[0]);
            return null;
        }
        if (count == 3 && first == int.class && params[1] == int.class && params[2] == int.class && Holder.class.isAssignableFrom(result)) {
            return generator.getBiomeSource().getNoiseBiome((Integer) args[0], (Integer) args[1], (Integer) args[2],
                    randomState.sampler());
        }
        if (count == 2 && first == net.minecraft.world.level.LightLayer.class) return 15;
        if (count == 2 && first == net.minecraft.core.Direction.class && result == float.class) return 1.0F;
        if (count == 2 && first == int.class && params[1] == int.class && result == boolean.class) return true; // chunk present
        if (List.class.isAssignableFrom(result)) return List.of();
        if (method.isDefault()) return InvocationHandler.invokeDefault(self, method, args);
        if (forImport && result != void.class) throw new StructureSampler.ImportFailure("pieces");
        return empty(result);
    }

    private static Object empty(Class<?> type) {
        if (!type.isPrimitive()) {
            if (List.class.isAssignableFrom(type)) return List.of();
            return null;
        }
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == long.class) return 0L;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
