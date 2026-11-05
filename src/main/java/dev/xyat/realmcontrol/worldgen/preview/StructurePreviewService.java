package dev.xyat.realmcontrol.worldgen.preview;

import dev.xyat.kineticcore.api.network.KineticBlobTransfer;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.realmcontrol.worldgen.config.StructureGenerationControl;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.BooleanSupplier;

/**
 * Answers preview requests from the structure panel: samples the structure on a background thread, sends the
 * progress as a percentage, then the encoded snapshot in parts. One request per player runs at a time (a new one
 * cancels the old one) and finished samples are kept for reuse until the server stops. Samples for row thumbnails
 * share the same thread but wait behind every interactive preview.
 */
public final class StructurePreviewService {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final int CACHED = 24;
    private static final long PROGRESS_INTERVAL_NANOS = 150_000_000L;
    /** Interactive previews go to the front, thumbnail samples to the back. */
    private static final LinkedBlockingDeque<Runnable> QUEUE = new LinkedBlockingDeque<>();
    private static final Map<String, Prepared> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();

    static {
        Thread worker = new Thread(() -> {
            while (true) {
                Runnable task;
                try {
                    task = QUEUE.takeFirst();
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("Structure preview task failed", e);
                }
            }
        }, "RealmControl structure preview");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** An encoded sample ready to send. */
    private record Prepared(WorldGenNetwork.StructurePreviewInfoPacket info, List<byte[]> parts) {
    }

    private static final class Job {
        volatile boolean cancelled;
    }

    private StructurePreviewService() {
    }

    /** Starts preparing a preview for a player; progress, the result and any failure are sent back to them. */
    public static void request(ServerPlayer player, int requestId, String structureId, int variant) {
        if (!player.hasPermissions(2)) return;
        MinecraftServer server = player.getServer();
        String key = structureId + "#" + variant;
        Prepared cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        Job job = new Job();
        Job previous = JOBS.put(player.getUUID(), job);
        if (previous != null) previous.cancelled = true;
        if (cached != null) {
            send(player, requestId, cached);
            return;
        }
        if (structureId.startsWith(dev.xyat.realmcontrol.structure.project.ProjectPreview.PREFIX)) {
            requestProject(server, player.getUUID(), requestId, structureId, key, job, false);
            return;
        }
        Holder.Reference<Structure> structure = structure(server, structureId);
        if (structure == null) {
            fail(player, requestId, dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.worldgen.preview.unknown"));
            return;
        }
        // A mega structure is too big to sample; its draft shows it, coarser when it is large.
        if (structure.value() instanceof dev.xyat.realmcontrol.structure.mega.MegaStructure) {
            if (dev.xyat.realmcontrol.structure.project.ProjectStore.exists(structureId)) {
                requestProject(server, player.getUUID(), requestId, dev.xyat.realmcontrol.structure.project.ProjectPreview.PREFIX + structureId, key, job, true);
            } else {
                fail(player, requestId, dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.worldgen.preview.mega_no_draft"));
            }
            return;
        }
        ServerLevel level = levelFor(server, structureId);
        UUID owner = player.getUUID();
        QUEUE.addFirst(() -> {
            if (job.cancelled) return;
            long[] lastSent = {0L};
            try {
                StructureSampler.Sample sample = StructureSampler.sample(level, structure, variant, share -> {
                    long now = System.nanoTime();
                    if (now - lastSent[0] < PROGRESS_INTERVAL_NANOS) return;
                    lastSent[0] = now;
                    server.execute(() -> progress(server, owner, requestId, share));
                }, () -> job.cancelled);
                if (job.cancelled) return;
                if (sample == null) {
                    server.execute(() -> failFor(server, owner, requestId,
                            dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.worldgen.preview.no_layout")));
                    return;
                }
                Prepared prepared = keep(key, requestId, structureId, sample);
                server.execute(() -> {
                    ServerPlayer target = server.getPlayerList().getPlayer(owner);
                    if (target != null && !job.cancelled) send(target, requestId, prepared);
                });
                if (variant == 0) StructureThumbnailService.offer(server, owner, structureId, sample.snapshot());
            } catch (RuntimeException | LinkageError e) {
                server.execute(() -> failFor(server, owner, requestId,
                        dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.worldgen.preview.failed", String.valueOf(e.getMessage()))));
            }
        });
    }

    /** A structure editor draft: its tiles read into a snapshot; {@code generated} leaves its cut-outs out. */
    private static void requestProject(MinecraftServer server, UUID owner, int requestId, String structureId, String key, Job job, boolean generated) {
        String id = structureId.substring(dev.xyat.realmcontrol.structure.project.ProjectPreview.PREFIX.length());
        QUEUE.addFirst(() -> {
            if (job.cancelled) return;
            long[] lastSent = {0L};
            try {
                var result = dev.xyat.realmcontrol.structure.project.ProjectPreview.read(id, share -> {
                    long now = System.nanoTime();
                    if (now - lastSent[0] < PROGRESS_INTERVAL_NANOS) return;
                    lastSent[0] = now;
                    server.execute(() -> progress(server, owner, requestId, share));
                }, () -> job.cancelled, generated);
                if (job.cancelled) return;
                if (result == null) {
                    server.execute(() -> failFor(server, owner, requestId,
                            dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.worldgen.preview.unknown")));
                    return;
                }
                Prepared prepared = keep(key, requestId, structureId, new StructureSampler.Sample(result.snapshot(), result.missing(),
                        result.tiles(), result.sizeX(), result.sizeY(), result.sizeZ(), 0));
                server.execute(() -> {
                    ServerPlayer target = server.getPlayerList().getPlayer(owner);
                    if (target != null && !job.cancelled) send(target, requestId, prepared);
                });
            } catch (java.io.IOException | RuntimeException | LinkageError e) {
                LOGGER.warn("Preview of structure project {} failed", id, e);
                server.execute(() -> failFor(server, owner, requestId,
                        dev.xyat.kineticcore.api.text.KineticI18n.translatable("gui.realmcontrol.worldgen.preview.failed", String.valueOf(e.getMessage()))));
            }
        });
    }

    /** Forgets the kept samples of one structure or draft, after it changed. */
    public static void forget(String structureId) {
        synchronized (CACHE) {
            CACHE.keySet().removeIf(key -> key.startsWith(structureId + "#"));
        }
    }

    /** Queues a task behind every interactive preview, on the preview thread. */
    static void later(Runnable task) {
        QUEUE.addLast(task);
    }

    /**
     * Samples a structure's first layout for its thumbnail, on the preview thread, and keeps the sample so opening
     * its preview afterwards is immediate. Returns {@code null} if no layout was found or {@code cancelled} turned
     * true.
     */
    @Nullable
    static KineticBlockSnapshot sampleForThumbnail(MinecraftServer server, String structureId, BooleanSupplier cancelled) {
        Holder.Reference<Structure> structure = structure(server, structureId);
        if (structure == null) return null;
        if (structure.value() instanceof dev.xyat.realmcontrol.structure.mega.MegaStructure) {
            try {
                var draft = dev.xyat.realmcontrol.structure.project.ProjectPreview.read(structureId, share -> { }, cancelled, true);
                return draft == null ? null : draft.snapshot();
            } catch (java.io.IOException | RuntimeException e) {
                LOGGER.debug("Thumbnail of mega structure {} failed", structureId, e);
                return null;
            }
        }
        try {
            StructureSampler.Sample sample = StructureSampler.sample(levelFor(server, structureId), structure, 0,
                    share -> { }, cancelled);
            if (sample == null) return null;
            String key = structureId + "#0";
            boolean known;
            synchronized (CACHE) {
                known = CACHE.containsKey(key);
            }
            if (!known) keep(key, 0, structureId, sample);
            return sample.snapshot();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Thumbnail sample of {} failed", structureId, e);
            return null;
        }
    }

    /** Forgets cached samples, for example when the server stops or the structures are refreshed. */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        JOBS.values().forEach(job -> job.cancelled = true);
        JOBS.clear();
    }

    private static Prepared keep(String key, int requestId, String structureId, StructureSampler.Sample sample) {
        byte[] encoded = sample.snapshot().encode();
        List<byte[]> parts = KineticBlobTransfer.split(encoded, KineticBlobTransfer.DEFAULT_PART_BYTES);
        Prepared prepared = new Prepared(new WorldGenNetwork.StructurePreviewInfoPacket(requestId, structureId,
                sample.pieces(), sample.sizeX(), sample.sizeY(), sample.sizeZ(), sample.missing(),
                parts.size(), sample.failedChunks()), parts);
        synchronized (CACHE) {
            CACHE.put(key, prepared);
            while (CACHE.size() > CACHED) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return prepared;
    }

    @Nullable
    private static Holder.Reference<Structure> structure(MinecraftServer server, String structureId) {
        ResourceLocation id = ResourceLocation.tryParse(structureId);
        var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return id == null ? null : registry.getHolder(ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
    }

    private static ServerLevel levelFor(MinecraftServer server, String structureId) {
        for (String dimension : StructureGenerationControl.getStructureDimensions(server, structureId)) {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
            if (level != null) return level;
        }
        return server.getLevel(Level.OVERWORLD);
    }

    private static void send(ServerPlayer player, int requestId, Prepared prepared) {
        WorldGenNetwork.StructurePreviewInfoPacket info = prepared.info();
        WorldGenNetwork.CHANNEL.sendToPlayer(player, new WorldGenNetwork.StructurePreviewInfoPacket(requestId,
                info.structureId(), info.pieces(), info.sizeX(), info.sizeY(), info.sizeZ(), info.missing(),
                info.parts(), info.failedChunks()));
        List<byte[]> parts = prepared.parts();
        for (int i = 0; i < parts.size(); i++) {
            WorldGenNetwork.CHANNEL.sendToPlayer(player, new WorldGenNetwork.StructurePreviewPartPacket(requestId, i, parts.size(), parts.get(i)));
        }
    }

    private static void progress(MinecraftServer server, UUID owner, int requestId, float share) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            WorldGenNetwork.CHANNEL.sendToPlayer(player, new WorldGenNetwork.StructurePreviewStatusPacket(requestId, share, null));
        }
    }

    private static void fail(ServerPlayer player, int requestId, Component reason) {
        WorldGenNetwork.CHANNEL.sendToPlayer(player, new WorldGenNetwork.StructurePreviewStatusPacket(requestId, 0F, reason));
    }

    private static void failFor(MinecraftServer server, UUID owner, int requestId, Component reason) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) fail(player, requestId, reason);
    }
}
