package dev.xyat.realmcontrolvalidation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.server.event.KineticServerEvents;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.client.ControllerClient;
import dev.xyat.realmcontrol.structure.client.gui.BlockEditPage;
import dev.xyat.realmcontrol.structure.client.gui.MarkersPage;
import dev.xyat.realmcontrol.structure.network.BlockEditNetwork;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.ProjectStore;
import dev.xyat.realmcontrol.structure.project.ScanService;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.project.Tile;
import dev.xyat.realmcontrol.structure.publish.Publisher;
import dev.xyat.realmcontrol.structure.publish.TestPlacement;
import dev.xyat.realmcontrol.worldgen.preview.StructureImportService;
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.windows.User32;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Exercises registered layout import, client editing, actual placement and replacement across genuine world restarts. */
public final class ExistingLayoutValidation {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String DESERT = "minecraft:desert_pyramid", FOREIGN = "layout_fixture:import_probe";
    private static final String DESERT_DRAFT = "layout_validation:desert", DRAFT = "layout_validation:edited_probe";
    private static final String GIANT_DRAFT = "layout_validation:giant_sparse";
    private static final String ADORA = "adorabuild_structures:acacia_house_small_1", ADORA_DRAFT = "layout_validation:adorabuild_edited";
    private static final String ADORA_BLOCK = "minecraft:acacia_slab[type=bottom,waterlogged=false]";
    private static final String ORIGINAL_POOL = "layout_fixture:mixed", EDITED_POOL = DRAFT + "/start";
    private static final Path OUTPUT = Path.of(System.getProperty("realmcontrolstructure.existingValidation.output", "existing-layout-validation"));
    private static final String PHASE = System.getProperty("realmcontrolstructure.existingValidation.phase", "initial");
    private static final JsonObject RECEIPT = new JsonObject();
    private static CompletableFuture<?> pending;
    private static long deadline;
    private static int step, stopping;
    private static boolean finished;
    private static Edit edit;
    private static BlockPos placed;
    private static BlockPos clicked;
    private static long privateWindow;
    private static int inputTicks;
    private static String previewTrace = "";
    private static boolean measuring;
    private static long tickStart, operationStart;
    private static float lastProgress;
    private static int progressUpdates;
    private static final ArrayList<Double> GIANT_TICKS = new ArrayList<>();

    private record Edit(StructureProject project, BlockPos pos, String before) { }

    private ExistingLayoutValidation() { }

    public static void install() {
        deadline = System.nanoTime() + 300_000_000_000L;
        KineticClientEvents.onTick(KineticClientEvents.TickPhase.END, ExistingLayoutValidation::tick);
        if ("giant".equals(PHASE)) {
            KineticServerEvents.onTick(KineticEventPriority.HIGHEST, KineticServerEvents.TickPhase.START, server -> tickStart = System.nanoTime());
            KineticServerEvents.onTick(KineticEventPriority.LOWEST, KineticServerEvents.TickPhase.END, server -> {
                if (measuring && tickStart != 0L) GIANT_TICKS.add((System.nanoTime() - tickStart) / 1_000_000.0);
            });
        }
    }

    private static void tick() {
        if (finished) {
            if (++stopping == 15) KineticClientRuntime.stopClient();
            return;
        }
        try {
            if (System.nanoTime() > deadline) throw new IllegalStateException("Validation timed out in step " + step);
            var client = Minecraft.getInstance();
            var server = client.getSingleplayerServer();
            if (server == null || client.level == null || client.player == null || client.getOverlay() != null) return;
            switch (PHASE) {
                case "initial" -> initial(server);
                case "replacement" -> replacement(server);
                case "rollback" -> rollback(server);
                case "giant" -> giant(server);
                case "giant-reopen" -> giantReopen(server);
                //? if <1.21 {
                case "third-party", "third-party-packet" -> thirdParty(client, server);
                //?}
                default -> throw new IllegalArgumentException("Unknown validation phase " + PHASE);
            }
        } catch (Throwable error) {
            finished = true;
            LOG.error("REALM_EXISTING_LAYOUT_FAIL phase={} step={}", PHASE, step, error);
        }
    }

