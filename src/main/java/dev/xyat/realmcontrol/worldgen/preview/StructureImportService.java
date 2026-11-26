package dev.xyat.realmcontrol.worldgen.preview;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.mega.MegaStructure;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.ImportedStructureDraft;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Captures a registered structure's selected layout without placing it in the world. */
public final class StructureImportService {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final ConcurrentHashMap<UUID, Job> JOBS = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), work -> {
                Thread thread = new Thread(work, "RealmControl registered structure import");
                thread.setDaemon(true);
                return thread;
            });

    public record Result(@Nullable StructureProject project, @Nullable Component error) { }

    private static final class Job {
        final MinecraftServer server;
        final UUID owner;
        final Consumer<Result> completion;
        final AtomicReference<ImportedStructureDraft.Staged> staged = new AtomicReference<>();
        volatile boolean cancelled;

        private Job(MinecraftServer server, UUID owner, Consumer<Result> completion) {
            this.server = server;
            this.owner = owner;
            this.completion = completion;
        }

        void abandon() {
            cancelled = true;
            ImportedStructureDraft.Staged pending = staged.getAndSet(null);
            if (pending != null) pending.close();
        }
    }

    private StructureImportService() { }

    /** Called on the server thread. A still-connected requester receives completion on that thread. */
    public static void request(ServerPlayer player, String structureId, int variant, String draftId, Consumer<Result> completion) {
        if (!StructureEditorModule.allowed(player)) {
            completion.accept(failure("permission"));
            return;
        }
        if (!StructureProject.validId(draftId)) {
            completion.accept(new Result(null, KineticI18n.translatable("msg.realmcontrol.structure.project.bad_id", draftId)));
            return;
        }
        if (variant < 0 || variant > StructureSampler.MAX_VARIANT) {
            completion.accept(failure("variant"));
            return;
        }
        if (ProjectStore.exists(draftId)) {
            completion.accept(failure("exists", draftId));
            return;
        }
        MinecraftServer server = player.getServer();
        Holder.Reference<Structure> structure = StructurePreviewService.structure(server, structureId);
        if (structure == null) {
            completion.accept(new Result(null, KineticI18n.translatable("gui.realmcontrol.worldgen.preview.unknown")));
            return;
        }
        if (structure.value() instanceof MegaStructure) {
            completion.accept(failure("mega"));
            return;
        }
        ServerLevel level = StructurePreviewService.levelFor(server, structureId);
        if (level == null) {
            completion.accept(failure("pieces"));
            return;
        }
        Job job = new Job(server, player.getUUID(), completion);
        if (JOBS.putIfAbsent(job.owner, job) != null) {
            completion.accept(failure("busy"));
            return;
        }
        try {
            WORKER.execute(() -> prepare(job, level, structure, structureId, variant, draftId));
            StructureEditorNetwork.result(player, KineticI18n.translatable("msg.realmcontrol.structure.import.started", structureId), true);
        } catch (RejectedExecutionException e) {
            JOBS.remove(job.owner, job);
            completion.accept(failure("busy"));
        }
    }

    public static void stop(MinecraftServer server) {
        JOBS.values().removeIf(job -> {
            if (job.server != server) return false;
            job.abandon();
            return true;
        });
    }

    private static void prepare(Job job, ServerLevel level, Holder.Reference<Structure> structure, String structureId,
                                int variant, String draftId) {
        if (job.cancelled) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        BooleanSupplier cancelled = () -> job.cancelled || System.nanoTime() - deadline >= 0;
        try {
            StructureSampler.Capture capture = StructureSampler.capture(level, structure, variant, share -> { }, cancelled, true);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            if (capture == null) {
                deliver(job, new Result(null, KineticI18n.translatable("gui.realmcontrol.worldgen.preview.no_layout")), draftId);
                return;
            }
            StructureProject project = new StructureProject();
            project.id = draftId;
            project.name = draftId;
            //? if >=26.1 {
            /*project.sourceStructure = structure.key().identifier().toString();
            *///?} else {
            project.sourceStructure = structure.key().location().toString();
            //?}
            project.sourceVariant = variant;
            project.dimension = level.dimension().location().toString();
            project.generation.dimensions.set(0, project.dimension);
            project.generation.salt = Math.floorMod(draftId.hashCode(), 1_000_000_000);
            project.createdVersion = minecraftVersion();
            project.dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            project.scannedAt = System.currentTimeMillis();
            ImportedStructureDraft output = copy(level, capture.level(), project, cancelled);
            job.staged.set(output.stage(cancelled));
            if (job.cancelled) job.abandon();
            else deliver(job, new Result(project, null), draftId);
        } catch (CancellationException e) {
            if (!job.cancelled) deliver(job, failure("timeout"), draftId);
        } catch (StructureSampler.ImportFailure e) {
            deliver(job, failure(e.reason), draftId);
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not import {} variant {} as {}", structureId, variant, draftId, e);
            deliver(job, failure("failed"), draftId);
        }
    }

    private static ImportedStructureDraft copy(ServerLevel level, CaptureLevel captured, StructureProject project, BooleanSupplier cancelled) {
        Bounds bounds = new Bounds();
        for (var entry : captured.written().long2ObjectEntrySet()) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            long pos = entry.getLongKey();
            if (!entry.getValue().isAir()) bounds.include(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
        }
        if (bounds.minX == Integer.MAX_VALUE) throw new StructureSampler.ImportFailure("empty");
        var entities = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        for (Entity entity : captured.entities()) {
            if (entity.isPassenger()) continue;
            if (entity instanceof Player || !entity.getType().canSerialize() || !Double.isFinite(entity.getX())
                    || !Double.isFinite(entity.getY()) || !Double.isFinite(entity.getZ())) {
                throw new StructureSampler.ImportFailure("pieces");
            }
            entities.add(entity);
            BlockPos pos = entity.blockPosition();
            bounds.include(pos.getX(), pos.getY(), pos.getZ());
        }
        BlockPos min = new BlockPos(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = new BlockPos(bounds.maxX, bounds.maxY, bounds.maxZ);
        StructureSampler.checkImportBounds(min, max, level);
        project.size = new int[]{max.getX() - min.getX() + 1, max.getY() - min.getY() + 1, max.getZ() - min.getZ() + 1};
        if ("mega".equals(project.tier())) {
            project.tileSize = 32;
            project.generation.separation = project.megaSeparation() + 4;
            project.generation.spacing = project.generation.separation + 24;
        }
        ImportedStructureDraft draft = new ImportedStructureDraft(project);
        var palette = new IdentityHashMap<net.minecraft.world.level.block.state.BlockState, String>();
        for (var entry : captured.written().long2ObjectEntrySet()) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            var state = entry.getValue();
            if (state.isAir()) continue;
            BlockPos pos = BlockPos.of(entry.getLongKey());
            int x = pos.getX() - min.getX(), y = pos.getY() - min.getY(), z = pos.getZ() - min.getZ();
            draft.putBlock(x, y, z, palette.computeIfAbsent(state, BlockStates::serialize));
            if (!state.hasBlockEntity()) continue;
            var blockEntity = captured.blockEntity(pos);
            if (blockEntity == null) throw new StructureSampler.ImportFailure("pieces");
            var data = ScanService.blockEntityTag(blockEntity, level.registryAccess());
            data.remove("x");
            data.remove("y");
            data.remove("z");
            draft.putBlockEntity(x, y, z, blockEntity, data);
        }
        for (Entity entity : entities) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            draft.putEntity(entity.getX() - min.getX(), entity.getY() - min.getY(), entity.getZ() - min.getZ(),
                    ScanService.entityTag(entity), entity.hasCustomName() ? entity.getCustomName().getString() : "");
        }
        return draft;
    }

    private static void deliver(Job job, Result result, String draftId) {
        if (job.cancelled) return;
        job.server.execute(() -> {
            try (var staged = job.staged.getAndSet(null)) {
                if (!JOBS.remove(job.owner, job) || job.cancelled) return;
                ServerPlayer player = job.server.getPlayerList().getPlayer(job.owner);
                if (player == null) return;
                if (!StructureEditorModule.allowed(player)) job.completion.accept(failure("permission"));
                else if (staged == null) job.completion.accept(result);
                else job.completion.accept(staged.commit() ? result : failure("exists", draftId));
            } catch (IOException e) {
                LOGGER.warn("Could not activate the imported draft {}", draftId, e);
                job.completion.accept(failure("failed"));
            }
        });
    }

    private static Result failure(String code, Object... parameters) {
        return new Result(null, KineticI18n.translatable("msg.realmcontrol.structure.import." + code, parameters));
    }

    private static String minecraftVersion() {
        //? if >=26.1 {
        /*return SharedConstants.getCurrentVersion().name();
        *///?} else {
        return SharedConstants.getCurrentVersion().getName();
        //?}
    }

    private static final class Bounds {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        void include(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
    }
}
