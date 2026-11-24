package dev.xyat.realmcontrol.structure.client;

import dev.xyat.kineticcore.api.client.event.KineticClientEvents;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.render.KineticWorldRender;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.selection.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * How the structure controller shows a selection, as building tools do. In the world: the region and its cut-out
 * boxes as tinted volumes with outlines seen through blocks, the face the wheel moves in yellow, the two corners,
 * the block the next click picks and the box that click would make while a box is being drawn. On the HUD: labels
 * that follow the world (A and B on the corners, the region's size along its edges, the size of the box being
 * drawn) and a panel with the mode, the size and, for a while after the controller is taken up, the keys. The labels
 * are drawn in the HUD pass from the camera the level pass kept, which works the same on every version.
 */
final class SelectionOverlay {
    /** The camera of the last level pass, for the labels the HUD pass draws at world positions. */
    @Nullable private static KineticWorldRender.WorldProjection frame;
    private static final int REGION = 0x4D9BFF, PROBLEM = 0xFF5555, CUTOUT = 0xFF5555, ACTIVE = 0xFFD24A, CORNER = 0x55DD77,
            TARGET = 0xFFFFFF;
    private static final int LABEL_BACK = 0xD0141A22, LABEL_TEXT = 0xFFFFFFFF, MUTED_TEXT = 0xFF9AA4B2;
    /** Lifts tinted faces off the block faces they would lie on. */
    private static final double GROW = 0.01D;

    private SelectionOverlay() {
    }

    /**
     * The box the next click would make: a plain click replaces A, Shift replaces B; a cut-out is completed only
     * with Shift. {@code null} when the click leaves a corner unset or would not change the current region.
     */
    @Nullable
    static Selection.Box preview(Selection selection, ControllerClient.Mode mode, @Nullable BlockPos target, boolean shift) {
        if (target == null) return null;
        BlockPos other;
        if (mode == ControllerClient.Mode.CUTOUT) other = shift && selection.cutouts.size() < Selection.MAX_CUTOUTS ? selection.cutA : null;
        else other = shift ? selection.a : selection.b;
        if (other == null) return null;
        Selection.Box box = Selection.Box.of(other, target);
        return mode == ControllerClient.Mode.SELECT && box.equals(selection.region()) ? null : box;
    }

    static void world(KineticClientEvents.LevelRenderContext context, Selection selection, ControllerClient.Mode mode,
                      Direction facing, @Nullable BlockPos target, boolean shift) {
        Selection.Box region = selection.region();
        Selection.Box preview = preview(selection, mode, target, shift);
        int cut = mode == ControllerClient.Mode.CUTOUT ? CUTOUT : REGION;
        frame = KineticWorldRender.captureProjection(context);
        try (KineticWorldRender.GlowBatch faces = KineticWorldRender.beginTintBatch(context)) {
            if (region != null) {
                AABB box = aabb(region).inflate(GROW);
                fill(faces, box, Selection.problem(region) == null ? REGION : PROBLEM, 0x18);
                if (mode == ControllerClient.Mode.SELECT) face(faces, box, facing, ACTIVE, 0x50);
            }
            for (int i = 0; i < selection.cutouts.size(); i++) {
                AABB box = aabb(selection.cutouts.get(i)).inflate(GROW);
                fill(faces, box, CUTOUT, 0x26);
                if (mode == ControllerClient.Mode.CUTOUT && i == selection.cutouts.size() - 1) face(faces, box, facing, ACTIVE, 0x50);
            }
            if (preview != null) fill(faces, aabb(preview).inflate(GROW), cut, 0x14);
            if (selection.a != null) fill(faces, block(selection.a).inflate(GROW * 2), CORNER, 0x58);
            if (selection.b != null) fill(faces, block(selection.b).inflate(GROW * 2), CORNER, 0x58);
            if (selection.cutA != null) fill(faces, block(selection.cutA).inflate(GROW * 2), CUTOUT, 0x58);
            if (target != null) fill(faces, block(target).inflate(GROW * 3), TARGET, 0x34);
        }
        try (KineticWorldRender.LineBatch lines = KineticWorldRender.beginLineBatch(context)) {
            if (region != null) {
                lines.boxThroughBlocks(aabb(region), Selection.problem(region) == null ? KineticWorldRender.Indicator.INFO : KineticWorldRender.Indicator.DANGER);
            }
            for (Selection.Box cutout : selection.cutouts) lines.boxThroughBlocks(aabb(cutout), KineticWorldRender.Indicator.DANGER);
            if (preview != null) {
                lines.boxThroughBlocks(aabb(preview), mode == ControllerClient.Mode.CUTOUT ? KineticWorldRender.Indicator.DANGER : KineticWorldRender.Indicator.MUTED);
            }
            if (selection.a != null) lines.boxThroughBlocks(block(selection.a), KineticWorldRender.Indicator.SUCCESS);
            if (selection.b != null) lines.boxThroughBlocks(block(selection.b), KineticWorldRender.Indicator.SUCCESS);
            if (selection.cutA != null) lines.boxThroughBlocks(block(selection.cutA), KineticWorldRender.Indicator.DANGER);
            if (target != null) lines.box(block(target), KineticWorldRender.Indicator.WARNING);
        }
    }

