package dev.xyat.realmcontrol.worldgen.preview;

import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.kineticcore.api.world.structure.KineticSnapshotThumbnail;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small pictures of structures for the rows of the structure panel. A picture is drawn from the structure's preview
 * sample and kept in memory and as a PNG under {@code <game directory>/kineticcore/cache/structure_thumbnails/}, in
 * a folder named after the world seed, the enabled datapacks and the installed mods, so a change to any of them
 * starts afresh. Players ask for the rows they can see; samples for missing pictures queue behind interactive
 * previews, and a newer request from the same player drops the rows it no longer shows.
 */
public final class StructureThumbnailService {
    /** Picture size in pixels; rows show it at 28 GUI units. */
    public static final int SIZE = 96;
    /** Most rows one request may ask for. */
    public static final int MAX_REQUEST = 64;
    /** Raised whenever the pictures are drawn differently, so old cached files are not used. */
    private static final int FORMAT = 1;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Map<String, byte[]> MEMORY = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 512;
        }
    });
    /** Structures for which no layout was found this session; asked again only after a refresh. */
    private static final Set<String> NONE = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Set<String>> WANTED = new ConcurrentHashMap<>();
    @Nullable private static volatile String modsHash;

    private StructureThumbnailService() {
    }

    /** Sends a player the pictures of these structures, making the missing ones in the background. */
    public static void request(ServerPlayer player, List<String> structureIds) {
        if (!player.hasPermissions(2)) return;
        MinecraftServer server = player.getServer();
        UUID owner = player.getUUID();
        List<String> ids = structureIds.size() > MAX_REQUEST ? structureIds.subList(0, MAX_REQUEST) : structureIds;
        Set<String> wanted = ConcurrentHashMap.newKeySet();
        wanted.addAll(ids);
        WANTED.put(owner, wanted);
        if (ids.isEmpty()) return;
        Path folder = folder(server);
        CompletableFuture.runAsync(() -> {
            for (String id : ids) {
                byte[] png = known(folder, id);
                if (png != null || NONE.contains(id)) {
                    wanted.remove(id);
                    send(server, owner, id, png);
                } else {
                    StructurePreviewService.later(() -> make(server, owner, id, folder));
                }
            }
        });
    }

    /** Keeps the picture of a sample an interactive preview made, and sends it to the player who asked. */
    static void offer(MinecraftServer server, UUID owner, String structureId, KineticBlockSnapshot snapshot) {
        Path folder = folder(server);
        byte[] png = MEMORY.get(structureId);
        if (png == null) png = storeOrNull(folder, structureId, snapshot);
        Set<String> wanted = WANTED.get(owner);
        if (wanted != null) wanted.remove(structureId);
        send(server, owner, structureId, png);
    }

    /** Forgets every picture of the current world, datapacks and mods, in memory and on disk. */
    /** Forgets one structure's picture, in memory and on disk, after its files changed. */
    public static void forget(MinecraftServer server, String structureId) {
        MEMORY.remove(structureId);
        NONE.remove(structureId);
        Path file = file(folder(server), structureId);
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A file another process holds is replaced when the picture is made again.
        }
        StructurePreviewService.forget(structureId);
    }

    public static void clear(MinecraftServer server) {
        MEMORY.clear();
        NONE.clear();
        Path folder = folder(server);
        try (var files = Files.walk(folder)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A file another process holds stays; it is replaced when the picture is made again.
                }
            });
        } catch (IOException ignored) {
            // Nothing cached yet.
        }
    }

    /** Stops all pending work, when the server stops. */
    public static void stop() {
        WANTED.clear();
        MEMORY.clear();
        NONE.clear();
        modsHash = null;
    }

    private static void make(MinecraftServer server, UUID owner, String structureId, Path folder) {
        // The player's latest request decides; it repeats the rows still missing, so this check follows scrolling.
        if (!wanted(owner, structureId)) return;
        byte[] png = known(folder, structureId);
        if (png == null && !NONE.contains(structureId)) {
            KineticBlockSnapshot snapshot = StructurePreviewService.sampleForThumbnail(server, structureId,
                    () -> !wanted(owner, structureId));
            if (snapshot == null) {
                if (!wanted(owner, structureId)) return;
                NONE.add(structureId);
            } else {
                png = storeOrNull(folder, structureId, snapshot);
            }
        }
        Set<String> wanted = WANTED.get(owner);
        if (wanted != null) wanted.remove(structureId);
        send(server, owner, structureId, png);
    }

    private static boolean wanted(UUID owner, String structureId) {
        Set<String> wanted = WANTED.get(owner);
        return wanted != null && wanted.contains(structureId);
    }

    @Nullable
    private static byte[] known(Path folder, String structureId) {
        byte[] png = MEMORY.get(structureId);
        if (png != null) return png;
        Path file = file(folder, structureId);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            png = Files.readAllBytes(file);
            MEMORY.put(structureId, png);
            return png;
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    private static byte[] storeOrNull(Path folder, String structureId, KineticBlockSnapshot snapshot) {
        byte[] png;
        try {
            png = KineticSnapshotThumbnail.encodePng(KineticSnapshotThumbnail.render(snapshot, SIZE), SIZE, SIZE);
        } catch (RuntimeException e) {
            LOGGER.warn("Cannot draw the thumbnail of {}", structureId, e);
            NONE.add(structureId);
            return null;
        }
        MEMORY.put(structureId, png);
        Path file = file(folder, structureId);
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, png);
            } catch (IOException e) {
                LOGGER.debug("Cannot cache the thumbnail of {}", structureId, e);
            }
        }
        return png;
    }

    private static void send(MinecraftServer server, UUID owner, String structureId, @Nullable byte[] png) {
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player != null) {
                WorldGenNetwork.CHANNEL.sendToPlayer(player, new WorldGenNetwork.StructureThumbnailPacket(structureId,
                        png == null ? new byte[0] : png));
            }
        });
    }

    @Nullable
    private static Path file(Path folder, String structureId) {
        int colon = structureId.indexOf(':');
        if (colon <= 0 || !structureId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || structureId.contains("..")) return null;
        return folder.resolve(structureId.substring(0, colon)).resolve(structureId.substring(colon + 1) + ".png");
    }

    /** The cache folder for this world seed, these datapacks and these mods. */
    private static Path folder(MinecraftServer server) {
        List<String> parts = new ArrayList<>();
        parts.add("format " + FORMAT);
        parts.add("seed " + server.overworld().getSeed());
        parts.add("packs " + String.join(",", server.getPackRepository().getSelectedIds()));
        parts.add("mods " + mods());
        String key = hash(String.join("\n", parts));
        return KineticPlatform.gameDirectory().resolve("kineticcore").resolve("cache").resolve("structure_thumbnails")
                .resolve(key.length() > 16 ? key.substring(0, 16) : key);
    }

    private static String mods() {
        String hash = modsHash;
        if (hash != null) return hash;
        StringBuilder text = new StringBuilder();
        for (KineticPlatform.LoadedMod mod : KineticPlatform.loadedMods()) {
            text.append(mod.id());
            Path source = mod.sourcePath();
            if (source != null) {
                text.append(' ').append(source.getFileName());
                try {
                    if (Files.isRegularFile(source)) text.append(' ').append(Files.size(source));
                } catch (IOException ignored) {
                    // The name alone still tells most updates apart.
                }
            }
            text.append('\n');
        }
        hash = hash(text.toString());
        modsHash = hash;
        return hash;
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