    private static void initial(MinecraftServer server) throws IOException {
        switch (step) {
            case 0 -> { pending = request(server, DESERT, 2, DESERT_DRAFT); step++; }
            case 1 -> {
                if (!ready()) return;
                StructureProject desert = imported(DESERT, 2);
                require(desert.blocks > 800 && desert.containers >= 4, "vanilla desert pyramid retains four chests");
                try (var draft = ProjectStore.openDraft(DESERT_DRAFT)) {
                    require(draft.markers().containers.stream().anyMatch(container ->
                            container.table.equals("minecraft:chests/desert_pyramid")), "vanilla chest loot table captured");
                }
                RECEIPT.addProperty("vanilla_blocks", desert.blocks);
                RECEIPT.addProperty("vanilla_chests", desert.containers);
                pending = request(server, DESERT, 2, DESERT_DRAFT);
                step++;
            }
            case 2 -> {
                if (!ready()) return;
                var duplicate = (StructureImportService.Result) pending.join();
                require(duplicate.project() == null && duplicate.error() != null, "a duplicate draft is rejected");
                require(ProjectStore.load(DESERT_DRAFT).containers == RECEIPT.get("vanilla_chests").getAsLong(), "earlier draft unchanged");
                pending = request(server, FOREIGN, 5, DRAFT);
                step++;
            }
            case 3 -> {
                if (!ready()) return;
                var project = imported(FOREIGN, 5);
                edit = foreignEdit(project);
                BlockEditNetwork.save(901, project, edit.pos(), edit.before(), "minecraft:gold_block");
                step++;
            }
            case 4 -> {
                if (!"minecraft:gold_block".equals(state(DRAFT, edit.pos()))) return;
                var modified = ProjectStore.load(DRAFT);
                require(modified.blockRevision > edit.project().blockRevision, "client request committed a new tile revision");
                RECEIPT.addProperty("selected_cell", edit.pos().toShortString());
                pending = server.submit(() -> {
                    var player = operator(server);
                    var level = player.serverLevel();
                    placed = new BlockPos(player.getBlockX() + 20, Math.min(level.getMaxBuildHeight() - 16, 200), player.getBlockZ() + 12);
                    require(!level.getBlockState(placed.offset(edit.pos())).is(Blocks.GOLD_BLOCK), "target was unmodified");
                    TestPlacement.generate(player, DRAFT, placed);
                    require(TestPlacement.busy(DRAFT), "permanent operation entered its real tick queue");
                });
                step++;
            }
            case 5 -> {
                if (!ready()) return;
                pending.join();
                pending = server.submit(() -> {
                    if (TestPlacement.busy(DRAFT)) return false;
                    require(server.overworld().getBlockState(placed.offset(edit.pos())).is(Blocks.GOLD_BLOCK), "draft block was actually placed in the world");
                    require(TestPlacement.record(server) == null, "permanent placement did not create test undo state");
                    return true;
                });
                step++;
            }
            case 6 -> {
                if (!ready()) return;
                if (!(Boolean) pending.join()) { step--; return; }
                RECEIPT.addProperty("placed_min", placed.toShortString());
                pending = publish(server, false);
                step++;
            }
            case 7 -> {
                if (!ready()) return;
                checkPublish();
                require(Files.exists(draftSet(server)), "default publication creates its own structure set");
                require(!Files.exists(overrideFile(server)), "default publication leaves the source alone");
                RECEIPT.addProperty("independent_by_default", true);
                pending = publish(server, true);
                step++;
            }
            case 8 -> {
                if (!ready()) return;
                checkPublish();
                require(!Files.exists(draftSet(server)), "source replacement uses the original structure's generation entry");
                var replacement = JsonParser.parseString(Files.readString(overrideFile(server))).getAsJsonObject();
                require(replacement.get("start_pool").getAsString().equals(EDITED_POOL), "replacement file points to the edited blueprint");
                require(replacement.getAsJsonObject("start_height").get("absolute").getAsInt() == 64
                        && !replacement.has("project_start_to_heightmap"), "replacement preserves the source structure's height configuration");
                RECEIPT.addProperty("source_absolute_height", 64);
                finish();
            }
            default -> throw new IllegalStateException("Unexpected initial validation step " + step);
        }
    }

