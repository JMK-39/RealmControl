package dev.xyat.realmcontrolvalidation;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.runtime.KineticPlatform;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.client.ControllerClient;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.client.gui.BiomePickerPage;
import dev.xyat.realmcontrol.structure.client.gui.CutoutPage;
import dev.xyat.realmcontrol.structure.client.gui.GenerationPage;
import dev.xyat.realmcontrol.structure.client.gui.LootTablePickerPage;
import dev.xyat.realmcontrol.structure.client.gui.MarkersPage;
import dev.xyat.realmcontrol.structure.client.gui.NewLootTablePage;
import dev.xyat.realmcontrol.structure.client.gui.NewProjectPage;
import dev.xyat.realmcontrol.structure.client.gui.ProjectSettingsPage;
import dev.xyat.realmcontrol.structure.client.gui.PublishPage;
import dev.xyat.realmcontrol.structure.client.gui.SpawnerPage;
import dev.xyat.realmcontrol.structure.client.gui.StructureEditorPage;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import dev.xyat.realmcontrol.structure.mega.MegaLayout;
import dev.xyat.realmcontrol.structure.mega.MegaStats;
import dev.xyat.realmcontrol.structure.mega.MegaStructure;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.Markers;
import dev.xyat.realmcontrol.structure.project.ProjectPreview;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.project.Tile;
import dev.xyat.realmcontrol.structure.publish.LootTables;
import dev.xyat.realmcontrol.structure.publish.Publisher;
import dev.xyat.realmcontrol.structure.publish.TestPlacement;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.structure.selection.SelectionStore;
import dev.xyat.realmcontrol.worldgen.client.gui.WorldGenPage;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end check of the structure editor, in one game. In the superflat test world it builds a small courtyard
 * house (rooms around a courtyard with a well, a chest with diamonds, a chest with gold, a zombie spawner, an armor
 * stand and an iron golem), selects it with the courtyard cut out, scans it, adds back the well, gives the diamond
 * chest a new loot table made from its items (plus an entry of a mod that is not installed), makes the spawner spawn
 * skeletons and leaves iron golems out. It checks that an unknown table stops the publish, publishes as a ZIP, into
 * the world's datapacks folder and into the KineticCore pack, and checks the template through vanilla: loaded by the
 * template manager and placed elsewhere, walls match, the courtyard and open-sky air keep what was there, indoor air
 * is carved, the chest names its table, the spawner spawns skeletons, the armor stand has a new UUID and the golem is
 * gone. With ContentStudio installed it saves the table through ContentStudio's file editing. It then captures the
 * editor's pages in Chinese at 1920×1080 (and, before them, test places the house on open ground through the editor's
 * request, compares every cell with the draft and with what was there, then undoes it and checks the ground is back,
 * with no item dropped and the placed entities gone), publishes again with Publish and Rejoin (the world is left and
 * opened again, and then holds the structure), leaves the world, opens a normally generated test world (where the
 * published data pack now loads), locates the structure, lets it generate and checks its walls, its natural
 * courtyard, its well, its biome, the chest's loot (the table loads without the missing mod's entry), the spawner
 * and, once the player stands there, its entities.
 * <p>
 * Beside it a 200×200 medium structure is built (a patterned floor, walls, a fence line and a pane line across tile
 * seams, a bed and a hut with a door straddling seams, a chest on a seam, a cut-out courtyard and a corner tile cut
 * out whole), scanned and published as vanilla jigsaw tiles: the cut-out tile is left out, files of an earlier publish
 * are removed, every file uses vanilla types only, and changing its spacing the way the structure panel does
 * rewrites its structure set. In the generated world the new spacing is in effect, and two generated copies (one
 * turned a quarter, so the tiles' axes swap) match the draft block for block where no other structure overlaps
 * them, with nothing of the cut-outs.
 * <p>
 * Further on a 1024×1024 mega structure is built (floor, walls, fence and pane lines across every seam, huts and a
 * chest across group seams, a 160×160 cut-out courtyard and a corner tile cut out whole), scanned and published:
 * a too small separation and another target than the KineticCore pack are refused, the tiles cut out whole are
 * listed absent, and a structure file of a type nothing registers, put into the pack beside it, is left out so the world
 * still loads. In the generated world /locate finds it; two copies (one turned a quarter) generate whole and match
 * the draft block for block, no other structure starts inside them, the cut-out keeps the natural ground, the
 * ground above is cleared and the foundation filled. The flight across the second copy, while its chunks generate,
 * reports the server's tick times and the time spent placing tiles. Last, the
 * structure panel shows both structures as custom and opens the medium one's generation page. Its files are removed
 * at the end.
 */
public final class StructureEditorValidation {
    private static final Logger LOG = LoggerFactory.getLogger(StructureEditorValidation.class);
    private static final String ROOT = System.getProperty("realmcontrolstructure.guiValidation.output", "D:/IDEAWork/RealmControl/.gradle/structure-validation/");
    private static final String GEN_WORLD = System.getProperty("realmcontrolstructure.genWorld", "kinetic-gen");
    /** Where a copy of the published medium structure goes, as a data pack of its own, for a run on a vanilla server. */
    private static final String EXPORT_PACK = System.getProperty("realmcontrolstructure.exportPack", "");
    private static final String ID = "realmcontrol:validation_courtyard";
    private static final int SIZE = 33, HEIGHT = 7;
    // The copy sits behind the house, within the player's view distance: entities in chunks that are only loaded
    // for their blocks are hidden from entity queries.
    private static final BlockPos ORIGIN = new BlockPos(1000, -63, 1000), COPY = new BlockPos(1000, -63, 1040);
    // A new id every run: a table file an interrupted run left behind is loaded when the world opens, and a loaded
    // table cannot be created again.
    private static final String TABLE_PATH = "chests/validation_courtyard/loot_" + Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);
    private static final String TABLE = "realmcontrol:" + TABLE_PATH, GHOST = "missingmod:ghost_item";
    private static final List<String> PAGES = pages();
    /** Where the test placement goes: open ground beside the copy, the player standing in the middle. */
    private static final int TEST_X = 1016, TEST_Z = 1120;
    private static BlockPos testMin;
    private static BlockState[] testBefore;
    private static MinecraftServer rejoinServer;
    private static String rejoinWorld;
    private static final String MEDIUM = "realmcontrol:validation_medium";
    private static final int MEDIUM_SIZE = 200, MEDIUM_HEIGHT = 6, MEDIUM_SPACING = 26;
    private static final BlockPos MEDIUM_ORIGIN = new BlockPos(1100, -63, 760);
    private static List<StructureStart> mediumStarts = List.of();
    private static List<ChunkPos> mediumChunks = List.of();
    private static int mediumChunk;
    private static final String MEGA = "realmcontrol:validation_mega";
    private static final int MEGA_SIZE = 1024, MEGA_HEIGHT = 6, MEGA_SEPARATION = 66, FORCED = 32, MAX_LOADED = 6000;
    // Far apart, since other structures keep out of mega footprints: the courtyard and medium copies need room.
    private static final int MEGA_SPACING = 160;
    private static final BlockPos MEGA_ORIGIN = new BlockPos(2000, -63, -600);
    private static final Block[] MEGA_FLOOR = {Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS};
    /** A mega structure instance: its anchor chunk, its turn and its bottom. */
    private record MegaInstance(ChunkPos anchor, Rotation rotation, int bottom) {
    }
    private static MegaInstance megaA, megaB;
    private static int megaBuilt, flightStep, flightX, flightZ, flightY, generatedChunks;
    private static long phaseStarted, lastProgress;
    private static final ArrayDeque<ChunkPos> pendingChunks = new ArrayDeque<>();
    private static final List<ChunkPos> forcedChunks = new ArrayList<>();
    private static volatile boolean measuring;
    private static volatile long tickStarted;
    private static final List<Long> tickNanos = Collections.synchronizedList(new ArrayList<>());
    private static boolean installed, finished;
    private static int step, page = -1, checks, captures, failures, wallBlocks, sourceBlocks;
    private static UUID sourceStand;
    private static BoundingBox generated;
    private static long due, deadline;
    private static String originalLanguage;
    private static int originalWidth, originalHeight;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static final StringBuilder details = new StringBuilder();

    private StructureEditorValidation() {
    }

    private static List<String> pages() {
        List<String> pages = new ArrayList<>(List.of("editor", "new", "settings", "cutouts", "generation", "biomes", "markers", "loot_picker",
                "new_table", "spawner", "markers_entities", "save", "save_medium", "save_mega"));
        if (KineticPlatform.isModLoaded("contentstudio")) pages.add("contentstudio");
        return pages;
    }

    public static void install() {
        if (installed) return;
        installed = true;
        KineticClientEvents.onTick(KineticClientEvents.TickPhase.END, StructureEditorValidation::tick);
        // How long each server tick takes while the flight across the mega structure is measured.
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.START, server -> tickStarted = System.nanoTime());
        KineticServerEvents.onTick(KineticEventPriority.NORMAL, KineticServerEvents.TickPhase.END, server -> {
            if (measuring && tickStarted != 0L) tickNanos.add(System.nanoTime() - tickStarted);
        });
    }

    private static void tick() {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        try {
            switch (step) {
                case 0 -> {
                    if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) return;
                    // A player who died in an earlier run (health carries over) loads no chunks until respawned.
                    if (mc.player.isDeadOrDying()) {
                        mc.player.respawn();
                        mc.setScreen(null);
                        return;
                    }
                    originalLanguage = mc.getLanguageManager().getSelected();
                    originalWidth = mc.getWindow().getWidth();
                    originalHeight = mc.getWindow().getHeight();
                    onServer(() -> {
                        MinecraftServer server = mc.getSingleplayerServer();
                        removeFiles();
                        // Creative: the views from above must not end in a fall.
                        command(server, "gamemode creative " + mc.player.getGameProfile().getName());
                        // Corners are only taken within the controller's reach of the player.
                        command(server, "tp " + mc.player.getGameProfile().getName() + " " + (ORIGIN.getX() + 16) + " " + (ORIGIN.getY() + 8) + " " + (ORIGIN.getZ() - 14));
                        return null;
                    });
                    deadline = now + 60_000L;
                    step = 10;
                }
                case 10 -> {
                    // Entities load after the chunks; earlier runs' armor stands must be there to be cleared.
                    if (!onServer(StructureEditorValidation::entitiesLoaded)) {
                        require(now < deadline, "the test area's entities did not load within a minute: " + onServer(StructureEditorValidation::unloadedEntityChunks));
                        return;
                    }
                    onServer(StructureEditorValidation::setUp);
                    megaBuilt = 0;
                    deadline = now + 300_000L;
                    step = 11;
                    LOG.info("REALM_STRUCTURE_STEP built and scanning; building the mega structure");
                }
                case 11 -> {
                    // 64×64 blocks of the mega structure a tick, so the chunks it touched can unload again.
                    if (onServer(StructureEditorValidation::buildMegaPart)) {
                        require(now < deadline, "building the mega structure took more than 5 minutes");
                        return;
                    }
                    onServer(StructureEditorValidation::scanMega);
                    deadline = now + 900_000L;
                    step = 1;
                    LOG.info("REALM_STRUCTURE_STEP mega structure built and scanning");
                }
                case 1 -> {
                    boolean done = onServer(() -> !ScanService.busy(ID) && ProjectStore.exists(ID) && !ScanService.busy(MEDIUM) && ProjectStore.exists(MEDIUM)
                            && !ScanService.busy(MEGA) && ProjectStore.exists(MEGA));
                    if (!done) {
                        require(now < deadline, "the scans did not finish within 15 minutes");
                        return;
                    }
                    onServer(StructureEditorValidation::checkAndPublish);
                    step = 2;
                    LOG.info("REALM_STRUCTURE_STEP published; checks so far {}", checks);
                    setScreenSize(mc, "zh_cn");
                }
                case 2 -> {
                    if (reload != null && (!reload.isDone() || mc.getOverlay() != null)) return;
                    reload = null;
                    // The controller in hand in front of the house, with its region, cut-out box and status line.
                    onServer(() -> {
                        MinecraftServer server = mc.getSingleplayerServer();
                        String name = mc.player.getGameProfile().getName();
                        // Flying, so the view stays where it is put instead of falling to the floor.
                        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                        player.getAbilities().flying = true;
                        player.onUpdateAbilities();
                        command(server, "tp " + name + " " + (ORIGIN.getX() + 16) + " " + (ORIGIN.getY() + 18) + " " + (ORIGIN.getZ() - 22) + " 0 32");
                        command(server, "item replace entity " + name + " weapon.mainhand with " + StructureEditorModule.MODID + ":structure_controller");
                        return null;
                    });
                    mc.setScreen(null);
                    due = now + 4000L;
                    step = 3;
                }
                case 3 -> {
                    if (now < due) return;
                    mc.gui.getChat().clearMessages(false);
                    capture("hud");
                    // The container mode looking straight down on the house: the containers' boxes show through the roof, with their counts in the status line.
                    onServer(() -> {
                        MinecraftServer server = mc.getSingleplayerServer();
                        command(server, "tp " + mc.player.getGameProfile().getName() + " " + (ORIGIN.getX() + 16.5D) + " " + (ORIGIN.getY() + 26) + " " + (ORIGIN.getZ() + 16.5D) + " 0 90");
                        return null;
                    });
                    ControllerClient.setMode(ControllerClient.Mode.CONTAINER);
                    due = now + 3000L;
                    step = 30;
                }
                case 30 -> {
                    if (now < due) return;
                    mc.gui.getChat().clearMessages(false);
                    capture("hud_container");
                    ControllerClient.setMode(ControllerClient.Mode.SELECT);
                    // Test placement: the player flies to open ground and stands on it.
                    onServer(() -> {
                        MinecraftServer server = mc.getSingleplayerServer();
                        ServerLevel level = server.overworld();
                        level.getChunk(TEST_X >> 4, TEST_Z >> 4);
                        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, TEST_X, TEST_Z);
                        command(server, "tp " + mc.player.getGameProfile().getName() + " " + (TEST_X + 0.5D) + " " + ground + " " + (TEST_Z + 0.5D) + " 0 60");
                        checkTestRefusals();
                        return null;
                    });
                    StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_EDITOR, ID, "");
                    due = now + 5000L;
                    step = 40;
                }
                case 40 -> {
                    if (now < due) return;
                    openTestDialog();
                    due = now + 1500L;
                    step = 41;
                }
                case 41 -> {
                    if (now < due) return;
                    capture("test_dialog");
                    mc.setScreen(null);
                    onServer(StructureEditorValidation::recordTestArea);
                    // As the dialog's button does.
                    StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.TEST_PLACE, ID, "");
                    deadline = now + 180_000L;
                    step = 42;
                }
                case 42 -> {
                    if (!onServer(() -> TestPlacement.record(mc.getSingleplayerServer()) != null && !TestPlacement.busy(ID))) {
                        require(now < deadline, "the test placement did not finish within 3 minutes");
                        return;
                    }
                    onServer(StructureEditorValidation::checkTestPlacement);
                    StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_EDITOR, ID, "");
                    due = now + 5000L;
                    step = 43;
                }
                case 43 -> {
                    if (now < due) return;
                    capture("editor_test");
                    openTestDialog();
                    due = now + 1500L;
                    step = 44;
                }
                case 44 -> {
                    if (now < due) return;
                    capture("test_undo_dialog");
                    mc.setScreen(null);
                    StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.TEST_UNDO, ID, "");
                    deadline = now + 180_000L;
                    step = 45;
                }
                case 45 -> {
                    if (!onServer(() -> TestPlacement.record(mc.getSingleplayerServer()) == null && !TestPlacement.busy(ID))) {
                        require(now < deadline, "undoing the test placement did not finish within 3 minutes");
                        return;
                    }
                    onServer(StructureEditorValidation::checkTestUndo);
                    step = 4;
                    page = -1;
                    due = now;
                }
                case 4 -> {
                    if (now < due) return;
                    if (page >= 0) capture(PAGES.get(page));
                    page++;
                    if (page >= PAGES.size()) {
                        mc.setScreen(null);
                        // Publish and Rejoin, as the save options page does: the world is left and opened again.
                        rejoinServer = mc.getSingleplayerServer();
                        rejoinWorld = rejoinServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
                        boolean registered = onServer(() -> registered(rejoinServer));
                        LOG.info("REALM_STRUCTURE_STEP publishing and re-entering {}; structure registered before: {}", rejoinWorld, registered);
                        EditorClient.rejoinAfterPublish(ID);
                        StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.PUBLISH, ID, "");
                        deadline = now + 600_000L;
                        step = 46;
                        return;
                    }
                    openPage(page);
                    due = now + (page == 0 || page == 3 || PAGES.get(page).equals("contentstudio") ? 6000L : 3500L);
                }
                case 46 -> {
                    MinecraftServer server = mc.getSingleplayerServer();
                    if (mc.player == null || mc.level == null || server == null || server == rejoinServer) {
                        require(now < deadline, "publish and rejoin did not open the world again within 10 minutes");
                        return;
                    }
                    rejoinServer = null;
                    String world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
                    require(world.equals(rejoinWorld), "rejoined world " + world + " instead of " + rejoinWorld);
                    require(onServer(() -> registered(server)), "the published structure is registered after re-entering");
                    checks += 2;
                    LOG.info("REALM_STRUCTURE_REJOIN_PASS world={}", world);
                    step = 5;
                    leaveWorld(mc);
                    deadline = now + 60_000L;
                }
                case 5 -> {
                    if (mc.level != null || mc.getSingleplayerServer() != null) {
                        require(now < deadline, "leaving the test world took more than a minute");
                        return;
                    }
                    require(Files.isRegularFile(mc.gameDirectory.toPath().resolve("saves").resolve(GEN_WORLD).resolve("level.dat")),
                            "no generation test world saves/" + GEN_WORLD);
                    // Chunks of earlier runs hold earlier versions of the structure: everything generates again, and the player starts at spawn.
                    Path genWorld = mc.gameDirectory.toPath().resolve("saves").resolve(GEN_WORLD);
                    for (String folder : List.of("region", "entities", "poi", "playerdata", "players", "dimensions/minecraft/overworld/region",
                            "dimensions/minecraft/overworld/entities", "dimensions/minecraft/overworld/poi", "data/chunks.dat", "data/minecraft/chunk_tickets.dat",
                            "dimensions/minecraft/overworld/data/chunks.dat", "dimensions/minecraft/overworld/data/minecraft/chunk_tickets.dat")) {
                        ProjectStore.deleteTree(genWorld.resolve(folder));
                    }
                    //? if >=1.21 {
                    /*mc.createWorldOpenFlows().openWorld(GEN_WORLD, () -> { });
                    *///?} else {
                    mc.createWorldOpenFlows().loadLevel(new net.minecraft.client.gui.screens.TitleScreen(), GEN_WORLD);
                    //?}
                    deadline = now + 600_000L;
                    step = 6;
                    LOG.info("REALM_STRUCTURE_STEP opening {}", GEN_WORLD);
                }
                case 6 -> {
                    if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
                        require(now < deadline, "the generation test world did not open within 10 minutes");
                        return;
                    }
                    onServer(StructureEditorValidation::checkGeneration);
                    deadline = now + 90_000L;
                    step = 7;
                }
                case 7 -> {
                    // Entities of chunks far from players are hidden from queries: the player went there.
                    if (!onServer(StructureEditorValidation::generatedEntitiesLoaded)) {
                        require(now < deadline, "the structure's entities did not load within 90 seconds");
                        return;
                    }
                    onServer(StructureEditorValidation::checkGeneratedEntities);
                    onServer(StructureEditorValidation::locateMedium);
                    deadline = now + 600_000L;
                    step = 8;
                }
                case 8 -> {
                    // A few chunks a tick, so the medium structure's whole area generates.
                    if (onServer(StructureEditorValidation::generateMediumChunks)) {
                        require(now < deadline, "the medium structure's chunks did not generate within 10 minutes");
                        return;
                    }
                    onServer(StructureEditorValidation::checkMediumGeneration);
                    onServer(StructureEditorValidation::locateMega);
                    phaseStarted = now;
                    deadline = now + 1_200_000L;
                    step = 12;
                }
                case 12 -> {
                    // The first mega copy's chunks, many at a time.
                    if (onServer(StructureEditorValidation::pumpChunks)) {
                        require(now < deadline, "the mega structure's chunks did not generate within 20 minutes");
                        return;
                    }
                    megaCheck = new MegaCheck(megaA, "A", now - phaseStarted);
                    step = 15;
                }
                case 15 -> {
                    // A row of tiles a tick, so the server can let go of the chunks in between.
                    if (onServer(StructureEditorValidation::continueMegaCheck)) return;
                    onServer(StructureEditorValidation::finishMegaCheck);
                    if (megaCheck.instance() == megaA) {
                        onServer(StructureEditorValidation::startFlight);
                        phaseStarted = now;
                        due = now;
                        step = 13;
                    } else {
                        WorldGenNetwork.requestOpenEditor();
                        deadline = now + 60_000L;
                        step = 9;
                    }
                }
                case 13 -> {
                    // Across the second copy at 32 blocks a second while its chunks generate.
                    if (now < due) return;
                    due = now + 500L;
                    if (onServer(StructureEditorValidation::flyStep)) return;
                    onServer(StructureEditorValidation::endFlight);
                    phaseStarted = now;
                    deadline = now + 1_200_000L;
                    step = 14;
                }
                case 14 -> {
                    if (onServer(StructureEditorValidation::pumpChunks)) {
                        require(now < deadline, "the second mega copy's chunks did not generate within 20 minutes");
                        return;
                    }
                    megaCheck = new MegaCheck(megaB, "B", now - phaseStarted);
                    step = 15;
                }
                case 9 -> {
                    WorldGenPage panel = KineticGui.findPage(WorldGenPage.class);
                    if (panel == null) {
                        require(now < deadline, "the structure panel did not open");
                        return;
                    }
                    focusPanel(panel);
                    due = now + 60_000L;
                    step = 90;
                }
                case 90 -> {
                    // The 3D preview and both thumbnails, then a moment to draw them.
                    if (now < due && !panelLoaded(KineticGui.findPage(WorldGenPage.class))) return;
                    if (due - now > 1500L) {
                        due = now + 1500L;
                        return;
                    }
                    if (now < due) return;
                    capture("panel");
                    StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_GENERATION, MEGA, "");
                    deadline = now + 30_000L;
                    due = 0L;
                    step = 91;
                }
                case 91 -> {
                    if (KineticGui.findPage(GenerationPage.class) == null) {
                        require(now < deadline, "the structure panel did not open the mega structure's generation page");
                        return;
                    }
                    if (due == 0L) due = now + 4000L;
                    if (now < due) return;
                    capture("panel_generation");
                    checks++;
                    finish(null);
                }
                default -> {
                }
            }
        } catch (Throwable error) {
            finish(error);
        }
    }

    // ---- stage 1: build, scan, publish ----

    private static boolean entitiesLoaded() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        for (int cx = ORIGIN.getX() >> 4; cx <= (COPY.getX() + SIZE) >> 4; cx++) {
            for (int cz = ORIGIN.getZ() >> 4; cz <= (COPY.getZ() + SIZE) >> 4; cz++) {
                if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) return false;
            }
        }
        return true;
    }

    private static String unloadedEntityChunks() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        StringBuilder text = new StringBuilder("player at " + Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().get(0).blockPosition() + ", waiting for");
        for (int cx = ORIGIN.getX() >> 4; cx <= (COPY.getX() + SIZE) >> 4; cx++) {
            for (int cz = ORIGIN.getZ() >> 4; cz <= (COPY.getZ() + SIZE) >> 4; cz++) {
                if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) text.append(' ').append(cx).append(',').append(cz);
            }
        }
        return text.toString();
    }

    private static Void setUp() throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        // Earlier runs left a chest full of diamonds, which would drop as items when replaced, and armor stands.
        for (BlockPos pos : BlockPos.betweenClosed(ORIGIN.offset(-2, 0, -2), COPY.offset(SIZE + 2, HEIGHT + 2, SIZE + 2))) {
            if (level.getBlockEntity(pos) instanceof Container old) old.clearContent();
        }
        for (BlockPos pos : BlockPos.betweenClosed(COPY, COPY.offset(SIZE - 1, HEIGHT + 2, SIZE - 1))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        build(level, ORIGIN);
        AABB area = new AABB(ORIGIN.getX() - 2, ORIGIN.getY() - 2, ORIGIN.getZ() - 2, COPY.getX() + SIZE + 2, COPY.getY() + HEIGHT + 4, COPY.getZ() + SIZE + 2);
        for (var old : level.getEntities((net.minecraft.world.entity.Entity) null, area, entity -> !(entity instanceof net.minecraft.world.entity.player.Player))) old.discard();
        ArmorStand stand = new ArmorStand(level, ORIGIN.getX() + 28.5D, ORIGIN.getY() + 1D, ORIGIN.getZ() + 16.5D);
        level.addFreshEntity(stand);
        sourceStand = stand.getUUID();
        command(server, "summon minecraft:iron_golem " + (ORIGIN.getX() + 4.5D) + " " + (ORIGIN.getY() + 1) + " " + (ORIGIN.getZ() + 28.5D) + " {NoAI:1b}");
        BlockPos max = ORIGIN.offset(SIZE - 1, HEIGHT - 1, SIZE - 1);
        sourceBlocks = 0;
        wallBlocks = 0;
        for (BlockPos pos : BlockPos.betweenClosed(ORIGIN, max)) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) sourceBlocks++;
            if (state.is(Blocks.STONE_BRICKS)) wallBlocks++;
        }
        SelectionStore.apply(player, SelectionStore.Action.CLEAR, null, 0, 0);
        SelectionStore.apply(player, SelectionStore.Action.SET_A, ORIGIN, 0, 0);
        SelectionStore.apply(player, SelectionStore.Action.SET_B, max, 0, 0);
        SelectionStore.apply(player, SelectionStore.Action.CUT_A, ORIGIN.offset(7, 0, 7), 0, 0);
        SelectionStore.apply(player, SelectionStore.Action.CUT_B, ORIGIN.offset(25, HEIGHT - 1, 25), 0, 0);
        var selection = SelectionStore.get(player);
        require(selection.region() != null && selection.region().volume() == (long) SIZE * HEIGHT * SIZE, "selection region");
        require(selection.cutouts.size() == 1, "selection cut-out box");
        checks += 2;
        ScanService.start(player, ID, "验证四合院", level, selection.region(), selection.cutouts);
        buildMedium(level, MEDIUM_ORIGIN);
        List<Selection.Box> mediumCutouts = List.of(new Selection.Box(MEDIUM_ORIGIN.offset(110, 0, 110), MEDIUM_ORIGIN.offset(145, MEDIUM_HEIGHT - 1, 145)),
                new Selection.Box(MEDIUM_ORIGIN.offset(192, 0, 192), MEDIUM_ORIGIN.offset(MEDIUM_SIZE - 1, MEDIUM_HEIGHT - 1, MEDIUM_SIZE - 1)));
        ScanService.start(player, MEDIUM, "验证工坊", level,
                new Selection.Box(MEDIUM_ORIGIN, MEDIUM_ORIGIN.offset(MEDIUM_SIZE - 1, MEDIUM_HEIGHT - 1, MEDIUM_SIZE - 1)), mediumCutouts);
        return null;
    }

    /**
     * A 200×200 yard cut into 4×4 tiles of 64 (seams at 64, 128 and 192): a floor in a pattern of three stones, a
     * cobblestone wall around it, an oak fence line and a glass pane line crossing seams (connected), a bed whose two
     * halves lie in different tiles, a hut across a seam with a door, a chest with seven emeralds on a seam, and gold
     * blocks 95 blocks from the middle in the four directions, which land in the same places whichever way the
     * structure turns (the vanilla server check finds them). Lapis under the courtyard cut-out and emerald blocks in
     * the corner tile (cut out whole) must not generate.
     */
    /**
     * One 64×64 part of the 1024×1024 mega structure: a floor in a pattern of three stones, a cobblestone wall around
     * it, an oak fence line (along z 100) and a glass pane line (along x 512) crossing every seam, two huts across group
     * seams (x 384, z 640) and a chest with seven emeralds on one, lapis under the courtyard cut-out (x 600-759,
     * z 200-359) and emerald blocks in the corner tile (cut out whole). True while parts are left.
     */
    private static boolean buildMegaPart() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        int parts = MEGA_SIZE / 64;
        if (megaBuilt >= parts * parts) return false;
        int fromX = megaBuilt % parts * 64, fromZ = megaBuilt / parts * 64;
        megaBuilt++;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = fromX; x < fromX + 64; x++) {
            for (int z = fromZ; z < fromZ + 64; z++) {
                for (int y = 0; y < MEGA_HEIGHT + 2; y++) {
                    pos.set(MEGA_ORIGIN.getX() + x, MEGA_ORIGIN.getY() + y, MEGA_ORIGIN.getZ() + z);
                    if (level.getBlockState(pos).hasBlockEntity() && level.getBlockEntity(pos) instanceof Container old) old.clearContent();
                    BlockState state = megaState(x, y, z);
                    level.setBlock(pos, state == null ? Blocks.AIR.defaultBlockState() : state, 2);
                }
            }
        }
        if (fromX <= 384 && 384 < fromX + 64 && fromZ <= 700 && 700 < fromZ + 64
                && level.getBlockEntity(MEGA_ORIGIN.offset(384, 1, 700)) instanceof Container chest) {
            chest.setItem(0, new ItemStack(Items.EMERALD, 7));
        }
        return megaBuilt < parts * parts;
    }

    @javax.annotation.Nullable
    private static BlockState megaState(int x, int y, int z) {
        boolean courtyard = x >= 600 && x <= 759 && z >= 200 && z <= 359, corner = x >= 992 && z >= 992;
        if (y == 0) return (courtyard ? Blocks.LAPIS_BLOCK : corner ? Blocks.EMERALD_BLOCK : MEGA_FLOOR[(x / 4 + z / 4) % 3]).defaultBlockState();
        if ((x == 0 || z == 0 || x == MEGA_SIZE - 1 || z == MEGA_SIZE - 1) && y <= 3) return (corner ? Blocks.EMERALD_BLOCK : Blocks.COBBLESTONE).defaultBlockState();
        for (int[] hut : new int[][]{{376, 391, 500, 510}, {100, 110, 632, 647}}) {
            if (x < hut[0] || x > hut[1] || z < hut[2] || z > hut[3] || y > 4) continue;
            return y == 4 || x == hut[0] || x == hut[1] || z == hut[2] || z == hut[3] ? Blocks.OAK_PLANKS.defaultBlockState() : null;
        }
        if (y == 1 && x == 384 && z == 700) return Blocks.CHEST.defaultBlockState();
        if (y == 1 && z == 100 && x >= 10 && x <= 1013) {
            return Blocks.OAK_FENCE.defaultBlockState().setValue(CrossCollisionBlock.WEST, x > 10).setValue(CrossCollisionBlock.EAST, x < 1013);
        }
        if (y == 2 && x == 512 && z >= 10 && z <= 1013) {
            return Blocks.GLASS_PANE.defaultBlockState().setValue(CrossCollisionBlock.NORTH, z > 10).setValue(CrossCollisionBlock.SOUTH, z < 1013);
        }
        return null;
    }

    private static Void scanMega() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        List<Selection.Box> cutouts = List.of(new Selection.Box(MEGA_ORIGIN.offset(600, 0, 200), MEGA_ORIGIN.offset(759, MEGA_HEIGHT - 1, 359)),
                new Selection.Box(MEGA_ORIGIN.offset(992, 0, 992), MEGA_ORIGIN.offset(MEGA_SIZE - 1, MEGA_HEIGHT - 1, MEGA_SIZE - 1)));
        ScanService.start(player, MEGA, "验证城市", server.overworld(),
                new Selection.Box(MEGA_ORIGIN, MEGA_ORIGIN.offset(MEGA_SIZE - 1, MEGA_HEIGHT - 1, MEGA_SIZE - 1)), cutouts);
        return null;
    }

    private static void buildMedium(ServerLevel level, BlockPos origin) {
        BlockPos chestPos = origin.offset(64, 1, 100);
        if (level.getBlockEntity(chestPos) instanceof Container old) old.clearContent();
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(MEDIUM_SIZE - 1, MEDIUM_HEIGHT + 2, MEDIUM_SIZE - 1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        Block[] floor = {Blocks.POLISHED_ANDESITE, Blocks.SMOOTH_STONE, Blocks.STONE_BRICKS};
        for (int x = 0; x < MEDIUM_SIZE; x++) {
            for (int z = 0; z < MEDIUM_SIZE; z++) {
                boolean courtyard = x >= 110 && x <= 145 && z >= 110 && z <= 145, corner = x >= 192 && z >= 192;
                level.setBlock(origin.offset(x, 0, z), (courtyard ? Blocks.LAPIS_BLOCK : corner ? Blocks.EMERALD_BLOCK : floor[(x / 4 + z / 4) % 3]).defaultBlockState(), 2);
                if (x == 0 || z == 0 || x == MEDIUM_SIZE - 1 || z == MEDIUM_SIZE - 1) {
                    for (int y = 1; y <= 3; y++) level.setBlock(origin.offset(x, y, z), (corner ? Blocks.EMERALD_BLOCK : Blocks.COBBLESTONE).defaultBlockState(), 2);
                }
            }
        }
        for (int x = 186; x <= 197; x++) {
            for (int z = 40; z <= 50; z++) {
                if (x == 186 || x == 197 || z == 40 || z == 50) {
                    for (int y = 1; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
                }
                level.setBlock(origin.offset(x, 4, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            }
        }
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(origin.offset(192, 1, 40), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(origin.offset(192, 2, 40), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        // Fences and panes take their connections from the blocks beside them, as a player placing them would.
        for (int x = 10; x < 190; x++) {
            BlockPos pos = origin.offset(x, 1, 30);
            level.setBlock(pos, Block.updateFromNeighbourShapes(Blocks.OAK_FENCE.defaultBlockState(), level, pos), 3);
        }
        for (int z = 10; z < 190; z++) {
            BlockPos pos = origin.offset(100, 2, z);
            level.setBlock(pos, Block.updateFromNeighbourShapes(Blocks.GLASS_PANE.defaultBlockState(), level, pos), 3);
        }
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
        level.setBlock(origin.offset(63, 1, 20), bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(origin.offset(64, 1, 20), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        for (int[] gold : new int[][]{{195, 100}, {5, 100}, {100, 195}, {100, 5}}) level.setBlock(origin.offset(gold[0], 1, gold[1]), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 2);
        if (level.getBlockEntity(chestPos) instanceof Container chest) chest.setItem(0, new ItemStack(Items.EMERALD, 7));
    }

    /**
     * Ground of grass, rooms around a 19×19 courtyard (stone brick walls three high, plank floors and roof, a door
     * to the outside and one to the courtyard), a cobblestone well with water in the middle of the courtyard, a
     * chest with three diamonds, a chest with five gold ingots and a zombie spawner.
     */
    private static void build(ServerLevel level, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(SIZE - 1, HEIGHT + 2, SIZE - 1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        }
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                boolean inside = x >= 1 && x <= 31 && z >= 1 && z <= 31;
                boolean courtyard = x >= 7 && x <= 25 && z >= 7 && z <= 25;
                boolean outerWall = inside && (x == 1 || x == 31 || z == 1 || z == 31);
                boolean innerWall = x >= 6 && x <= 26 && z >= 6 && z <= 26 && (x == 6 || x == 26 || z == 6 || z == 26);
                // Grass under the well would turn to dirt on a random tick and differ from the scan.
                boolean well = x >= 15 && x <= 17 && z >= 15 && z <= 17;
                level.setBlock(origin.offset(x, 0, z), (inside && !courtyard ? Blocks.OAK_PLANKS : well ? Blocks.DIRT : Blocks.GRASS_BLOCK).defaultBlockState(), 2);
                if (outerWall || innerWall) {
                    for (int y = 1; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
                if (inside && !courtyard) level.setBlock(origin.offset(x, 4, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            }
        }
        for (int y = 1; y <= 2; y++) {
            level.setBlock(origin.offset(16, y, 1), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(origin.offset(16, y, 6), Blocks.AIR.defaultBlockState(), 2);
        }
        for (int x = 15; x <= 17; x++) {
            for (int z = 15; z <= 17; z++) level.setBlock(origin.offset(x, 1, z), Blocks.COBBLESTONE.defaultBlockState(), 2);
        }
        level.setBlock(origin.offset(16, 1, 16), Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(origin.offset(10, 1, 10), Blocks.POPPY.defaultBlockState(), 2);
        level.setBlock(origin.offset(16, 1, 3), Blocks.CHEST.defaultBlockState(), 2);
        if (level.getBlockEntity(origin.offset(16, 1, 3)) instanceof Container chest) chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        level.setBlock(origin.offset(28, 1, 4), Blocks.CHEST.defaultBlockState(), 2);
        if (level.getBlockEntity(origin.offset(28, 1, 4)) instanceof Container chest) chest.setItem(0, new ItemStack(Items.GOLD_INGOT, 5));
        level.setBlock(origin.offset(4, 1, 4), Blocks.SPAWNER.defaultBlockState(), 2);
        if (level.getBlockEntity(origin.offset(4, 1, 4)) instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity spawner) {
            spawner.setEntityId(EntityType.ZOMBIE, level.getRandom());
        }
    }

    private static Void checkAndPublish() throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        StructureProject project = ProjectStore.load(ID);
        require(project != null, "project saved");
        require(project.size[0] == SIZE && project.size[1] == HEIGHT && project.size[2] == SIZE, "project size " + java.util.Arrays.toString(project.size));
        require(project.blocks == sourceBlocks, "scanned blocks " + project.blocks + " != " + sourceBlocks);
        require(project.containers == 2 && project.entities == 2, "containers " + project.containers + ", entities " + project.entities);
        require(project.embed == 1, "embedding guessed from the ground layer: " + project.embed);
        require("small".equals(project.tier()), "tier " + project.tier());
        checks += 6;
        var preview = ProjectPreview.read(ID, share -> { }, () -> false);
        require(preview != null && preview.snapshot().nonAirCount() == sourceBlocks && preview.missing().isEmpty(), "preview of the draft");
        checks++;

        Cutouts cutouts = ProjectStore.cutouts(ID);
        require(cutouts.boxes.size() == 1 && cutouts.boxes.get(0).minX() == 7 && cutouts.boxes.get(0).maxZ() == 25, "cut-out box from the selection");
        cutouts.boxes.add(new Cutouts.Box("well", 15, 0, 15, 17, 1, 17, true));
        ProjectStore.saveCutouts(ID, cutouts);
        StructureProject.Generation generation = project.generation;
        generation.spacing = 6;
        generation.separation = 3;
        generation.frequency = 1F;
        generation.include = new ArrayList<>(List.of("#minecraft:is_overworld"));
        generation.exclude = new ArrayList<>(List.of("#minecraft:is_ocean", "#minecraft:is_deep_ocean", "#minecraft:is_river", "#minecraft:is_beach"));
        // Courtyards start every six chunks; none may start where it would overlap the medium structure.
        generation.exclusionSet = MEDIUM;
        generation.exclusionChunks = 9;
        ProjectStore.save(project);
        checks++;

        // Markers: what the scan listed, a table made from the diamond chest, a custom spawner, golems left out.
        Markers markers = ProjectStore.markers(ID);
        require(markers.containers.size() == 2 && markers.containers.stream().allMatch(container -> container.lootable), "two lootable containers listed");
        Markers.Spawner spawner = markers.spawner(4, 1, 4);
        require(spawner != null && "minecraft:zombie".equals(spawner.entity), "the spawner is listed with its zombie");
        require(markers.entities.size() == 2 && markers.entity("minecraft:armor_stand", 28.5D, 1D, 16.5D) != null, "entities listed " + markers.entities.size());
        checks += 3;
        Component problem = LootTables.create(server, TABLE, LootTables.Kind.ITEMS, "", ID, new int[]{16, 1, 3});
        require(problem == null, "new table from the chest: " + (problem == null ? "" : problem.getString()));
        Path tableFile = LootTables.packFile(ResourceLocation.tryParse(TABLE));
        JsonObject table = JsonParser.parseString(Files.readString(tableFile)).getAsJsonObject();
        JsonArray entries = table.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries");
        require(entries.size() == 1 && entries.get(0).getAsJsonObject().get("name").getAsString().equals("minecraft:diamond")
                && entries.get(0).toString().contains("\"count\":3"), "table made from the chest's items: " + entries);
        // An entry of a mod that is not installed: the table must still load, without it.
        JsonObject ghost = new JsonObject();
        ghost.addProperty("type", "minecraft:item");
        ghost.addProperty("name", GHOST);
        ghost.addProperty("weight", 1);
        entries.add(ghost);
        Files.writeString(tableFile, table.toString());
        require(LootTables.missingItems(TABLE).equals(List.of(GHOST)), "the missing item is reported");
        checks += 2;
        if (KineticPlatform.isModLoaded("contentstudio")) checkContentStudio(server, table);
        markers.container(16, 1, 3).mode = "table";
        markers.container(16, 1, 3).table = TABLE;
        markers.container(28, 1, 4).mode = "table";
        markers.container(28, 1, 4).table = "realmcontrol:chests/validation_missing";
        spawner.mode = "custom";
        spawner.mobs = new ArrayList<>(List.of(new Markers.Mob("minecraft:skeleton", 1)));
        markers.entityTypes.put("minecraft:iron_golem", false);
        ProjectStore.saveMarkers(ID, markers);
        Publisher.Outcome refused = Publisher.publish(server, ID, share -> { });
        require(!refused.success() && refused.message().getString().contains("validation_missing"), "an unknown table stops the publish: " + refused.message().getString());
        markers.container(28, 1, 4).mode = "keep";
        ProjectStore.saveMarkers(ID, markers);
        checks++;

        // A ZIP, then this world's datapacks folder; both carry the table along.
        project = ProjectStore.load(ID);
        project.save.target = "zip";
        ProjectStore.save(project);
        Publisher.Outcome zipped = Publisher.publish(server, ID, share -> { });
        Path zip = Publisher.exportFolder().resolve("realmcontrol_validation_courtyard.zip");
        require(zipped.success() && Files.isRegularFile(zip), "ZIP export: " + zipped.message().getString());
        try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip.toFile())) {
            require(file.getEntry("pack.mcmeta") != null && file.getEntry("data/realmcontrol/" + templateFolder() + "/validation_courtyard.nbt") != null
                    && file.getEntry("data/realmcontrol/" + LootTables.FOLDER + "/" + TABLE_PATH + ".json") != null, "the ZIP holds the pack, the template and the table");
        }
        Files.delete(zip);
        project.save.target = "world";
        ProjectStore.save(project);
        Publisher.Outcome world = Publisher.publish(server, ID, share -> { });
        Path worldPack = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(Publisher.PACK);
        require(world.success() && Files.isRegularFile(worldPack.resolve("data/realmcontrol/" + templateFolder() + "/validation_courtyard.nbt"))
                && Files.isRegularFile(worldPack.resolve("data/realmcontrol/" + LootTables.FOLDER + "/" + TABLE_PATH + ".json")), "world datapacks folder");
        removePublished(worldPack);
        project.save.target = "kineticcore";
        // 1.20.1 also writes the folders 1.21 reads.
        project.save.bothFormats = LootTables.FOLDER.equals("loot_tables");
        ProjectStore.save(project);
        checks += 2;

        Publisher.Outcome outcome = Publisher.publish(server, ID, share -> { });
        require(outcome.success(), "publish: " + outcome.message().getString());
        Path data = Publisher.packFolder().resolve("data").resolve("realmcontrol");
        if (project.save.bothFormats) {
            require(Files.isRegularFile(data.resolve("structure/validation_courtyard.nbt"))
                    && Files.isRegularFile(data.resolve("loot_table/" + TABLE_PATH + ".json")), "the 1.21 folders are written too");
            checks++;
        }
        //? if >=1.21 {
        /*Path template = data.resolve("structure/validation_courtyard.nbt");
        *///?} else {
        Path template = data.resolve("structures/validation_courtyard.nbt");
        //?}
        for (Path file : List.of(template, data.resolve("worldgen/template_pool/validation_courtyard/start.json"),
                data.resolve("worldgen/structure/validation_courtyard.json"), data.resolve("worldgen/structure_set/validation_courtyard.json"),
                data.resolve("tags/worldgen/biome/has_structure/validation_courtyard.json"), Publisher.packFolder().resolve("pack.mcmeta"))) {
            require(Files.isRegularFile(file), "published file " + file);
        }
        String tagText = Files.readString(data.resolve("tags/worldgen/biome/has_structure/validation_courtyard.json"));
        require(!tagText.contains("minecraft:ocean\"") && tagText.contains("\"id\""), "biome tag lists biomes without oceans");
        require("published".equals(ProjectStore.load(ID).status), "status published");
        checks += 3;

        CompoundTag root = readNbt(template);
        StructureTemplate structure = new StructureTemplate();
        //? if >=26.1 {
        /*structure.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK, root);
        *///?} else {
        structure.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), root);
        //?}
        require(structure.getSize().getX() == SIZE && structure.getSize().getY() == HEIGHT && structure.getSize().getZ() == SIZE, "template size " + structure.getSize());
        checks++;

        // A copy placed through vanilla over ground and markers: void keeps them, indoor air carves.
        for (BlockPos pos : BlockPos.betweenClosed(COPY, COPY.offset(SIZE - 1, HEIGHT + 2, SIZE - 1))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        for (BlockPos pos : BlockPos.betweenClosed(COPY, COPY.offset(SIZE - 1, 0, SIZE - 1))) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
        BlockPos courtyardMarker = COPY.offset(10, 2, 10), indoorMarker = COPY.offset(3, 2, 16), roofMarker = COPY.offset(3, 5, 3), outsideMarker = COPY.offset(0, 2, 0);
        for (BlockPos marker : List.of(courtyardMarker, indoorMarker, roofMarker, outsideMarker)) level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        structure.placeInWorld(level, COPY, COPY, new StructurePlaceSettings(), level.getRandom(), 2);
        int mismatches = 0;
        String firstMismatch = "";
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {
                    boolean courtyard = x >= 7 && x <= 25 && z >= 7 && z <= 25 && !(x >= 15 && x <= 17 && z >= 15 && z <= 17 && y <= 1);
                    BlockState source = level.getBlockState(ORIGIN.offset(x, y, z));
                    if (courtyard || source.isAir()) continue;
                    BlockState placed = level.getBlockState(COPY.offset(x, y, z));
                    if (!placed.equals(source) && mismatches++ == 0) firstMismatch = " first at " + x + "," + y + "," + z + ": " + source + " became " + placed;
                }
            }
        }
        require(mismatches == 0, mismatches + " placed blocks differ from the original" + firstMismatch);
        require(level.getBlockState(COPY.offset(10, 0, 10)).is(Blocks.DIRT) && level.getBlockState(COPY.offset(10, 1, 10)).isAir(), "courtyard left alone");
        require(level.getBlockState(courtyardMarker).is(Blocks.GOLD_BLOCK), "cut-out keeps what was there");
        require(level.getBlockState(roofMarker).is(Blocks.GOLD_BLOCK) && level.getBlockState(outsideMarker).is(Blocks.GOLD_BLOCK), "open-sky air keeps what was there");
        require(level.getBlockState(indoorMarker).isAir(), "indoor air carves");
        require(level.getBlockState(COPY.offset(16, 1, 15)).is(Blocks.COBBLESTONE), "the well was added back");
        // Read before anything opens the chest: opening it would fill it from the table.
        CompoundTag tag = ScanService.blockEntityTag(level.getBlockEntity(COPY.offset(16, 1, 3)));
        require(TABLE.equals(tag.getString("LootTable")) && !tag.contains("Items"), "the chest names its loot table instead of holding items: " + tag);
        require(level.getBlockEntity(COPY.offset(28, 1, 4)) instanceof Container chest && chest.getItem(0).is(Items.GOLD_INGOT) && chest.getItem(0).getCount() == 5,
                "the other chest keeps its items");
        tag = ScanService.blockEntityTag(level.getBlockEntity(COPY.offset(4, 1, 4)));
        tag = tag.getCompound("SpawnData");
        tag = tag.getCompound("entity");
        require("minecraft:skeleton".equals(tag.getString("id")), "the spawner spawns skeletons: " + tag);
        AABB copyBox = new AABB(COPY.getX(), COPY.getY(), COPY.getZ(), COPY.getX() + SIZE, COPY.getY() + HEIGHT, COPY.getZ() + SIZE);
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, copyBox);
        require(stands.size() == 1 && !stands.get(0).getUUID().equals(sourceStand), "armor stand placed with a UUID of its own");
        require(level.getEntities(EntityType.IRON_GOLEM, copyBox, entity -> true).isEmpty(), "the iron golem is left out");
        checks += 11;
        LOG.info("REALM_STRUCTURE_TEMPLATE_PASS blocks={} walls={} checks={}", sourceBlocks, wallBlocks, checks);
        checkMediumPublish(server);
        return null;
    }

    private static CompoundTag readNbt(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            //? if >=1.20.3 {
            /*return NbtIo.readCompressed(in, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            *///?} else {
            return NbtIo.readCompressed(in);
            //?}
        }
    }

    /**
     * The medium draft publishes as tiles: 15 tile templates (the corner tile is cut out whole) and the frame with the
     * anchor and 15 jigsaw blocks, a pool for each, a structure that starts at the anchor, only vanilla types; files
     * of an earlier publish that no longer belong are gone. Then its spacing changes as the structure panel changes
     * it, which rewrites the published structure set at once.
     */
    private static void checkMediumPublish(MinecraftServer server) throws Exception {
        StructureProject project = ProjectStore.load(MEDIUM);
        require(project != null && "medium".equals(project.tier()) && project.tilesX() == 4 && project.tilesZ() == 4 && project.tileSize == 64,
                "medium draft: " + (project == null ? "missing" : project.tier() + " " + project.tilesX() + "x" + project.tilesZ()));
        require(ProjectStore.cutouts(MEDIUM).boxes.size() == 2, "the medium draft's cut-outs");
        StructureProject.Generation generation = project.generation;
        generation.spacing = 24;
        generation.separation = 12;
        generation.frequency = 1F;
        generation.salt = 734105;
        generation.include = new ArrayList<>(List.of("#minecraft:is_overworld"));
        generation.exclude = new ArrayList<>(List.of("#minecraft:is_ocean", "#minecraft:is_deep_ocean", "#minecraft:is_river", "#minecraft:is_beach"));
        ProjectStore.save(project);
        Path data = Publisher.packFolder().resolve("data").resolve("realmcontrol");
        Path tiles = data.resolve(templateFolder()).resolve("validation_medium");
        Path pools = data.resolve("worldgen/template_pool/validation_medium");
        // Left from an earlier publish: a small template and a tile that no longer exists.
        for (Path stale : List.of(data.resolve(templateFolder()).resolve("validation_medium.nbt"), tiles.resolve("t_9_9.nbt"), pools.resolve("t_9_9.json"))) {
            Files.createDirectories(stale.getParent());
            Files.writeString(stale, "{}");
        }
        Publisher.Outcome outcome = Publisher.publish(server, MEDIUM, share -> { });
        require(outcome.success(), "medium publish: " + outcome.message().getString());
        int written = 0;
        for (int tz = 0; tz < 4; tz++) {
            for (int tx = 0; tx < 4; tx++) {
                boolean template = Files.isRegularFile(tiles.resolve("t_" + tx + "_" + tz + ".nbt")), pool = Files.isRegularFile(pools.resolve("t_" + tx + "_" + tz + ".json"));
                require(template == pool && template == !(tx == 3 && tz == 3), "tile " + tx + "," + tz + " template " + template + ", pool " + pool);
                if (template) written++;
            }
        }
        require(written == 15 && Files.isRegularFile(tiles.resolve("frame.nbt")) && Files.isRegularFile(pools.resolve("start.json")), "15 tiles and the frame");
        require(!Files.exists(data.resolve(templateFolder()).resolve("validation_medium.nbt")) && !Files.exists(tiles.resolve("t_9_9.nbt"))
                && !Files.exists(pools.resolve("t_9_9.json")), "files of an earlier publish are removed");
        CompoundTag root = readNbt(tiles.resolve("frame.nbt"));
        int jigsaws = root.getList("blocks", Tag.TAG_COMPOUND).size();
        require(jigsaws == 16, "the frame holds the anchor and a jigsaw per tile: " + jigsaws);
        JsonObject structure = JsonParser.parseString(Files.readString(data.resolve("worldgen/structure/validation_medium.json"))).getAsJsonObject();
        require((MEDIUM + "/anchor").equals(structure.get("start_jigsaw_name").getAsString()) && structure.get("size").getAsInt() == 1
                && (MEDIUM + "/start").equals(structure.get("start_pool").getAsString()), "the structure starts at the frame's anchor: " + structure);
        // Nothing of RealmControl in the files: the structure generates without it.
        Pattern types = Pattern.compile("\"(type|element_type|processors|fallback)\"\\s*:\\s*\"([^\"]+)\"");
        for (String file : List.of("worldgen/structure/validation_medium.json", "worldgen/structure_set/validation_medium.json",
                "worldgen/template_pool/validation_medium/start.json", "worldgen/template_pool/validation_medium/t_0_0.json")) {
            Matcher matcher = types.matcher(Files.readString(data.resolve(file)));
            int found = 0;
            while (matcher.find()) {
                found++;
                require(matcher.group(2).startsWith("minecraft:"), file + " uses " + matcher.group(2));
            }
            require(found > 0, "no types read from " + file);
        }
        checks += 7;

        // The structure panel saves a custom structure's rules through the editor: the published set changes at once.
        StructureProject edited = ProjectStore.load(MEDIUM);
        edited.generation.spacing = MEDIUM_SPACING;
        Publisher.Outcome synced = StructureEditorNetwork.applyEdit(server, edited, "");
        JsonObject set = JsonParser.parseString(Files.readString(data.resolve("worldgen/structure_set/validation_medium.json"))).getAsJsonObject();
        require(synced.success() && set.getAsJsonObject("placement").get("spacing").getAsInt() == MEDIUM_SPACING,
                "the new spacing is in the published structure set: " + synced.message().getString());
        require("published".equals(ProjectStore.load(MEDIUM).status), "a rule change keeps the draft published");
        checks += 2;
        if (!EXPORT_PACK.isBlank()) exportMedium(Publisher.packFolder(), Path.of(EXPORT_PACK));
        LOG.info("REALM_STRUCTURE_MEDIUM_PUBLISH_PASS tiles={} checks={}", written, checks);
        checkMegaPublish(server);
    }

    /**
     * The mega draft: 32×32 tiles and a separation that keeps two apart from the start. A too small separation and
     * the world's datapacks folder are refused; into the KineticCore pack it writes 1007 tile templates (the corner
     * and the 16 tiles wholly inside the courtyard cut-out are listed absent), a {@code realmcontrol:mega} structure and a {@code realmcontrol:mega_spread} set. A
     * structure and set of types nothing registers are put beside it, which the next world must leave out.
     */
    private static void checkMegaPublish(MinecraftServer server) throws Exception {
        StructureProject project = ProjectStore.load(MEGA);
        require(project != null && "mega".equals(project.tier()) && project.tileSize == 32 && project.tilesX() == 32 && project.tilesZ() == 32,
                "mega draft: " + (project == null ? "missing" : project.tier() + " tiles " + project.tileSize));
        require(project.generation.separation >= project.megaSeparation() && project.generation.spacing > project.generation.separation,
                "a new mega draft starts with a separation that keeps two apart: " + project.generation.separation + " < " + project.megaSeparation());
        StructureProject.Generation generation = project.generation;
        generation.spacing = MEGA_SPACING;
        generation.separation = 10;
        generation.frequency = 1F;
        generation.salt = 913377;
        // A foundation block that never generates by itself, so where the foundation went can be told from the ground.
        generation.foundationBlock = "minecraft:waxed_copper_block";
        generation.include = new ArrayList<>(List.of("#minecraft:is_overworld"));
        generation.exclude = new ArrayList<>(List.of("#minecraft:is_ocean", "#minecraft:is_deep_ocean", "#minecraft:is_river", "#minecraft:is_beach"));
        ProjectStore.save(project);
        Publisher.Outcome tooClose = Publisher.publish(server, MEGA, share -> { });
        require(!tooClose.success() && tooClose.message().getString().contains(String.valueOf(project.megaSeparation())), "a too small separation is refused: " + tooClose.message().getString());
        generation.separation = MEGA_SEPARATION;
        project.save.target = "world";
        ProjectStore.save(project);
        Publisher.Outcome elsewhere = Publisher.publish(server, MEGA, share -> { });
        require(!elsewhere.success(), "a mega structure is not published into the world's datapacks folder");
        project.save.target = "kineticcore";
        ProjectStore.save(project);
        Publisher.Outcome outcome = Publisher.publish(server, MEGA, share -> { });
        require(outcome.success(), "mega publish: " + outcome.message().getString());
        Path data = Publisher.packFolder().resolve("data").resolve("realmcontrol");
        Path tiles = data.resolve(templateFolder()).resolve("validation_mega");
        int written;
        try (var files = Files.list(tiles)) {
            written = (int) files.filter(file -> file.getFileName().toString().matches("t_\\d+_\\d+\\.nbt")).count();
        }
        // The corner tile and the 4×4 tiles wholly inside the courtyard cut-out have nothing to place.
        require(written == 1007 && !Files.exists(tiles.resolve("t_31_31.nbt")) && !Files.exists(tiles.resolve("t_20_8.nbt")),
                "1007 mega tiles, those cut out whole left out: " + written);
        JsonObject structure = JsonParser.parseString(Files.readString(data.resolve("worldgen/structure/validation_mega.json"))).getAsJsonObject();
        require("realmcontrol:mega".equals(structure.get("type").getAsString()) && structure.getAsJsonArray("absent").size() == 17
                && structure.getAsJsonArray("absent").toString().contains("\"31_31\""),
                "the mega structure file: " + structure);
        JsonObject placement = JsonParser.parseString(Files.readString(data.resolve("worldgen/structure_set/validation_mega.json"))).getAsJsonObject().getAsJsonObject("placement");
        require("realmcontrol:mega_spread".equals(placement.get("type").getAsString()) && placement.getAsJsonArray("size").toString().equals("[1024,1024]")
                && placement.get("separation").getAsInt() == MEGA_SEPARATION, "the mega structure set: " + placement);
        // Types nothing registers, as after removing the mod that adds them: the pack must leave these files out.
        Files.writeString(data.resolve("worldgen/structure/validation_unknown_type.json"), "{\"type\":\"realmcontrol:not_registered\",\"biomes\":\"#minecraft:is_overworld\","
                + "\"step\":\"surface_structures\",\"spawn_overrides\":{}}");
        Files.writeString(data.resolve("worldgen/structure_set/validation_unknown_type.json"), "{\"structures\":[{\"structure\":\"realmcontrol:validation_unknown_type\","
                + "\"weight\":1}],\"placement\":{\"type\":\"realmcontrol:not_registered_spread\",\"salt\":1}}");
        checks += 7;
        LOG.info("REALM_STRUCTURE_MEGA_PUBLISH_PASS tiles={} checks={}", written, checks);
    }

    /** Copies the medium structure's published files, with the pack's pack.mcmeta, into a data pack folder of their own. */
    private static void exportMedium(Path pack, Path target) throws Exception {
        ProjectStore.deleteTree(target);
        Files.createDirectories(target);
        Files.copy(pack.resolve("pack.mcmeta"), target.resolve("pack.mcmeta"));
        try (var files = Files.walk(pack.resolve("data"))) {
            for (Path file : files.filter(Files::isRegularFile).filter(file -> file.toString().replace('\\', '/').contains("validation_medium")).toList()) {
                Path copy = target.resolve(pack.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        }
        LOG.info("REALM_STRUCTURE_STEP medium pack exported to {}", target);
    }

    /** ContentStudio edits the pack's table file itself: no override, and the missing mod's entry stays in the file. */
    private static void checkContentStudio(MinecraftServer server, JsonObject table) throws Exception {
        Class<?> store = Class.forName("dev.xyat.contentstudio.loot.server.LootTableOverrideStore");
        ResourceLocation id = ResourceLocation.tryParse(TABLE);
        require((boolean) store.getMethod("isFileBacked", ResourceLocation.class).invoke(null, id), "ContentStudio edits the pack's table file");
        JsonObject edited = table.deepCopy();
        edited.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("weight", 2);
        Object result = store.getMethod("save", MinecraftServer.class, ResourceLocation.class, String.class).invoke(null, server, id, edited.toString());
        require((boolean) result.getClass().getMethod("success").invoke(result), "ContentStudio saved the table file");
        String saved = Files.readString(LootTables.packFile(id));
        require(saved.contains("\"weight\": 2") && saved.contains(GHOST), "the file holds the edit and keeps the missing mod's entry");
        require(!(boolean) store.getMethod("hasOverride", ResourceLocation.class).invoke(null, id), "no ContentStudio override was made");
        checks += 3;
    }

    private static String templateFolder() {
        return LootTables.FOLDER.equals("loot_table") ? "structure" : "structures";
    }

    // ---- captures ----

    private static void setScreenSize(Minecraft mc, String language) {
        mc.getLanguageManager().setSelected(language);
        mc.options.languageCode = language;
        mc.getWindow().setWindowed(1920, 1080);
        mc.resizeDisplay();
        reload = mc.reloadResourcePacks();
    }

    private static void openPage(int index) {
        StructureProject project = EditorClient.projects().stream().map(EditorClient.ProjectEntry::project)
                .filter(entry -> entry.id.equals(ID)).findFirst().orElse(null);
        switch (index) {
            case 0 -> StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_EDITOR, ID, "");
            case 1 -> {
                StructureEditorPage parent = KineticGui.findPage(StructureEditorPage.class);
                require(parent != null && project != null, "the editor did not open with the draft");
                KineticGui.openChild(new NewProjectPage(parent));
            }
            case 2 -> KineticGui.openChild(new ProjectSettingsPage(project));
            case 3 -> KineticGui.openChild(new CutoutPage(project));
            case 4 -> KineticGui.openChild(new GenerationPage(project));
            case 5 -> {
                GenerationPage generation = KineticGui.findPage(GenerationPage.class);
                require(generation != null, "generation page open");
                KineticGui.openChild(new BiomePickerPage(generation));
            }
            case 6 -> KineticGui.openChild(markersPage(project, "c:16,1,3"));
            case 7 -> KineticGui.openChild(new LootTablePickerPage(TABLE, id -> { }));
            case 8 -> KineticGui.openChild(new NewLootTablePage(openMarkers(), ProjectStore.markers(ID).container(16, 1, 3)));
            case 9 -> KineticGui.openChild(new SpawnerPage(openMarkers(), List.of(ProjectStore.markers(ID).spawner(4, 1, 4))));
            case 10 -> KineticGui.openChild(markersPage(project, "e:minecraft:iron_golem"));
            case 11 -> KineticGui.openChild(new PublishPage(project));
            case 12 -> KineticGui.openChild(new PublishPage(EditorClient.projects().stream().map(EditorClient.ProjectEntry::project)
                    .filter(entry -> entry.id.equals(MEDIUM)).findFirst().orElseThrow()));
            case 13 -> KineticGui.openChild(new PublishPage(EditorClient.projects().stream().map(EditorClient.ProjectEntry::project)
                    .filter(entry -> entry.id.equals(MEGA)).findFirst().orElseThrow()));
            case 14 -> {
                try {
                    Class.forName("dev.xyat.contentstudio.loot.client.LootClientHandler").getMethod("requestOpenTable", String.class).invoke(null, TABLE);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("ContentStudio did not open the table", e);
                }
            }
            default -> {
            }
        }
    }

    /** The markers page as the server would open it: the table exists and names one missing item. */
    private static boolean registered(MinecraftServer server) {
        return server.registryAccess().registryOrThrow(Registries.STRUCTURE).containsKey(ResourceLocation.tryParse(ID));
    }

    /** The editor's Test Place (or Undo Test) for the courtyard, as the button does: its dialog opens. */
    private static void openTestDialog() throws Exception {
        StructureEditorPage editor = KineticGui.findPage(StructureEditorPage.class);
        require(editor != null, "editor open for the test placement");
        EditorClient.ProjectEntry entry = EditorClient.projects().stream().filter(e -> e.project().id.equals(ID)).findFirst().orElse(null);
        require(entry != null, "the courtyard is listed");
        var method = StructureEditorPage.class.getDeclaredMethod("test", EditorClient.ProjectEntry.class);
        method.setAccessible(true);
        method.invoke(editor, entry);
    }

    /** A mega draft is not test placed; nothing is recorded. */
    private static void checkTestRefusals() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        require(TestPlacement.record(server) == null, "no test placement left from an earlier run");
        TestPlacement.place(player, MEGA);
        require(!TestPlacement.busy(MEGA) && TestPlacement.record(server) == null, "a mega draft is refused");
        checks += 2;
    }

    /** What the box holds before the test placement, where the server will put it: centred on the player, set in by the embedding. */
    private static Void recordTestArea() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        BlockPos at = server.getPlayerList().getPlayers().get(0).blockPosition();
        testMin = new BlockPos(at.getX() - SIZE / 2, at.getY() - ProjectStore.load(ID).embed, at.getZ() - SIZE / 2);
        testBefore = new BlockState[SIZE * HEIGHT * SIZE];
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) testBefore[x + SIZE * (z + SIZE * y)] = level.getBlockState(testMin.offset(x, y, z));
            }
        }
        // Entities an interrupted earlier run placed would be counted with this run's.
        List<net.minecraft.world.entity.Entity> old = new ArrayList<>();
        for (var entity : level.getAllEntities()) if (entity != null && TestPlacement.tagged(entity)) old.add(entity);
        old.forEach(net.minecraft.world.entity.Entity::discard);
        return null;
    }

    /** Whether the container at {@code pos} names the courtyard's loot table, under vanilla's key or Lootr's. */
    private static boolean namesTable(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) return false;
        CompoundTag tag = ScanService.blockEntityTag(blockEntity);
        return TABLE.equals(tag.getString("LootTable")) || TABLE.equals(tag.getString("specialLootChest_table"));
    }

    private static AABB testBox() {
        return new AABB(testMin.getX(), testMin.getY(), testMin.getZ(), testMin.getX() + SIZE, testMin.getY() + HEIGHT, testMin.getZ() + SIZE);
    }

    /**
     * The test placement holds the draft as it generates: every listed cell is the draft's block, every other cell
     * (the cut-out courtyard, open-sky air) what was there; the chest names its table, the other keeps its gold, the
     * spawner spawns skeletons, the armor stand came with the tag and the golem stayed out.
     */
    private static Void checkTestPlacement() throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        TestPlacement.Record record = TestPlacement.record(server);
        require(record != null && record.min().equals(testMin) && record.sizeX() == SIZE && record.sizeY() == HEIGHT && record.sizeZ() == SIZE
                && ID.equals(record.project()), "test placement box " + record + " expected at " + testMin);
        StructureProject project = ProjectStore.load(ID);
        Tile tile = ProjectStore.readTile(ID, 0, 0);
        boolean[] keep = Publisher.cellsToWrite(project, tile, ProjectStore.cutouts(ID), 0, 0);
        int placed = 0, kept = 0, converted = 0, mismatches = 0;
        String first = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    int cell = tile.cell(x, y, z);
                    String expected = keep[cell] ? tile.palette.get(tile.cells[cell] & 0xFFFF) : BlockStates.serialize(testBefore[x + SIZE * (z + SIZE * y)]);
                    String actual = BlockStates.serialize(level.getBlockState(testMin.offset(x, y, z)));
                    if (keep[cell]) placed++;
                    else kept++;
                    if (expected.equals(actual)) continue;
                    // Lootr (in the 1.20.1 pack) swaps every placed container that names a loot table for its own, keeping the table.
                    if (keep[cell] && namesTable(level, testMin.offset(x, y, z))) {
                        converted++;
                        continue;
                    }
                    if (mismatches++ == 0) first = " first at " + x + "," + y + "," + z + ": " + expected + " became " + actual;
                }
            }
        }
        require(mismatches == 0, mismatches + " cells of the test placement differ" + first);
        require(placed > 0 && kept > 0, "placed " + placed + " cells, kept " + kept);
        CompoundTag tag = ScanService.blockEntityTag(level.getBlockEntity(testMin.offset(16, 1, 3)));
        require(namesTable(level, testMin.offset(16, 1, 3)) && (converted > 0 || !tag.contains("Items")), "the test placement's chest names its loot table: " + tag);
        require(level.getBlockEntity(testMin.offset(28, 1, 4)) instanceof Container chest && chest.getItem(0).is(Items.GOLD_INGOT)
                && chest.getItem(0).getCount() == 5, "the other chest keeps its gold");
        tag = ScanService.blockEntityTag(level.getBlockEntity(testMin.offset(4, 1, 4)));
        tag = tag.getCompound("SpawnData");
        tag = tag.getCompound("entity");
        require("minecraft:skeleton".equals(tag.getString("id")), "the test placement's spawner spawns skeletons: " + tag);
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, testBox());
        require(stands.size() == 1 && TestPlacement.tagged(stands.get(0)), "one armor stand, tagged as placed by the test: " + stands.size());
        require(level.getEntities(EntityType.IRON_GOLEM, testBox(), entity -> true).isEmpty(), "the iron golem is left out");
        // One test placement at a time.
        TestPlacement.place(server.getPlayerList().getPlayers().get(0), ID);
        require(!TestPlacement.busy(ID), "a second test placement waits for the undo");
        // A change made after the placement goes too: undo restores the whole box.
        level.setBlock(testMin.offset(10, 2, 10), Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        checks += 8;
        LOG.info("REALM_STRUCTURE_TEST_PLACE_PASS min={} placed={} kept={} containersConvertedByMods={}", testMin, placed, kept, converted);
        return null;
    }

    /** Undo puts every cell back, drops no container's items and removes the entities the placement added. */
    private static Void checkTestUndo() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        int mismatches = 0;
        String first = "";
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    BlockState before = testBefore[x + SIZE * (z + SIZE * y)], now = level.getBlockState(testMin.offset(x, y, z));
                    if (!before.equals(now) && mismatches++ == 0) first = " first at " + x + "," + y + "," + z + ": " + before + " is " + now;
                }
            }
        }
        require(mismatches == 0, mismatches + " cells differ from before the test placement" + first);
        require(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, testBox().inflate(2)).isEmpty(), "undo dropped no items");
        int tagged = 0;
        for (var entity : level.getAllEntities()) if (entity != null && TestPlacement.tagged(entity)) tagged++;
        require(tagged == 0, tagged + " entities of the test placement are left");
        require(!Files.exists(server.getWorldPath(LevelResource.ROOT).resolve("realmcontrol").resolve("test_placement")), "the undo data is removed");
        checks += 4;
        LOG.info("REALM_STRUCTURE_TEST_UNDO_PASS min={}", testMin);
        return null;
    }

    private static MarkersPage markersPage(StructureProject project, String focus) {
        JsonObject state = new JsonObject();
        state.addProperty("exists", true);
        JsonArray missing = new JsonArray();
        LootTables.missingItems(TABLE).forEach(missing::add);
        state.add("missing", missing);
        JsonObject tables = new JsonObject();
        tables.add(TABLE, state);
        return new MarkersPage(project, ProjectStore.markers(ID), tables, focus);
    }

    private static MarkersPage openMarkers() {
        MarkersPage markers = KineticGui.findPage(MarkersPage.class);
        require(markers != null, "markers page open");
        return markers;
    }

    private static void capture(String name) throws Exception {
        var mc = Minecraft.getInstance();
        Path path = Path.of(ROOT, String.format("1-%02d-structure-%s-start.png", captures, name));
        Files.createDirectories(path.getParent());
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(path);}
        captures++;
        LOG.info("REALM_STRUCTURE_CAPTURE {} page={}", name, KineticGui.currentPage() == null ? "none" : KineticGui.currentPage().getClass().getSimpleName());
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

    // ---- stage 2: natural generation ----

    private static Void checkGeneration() throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.tryParse(ID))).orElse(null);
        require(holder != null, "the published structure is registered in the new world");
        BlockPos from = server.getPlayerList().getPlayers().get(0).blockPosition();
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder), from, 64, false);
        require(found != null, "locate found the structure within 64 chunks");
        BlockPos at = found.getFirst();
        StructureStart start = level.getChunk(at.getX() >> 4, at.getZ() >> 4).getStartForStructure(holder.value());
        require(start != null && start.isValid(), "structure start at " + at);
        BoundingBox box = start.getBoundingBox();
        for (int cx = (box.minX() >> 4) - 1; cx <= (box.maxX() >> 4) + 1; cx++) {
            for (int cz = (box.minZ() >> 4) - 1; cz <= (box.maxZ() >> 4) + 1; cz++) level.getChunk(cx, cz);
        }
        int walls = 0;
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.STONE_BRICKS)) walls++;
        }
        require(walls == wallBlocks, "generated walls " + walls + " != " + wallBlocks + " at " + box);
        int centerX = (box.minX() + box.maxX()) / 2, centerZ = (box.minZ() + box.maxZ()) / 2;
        // The well is a cobblestone ring around water in the middle of the courtyard, the same in every rotation.
        require(column(level, box, centerX + 1, centerZ, Blocks.COBBLESTONE) > 0 && column(level, box, centerX, centerZ, Blocks.WATER) > 0,
                "the well stands in the courtyard");
        // A courtyard column away from the well holds nothing of the structure: the world made it.
        for (Block block : List.of(Blocks.STONE_BRICKS, Blocks.OAK_PLANKS, Blocks.COBBLESTONE)) {
            require(column(level, box, centerX + 5, centerZ + 5, block) == 0, "courtyard column holds " + block);
        }
        // Where vanilla checks it: the stored biome of that 4-block cell, not the blurred lookup getBiome does at borders.
        var biome = level.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(centerX), net.minecraft.core.QuartPos.fromBlock(box.minY() + 1),
                net.minecraft.core.QuartPos.fromBlock(centerZ));
        require(!biome.is(BiomeTags.IS_OCEAN) && !biome.is(BiomeTags.IS_DEEP_OCEAN) && !biome.is(BiomeTags.IS_RIVER) && !biome.is(BiomeTags.IS_BEACH),
                "excluded biome " + biome.unwrapKey());
        checks += 7;
        // The chest with the table fills from it: the table loaded although one entry names a missing mod's item.
        int diamonds = -1, others = 0, gold = 0;
        String spawned = "";
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) continue;
            CompoundTag tag = ScanService.blockEntityTag(blockEntity);
            if (blockEntity instanceof Container container && TABLE.equals(tag.getString("LootTable"))) {
                diamonds = 0;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (stack.is(Items.DIAMOND)) diamonds += stack.getCount();
                    else if (!stack.isEmpty()) others++;
                }
            } else if (blockEntity instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).is(Items.GOLD_INGOT)) gold += container.getItem(slot).getCount();
            } else if (tag.contains("SpawnData")) {
                tag = tag.getCompound("SpawnData");
                tag = tag.getCompound("entity");
                spawned = tag.getString("id");
            }
        }
        // ContentStudio's global chest additions may add more; the table's three diamonds must be there.
        require(diamonds >= 3, "the chest filled from its table: " + diamonds + " diamonds, " + others + " other stacks");
        require(gold == 5, "the other chest kept its gold: " + gold);
        require("minecraft:skeleton".equals(spawned), "the spawner spawns skeletons: " + spawned);
        checks += 3;
        generated = box;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        command(server, "gamemode creative " + player.getGameProfile().getName());
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
        command(server, "tp " + player.getGameProfile().getName() + " " + centerX + " " + (box.maxY() + 4) + " " + centerZ);
        LOG.info("REALM_STRUCTURE_GENERATION_PASS at={} box={} walls={} biome={}", at, box, walls, biome.unwrapKey().map(key -> key.location().toString()).orElse("?"));
        return null;
    }

    private static boolean generatedEntitiesLoaded() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        for (int cx = generated.minX() >> 4; cx <= generated.maxX() >> 4; cx++) {
            for (int cz = generated.minZ() >> 4; cz <= generated.maxZ() >> 4; cz++) {
                if (!level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cx, cz))) return false;
            }
        }
        return true;
    }

    /** The armor stand came along with a UUID of its own; the iron golem was left out. */
    private static Void checkGeneratedEntities() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        AABB area = new AABB(generated.minX(), generated.minY(), generated.minZ(), generated.maxX() + 1, generated.maxY() + 1, generated.maxZ() + 1);
        List<ArmorStand> stands = level.getEntitiesOfClass(ArmorStand.class, area);
        require(stands.size() == 1 && !stands.get(0).getUUID().equals(sourceStand), "the generated structure has its armor stand: " + stands.size());
        require(level.getEntities(EntityType.IRON_GOLEM, area, entity -> true).isEmpty(), "the generated structure has no iron golem");
        checks += 2;
        LOG.info("REALM_STRUCTURE_ENTITIES_PASS stands={}", stands.size());
        return null;
    }

    /**
     * The new spacing is in effect. The nearest medium structure is found with all its tiles, and a second one whose
     * turn swaps the axes when the first does not (and the other way round), so the comparison covers both.
     */
    private static Void locateMedium() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        var sets = server.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);
        Holder<StructureSet> set = sets.getHolder(ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.tryParse(MEDIUM))).orElse(null);
        require(set != null && set.value().placement() instanceof RandomSpreadStructurePlacement spread && spread.spacing() == MEDIUM_SPACING,
                "the spacing changed in the structure panel is in effect after entering the world");
        var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Holder<Structure> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.tryParse(MEDIUM))).orElse(null);
        require(holder != null, "the medium structure is registered");
        BlockPos from = server.getPlayerList().getPlayers().get(0).blockPosition();
        StructureStart first = mediumAt(level, holder, from, 100);
        require(first != null, "locate found the medium structure within 100 chunks");
        List<StructureStart> starts = new ArrayList<>(List.of(first));
        boolean firstTurned = turned(first);
        int apart = MEDIUM_SPACING * 16;
        BlockPos center = first.getChunkPos().getWorldPosition();
        for (int[] offset : new int[][]{{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {2, 0}, {0, 2}, {-2, 0}, {0, -2},
                {2, 2}, {-2, 2}, {2, -2}, {-2, -2}, {3, 0}, {0, 3}, {-3, 0}, {0, -3}}) {
            StructureStart other = mediumAt(level, holder, center.offset(offset[0] * apart, 0, offset[1] * apart), 2);
            if (other != null && !other.getChunkPos().equals(first.getChunkPos()) && turned(other) != firstTurned) {
                starts.add(other);
                break;
            }
        }
        require(starts.size() == 2, "no second medium structure turned the other way near " + center);
        List<ChunkPos> chunks = new ArrayList<>();
        for (StructureStart start : starts) {
            require(start.getPieces().size() == 16 && start.getPieces().get(0) instanceof PoolElementStructurePiece,
                    "the frame and every tile were placed: " + start.getPieces().size() + " pieces");
            BoundingBox box = start.getBoundingBox();
            require(box.getXSpan() <= MEDIUM_SIZE && box.getZSpan() <= MEDIUM_SIZE && Math.max(box.getXSpan(), box.getZSpan()) >= MEDIUM_SIZE - 1, "medium box " + box);
            for (int cx = (box.minX() >> 4) - 1; cx <= (box.maxX() >> 4) + 1; cx++) {
                for (int cz = (box.minZ() >> 4) - 1; cz <= (box.maxZ() >> 4) + 1; cz++) chunks.add(new ChunkPos(cx, cz));
            }
            LOG.info("REALM_STRUCTURE_STEP medium structure at {} box {} rotation {}", start.getChunkPos(), box,
                    ((PoolElementStructurePiece) start.getPieces().get(0)).getRotation());
        }
        mediumStarts = starts;
        mediumChunks = chunks;
        mediumChunk = 0;
        checks += 5;
        return null;
    }

    @javax.annotation.Nullable
    private static StructureStart mediumAt(ServerLevel level, Holder<Structure> holder, BlockPos from, int radius) {
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder), from, radius, false);
        if (found == null) return null;
        BlockPos at = found.getFirst();
        StructureStart start = level.getChunk(at.getX() >> 4, at.getZ() >> 4).getStartForStructure(holder.value());
        return start != null && start.isValid() ? start : null;
    }

    /** Whether the structure is turned a quarter either way, which swaps its x and z. */
    private static boolean turned(StructureStart start) {
        Rotation rotation = ((PoolElementStructurePiece) start.getPieces().get(0)).getRotation();
        return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
    }

    /** Generates a few more of the medium structure's chunks; true while some are left. */
    private static boolean generateMediumChunks() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        for (int i = 0; i < 6 && mediumChunk < mediumChunks.size(); i++) {
            ChunkPos chunkPos = mediumChunks.get(mediumChunk++);
            level.getChunk(chunkPos.x, chunkPos.z);
        }
        return mediumChunk < mediumChunks.size();
    }

    /**
     * Every cell the templates list is in the world as the draft has it, turned with the structure. Two things vanilla
     * does after placing are allowed: waterlogging a block that lands in water, features of later chunks growing into
     * air cells (lichen, plants), and re-shaping a block whose position
     * noise or a carver marked for post-processing (where water was) against the blocks around it, so a fence there
     * may join the terrain beside it; the block must then be exactly the draft's block re-shaped that way. Cells
     * inside another structure that overlaps are not compared, but three in four must be: inside its pieces' boxes, and
     * at any height in the columns of its terrain-matching pieces (village streets), which follow the surface they
     * find, our floor included. In the layers set into the ground, mods' terrain features may still change a few
     * blocks after the structure is placed (the 1.20.1 pack turns some floor into stone); there at most 2 in 100 of
     * a tile's cells may differ, while a missing, shifted or turned tile changes its whole patterned floor. Above
     * the ground every cell must match. Nothing of the cut-outs is there, and the chest on a seam has its emeralds.
     */
    private static Void checkMediumGeneration() throws Exception {
        for (StructureStart start : mediumStarts) checkMediumGeneration(start);
        return null;
    }

    private static void checkMediumGeneration(StructureStart mediumStart) throws Exception {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        StructureProject project = ProjectStore.load(MEDIUM);
        Cutouts cutouts = ProjectStore.cutouts(MEDIUM);
        PoolElementStructurePiece frame = (PoolElementStructurePiece) mediumStart.getPieces().get(0);
        Rotation rotation = frame.getRotation();
        BlockPos at = frame.getPosition();
        BoundingBox box = mediumStart.getBoundingBox();
        Structure medium = mediumStart.getStructure();
        List<BoundingBox> others = new ArrayList<>();
        // Every piece of other structures near this one, named, to say what a differing cell is next to.
        List<String> nearbyNames = new ArrayList<>();
        List<BoundingBox> nearby = new ArrayList<>();
        BoundingBox around = box.inflatedBy(32);
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Set<StructureStart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ChunkPos pos : mediumChunks) {
            for (StructureStart other : level.structureManager().startsForStructure(pos, structure -> structure != medium)) {
                if (!seen.add(other)) continue;
                for (StructurePiece piece : other.getPieces()) {
                    BoundingBox pieceBox = piece.getBoundingBox();
                    if (piece instanceof PoolElementStructurePiece pool && pool.getElement().getProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING) {
                        pieceBox = new BoundingBox(pieceBox.minX(), box.minY(), pieceBox.minZ(), pieceBox.maxX(), box.maxY(), pieceBox.maxZ());
                    }
                    if (pieceBox.intersects(box)) others.add(pieceBox);
                    if (piece.getBoundingBox().intersects(around)) {
                        nearby.add(piece.getBoundingBox());
                        nearbyNames.add(String.valueOf(structures.getKey(other.getStructure())));
                    }
                }
            }
        }
        int compared = 0, skipped = 0, mismatches = 0, reshaped = 0, groundChanged = 0, featureChanged = 0;
        String first = "", groundFirst = "", featureFirst = "";
        for (int tz = 0; tz < project.tilesZ(); tz++) {
            for (int tx = 0; tx < project.tilesX(); tx++) {
                Tile tile = ProjectStore.readTile(MEDIUM, tx, tz);
                int ox = tx * project.tileSize, oz = tz * project.tileSize;
                boolean[] keep = Publisher.cellsToWrite(project, tile, cutouts, ox, oz);
                int groundCompared = 0, groundDiffers = 0, tileCompared = 0, featureDiffers = 0;
                for (int y = 0; y < tile.sizeY; y++) {
                    for (int z = 0; z < tile.sizeZ; z++) {
                        for (int x = 0; x < tile.sizeX; x++) {
                            if (!keep[tile.cell(x, y, z)]) continue;
                            BlockPos world = at.offset(StructureTemplate.transform(new BlockPos(ox + x, y, oz + z), Mirror.NONE, rotation, BlockPos.ZERO));
                            if (others.stream().anyMatch(other -> other.isInside(world))) {
                                skipped++;
                                continue;
                            }
                            compared++;
                            tileCompared++;
                            boolean ground = y < project.embed;
                            if (ground) groundCompared++;
                            BlockState expected = BlockStates.parse(tile.state(x, y, z)).rotate(rotation), actual = level.getBlockState(world);
                            if (dry(actual).equals(dry(expected))) continue;
                            if (dry(actual).equals(dry(Block.updateFromNeighbourShapes(expected, level, world)))
                                    || decorated(expected, actual, level, world)) {
                                reshaped++;
                            } else if (ground) {
                                groundDiffers++;
                                if (groundFirst.isEmpty()) groundFirst = " (first " + expected + " is " + actual + " at " + world + ")";
                            } else if (rock(actual)) {
                                // A rock or boulder of the 1.20.1 pack's terrain mods, placed from a neighbouring chunk after the
                                // structure, as it would be into a vanilla structure; medium structures are plain vanilla ones.
                                featureDiffers++;
                                if (featureFirst.isEmpty()) featureFirst = " (first " + expected + " is " + actual + " at " + world + ")";
                            } else if (mismatches++ < 6) {
                                String near = "nothing";
                                int best = Integer.MAX_VALUE;
                                for (int i = 0; i < nearby.size(); i++) {
                                    BoundingBox piece = nearby.get(i);
                                    int dx = Math.max(0, Math.max(piece.minX() - world.getX(), world.getX() - piece.maxX()));
                                    int dz = Math.max(0, Math.max(piece.minZ() - world.getZ(), world.getZ() - piece.maxZ()));
                                    if (Math.max(dx, dz) < best) {
                                        best = Math.max(dx, dz);
                                        near = nearbyNames.get(i) + " " + best + " blocks away";
                                    }
                                }
                                first += "; at " + (ox + x) + "," + y + "," + (oz + z) + " (" + world + "): " + expected + " is " + actual + ", nearest other structure piece: " + near;
                            }
                        }
                    }
                }
                require(groundDiffers * 50 <= groundCompared, groundDiffers + " of " + groundCompared + " ground cells of tile " + tx + "," + tz + " differ" + groundFirst);
                require(featureDiffers * 50 <= tileCompared, featureDiffers + " of " + tileCompared + " cells of tile " + tx + "," + tz + " turned to natural stone" + featureFirst);
                groundChanged += groundDiffers;
                featureChanged += featureDiffers;
            }
        }
        require(mismatches == 0, mismatches + " of " + compared + " generated cells differ from the draft (rotation " + rotation + ")" + first);
        require(compared * 4L >= (compared + skipped) * 3L, "only " + compared + " of " + (compared + skipped) + " cells could be compared: " + others.size() + " pieces of other structures overlap");
        int cutBlocks = 0;
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.LAPIS_BLOCK) || state.is(Blocks.EMERALD_BLOCK)) cutBlocks++;
        }
        require(cutBlocks == 0, cutBlocks + " blocks of the cut-outs generated");
        BlockPos chest = at.offset(StructureTemplate.transform(new BlockPos(64, 1, 100), Mirror.NONE, rotation, BlockPos.ZERO));
        if (others.stream().noneMatch(other -> other.isInside(chest))) {
            require(level.getBlockEntity(chest) instanceof Container container && container.getItem(0).is(Items.EMERALD) && container.getItem(0).getCount() == 7,
                    "the chest on the seam has its emeralds");
        }
        checks += 4;
        LOG.info("REALM_STRUCTURE_MEDIUM_GENERATION_PASS box={} rotation={} compared={} reshaped={} groundChanged={}{} rockChanged={}{} skipped={} overlapping={}", box, rotation,
                compared, reshaped, groundChanged, groundFirst, featureChanged, featureFirst, skipped, others.size());
    }

    /**
     * The unknown-type files were left out and the world loaded; /locate (the command too) finds the mega structure,
     * whose height is the one the structure computes from the noise. A second copy is picked whose turn swaps the axes
     * when the first does not, and the first copy's chunks are queued.
     */
    private static Void locateMega() throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server.overworld();
        var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        require(registry.getHolder(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.tryParse("realmcontrol:validation_unknown_type"))).isEmpty(),
                "the structure of a type nothing registers is left out, and the world loaded");
        Holder<Structure> holder = registry.getHolder(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.tryParse(MEGA))).orElse(null);
        require(holder != null && holder.value() instanceof MegaStructure, "the mega structure is registered");
        BlockPos from = server.getPlayerList().getPlayers().get(0).blockPosition();
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder), from, 100, false);
        require(found != null, "locate found the mega structure within 100 regions");
        int located = server.getCommands().getDispatcher().execute("locate structure " + MEGA, server.createCommandSourceStack().withSuppressedOutput());
        require(located > 0, "/locate structure " + MEGA + " found it");
        ChunkPos anchor = new ChunkPos(found.getFirst());
        StructureStart start = level.getChunk(MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor)).getStartForStructure(holder.value());
        require(start != null && start.isValid(), "the mega structure starts at its anchor " + anchor);
        long seed = level.getSeed();
        MegaStructure mega = (MegaStructure) holder.value();
        Rotation rotation = MegaLayout.rotation(seed, MegaLayout.chunkX(anchor), MegaLayout.chunkZ(anchor));
        int bottom = start.getBoundingBox().minY();
        require(bottom == mega.bottom(level.getChunkSource().getGenerator(), level, level.getChunkSource().randomState(), anchor, rotation),
                "every part of the instance agrees on its height");
        megaA = new MegaInstance(anchor, rotation, bottom);
        boolean turned = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
        megaB = null;
        MegaInstance fallback = null;
        int apart = MEGA_SPACING * 16;
        // Another copy turned the other way, so both turns are compared; failing that (few regions suit the biomes), any other copy.
        search:
        for (int ring = 1; ring <= 3; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var other = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder),
                            anchor.getWorldPosition().offset(dx * apart, 0, dz * apart), 1, false);
                    if (other == null) continue;
                    ChunkPos otherAnchor = new ChunkPos(other.getFirst());
                    if (otherAnchor.equals(anchor)) continue;
                    Rotation otherRotation = MegaLayout.rotation(seed, MegaLayout.chunkX(otherAnchor), MegaLayout.chunkZ(otherAnchor));
                    MegaInstance candidate = new MegaInstance(otherAnchor, otherRotation,
                            mega.bottom(level.getChunkSource().getGenerator(), level, level.getChunkSource().randomState(), otherAnchor, otherRotation));
                    if (fallback == null) fallback = candidate;
                    if ((otherRotation == Rotation.CLOCKWISE_90 || otherRotation == Rotation.COUNTERCLOCKWISE_90) != turned) {
                        megaB = candidate;
                        break search;
                    }
                }
            }
        }
        if (megaB == null) megaB = fallback;
        require(megaB != null, "no second mega copy near " + anchor);
        queueChunks(megaA);
        generatedChunks = 0;
        checks += 6;
        LOG.info("REALM_STRUCTURE_STEP mega copies at {} ({}) and {} ({}), generating {} chunks", megaA.anchor(), megaA.rotation(), megaB.anchor(), megaB.rotation(),
                pendingChunks.size());
        return null;
    }

    private static MegaLayout megaLayout() {
        return new MegaLayout(MEGA_SIZE, MEGA_HEIGHT, MEGA_SIZE);
    }

    private static void queueChunks(MegaInstance instance) {
        BoundingBox box = megaLayout().footprint(instance.anchor(), instance.bottom(), instance.rotation());
        for (int cx = (box.minX() >> 4) - 1; cx <= (box.maxX() >> 4) + 1; cx++) {
            for (int cz = (box.minZ() >> 4) - 1; cz <= (box.maxZ() >> 4) + 1; cz++) pendingChunks.add(new ChunkPos(cx, cz));
        }
    }

    /**
     * Keeps up to 32 queued chunks forced until they are fully generated; true while any are left. No more are forced
     * while the server holds many chunks, so it unloads the finished ones first and the game's memory stays bounded.
     */
    private static boolean pumpChunks() {
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        forcedChunks.removeIf(chunkPos -> {
            if (!level.getChunkSource().hasChunk(chunkPos.x, chunkPos.z)) return false;
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
            generatedChunks++;
            return true;
        });
        while (forcedChunks.size() < FORCED && !pendingChunks.isEmpty() && level.getChunkSource().getLoadedChunksCount() < MAX_LOADED) {
            ChunkPos chunkPos = pendingChunks.poll();
            try {
                level.setChunkForced(chunkPos.x, chunkPos.z, true);
            } catch (IllegalStateException unloading) {
                // 1.20.1 loads a chunk as it is forced; one caught while it unloads fails ("not there: UNLOADED"). Undo and retry it later.
                level.setChunkForced(chunkPos.x, chunkPos.z, false);
                pendingChunks.addLast(chunkPos);
                break;
            }
            forcedChunks.add(chunkPos);
        }
        long now = System.currentTimeMillis();
        if (now - lastProgress > 30_000L) {
            lastProgress = now;
            LOG.info("REALM_STRUCTURE_STEP chunks generated {}, forced {}, queued {}, loaded {}, memory used {} MB", generatedChunks, forcedChunks.size(),
                    pendingChunks.size(), level.getChunkSource().getLoadedChunksCount(),
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576L);
        }
        return !forcedChunks.isEmpty() || !pendingChunks.isEmpty();
    }

    /** The flight starts 700 blocks west of the second copy's middle, 80 blocks above its bottom. */
    private static Void startFlight() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        BlockPos middle = megaB.anchor().getWorldPosition();
        flightX = middle.getX() - 700;
        flightZ = middle.getZ();
        flightY = megaB.bottom() + 80;
        flightStep = 0;
        MegaStats.reset();
        tickNanos.clear();
        measuring = true;
        command(server, "tp " + server.getPlayerList().getPlayers().get(0).getGameProfile().getName() + " " + flightX + " " + flightY + " " + flightZ + " -90 30");
        return null;
    }

    /** 16 blocks further east; true while the flight goes on (88 steps, 1408 blocks). */
    private static boolean flyStep() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        flightStep++;
        command(server, "tp " + server.getPlayerList().getPlayers().get(0).getGameProfile().getName() + " " + (flightX + 16 * flightStep) + " " + flightY + " " + flightZ + " -90 30");
        return flightStep < 88;
    }

    /** Reports the server's tick times during the flight and the time spent placing tiles, then queues the second copy. */
    private static Void endFlight() {
        measuring = false;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        List<Long> ticks;
        synchronized (tickNanos) {
            ticks = new ArrayList<>(tickNanos);
        }
        Collections.sort(ticks);
        double average = ticks.stream().mapToLong(Long::longValue).average().orElse(0D) / 1e6;
        double p95 = ticks.isEmpty() ? 0D : ticks.get(Math.min(ticks.size() - 1, (int) (ticks.size() * 0.95))) / 1e6;
        double slowest = ticks.isEmpty() ? 0D : ticks.get(ticks.size() - 1) / 1e6;
        double tps = Math.min(20D, 1000D / Math.max(50D, average));
        long parts = MegaStats.parts();
        double perPart = parts == 0 ? 0D : MegaStats.nanos() / 1e6 / parts;
        String report = String.format(java.util.Locale.ROOT,
                "flight 1408 blocks at 32 blocks/s, view distance %d: %d ticks, average %.1f ms, 95%% %.1f ms, slowest %.1f ms, TPS %.1f; "
                        + "%d tile parts placed, %.2f ms each on average, slowest %.1f ms",
                server.getPlayerList().getViewDistance(), ticks.size(), average, p95, slowest, tps, parts, perPart, MegaStats.slowestNanos() / 1e6);
        details.append("MEGA_TPS ").append(report).append(System.lineSeparator());
        LOG.info("REALM_STRUCTURE_MEGA_TPS {}", report);
        require(parts > 0, "tiles were placed during the flight");
        queueChunks(megaB);
        generatedChunks = 0;
        checks++;
        return null;
    }

    /** The comparison of one mega copy, a tile row at a time. */
    private static final class MegaCheck {
        final MegaInstance instance;
        final String label;
        final long millis;
        final Set<StructureStart> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int row, compared, skipped, mismatches, reshaped, groundChanged, clearColumns, unclearedColumns, standing, founded, cutBlocks, startsInside, overlapping, badTiles;
        String first = "", startedInside = "";
        final StringBuilder groundIssues = new StringBuilder();

        MegaCheck(MegaInstance instance, String label, long millis) {
            this.instance = instance;
            this.label = label;
            this.millis = millis;
        }

        MegaInstance instance() {
            return instance;
        }
    }

    private static MegaCheck megaCheck;

    /**
     * One row of tiles of a mega copy, once its chunks generated. Other structures are found through the chunks of
     * each tile (every piece reaching into a chunk is referenced there): none may start inside the copy, and their
     * pieces' cells are not compared. Every cell the tiles list matches the draft as for the medium structure; every
     * eighth column is cleared above its top block and, if it stands on a block, has a foundation below; no lapis or
     * emerald of the cut-outs is anywhere. True while rows are left.
     */
    private static boolean continueMegaCheck() throws Exception {
        MegaCheck check = megaCheck;
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        StructureProject project = ProjectStore.load(MEGA);
        Cutouts cutouts = ProjectStore.cutouts(MEGA);
        MegaLayout layout = megaLayout();
        ChunkPos anchor = check.instance.anchor();
        Rotation rotation = check.instance.rotation();
        int bottom = check.instance.bottom();
        BoundingBox box = layout.footprint(anchor, bottom, rotation);
        var structures = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int tz = check.row++;
        for (int tx = 0; tx < layout.tilesX(); tx++) {
            BoundingBox tileBox = layout.tileBox(anchor, bottom, rotation, tx, tz);
            List<BoundingBox> others = new ArrayList<>();
            for (int cx = tileBox.minX() >> 4; cx <= tileBox.maxX() >> 4; cx++) {
                for (int cz = tileBox.minZ() >> 4; cz <= tileBox.maxZ() >> 4; cz++) {
                    for (StructureStart other : level.structureManager().startsForStructure(new ChunkPos(cx, cz), structure -> !(structure instanceof MegaStructure))) {
                        if (check.seen.add(other)) {
                            BlockPos origin = other.getChunkPos().getWorldPosition();
                            if (box.isInside(new BlockPos(origin.getX(), box.minY(), origin.getZ()))) {
                                check.startsInside++;
                                check.startedInside = " " + structures.getKey(other.getStructure()) + " at " + other.getChunkPos();
                            }
                        }
                        for (StructurePiece piece : other.getPieces()) {
                            BoundingBox pieceBox = piece.getBoundingBox();
                            if (piece instanceof PoolElementStructurePiece pool && pool.getElement().getProjection() == StructureTemplatePool.Projection.TERRAIN_MATCHING) {
                                pieceBox = new BoundingBox(pieceBox.minX(), box.minY(), pieceBox.minZ(), pieceBox.maxX(), box.maxY(), pieceBox.maxZ());
                            }
                            if (pieceBox.intersects(tileBox)) others.add(pieceBox);
                        }
                    }
                }
            }
            check.overlapping += others.size();
            for (BlockPos pos : BlockPos.betweenClosed(tileBox.minX(), tileBox.minY(), tileBox.minZ(), tileBox.maxX(), tileBox.maxY(), tileBox.maxZ())) {
                BlockState state = level.getBlockState(pos);
                if (state.is(Blocks.LAPIS_BLOCK) || state.is(Blocks.EMERALD_BLOCK)) check.cutBlocks++;
            }
            Tile tile = ProjectStore.readTile(MEGA, tx, tz);
            int ox = tx * MegaLayout.TILE, oz = tz * MegaLayout.TILE;
            boolean[] keep = Publisher.cellsToWrite(project, tile, cutouts, ox, oz);
            int groundCompared = 0, groundDiffers = 0;
            StringBuilder groundExamples = new StringBuilder();
            for (int z = 0; z < tile.sizeZ; z++) {
                for (int x = 0; x < tile.sizeX; x++) {
                    int top = -1;
                    for (int y = 0; y < tile.sizeY; y++) {
                        if (!keep[tile.cell(x, y, z)]) continue;
                        BlockPos world = layout.world(anchor, bottom, rotation, ox + x, y, oz + z);
                        if (!tile.state(x, y, z).equals(BlockStates.AIR)) top = y;
                        if (others.stream().anyMatch(other -> other.isInside(world))) {
                            check.skipped++;
                            continue;
                        }
                        check.compared++;
                        boolean ground = y < project.embed;
                        if (ground) groundCompared++;
                        BlockState expected = BlockStates.parse(tile.state(x, y, z)).rotate(rotation), actual = level.getBlockState(world);
                        if (dry(actual).equals(dry(expected))) continue;
                        if (dry(actual).equals(dry(Block.updateFromNeighbourShapes(expected, level, world))) || decorated(expected, actual, level, world)) {
                            check.reshaped++;
                        } else if (ground) {
                            if (groundDiffers++ < 3) {
                                groundExamples.append(' ').append(world.toShortString()).append(' ').append(actual.getBlock()).append(" in ")
                                        .append(level.getBiome(world).unwrapKey().map(key -> key.location().toString()).orElse("?"));
                            }
                        } else if (check.mismatches++ < 6) {
                            check.first += "; at " + (ox + x) + "," + y + "," + (oz + z) + " (" + world + "): " + expected + " is " + actual;
                        }
                    }
                    // Every eighth column: cleared above its top block, filled below if it stands on a block.
                    if ((x + z) % 8 != 0 || top < 0) continue;
                    BlockPos column = layout.world(anchor, bottom, rotation, ox + x, 0, oz + z);
                    if (others.stream().anyMatch(other -> other.isInside(column))) continue;
                    check.clearColumns++;
                    for (int y = top + 1; y <= top + 6; y++) {
                        BlockState above = level.getBlockState(cursor.set(column.getX(), bottom + y, column.getZ()));
                        if (!above.isAir() && !above.canBeReplaced() && above.getFluidState().isEmpty()) {
                            check.unclearedColumns++;
                            break;
                        }
                    }
                    if (keep[tile.cell(x, 0, z)] && !tile.state(x, 0, z).equals(BlockStates.AIR)) {
                        check.standing++;
                        BlockState below = level.getBlockState(cursor.set(column.getX(), bottom - 1, column.getZ()));
                        if (!below.isAir() && below.getFluidState().isEmpty()) check.founded++;
                    }
                }
            }
            if (groundDiffers * 50 > groundCompared) {
                check.badTiles++;
                if (check.groundIssues.length() < 1500) {
                    check.groundIssues.append("; tile ").append(tx).append(',').append(tz).append(": ").append(groundDiffers).append(" of ").append(groundCompared)
                            .append(groundExamples);
                }
            }
            check.groundChanged += groundDiffers;
        }
        return check.row < layout.tilesZ();
    }

    /**
     * The totals of a mega copy: nothing differs, nine in ten cells were compared, no other structure starts inside,
     * the ground is cleared above and the foundation filled below, nothing of the cut-outs generated, the courtyard
     * keeps the natural ground (no foundation block in it, and no column whose ground above the floor level, where
     * vanilla's noise puts it, is gone entirely, as clearing would leave it; caves only take part of a column) and
     * the chest on a group seam has its emeralds.
     */
    private static Void finishMegaCheck() {
        MegaCheck check = megaCheck;
        ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
        MegaLayout layout = megaLayout();
        ChunkPos anchor = check.instance.anchor();
        Rotation rotation = check.instance.rotation();
        int bottom = check.instance.bottom();
        String label = check.label;
        require(check.startsInside == 0, check.startsInside + " other structures start inside mega copy " + label + ":" + check.startedInside);
        require(check.badTiles == 0, check.badTiles + " tiles of mega copy " + label + " have more than 2 in 100 ground cells changed" + check.groundIssues);
        require(check.mismatches == 0, check.mismatches + " of " + check.compared + " cells of mega copy " + label + " differ from the draft (rotation " + rotation + ")" + check.first);
        require(check.compared * 4L >= (check.compared + check.skipped) * 3L, "only " + check.compared + " of " + (check.compared + check.skipped) + " mega cells could be compared");
        require(check.unclearedColumns * 100 <= check.clearColumns, check.unclearedColumns + " of " + check.clearColumns + " mega columns have ground left above them");
        require(check.founded * 100 >= check.standing * 99, "only " + check.founded + " of " + check.standing + " mega columns stand on a foundation");
        require(check.cutBlocks == 0, check.cutBlocks + " blocks of the mega cut-outs generated");
        int cleared = 0, groundCells = 0, foundation = 0, sampled = 0;
        var generator = level.getChunkSource().getGenerator();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = 600; x <= 759; x += 2) {
            for (int z = 200; z <= 359; z += 2) {
                BlockPos column = layout.world(anchor, bottom, rotation, x, 0, z);
                sampled++;
                var noise = generator.getBaseColumn(column.getX(), column.getZ(), level, level.getChunkSource().randomState());
                for (int y = bottom - 64; y < bottom + MEGA_HEIGHT; y++) {
                    if (level.getBlockState(cursor.set(column.getX(), y, column.getZ())).is(Blocks.WAXED_COPPER_BLOCK)) foundation++;
                }
                int noiseTop = bottom;
                for (int y = bottom + 64; y > bottom; y--) {
                    if (noise.getBlock(y).isSolid()) {
                        noiseTop = y;
                        break;
                    }
                }
                if (noiseTop <= bottom) continue;
                groundCells++;
                boolean any = false;
                for (int y = bottom + 1; y <= noiseTop && !any; y++) any = level.getBlockState(cursor.set(column.getX(), y, column.getZ())).isSolid();
                if (!any) cleared++;
            }
        }
        require(foundation == 0, foundation + " foundation blocks in the mega courtyard");
        require(cleared * 50 <= groundCells, cleared + " of " + groundCells + " mega courtyard columns with ground above the floor level lost all of it");
        BlockPos chest = layout.world(anchor, bottom, rotation, 384, 1, 700);
        require(level.getBlockEntity(chest) instanceof Container container && container.getItem(0).is(Items.EMERALD) && container.getItem(0).getCount() == 7,
                "the chest on the mega group seam has its emeralds");
        checks += 8;
        String report = String.format(java.util.Locale.ROOT, "%s at %s rotation %s: %d chunks in %.0f s, %d cells compared (%d reshaped, %d ground changed, %d skipped, "
                        + "%d overlapping pieces), courtyard %d columns (%d of %d with ground above the floor emptied), cleared %d/%d, foundation %d/%d", label, anchor, rotation,
                generatedChunks, check.millis / 1000D, check.compared, check.reshaped, check.groundChanged, check.skipped, check.overlapping, sampled, cleared, groundCells,
                check.clearColumns - check.unclearedColumns,
                check.clearColumns, check.founded, check.standing);
        details.append("MEGA ").append(report).append(System.lineSeparator());
        LOG.info("REALM_STRUCTURE_MEGA_GENERATION_PASS {}", report);
        return null;
    }

    /**
     * An air cell of the structure that a vanilla feature of a chunk decorated later grew into, as it grows into any
     * structure's air: lichen, plants, snow, things without a collision box.
     */
    private static boolean decorated(BlockState expected, BlockState actual, ServerLevel level, BlockPos pos) {
        return expected.isAir() && actual.getCollisionShape(level, pos).isEmpty();
    }

    /** Natural ground a terrain feature leaves: stone of the overworld, dirt, sand, gravel, mossy cobblestone. */
    private static boolean rock(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(Blocks.SAND)
                || state.is(Blocks.GRAVEL) || state.is(Blocks.MOSSY_COBBLESTONE);
    }

    private static BlockState dry(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) ? state.setValue(BlockStateProperties.WATERLOGGED, false) : state;
    }

    /** Whether the panel shows the mega structure's preview and the test structures' pictures. */
    private static boolean panelLoaded(WorldGenPage panel) throws Exception {
        if (panel == null) return false;
        var preview = WorldGenPage.class.getDeclaredField("previewResult");
        preview.setAccessible(true);
        return preview.get(panel) != null && dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient.get(ID) != null
                && dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient.get(MEDIUM) != null
                && dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient.get(MEGA) != null;
    }

    /** Shows the test structures in the structure panel, the mega one selected. */
    private static void focusPanel(WorldGenPage panel) throws Exception {
        for (String[] field : new String[][]{{"structureSearch", "validation_"}, {"selectedStructureId", MEGA}}) {
            var declared = WorldGenPage.class.getDeclaredField(field[0]);
            declared.setAccessible(true);
            declared.set(panel, field[1]);
        }
        panel.rebuild();
    }

    private static int column(ServerLevel level, BoundingBox box, int x, int z, Block block) {
        int count = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) if (level.getBlockState(new BlockPos(x, y, z)).is(block)) count++;
        return count;
    }

    // ---- helpers ----

    private static void removeFiles() throws Exception {
        ProjectStore.delete(ID);
        ProjectStore.delete(MEDIUM);
        ProjectStore.delete(MEGA);
        removePublished(Publisher.packFolder());
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) removePublished(server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(Publisher.PACK));
        // A test placement an interrupted run left: its record would refuse this run's.
        if (server != null) ProjectStore.deleteTree(server.getWorldPath(LevelResource.ROOT).resolve("realmcontrol").resolve("test_placement"));
        Files.deleteIfExists(Publisher.exportFolder().resolve("realmcontrol_validation_courtyard.zip"));
        ProjectStore.deleteTree(Publisher.exportFolder().resolve(".staging").resolve("realmcontrol_validation_courtyard"));
        for (Path folder : List.of(Publisher.exportFolder().resolve(".staging"), Publisher.exportFolder())) {
            if (Files.isDirectory(folder)) {
                try (var entries = Files.list(folder)) {
                    if (entries.findAny().isEmpty()) Files.delete(folder);
                }
            }
        }
    }

    /**
     * Removes what the test publishes from a pack folder and the folders that leaves empty; the pack goes too when
     * the test was all it held.
     */
    private static void removePublished(Path pack) throws Exception {
        Path data = pack.resolve("data").resolve("realmcontrol");
        for (String file : List.of("structure/validation_courtyard.nbt", "structures/validation_courtyard.nbt", "worldgen/template_pool/validation_courtyard/start.json",
                "worldgen/structure/validation_courtyard.json", "worldgen/structure_set/validation_courtyard.json", "tags/worldgen/biome/has_structure/validation_courtyard.json",
                "structure/validation_medium.nbt", "structures/validation_medium.nbt", "worldgen/structure/validation_medium.json",
                "worldgen/structure_set/validation_medium.json", "tags/worldgen/biome/has_structure/validation_medium.json",
                "worldgen/structure/validation_mega.json", "worldgen/structure_set/validation_mega.json", "tags/worldgen/biome/has_structure/validation_mega.json",
                "worldgen/structure/validation_unknown_type.json", "worldgen/structure_set/validation_unknown_type.json")) {
            Files.deleteIfExists(data.resolve(file));
        }
        for (String folder : List.of("structure/validation_medium", "structures/validation_medium", "worldgen/template_pool/validation_medium",
                "structure/validation_mega", "structures/validation_mega", "worldgen/template_pool/validation_mega")) {
            ProjectStore.deleteTree(data.resolve(folder));
        }
        // Every run's tables, also those of runs that were interrupted.
        ProjectStore.deleteTree(data.resolve("loot_tables/chests/validation_courtyard"));
        ProjectStore.deleteTree(data.resolve("loot_table/chests/validation_courtyard"));
        if (!Files.isDirectory(pack)) return;
        try (var folders = Files.walk(pack)) {
            for (Path folder : folders.filter(Files::isDirectory).sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (folder.equals(pack)) continue;
                try (var entries = Files.list(folder)) {
                    if (entries.findAny().isEmpty()) Files.delete(folder);
                }
            }
        }
        try (var files = Files.walk(pack)) {
            if (files.noneMatch(file -> Files.isRegularFile(file) && !file.getFileName().toString().equals("pack.mcmeta"))) ProjectStore.deleteTree(pack);
        }
    }

    private static void command(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static <T> T onServer(Callable<T> task) throws Exception {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server.submit(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).get();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Throwable error) {
        if (finished) return;
        finished = true;
        Minecraft mc = Minecraft.getInstance();
        measuring = false;
        // Chunks the test forced stay forced in the world's saved data otherwise.
        if (!forcedChunks.isEmpty() && mc.getSingleplayerServer() != null) {
            try {
                onServer(() -> {
                    ServerLevel level = mc.getSingleplayerServer().overworld();
                    for (ChunkPos chunkPos : forcedChunks) level.setChunkForced(chunkPos.x, chunkPos.z, false);
                    forcedChunks.clear();
                    return null;
                });
            } catch (Exception e) {
                LOG.warn("REALM_STRUCTURE could not release the forced chunks", e);
            }
        }
        if (error != null) {
            failures++;
            LOG.error("REALM_STRUCTURE_ERROR step={} page={}", step, page, error);
            details.append(error).append(System.lineSeparator());
        }
        try {
            removeFiles();
        } catch (Exception e) {
            failures++;
            LOG.error("REALM_STRUCTURE_CLEANUP_FAIL", e);
        }
        boolean restored = true;
        try {
            if (originalLanguage != null) {
                mc.getLanguageManager().setSelected(originalLanguage);
                mc.options.languageCode = originalLanguage;
                mc.setScreen(null);
                mc.getWindow().setWindowed(originalWidth, originalHeight);
            }
        } catch (Throwable e) {
            restored = false;
        }
        try {
            Files.createDirectories(Path.of(ROOT));
            Files.writeString(Path.of(ROOT, "result.txt"), (failures == 0 ? "PASS" : "FAIL") + " step=" + step + " checks=" + checks
                    + " captures=" + captures + System.lineSeparator() + details);
        } catch (Exception ignored) {
            // The log line below still carries the result.
        }
        LOG.info("REALM_STRUCTURE_{} step={} checks={} captures={} failures={} userSettingsRestored={}", failures == 0 ? "PASS" : "FAIL",
                step, checks, captures, failures, restored);
        dev.xyat.kineticcore.api.runtime.KineticClientRuntime.stopClient();
    }
}