    /** The labels that follow the world, drawn by the HUD pass at where the last level pass saw their points. */
    private static void labels(KineticGraphics g, int screenWidth, int screenHeight, Selection selection, ControllerClient.Mode mode,
                               @Nullable BlockPos target, @Nullable Selection.Box preview) {
        KineticWorldRender.WorldProjection camera = frame;
        if (camera == null) return;
        Selection.Box region = selection.region();
        if (region != null) sizeLabels(g, camera, screenWidth, screenHeight, aabb(region), region);
        if (selection.a != null) label(g, camera, screenWidth, screenHeight, Vec3.atCenterOf(selection.a).add(0D, 0.9D, 0D), "A", 0xFF77DD77);
        if (selection.b != null) label(g, camera, screenWidth, screenHeight, Vec3.atCenterOf(selection.b).add(0D, 0.9D, 0D), "B", 0xFF77DD77);
        if (mode == ControllerClient.Mode.CUTOUT && !selection.cutouts.isEmpty()) {
            // The box the wheel moves, the newest one, says how big it is.
            Selection.Box newest = selection.cutouts.get(selection.cutouts.size() - 1);
            AABB box = aabb(newest);
            label(g, camera, screenWidth, screenHeight, new Vec3(box.getCenter().x, box.maxY + 0.4D, box.getCenter().z),
                    newest.sizeX() + "×" + newest.sizeY() + "×" + newest.sizeZ(), 0xFFFF9C9C);
        }
        if (preview != null && target != null) {
            label(g, camera, screenWidth, screenHeight, Vec3.atCenterOf(target).add(0D, 0.9D, 0D),
                    preview.sizeX() + "×" + preview.sizeY() + "×" + preview.sizeZ(), Selection.problem(preview) == null || mode == ControllerClient.Mode.CUTOUT ? LABEL_TEXT : 0xFFFF7777);
        }
    }

    /** The region's size along the three edges that meet at its corner nearest the camera. */
    private static void sizeLabels(KineticGraphics g, KineticWorldRender.WorldProjection camera, int screenWidth, int screenHeight, AABB box, Selection.Box region) {
        Vec3 middle = box.getCenter(), eye = camera.cameraPosition();
        double x = eye.x < middle.x ? box.minX : box.maxX, y = eye.y < middle.y ? box.minY : box.maxY, z = eye.z < middle.z ? box.minZ : box.maxZ;
        label(g, camera, screenWidth, screenHeight, new Vec3(middle.x, y, z), "X " + region.sizeX(), 0xFFFF8A80);
        label(g, camera, screenWidth, screenHeight, new Vec3(x, middle.y, z), "Y " + region.sizeY(), 0xFF9BE28E);
        label(g, camera, screenWidth, screenHeight, new Vec3(x, y, middle.z), "Z " + region.sizeZ(), 0xFF8AB4FF);
    }

    /** A small dark tag centred on a world point, left out when the point is behind the camera or off the screen. */
    private static void label(KineticGraphics g, KineticWorldRender.WorldProjection camera, int screenWidth, int screenHeight, Vec3 at, String text, int argb) {
        Vec2 point = camera.project(at, screenWidth, screenHeight);
        if (point == null || point.x < 0F || point.y < 0F || point.x > screenWidth || point.y > screenHeight) return;
        int width = g.textWidth(text) + 6, height = 12;
        int left = Math.round(point.x) - width / 2, top = Math.round(point.y) - height / 2;
        // Corners cut by a pixel, as the theme's rounded panels are.
        g.fill(left + 1, top, left + width - 1, top + height, LABEL_BACK);
        g.fill(left, top + 1, left + 1, top + height - 1, LABEL_BACK);
        g.fill(left + width - 1, top + 1, left + width, top + height - 1, LABEL_BACK);
        g.text(text, left + 3, top + 2, argb, false);
    }

