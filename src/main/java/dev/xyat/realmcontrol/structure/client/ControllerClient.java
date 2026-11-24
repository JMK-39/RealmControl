package dev.xyat.realmcontrol.structure.client;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.input.KineticKeyBindings;
import dev.xyat.kineticcore.api.event.KineticEventPriority;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.selection.Selection;
import dev.xyat.realmcontrol.structure.selection.SelectionStore;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
 * towards, by 1 block or 16 with Shift, and Alt + wheel switches between the selection and cut-out modes. K opens the
 * editor, where containers, spawners and entities are edited in 3D. While held, {@link SelectionOverlay} draws the
 * region, its cut-out boxes, the block the next click picks and the status panel.
 */
public final class ControllerClient {
    private static final double REACH = 512.0D;
    private static final long CLEAR_CONFIRM_NANOS = 3_000_000_000L;
    /** How long the keys stay on the status panel after the controller is taken up or its mode is switched. */
    private static final long KEYS_SHOWN_NANOS = 10_000_000_000L;
    private static long keysShownUntil;
    private static boolean wasHolding;
    private static Mode mode = Mode.SELECT;
    private static long clearArmed;
    // The block the next click picks, looked up once a tick while the controller is held.
    @Nullable private static BlockPos lookedAt;
    @Nullable private static KineticKeyBindings.Binding editorBinding;

    /** What the controller's clicks edit. */
    public enum Mode {
        SELECT, CUTOUT
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
        KineticClientEvents.onTick(KineticClientEvents.TickPhase.END, () -> {
            boolean held = holding();
            lookedAt = held ? target() : null;
            if (held && !wasHolding) showKeys();
            wasHolding = held;
        });
        KineticClientEvents.onLevelRender(KineticClientEvents.LevelRenderStage.AFTER_PARTICLES, ControllerClient::render);
        KineticClientEvents.onHudRender(KineticClientEvents.HudStage.END, ControllerClient::renderHud);
        KineticClientEvents.onItemTooltip(context -> {
            if (!context.itemStack().is(StructureEditorModule.CONTROLLER.get())) return;
            List<Component> lines = context.tooltip();
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.select", (int) REACH).withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.wheel").withStyle(net.minecraft.ChatFormatting.GRAY));
            lines.add(KineticI18n.translatable("tooltip.realmcontrol.structure_controller.clear").withStyle(net.minecraft.ChatFormatting.GRAY));
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
        showKeys();
    }

    /** Shows the keys on the status panel for a while, as taking the controller in hand does. */
    public static void showKeys() {
        keysShownUntil = System.nanoTime() + KEYS_SHOWN_NANOS;
    }

    /** The key that opens the editor while the controller is held, as the controls screen names it. */
    public static Component editorKeyName() {
        return editorBinding == null ? Component.literal("K") : editorBinding.translatedKeyMessage();
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
        if (context.useItem()) {
            BlockPos target = target();
            if (target == null) {
                KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.no_target", (int) REACH), true);
                return;
            }
            SelectionStore.Action action = mode == Mode.SELECT
                    ? (shift ? SelectionStore.Action.SET_B : SelectionStore.Action.SET_A)
                    : (shift ? SelectionStore.Action.CUT_B : SelectionStore.Action.CUT_A);
            StructureEditorNetwork.selectionAction(action, target, 0, 0);
        } else if (context.attack() && shift) {
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
            setMode(modes[Math.floorMod(mode.ordinal() + (context.vertical() > 0D ? -1 : 1), modes.length)]);
            KineticClientRuntime.displayClientMessage(KineticI18n.translatable("msg.realmcontrol.structure.mode",
                    KineticI18n.translatable("gui.realmcontrol.structure.mode." + mode.name().toLowerCase(java.util.Locale.ROOT))), true);
            context.cancel();
        } else if (KineticClientRuntime.controlModifierDown()) {
            int amount = (KineticClientRuntime.shiftKeyDown() ? 16 : 1) * (context.vertical() > 0D ? 1 : -1);
            StructureEditorNetwork.selectionAction(mode == Mode.SELECT ? SelectionStore.Action.PUSH : SelectionStore.Action.PUSH_CUTOUT,
                    null, facing().ordinal(), amount);
            context.cancel();
        }
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
            AABB box = SelectionOverlay.aabb(boxes.get(i));
            Optional<Vec3> hit = box.contains(eye) ? Optional.of(eye) : box.clip(eye, end);
            if (hit.isPresent() && hit.get().distanceToSqr(eye) < bestDistance) {
                bestDistance = hit.get().distanceToSqr(eye);
                best = i;
            }
        }
        return best;
    }

    private static void render(KineticClientEvents.LevelRenderContext context) {
        if (!holding()) return;
        SelectionOverlay.world(context, EditorClient.selection(), mode, facing(), lookedAt, KineticClientRuntime.shiftKeyDown());
    }

    private static void renderHud(KineticGraphics g, float partialTick) {
        if (!holding() || KineticClientRuntime.guiHidden() || KineticClientRuntime.currentScreen() != null) return;
        Selection selection = EditorClient.selection();
        SelectionOverlay.hud(g, KineticClientRuntime.guiScaledWidth(), KineticClientRuntime.guiScaledHeight(), selection, mode, lookedAt,
                SelectionOverlay.preview(selection, mode, lookedAt, KineticClientRuntime.shiftKeyDown()), editorKeyName(),
                System.nanoTime() < keysShownUntil);
    }
}
