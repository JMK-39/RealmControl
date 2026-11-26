package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.ui.NumberType;
import dev.xyat.kineticcore.api.client.gui.widget.KineticNumberField;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Places the draft in the current dimension, anchored at the specified minimum corner. */
public final class GenerateStructurePage extends KineticPage {
    private final StructureProject draft;
    private final KineticNumberField[] fields = new KineticNumberField[3];

    public GenerateStructurePage(StructureProject draft) {
        super(KineticI18n.translatable("gui.realmcontrol.structure.generate"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.draft = draft;
    }

    private static BlockPos target() {
        var player = KineticClientRuntime.localPlayer();
        if (player == null) return BlockPos.ZERO;
        HitResult hit = player.pick(512, 1, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK
                ? block.getBlockPos().relative(block.getDirection()) : player.blockPosition();
    }

    @Override
    protected void build(KineticUi ui) {
        ui().button(4, 4, 44).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::navigateBack).build();
        BlockPos start = target();
        int[] coords = {start.getX(), start.getY(), start.getZ()};
        for (int axis = 0; axis < 3; axis++) {
            Integer previous = fields[axis] == null ? null : fields[axis].getIntValue();
            fields[axis] = ui().numberField(18 + axis * 100, 66, 88, NumberType.INT).allowNegative(true)
                    .range(-30000000, 30000000).value(previous == null ? coords[axis] : previous).build();
        }
        ui().button(18, 94, 92).text(KineticI18n.translatable("gui.realmcontrol.structure.generate.aim"))
                .onClick(() -> fill(target())).build();
        var a = EditorClient.selection().a;
        ui().button(114, 94, 92).text(KineticI18n.translatable("gui.realmcontrol.structure.generate.corner"))
                .onClick(() -> { if (a != null) fill(a); }).build().setEnabled(a != null);
        var local = KineticClientRuntime.localPlayer();
        boolean hasOrigin = draft.originInCurrentWorld && draft.sourceVariant < 0 && local != null && local.level().dimension().location().toString().equals(draft.dimension);
        ui().button(210, 94, 98).text(KineticI18n.translatable("gui.realmcontrol.structure.generate.source"))
                .onClick(() -> fill(new BlockPos(draft.origin[0], draft.origin[1], draft.origin[2]))).build().setEnabled(hasOrigin);
        ui().button(18, 134, 112).text(KineticI18n.translatable("gui.realmcontrol.structure.generate")).onClick(this::confirm).build();
    }

    private void fill(BlockPos pos) {
        fields[0].setIntValue(pos.getX());
        fields[1].setIntValue(pos.getY());
        fields[2].setIntValue(pos.getZ());
    }

    private void confirm() {
        Integer x = fields[0].getIntValue(), y = fields[1].getIntValue(), z = fields[2].getIntValue();
        if (x == null || y == null || z == null) return;
        openDialog(title(), KineticI18n.translatable("gui.realmcontrol.structure.generate.confirm", draft.name, x, y, z,
                        (long)x + draft.size[0] - 1, (long)y + draft.size[1] - 1, (long)z + draft.size[2] - 1),
                title(), KineticI18n.translatable("gui.realmcontrol.worldgen.config.back"), () -> {
                    StructureEditorNetwork.generate(draft.id, new BlockPos(x, y, z));
                    navigateBack();
                }, () -> { });
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        g.scrollingTextCentered(title(), width() / 2, 8, width() - 104, 0xFFFFAA00, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.structure.generate.bounds", draft.name, draft.size[0], draft.size[1],
                draft.size[2]), 18, 34, width() - 36, 0xFFFFFFFF, false);
        for (int i = 0; i < 3; i++) g.scrollingText(net.minecraft.network.chat.Component.literal("XYZ".substring(i, i + 1)), 18 + 100*i,
                53, 88, 0xFFBBBBBB, false);
        g.wrappedText(KineticI18n.translatable("gui.realmcontrol.structure.generate.help"), 18, 164, width() - 36, 0xFFBBBBBB);
    }
}
