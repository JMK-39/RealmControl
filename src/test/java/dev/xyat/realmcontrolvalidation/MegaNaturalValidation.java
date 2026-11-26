
package dev.xyat.realmcontrolvalidation;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.resource.KineticResourceIds;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import dev.xyat.realmcontrol.structure.mega.MegaLayout;
import dev.xyat.realmcontrol.structure.mega.MegaStats;
import dev.xyat.realmcontrol.structure.mega.MegaStructure;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.project.Tile;
import dev.xyat.realmcontrol.structure.publish.Publisher;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Opt-in 4096-block publisher, noise world, cross-group, and fresh-JVM persistence validation. */
public final class MegaNaturalValidation {
    private static final Logger LOG = LoggerFactory.getLogger(MegaNaturalValidation.class);
    private static final String ID = "realmcontrol:validation_mega_4096";
    private static final boolean TALL = Boolean.getBoolean("realmcontrolmega.tall");
    private static final int SIZE = 4096, HEIGHT = TALL ? 128 : 4, TILE = MegaLayout.TILE, DENSE_MIN = 1664, DENSE_MAX = 2176;
    private static final int TOWER_MIN = 2048, TOWER_MAX = TOWER_MIN + 64;
    private static final int CUT_X = 2016, CUT_Z = 2016;
    private static final int WORLD_SEED = 20261103;
    private static final String WORLD = System.getProperty("realmcontrolmega.world", "kinetic-4096");
    private static final Path OUTPUT = Path.of(System.getProperty("realmcontrolmega.output", "mega-natural-validation"));
    private static final boolean RESTART = Boolean.getBoolean("realmcontrolmega.restart");
    private static final boolean FULL = Boolean.getBoolean("realmcontrolmega.full");
    private static final boolean LEGACY = Boolean.getBoolean("realmcontrolmega.legacy");
    private static final boolean FLIGHT = Boolean.getBoolean("realmcontrolmega.flight");
    private static final ArrayDeque<ChunkPos> PENDING = new ArrayDeque<>();
    private static final HashSet<ChunkPos> FORCED = new HashSet<>();
    private static final ArrayList<Long> TICKS = new ArrayList<>();
    private static final ArrayList<Long> TICK_INTERVALS = new ArrayList<>();
    private static final int FLIGHT_FROM = 1536, FLIGHT_END = 2560, FLIGHT_Z = 1936, FLIGHT_SPEED = 32;
    private static final HashSet<String> FLOWN_MARKERS = new HashSet<>();
    private static final HashSet<ChunkPos> FLOWN_STARTS = new HashSet<>();
    private static int flightDistance = -16;
    private static String flightBiome = "";
    private static long flightCandidateMillis;
    private static int phase, checks, finishedDelay, targetChunks;
    private static volatile boolean measuring;
    private static long tickStart, generationStart, lastProgress, locateMillis;
    private static CompletableFuture<Void> publication;
    private static CompletableFuture<Boolean> running;

    private record Sample(int x, int y, int z, Block block, String id) {
    }

    private record Placement(MegaStructure structure, ChunkPos anchor, Rotation rotation, int bottom) {
        BlockPos pos(int x, int y, int z) {
            return structure.layout.world(anchor, bottom, rotation, x, y, z);
        }
    }