    private static void replacement(MinecraftServer server) throws IOException {
        if (step == 0) {
            pending = server.submit(() -> {
                require(sourcePool(server).equals(EDITED_POOL), "reopened registry retained original ID but resolves the edited pool");
                var template = server.overworld().getStructureManager().get(ResourceLocation.tryParse(DRAFT)).orElseThrow();
                require(template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.GOLD_BLOCK).size() == 1,
                        "vanilla loaded the exact edited template as part of the replacement");
            });
            step++;
        } else if (step == 1 && ready()) {
            pending.join();
            RECEIPT.addProperty("reopened_source_pool", EDITED_POOL);
            pending = publish(server, false);
            step++;
        } else if (step == 2 && ready()) {
            checkPublish();
            require(!Files.exists(overrideFile(server)) && Files.exists(draftSet(server)), "closing replacement removes source override and restores independent output");
            finish();
        }
    }

    private static void rollback(MinecraftServer server) throws IOException {
        if (step == 0) {
            pending = server.submit(() -> {
                require(sourcePool(server).equals(ORIGINAL_POOL), "next real world load restores the source registry entry");
                registeredStructure(server, DRAFT);
            });
            step++;
        } else if (ready()) {
            pending.join();
            require("minecraft:gold_block".equals(state(DRAFT, readEditedPos())), "turning off replacement kept the saved draft edits");
            RECEIPT.addProperty("restored_source_pool", ORIGINAL_POOL);
            finish();
        }
    }

    private static void giant(MinecraftServer server) throws IOException {
        var progress = EditorClient.task(GIANT_DRAFT);
        if (progress != null && progress.share() > lastProgress) {
            lastProgress = progress.share();
            progressUpdates++;
        }
        switch (step) {
            case 0 -> { pending = CompletableFuture.runAsync(() -> prepareGiant(server)); step++; }
            case 1 -> {
                if (!ready()) return;
                pending.join();
                pending = server.submit(() -> {
                    var player = operator(server);
                    placed = new BlockPos(((player.getBlockX() >> 4) + 4) << 4, 196, ((player.getBlockZ() >> 4) + 4) << 4);
                    var level = server.overworld();
                    BlockPos gap = placed.offset(1024, 1, 1024);
                    var untouched = level.getBlockState(gap);
                    require(!untouched.is(Blocks.NETHERITE_BLOCK), "sparse gap starts as original terrain");
                    RECEIPT.addProperty("gap_before", untouched.toString());
                    operationStart = System.nanoTime();
                    measuring = true;
                    TestPlacement.generate(player, GIANT_DRAFT, placed);
                    require(TestPlacement.busy(GIANT_DRAFT), "4096 operation entered its real tick queue");
                });
                step++;
            }
            case 2 -> {
                if (!ready()) return;
                pending.join();
                pending = server.submit(() -> {
                    if (TestPlacement.busy(GIANT_DRAFT)) return false;
                    measuring = false;
                    RECEIPT.addProperty("duration_ms", (System.nanoTime() - operationStart) / 1_000_000.0);
                    checkGiantWorld(server, placed, RECEIPT.get("gap_before").getAsString());
                    require(TestPlacement.record(server) == null, "giant permanent operation has no undo record");
                    return true;
                });
                step++;
            }
            case 3 -> {
                if (!ready()) return;
                if (!(Boolean) pending.join()) { step--; return; }
                GIANT_TICKS.sort(Double::compareTo);
                require(!GIANT_TICKS.isEmpty() && progressUpdates > 1, "server tick timings and real client progress received");
                RECEIPT.addProperty("width", 4096);
                RECEIPT.addProperty("depth", 4096);
                RECEIPT.addProperty("nonempty_tiles", 5);
                RECEIPT.addProperty("x", placed.getX());
                RECEIPT.addProperty("y", placed.getY());
                RECEIPT.addProperty("z", placed.getZ());
                RECEIPT.addProperty("observed_progress_updates", progressUpdates);
                RECEIPT.addProperty("last_running_progress", lastProgress);
                RECEIPT.addProperty("measured_server_ticks", GIANT_TICKS.size());
                RECEIPT.addProperty("server_tick_p95_ms", GIANT_TICKS.get((int) Math.ceil(GIANT_TICKS.size() * 0.95) - 1));
                RECEIPT.addProperty("server_tick_max_ms", GIANT_TICKS.get(GIANT_TICKS.size() - 1));
                finish();
            }
            default -> throw new IllegalStateException("Unexpected giant validation step " + step);
        }
    }

    private static void giantReopen(MinecraftServer server) throws IOException {
        if (step == 0) {
            var initial = JsonParser.parseString(Files.readString(OUTPUT.resolve("giant.json"))).getAsJsonObject();
            placed = new BlockPos(initial.get("x").getAsInt(), initial.get("y").getAsInt(), initial.get("z").getAsInt());
            pending = server.submit(() -> checkGiantWorld(server, placed, initial.get("gap_before").getAsString()));
            step++;
        } else if (ready()) {
            pending.join();
            RECEIPT.addProperty("persisted_remote_corners_and_inventory", true);
            finish();
        }
    }

    private static void prepareGiant(MinecraftServer server) {
        try {
            final int width = 4096, tileSize = 32, height = 4;
            var project = new StructureProject();
            project.id = GIANT_DRAFT;
            project.name = "Sparse full-width test";
            project.size = new int[]{width, height, width};
            project.tileSize = tileSize;
            project.air = "none";
            var scan = MegaDraftFixture.begin(project, false);
            int stride = project.tilesX();
            Map<Integer, Tile> tiles = new HashMap<>();
            for (int x : new int[]{0, width - 1}) for (int z : new int[]{0, width - 1}) {
                var corner = new Tile(tileSize, height, tileSize);
                corner.cells[corner.cell(x % tileSize, 0, z % tileSize)] = (short) corner.index("minecraft:netherite_block");
                tiles.put(z / tileSize * stride + x / tileSize, corner);
            }
            var center = new Tile(tileSize, height, tileSize);
            int stone = center.index("minecraft:iron_block");
            for (int z = 0; z < tileSize; z++) for (int x = 0; x < tileSize; x++) center.cells[center.cell(x, 0, z)] = (short) stone;
            int chestCell = center.cell(2, 1, 3);
            center.cells[chestCell] = (short) center.index("minecraft:chest[facing=north,type=single,waterlogged=false]");
            var chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
            center.blockEntities.put(chestCell, ScanService.blockEntityTag(chest, server.registryAccess()));
            tiles.put((stride / 2) * stride + stride / 2, center);
            project.blocks = (long) tileSize * tileSize + 5;
            project.blockEntities = project.containers = 1;
            BitSet empty = new BitSet(stride * project.tilesZ());
            empty.set(0, stride * project.tilesZ());
            for (var entry : tiles.entrySet()) {
                int index = entry.getKey();
                entry.getValue().write(ProjectStore.tile(scan.resolve("tiles"), index % stride, index / stride));
                empty.clear(index);
            }
            MegaDraftFixture.finish(project, false, scan, new Cutouts(), empty);
        } catch (IOException error) { throw new CompletionException(error); }
    }

    private static void checkGiantWorld(MinecraftServer server, BlockPos min, String gap) {
        var level = server.overworld();
        for (int x : new int[]{0, 4095}) for (int z : new int[]{0, 4095}) {
            require(level.getBlockState(min.offset(x, 0, z)).is(Blocks.NETHERITE_BLOCK), "far corner placed at " + x + "," + z);
        }
        require(level.getBlockState(min.offset(2048, 0, 2048)).is(Blocks.IRON_BLOCK)
                && level.getBlockState(min.offset(2079, 0, 2079)).is(Blocks.IRON_BLOCK), "dense 32×32 tile placed through chunk seams");
        var chest = level.getBlockEntity(min.offset(2050, 1, 2051));
        require(chest instanceof ChestBlockEntity container && container.getItem(0).is(Items.DIAMOND)
                && container.getItem(0).getCount() == 7, "remote chest inventory persisted");
        require(level.getBlockState(min.offset(1024, 1, 1024)).toString().equals(gap), "sparse tile leaves existing world cells unchanged");
    }

    //? if <1.21 {
    private static void thirdParty(Minecraft client, MinecraftServer server) throws IOException, ReflectiveOperationException {
        switch (step) {
            case 0 -> {
                var previous = ProjectStore.load(ADORA_DRAFT);
                if (previous == null) pending = request(server, ADORA, 4, ADORA_DRAFT);
                else pending = CompletableFuture.completedFuture(new StructureImportService.Result(previous, null));
                step++;
            }
            case 1 -> {
                if (!ready()) return;
                var sampled = imported(ADORA, 4);
                require(sampled.blocks > 50, "a layout from the installed AdoraBuild mod was imported");
                RECEIPT.addProperty("third_party_source", ADORA);
                RECEIPT.addProperty("sampled_blocks", sampled.blocks);
                pending = server.submit(() -> locateAdora(server));
                deadline = System.nanoTime() + 45_000_000_000L;
                step++;
            }
            case 2 -> {
                if (!ready()) return;
                pending.join();
                if (++inputTicks == 20) server.execute(() -> {
                    var player = operator(server);
                    player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(StructureEditorModule.CONTROLLER.get()));
                    player.inventoryMenu.broadcastChanges();
                });
                if (inputTicks == 60) LOG.info("REALM_EXISTING_LAYOUT_NATIVE_POSE pos={} controller={} camera={} hit={}",
                        client.player.position(), ControllerClient.holding(), client.player.getXRot(), client.player.pick(32.0D, 1.0F, false));
                if (!ControllerClient.holding() || client.player.distanceToSqr(clicked.getX() + 0.5D, clicked.getY() + 1.0D, clicked.getZ() + 0.5D) > 4.0D) return;
                client.player.setXRot(90F);
                if (!(client.player.pick(32.0D, 1.0F, false) instanceof BlockHitResult hit) || !hit.getBlockPos().equals(clicked)) return;
                if ("third-party-packet".equals(PHASE)) {
                    StructureEditorNetwork.inspect(clicked, -1);
                    RECEIPT.addProperty("input_path", "client-inspect-packet");
                    deadline = System.nanoTime() + 45_000_000_000L;
                    step = 5;
                    return;
                }
                inputTicks = 0;
                privateWindow = GLFWNativeWin32.glfwGetWin32Window(client.getWindow().getWindow());
                require(privateWindow != 0 && Boolean.getBoolean("kineticcore.guiValidation.background"), "fixture owns an isolated native game window");
                require(User32.PostMessage(privateWindow, 0x0100, 0x11, (29L << 16) | 1L), "left Ctrl key reached the private native window");
                deadline = System.nanoTime() + 20_000_000_000L;
                step++;
            }
            case 3 -> {
                if (!KineticClientRuntime.controlModifierDown()) return;
                require(User32.PostMessage(privateWindow, 0x0204, 0x000A, 128L | (128L << 16)), "right mouse press reached the private native window");
                step++;
            }
            case 4 -> {
                if (++inputTicks < 3) return;
                require(User32.PostMessage(privateWindow, 0x0205, 0x0008, 128L | (128L << 16)), "right mouse release reached the private native window");
                require(User32.PostMessage(privateWindow, 0x0101, 0x11, 0xc0000000L | (29L << 16) | 1L), "Ctrl release reached the private native window");
                RECEIPT.addProperty("input_path", "native-control-and-right-button");
                deadline = System.nanoTime() + 45_000_000_000L;
                step++;
            }
            case 5 -> {
                traceBlockPreview();
                var page = KineticGui.currentPage(BlockEditPage.class);
                if (page == null) return;
                var field = editorField(page, "stateField", KineticTextField.class);
                if (field == null) return;
                var instance = editorField(page, "project", StructureProject.class);
                require(instance.sourceStructure.equals(ADORA) && instance.sourceVariant < 0, "the opened editor targets the naturally generated instance");
                require(instance.blocks > 50 && instance.origin[0] != 0, "the opened draft contains real generated blocks at their existing coordinates");
                var position = editorField(page, "position", BlockPos.class);
                require(position.offset(instance.origin[0], instance.origin[1], instance.origin[2]).equals(clicked), "the edit page targets the clicked world block");
                String original = editorField(page, "original", String.class);
                require(original.equals(ADORA_BLOCK) && field.textValue().equals(original)
                        && state(instance.id, position).equals(original), "the block editor and saved draft contain the selected bottom acacia slab state");
                RECEIPT.addProperty("clicked_world_position", clicked.toShortString());
                RECEIPT.addProperty("actual_blocks", instance.blocks);
                RECEIPT.addProperty("opened_editor", page.getClass().getSimpleName());
                RECEIPT.addProperty("original_block_state", original);
                RECEIPT.addProperty("editable_state_text", field.textValue());
                finish();
            }
            default -> throw new IllegalStateException("Unexpected modded validation step " + step);
        }
    }

    private static void locateAdora(MinecraftServer server) {
        var level = server.overworld();
        var holder = server.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(
                ResourceKey.create(Registries.STRUCTURE, ResourceLocation.tryParse(ADORA))).orElseThrow();
        var player = operator(server);
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(holder), player.blockPosition(), 140, false);
        require(found != null, "find an AdoraBuild instance in the actual generated terrain");
        var start = level.getChunk(found.getFirst()).getStartForStructure(holder.value());
        require(start != null && start.isValid(), "the located instance has a real registered structure start");
        var box = start.getPieces().get(0).getBoundingBox();
        search:
        for (int y = box.maxY(); y >= box.minY(); y--) for (int x = box.minX(); x <= box.maxX(); x++) for (int z = box.minZ(); z <= box.maxZ(); z++) {
            var pos = new BlockPos(x, y, z);
            var state = level.getBlockState(pos);
            if (state.is(Blocks.ACACIA_SLAB) && BlockStates.serialize(state).equals(ADORA_BLOCK) && level.getBlockState(pos.above()).isAir()
                    && level.getBlockState(pos.above(2)).isAir() && level.getBlockState(pos.above(3)).isAir()) {
                clicked = pos;
                break search;
            }
        }
        require(clicked != null, "pick a visible building block belonging to the generated house");
        LOG.info("REALM_EXISTING_LAYOUT_NATIVE_TARGET {} {}", clicked, level.getBlockState(clicked));
        String teleport = "tp @p " + (clicked.getX() + 0.5D) + " " + (clicked.getY() + 1.0D) + " " + (clicked.getZ() + 0.5D) + " 0 90";
        try {
            server.getCommands().getDispatcher().execute(teleport, server.createCommandSourceStack().withSuppressedOutput());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException("Could not position the real player at the house", e);
        }
    }

    private static <T> T editorField(BlockEditPage page, String name, Class<T> type) throws ReflectiveOperationException {
        return readField(BlockEditPage.class, page, name, type);
    }

    private static <T> T readField(Class<?> owner, Object object, String name, Class<T> type) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(object));
    }

    private static void traceBlockPreview() throws ReflectiveOperationException {
        var page = KineticGui.currentPage();
        var key = readField(StructurePreviewClient.class, null, "currentKey", String.class);
        var request = readField(StructurePreviewClient.class, null, "current", Integer.class);
        String trace = (page == null ? "world" : page.getClass().getSimpleName()) + " preview=" + key + " request=" + request;
        if (page instanceof MarkersPage markersPage) {
            var origin = readField(MarkersPage.class, markersPage, "windowOrigin", BlockPos.class);
            var project = readField(MarkersPage.class, markersPage, "project", StructureProject.class);
            var shown = readField(MarkersPage.class, markersPage, "shown", KineticBlockSnapshot.class);
            trace += " window=" + origin + " layout=" + project.id;
            if (shown != null && origin != null) {
                int x = clicked.getX() - project.origin[0] - origin.getX();
                int y = clicked.getY() - project.origin[1] - origin.getY();
                int z = clicked.getZ() - project.origin[2] - origin.getZ();
                trace += " scale=" + shown.scale() + " hit=" + shown.paletteName(shown.index(x, y, z));
            }
        }
        if (!previewTrace.equals(trace)) {
            previewTrace = trace;
            LOG.info("REALM_EXISTING_LAYOUT_BLOCK_PREVIEW {}", trace);
        }
    }
    //?}


    private static BlockPos readEditedPos() throws IOException {
        try (var draft = ProjectStore.openDraft(DRAFT)) {
            for (int tz = 0; tz < draft.project().tilesZ(); tz++) for (int tx = 0; tx < draft.project().tilesX(); tx++) {
                var tile = draft.readTile(tx, tz);
                if (tile == null) continue;
                for (int y = 0; y < tile.sizeY; y++) for (int z = 0; z < tile.sizeZ; z++) for (int x = 0; x < tile.sizeX; x++) {
                    if (tile.state(x, y, z).equals("minecraft:gold_block")) return new BlockPos(x + tx * draft.project().tileSize, y, z + tz * draft.project().tileSize);
                }
            }
        }
        throw new IllegalStateException("Cannot find saved gold block");
    }

    private static Edit foreignEdit(StructureProject project) throws IOException {
        require(project.blocks >= 25 && project.containers == 1 && project.entities == 1 && project.blockEntities == 2, "raw foreign structure data retained");
        try (var draft = ProjectStore.openDraft(DRAFT)) {
            require(draft.markers().containers.get(0).table.equals("minecraft:chests/desert_pyramid"), "foreign chest retains its loot table");
            require(draft.markers().spawners.get(0).entity.equals("minecraft:skeleton"), "foreign spawner retains its selected mob");
            var tile = draft.readTile(0, 0);
            require(tile.blockEntities.values().stream().anyMatch(tag -> tag.getLong("LootTableSeed") != 0L), "foreign chest retains the seed assigned during vanilla placement");
            var entity = tile.entities.get(0).tag();
            //? if >=26.1 {
            /*require(entity.getBooleanOr("Invisible", false) && entity.getBooleanOr("NoGravity", false), "full entity NBT retained");
            *///?} else {
            require(entity.getBoolean("Invisible") && entity.getBoolean("NoGravity"), "full entity NBT retained");
            //?}
            for (int y = 0; y < tile.sizeY; y++) for (int z = 0; z < tile.sizeZ; z++) for (int x = 0; x < tile.sizeX; x++) {
                String state = tile.state(x, y, z);
                if (state.equals("minecraft:emerald_block")) {
                    RECEIPT.addProperty("foreign_source", project.sourceStructure);
                    RECEIPT.addProperty("foreign_variant", project.sourceVariant);
                    RECEIPT.addProperty("raw_entity_spawner_and_loot", true);
                    return new Edit(project, new BlockPos(x, y, z), state);
                }
            }
        }
        throw new IllegalStateException("The original emerald marker is absent");
    }

    private static String state(String id, BlockPos pos) throws IOException {
        try (var draft = ProjectStore.openDraft(id)) {
            int size = draft.project().tileSize;
            var tile = draft.readTile(pos.getX() / size, pos.getZ() / size);
            return tile.state(pos.getX() % size, pos.getY(), pos.getZ() % size);
        }
    }

    private static String sourcePool(MinecraftServer server) {
        var structure = registeredStructure(server, FOREIGN);
        var json = Structure.DIRECT_CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, server.registryAccess()), structure).result().orElseThrow();
        return json.getAsJsonObject().get("start_pool").getAsString();
    }

    private static Structure registeredStructure(MinecraftServer server, String name) {
        var registry = server.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return registry.getHolder(ResourceKey.create(Registries.STRUCTURE, ResourceLocation.tryParse(name))).orElseThrow().value();
    }

    private static CompletableFuture<StructureImportService.Result> request(MinecraftServer server, String source, int variant, String id) {
        CompletableFuture<StructureImportService.Result> result = new CompletableFuture<>();
        server.execute(() -> {
            try { StructureImportService.request(operator(server), source, variant, id, result::complete); }
            catch (RuntimeException error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private static ServerPlayer operator(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        require(StructureEditorModule.allowed(player), "isolated operator has game master permission");
        return player;
    }

    private static StructureProject imported(String source, int variant) {
        var result = (StructureImportService.Result) pending.join();
        if (result.project() == null) throw new IllegalStateException("Cannot import " + source + ": " + result.error().getString());
        require(result.error() == null && result.project().sourceStructure.equals(source) && result.project().sourceVariant == variant,
                "persisted source identifies the selected variant");
        return result.project();
    }

    private static CompletableFuture<Publisher.Outcome> publish(MinecraftServer server, boolean replace) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var project = ProjectStore.load(DRAFT);
                project.save.target = "world";
                project.save.replaceSource = replace;
                ProjectStore.save(project);
                return Publisher.publish(server, DRAFT, share -> { });
            } catch (IOException error) { throw new CompletionException(error); }
        });
    }

    private static void checkPublish() {
        var result = (Publisher.Outcome) pending.join();
        require(result.success(), result.message().getString());
    }

    private static Path overrideFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(Publisher.PACK).resolve("data/layout_fixture/worldgen/structure/import_probe.json");
    }

    private static Path draftSet(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(Publisher.PACK).resolve("data/layout_validation/worldgen/structure_set/edited_probe.json");
    }

    private static boolean ready() { return pending != null && pending.isDone(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish() throws IOException {
        Files.createDirectories(OUTPUT);
        RECEIPT.addProperty("phase", PHASE);
        Files.writeString(OUTPUT.resolve(PHASE + ".json"), RECEIPT.toString() + "\n");
        LOG.info("REALM_EXISTING_LAYOUT_PASS phase={} {}", PHASE, RECEIPT);
        finished = true;
    }
}
