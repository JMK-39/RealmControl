package dev.xyat.realmcontrol.structure.project;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.worldgen.preview.StructurePreviewService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads selected regions into drafts on the server thread, a slice each tick: at most 8 ms per tick, half of that
 * while the server runs below 15 TPS. One scan runs at a time; others wait in line. Finished tiles are written by a
 * background thread, into a new folder that replaces the old tiles only when the whole scan succeeded. Scanning only
 * reads the world; chunks that are not loaded are loaded (and generated if they never were) as vanilla does.
 */
public final class ScanService {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final long BUDGET_NANOS = 8_000_000L;
    private static final long PROGRESS_INTERVAL_NANOS = 150_000_000L;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RealmControl structure scan writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    @Nullable private static Job current;
    private static long lastTick;
    private static double tickInterval = 50.0D;

    private ScanService() {
    }

    /**
     * Queues a scan of {@code region} into project {@code id}. A new project takes the name and the selection's
     * cut-out boxes; an existing one keeps its settings and cut-outs and only gets new tiles.
     */
    public static void start(ServerPlayer player, String id, String name, ServerLevel level, Selection.Box region,
                             List<Selection.Box> worldCutouts) {
        if (busy(id)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.scan.busy"), false);
            return;
        }
        Job job = new Job(player.getUUID(), id, name, level, region, worldCutouts);
        QUEUE.addLast(job);
        job.sendProgress(player.getServer(), true);
    }

    /** Stops the owner's scan of {@code id}; the draft keeps its previous tiles. */
    public static void cancel(ServerPlayer player, String id) {
        for (Job job : new ArrayList<>(QUEUE)) {
            if (job.id.equals(id) && job.owner.equals(player.getUUID())) {
                QUEUE.remove(job);
                job.finish(player.getServer(), KineticI18n.translatable("msg.realmcontrol.structure.scan.cancelled"));
            }
        }
        if (current != null && current.id.equals(id) && current.owner.equals(player.getUUID())) {
            current.cancelled = true;
        }
    }

    /** Whether a scan is being read now (not only waiting in line). */
    public static boolean running() {
        return current != null;
    }

    public static boolean busy(String id) {
        if (current != null && current.id.equals(id)) return true;
        return QUEUE.stream().anyMatch(job -> job.id.equals(id));
    }

    public static void stop(MinecraftServer server) {
        QUEUE.clear();
        if (current != null) {
            current.cancelled = true;
            current.cleanUp();
            current = null;
        }
    }

    public static void tick(MinecraftServer server) {
        long now = System.nanoTime();
        if (lastTick != 0L) tickInterval = tickInterval * 0.9D + (now - lastTick) / 1_000_000.0D * 0.1D;
        lastTick = now;
        if (current == null) {
            current = QUEUE.pollFirst();
            if (current == null) return;
        }
        // Below 15 TPS the scan takes half its usual share of the tick.
        long budget = tickInterval > 1000.0D / 15.0D ? BUDGET_NANOS / 2 : BUDGET_NANOS;
        Job job = current;
        try {
            if (job.step(server, now + budget)) current = null;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("Structure scan of {} failed", job.id, e);
            job.cleanUp();
            job.finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.failed", String.valueOf(e.getMessage())));
            current = null;
        }
    }

    /** One scan, kept as the position it has reached so it can stop at any section and go on next tick. */
    private static final class Job {
        final UUID owner;
        final String id;
        final String name;
        final ServerLevel level;
        final Selection.Box region;
        final List<Selection.Box> worldCutouts;
        final int tileSize;
        final int tilesX, tilesZ;
        final long total;
        final Path newTiles;
        final List<CompletableFuture<Void>> writes = new ArrayList<>();
        volatile boolean cancelled;
        long done;
        long lastProgress;
        int tileX, tileZ;
        @Nullable Tile tile;
        List<ChunkPos> chunks = List.of();
        int chunkIndex;
        int sectionY;
        int blocks, blockEntities, containers, entities, entityChunksSkipped, bottomSolid;
        final Markers markers = new Markers();
        @Nullable IOException writeError;

        Job(UUID owner, String id, String name, ServerLevel level, Selection.Box region, List<Selection.Box> worldCutouts) {
            this.owner = owner;
            this.id = id;
            this.name = name;
            this.level = level;
            this.region = region;
            this.worldCutouts = List.copyOf(worldCutouts);
            this.tileSize = Math.max(region.sizeX(), region.sizeZ()) > StructureProject.MEDIUM_LIMIT ? 32 : 64;
            this.tilesX = (region.sizeX() + tileSize - 1) / tileSize;
            this.tilesZ = (region.sizeZ() + tileSize - 1) / tileSize;
            this.total = region.volume();
            this.newTiles = ProjectStore.folder(id).resolve("tiles.new");
        }

