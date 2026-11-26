package dev.xyat.realmcontrol.structure.project;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.mega.MegaLayout;
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
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
//? if <1.20.5 {
import net.minecraft.world.level.chunk.ChunkStatus;
//?} else {
/*import net.minecraft.world.level.chunk.status.ChunkStatus;
*///?}
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
//? if <26.1 {
import java.util.Comparator;
//?}
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Reads selected regions on the server thread in timed slices. Loads a tile's chunks asynchronously and limits
 * how many completed tiles can wait for disk before scanning more. The old scan remains readable until the new
 * tile files and metadata are ready and the project pointer changes.
 */
public final class ScanService {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final long BUDGET_NANOS = 8_000_000L;
    private static final long PROGRESS_INTERVAL_NANOS = 150_000_000L;
    private static final long CHUNK_WAIT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int MAX_PENDING_WRITES = 4;
    //? if >=26.1 {
    /*private static final TicketType SCAN_TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    *///?} else {
    private static final TicketType<ChunkPos> SCAN_TICKET = TicketType.create("realmcontrol_structure_scan", Comparator.comparingLong(ChunkPos::toLong));
    //?}
    private static final AtomicInteger PENDING_WRITES = new AtomicInteger();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RealmControl structure scan writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final ExecutorService CHUNK_REQUESTER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RealmControl structure scan chunk requests");
        thread.setDaemon(true);
        return thread;
    });
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    @Nullable private static Job current;
    private static long lastTick;
    private static double tickInterval = 50.0D;

    private ScanService() {
    }

    private static <T> CompletableFuture<T> writeInBackground(Supplier<T> write) {
        PENDING_WRITES.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return write.get();
            } finally {
                PENDING_WRITES.decrementAndGet();
            }
        }, WRITER);
    }

    private record PendingChunk(long started, CompletableFuture<ChunkAccess> future) { }

    /**
     * Queues a scan of {@code region} into project {@code id}. A new project takes the name and the selection's
     * cut-out boxes; an existing one keeps its settings and cut-outs and only gets new tiles.
     */
    public static void start(ServerPlayer player, String id, String name, ServerLevel level, @Nullable Selection.Box region,
                             List<Selection.Box> worldCutouts) {
        start(player, id, name, level, region, worldCutouts, "", null);
    }

    public static void start(ServerPlayer player, String id, String name, ServerLevel level, @Nullable Selection.Box region,
                             List<Selection.Box> worldCutouts, String sourceStructure,
                             @Nullable java.util.function.Consumer<ServerPlayer> onReady) {
        if (!StructureProject.validId(id)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.bad_id", id), false);
            return;
        }
        StructureProject stored = ProjectStore.load(id);
        if (stored != null && stored.sourceVariant >= 0) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.import.scan"), false);
            return;
        }
        String problem = Selection.problem(region, level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (problem != null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable(problem, Selection.MAX_FOOTPRINT), false);
            return;
        }
        if (busy(id)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.scan.busy"), false);
            return;
        }
        if (region == null) return;
        Job job = new Job(player.getUUID(), id, name, level, region, worldCutouts);
        job.sourceStructure = sourceStructure;
        job.onReady = onReady;
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

    /** While sealing the scanned data, edits would otherwise change the metadata being copied into the new scan. */
    public static boolean sealing(String id) {
        return current != null && current.id.equals(id) && current.prepared != null;
    }

    public static void stop(MinecraftServer server) {
        QUEUE.clear();
        if (current != null) {
            current.cancelled = true;
            current.cleanUp();
            current = null;
        }
        lastTick = 0L;
        tickInterval = 50.0D;
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
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Structure scan of {} failed", job.id, e);
            job.cleanUp();
            Throwable cause = e.getCause() == null ? e : e.getCause();
            job.finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.failed", String.valueOf(cause.getMessage())));
            current = null;
        }
    }

    /** One scan, kept as the position it has reached so it can stop at any section and go on next tick. */
    private static final class Job {
        String sourceStructure = "";
        @Nullable java.util.function.Consumer<ServerPlayer> onReady;
        final UUID owner;
        final String id;
        final String name;
        final ServerLevel level;
        final Selection.Box region;
        final List<Selection.Box> worldCutouts;
        final int tileSize;
        final int tilesX, tilesZ;
        final long total;
        final Path newScan;
        final Deque<CompletableFuture<Void>> writes = new ArrayDeque<>();
        final BitSet emptyTiles = new BitSet();
        volatile boolean cancelled;
        boolean installed;
        long done;
        long lastProgress;
        int tileX, tileZ;
        int tileWidth, tileDepth;
        @Nullable Tile tile;
        final Map<BlockState, Integer> tilePalette = new IdentityHashMap<>();
        List<ChunkPos> chunks = List.of();
        final Map<ChunkPos, PendingChunk> requests = new HashMap<>();
        final Set<ChunkPos> heldChunks = new HashSet<>();
        @Nullable LevelChunk activeChunk;
        int chunkIndex;
        int sectionY;
        long blocks, blockEntities, containers, entities, entityChunksSkipped, bottomSolid;
        final Markers markers = new Markers();
        @Nullable CompletableFuture<StructureProject> prepared;

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
            this.newScan = ProjectStore.newScan(id);
        }

        /** Scans until {@code deadline}; returns {@code true} once the job is over. */
        boolean step(MinecraftServer server, long deadline) throws IOException {
            if (cancelled) {
                cleanUp();
                finish(server, KineticI18n.translatable("msg.realmcontrol.structure.scan.cancelled"));
                return true;
            }
            reapWrites();
            while (tileZ < tilesZ) {
                if (chunks.isEmpty()) {
                    if (System.nanoTime() >= deadline || PENDING_WRITES.get() >= MAX_PENDING_WRITES) return waitForWork(server);
                    beginTile();
                }
                while (chunkIndex < chunks.size()) {
                    if (System.nanoTime() >= deadline) return waitForWork(server);
                    ChunkPos chunkPos = chunks.get(chunkIndex);
                    if (activeChunk == null) activeChunk = readyChunk(chunkPos);
                    if (activeChunk == null) return waitForWork(server);
                    scanSection(activeChunk, chunkPos);
                }
                endTile();
                reapWrites();
                if (++tileX >= tilesX) {
                    tileX = 0;
                    tileZ++;
                }
            }
            if (!writes.isEmpty()) return waitForWork(server);
            if (prepared == null) {
                if (PENDING_WRITES.get() >= MAX_PENDING_WRITES) return waitForWork(server);
                prepared = writeInBackground(this::prepare);
            }
            if (!prepared.isDone()) return waitForWork(server);
            if (!ProjectStore.activateScan(newScan, prepared.join())) return waitForWork(server);
            installed = true;
            complete(server);
            return true;
        }

        private boolean waitForWork(MinecraftServer server) {
            sendProgress(server, false);
            return false;
        }

        private void reapWrites() {
            // Done futures cannot block. Joining propagates every failure, including unchecked writer errors.
            while (!writes.isEmpty() && writes.peekFirst().isDone()) writes.removeFirst().join();
        }

        private int tileMinX() {
            return region.min().getX() + tileX * tileSize;
        }

        private int tileMinZ() {
            return region.min().getZ() + tileZ * tileSize;
        }

        private void beginTile() {
            tileWidth = Math.min(tileSize, region.max().getX() - tileMinX() + 1);
            tileDepth = Math.min(tileSize, region.max().getZ() - tileMinZ() + 1);
            List<ChunkPos> list = new ArrayList<>();
            for (int cz = tileMinZ() >> 4; cz <= (tileMinZ() + tileDepth - 1) >> 4; cz++) {
                for (int cx = tileMinX() >> 4; cx <= (tileMinX() + tileWidth - 1) >> 4; cx++) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    list.add(pos);
                    holdChunk(pos);
                    if (level.getChunkSource().getChunkNow(cx, cz) == null) requestChunk(pos);
                }
            }
            chunks = list;
            chunkIndex = 0;
            sectionY = region.min().getY() >> 4;
        }

        private Tile contents() {
            if (tile == null) tile = new Tile(tileWidth, region.sizeY(), tileDepth);
            return tile;
        }

        private void holdChunk(ChunkPos pos) {
            heldChunks.add(pos);
            // Vanilla's on-demand UNKNOWN ticket can expire while the future is still generating the chunk.
            //? if >=26.1 {
            /*level.getChunkSource().addTicketWithRadius(SCAN_TICKET, pos, 0);
            *///?} else {
            level.getChunkSource().addRegionTicket(SCAN_TICKET, pos, 0, pos);
            //?}
        }

        private void removeChunkTicket(ChunkPos pos) {
            //? if >=26.1 {
            /*level.getChunkSource().removeTicketWithRadius(SCAN_TICKET, pos, 0);
            *///?} else {
            level.getChunkSource().removeRegionTicket(SCAN_TICKET, pos, 0, pos);
            //?}
        }

        private PendingChunk requestChunk(ChunkPos pos) {
            return requests.computeIfAbsent(pos, key -> new PendingChunk(System.nanoTime(), CompletableFuture.supplyAsync(() -> {
                if (cancelled) return CompletableFuture.<ChunkAccess>completedFuture(null);
                // Vanilla's public method waits if invoked on the server thread. Calling it here schedules the
                // normal ticket request on that thread and returns the future without occupying the tick.
                return level.getChunkSource().getChunkFuture(MegaLayout.chunkX(key), MegaLayout.chunkZ(key), ChunkStatus.FULL, true).thenApply(result -> {
                    //? if <1.20.5 {
                    return result.left().orElse(null);
                    //?} else {
                    /*return result.orElse(null);
                    *///?}
                });
            }, CHUNK_REQUESTER).thenCompose(future -> future)));
        }

        @Nullable
        private LevelChunk readyChunk(ChunkPos pos) {
            LevelChunk loaded = level.getChunkSource().getChunkNow(MegaLayout.chunkX(pos), MegaLayout.chunkZ(pos));
            if (loaded != null) return loaded;
            PendingChunk pending = requestChunk(pos);
            if (!pending.future.isDone()) {
                if (System.nanoTime() - pending.started > CHUNK_WAIT_NANOS) throw new IllegalStateException("Timed out loading chunk " + pos);
                return null;
            }
            if (pending.future.join() instanceof LevelChunk full) return full;
            throw new IllegalStateException("Unable to load chunk " + pos);
        }

        /** Reads one 16-block-high section of a chunk inside the tile; the first section also takes the chunk's block entities and entities. */
        private void scanSection(LevelChunk chunk, ChunkPos chunkPos) {
            int fromX = Math.max(tileMinX(), chunkPos.getMinBlockX()), toX = Math.min(tileMinX() + tileWidth - 1, chunkPos.getMaxBlockX());
            int fromZ = Math.max(tileMinZ(), chunkPos.getMinBlockZ()), toZ = Math.min(tileMinZ() + tileDepth - 1, chunkPos.getMaxBlockZ());
            int minY = region.min().getY(), maxY = region.max().getY();
            if (sectionY == minY >> 4) captureChunkContents(chunk, chunkPos, fromX, toX, fromZ, toZ);
            int fromY = Math.max(minY, sectionY << 4), toY = Math.min(maxY, (sectionY << 4) + 15);
            int columns = (toX - fromX + 1) * (toZ - fromZ + 1);
            int sectionIndex = chunk.getSectionIndex(fromY);
            LevelChunkSection section = sectionIndex >= 0 && sectionIndex < chunk.getSections().length ? chunk.getSection(sectionIndex) : null;
            if (section != null && !section.hasOnlyAir()) {
                Tile tile = this.tile;
                int minX = tileMinX(), minZ = tileMinZ();
                for (int y = fromY; y <= toY; y++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        for (int x = fromX; x <= toX; x++) {
                            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                            if (state.isAir()) continue;
                            if (tile == null) tile = contents();
                            Integer index = tilePalette.get(state);
                            if (index == null) {
                                index = tile.index(BlockStates.serialize(state));
                                tilePalette.put(state, index);
                            }
                            tile.cells[tile.cell(x - minX, y - minY, z - minZ)] = (short) (int) index;
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
                requests.remove(chunkPos);
                removeChunkTicket(chunkPos);
                heldChunks.remove(chunkPos);
                activeChunk = null;
                chunkIndex++;
            }
        }

        private void captureChunkContents(LevelChunk chunk, ChunkPos chunkPos, int fromX, int toX, int fromZ, int toZ) {
            int minY = region.min().getY(), maxY = region.max().getY();
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey();
                if (pos.getX() < fromX || pos.getX() > toX || pos.getZ() < fromZ || pos.getZ() > toZ || pos.getY() < minY || pos.getY() > maxY) continue;
                CompoundTag data = blockEntityTag(entry.getValue());
                data.remove("x");
                data.remove("y");
                data.remove("z");
                Tile tile = contents();
                tile.blockEntities.put(tile.cell(pos.getX() - tileMinX(), pos.getY() - minY, pos.getZ() - tileMinZ()), data);
                blockEntities++;
                if (entry.getValue() instanceof Container) containers++;
                markBlockEntity(markers, entry.getValue(), pos.getX() - region.min().getX(),
                        pos.getY() - minY, pos.getZ() - region.min().getZ(), data);
            }
            if (!level.areEntitiesLoaded(ChunkPos.asLong(MegaLayout.chunkX(chunkPos), MegaLayout.chunkZ(chunkPos)))) {
                entityChunksSkipped++;
                return;
            }
            AABB box = new AABB(fromX, minY, fromZ, toX + 1, maxY + 1, toZ + 1);
            for (Entity entity : level.getEntities((Entity) null, box, entity -> !(entity instanceof Player) && !entity.isPassenger())) {
                if (!entity.getType().canSerialize() || !box.contains(entity.getX(), entity.getY(), entity.getZ())) continue;
                CompoundTag data = entityTag(entity);
                contents().entities.add(new Tile.EntityData(entity.getX() - tileMinX(), entity.getY() - minY, entity.getZ() - tileMinZ(), data));
                entities++;
                if (markers.entities.size() < Markers.MAX_ENTITIES) {
                    Markers.EntityMark mark = new Markers.EntityMark();
                    mark.type = data.getString("id");
                    mark.sourceUuid = entity.getUUID().toString();
                    mark.x = entity.getX() - region.min().getX();
                    mark.y = entity.getY() - minY;
                    mark.z = entity.getZ() - region.min().getZ();
                    mark.name = entity.hasCustomName() ? entity.getCustomName().getString() : "";
                    markers.entities.add(mark);
                }
            }
        }

        private void endTile() {
            Tile finished = tile;
            tile = null;
            tilePalette.clear();
            chunks = List.of();
            if (finished == null) {
                emptyTiles.set(tileZ * tilesX + tileX);
                return;
            }
            Path file = ProjectStore.tile(newScan.resolve("tiles"), tileX, tileZ);
            writes.add(writeInBackground(() -> {
                if (cancelled) return null;
                try {
                    finished.write(file);
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }));
        }

        private StructureProject prepare() {
            if (cancelled) return null;
            try {
                StructureProject project = ProjectStore.load(id);
                if (project != null && project.sourceVariant >= 0) throw new IOException("Registered structure samples cannot be rescanned from the world");
                boolean fresh = project == null;
                if (fresh) {
                    project = new StructureProject();
                    project.id = id;
                    project.sourceStructure = sourceStructure;
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
                project.sourceWorld = PlacedDrafts.worldKey(level);
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
                Cutouts cutouts = fresh ? relativeCutouts() : ProjectStore.cutouts(project);
                if (!fresh) markers.carryOver(ProjectStore.markers(project));
                ProjectStore.prepareScan(newScan, cutouts, markers, emptyTiles);
                return project;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private void complete(MinecraftServer server) {
            dev.xyat.realmcontrol.worldgen.preview.StructureThumbnailService.forget(server, ProjectPreview.PREFIX + id);
            done = total;
            sendProgress(server, true);
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player != null) {
                StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.ScanProgressPacket(id, 1F, 0, true, null));
                StructureEditorNetwork.sendProjects(player, id);
                if (onReady != null && dev.xyat.realmcontrol.structure.StructureEditorModule.allowed(player)) onReady.accept(player);
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
            if (installed) return;
            cancelled = true;
            heldChunks.forEach(this::removeChunkTicket);
            heldChunks.clear();
            requests.clear();
            // This barrier runs after the outstanding writes, so deletion never races a writer or blocks a tick.
            Path abandoned = newScan;
            if (writes.isEmpty() && prepared == null) ProjectStore.removeInBackground(abandoned);
            else WRITER.execute(() -> ProjectStore.removeInBackground(abandoned));
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

    /**
     * Lists a container or spawner. A container that already names a loot table (never opened) keeps that table;
     * any other keeps its items until the editor gives it a table.
     */
    static void markBlockEntity(Markers markers, BlockEntity blockEntity, int x, int y, int z, CompoundTag data) {
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
        //? if >=1.20.5 {
        /*return blockEntityTag(blockEntity, blockEntity.getLevel().registryAccess());
        *///?} else {
        return blockEntityTag(blockEntity, null);
        //?}
    }

    /** A captured block entity has no loaded world, so its serialization uses the supplied registries. */
    public static CompoundTag blockEntityTag(BlockEntity blockEntity, net.minecraft.core.HolderLookup.Provider registries) {
        //? if >=26.1 {
        /*return blockEntity.saveWithFullMetadata(registries);
        *///?} else if >=1.20.5 {
        /*return blockEntity.saveWithId(registries);
        *///?} else {
        return blockEntity.saveWithId();
        //?}
    }

    /** Entity data as vanilla saves it, with its id and without its UUID, as vanilla structure templates keep entities. */
    public static CompoundTag entityTag(Entity entity) {
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
