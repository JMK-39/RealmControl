package dev.xyat.realmcontrol.structure.client.gui;

import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.selector.KineticSelectors;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.KineticButton;
import dev.xyat.kineticcore.api.client.gui.widget.KineticDropdown;
import dev.xyat.kineticcore.api.client.gui.widget.KineticTextField;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.network.BlockEditNetwork;
import dev.xyat.realmcontrol.structure.project.BlockStates;
import dev.xyat.realmcontrol.structure.project.StructureProject;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Edits the exact selected block and its state; the server commits the replacement to the draft. */
public final class BlockEditPage extends KineticPage {
    static final String TEXT = "gui.realmcontrol.structure.block_edit.";
    private final StructureProject project;
    private final BlockPos position;
    private final String original;
    private final IntConsumer saved;
    private String replacement;
    private KineticTextField stateField;
    private KineticButton back, apply, picker, properties, remove;
    private int pending;
    private long submitted;
    @Nullable private Component status;

    BlockEditPage(StructureProject project, BlockPos position, String original, IntConsumer saved) {
        super(text("title"));
        useCanvas(440, 170, SAFE_MARGIN);
        this.project = project;
        this.position = position;
        this.original = original;
        this.replacement = original;
        this.saved = saved;
    }

    @Override
    protected void build(KineticUi ui) {
        back = ui.button(4, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
        apply = ui.button(width() - 68, 4, 64).text(text("save")).onClick(this::save).build();
        stateField = ui.textField(12, 75, width() - 24).build();
        stateField.limitTextLength(BlockEditNetwork.MAX_STATE);
        stateField.setTextValue(replacement);
        stateField.setDefaultText(original);
        stateField.onTextChange(value -> {
            replacement = value == null ? "" : value;
            status = null;
            updateControls();
        });
        picker = ui.button(12, 101, 105).text(text("choose")).onClick(this::choose).build();
        properties = ui.button(121, 101, 112).text(text("properties")).onClick(this::properties).build();
        remove = ui.button(237, 101, 90).text(text("remove")).onClick(() -> set(BlockStates.AIR)).build();
        updateControls();
    }

    private void choose() {
        KineticSelectors.openItemSelectorWithOptions(KineticSelectors.ItemSelectorOptions.itemsOnly(null, null,
                stack -> stack.getItem() instanceof BlockItem item && !item.getBlock().defaultBlockState().hasBlockEntity()), selection -> {
            if (selection.isItem() && selection.stack().getItem() instanceof BlockItem item) {
                set(BlockStates.serialize(item.getBlock().defaultBlockState()));
            }
        });
    }

    private void properties() {
        BlockState state = state();
        if (state != null && !state.getProperties().isEmpty()) openChild(new PropertiesPage(state, value -> set(BlockStates.serialize(value))));
    }

    private void set(String value) {
        replacement = value;
        stateField.setTextValue(value);
        updateControls();
    }

    @Nullable
    private BlockState state() {
        return BlockStates.parse(replacement.trim());
    }

    private void updateControls() {
        if (remove == null) return;
        BlockState state = state();
        boolean ready = pending == 0;
        boolean valid = state != null && !state.hasBlockEntity();
        back.setEnabled(ready);
        picker.setEnabled(ready);
        properties.setEnabled(ready && valid && !state.getProperties().isEmpty());
        remove.setEnabled(ready && !BlockStates.AIR.equals(replacement.trim()));
        stateField.setTextEditable(ready);
        apply.setEnabled(ready && valid && !original.equals(normalized(state)));
    }

    private static String normalized(BlockState state) {
        return state.isAir() ? BlockStates.AIR : BlockStates.serialize(state);
    }

    private void save() {
        BlockState state = state();
        if (pending != 0 || state == null || state.hasBlockEntity()) return;
        String value = normalized(state);
        if (original.equals(value)) return;
        pending = BlockEditNetwork.requestId();
        submitted = System.nanoTime();
        status = text("saving");
        updateControls();
        BlockEditNetwork.save(pending, project, position, original, value);
    }

    public static void receive(BlockEditNetwork.Reply reply) {
        BlockEditPage page = KineticGui.currentPage(BlockEditPage.class);
        if (page == null || page.pending == 0 || page.pending != reply.request()) return;
        page.pending = 0;
        page.status = reply.message();
        if (reply.saved()) {
            page.navigateBack();
            page.saved.accept(reply.revision());
            KineticOverlays.toast(reply.message());
        } else {
            page.updateControls();
        }
    }

    @Override
    protected boolean onCloseRequested() {
        return pending != 0;
    }

    @Override
    protected void onTick() {
        if (pending != 0 && System.nanoTime() - submitted > 30_000_000_000L) {
            pending = 0;
            status = text("timeout");
            updateControls();
        }
    }

    @Override
    protected void renderBackground(KineticGraphics graphics, int mx, int my, float partial) {
        KineticTheme.shadow(graphics, width(), height());
        KineticTheme.panel(graphics, 0, 0, width(), height());
        KineticTheme.separator(graphics, 4, 24, width() - 8);
    }

    @Override
    protected void renderForeground(KineticGraphics graphics, int mx, int my, float partial) {
        graphics.scrollingTextCentered(text("title"), width() / 2, 8, width() - 144, 0xFFFFAA00, false);
        graphics.scrollingText(text("position", position.getX(), position.getY(), position.getZ()), 12, 34, width() - 24, 0xFFFFFFFF, false);
        graphics.scrollingText(text("original", original), 12, 49, width() - 24, 0xFFCCCCCC, false);
        graphics.scrollingText(text("replacement"), 12, 63, width() - 24, 0xFFCCCCCC, false);
        BlockState state = state();
        if (state != null && !state.isAir()) graphics.item(new ItemStack(state.getBlock()), width() - 39, 101);
        if (status != null) graphics.wrappedText(status, 12, 130, width() - 24, 0xFFFFBB55);
    }

    private static Component text(String key, Object... args) {
        return KineticI18n.translatable(TEXT + key, args);
    }

    private static final class PropertiesPage extends KineticPage {
        private static final int ROWS = 6;
        private final List<Property<?>> definitions;
        private final Consumer<BlockState> accept;
        private BlockState state;
        private int page;

        PropertiesPage(BlockState state, Consumer<BlockState> accept) {
            super(text("properties"));
            useCanvas(380, 178, SAFE_MARGIN);
            this.state = state;
            this.definitions = List.copyOf(state.getProperties());
            this.accept = accept;
        }

        @Override
        protected void build(KineticUi ui) {
            ui.button(4, 4, 48).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(this::close).build();
            ui.button(width() - 68, 4, 64).text(text("use_state")).onClick(() -> { accept.accept(state); navigateBack(); }).build();
            for (int row = 0; row < ROWS && page * ROWS + row < definitions.size(); row++) {
                add(definitions.get(page * ROWS + row), row);
            }
            ui.button(12, 154, 73).text(text("previous")).enabled(page > 0).onClick(() -> { page--; rebuild(); }).build();
            ui.button(width() - 85, 154, 73).text(text("next")).enabled((page + 1) * ROWS < definitions.size())
                    .onClick(() -> { page++; rebuild(); }).build();
        }

        private <T extends Comparable<T>> void add(Property<T> property, int row) {
            List<KineticDropdown.Option> choices = property.getPossibleValues().stream()
                    .map(value -> new KineticDropdown.Option(property.getName(value), Component.empty())).toList();
            ui().dropdown(width() / 2, 28 + row * 20, width() / 2 - 12, choices).selected(property.getName(state.getValue(property)))
                    .onChange(name -> property.getValue(name).ifPresent(value -> state = state.setValue(property, value))).build();
        }

        @Override
        protected void renderBackground(KineticGraphics graphics, int mx, int my, float partial) {
            KineticTheme.shadow(graphics, width(), height());
            KineticTheme.panel(graphics, 0, 0, width(), height());
            KineticTheme.separator(graphics, 4, 24, width() - 8);
        }

        @Override
        protected void renderForeground(KineticGraphics graphics, int mx, int my, float partial) {
            graphics.scrollingTextCentered(text("properties"), width() / 2, 8, width() - 144, 0xFFFFAA00, false);
            for (int row = 0; row < ROWS && page * ROWS + row < definitions.size(); row++) {
                graphics.scrollingText(Component.literal(definitions.get(page * ROWS + row).getName()),
                        12, 31 + row * 20, width() / 2 - 16, 0xFFFFFFFF, false);
            }
        }
    }
}