    /** All six faces of a box in one tint. */
    private static void fill(KineticWorldRender.GlowBatch faces, AABB box, int rgb, int alpha) {
        for (Direction direction : Direction.values()) face(faces, box, direction, rgb, alpha);
    }

    private static void face(KineticWorldRender.GlowBatch faces, AABB b, Direction direction, int rgb, int alpha) {
        int argb = alpha << 24 | rgb;
        switch (direction) {
            case EAST -> faces.quad(new Vec3(b.maxX, b.minY, b.minZ), new Vec3(b.maxX, b.maxY, b.minZ), new Vec3(b.maxX, b.maxY, b.maxZ), new Vec3(b.maxX, b.minY, b.maxZ), argb);
            case WEST -> faces.quad(new Vec3(b.minX, b.minY, b.minZ), new Vec3(b.minX, b.maxY, b.minZ), new Vec3(b.minX, b.maxY, b.maxZ), new Vec3(b.minX, b.minY, b.maxZ), argb);
            case UP -> faces.quad(new Vec3(b.minX, b.maxY, b.minZ), new Vec3(b.maxX, b.maxY, b.minZ), new Vec3(b.maxX, b.maxY, b.maxZ), new Vec3(b.minX, b.maxY, b.maxZ), argb);
            case DOWN -> faces.quad(new Vec3(b.minX, b.minY, b.minZ), new Vec3(b.maxX, b.minY, b.minZ), new Vec3(b.maxX, b.minY, b.maxZ), new Vec3(b.minX, b.minY, b.maxZ), argb);
            case SOUTH -> faces.quad(new Vec3(b.minX, b.minY, b.maxZ), new Vec3(b.maxX, b.minY, b.maxZ), new Vec3(b.maxX, b.maxY, b.maxZ), new Vec3(b.minX, b.maxY, b.maxZ), argb);
            case NORTH -> faces.quad(new Vec3(b.minX, b.minY, b.minZ), new Vec3(b.maxX, b.minY, b.minZ), new Vec3(b.maxX, b.maxY, b.minZ), new Vec3(b.minX, b.maxY, b.minZ), argb);
        }
    }

    static AABB aabb(Selection.Box box) {
        return new AABB(box.min().getX(), box.min().getY(), box.min().getZ(), box.max().getX() + 1, box.max().getY() + 1, box.max().getZ() + 1);
    }

    static AABB block(BlockPos pos) {
        return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
    }

    // ---- HUD ----

    /** One piece of the status row: plain text in a colour, or a tag of the theme. */
    private record Piece(Component text, int argb, @Nullable KineticTheme.Indicator tag) {
        Piece(Component text, int argb) {
            this(text, argb, null);
        }

        Piece(Component text, KineticTheme.Indicator tag) {
            this(text, 0, tag);
        }

        int width(KineticGraphics g) {
            return tag != null ? KineticTheme.tagWidth(g, text) : g.textWidth(text);
        }
    }

