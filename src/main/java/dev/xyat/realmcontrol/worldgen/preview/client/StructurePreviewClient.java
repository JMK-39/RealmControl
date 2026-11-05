package dev.xyat.realmcontrol.worldgen.preview.client;

import dev.xyat.kineticcore.api.network.KineticBlobTransfer;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Client side of structure previews: asks the server for a sample, reports progress while it is generated and sent
 * (generation counts for 85 %, the transfer for the rest), decodes the snapshot off the render thread and keeps the
 * last few previews so going back to a structure shows it at once.
 */
public final class StructurePreviewClient {
    private static final int CACHED = 8;
    private static final KineticBlobTransfer.Assembler ASSEMBLER =
            new KineticBlobTransfer.Assembler(KineticBlockSnapshot.MAX_ENCODED_BYTES, 30_000L);
    private static final Map<String, Result> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static int nextRequest = 1;
    private static int current;
    @Nullable private static String currentKey;
    @Nullable private static Listener listener;
    @Nullable private static WorldGenNetwork.StructurePreviewInfoPacket currentInfo;

    /** A finished preview: the snapshot and what the server said about the sample. */
    public record Result(KineticBlockSnapshot snapshot, WorldGenNetwork.StructurePreviewInfoPacket info) {
    }

    /** Receives the state of the preview that was asked for last. Called on the client thread. */
    public interface Listener {
        void progress(float share);

        void finished(Result result);

        void failed(Component reason);
    }

    private StructurePreviewClient() {
    }

    /** Asks for a preview; a cached one is delivered at once. Replaces any earlier request and its listener. */
    public static void request(String structureId, int variant, Listener target) {
        if (current != 0) ASSEMBLER.cancel(current);
        listener = target;
        currentKey = structureId + "#" + variant;
        currentInfo = null;
        Result cached = CACHE.get(currentKey);
        if (cached != null) {
            current = 0;
            target.finished(cached);
            return;
        }
        current = nextRequest++;
        target.progress(0F);
        WorldGenNetwork.requestStructurePreview(current, structureId, variant);
    }

    /** Stops listening, for a screen that closes. */
    public static void cancel() {
        if (current != 0) ASSEMBLER.cancel(current);
        current = 0;
        listener = null;
    }

    /** Stops listening if {@code owner} is still the listener, for a screen that closes after another took over. */
    public static void cancel(Listener owner) {
        if (listener == owner) cancel();
    }

    /** Forgets the kept previews, for example after the structures were refreshed. */
    public static void clearCache() {
        CACHE.clear();
    }

    /** What is already known about a structure's preview, for marking rows, or {@code null}. */
    @Nullable
    public static WorldGenNetwork.StructurePreviewInfoPacket known(String structureId) {
        Result result = CACHE.get(structureId + "#0");
        return result == null ? null : result.info();
    }

    public static void handleStatus(WorldGenNetwork.StructurePreviewStatusPacket packet) {
        if (packet.requestId() != current || listener == null) return;
        if (packet.error() != null) {
            listener.failed(packet.error());
            current = 0;
        } else {
            listener.progress(0.85F * Math.max(0F, Math.min(1F, packet.progress())));
        }
    }

    public static void handleInfo(WorldGenNetwork.StructurePreviewInfoPacket packet) {
        if (packet.requestId() != current) return;
        currentInfo = packet;
        if (listener != null) listener.progress(0.85F);
    }

    public static void handlePart(WorldGenNetwork.StructurePreviewPartPacket packet) {
        if (packet.requestId() != current) return;
        byte[] whole = ASSEMBLER.accept(packet.requestId(), packet.index(), packet.total(), packet.data());
        if (whole == null) {
            if (listener != null) listener.progress(0.85F + 0.15F * ASSEMBLER.progress(packet.requestId()));
            return;
        }
        int request = current;
        String key = currentKey;
        WorldGenNetwork.StructurePreviewInfoPacket info = currentInfo;
        CompletableFuture.supplyAsync(() -> KineticBlockSnapshot.decode(whole)).whenComplete((snapshot, error) ->
                KineticClientRuntime.execute(() -> {
                    if (request != current || listener == null) return;
                    current = 0;
                    if (error != null || info == null) {
                        listener.failed(dev.xyat.kineticcore.api.text.KineticI18n.translatable(
                                "gui.realmcontrol.worldgen.preview.failed", error == null ? "?" : String.valueOf(error.getMessage())));
                        return;
                    }
                    Result result = new Result(snapshot, info);
                    CACHE.put(key, result);
                    while (CACHE.size() > CACHED) CACHE.remove(CACHE.keySet().iterator().next());
                    listener.finished(result);
                }));
    }
}
