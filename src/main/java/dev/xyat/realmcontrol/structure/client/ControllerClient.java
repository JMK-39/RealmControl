package dev.xyat.realmcontrol.structure.client;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.input.KineticKeyBindings;
import dev.xyat.kineticcore.api.client.render.KineticWorldRender;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.structure.selection.SelectionStore;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * The structure controller in hand: right click sets corners (with Shift the second one) of the region or of a
 * cut-out box, left click never breaks blocks (Shift + left click clears the region, asked twice, or deletes the
 * cut-out box looked at), Ctrl + wheel moves the face of the region (or of the newest cut-out box) the player looks
 * towards, by 1 block or 16 with Shift, and Alt + wheel switches between the modes. In the container and spawner
 * modes right click opens the markers page on the scanned container or spawner looked at (Shift + right click gathers
 * several for one batch edit); in the entity mode right click keeps or leaves out the entity looked at. K opens the
 * editor. While held, the region, its cut-out boxes, the markers of the mode and a status line are drawn.
 */
public final class ControllerClient {
    private static final double REACH = 512.0D;
    private static final long CLEAR_CONFIRM_NANOS = 3_000_000_000L;
    private static final long NEARBY_REFRESH_NANOS = 5_000_000_000L;
    private static final double ENTITY_REACH = 64.0D;
    private static long nearbyRequested;
    private static Mode mode = Mode.SELECT;
    private static long clearArmed;
    @Nullable private static KineticKeyBindings.Binding editorBinding;

    /** What the controller's clicks edit. */
    public enum Mode {
        SELECT, CUTOUT, CONTAINER, SPAWNER, ENTITY;

        boolean markers() {
            return this == CONTAINER || this == SPAWNER || this == ENTITY;
        }
    }

    private ControllerClient() {
    }

