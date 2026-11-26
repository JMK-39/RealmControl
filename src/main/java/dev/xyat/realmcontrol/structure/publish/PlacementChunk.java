
package dev.xyat.realmcontrol.structure.publish;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
//? if <1.20.5 {
import net.minecraft.world.level.chunk.ChunkStatus;
//?} else {
/*import net.minecraft.world.level.chunk.status.ChunkStatus;
*///?}

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Loads and holds one destination chunk without waiting on a server tick. */
final class PlacementChunk implements AutoCloseable {
    private static final long CHUNK_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    //? if >=26.1 {
    /*private static final TicketType TICKET = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
    *///?} else {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("realmcontrol_permanent_placement", Comparator.comparingLong(ChunkPos::toLong));
    //?}
    private static final ExecutorService REQUESTS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "RealmControl placement chunk requests");
        thread.setDaemon(true);
        return thread;
    });

    private final ServerLevel level;
    @Nullable private ChunkPos held;
    @Nullable private CompletableFuture<ChunkAccess> request;
    private long requestedAt;
    private volatile boolean closed;

    PlacementChunk(ServerLevel level) {
        this.level = level;
    }

    @Nullable LevelChunk ready(int x, int z) throws IOException {
        var provider = level.getChunkSource();
        if (held == null) {
            held = new ChunkPos(x, z);
            //? if >=26.1 {
            /*provider.addTicketWithRadius(TICKET, held, 0);
            *///?} else {
            provider.addRegionTicket(TICKET, held, 0, held);
            //?}
        } else if (!held.equals(new ChunkPos(x, z))) {
            throw new IllegalStateException("Destination advanced without releasing placement chunk " + held);
        }
        LevelChunk loaded = provider.getChunkNow(x, z);
        if (loaded != null) return loaded;
        if (request == null) {
            requestedAt = System.nanoTime();
            // Vanilla waits synchronously when this method is invoked directly on the server thread.
            request = CompletableFuture.supplyAsync(() -> {
                if (closed) return CompletableFuture.<ChunkAccess>completedFuture(null);
                return provider.getChunkFuture(x, z, ChunkStatus.FULL, true).thenApply(result -> {
                    //? if <1.20.5 {
                    return result.left().orElse(null);
                    //?} else {
                    /*return result.orElse(null);
                    *///?}
                });
            }, REQUESTS).thenCompose(future -> future);
        }
        if (request.isDone()) {
            if (request.join() instanceof LevelChunk full) return full;
            throw new IOException("Could not load placement chunk " + held);
        }
        if (System.nanoTime() - requestedAt >= CHUNK_TIMEOUT_NANOS) {
            throw new IOException("Timed out loading placement chunk " + held);
        }
        return null;
    }

    void release() {
        if (held == null) return;
        var provider = level.getChunkSource();
        //? if >=26.1 {
        /*provider.removeTicketWithRadius(TICKET, held, 0);
        *///?} else {
        provider.removeRegionTicket(TICKET, held, 0, held);
        //?}
        held = null;
        request = null;
    }

    @Override
    public void close() {
        closed = true;
        release();
    }
}
