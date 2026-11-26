package dev.xyat.realmcontrol.structure.publish;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.PlacedDrafts;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.project.Tile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Test placement: a copy of a small or medium draft put into the world around a player, as it would generate (cut-outs,
 * markers and save options applied, mobs set up as vanilla does), and undo, which puts the area back. Before anything
 * is placed the covered box is read into a snapshot in the world folder, in the draft's tile format, so undo restores
 * it exactly, block entities included; entities the placement added carry {@link #ENTITY_TAG} and are removed. One test
 * placement per world at a time. Reading, placing and restoring run a slice per tick as scans do (8 ms, half of that
 * below 15 TPS) and wait while a scan runs; blocks are set without neighbour updates, the way templates place them,
 * and come to rest as scanned: the block and fluid ticks placing schedules (water spreading, sand falling) are dropped,
 * or water would run into the part of the box the next slices have not set yet.
 */
public final class TestPlacement {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    /** Scoreboard tag of the entities a test placement added. */
    public static final String ENTITY_TAG = "realmcontrol_test_placement";
    /** Progress codes in {@link StructureEditorNetwork.ScanProgressPacket#waiting()}. */
    public static final int PLACING = -2, UNDOING = -3, GENERATING = -4;
    private static final int TILE = 64;
    private static final long BUDGET_NANOS = 8_000_000L;
    private static final long PROGRESS_INTERVAL_NANOS = 150_000_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "RealmControl test placement files");
        thread.setDaemon(true);
        return thread;
    });
    @Nullable private static Job current;
    private static long lastTick;
    private static double tickInterval = 50.0D;

    private TestPlacement() {
    }

    /** The test placement of a world: which draft, where, and how large. */
    public record Record(String project, String name, String dimension, BlockPos min, int sizeX, int sizeY, int sizeZ) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("project", project);
            json.addProperty("name", name);
            json.addProperty("dimension", dimension);
            json.addProperty("x", min.getX());
            json.addProperty("y", min.getY());
            json.addProperty("z", min.getZ());
            json.addProperty("size_x", sizeX);
            json.addProperty("size_y", sizeY);
            json.addProperty("size_z", sizeZ);
            return json;
        }

        public static Record fromJson(JsonObject json) {
            return new Record(json.get("project").getAsString(), json.get("name").getAsString(), json.get("dimension").getAsString(),
                    new BlockPos(json.get("x").getAsInt(), json.get("y").getAsInt(), json.get("z").getAsInt()),
                    json.get("size_x").getAsInt(), json.get("size_y").getAsInt(), json.get("size_z").getAsInt());
        }

        BlockPos max() {
            return min.offset(sizeX - 1, sizeY - 1, sizeZ - 1);
        }
    }

    private static Path folder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("realmcontrol").resolve("test_placement");
    }

    /** This world's test placement, or {@code null}; one whose snapshot never finished does not count. */
    @Nullable
    public static Record record(MinecraftServer server) {
        Path file = folder(server).resolve("test.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            return Record.fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read the test placement record {}", file, e);
            return null;
        }
    }

    public static boolean busy(String id) {
        return current != null && !current.finished && current.id.equals(id);
    }

    /** Permanently places a draft at its explicitly chosen minimum corner in the operator's current world. */
    public static void generate(ServerPlayer player, String id, BlockPos min) {
        if (!StructureEditorModule.allowed(player)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.no_permission"), false);
            return;
        }
        if (current != null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.generate.busy"), false);
            return;
        }
        if (ScanService.busy(id)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.scan.busy"), false);
            return;
        }
        ProjectStore.Draft draft = null;
        try {
            draft = ProjectStore.openDraft(id);
            if (draft == null) {
                StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", id), false);
                return;
            }
            StructureProject project = draft.project();
            PlacementVolume volume = PlacementVolume.at(min, project.size);
            ServerLevel level = player.serverLevel();
            if (volume == null || level.isOutsideBuildHeight(volume.min().getY()) || level.isOutsideBuildHeight(volume.max().getY())
                    || !level.getWorldBorder().isWithinBounds(volume.min()) || !level.getWorldBorder().isWithinBounds(volume.max())) {
                StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.generate.bounds"), false);
                return;
            }
            MinecraftServer server = player.getServer();
            Record test = record(server);
            if (test != null && test.dimension().equals(level.dimension().location().toString())
                    && overlaps(min, project.size, test.min(), new int[]{test.sizeX(), test.sizeY(), test.sizeZ()})) {
                StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.exists", test.name()), false);
                return;
            }
            var job = new GenerateJob(player.getUUID(), draft, level, volume);
            current = job;
            draft = null;
            job.sendProgress(server, true);
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not prepare direct structure placement for {}", id, error);
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.generate.failed",
                    String.valueOf(error.getMessage())), false);
        } finally {
            if (draft != null) draft.close();
        }
    }

    /** Places draft {@code id} around the player, its bottom set into the ground by the draft's embedding. */
    public static void place(ServerPlayer player, String id) {
        MinecraftServer server = player.getServer();
        if (current != null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.busy"), false);
            return;
        }
        StructureProject project = ProjectStore.load(id);
        if (project == null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.publish.unknown", id), false);
            return;
        }
        if ("mega".equals(project.tier())) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.mega", StructureProject.MEDIUM_LIMIT, StructureProject.MEDIUM_LIMIT), false);
            return;
        }
        if (ScanService.busy(id)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.scan.busy"), false);
            return;
        }
        Record existing = record(server);
        if (existing != null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.exists", existing.name()), false);
            return;
        }
        ServerLevel level = player.serverLevel();
        BlockPos at = player.blockPosition();
        BlockPos min = new BlockPos(at.getX() - project.size[0] / 2, at.getY() - project.embed, at.getZ() - project.size[2] / 2);
        int maxY = min.getY() + project.size[1] - 1;
        if (level.isOutsideBuildHeight(min.getY()) || level.isOutsideBuildHeight(maxY)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.height", min.getY(), maxY), false);
            return;
        }
        // Placing over the place the draft was scanned from would overwrite the original building.
        if (project.dimension.equals(level.dimension().location().toString())
                && overlaps(min, project.size, new BlockPos(project.origin[0], project.origin[1], project.origin[2]), project.size)) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.source"), false);
            return;
        }
        PlaceJob job = new PlaceJob(player.getUUID(), project, level, min);
        current = job;
        job.sendProgress(server, true);
    }

    /** Puts back the area of this world's test placement and removes the entities it added. */
    public static void undo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (current != null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.busy"), false);
            return;
        }
        Record record = record(server);
        if (record == null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.test.none"), false);
            return;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(record.dimension());
        ServerLevel level = dimension == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        if (level == null) {
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.project.no_dimension", record.dimension()), false);
            return;
        }
        UndoJob job = new UndoJob(player.getUUID(), record, level, folder(server).resolve("undo"));
        current = job;
        job.sendProgress(server, true);
    }

    /** Stops the owner's placement or undo of {@code id}. A permanent placement retains completed changes. */
    public static void cancel(ServerPlayer player, String id) {
        if (current != null && current.id.equals(id) && current.owner.equals(player.getUUID())) current.cancelled = true;
    }

    public static void stop(MinecraftServer server) {
        if (current == null) return;
        current.cancelled = true;
        current.cleanUp(server);
        current = null;
    }

    public static void tick(MinecraftServer server) {
        long now = System.nanoTime();
        if (lastTick != 0L) tickInterval = tickInterval * 0.9D + (now - lastTick) / 1_000_000.0D * 0.1D;
        lastTick = now;
        if (current == null) return;
        if (current instanceof GenerateJob job && !job.cancelled) {
            ServerPlayer owner = server.getPlayerList().getPlayer(job.owner);
            if (owner == null || !StructureEditorModule.allowed(owner)) job.cancelled = true;
        }
        // A scan has the tick to itself. Cancellation may release an outstanding placement ticket even during a scan.
        if (ScanService.running() && !(current instanceof GenerateJob && current.cancelled)) return;
        long budget = tickInterval > 1000.0D / 15.0D ? BUDGET_NANOS / 2 : BUDGET_NANOS;
        Job job = current;
        try {
            if (job.step(server, now + budget)) current = null;
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Structure placement of {} failed", job.id, e);
            job.cleanUp(server);
            job.finish(server, job.failure(e), false);
            current = null;
        }
    }

    private static boolean overlaps(BlockPos a, int[] aSize, BlockPos b, int[] bSize) {
        return a.getX() < b.getX() + bSize[0] && b.getX() < a.getX() + aSize[0]
                && a.getY() < b.getY() + bSize[1] && b.getY() < a.getY() + aSize[1]
                && a.getZ() < b.getZ() + bSize[2] && b.getZ() < a.getZ() + aSize[2];
    }

    private abstract static class Job {
        final UUID owner;
        final String id;
        final ServerLevel level;
        final BlockPos min;
        final int sizeX, sizeY, sizeZ;
        final long total;
        long done;
        long lastProgress;
        boolean finished;
        volatile boolean cancelled;

        Job(UUID owner, String id, ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, long total) {
            this.owner = owner;
            this.id = id;
            this.level = level;
            this.min = min;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.total = total;
        }

        /** Works until {@code deadline}; returns {@code true} once the job is over. */
        abstract boolean step(MinecraftServer server, long deadline) throws IOException;

        abstract int code();

        void cleanUp(MinecraftServer server) {
        }

        Component failure(Throwable error) {
            return KineticI18n.translatable("msg.realmcontrol.structure.test.failed", String.valueOf(error.getMessage()));
        }

        void finish(MinecraftServer server, Component message, boolean success) {
            finished = true;
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) return;
            // A failure shows as the task's message; success as a result.
            StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.ScanProgressPacket(id, 1F, code(), true, success ? null : message));
            if (success) StructureEditorNetwork.result(player, message, true);
            StructureEditorNetwork.sendProjects(player, id);
        }

        void sendProgress(MinecraftServer server, boolean force) {
            long now = System.nanoTime();
            if (!force && now - lastProgress < PROGRESS_INTERVAL_NANOS) return;
            lastProgress = now;
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null) return;
            StructureEditorNetwork.CHANNEL.sendToPlayer(player, new StructureEditorNetwork.ScanProgressPacket(id,
                    total == 0 ? 1F : (float) Math.min(0.999D, done / (double) total), code(), false, null));
        }
    }

    private static final class GenerateJob extends Job {
        private final ProjectStore.Draft draft;
        private final CompletableFuture<DraftSource> source;
        @Nullable private Placer placer;

        GenerateJob(UUID owner, ProjectStore.Draft draft, ServerLevel level, PlacementVolume volume) {
            super(owner, draft.project().id, level, volume.min(), draft.project().size[0], draft.project().size[1],
                    draft.project().size[2], volume.cells());
            this.draft = draft;
            source = CompletableFuture.supplyAsync(() -> {
                StructureProject project = draft.project();
                return new DraftSource(project, project.save.cutouts ? draft.cutouts() : new Cutouts(), draft.markers(), draft, false);
            }, IO);
        }

        @Override int code() {
            return GENERATING;
        }

        @Override boolean step(MinecraftServer server, long deadline) throws IOException {
            if (cancelled) {
                finish(server, KineticI18n.translatable(changed()
                        ? "msg.realmcontrol.structure.generate.stopped" : "msg.realmcontrol.structure.generate.cancelled"), false);
                cleanUp(server);
                return true;
            }
            if (placer == null) {
                if (!source.isDone()) return false;
                placer = new Placer(level, min, sizeX, sizeY, sizeZ, source.join(), draft.project().tileSize, true);
            }
            boolean complete = placer.step(deadline);
            done = placer.placed;
            if (!complete) {
                sendProgress(server, false);
                return false;
            }
            if (changed()) PlacedDrafts.record(level, draft.project(), min);
            finish(server, KineticI18n.translatable("msg.realmcontrol.structure.generate.done", draft.project().name,
                    min.getX(), min.getY(), min.getZ(), min.getX() + sizeX - 1, min.getY() + sizeY - 1, min.getZ() + sizeZ - 1), true);
            cleanUp(server);
            return true;
        }

        private boolean changed() {
            return placer != null && placer.changed;
        }

        @Override Component failure(Throwable error) {
            return KineticI18n.translatable(changed() ? "msg.realmcontrol.structure.generate.failed_partial" : "msg.realmcontrol.structure.generate.failed",
                    String.valueOf(error.getMessage()));
        }

        @Override void cleanUp(MinecraftServer server) {
            if (placer != null) placer.close();
            // A cancelled job can still have one off-thread reader. It sees the pinned generation until it returns.
            CompletableFuture<?> reading = placer == null ? source : placer.loading;
            if (reading == null) draft.close();
            else reading.whenComplete((ignored, failure) -> draft.close());
        }
    }

    /** Reads the box into the snapshot, records it, then places the draft. */
    private static final class PlaceJob extends Job {
        final StructureProject project;
        final Path folder, newUndo;
        final int tilesX, tilesZ;
        final List<CompletableFuture<Void>> writes = new ArrayList<>();
        @Nullable volatile IOException writeError;
        int tileX, tileZ;
        @Nullable Tile tile;
        int chunkX, chunkZ, sectionY;
        boolean recorded;
        @Nullable Placer placer;

        PlaceJob(UUID owner, StructureProject project, ServerLevel level, BlockPos min) {
            super(owner, project.id, level, min, project.size[0], project.size[1], project.size[2],
                    2L * project.size[0] * project.size[1] * project.size[2]);
            this.project = project;
            this.folder = folder(level.getServer());
            this.newUndo = folder.resolve("undo.new");
            this.tilesX = (sizeX + TILE - 1) / TILE;
            this.tilesZ = (sizeZ + TILE - 1) / TILE;
        }

        @Override
        int code() {
            return PLACING;
        }

        @Override
        boolean step(MinecraftServer server, long deadline) throws IOException {
            if (cancelled) {
                if (recorded) {
                    finish(server, KineticI18n.translatable("msg.realmcontrol.structure.test.stopped"), false);
                } else {
                    cleanUp(server);
                    finish(server, KineticI18n.translatable("msg.realmcontrol.structure.test.cancelled"), false);
                }
                return true;
            }
            if (!recorded) {
                if (done == 0L && tile == null && tileX == 0 && tileZ == 0) {
                    ProjectStore.deleteTree(newUndo);
                    Files.createDirectories(newUndo);
                }
                while (tileZ < tilesZ) {
                    if (tile == null) beginTile();
                    while (chunkZ <= tileMaxZ() >> 4) {
                        if (System.nanoTime() > deadline) {
                            sendProgress(server, false);
                            return false;
                        }
                        readSection();
                    }
                    endTile();
                    if (++tileX >= tilesX) {
                        tileX = 0;
                        tileZ++;
                    }
                }
                if (!writes.stream().allMatch(CompletableFuture::isDone)) {
                    sendProgress(server, false);
                    return false;
                }
                if (writeError != null) throw writeError;
                commit();
                Cutouts cutouts = project.save.cutouts ? ProjectStore.cutouts(id) : new Cutouts();
                placer = new Placer(level, min, sizeX, sizeY, sizeZ, new DraftSource(project, cutouts, ProjectStore.markers(id)));
            }
            long before = placer.placed;
            boolean over = placer.step(deadline);
            done += placer.placed - before;
            if (!over) {
                sendProgress(server, false);
                return false;
            }
            finish(server, KineticI18n.translatable("msg.realmcontrol.structure.test.placed", project.name, min.getX(), min.getY(), min.getZ(),
                    min.getX() + sizeX - 1, min.getY() + sizeY - 1, min.getZ() + sizeZ - 1), true);
            return true;
        }

        private int tileMinX() {
            return min.getX() + tileX * TILE;
        }

        private int tileMinZ() {
            return min.getZ() + tileZ * TILE;
        }

        private int tileMaxX() {
            return Math.min(tileMinX() + TILE, min.getX() + sizeX) - 1;
        }

        private int tileMaxZ() {
            return Math.min(tileMinZ() + TILE, min.getZ() + sizeZ) - 1;
        }

        private void beginTile() {
            tile = new Tile(tileMaxX() - tileMinX() + 1, sizeY, tileMaxZ() - tileMinZ() + 1);
            chunkX = tileMinX() >> 4;
            chunkZ = tileMinZ() >> 4;
            sectionY = min.getY() >> 4;
        }

        /** Reads one 16-block-high section of a chunk inside the tile, with its block entities. */
        private void readSection() {
            Tile tile = this.tile;
            LevelChunk chunk = level.getChunk(chunkX, chunkZ);
            int fromX = Math.max(tileMinX(), chunkX << 4), toX = Math.min(tileMaxX(), (chunkX << 4) + 15);
            int fromZ = Math.max(tileMinZ(), chunkZ << 4), toZ = Math.min(tileMaxZ(), (chunkZ << 4) + 15);
            int minY = min.getY(), maxY = minY + sizeY - 1;
            int fromY = Math.max(minY, sectionY << 4), toY = Math.min(maxY, (sectionY << 4) + 15);
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
                        }
                    }
                }
            }
            if (sectionY == minY >> 4) {
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos pos = entry.getKey();
                    if (pos.getX() < fromX || pos.getX() > toX || pos.getZ() < fromZ || pos.getZ() > toZ || pos.getY() < minY || pos.getY() > maxY) continue;
                    CompoundTag data = ScanService.blockEntityTag(entry.getValue());
                    data.remove("x");
                    data.remove("y");
                    data.remove("z");
                    tile.blockEntities.put(tile.cell(pos.getX() - tileMinX(), pos.getY() - minY, pos.getZ() - tileMinZ()), data);
                }
            }
            done += (long) (toX - fromX + 1) * (toZ - fromZ + 1) * (toY - fromY + 1);
            if (++sectionY > maxY >> 4) {
                sectionY = minY >> 4;
                if (++chunkX > tileMaxX() >> 4) {
                    chunkX = tileMinX() >> 4;
                    chunkZ++;
                }
            }
        }

        private void endTile() {
            Tile finished = tile;
            Path file = ProjectStore.tile(newUndo, tileX, tileZ);
            tile = null;
            writes.add(CompletableFuture.runAsync(() -> {
                try {
                    finished.write(file);
                } catch (IOException e) {
                    writeError = e;
                }
            }, IO));
        }

        /** The snapshot is complete: it becomes the undo data, and the record says what to put back. */
        private void commit() throws IOException {
            Path undo = folder.resolve("undo");
            ProjectStore.deleteTree(undo);
            Files.move(newUndo, undo, StandardCopyOption.ATOMIC_MOVE);
            Record record = new Record(project.id, project.name, level.dimension().location().toString(), min, sizeX, sizeY, sizeZ);
            Path file = folder.resolve("test.json");
            Path temp = folder.resolve("test.json.tmp");
            Files.writeString(temp, record.toJson().toString(), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            recorded = true;
        }

        @Override
        void cleanUp(MinecraftServer server) {
            if (recorded) return;
            try {
                CompletableFuture.allOf(writes.toArray(new CompletableFuture[0])).join();
                ProjectStore.deleteTree(newUndo);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Could not remove the partial test placement snapshot", e);
            }
        }
    }

    /** Removes the entities the placement added, puts the snapshot back, then forgets the test placement. */
    private static final class UndoJob extends Job {
        final Record record;
        final Path undo;
        final Placer placer;
        int removed = -1;

        UndoJob(UUID owner, Record record, ServerLevel level, Path undo) {
            super(owner, record.project(), level, record.min(), record.sizeX(), record.sizeY(), record.sizeZ(),
                    (long) record.sizeX() * record.sizeY() * record.sizeZ());
            this.record = record;
            this.undo = undo;
            this.placer = new Placer(level, min, sizeX, sizeY, sizeZ, new SnapshotSource(undo));
        }

        @Override
        int code() {
            return UNDOING;
        }

        @Override
        boolean step(MinecraftServer server, long deadline) throws IOException {
            if (cancelled) {
                finish(server, KineticI18n.translatable("msg.realmcontrol.structure.test.undo_stopped"), false);
                return true;
            }
            if (removed < 0) {
                // Ticks waiting in the box (a placed water source's spread, say) would run between slices.
                clearTicks(level, new BoundingBox(min.getX(), min.getY(), min.getZ(), min.getX() + sizeX - 1, min.getY() + sizeY - 1, min.getZ() + sizeZ - 1));
                // Mobs may have walked out of the box: every loaded entity with the tag goes.
                List<Entity> tagged = new ArrayList<>();
                for (Entity entity : level.getAllEntities()) {
                    if (entity != null && tagged(entity)) tagged.add(entity);
                }
                tagged.forEach(Entity::discard);
                removed = tagged.size();
            }
            long before = placer.placed;
            boolean over = placer.step(deadline);
            done += placer.placed - before;
            if (!over) {
                sendProgress(server, false);
                return false;
            }
            ProjectStore.deleteTree(folder(server));
            finish(server, KineticI18n.translatable("msg.realmcontrol.structure.test.undone", record.name(), removed), true);
            return true;
        }
    }

    /** What a placer puts into the world: the draft as it generates, or the snapshot exactly. */
    private interface Source {
        @Nullable
        Tile tile(int tileX, int tileZ) throws IOException;

        default boolean skipTile(int tileX, int tileZ) {
            return false;
        }

        default boolean skipContents(Tile tile) {
            return false;
        }

        /** The cells of a tile to set; {@code ox}, {@code oz} are the tile's corner in the box. */
        boolean[] keep(Tile tile, int ox, int oz);

        /** The block entity data of a cell; {@code x}, {@code y}, {@code z} are the cell in the box. */
        @Nullable
        CompoundTag blockEntity(Tile tile, int cell, int x, int y, int z);

        List<Tile.EntityData> entities(Tile tile, int ox, int oz);

        CompoundTag entity(Tile.EntityData entity);

        /** As generation places it (waterlogging from the world, mobs set up) rather than an exact copy. */
        boolean generated();
    }

    private record DraftSource(StructureProject project, Cutouts cutouts, Markers markers,
                               @Nullable ProjectStore.Draft draft, boolean test) implements Source {
        DraftSource(StructureProject project, Cutouts cutouts, Markers markers) {
            this(project, cutouts, markers, null, true);
        }

        @Override
        public Tile tile(int tileX, int tileZ) throws IOException {
            return draft == null ? ProjectStore.readTile(project.id, tileX, tileZ) : Publisher.readTile(draft, tileX, tileZ);
        }

        @Override public boolean skipTile(int tileX, int tileZ) {
            return draft != null && !keepsAir() && draft.isEmptyTile(tileX, tileZ);
        }

        @Override public boolean skipContents(Tile tile) {
            return draft != null && !keepsAir() && tile.palette.size() == 1 && tile.entities.isEmpty();
        }

        private boolean keepsAir() {
            return "keep".equals(project.air) && !project.ignoreBlocks.contains(BlockStates.AIR);
        }

        @Override
        public boolean[] keep(Tile tile, int ox, int oz) {
            return Publisher.cellsToWrite(project, tile, cutouts, ox, oz);
        }

        @Override
        public CompoundTag blockEntity(Tile tile, int cell, int x, int y, int z) {
            return Publisher.blockEntity(tile.blockEntities.get(cell), x, y, z, markers, project.save);
        }

        @Override
        public List<Tile.EntityData> entities(Tile tile, int ox, int oz) {
            return Publisher.entitiesToWrite(tile, cutouts, markers, project.save, ox, oz);
        }

        @Override
        public CompoundTag entity(Tile.EntityData entity) {
            CompoundTag data = Publisher.entityData(entity.tag(), project.save).copy();
            if (!test) return data;
            ListTag tags = data.get("Tags") instanceof ListTag list ? list.copy() : new ListTag();
            tags.add(StringTag.valueOf(ENTITY_TAG));
            data.put("Tags", tags);
            return data;
        }

        @Override
        public boolean generated() {
            return true;
        }
    }

    private record SnapshotSource(Path folder) implements Source {
        @Override
        public Tile tile(int tileX, int tileZ) throws IOException {
            Path file = ProjectStore.tile(folder, tileX, tileZ);
            return Files.isRegularFile(file) ? Tile.read(file) : null;
        }

        @Override
        public boolean[] keep(Tile tile, int ox, int oz) {
            boolean[] keep = new boolean[tile.cells.length];
            java.util.Arrays.fill(keep, true);
            return keep;
        }

        @Override
        public CompoundTag blockEntity(Tile tile, int cell, int x, int y, int z) {
            return tile.blockEntities.get(cell);
        }

        @Override
        public List<Tile.EntityData> entities(Tile tile, int ox, int oz) {
            return List.of();
        }

        @Override
        public CompoundTag entity(Tile.EntityData entity) {
            return entity.tag();
        }

        @Override
        public boolean generated() {
            return false;
        }
    }

    /**
     * Places a tile one 16-block-high section at a time using vanilla templates, including block-entity NBT and
     * mob initialization. Draft tiles are read on the file thread.
     */
    private static final class Placer {
        final ServerLevel level;
        final BlockPos min;
        final int sizeX, sizeY, sizeZ, tileSize, tilesX, tilesZ;
        final Source source;
        @Nullable final PlacementChunk destination;
        int tileX, tileZ;
        @Nullable CompletableFuture<Loaded> loading;
        @Nullable Loaded loaded;
        @Nullable Section prepared;
        int chunkX, chunkZ, sectionY;
        long placed;
        boolean changed;

        private record Loaded(Tile tile, boolean[] keep, Map<Long, List<Tile.EntityData>> entities) {
        }

        private record Section(BlockPos from, BlockPos to, @Nullable CompoundTag template, boolean hasEntities) {
        }

        Placer(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, Source source) {
            this(level, min, sizeX, sizeY, sizeZ, source, TILE, false);
        }

        Placer(ServerLevel level, BlockPos min, int sizeX, int sizeY, int sizeZ, Source source, int tileSize, boolean permanent) {
            this.level = level;
            this.min = min;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.tileSize = tileSize;
            this.tilesX = (sizeX + tileSize - 1) / tileSize;
            this.tilesZ = (sizeZ + tileSize - 1) / tileSize;
            this.source = source;
            this.destination = permanent ? new PlacementChunk(level) : null;
        }

        /** Places until the tick deadline, returning true once every tile has been considered. */
        boolean step(long deadline) throws IOException {
            while (tileZ < tilesZ) {
                if (System.nanoTime() > deadline) return false;
                if (loaded == null) {
                    if (source.skipTile(tileX, tileZ)) {
                        skipTile();
                        continue;
                    }
                    if (loading == null) {
                        int tx = tileX, tz = tileZ;
                        loading = CompletableFuture.supplyAsync(() -> loadTile(tx, tz), IO);
                    }
                    if (!loading.isDone()) return false;
                    try {
                        loaded = loading.join();
                    } catch (java.util.concurrent.CompletionException e) {
                        if (e.getCause() instanceof java.io.UncheckedIOException io) throw io.getCause();
                        throw e.getCause() instanceof RuntimeException runtime ? runtime : e;
                    }
                    loading = null;
                    if (loaded == null) {
                        skipTile();
                        continue;
                    }
                    chunkX = tileMinX() >> 4;
                    chunkZ = tileMinZ() >> 4;
                    sectionY = min.getY() >> 4;
                }
                while (chunkZ <= (tileMaxZ() >> 4)) {
                    if (System.nanoTime() > deadline || !placeSection()) return false;
                }
                loaded = null;
                nextTile();
            }
            return true;
        }

        @Nullable
        private Loaded loadTile(int tx, int tz) {
            try {
                Tile tile = source.tile(tx, tz);
                if (tile == null) throw new IOException("Missing placement tile " + tx + "," + tz);
                if (source.skipContents(tile)) return null;
                int ox = tx * tileSize, oz = tz * tileSize;
                Map<Long, List<Tile.EntityData>> entities = new HashMap<>();
                for (Tile.EntityData entity : source.entities(tile, ox, oz)) {
                    int x = min.getX() + ox + (int) Math.floor(entity.x());
                    int y = min.getY() + (int) Math.floor(entity.y());
                    int z = min.getZ() + oz + (int) Math.floor(entity.z());
                    long section = SectionPos.asLong(x >> 4, y >> 4, z >> 4);
                    entities.computeIfAbsent(section, ignored -> new ArrayList<>()).add(entity);
                }
                return new Loaded(tile, source.keep(tile, ox, oz), entities);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private void skipTile() {
            placed += (long) (tileMaxX() - tileMinX() + 1) * sizeY * (tileMaxZ() - tileMinZ() + 1);
            nextTile();
        }

        private void nextTile() {
            if (++tileX >= tilesX) {
                tileX = 0;
                tileZ++;
            }
        }

        private int tileMinX() {
            return min.getX() + tileX * tileSize;
        }

        private int tileMinZ() {
            return min.getZ() + tileZ * tileSize;
        }

        private int tileMaxX() {
            return Math.min(tileMinX() + tileSize, min.getX() + sizeX) - 1;
        }

        private int tileMaxZ() {
            return Math.min(tileMinZ() + tileSize, min.getZ() + sizeZ) - 1;
        }

        private boolean placeSection() throws IOException {
            if (prepared == null) prepared = prepareSection();
            Section section = prepared;
            if (section.template() != null) {
                LevelChunk chunk = destination == null ? level.getChunk(chunkX, chunkZ) : destination.ready(chunkX, chunkZ);
                if (chunk == null) return false;
                applySection(chunk, section);
            }
            BlockPos from = section.from(), to = section.to();
            placed += (long) (to.getX() - from.getX() + 1) * (to.getZ() - from.getZ() + 1) * (to.getY() - from.getY() + 1);
            prepared = null;
            if (++sectionY > ((min.getY() + sizeY - 1) >> 4)) {
                if (destination != null) destination.release();
                sectionY = min.getY() >> 4;
                if (++chunkX > (tileMaxX() >> 4)) {
                    chunkX = tileMinX() >> 4;
                    chunkZ++;
                }
            }
            return true;
        }

        private Section prepareSection() {
            Loaded loaded = this.loaded;
            Tile tile = loaded.tile();
            int fromX = Math.max(tileMinX(), chunkX << 4), toX = Math.min(tileMaxX(), (chunkX << 4) + 15);
            int fromZ = Math.max(tileMinZ(), chunkZ << 4), toZ = Math.min(tileMaxZ(), (chunkZ << 4) + 15);
            int fromY = Math.max(min.getY(), sectionY << 4), toY = Math.min(min.getY() + sizeY - 1, (sectionY << 4) + 15);
            // Tile cells are relative to the tile origin; the section template is relative to its own lower corner.
            int cx0 = fromX - tileMinX(), cx1 = toX - tileMinX(), cz0 = fromZ - tileMinZ(), cz1 = toZ - tileMinZ();
            int cy0 = fromY - min.getY(), cy1 = toY - min.getY();
            int ox = tileX * tileSize, oz = tileZ * tileSize;
            Map<Integer, Integer> remap = new HashMap<>();
            ListTag palette = new ListTag(), blocks = new ListTag(), entities = new ListTag();
            boolean[] keep = loaded.keep();
            for (int y = cy0; y <= cy1; y++) {
                for (int z = cz0; z <= cz1; z++) {
                    for (int x = cx0; x <= cx1; x++) {
                        int cell = tile.cell(x, y, z);
                        if (!keep[cell]) continue;
                        int index = tile.cells[cell] & 0xFFFF;
                        Integer state = remap.get(index);
                        if (state == null) {
                            state = palette.size();
                            remap.put(index, state);
                            palette.add(paletteEntry(tile.palette.get(index)));
                        }
                        CompoundTag block = new CompoundTag();
                        block.put("pos", ints(x - cx0, y - cy0, z - cz0));
                        block.putInt("state", state);
                        CompoundTag nbt = source.blockEntity(tile, cell, ox + x, y, oz + z);
                        if (nbt != null) block.put("nbt", nbt.copy());
                        blocks.add(block);
                    }
                }
            }
            long key = SectionPos.asLong(chunkX, sectionY, chunkZ);
            for (Tile.EntityData entity : loaded.entities().getOrDefault(key, List.of())) {
                int ex = (int) Math.floor(entity.x()), ey = (int) Math.floor(entity.y()), ez = (int) Math.floor(entity.z());
                if (ex < cx0 || ex > cx1 || ey < cy0 || ey > cy1 || ez < cz0 || ez > cz1) continue;
                CompoundTag info = new CompoundTag();
                ListTag pos = new ListTag();
                pos.add(DoubleTag.valueOf(entity.x() - cx0));
                pos.add(DoubleTag.valueOf(entity.y() - cy0));
                pos.add(DoubleTag.valueOf(entity.z() - cz0));
                info.put("pos", pos);
                info.put("blockPos", ints(ex - cx0, ey - cy0, ez - cz0));
                info.put("nbt", source.entity(entity));
                entities.add(info);
            }
            BlockPos from = new BlockPos(fromX, fromY, fromZ), to = new BlockPos(toX, toY, toZ);
            if (blocks.isEmpty() && entities.isEmpty()) return new Section(from, to, null, false);
            CompoundTag root = new CompoundTag();
            root.put("size", ints(cx1 - cx0 + 1, cy1 - cy0 + 1, cz1 - cz0 + 1));
            root.put("palette", palette);
            root.put("blocks", blocks);
            root.put("entities", entities);
            root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            return new Section(from, to, root, !entities.isEmpty());
        }

        private void applySection(LevelChunk chunk, Section section) {
            BlockPos from = section.from(), to = section.to();
            StructureTemplate template = new StructureTemplate();
            template.load(BlockStates.lookup(), section.template());
            StructurePlaceSettings settings = new StructurePlaceSettings().setKnownShape(true)
                    .setIgnoreEntities(!section.hasEntities()).setFinalizeEntities(source.generated());
            // Preserve the draft's saved fluid states during permanent placement. Vanilla's ambient-water pass
            // looks into neighboring chunks and can synchronously load them at a section edge.
            liquids(settings, destination == null && source.generated());
            changed = true;
            // Clear inventories of replaced containers before vanilla swaps their block entities.
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = entry.getKey();
                if (pos.getX() < from.getX() || pos.getX() > to.getX() || pos.getZ() < from.getZ() || pos.getZ() > to.getZ()
                        || pos.getY() < from.getY() || pos.getY() > to.getY()) continue;
                if (entry.getValue() instanceof Clearable clearable
                        && loaded.keep()[loaded.tile().cell(pos.getX() - tileMinX(), pos.getY() - min.getY(), pos.getZ() - tileMinZ())]) {
                    clearable.clearContent();
                }
            }
            template.placeInWorld(level, from, from, settings, level.getRandom(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            // Test and undo need the exact captured shape; a permanent placement retains normal block and fluid ticks.
            if (destination == null) clearTicks(level, BoundingBox.fromCorners(from, to));
        }

        void close() {
            if (destination != null) destination.close();
        }
    }

    /** Whether a test placement added this entity. */
    public static boolean tagged(Entity entity) {
        //? if >=26.1 {
        /*return entity.entityTags().contains(ENTITY_TAG);
        *///?} else {
        return entity.getTags().contains(ENTITY_TAG);
        //?}
    }

    /** Drops the block and fluid ticks waiting in {@code box}. */
    private static void clearTicks(ServerLevel level, BoundingBox box) {
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
    }

    /** Waterlogging: from the world where generation would take it, never for an exact restore. */
    private static void liquids(StructurePlaceSettings settings, boolean generated) {
        //? if >=1.21 {
        /*settings.setLiquidSettings(generated ? net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.APPLY_WATERLOGGING
                : net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings.IGNORE_WATERLOGGING);
        *///?} else {
        settings.setKeepLiquids(generated);
        //?}
    }

    /** A palette entry as structure templates save it: Name, then Properties when there are any. */
    private static CompoundTag paletteEntry(String state) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Name", BlockStates.blockId(state));
        Map<String, String> properties = BlockStates.properties(state);
        if (!properties.isEmpty()) {
            CompoundTag values = new CompoundTag();
            properties.forEach(values::putString);
            entry.put("Properties", values);
        }
        return entry;
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) list.add(IntTag.valueOf(value));
        return list;
    }
}