        /** Scans until {@code deadline}; returns {@code true} once the job is over. */
        boolean step(MinecraftServer server, long deadline) {
            if (cancelled) {
                cleanUp();
                finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.cancelled"));
                return true;
            }
            if (done == 0L && tile == null && tileX == 0 && tileZ == 0) {
                try {
                    ProjectStore.deleteTree(newTiles);
                    Files.createDirectories(newTiles);
                } catch (IOException e) {
                    finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.failed", String.valueOf(e.getMessage())));
                    return true;
                }
            }
            while (tileZ < tilesZ) {
                if (tile == null) beginTile();
                while (chunkIndex < chunks.size()) {
                    if (System.nanoTime() > deadline) {
                        sendProgress(server, false);
                        return false;
                    }
                    scanSection(chunks.get(chunkIndex));
                }
                endTile();
                if (++tileX >= tilesX) {
                    tileX = 0;
                    tileZ++;
                }
            }
            // Every tile is read; wait for the writer before the new tiles replace the old ones.
            if (!writes.stream().allMatch(CompletableFuture::isDone)) {
                sendProgress(server, false);
                return false;
            }
            if (writeError != null) {
                cleanUp();
                finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.failed", String.valueOf(writeError.getMessage())));
                return true;
            }
            complete(server);
            return true;
        }

        private int tileMinX() {
            return region.min().getX() + tileX * tileSize;
        }

        private int tileMinZ() {
            return region.min().getZ() + tileZ * tileSize;
        }

        private void beginTile() {
            int sizeX = Math.min(tileSize, region.max().getX() - tileMinX() + 1);
            int sizeZ = Math.min(tileSize, region.max().getZ() - tileMinZ() + 1);
            tile = new Tile(sizeX, region.sizeY(), sizeZ);
            List<ChunkPos> list = new ArrayList<>();
            for (int cz = tileMinZ() >> 4; cz <= (tileMinZ() + sizeZ - 1) >> 4; cz++) {
                for (int cx = tileMinX() >> 4; cx <= (tileMinX() + sizeX - 1) >> 4; cx++) list.add(new ChunkPos(cx, cz));
            }
            chunks = list;
            chunkIndex = 0;
            sectionY = region.min().getY() >> 4;
        }