    private static final List<Sample> MARKERS = List.of(
            new Sample(0, 0, 0, Blocks.REDSTONE_BLOCK, "northwest"),
            new Sample(4095, 0, 0, Blocks.GOLD_BLOCK, "northeast"),
            new Sample(0, 0, 4095, Blocks.EMERALD_BLOCK, "southwest"),
            new Sample(4095, 0, 4095, Blocks.DIAMOND_BLOCK, "southeast"),
            new Sample(1919, 1, 1932, Blocks.LAPIS_BLOCK, "group_x_left"),
            new Sample(1920, 1, 1932, Blocks.IRON_BLOCK, "group_x_right"),
            new Sample(1932, 1, 1919, Blocks.AMETHYST_BLOCK, "group_z_left"),
            new Sample(1932, 1, 1920, Blocks.OBSIDIAN, "group_z_right"),
            new Sample(1935, 1, 1936, Blocks.NETHERITE_BLOCK, "chunk_left"),
            new Sample(1936, 1, 1936, Blocks.CRYING_OBSIDIAN, "chunk_right"),
            new Sample(1935, 1, 1940, Blocks.OAK_FENCE, "fence_left"),
            new Sample(1936, 1, 1940, Blocks.OAK_FENCE, "fence_right"),
            new Sample(1940, 1, 1942, Blocks.CHEST, "chest"));
    private static final Sample FUTURE = new Sample(4095, 0, 2452, Blocks.QUARTZ_BLOCK, "edge_after_restart");
    private static final List<Sample> TOWER_MARKERS = List.of(
            new Sample(2063, 63, 2065, Blocks.BLUE_CONCRETE, "tower_mid_left"),
            new Sample(2064, 63, 2065, Blocks.RED_CONCRETE, "tower_mid_right"),
            new Sample(2079, HEIGHT - 1, 2080, Blocks.WHITE_CONCRETE, "tower_top_left"),
            new Sample(2080, HEIGHT - 1, 2080, Blocks.BLACK_CONCRETE, "tower_top_right"));
    private static final List<Sample> FLIGHT_MARKERS = List.of(
            new Sample(1919, 1, 1932, Blocks.LAPIS_BLOCK, "flown_group_left"),
            new Sample(1920, 1, 1932, Blocks.IRON_BLOCK, "flown_group_right"),
            new Sample(1935, 1, 1936, Blocks.NETHERITE_BLOCK, "flown_chunk_left"),
            new Sample(1936, 1, 1936, Blocks.CRYING_OBSIDIAN, "flown_chunk_right"),
            new Sample(2048, 0, 1936, Blocks.COPPER_BLOCK, "flown_center_floor"));
    private static Placement placement;
    private static long deadline;

    private MegaNaturalValidation() {
    }

