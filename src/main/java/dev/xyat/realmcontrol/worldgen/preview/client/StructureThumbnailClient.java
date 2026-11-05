package dev.xyat.realmcontrol.worldgen.preview.client;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.render.KineticRuntimeTextures;
import dev.xyat.kineticcore.api.client.gui.render.KineticTexture;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client side of row thumbnails: rows say which structures they show each frame, the missing ones are asked for in
 * one request whenever that set changes, and arriving pictures become textures. The most recently shown pictures are
 * kept; all are dropped on logout and when the structures are refreshed.
 */
public final class StructureThumbnailClient {
    private static final int CACHED = 256;
    private static final long REQUEST_INTERVAL_NANOS = 200_000_000L;
    /** Known structures; a {@code null} texture means the structure has no layout to show. */
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75F, true);
    private static final Set<String> SHOWN = new LinkedHashSet<>();
    private static List<String> requested = List.of();
    private static long lastRequest;
    private static int nextTexture;

    private record Entry(@Nullable KineticTexture texture) {
    }

    static {
        KineticClientEvents.onLogout(StructureThumbnailClient::clear);
    }

    private StructureThumbnailClient() {
    }

    /** The thumbnail of a structure, or {@code null} while it is not known; notes the structure as shown. */
    @Nullable
    public static KineticTexture get(String structureId) {
        Entry entry = ENTRIES.get(structureId);
        if (entry == null) SHOWN.add(structureId);
        return entry == null ? null : entry.texture();
    }

    /** Whether a structure's thumbnail is here, without noting the structure as shown. */
    public static boolean ready(String structureId) {
        Entry entry = ENTRIES.get(structureId);
        return entry != null && entry.texture() != null;
    }

    /** Whether the server found no layout for this structure, so no thumbnail will come. */
    public static boolean none(String structureId) {
        Entry entry = ENTRIES.get(structureId);
        return entry != null && entry.texture() == null;
    }

    /**
     * Asks for the thumbnails the rows shown since the last call are missing, once that set changes; at most every
     * 200 ms. Call once per frame after drawing the rows.
     */
    public static void flush() {
        List<String> shown = new ArrayList<>(SHOWN);
        // Pictures that arrived since the last request are not a change; the server drops them by itself.
        List<String> stillMissing = new ArrayList<>(requested);
        stillMissing.removeIf(ENTRIES::containsKey);
        if (shown.equals(stillMissing)) {
            SHOWN.clear();
            return;
        }
        long now = System.nanoTime();
        if (now - lastRequest < REQUEST_INTERVAL_NANOS) return;
        SHOWN.clear();
        requested = shown;
        lastRequest = now;
        WorldGenNetwork.requestStructureThumbnails(shown);
    }

    /** Stops the thumbnails still being made, for a screen that closes. */
    public static void stop() {
        SHOWN.clear();
        if (requested.isEmpty()) return;
        requested = List.of();
        WorldGenNetwork.requestStructureThumbnails(List.of());
    }

    /** Drops every thumbnail, for example after the structures were refreshed. */
    public static void clear() {
        for (Entry entry : ENTRIES.values()) {
            if (entry.texture() != null) KineticRuntimeTextures.release(entry.texture());
        }
        ENTRIES.clear();
        SHOWN.clear();
        requested = List.of();
    }

    public static void handle(WorldGenNetwork.StructureThumbnailPacket packet) {
        KineticTexture texture = packet.png().length == 0 ? null
                : KineticRuntimeTextures.upload("realmcontrol", "structure_thumbnail/" + nextTexture++, packet.png());
        Entry previous = ENTRIES.put(packet.structureId(), new Entry(texture));
        if (previous != null && previous.texture() != null) KineticRuntimeTextures.release(previous.texture());
        for (Iterator<Entry> it = ENTRIES.values().iterator(); ENTRIES.size() > CACHED && it.hasNext(); ) {
            Entry eldest = it.next();
            if (eldest.texture() != null) KineticRuntimeTextures.release(eldest.texture());
            it.remove();
        }
    }
}
