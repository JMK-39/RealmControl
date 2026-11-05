package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.input.MouseDragInput;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.render.KineticRuntimeTextures;
import dev.xyat.kineticcore.api.client.gui.render.KineticTexture;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.client.gui.widget.list.KineticRowList;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.kineticcore.api.world.structure.KineticSnapshotThumbnail;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.structure.selection.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Cut-outs of a draft, seen from above: the map is coloured by each column's top block, cut-out columns are hatched
 * red. Boxes are dragged out on the map, the brush and eraser paint single columns, fill takes connected columns
 * with the same top block. Every tool works on the height range set beside the map (the whole height by default).
 * The 3D preview shows cut-outs hidden or red. Changes stay on this page until Save; undo and redo go back and forth
 * through them.
 */
public final class CutoutPage extends ProjectPage {
    private static final int MAP_X = 4, MAP_Y = 46, SIDE_X = 322, LIST_Y = 70, LIST_H = 96, HISTORY = 64;
    private static final String[] TOOLS = {"box", "brush", "eraser", "fill"};

    private String tool = "box";
    private int brush = 1;
    private boolean red = true;
    private final Deque<Cutouts> undo = new ArrayDeque<>();
    private final Deque<Cutouts> redo = new ArrayDeque<>();
    private final List<KineticButton> toolButtons = new ArrayList<>();
    private KineticNumberField fromField, toField;
    private KineticButton brushButton, redButton, undoButton, redoButton, flipButton, deleteButton;
    private BoxList boxList;
    @Nullable private KineticTexture map;
    @Nullable private int[] topY;
    @Nullable private String[] topName;
    private int dragStartX = -1, dragStartZ = -1, dragEndX, dragEndZ;
    private boolean painting;
    private int selectedRow = -1;
    private boolean dirty;

    public CutoutPage(StructureProject project) {
        super("gui.realmcontrol.structure.cutouts.title", project);
    }

    @Override
    protected int previewX() {
        return SIDE_X;
    }

    @Override
    protected int previewY() {
        return LIST_Y + LIST_H + 4;
    }

    @Override
    protected Boolean cutoutsRed() {
        return red;
    }

    private int mapSize() {
        return height() - 4 - MAP_Y;
    }

    /** Pixels per column on the map. */
    private float cell() {
        return mapSize() / (float) Math.max(project.size[0], project.size[2]);
    }