    /**
     * The labels in the world, and over them the status panel above the hotbar: the mode, then the region's size,
     * blocks and tier (or what to click next), and below it the keys while {@code keysShown}: for a while after the
     * controller is taken up or its mode is switched, so they do not stay in the way. Status fields wrap to fit
     * smaller screens; on a short screen the key hints are hidden.
     */
    static void hud(KineticGraphics g, int screenWidth, int screenHeight, Selection selection, ControllerClient.Mode mode,
                    @Nullable BlockPos target, @Nullable Selection.Box preview, Component editorKey, boolean keysShown) {
        labels(g, screenWidth, screenHeight, selection, mode, target, preview);
        Selection.Box region = selection.region();
        List<Piece> pieces = new ArrayList<>();
        boolean cutting = mode == ControllerClient.Mode.CUTOUT;
        // The mode in the colour its boxes have in the world: the region blue, cut-outs red.
        pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.mode." + (cutting ? "cutout" : "select")),
                cutting ? KineticTheme.Indicator.DANGER : KineticTheme.Indicator.INFO));
        if (region != null) {
            String problem = Selection.problem(region);
            pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.hud.region", region.sizeX(), region.sizeY(), region.sizeZ()),
                    problem == null ? LABEL_TEXT : 0xFFFF7777));
            pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.hud.blocks", region.volume()), 0xFFCCCCCC));
            int footprint = Math.max(region.sizeX(), region.sizeZ());
            String tier = footprint <= StructureProject.SMALL_LIMIT ? "small" : footprint <= StructureProject.MEDIUM_LIMIT ? "medium" : "mega";
            pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.tier." + tier), KineticTheme.Indicator.MUTED));
            if (problem != null) pieces.add(new Piece(KineticI18n.translatable(problem, Selection.MAX_FOOTPRINT), 0xFFFF7777));
        } else {
            pieces.add(new Piece(KineticI18n.translatable(selection.a == null
                    ? "gui.realmcontrol.structure.hud.set_a" : "gui.realmcontrol.structure.hud.set_b"), LABEL_TEXT));
        }
        if (!selection.cutouts.isEmpty()) {
            pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.hud.cutouts", selection.cutouts.size()), 0xFFFF9C9C));
        }
        if (preview != null) {
            pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.hud.preview", preview.sizeX(), preview.sizeY(), preview.sizeZ()),
                    0xFFFFD24A));
        } else if (target != null) {
            pieces.add(new Piece(KineticI18n.translatable("gui.realmcontrol.structure.hud.target", target.getX(), target.getY(), target.getZ()),
                    MUTED_TEXT));
        }
        Component keys = KineticI18n.translatable("gui.realmcontrol.structure.hud.keys." + (cutting ? "cutout" : "select"), editorKey);

        int gap = 6, rowHeight = KineticTheme.TAG_HEIGHT, pad = 5;
        int limit = Math.max(pad * 2 + 1, screenWidth - 16), contentLimit = limit - pad * 2;
        List<List<Piece>> rows = new ArrayList<>();
        List<Integer> rowWidths = new ArrayList<>();
        List<Piece> row = new ArrayList<>();
        int rowWidth = 0, statusWidth = 0;
        for (Piece piece : pieces) {
            int pieceWidth = Math.min(piece.width(g), contentLimit);
            if (!row.isEmpty() && rowWidth + gap + pieceWidth > contentLimit) {
                rows.add(row);
                rowWidths.add(rowWidth);
                statusWidth = Math.max(statusWidth, rowWidth);
                row = new ArrayList<>();
                rowWidth = 0;
            }
            rowWidth += (row.isEmpty() ? 0 : gap) + pieceWidth;
            row.add(piece);
        }
        rows.add(row);
        rowWidths.add(rowWidth);
        statusWidth = Math.max(statusWidth, rowWidth);
        boolean showKeys = keysShown && screenHeight >= 240;
        int keysWidth = showKeys ? Math.min(g.textWidth(keys), limit - pad * 2) : 0;
        int width = Math.min(limit, Math.max(statusWidth, keysWidth) + pad * 2);
        int height = pad * 2 + rows.size() * rowHeight + (showKeys ? rowHeight : 0) - 2;
        // Above the action bar line (screen height - 72), where the controller's own messages appear.
        int left = (screenWidth - width) / 2, top = screenHeight - 76 - height;
        KineticTheme.surface(g, left, top, width, height, KineticTheme.Surface.PANEL);
        int y = top + pad;
        for (int i = 0; i < rows.size(); i++) {
            int x = left + Math.max(pad, (width - rowWidths.get(i)) / 2);
            for (Piece piece : rows.get(i)) {
                // A single long translated field scrolls within its row instead of escaping the panel.
                if (piece.width(g) > contentLimit) g.scrollingText(piece.text(), x, y + 1, contentLimit,
                        piece.tag() == null ? piece.argb() : LABEL_TEXT, false);
                else if (piece.tag() != null) KineticTheme.tag(g, piece.text(), x, y - 1, piece.tag());
                else g.text(piece.text(), x, y + 1, piece.argb(), false);
                x += Math.min(piece.width(g), contentLimit) + gap;
            }
            y += rowHeight;
        }
        if (showKeys) g.scrollingTextCentered(keys, left + width / 2, y + 2, width - pad * 2, MUTED_TEXT, false);
    }
}