        /** Reads one 16-block-high section of a chunk inside the tile; the first section also takes the chunk's block entities and entities. */
        private void scanSection(ChunkPos chunkPos) {
            Tile tile = this.tile;
            LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
            int fromX = Math.max(tileMinX(), chunkPos.getMinBlockX()), toX = Math.min(tileMinX() + tile.sizeX - 1, chunkPos.getMaxBlockX());
            int fromZ = Math.max(tileMinZ(), chunkPos.getMinBlockZ()), toZ = Math.min(tileMinZ() + tile.sizeZ - 1, chunkPos.getMaxBlockZ());
            int minY = region.min().getY(), maxY = region.max().getY();
            if (sectionY == minY >> 4) captureChunkContents(chunk, chunkPos, fromX, toX, fromZ, toZ);
            int fromY = Math.max(minY, sectionY << 4), toY = Math.min(maxY, (sectionY << 4) + 15);
            int columns = (toX - fromX + 1) * (toZ - fromZ + 1);
            int sectionIndex = chunk.getSectionIndex(fromY);
            LevelChunkSection section = sectionIndex >= 0 && sectionIndex < chunk.getSections().length ? chunk.getSection(sectionIndex) : null;
            if (section != null && !section.hasOnlyAir()) {
                Map<BlockState, Integer> indices = new IdentityHashMap<>();
                for (int y = fromY; y <= toY; y++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int x = fromX; x <= toX; x++) {
                            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                            if (state.isAir()) continue;
                            Integer index = indices.get(state);
                            if (index == null) {
                                index = tile.index(BlockStates.serialize(state));
                                indices.put(state, index);
                            }
                            tile.cells[tile.cell(x - tileMinX(), y - minY, z - tileMinZ())] = (short) (int) index;
                            blocks++;
                            if (y == minY && !state.getCollisionShape(level, new BlockPos(x, y, z)).isEmpty()) bottomSolid++;
                        }
                    }
                }
            }
            done += (long) columns * (toY - fromY + 1);
            sectionY++;
            if (sectionY > maxY >> 4) {
                sectionY = minY >> 4;
                chunkIndex++;
            }
        }

        private void captureChunkContents(LevelChunk chunk, ChunkPos chunkPos, int fromX, int toX, int fromZ, int toZ) {
            Tile tile = this.tile;
            int minY = region.min().getY(), maxY = region.max().getY();
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey();
                if (pos.getX() < fromX || pos.getX() > toX || pos.getZ() < fromZ || pos.getZ() > toZ || pos.getY() < minY || pos.getY() > maxY) continue;
                CompoundTag data = blockEntityTag(entry.getValue());
                data.remove("x");
                data.remove("y");
                data.remove("z");
                tile.blockEntities.put(tile.cell(pos.getX() - tileMinX(), pos.getY() - minY, pos.getZ() - tileMinZ()), data);
                blockEntities++;
                if (entry.getValue() instanceof Container) containers++;
                mark(entry.getValue(), pos, data);
            }
            if (!level.areEntitiesLoaded(ChunkPos.asLong(chunkPos.x, chunkPos.z))) {
                entityChunksSkipped++;
                return;
            }
            AABB box = new AABB(fromX, minY, fromZ, toX + 1, maxY + 1, toZ + 1);
            for (Entity entity : level.getEntities((Entity) null, box, entity -> !(entity instanceof Player) && !entity.isPassenger())) {
                if (!entity.getType().canSerialize()) continue;
                CompoundTag data = entityTag(entity);
                tile.entities.add(new Tile.EntityData(entity.getX() - tileMinX(), entity.getY() - minY, entity.getZ() - tileMinZ(), data));
                entities++;
                if (markers.entities.size() < Markers.MAX_ENTITIES) {
                    Markers.EntityMark mark = new Markers.EntityMark();
                    mark.type = data.getString("id");
                    mark.x = entity.getX() - region.min().getX();
                    mark.y = entity.getY() - minY;
                    mark.z = entity.getZ() - region.min().getZ();
                    mark.name = entity.hasCustomName() ? entity.getCustomName().getString() : "";
                    markers.entities.add(mark);
                }
            }
        }

        /**
         * Lists a container or spawner. A container that already names a loot table (never opened) keeps that table;
         * any other keeps its items until the editor gives it a table.
         */
        private void mark(BlockEntity blockEntity, BlockPos pos, CompoundTag data) {
            int x = pos.getX() - region.min().getX(), y = pos.getY() - region.min().getY(), z = pos.getZ() - region.min().getZ();
            String block = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).toString();
            if (blockEntity instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity) {
                Markers.Spawner spawner = new Markers.Spawner();
                spawner.x = x;
                spawner.y = y;
                spawner.z = z;
                spawner.block = block;
                CompoundTag tag = data.getCompound("SpawnData");
                tag = tag.getCompound("entity");
                spawner.entity = tag.getString("id");
                markers.spawners.add(spawner);
                return;
            }
            boolean lootable = lootable(blockEntity);
            if (!lootable && !(blockEntity instanceof Container)) return;
            Markers.Container container = new Markers.Container();
            container.x = x;
            container.y = y;
            container.z = z;
            container.block = block;
            container.lootable = lootable;
            container.items = data.getList("Items", Tag.TAG_COMPOUND).size() + (data.contains("item") ? 1 : 0);
            String table = data.getString("LootTable");
            if (lootable && !table.isEmpty()) {
                container.mode = "table";
                container.table = table;
            }
            markers.containers.add(container);
        }

        private void endTile() {
            Tile finished = tile;
            Path file = ProjectStore.tile(newTiles, tileX, tileZ);
            tile = null;
            writes.add(CompletableFuture.runAsync(() -> {
                try {
                    finished.write(file);
                } catch (IOException e) {
                    writeError = e;
                }
            }, WRITER));
        }

        private void complete(MinecraftServer server) {
            try {
                StructureProject project = ProjectStore.load(id);
                boolean fresh = project == null;
                if (fresh) {
                    project = new StructureProject();
                    project.id = id;
                    project.name = name.isBlank() ? id : name;
                    project.generation.salt = (int) (Math.abs(id.hashCode() * 31L + System.nanoTime()) % 1_000_000_000L);
                    // A bottom layer that is mostly solid is the ground the building stood on.
                    project.embed = bottomSolid * 10L >= (long) region.sizeX() * region.sizeZ() * 7L ? 1 : 0;
                    project.size = new int[]{region.sizeX(), region.sizeY(), region.sizeZ()};
                    // Mega instances must not overlap: start with a separation that keeps them apart.
                    if ("mega".equals(project.tier())) {
                        project.generation.separation = project.megaSeparation() + 4;
                        project.generation.spacing = project.generation.separation + 24;
                    }
                }
                project.createdVersion = minecraftVersion();
                project.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
                project.dimension = level.dimension().location().toString();
                project.origin = new int[]{region.min().getX(), region.min().getY(), region.min().getZ()};
                project.size = new int[]{region.sizeX(), region.sizeY(), region.sizeZ()};
                project.tileSize = tileSize;
                project.blocks = blocks;
                project.blockEntities = blockEntities;
                project.containers = containers;
                project.entities = entities;
                project.entityChunksSkipped = entityChunksSkipped;
                project.missing.clear();
                project.scannedAt = System.currentTimeMillis();
                if (!"draft".equals(project.status)) project.status = "modified";
                Path folder = ProjectStore.folder(id);
                Path tiles = folder.resolve("tiles");
                Path old = folder.resolve("tiles.old");
                ProjectStore.deleteTree(old);
                if (Files.exists(tiles)) Files.move(tiles, old, StandardCopyOption.ATOMIC_MOVE);
                Files.move(newTiles, tiles, StandardCopyOption.ATOMIC_MOVE);
                ProjectStore.deleteTree(old);
                ProjectStore.save(project);
                if (fresh) ProjectStore.saveCutouts(id, relativeCutouts());
                markers.carryOver(ProjectStore.markers(id));
                ProjectStore.saveMarkers(id, markers);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not save structure project {}", id, e);
                finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.failed", String.valueOf(e.getMessage())));
                return;
            }
            StructurePreviewService.forget(ProjectPreview.PREFIX + id);
            done = total;
            sendProgress(server, true);
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player != null) {
                StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.ScanProgressPacket(id, 1F, 0, true, null));
                StructureEditorNetwork.sendProjects(player, id);
            }
        }

        /** The selection's cut-out boxes that touch the region, clipped and relative to its minimum corner. */
        private Cutouts relativeCutouts() {
            Cutouts cutouts = new Cutouts();
            BlockPos min = region.min(), max = region.max();
            int n = 0;
            for (Selection.Box box : worldCutouts) {
                int minX = Math.max(box.min().getX(), min.getX()), minY = Math.max(box.min().getY(), min.getY()), minZ = Math.max(box.min().getZ(), min.getZ());
                int maxX = Math.min(box.max().getX(), max.getX()), maxY = Math.min(box.max().getY(), max.getY()), maxZ = Math.min(box.max().getZ(), max.getZ());
                if (minX > maxX || minY > maxY || minZ > maxZ || cutouts.boxes.size() >= Cutouts.MAX_BOXES) continue;
                cutouts.boxes.add(new Cutouts.Box("#" + (++n), minX - min.getX(), minY - min.getY(), minZ - min.getZ(),
                        maxX - min.getX(), maxY - min.getY(), maxZ - min.getZ(), false));
            }
            return cutouts;
        }

        void cleanUp() {
            try {
                CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).join();
                ProjectStore.deleteTree(newTiles);
                // A new project that never finished leaves no empty folder behind.
                if (!ProjectStore.exists(id)) ProjectStore.delete(id);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not remove the partial scan of {}", id, e);
            }
        }

        void finish(MinecraftServer server, Component reason) {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player != null) StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.ScanProgressPacket(id, 0F, 0, true, reason));
        }

        void sendProgress(MinecraftServer server, boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastProgress < PROGRESS_INTERVAL_NANOS) return;
            lastProgress = now;
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) return;
            int waiting = 0;
            if (current != this) {
                for (Job job : QUEUE) {
                    waiting++;
                    if (job == this) break;
                }
            }
            StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.ScanProgressPacket(id,
                    total == 0 ? 1F : (float) Math.min(0.999D, done / (double) total), waiting, false, null));
        }
    }

    private static String minecraftVersion() {
        //? if >=26.1 {
        /*return SharedConstants.getCurrentVersion().name();
        *///?} else {
        return SharedConstants.getCurrentVersion().getName();
        //?}
    }

    /** Whether vanilla fills this block entity from a loot table: chests, barrels, shulker boxes, suspicious sand and the like. */
    public static boolean lootable(BlockEntity blockEntity) {
        if (blockEntity instanceof net.minecraft.world.level.block.entity.BrushableBlockEntity) return true;
        //? if >=1.21 {
        /*return blockEntity instanceof net.minecraft.world.RandomizableContainer;
        *///?} else {
        return blockEntity instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
        //?}
    }

    /** Block entity data as vanilla saves it, with its id. */
    public static CompoundTag blockEntityTag(BlockEntity blockEntity) {
        //? if >=26.1 {
        /*return blockEntity.saveWithFullMetadata(blockEntity.getLevel().registryAccess());
        *///?} else if >=1.20.5 {
        /*return blockEntity.saveWithId(blockEntity.getLevel().registryAccess());
        *///?} else {
        return blockEntity.saveWithId();
        //?}
    }

    /** Entity data as vanilla saves it, with its id and without its UUID, as vanilla structure templates keep entities. */
    static CompoundTag entityTag(Entity entity) {
        //? if >=26.1 {
        /*net.minecraft.world.level.storage.TagValueOutput output = net.minecraft.world.level.storage.TagValueOutput
                .createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, entity.registryAccess());
        entity.saveWithoutId(output);
        CompoundTag data = output.buildResult();
        *///?} else {
        CompoundTag data = new CompoundTag();
        entity.saveWithoutId(data);
        //?}
        data.putString("id", EntityType.getKey(entity.getType()).toString());
        data.remove("UUID");
        return data;
    }
}