    public static void install() {
        deadline = System.nanoTime() + 30L * 60 * 1_000_000_000L;
        KineticClientEvents.onTick(KineticClientEvents.TickPhase.END, MegaNaturalValidation::tick);
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.START, server -> {
            long now = System.nanoTime();
            if (measuring && tickStart >= generationStart) TICK_INTERVALS.add(now - tickStart);
            tickStart = now;
        });
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.END, server -> {
            if (measuring && tickStart != 0L) TICKS.add(System.nanoTime() - tickStart);
        });
    }

    private static void tick() {
        if (phase == 3) {
            if (++finishedDelay == 15) KineticClientRuntime.stopClient();
            return;
        }
        var mc = Minecraft.getInstance();
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("4096 validation timed out");
            if (phase == 1) {
                if (mc.level == null && mc.getSingleplayerServer() == null && mc.getOverlay() == null) {
                    phase = 2;
                    openWorld(mc);
                }
                return;
            }
            var server = mc.getSingleplayerServer();
            if (server == null || mc.level == null || mc.player == null || mc.getOverlay() != null) return;
            if (phase == 0 && !RESTART) {
                if (publication == null) {
                    publication = CompletableFuture.runAsync(() -> {
                        try {
                            prepare(server);
                        } catch (Exception error) {
                            throw new CompletionException(error);
                        }
                    });
                    return;
                }
                if (!publication.isDone()) return;
                publication.get();
                phase = 1;
                leaveWorld(mc);
                return;
            }
            if (running == null) {
                running = server.submit(() -> {
                    try {
                        return step(server);
                    } catch (Exception error) {
                        throw new CompletionException(error);
                    }
                });
                return;
            }
            if (!running.isDone()) return;
            boolean complete = running.get();
            running = null;
            if (complete) phase = 3;
        } catch (Throwable error) {
            measuring = false;
            phase = 3;
            LOG.error("REALM_MEGA_4096_FAIL", error);
        }
    }

    private static void openWorld(Minecraft mc) {
        //? if >=1.21 {
        /*mc.createWorldOpenFlows().openWorld(WORLD, () -> {});
        *///?} else {
        mc.createWorldOpenFlows().loadLevel(new net.minecraft.client.gui.screens.TitleScreen(), WORLD);
        //?}
    }

    private static void leaveWorld(Minecraft mc) {
        //? if >=26.1 {
        /*mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
        *///?} else if >=1.21 {
        /*mc.level.disconnect();
        mc.disconnect(new net.minecraft.client.gui.screens.TitleScreen());
        *///?} else {
        mc.level.disconnect();
        mc.clearLevel(new net.minecraft.client.gui.screens.TitleScreen());
        //?}
    }

    private static void prepare(MinecraftServer server) throws IOException {
        long started = System.nanoTime();
        Files.createDirectories(OUTPUT);
        var project = new StructureProject();
        project.id = ID;
        project.name = "4096 natural generation validation";
        project.size = new int[]{SIZE, HEIGHT, SIZE};
        project.tileSize = TILE;
        project.air = "none";
        project.generation.spacing = 640;
        project.generation.separation = project.megaSeparation();
        project.generation.frequency = 1;
        project.generation.salt = 428206520;
        project.generation.foundation = "fill_clear";
        project.generation.foundationBlock = "minecraft:stone";
        project.save.loot = false;
        if (TALL) {
            project.generation.height = "absolute";
            project.generation.offset = 64;
        }
        var scan = MegaDraftFixture.begin(project, LEGACY);
        var tiles = scan.resolve("tiles");
        Files.createDirectories(tiles);
        var cutouts = new Cutouts();
        cutouts.boxes.add(new Cutouts.Box("empty tile in dense area", CUT_X, 0, CUT_Z, CUT_X + TILE - 1, HEIGHT - 1, CUT_Z + TILE - 1, false));

        var emptyTiles = new BitSet(project.tilesX() * project.tilesZ());
        var emptyTileFile = OUTPUT.resolve("legacy-empty-tile.bin");
        if (LEGACY) new Tile(TILE, HEIGHT, TILE).write(emptyTileFile);
        var samples = allMarkers();
        int written = 0;
        for (int z = 0; z < SIZE; z += TILE) {
            for (int x = 0; x < SIZE; x += TILE) {
                var path = ProjectStore.tile(tiles, x / TILE, z / TILE);
                boolean dense = x >= DENSE_MIN && x < DENSE_MAX && z >= DENSE_MIN && z < DENSE_MAX;
                final int minX = x, minZ = z;
                var markers = samples.stream().filter(mark -> markerInTile(mark, minX, minZ)).toList();
                boolean future = markerInTile(FUTURE, x, z);
                if (!dense && markers.isEmpty() && !future) {
                    if (LEGACY) {
                        try {
                            Files.createLink(path, emptyTileFile);
                        } catch (UnsupportedOperationException | IOException failedLink) {
                            Files.copy(emptyTileFile, path);
                        }
                    } else {
                        emptyTiles.set(z / TILE * project.tilesX() + x / TILE);
                    }
                    continue;
                }
                var tile = new Tile(TILE, HEIGHT, TILE);
                if (dense) {
                    short floor = (short) tile.index("minecraft:copper_block");
                    for (int dz = 0; dz < TILE; dz++) for (int dx = 0; dx < TILE; dx++) tile.cells[tile.cell(dx, 0, dz)] = floor;
                }
                if (TALL && x >= TOWER_MIN && x < TOWER_MAX && z >= TOWER_MIN && z < TOWER_MAX) {
                    short wall = (short) tile.index("minecraft:stone_bricks");
                    java.util.Arrays.fill(tile.cells, TILE * TILE, tile.cells.length, wall);
                }
                for (Sample marker : markers) writeMarker(tile, marker, x, z);
                if (future) writeMarker(tile, FUTURE, x, z);
                tile.write(path);
                written++;
            }
        }
        MegaDraftFixture.finish(project, LEGACY, scan, cutouts, emptyTiles);
        long staged = System.nanoTime();
        var result = Publisher.publish(server, ID, progress -> {});
        require(result.success(), result.message().getString());
        JsonObject receipt = new JsonObject();
        receipt.addProperty("footprint", SIZE + " x " + HEIGHT + " x " + SIZE);
        receipt.addProperty("draft_tiles", project.tilesX() * project.tilesZ());
        receipt.addProperty("expected_templates", written - 1);
        receipt.addProperty("legacy_draft", LEGACY);
        receipt.addProperty("tall_dense", TALL);
        receipt.addProperty("tower_tiles", TALL ? 4 : 0);
        receipt.addProperty("fixture_staging_ms", (staged - started) / 1_000_000L);
        receipt.addProperty("publisher_ms", millisSince(staged));
        write("publish.json", receipt);
        LOG.info("REALM_MEGA_4096_PUBLISH {}", receipt);
    }

    private static boolean markerInTile(Sample marker, int x, int z) {
        return marker.x() >= x && marker.x() < x + TILE && marker.z() >= z && marker.z() < z + TILE;
    }

    private static List<Sample> allMarkers() {
        if (!TALL) return MARKERS;
        var markers = new ArrayList<>(MARKERS);
        markers.addAll(TOWER_MARKERS);
        return markers;
    }

    private static void writeMarker(Tile tile, Sample marker, int x, int z) {
        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(marker.block());
        int index = tile.cell(marker.x() - x, marker.y(), marker.z() - z);
        String state = switch (marker.id()) {
            case "fence_left" -> id + "[east=true]";
            case "fence_right" -> id + "[west=true]";
            default -> id.toString();
        };
        tile.cells[index] = (short) tile.index(state);
        if (marker.block() == Blocks.CHEST) {
            var data = new CompoundTag();
            data.putString("id", "minecraft:chest");
            var items = new ListTag();
            var stack = new CompoundTag();
            stack.putByte("Slot", (byte) 0);
            stack.putString("id", "minecraft:emerald");
            //? if >=1.21 {
            /*stack.putInt("count", 7);
            *///?} else {
            stack.putByte("Count", (byte) 7);
            //?}
            items.add(stack);
            data.put("Items", items);
            tile.blockEntities.put(index, data);
        }
    }

    private static boolean step(MinecraftServer server) throws Exception {
        var level = server.overworld();
        require(level.getSeed() == WORLD_SEED, "expected fixed seed in isolated world");
        require(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "expected normal noise terrain");
        if (placement == null) {
            var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
            Holder<Structure> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE,
                    KineticResourceIds.of("realmcontrol", "validation_mega_4096"))).orElseThrow();
            require(holder.value() instanceof MegaStructure, "published custom structure is missing from registry");
            var structure = (MegaStructure) holder.value();
            require(structure.layout.sizeX == SIZE && structure.layout.sizeY == HEIGHT && structure.layout.sizeZ == SIZE,
                    "structure registry truncated the volume");
            if (RESTART) return restart(level, structure);
            long locating = System.nanoTime();
            var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder),
                    new BlockPos(-12000, 64, 9000), 6, false);
            locateMillis = millisSince(locating);
            require(found != null, "native locate could not find the published 4096 structure");
            var anchor = chunkAt(found.getFirst());
            var rotation = MegaLayout.rotation(level.getSeed(), MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor));
            int base = structure.bottom(level.getChunkSource().getGenerator(), level, level.getChunkSource().randomState(), anchor, rotation);
            placement = new Placement(structure, anchor, rotation, base);
            if (FLIGHT) {
                planFlight(server, level, placement);
                return false;
            }
            enqueue(placement);
            targetChunks = PENDING.size();
            TICKS.clear();
            MegaStats.reset();
            measuring = true;
            generationStart = System.nanoTime();
            lastProgress = generationStart;
            LOG.info("REALM_MEGA_4096_QUEUE anchor={} rotation={} bottom={} targets={} full={} locate_ms={}",
                    anchor, rotation, base, targetChunks, FULL, locateMillis);
            return false;
        }
        if (FLIGHT) return fly(server, level);
        if (pump(level)) {
            long now = System.nanoTime();
            if (now - lastProgress >= 10_000_000_000L) {
                lastProgress = now;
                LOG.info("REALM_MEGA_4096_PROGRESS finished={} targets={} queued={} forced={} loaded={}",
                        targetChunks - PENDING.size() - FORCED.size(), targetChunks, PENDING.size(), FORCED.size(),
                        level.getChunkSource().getLoadedChunksCount());
            }
            return false;
        }
        measuring = false;
        long generated = millisSince(generationStart);
        JsonObject receipt = verify(level, placement);
        receipt.addProperty("queue_ms", generated);
        receipt.addProperty("locate_ms", locateMillis);
        receipt.addProperty("target_chunks", targetChunks);
        receipt.addProperty("full_footprint_queued", FULL);
        writeMetrics(receipt);
        var future = placement.pos(FUTURE.x(), FUTURE.y(), FUTURE.z());
        receipt.addProperty("future_chunk_x", future.getX() >> 4);
        receipt.addProperty("future_chunk_z", future.getZ() >> 4);
        write("initial.json", receipt);
        LOG.info("REALM_MEGA_4096_GENERATION_PASS {}", receipt);
        return true;
    }

    private static void writeMetrics(JsonObject receipt) {
        receipt.addProperty("tile_parts", MegaStats.parts());
        receipt.addProperty("tile_part_mean_ms", MegaStats.parts() == 0L ? 0 : MegaStats.nanos() / 1e6 / MegaStats.parts());
        receipt.addProperty("tile_part_max_ms", MegaStats.slowestNanos() / 1e6);
        TICKS.sort(Long::compareTo);
        if (!TICKS.isEmpty()) {
            receipt.addProperty("ticks_sampled", TICKS.size());
            receipt.addProperty("mspt_mean", TICKS.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e6);
            receipt.addProperty("mspt_p95", TICKS.get((int) (TICKS.size() * .95)) / 1e6);
            receipt.addProperty("mspt_max", TICKS.get(TICKS.size() - 1) / 1e6);
        }
        TICK_INTERVALS.sort(Long::compareTo);
        if (!TICK_INTERVALS.isEmpty()) {
            receipt.addProperty("tick_intervals_sampled", TICK_INTERVALS.size());
            receipt.addProperty("tick_interval_p95_ms", TICK_INTERVALS.get((int) (TICK_INTERVALS.size() * .95)) / 1e6);
            receipt.addProperty("tick_interval_max_ms", TICK_INTERVALS.get(TICK_INTERVALS.size() - 1) / 1e6);
            receipt.addProperty("tick_intervals_over_1s", TICK_INTERVALS.stream().filter(ns -> ns > 1_000_000_000L).count());
        }
    }

    /** Plans a second, cold copy without reading target chunks. Only normal player tracking may load the flight. */
    private static void planFlight(MinecraftServer server, ServerLevel level, Placement first) {
        var rule = first.structure().placement(server.registryAccess());
        require(rule != null, "the flight needs the published random spread");
        int spacing = rule.spacing();
        int regionX = Math.floorDiv(MegaLayout.chunkX(first.anchor()), spacing) + 8;
        int regionZ = Math.floorDiv(MegaLayout.chunkZ(first.anchor()), spacing) - 8;
        long candidateSearch = System.nanoTime();
        placement = findColdBiome(level, first.structure(), rule, regionX, regionZ);
        flightCandidateMillis = millisSince(candidateSearch);
        require(placement != null, "no cold flight anchor in a biome allowed by the published structure");
        var world = server.getWorldPath(LevelResource.ROOT);
        var region = world.resolve("region");
        //? if >=26.1 {
        /*region = world.resolve("dimensions/minecraft/overworld/region");
        *///?}
        for (var sample : FLIGHT_MARKERS) {
            var at = placement.pos(sample.x(), sample.y(), sample.z());
            int cx = at.getX() >> 4, cz = at.getZ() >> 4;
            require(!level.getChunkSource().hasChunk(cx, cz), "flight sample was previously loaded at " + at);
            require(!Files.exists(region.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca")),
                    "flight region existed before moving the player at " + at);
        }
        server.getPlayerList().getPlayers().get(0).setGameMode(GameType.SPECTATOR);
        MegaStats.reset();
        generationStart = System.nanoTime();
        measuring = true;
        LOG.info("REALM_MEGA_4096_FLIGHT_START first={} second={} turn={} floor={} target_speed={}",
                first.anchor(), placement.anchor(), placement.rotation(), placement.bottom(), FLIGHT_SPEED);
    }

    private static Placement findColdBiome(ServerLevel level, MegaStructure structure,
                                          dev.xyat.realmcontrol.structure.mega.MegaPlacement rule, int rx, int rz) {
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        for (int ring = 0; ring <= 6; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var anchor = rule.getPotentialStructureChunk(level.getSeed(), (rx + dx) * rule.spacing(), (rz + dz) * rule.spacing());
                    var rotation = MegaLayout.rotation(level.getSeed(), MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor));
                    int floor = structure.bottom(generator, level, random, anchor, rotation);
                    var biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(anchor.getMinBlockX()),
                            QuartPos.fromBlock(floor), QuartPos.fromBlock(anchor.getMinBlockZ()), random.sampler());
                    if (!structure.biomes().contains(biome)) continue;
                    flightBiome = biome.unwrapKey().map(key -> key.location().toString()).orElse("");
                    return new Placement(structure, anchor, rotation, floor);
                }
            }
        }
        return null;
    }

    private static boolean fly(MinecraftServer server, ServerLevel level) throws Exception {
        int route = FLIGHT_END - FLIGHT_FROM;
        long elapsed = millisSince(generationStart);
        int distance = flightDistance < 0 ? 0 : (int) Math.min(route, elapsed * FLIGHT_SPEED / 1000);
        if (flightDistance < route && (distance >= flightDistance + 16 || distance == route)) {
            var at = placement.pos(FLIGHT_FROM + distance, HEIGHT + 80, FLIGHT_Z);
            server.getCommands().getDispatcher().execute("tp @p " + at.getX() + " " + at.getY() + " " + at.getZ(),
                    server.createCommandSourceStack().withSuppressedOutput());
            flightDistance = distance;
        }
        for (var sample : FLIGHT_MARKERS) {
            var at = placement.pos(sample.x(), sample.y(), sample.z());
            if (!FLOWN_MARKERS.contains(sample.id())) {
                var chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
                if (chunk != null) {
                    require(chunk.getBlockState(at).is(sample.block()), "the player-loaded flight missed " + sample.id() + " at " + at);
                    FLOWN_MARKERS.add(sample.id());
                }
            }
            var group = groupFor(placement, sample);
            if (!FLOWN_STARTS.contains(group)) {
                var chunk = level.getChunkSource().getChunkNow(MegaLayout.chunkX(group), MegaLayout.chunkZ(group));
                if (chunk != null) {
                    var start = chunk.getStartForStructure(placement.structure());
                    require(start != null && start.isValid() && !start.getPieces().isEmpty(),
                            "the player-loaded flight missed a real group start: " + group);
                    FLOWN_STARTS.add(group);
                }
            }
        }
        if (flightDistance < route) return false;
        measuring = false;
        require(FLOWN_MARKERS.size() == FLIGHT_MARKERS.size(), "not all flight landmarks loaded: " + FLOWN_MARKERS);
        require(FLOWN_STARTS.size() >= 2, "the flight did not observe both sides of the group seam");
        var receipt = new JsonObject();
        receipt.addProperty("seed", level.getSeed());
        receipt.addProperty("anchor_x", MegaLayout.chunkX(placement.anchor()));
        receipt.addProperty("anchor_z", MegaLayout.chunkZ(placement.anchor()));
        receipt.addProperty("rot", placement.rotation().name());
        receipt.addProperty("base", placement.bottom());
        receipt.addProperty("locate_ms", locateMillis);
        receipt.addProperty("cold_candidate_ms", flightCandidateMillis);
        receipt.addProperty("anchor_biome", flightBiome);
        receipt.addProperty("flight_ms", elapsed);
        receipt.addProperty("flight_distance", route);
        receipt.addProperty("target_blocks_per_second", FLIGHT_SPEED);
        receipt.addProperty("view_distance", server.getPlayerList().getViewDistance());
        receipt.addProperty("forced_target_chunks", 0);
        receipt.addProperty("player_loaded_markers", FLOWN_MARKERS.size());
        receipt.addProperty("player_loaded_starts", FLOWN_STARTS.size());
        writeMetrics(receipt);
        write("flight.json", receipt);
        LOG.info("REALM_MEGA_4096_FLIGHT_PASS {}", receipt);
        return true;
    }

    private static void enqueue(Placement p) {
        Collection<ChunkPos> chunks = new LinkedHashSet<>();
        int min = FULL ? 0 : DENSE_MIN, max = FULL ? SIZE - 1 : DENSE_MAX - 1;
        var a = p.pos(min, 0, min);
        var b = p.pos(max, 0, max);
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++) {
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        for (var sample : allMarkers()) chunks.add(chunkAt(p.pos(sample.x(), sample.y(), sample.z())));
        PENDING.addAll(chunks);
    }

    private static ChunkPos chunkAt(BlockPos at) {
        return new ChunkPos(at.getX() >> 4, at.getZ() >> 4);
    }

    private static boolean pump(ServerLevel level) {
        FORCED.removeIf(pos -> {
            int x = MegaLayout.chunkX(pos), z = MegaLayout.chunkZ(pos);
            if (!level.getChunkSource().hasChunk(x, z)) return false;
            level.setChunkForced(x, z, false);
            return true;
        });
        while (FORCED.size() < 24 && !PENDING.isEmpty() && level.getChunkSource().getLoadedChunksCount() < 5000) {
            ChunkPos pos = PENDING.removeFirst();
            int x = MegaLayout.chunkX(pos), z = MegaLayout.chunkZ(pos);
            try {
                level.setChunkForced(x, z, true);
            } catch (IllegalStateException unloading) {
                level.setChunkForced(x, z, false);
                PENDING.addLast(pos);
                break;
            }
            FORCED.add(pos);
        }
        return !PENDING.isEmpty() || !FORCED.isEmpty();
    }

    private static JsonObject verify(ServerLevel level, Placement p) {
        var groupChunks = new HashSet<ChunkPos>();
        for (Sample marker : allMarkers()) {
            var at = p.pos(marker.x(), marker.y(), marker.z());
            var state = level.getBlockState(at);
            require(state.is(marker.block()), "missing " + marker.id() + " at " + at + ": " + state);
            if (marker.block() == Blocks.OAK_FENCE) {
                var property = switch (p.rotation().rotate(marker.id().endsWith("left") ? Direction.EAST : Direction.WEST)) {
                    case NORTH -> BlockStateProperties.NORTH;
                    case EAST -> BlockStateProperties.EAST;
                    case SOUTH -> BlockStateProperties.SOUTH;
                    case WEST -> BlockStateProperties.WEST;
                    default -> throw new IllegalStateException("unexpected fence direction");
                };
                require(state.getValue(property), "fence connection was lost across the chunk seam at " + at);
            } else if (marker.block() == Blocks.CHEST) {
                require(level.getBlockEntity(at) instanceof Container chest && chest.getItem(0).is(Items.EMERALD)
                        && chest.getItem(0).getCount() == 7, "published chest contents were lost at " + at);
            }
            groupChunks.add(groupFor(p, marker));
        }
        for (ChunkPos group : groupChunks) verifyStart(level, p.structure(), group);
        verifyStart(level, p.structure(), p.anchor());
        int denseSamples = 0;
        for (int z = DENSE_MIN + 5; z < DENSE_MAX; z += 23) {
            for (int x = DENSE_MIN + 7; x < DENSE_MAX; x += 23) {
                boolean cut = x >= CUT_X && x < CUT_X + TILE && z >= CUT_Z && z < CUT_Z + TILE;
                var at = p.pos(x, 0, z);
                require(level.getBlockState(at).is(Blocks.COPPER_BLOCK) != cut, "dense or cut-out column mismatch at " + at);
                denseSamples++;
            }
        }
        int outside = 0;
        for (int[] coord : List.of(new int[]{-1,-1}, new int[]{SIZE,-1}, new int[]{-1,SIZE}, new int[]{SIZE,SIZE})) {
            var at = p.pos(coord[0], 0, coord[1]);
            require(MARKERS.stream().noneMatch(marker -> level.getBlockState(at).is(marker.block())), "corner overran footprint");
            outside++;
        }
        if (TALL) {
            for (int y : new int[]{1, 31, 64, 95, HEIGHT - 2}) {
                for (int[] edge : List.of(new int[]{TOWER_MIN, TOWER_MIN}, new int[]{TOWER_MAX - 1, TOWER_MIN},
                        new int[]{TOWER_MIN, TOWER_MAX - 1}, new int[]{TOWER_MAX - 1, TOWER_MAX - 1})) {
                    var at = p.pos(edge[0], y, edge[1]);
                    require(level.getBlockState(at).is(Blocks.STONE_BRICKS), "tower interior was not generated at " + at);
                }
            }
            var above = p.pos(TOWER_MIN + 3, HEIGHT, TOWER_MIN + 3);
            require(!level.getBlockState(above).is(Blocks.STONE_BRICKS), "tower extends above the published volume");
        }
        var receipt = new JsonObject();
        receipt.addProperty("seed", level.getSeed());
        receipt.addProperty("generator", level.getChunkSource().getGenerator().getClass().getSimpleName());
        receipt.addProperty("anchor_x", MegaLayout.chunkX(p.anchor()));
        receipt.addProperty("anchor_z", MegaLayout.chunkZ(p.anchor()));
        receipt.addProperty("rot", p.rotation().name());
        receipt.addProperty("base", p.bottom());
        receipt.addProperty("marker_blocks", allMarkers().size());
        receipt.addProperty("tower_samples", TALL ? TOWER_MARKERS.size() + 21 : 0);
        receipt.addProperty("observed_group_starts", groupChunks.size());
        receipt.addProperty("dense_and_cutout_samples", denseSamples);
        receipt.addProperty("outside_corners", outside);
        receipt.addProperty("assertions", checks);
        return receipt;
    }

    private static void verifyStart(ServerLevel level, MegaStructure structure, ChunkPos pos) {
        var chunk = level.getChunk(MegaLayout.chunkX(pos), MegaLayout.chunkZ(pos));
        var start = chunk.getStartForStructure(structure);
        require(start != null && start.isValid() && !start.getPieces().isEmpty(), "missing structure start at " + pos);
    }

    private static ChunkPos groupFor(Placement p, Sample marker) {
        var layout = p.structure().layout;
        var offset = MegaLayout.groupOffset(p.rotation(), layout.groupX(marker.x() / TILE), layout.groupZ(marker.z() / TILE));
        return new ChunkPos(MegaLayout.chunkX(p.anchor()) + offset.getX(), MegaLayout.chunkZ(p.anchor()) + offset.getZ());
    }

    private static boolean restart(ServerLevel level, MegaStructure structure) throws IOException {
        var prior = JsonParser.parseString(Files.readString(OUTPUT.resolve("initial.json"))).getAsJsonObject();
        var anchor = new ChunkPos(prior.get("anchor_x").getAsInt(), prior.get("anchor_z").getAsInt());
        var rotation = Rotation.valueOf(prior.get("rot").getAsString());
        var p = new Placement(structure, anchor, rotation, prior.get("base").getAsInt());
        var receipt = verify(level, p);
        var at = p.pos(FUTURE.x(), FUTURE.y(), FUTURE.z());
        long began = System.nanoTime();
        level.getChunk(at.getX() >> 4, at.getZ() >> 4);
        require(level.getBlockState(at).is(FUTURE.block()), "new chunk after restart did not generate its sentinel");
        var offset = MegaLayout.groupOffset(p.rotation(), structure.layout.groupX(FUTURE.x() / TILE), structure.layout.groupZ(FUTURE.z() / TILE));
        verifyStart(level, structure, new ChunkPos(MegaLayout.chunkX(anchor) + offset.getX(), MegaLayout.chunkZ(anchor) + offset.getZ()));
        receipt.addProperty("after_restart_block", FUTURE.id());
        receipt.addProperty("edge_elapsed_ms", millisSince(began));
        write("restart.json", receipt);
        LOG.info("REALM_MEGA_4096_RESTART_PASS {}", receipt);
        return true;
    }

    private static void write(String name, JsonObject evidence) throws IOException {
        Files.writeString(OUTPUT.resolve(name), new GsonBuilder().setPrettyPrinting().create().toJson(evidence));
    }

    private static long millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
        checks++;
    }
}
