package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticStructureView;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.world.structure.KineticBlockSnapshot;
import dev.xyat.realmcontrol.structure.client.CutoutSnapshots;
import dev.xyat.realmcontrol.structure.client.EditorClient;
import dev.xyat.realmcontrol.structure.project.Cutouts;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

/**
 * A page that edits one draft: Back at the top left, Save at the top right, the draft's name beside the title, and
 * (when {@link #previewX()} is inside the page) the 3D preview on the right with cut-outs shown as the subclass asks.
 */
abstract class ProjectPage extends KineticPage implements EditorClient.Listener {
    protected static final int TOP = 30, SAVE_W = 48;
    protected final StructureProject project;
    @Nullable protected Cutouts cutouts;
    protected KineticStructureView preview;
    @Nullable private StructurePreviewClient.Result previewResult;
    private StructurePreviewClient.Listener previewListener;
    private int previewRequest;
    protected KineticButton saveButton;

    protected ProjectPage(String titleKey, StructureProject project) {
        super(KineticI18n.translatable(titleKey));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.project = StructureProject.fromJson(project.toJson());
    }

    /** Left edge of the preview; the page width or more for no preview. */
    protected abstract int previewX();

    /** Top of the preview. */
    protected int previewY() {
        return TOP;
    }

    /** Bottom of the preview. */
    protected int previewBottom() {
        return height() - 4;
    }

    /** How cut-outs show in the preview: {@code null} as they are not applied, else hidden or red. */
    @Nullable
    protected Boolean cutoutsRed() {
        return Boolean.FALSE;
    }

    /** Width the buttons at the top right take, Save included; the title stays clear of them. */
    protected int headerRightWidth() {
        return SAVE_W;
    }

    protected abstract void buildFields(KineticUi ui);

    protected abstract void save();

    @Override
    protected final void build(KineticUi ui) {
        ui().button(4, 4, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
        saveButton = ui().button(width() - 4 - SAVE_W, 4, SAVE_W).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_all"))
                .onClick(this::save).build();
        buildFields(ui);
        if (previewX() < width()) {
            preview = ui.add(new KineticStructureView(previewX(), previewY(), width() - 4 - previewX(), previewBottom() - previewY()));
            preview.setLoading(0F);
            previewListener = new StructurePreviewClient.Listener() {
                @Override
                public void progress(float share) {
                    if (preview != null && preview.snapshot() == null) preview.setLoading(share);
                }

                @Override
                public void finished(StructurePreviewClient.Result result) {
                    previewResult = result;
                    refreshPreview();
                }

                @Override
                public void failed(Component reason) {
                    if (preview != null) preview.setMessage(reason);
                }
            };
            StructurePreviewClient.request(StructureEditorPage.PREVIEW_PREFIX + project.id, 0, previewListener);
        }
        dev.xyat.realmcontrol.structure.network.StructureEditorNetwork.request(
                dev.xyat.realmcontrol.structure.network.StructureEditorNetwork.ProjectRequestPacket.Kind.DETAIL, project.id, "");
    }

    /** The preview's snapshot as it arrived, or {@code null} while loading. */
    @Nullable
    protected KineticBlockSnapshot baseSnapshot() {
        return previewResult == null ? null : previewResult.snapshot();
    }

    /** Applies the current cut-outs to the preview again, off the render thread. */
    protected void refreshPreview() {
        StructurePreviewClient.Result result = previewResult;
        if (result == null || preview == null) return;
        Boolean red = cutoutsRed();
        Cutouts current = cutouts;
        if (red == null || current == null || current.isEmpty()) {
            preview.setSnapshot(result.snapshot());
            return;
        }
        Cutouts copy = current.copy();
        int request = ++previewRequest;
        CompletableFuture.supplyAsync(() -> CutoutSnapshots.apply(result.snapshot(), copy, red)).thenAccept(snapshot ->
                KineticClientRuntime.execute(() -> {
                    if (request != previewRequest || preview == null) return;
                    // Keep the camera where the user left it.
                    preview.setSnapshot(snapshot);
                }));
    }

    @Override
    public void detailArrived(StructureProject arrived, Cutouts arrivedCutouts) {
        if (!arrived.id.equals(project.id)) return;
        cutouts = arrivedCutouts;
        cutoutsArrived();
        refreshPreview();
    }

    /** Called when the draft's cut-outs arrive from the server. */
    protected void cutoutsArrived() {
    }

    @Override
    protected void onRemoved() {
        if (previewListener != null) StructurePreviewClient.cancel(previewListener);
        if (preview != null) preview.release();
        super.onRemoved();
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, width(), height());
        KineticTheme.panel(g, 0, 0, width(), height());
        KineticTheme.separator(g, 4, 24, width() - 8);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int titleRoom = Math.max(1, 2 * Math.min(width() / 2 - 48, width() - 4 - headerRightWidth() - 4 - width() / 2));
        Component title = KineticI18n.translatable("gui.realmcontrol.structure.page_title", project.name, title());
        g.scrollingTextCentered(title, width() / 2, 8, titleRoom, 0xFFFFAA00, false);
    }
}