    @Override
    protected void buildFields(KineticUi ui) {
        toolButtons.clear();
        int x = 4, y = 28;
        for (String key : TOOLS) {
            KineticButton button = ui().button(x, y, 40).text(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.tool." + key))
                    .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.tool." + key + ".tooltip"))
                    .onClick(() -> {
                        tool = key;
                        updateButtons();
                    }).build();
            toolButtons.add(button);
            x += 44;
        }
        brushButton = ui().button(x, y, 52).text(brushText()).tooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.brush.tooltip"))
                .onClick(button -> {
                    brush = brush >= 9 ? 1 : brush + 2;
                    button.setText(brushText());
                }).build();
        x += 56;
        ui().button(x, y, 64).text(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.natural"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.natural.tooltip")).onClick(this::markNatural).build();
        x += 68;
        ui().button(x, y, 64).text(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.import"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.import.tooltip")).onClick(this::importSelection).build();
        x += 68;
        undoButton = ui().button(x, y, 40).text(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.undo")).onClick(this::undo).build();
        x += 44;
        redoButton = ui().button(x, y, 40).text(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.redo")).onClick(this::redo).build();
        redButton = ui().button(width() - 4 - 72, y, 72).text(redText()).tooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.show.tooltip"))
                .onClick(button -> {
                    red = !red;
                    button.setText(redText());
                    refreshPreview();
                }).build();

        int sideW = width() - 4 - SIDE_X;
        fromField = ui().numberField(SIDE_X + 52, MAP_Y, 44, NumberType.INT).range(0, project.size[1] - 1).value(0).build();
        toField = ui().numberField(SIDE_X + 112, MAP_Y, 44, NumberType.INT).range(0, project.size[1] - 1).value(project.size[1] - 1).build();
        fromField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.range.tooltip"));
        toField.setTooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.range.tooltip"));
        int listButtonsX = SIDE_X + sideW - 2 * 48 - 4;
        flipButton = ui().button(listButtonsX, MAP_Y, 48).text(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.flip"))
                .tooltip(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.flip.tooltip")).onClick(this::flipSelected).build();
        deleteButton = ui().button(listButtonsX + 52, MAP_Y, 48).text(KineticI18n.translatable("gui.realmcontrol.structure.action.delete"))
                .onClick(this::deleteSelected).build();
        boxList = ui.add(new BoxList(SIDE_X, LIST_Y, sideW, LIST_H));
        bindDeleteAction(() -> selectedRow >= 0, this::deleteSelected);
        updateButtons();
    }

    private Component brushText() {
        return KineticI18n.translatable("gui.realmcontrol.structure.cutouts.brush", brush);
    }

    private Component redText() {
        return KineticI18n.translatable(red ? "gui.realmcontrol.structure.cutouts.show.red" : "gui.realmcontrol.structure.cutouts.show.hidden");
    }

    private void updateButtons() {
        for (int i = 0; i < toolButtons.size(); i++) toolButtons.get(i).setSelected(TOOLS[i].equals(tool));
        if (brushButton != null) brushButton.setEnabled(tool.equals("brush") || tool.equals("eraser"));
        if (undoButton != null) undoButton.setEnabled(!undo.isEmpty());
        if (redoButton != null) redoButton.setEnabled(!redo.isEmpty());
        boolean box = cutouts != null && selectedRow >= 0 && selectedRow < cutouts.boxes.size();
        if (flipButton != null) flipButton.setEnabled(box);
        if (deleteButton != null) deleteButton.setEnabled(cutouts != null && selectedRow >= 0);
        if (saveButton != null) saveButton.setEnabled(cutouts != null);
        if (boxList != null && cutouts != null) {
            List<Integer> rows = new ArrayList<>();
            for (int i = 0; i < cutouts.boxes.size() + (cutouts.mask.isEmpty() ? 0 : 1); i++) rows.add(i);
            boxList.setItems(rows);
        }
    }

    @Override
    protected void cutoutsArrived() {
        undo.clear();
        redo.clear();
        dirty = false;
        selectedRow = -1;
        updateButtons();
    }

    /** Notes the state before a change, for undo. */
    private void remember() {
        if (cutouts == null) return;
        undo.push(cutouts.copy());
        while (undo.size() > HISTORY) undo.removeLast();
        redo.clear();
        dirty = true;
    }

    private void changed() {
        updateButtons();
        refreshPreview();
    }

    private void undo() {
        if (undo.isEmpty() || cutouts == null) return;
        redo.push(cutouts.copy());
        cutouts = undo.pop();
        dirty = true;
        changed();
    }

    private void redo() {
        if (redo.isEmpty() || cutouts == null) return;
        undo.push(cutouts.copy());
        cutouts = redo.pop();
        dirty = true;
        changed();
    }

    private int fromY() {
        Integer from = fromField.getIntValue(), to = toField.getIntValue();
        int a = from == null ? 0 : from, b = to == null ? project.size[1] - 1 : to;
        return Math.max(0, Math.min(a, b));
    }

    private int toY() {
        Integer from = fromField.getIntValue(), to = toField.getIntValue();
        int a = from == null ? 0 : from, b = to == null ? project.size[1] - 1 : to;
        return Math.min(project.size[1] - 1, Math.max(a, b));
    }

    private void paint(int cx, int cz, boolean add) {
        int radius = brush / 2;
        for (int z = cz - radius; z <= cz + radius; z++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x < 0 || z < 0 || x >= project.size[0] || z >= project.size[2]) continue;
                long key = Cutouts.column(x, z);
                if (add) {
                    int[] range = cutouts.mask.get(key);
                    cutouts.mask.put(key, range == null ? new int[]{fromY(), toY()}
                            : new int[]{Math.min(range[0], fromY()), Math.max(range[1], toY())});
                } else {
                    cutouts.mask.remove(key);
                }
            }
        }
    }

    /** Connected columns whose top block is the same as at the clicked one. */
    private void fill(int cx, int cz) {
        if (topName == null) return;
        String target = topName[cx + cz * project.size[0]];
        Set<Long> seen = new HashSet<>();
        Deque<int[]> open = new ArrayDeque<>();
        open.add(new int[]{cx, cz});
        while (!open.isEmpty() && seen.size() < 1_048_576) {
            int[] at = open.poll();
            int x = at[0], z = at[1];
            if (x < 0 || z < 0 || x >= project.size[0] || z >= project.size[2]) continue;
            long key = Cutouts.column(x, z);
            if (!seen.add(key) || !java.util.Objects.equals(topName[x + z * project.size[0]], target)) continue;
            cutouts.mask.put(key, new int[]{fromY(), toY()});
            open.add(new int[]{x + 1, z});
            open.add(new int[]{x - 1, z});
            open.add(new int[]{x, z + 1});
            open.add(new int[]{x, z - 1});
        }
    }

    /** Marks columns that hold only natural blocks (ground, stone, plants, water, snow) over the height range. */
    private void markNatural() {
        KineticBlockSnapshot snapshot = baseSnapshot();
        if (snapshot == null || cutouts == null) return;
        remember();
        int scale = snapshot.scale(), marked = 0;
        for (int z = 0; z < project.size[2]; z++) {
            for (int x = 0; x < project.size[0]; x++) {
                boolean natural = true;
                for (int y = fromY() / scale; y <= toY() / scale && natural; y++) {
                    BlockState state = snapshot.state(x / scale, y, z / scale);
                    natural = state.isAir() || isNatural(state);
                }
                if (natural) {
                    cutouts.mask.put(Cutouts.column(x, z), new int[]{fromY(), toY()});
                    marked++;
                }
            }
        }
        dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.natural.done", marked));
        changed();
    }

    private static boolean isNatural(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.SNOW)
                || state.is(BlockTags.ICE) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY) || state.canBeReplaced()
                || !state.getFluidState().isEmpty();
    }

    /** Adds the cut-out boxes drawn with the controller in the world, where they overlap this draft. */
    private void importSelection() {
        if (cutouts == null) return;
        Selection selection = EditorClient.selection();
        remember();
        int added = 0;
        for (Selection.Box box : selection.cutouts) {
            int minX = Math.max(box.min().getX() - project.origin[0], 0), minY = Math.max(box.min().getY() - project.origin[1], 0);
            int minZ = Math.max(box.min().getZ() - project.origin[2], 0);
            int maxX = Math.min(box.max().getX() - project.origin[0], project.size[0] - 1), maxY = Math.min(box.max().getY() - project.origin[1], project.size[1] - 1);
            int maxZ = Math.min(box.max().getZ() - project.origin[2], project.size[2] - 1);
            if (minX > maxX || minY > maxY || minZ > maxZ || cutouts.boxes.size() >= Cutouts.MAX_BOXES) continue;
            cutouts.boxes.add(new Cutouts.Box("#" + (cutouts.boxes.size() + 1), minX, minY, minZ, maxX, maxY, maxZ, false));
            added++;
        }
        dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays.toast(KineticI18n.translatable("msg.realmcontrol.structure.cutout.imported", added));
        if (added == 0) undo.pop();
        changed();
    }

    private void flipSelected() {
        if (cutouts == null || selectedRow < 0 || selectedRow >= cutouts.boxes.size()) return;
        remember();
        Cutouts.Box box = cutouts.boxes.get(selectedRow);
        cutouts.boxes.set(selectedRow, new Cutouts.Box(box.name(), box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), !box.add()));
        changed();
    }

    private void deleteSelected() {
        if (cutouts == null || selectedRow < 0) return;
        remember();
        if (selectedRow < cutouts.boxes.size()) cutouts.boxes.remove(selectedRow);
        else cutouts.mask.clear();
        selectedRow = -1;
        changed();
    }

    @Override
    protected void save() {
        if (cutouts == null) return;
        StructureEditorNetwork.save(project, cutouts);
        dirty = false;
    }

    /** The map column under the mouse, or {@code null}. */
    @Nullable
    private int[] column(double mx, double my) {
        float cell = cell();
        int x = (int) Math.floor((mx - MAP_X) / cell), z = (int) Math.floor((my - MAP_Y) / cell);
        return x < 0 || z < 0 || x >= project.size[0] || z >= project.size[2] ? null : new int[]{x, z};
    }

    @Override
    protected boolean onMouseClick(MouseInput input) {
        int[] at = cutouts == null || !input.isLeft() ? null : column(input.x(), input.y());
        if (at == null) return false;
        remember();
        switch (tool) {
            case "box" -> {
                dragStartX = dragEndX = at[0];
                dragStartZ = dragEndZ = at[1];
            }
            case "brush", "eraser" -> {
                painting = true;
                paint(at[0], at[1], tool.equals("brush"));
            }
            case "fill" -> {
                fill(at[0], at[1]);
                changed();
            }
            default -> {
            }
        }
        return true;
    }

    @Override
    protected boolean onMouseDrag(MouseDragInput input) {
        if (cutouts == null || (!painting && dragStartX < 0)) return false;
        float cell = cell();
        int x = Math.max(0, Math.min(project.size[0] - 1, (int) Math.floor((input.x() - MAP_X) / cell)));
        int z = Math.max(0, Math.min(project.size[2] - 1, (int) Math.floor((input.y() - MAP_Y) / cell)));
        if (painting) paint(x, z, tool.equals("brush"));
        else {
            dragEndX = x;
            dragEndZ = z;
        }
        return true;
    }

    @Override
    protected boolean onMouseRelease(MouseInput input) {
        if (painting) {
            painting = false;
            changed();
            return true;
        }
        if (dragStartX >= 0 && cutouts != null) {
            if (cutouts.boxes.size() < Cutouts.MAX_BOXES) {
                cutouts.boxes.add(new Cutouts.Box("#" + (cutouts.boxes.size() + 1), Math.min(dragStartX, dragEndX), fromY(),
                        Math.min(dragStartZ, dragEndZ), Math.max(dragStartX, dragEndX), toY(), Math.max(dragStartZ, dragEndZ), false));
            }
            dragStartX = -1;
            changed();
            return true;
        }
        return false;
    }

    @Override
    protected boolean onCloseRequested() {
        if (!dirty) return false;
        openDialog(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.unsaved.title"),
                KineticI18n.translatable("gui.realmcontrol.structure.cutouts.unsaved.message"),
                KineticI18n.translatable("gui.realmcontrol.structure.cutouts.unsaved.discard"),
                KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"),
                () -> {
                    dirty = false;
                    navigateBack();
                }, () -> { });
        return true;
    }

    /** Builds the top-down map texture once the preview has arrived. */
    private void ensureMap() {
        KineticBlockSnapshot snapshot = baseSnapshot();
        if (map != null || snapshot == null) return;
        int sizeX = project.size[0], sizeZ = project.size[2], scale = snapshot.scale();
        int[] argb = new int[sizeX * sizeZ];
        topY = new int[sizeX * sizeZ];
        topName = new String[sizeX * sizeZ];
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int i = x + z * sizeX, color = 0xFF202428;
                topY[i] = -1;
                for (int y = snapshot.sizeY() - 1; y >= 0; y--) {
                    int index = snapshot.index(x / scale, y, z / scale);
                    if (index == 0) continue;
                    BlockState state = snapshot.paletteState(index);
                    int rgb = snapshot.isMissing(index) ? 0xD03030 : state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
                    // Higher columns are drawn lighter, so height reads on the map.
                    float shade = 0.65F + 0.35F * y / Math.max(1, snapshot.sizeY() - 1);
                    color = 0xFF000000 | ((int) (((rgb >> 16) & 0xFF) * shade) << 16) | ((int) (((rgb >> 8) & 0xFF) * shade) << 8) | (int) ((rgb & 0xFF) * shade);
                    topY[i] = y * scale;
                    topName[i] = snapshot.paletteName(index);
                    break;
                }
                argb[i] = color;
            }
        }
        // Each column becomes a square of pixels, so the smooth scaling of runtime textures keeps the cells sharp.
        int zoom = Math.max(1, Math.min(16, 512 / Math.max(sizeX, sizeZ)));
        int[] pixels = new int[sizeX * zoom * sizeZ * zoom];
        for (int py = 0; py < sizeZ * zoom; py++) {
            for (int px = 0; px < sizeX * zoom; px++) pixels[px + py * sizeX * zoom] = argb[px / zoom + (py / zoom) * sizeX];
        }
        map = KineticRuntimeTextures.upload("realmcontrol", "structure_cutout_map/" + Math.abs(project.id.hashCode()),
                KineticSnapshotThumbnail.encodePng(pixels, sizeX * zoom, sizeZ * zoom));
    }

    @Override
    protected void onRemoved() {
        if (map != null) KineticRuntimeTextures.release(map);
        map = null;
        super.onRemoved();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        super.renderBackground(g, mx, my, pt);
        KineticTheme.surface(g, MAP_X, MAP_Y, mapSize(), mapSize(), KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        super.renderForeground(g, mx, my, pt);
        ensureMap();
        float cell = cell();
        int w = Math.round(project.size[0] * cell), h = Math.round(project.size[2] * cell);
        if (map != null) g.texture(map, MAP_X, MAP_Y, w, h, 0, 0, map.textureWidth(), map.textureHeight());
        else g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.loading"), MAP_X + mapSize() / 2, MAP_Y + mapSize() / 2,
                mapSize() - 8, 0xFFCCCCCC, false);
        if (cutouts != null) {
            for (var entry : cutouts.mask.entrySet()) {
                long key = entry.getKey();
                int x = (int) (key >> 32), z = (int) key;
                rect(g, x, z, x, z, cell, 0x80E03030);
            }
            for (int i = 0; i < cutouts.boxes.size(); i++) {
                Cutouts.Box box = cutouts.boxes.get(i);
                int color = box.add() ? 0x6040C040 : 0x70E03030;
                rect(g, box.minX(), box.minZ(), box.maxX(), box.maxZ(), cell, color);
                int x1 = MAP_X + Math.round(box.minX() * cell), z1 = MAP_Y + Math.round(box.minZ() * cell);
                int x2 = MAP_X + Math.round((box.maxX() + 1) * cell), z2 = MAP_Y + Math.round((box.maxZ() + 1) * cell);
                g.outline(x1, z1, x2 - x1, z2 - z1, i == selectedRow ? 0xFFFFDD33 : box.add() ? 0xFF55DD55 : 0xFFFF5555);
            }
        }
        if (dragStartX >= 0) {
            rect(g, Math.min(dragStartX, dragEndX), Math.min(dragStartZ, dragEndZ), Math.max(dragStartX, dragEndX), Math.max(dragStartZ, dragEndZ), cell, 0x60FFDD33);
        }
        g.outline(MAP_X, MAP_Y, w, h, 0xFF606A78);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.cutouts.range"), SIDE_X, MAP_Y + 4, 48, 0xFFFFFFFF, false);
        g.scrollingText(Component.literal("–"), SIDE_X + 100, MAP_Y + 4, 8, 0xFFFFFFFF, false);
        int[] at = column(mx, my);
        if (at != null && topY != null) {
            int i = at[0] + at[1] * project.size[0];
            Component line = topName[i] == null
                    ? KineticI18n.translatable("gui.realmcontrol.structure.cutouts.column_empty", at[0], at[1])
                    : KineticI18n.translatable("gui.realmcontrol.structure.cutouts.column", at[0], at[1], topName[i], topY[i]);
            showTooltip(line);
        }
    }

    private static void rect(KineticGraphics g, int minX, int minZ, int maxX, int maxZ, float cell, int argb) {
        g.fill(MAP_X + Math.round(minX * cell), MAP_Y + Math.round(minZ * cell), MAP_X + Math.round((maxX + 1) * cell), MAP_Y + Math.round((maxZ + 1) * cell), argb);
    }

    /** Boxes in order, then the painted columns as one row. */
    private final class BoxList extends KineticRowList<Integer> {
        BoxList(int x, int y, int width, int height) {
            super(x, y, width, height, 16);
        }

        @Override
        protected void renderRow(KineticGraphics g, Integer row, int index, int x, int y, int width, int height, boolean hovered, boolean selected) {
            if (cutouts == null) return;
            Component text;
            int color;
            if (row < cutouts.boxes.size()) {
                Cutouts.Box box = cutouts.boxes.get(row);
                text = KineticI18n.translatable(box.add() ? "gui.realmcontrol.structure.cutouts.row.add" : "gui.realmcontrol.structure.cutouts.row.cut",
                        box.name(), box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());
                color = box.add() ? 0xFF77DD77 : 0xFFFF7777;
            } else {
                text = KineticI18n.translatable("gui.realmcontrol.structure.cutouts.row.mask", cutouts.mask.size());
                color = 0xFFFF7777;
            }
            if (row == selectedRow) KineticTheme.stateOutline(g, x, y, width, height, true, false, false);
            g.scrollingText(text, x + 4, y + (height - g.lineHeight()) / 2 + 1, width - 8, color, false);
        }

        @Override
        protected boolean onRowClick(Integer row, int index, MouseInput input) {
            if (!input.isLeft()) return false;
            selectedRow = row;
            updateButtons();
            return true;
        }

        @Override
        protected Component emptyText() {
            return KineticI18n.translatable("gui.realmcontrol.structure.cutouts.none");
        }
    }
}