    public static void install() {
        KineticKeyBindings.Builder editorKey = KineticKeyBindings.builder("key.realmcontrol.structure_editor");
        // Key categories are ids on 26.1, labelled by key.category.realmcontrol.main.
        //? if >=26.1 {
        /*editorKey.category(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("realmcontrol", "main"));
        *///?} else {
        editorKey.category("key.categories.realmcontrol");
        //?}
        editorBinding = editorKey
                .context(KineticKeyBindings.Context.IN_GAME)
                .keyboard(KineticKeyBindings.Key.K)
                .enabledWhen(ControllerClient::holding)
                .onPressed(() -> {
                    StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_EDITOR, "", "");
                    return true;
                })
                .register();
        KineticClientEvents.onInteractionKey(ControllerClient::onInteraction);
        KineticClientEvents.onMouseScroll(KineticEventPriority.HIGH, ControllerClient::onScroll);
        KineticClientEvents.onLevelRender(KineticClientEvents.LevelRenderStage.AFTER_PARTICLES, ControllerClient::render);
        KineticClientEvents.onHudRender(KineticClientEvents.HudStage.END, ControllerClient::renderHud);
        KineticClientEvents.onItemTooltip(context -> {
            if (!context.itemStack().is(StructureEditorModule.CONTROLLER.get())) return;
            List<Component> lines = context.tooltip();
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.select", (int) REACH).withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.wheel").withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.clear").withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.markers").withStyle(net.minecraft.ChatFormatting.GRAY));
            Component key = editorBinding == null ? Component.literal("K") : editorBinding.translatedKeyMessage();
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.editor", key).withStyle(net.minecraft.ChatFormatting.GRAY));
        });
    }

    public static Mode mode() {
        return mode;
    }

    /** Switches the mode as Alt + wheel does. */
    public static void setMode(Mode next) {
        mode = next;
        if (mode.markers()) requestNearby(true);
    }

    public static boolean holding() {
        LocalPlayer player = KineticClientRuntime.localPlayer();
        return player != null && (player.getMainHandItem().is(StructureEditorModule.CONTROLLER.get())
                || player.getOffhandItem().is(StructureEditorModule.CONTROLLER.get()));
    }

    private static void onInteraction(KineticClientEvents.InteractionKeyContext context) {
        if (!holding()) return;
        context.cancel();
        context.swingHand(false);
        boolean shift = KineticClientRuntime.shiftKeyDown();
        if (context.useItem() && mode == Mode.ENTITY) {
            net.minecraft.world.entity.Entity entity = targetEntity();
            if (entity == null) {
                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.marker.no_entity", (int) ENTITY_REACH), true);
            } else {
                StructureEditorNetwork.toggleEntity(entity.getId());
            }
        } else if (context.useItem() && mode.markers()) {
            openMarker(shift);
        } else if (context.useItem()) {
            BlockPos target = target();
            if (target == null) {
                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.no_target", (int) REACH), true);
                return;
            }
            SelectionStore.Action action = mode == Mode.SELECT
                    ? (shift ? SelectionStore.Action.SET_B : SelectionStore.Action.SET_A)
                    : (shift ? SelectionStore.Action.CUT_B : SelectionStore.Action.CUT_A);
            StructureEditorNetwork.selectionAction(action, target, 0, 0);
        } else if (context.attack() && shift && !mode.markers()) {
            if (mode == Mode.CUTOUT) {
                int index = lookedAtCutout();
                if (index >= 0) StructureEditorNetwork.selectionAction(SelectionStore.Action.DELETE_CUTOUT, null, index, 0);
                return;
            }
            long now = System.nanoTime();
            if (now - clearArmed < CLEAR_CONFIRM_NANOS) {
                clearArmed = 0L;
                StructureEditorNetwork.selectionAction(SelectionStore.Action.CLEAR, null, 0, 0);
            } else if (!EditorClient.selection().isEmpty()) {
                clearArmed = now;
                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.selection.clear_confirm"), true);
            }
        }
    }

    private static void onScroll(KineticClientEvents.MouseScrollContext context) {
        if (!holding() || context.vertical() == 0D) return;
        if (KineticClientRuntime.altModifierDown()) {
            Mode[] modes = Mode.values();
            mode = modes[Math.floorMod(mode.ordinal() + (context.vertical() > 0D ? -1 : 1), modes.length)];
            if (mode.markers()) requestNearby(true);
            KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.mode",
                    KineticI18n.translatable("gui.realmcontrol.structure.mode." + mode.name().toLowerCase(java.util.Locale.ROOT))), true);
            context.cancel();
        } else if (KineticClientRuntime.controlModifierDown() && !mode.markers()) {
            int amount = (KineticClientRuntime.shiftKeyDown() ? 16 : 1) * (context.vertical() > 0D ? 1 : -1);
            StructureEditorNetwork.selectionAction(mode == Mode.SELECT ? SelectionStore.Action.PUSH : SelectionStore.Action.PUSH_CUTOUT,
                    null, facing().ordinal(), amount);
            context.cancel();
        }
    }

    private static void requestNearby(boolean force) {
        long now = System.nanoTime();
        if (!force && now - nearbyRequested < NEARBY_REFRESH_NANOS) return;
        nearbyRequested = now;
        StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.NEARBY_MARKERS, "", "");
    }

    /**
     * Right click in the container or spawner mode: opens the markers page on the marker looked at, with the gathered
     * ones selected too; with Shift it gathers or lets go of the marker instead.
     */
    private static void openMarker(boolean shift) {
        BlockPos target = target();
        if (target == null) {
            KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.no_target", (int) REACH), true);
            return;
        }
        for (EditorClient.NearbyProject nearby : EditorClient.nearby()) {
            StructureProject project = nearby.project();
            int x = target.getX() - project.origin[0], y = target.getY() - project.origin[1], z = target.getZ() - project.origin[2];
            boolean found = mode == Mode.CONTAINER ? nearby.markers().container(x, y, z) != null : nearby.markers().spawner(x, y, z) != null;
            if (!found) continue;
            String key = (mode == Mode.CONTAINER ? "c:" : "s:") + x + "," + y + "," + z;
            java.util.Set<String> batch = EditorClient.batch(project.id);
            if (shift) {
                if (!batch.remove(key)) batch.add(key);
                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.marker.batch", batch.size()), true);
                return;
            }
            StringBuilder focus = new StringBuilder(key);
            for (String other : batch) if (!other.equals(key)) focus.append(';').append(other);
            StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_MARKERS, project.id, focus.toString());
            return;
        }
        KineticClientRuntime.displayClientMessage(KineticI18n.translatable(mode == Mode.CONTAINER
                ? "msg.realmcontrol.structure.marker.no_container" : "msg.realmcontrol.structure.marker.no_spawner"), true);
    }

    /** The nearest entity the view ray passes within {@link #ENTITY_REACH}, unless a block is in front of it. */
    @Nullable
    private static net.minecraft.world.entity.Entity targetEntity() {
        LocalPlayer player = KineticClientRuntime.localPlayer();
        if (player == null) return null;
        Vec3 eye = player.getEyePosition(1.0F), view = player.getViewVector(1.0F), end = eye.add(view.scale(ENTITY_REACH));
        HitResult block = player.pick(ENTITY_REACH, 1.0F, false);
        double limit = block.getType() == HitResult.Type.BLOCK ? block.getLocation().distanceToSqr(eye) : ENTITY_REACH * ENTITY_REACH;
        net.minecraft.world.entity.Entity best = null;
        AABB search = player.getBoundingBox().expandTowards(view.scale(ENTITY_REACH)).inflate(1.0D);
        for (net.minecraft.world.entity.Entity entity : player.level().getEntities(player, search, entity -> !entity.isSpectator())) {
            AABB box = entity.getBoundingBox().inflate(entity.getPickRadius() + 0.1D);
            Optional<Vec3> hit = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
            if (hit.isPresent() && hit.get().distanceToSqr(eye) < limit) {
                limit = hit.get().distanceToSqr(eye);
                best = entity;
            }
        }
        return best;
    }

    /** The face a wheel turn moves: the one pointing most along the view. */
    private static Direction facing() {
        LocalPlayer player = KineticClientRuntime.localPlayer();
        Vec3 look = player == null ? new Vec3(0, 0, 1) : player.getViewVector(1.0F);
        double ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);
        if (ay >= ax && ay >= az) return look.y > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return look.x > 0 ? Direction.EAST : Direction.WEST;
        return look.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** The block looked at within {@link #REACH}, or {@code null}. */
    @Nullable
    private static BlockPos target() {
        LocalPlayer player = KineticClientRuntime.localPlayer();
        if (player == null) return null;
        HitResult hit = player.pick(REACH, 1.0F, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block.getBlockPos() : null;
    }

    /** Index of the nearest cut-out box the view ray passes through, or -1. */
    private static int lookedAtCutout() {
        LocalPlayer player = KineticClientRuntime.localPlayer();
        if (player == null) return -1;
        Vec3 eye = player.getEyePosition(1.0F), end = eye.add(player.getViewVector(1.0F).scale(REACH));
        List<Selection.Box> boxes = EditorClient.selection().cutouts;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < boxes.size(); i++) {
            Optional<Vec3> hit = aabb(boxes.get(i)).clip(eye, end);
            if (aabb(boxes.get(i)).contains(eye)) hit = Optional.of(eye);
            if (hit.isPresent() && hit.get().distanceToSqr(eye) < bestDistance) {
                bestDistance = hit.get().distanceToSqr(eye);
                best = i;
            }
        }
        return best;
    }

    private static AABB aabb(Selection.Box box) {
        return new AABB(box.min().getX(), box.min().getY(), box.min().getZ(), box.max().getX() + 1, box.max().getY() + 1, box.max().getZ() + 1);
    }

    private static AABB block(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    /** A thin slab on one face of a box, to mark the face the wheel moves. */
    private static AABB face(AABB box, Direction face) {
        double t = 0.06D;
        return switch (face) {
            case EAST -> new AABB(box.maxX - t, box.minY, box.minZ, box.maxX + t, box.maxY, box.maxZ);
            case WEST -> new AABB(box.minX - t, box.minY, box.minZ, box.minX + t, box.maxY, box.maxZ);
            case UP -> new AABB(box.minX, box.maxY - t, box.minZ, box.maxX, box.maxY + t, box.maxZ);
            case DOWN -> new AABB(box.minX, box.minY - t, box.minZ, box.maxX, box.minY + t, box.maxZ);
            case SOUTH -> new AABB(box.minX, box.minY, box.maxZ - t, box.maxX, box.maxY, box.maxZ + t);
            case NORTH -> new AABB(box.minX, box.minY, box.minZ - t, box.maxX, box.maxY, box.minZ + t);
        };
    }

    private static void render(KineticClientEvents.LevelRenderContext context) {
        if (!holding()) return;
        Selection selection = EditorClient.selection();
        try (KineticWorldRender.LineBatch lines = KineticWorldRender.beginLineBatch(context)) {
            Selection.Box region = selection.region();
            if (region != null) {
                AABB box = aabb(region);
                lines.boxThroughBlocks(box, Selection.problem(region) == null ? KineticWorldRender.Indicator.INFO : KineticWorldRender.Indicator.DANGER);
                if (mode == Mode.SELECT) lines.box(face(box, facing()), KineticWorldRender.Indicator.WARNING);
            } else {
                if (selection.a != null) lines.box(block(selection.a), KineticWorldRender.Indicator.SUCCESS);
                if (selection.b != null) lines.box(block(selection.b), KineticWorldRender.Indicator.SUCCESS);
            }
            for (int i = 0; i < selection.cutouts.size(); i++) {
                AABB box = aabb(selection.cutouts.get(i));
                lines.boxThroughBlocks(box, KineticWorldRender.Indicator.DANGER);
                if (mode == Mode.CUTOUT && i == selection.cutouts.size() - 1) lines.box(face(box, facing()), KineticWorldRender.Indicator.WARNING);
            }
            if (selection.cutA != null) lines.box(block(selection.cutA), KineticWorldRender.Indicator.DANGER);
            if (mode.markers()) {
                requestNearby(false);
                renderMarkers(lines);
            }
        }
    }

    /** The markers of the mode, seen through blocks: gold with a table or custom, grey as scanned, green when gathered; entities green or red. */
    private static void renderMarkers(KineticWorldRender.LineBatch lines) {
        LocalPlayer player = KineticClientRuntime.localPlayer();
        if (player == null) return;
        for (EditorClient.NearbyProject nearby : EditorClient.nearby()) {
            StructureProject project = nearby.project();
            int[] o = project.origin;
            java.util.Set<String> batch = EditorClient.batch(project.id);
            if (mode == Mode.CONTAINER) {
                for (var container : nearby.markers().containers) {
                    boolean gathered = batch.contains("c:" + container.x + "," + container.y + "," + container.z);
                    lines.boxThroughBlocks(block(new BlockPos(o[0] + container.x, o[1] + container.y, o[2] + container.z)), gathered
                            ? KineticWorldRender.Indicator.SUCCESS : container.usesTable() ? KineticWorldRender.Indicator.WARNING : KineticWorldRender.Indicator.MUTED);
                }
            } else if (mode == Mode.SPAWNER) {
                for (var spawner : nearby.markers().spawners) {
                    boolean gathered = batch.contains("s:" + spawner.x + "," + spawner.y + "," + spawner.z);
                    lines.boxThroughBlocks(block(new BlockPos(o[0] + spawner.x, o[1] + spawner.y, o[2] + spawner.z)), gathered
                            ? KineticWorldRender.Indicator.SUCCESS : spawner.custom() ? KineticWorldRender.Indicator.WARNING : KineticWorldRender.Indicator.MUTED);
                }
            } else {
                AABB area = new AABB(o[0], o[1], o[2], o[0] + project.size[0], o[1] + project.size[1], o[2] + project.size[2]).inflate(2.0D);
                List<net.minecraft.world.entity.Entity> live = player.level().getEntities(player, area, entity -> true);
                for (var mark : nearby.markers().entities) {
                    double x = o[0] + mark.x, y = o[1] + mark.y, z = o[2] + mark.z;
                    if (player.distanceToSqr(x, y, z) > 96 * 96) continue;
                    AABB box = new AABB(x - 0.25D, y, z - 0.25D, x + 0.25D, y + 0.5D, z + 0.25D);
                    double best = 2.25D;
                    for (net.minecraft.world.entity.Entity entity : live) {
                        double distance = entity.distanceToSqr(x, y, z);
                        if (distance < best && net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString().equals(mark.type)) {
                            best = distance;
                            box = entity.getBoundingBox();
                        }
                    }
                    lines.boxThroughBlocks(box, nearby.markers().keeps(mark) ? KineticWorldRender.Indicator.SUCCESS : KineticWorldRender.Indicator.DANGER);
                }
            }
        }
    }

    private static void renderHud(KineticGraphics g, float partialTick) {
        if (!holding() || KineticClientRuntime.guiHidden() || KineticClientRuntime.currentScreen() != null) return;
        Selection selection = EditorClient.selection();
        Selection.Box region = selection.region();
        MutableComponent line = KineticI18n.translatable("gui.realmcontrol.structure.mode." + mode.name().toLowerCase(java.util.Locale.ROOT));
        int color = 0xFFFFFFFF;
        if (region != null) {
            line.append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.hud.region",
                    region.sizeX(), region.sizeY(), region.sizeZ()));
            line.append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.hud.blocks", region.volume()));
            int footprint = Math.max(region.sizeX(), region.sizeZ());
            String tier = footprint <= StructureProject.SMALL_LIMIT ? "small" : footprint <= StructureProject.MEDIUM_LIMIT ? "medium" : "mega";
            line.append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.tier." + tier));
            String problem = Selection.problem(region);
            if (problem != null) {
                line.append(Component.literal(" · ")).append(KineticI18n.translatable(problem, Selection.MAX_FOOTPRINT));
                color = 0xFFFF5555;
            }
        } else {
            line.append(Component.literal(" · ")).append(KineticI18n.translatable(selection.a == null
                    ? "gui.realmcontrol.structure.hud.set_a" : "gui.realmcontrol.structure.hud.set_b"));
        }
        if (!selection.cutouts.isEmpty()) {
            line.append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.hud.cutouts", selection.cutouts.size()));
        }
        if (mode.markers()) {
            if (EditorClient.nearby().isEmpty()) {
                line = KineticI18n.translatable("gui.realmcontrol.structure.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)).copy()
                        .append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.hud.no_project"));
                color = 0xFFFFAA00;
            } else {
                EditorClient.NearbyProject nearby = EditorClient.nearby().get(0);
                var markers = nearby.markers();
                int tabled = 0, custom = 0;
                for (var container : markers.containers) if (container.usesTable()) tabled++;
                for (var spawner : markers.spawners) if (spawner.custom()) custom++;
                line = KineticI18n.translatable("gui.realmcontrol.structure.mode." + mode.name().toLowerCase(java.util.Locale.ROOT)).copy()
                        .append(Component.literal(" · " + nearby.project().name + " · "))
                        .append(KineticI18n.translatable("gui.realmcontrol.structure.hud.markers", markers.containers.size(), tabled,
                                markers.spawners.size(), custom, markers.entities.size(), markers.entities.size() - markers.keptEntities()));
                int gathered = EditorClient.batch(nearby.project().id).size();
                if (gathered > 0) line.append(Component.literal(" · ")).append(KineticI18n.translatable("gui.realmcontrol.structure.hud.batch", gathered));
                color = 0xFFFFFFFF;
            }
        }
        int width = g.textWidth(line);
        // Above the action bar line (screen height - 72), where the controller's own messages appear.
        int x = (KineticClientRuntime.guiScaledWidth() - width) / 2, y = KineticClientRuntime.guiScaledHeight() - 88;
        g.fill(x - 3, y - 2, x + width + 3, y + g.lineHeight() + 1, 0x90000000);
        g.text(line, x, y, color, false);
    }
}
