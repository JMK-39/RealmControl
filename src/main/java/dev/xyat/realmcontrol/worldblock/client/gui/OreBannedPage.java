package dev.xyat.realmcontrol.worldblock.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.input.ScrollInput;
import dev.xyat.kineticcore.api.client.gui.input.MouseDragInput;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.scroll.KineticScrollController;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.*;
import dev.xyat.kineticcore.api.client.gui.widget.list.*;

import dev.xyat.kineticcore.api.client.input.KineticMouseButtons;
import dev.xyat.kineticcore.api.client.search.KineticItemSearch;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import dev.xyat.realmcontrol.worldblock.util.ItemBanControl;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import dev.xyat.kineticcore.api.registry.KineticRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

public class OreBannedPage extends KineticPage {
    private static final int SLOT_SIZE = 18;

    private static int viewMode = 0;
    private static int rememberedScrollOffset = 0;
    private static String lastSearchQuery = "";

    private KineticTextField searchBox;
    private KineticButton ruleBtn;
    private KineticButton saveBtn;
    private KineticButton viewBtn;
    private KineticButton closeBtn;

    private final List<KineticItemSearch.CachedItem> allOreItems = new ArrayList<>();
    private final List<String> oreTags = new ArrayList<>();
    private final List<String> oreMods = new ArrayList<>();
    private List<KineticItemSearch.CachedItem> currentSourceList = new ArrayList<>();
    private List<KineticItemSearch.CachedItem> displayList = new ArrayList<>();
    private List<String> autoCompleteList = new ArrayList<>();

    private boolean isAutoCompleteMode;
    private int gridX;
    private int gridY;
    private int gridCols;
    private int contentW;
    private boolean compactToolbar;
    private int infoY;
    private final KineticScrollController gridScroll = new KineticScrollController();
    private int contentH;
    private int totalH;

    public OreBannedPage() {
        super(KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.title"));
        useCanvas(640, 360, 4);
        buildOreSources();
        configureStandaloneDraft(
                WorldBlockConfig::getNetworkJson,
                snapshot -> WorldBlockConfig.applyJson(snapshot, "gui_draft_restore", false)
        );
    }

    @Override
    protected void build(KineticUi ui) {
        int sidePadding =
                switch (layoutMetrics().level()) {
                    case LARGE -> 14;
                    case NORMAL -> 10;
                    case SMALL -> 8;
                    case COMPACT -> 6;
                };

        compactToolbar =
                isPortraitLayout()
                        || isCompactLayout()
                        || width() < 520;

        int searchY = 5;
        int spacing = 5;
        int ruleBtnW = 60;

        gridY =
                compactToolbar
                        ? 76
                        : 30;

        int availableWidth =
                Math.max(
                        SLOT_SIZE,
                        width()
                                - sidePadding * 2
                                - 12
                );

        gridCols =
                Math.max(
                        1,
                        availableWidth / SLOT_SIZE
                );

        contentW =
                gridCols * SLOT_SIZE;

        gridX =
                Math.max(
                        sidePadding,
                        (
                                width()
                                        - contentW
                                        - 8
                        ) / 2
                );

        contentH =
                Math.max(
                        SLOT_SIZE,
                        (
                                Math.max(
                                        SLOT_SIZE,
                                        height()
                                                - gridY
                                                - 8
                                ) / SLOT_SIZE
                        ) * SLOT_SIZE
                );

        int rightEdge =
                gridX + contentW;

        int btnW =
                compactToolbar
                        ? Math.max(
                                42,
                                (
                                        contentW
                                                - spacing * 2
                                ) / 3
                        )
                        : 80;

        int searchW =
                compactToolbar
                        ? Math.max(
                                80,
                                contentW
                                        - ruleBtnW
                                        - 2
                        )
                        : 120;

        searchBox =
                ui().textField(gridX, searchY, searchW).placeholder(KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.search.hint")).build();

        searchBox.setTextValue(lastSearchQuery);
        searchBox.setDefaultText(lastSearchQuery);
        searchBox.onTextChange(this::updateSearch);
ruleBtn =
                ui().button(searchBox.controlX()
                                        + searchBox.controlWidth()
                                        + 2, searchY, ruleBtnW).text(Component.empty()).onClick(() ->
                                        toggleRuleFromSearch()).build();
        setControlVisible(ruleBtn, false);
int buttonY =
                compactToolbar
                        ? 31
                        : searchY;

        int saveX;
        int viewX;
        int closeX;

        if (compactToolbar) {
            saveX = gridX;
            viewX = saveX + btnW + spacing;
            closeX = viewX + btnW + spacing;
        } else {
            closeX = rightEdge - btnW;
            viewX = closeX - spacing - btnW;
            saveX = viewX - spacing - btnW;
        }
        saveBtn =
                ui().button(saveX, buttonY, btnW).text(KineticI18n.translatable(
                                        "gui.realmcontrol.worldblock.banitem.btn.save"
                                )).onClick(() -> save()).build();
viewBtn =
                ui().button(viewX, buttonY, btnW).text(getViewModeText()).onClick(button -> {
                                    viewMode =
                                            (viewMode + 1) % 2;

                                    button.setText(
                                            getViewModeText()
                                    );

                                    updateSearch(
                                            searchBox.textValue()
                                    );
                                }).build();
closeBtn =
                ui().button(closeX, buttonY, btnW).text(KineticI18n.translatable(
                                        "gui.realmcontrol.worldblock.banitem.btn.back"
                                )).onClick(() -> close()).build();
infoY =
                compactToolbar
                        ? 57
                        : 11;

        updateSearch(lastSearchQuery);
        ItemSearchCache.prepareCache(() -> {
            buildOreSources();
            updateSearch(searchBox == null ? lastSearchQuery : searchBox.textValue());
        });
    }

    private void buildOreSources() {
        allOreItems.clear();
        oreTags.clear();
        oreMods.clear();
        Set<String> modSet = new TreeSet<>();
        Set<String> tagSet = new TreeSet<>();

        for (KineticItemSearch.CachedItem item : ItemSearchCache.getAllItems()) {
            if (item != null && item.stack() != null && !item.stack().isEmpty() && isOreCandidate(item)) {
                allOreItems.add(item);
                ResourceLocation id = getId(item.id());
                if (id != null) modSet.add("@" + id.getNamespace());
                for (String tag : ItemSearchCache.getRegistryTagIdsForId(item.id())) {
                    tagSet.add("#" + tag);
                }
            }
        }

        for (ResourceLocation id : KineticRegistries.blocks().tagIds()) {
            if (!id.getNamespace().equals("realmcontrol")) {
                tagSet.add("#" + id.toString().toLowerCase(Locale.ROOT));
            }
        }

        oreMods.addAll(modSet);
        oreTags.addAll(tagSet);
    }

    private Component getViewModeText() {
        return KineticI18n.translatable(viewMode == 0 ? "gui.realmcontrol.worldblock.banblock.view.all" : "gui.realmcontrol.worldblock.banblock.view.banned");
    }

    private void toggleRuleFromSearch() {
        if (searchBox == null) return;
        String q = searchBox.textValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty() || WorldBlockConfig.isProtected(q)) return;
        if (!q.startsWith("@") && !q.startsWith("#")) return;
        toggleOreRule(q);
    }

    private void toggleOreRule(String id) {
        if (WorldBlockConfig.data.bannedOreGenerations.contains(id)) {
            WorldBlockConfig.data.bannedOreGenerations.remove(id);
        } else {
            if (rejectOreBanRule(id)) return;
            WorldBlockConfig.data.bannedOreGenerations.add(id);
        }
        WorldBlockConfig.rebuildCache();
        ItemSearchCache.markRulesChanged();
        updateSearch(searchBox == null ? "" : searchBox.textValue());
    }

    private boolean rejectOreBanRule(String id) {
        if (!WorldBlockConfig.wouldOreGenerationRuleBanWorldgenMergeTarget(id)) return false;
        KineticOverlays.toast("banore_conflict_merge_target", KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.conflict.merge_target"));
        return true;
    }

    private void save() {
        dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.beginServerSave("gui.realmcontrol.worldblock.banblock.save_success");
        WorldBlockNetwork.CHANNEL.sendToServer(new WorldBlockNetwork.SaveWorldBlockConfigPacket(WorldBlockConfig.GSON.toJson(WorldBlockConfig.data)));
        commitDraft();
    }

    private void updateSearch(String text) {
        String query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        boolean changed = !query.equals(lastSearchQuery);
        lastSearchQuery = query;

        if (query.startsWith("@")) {
            if (oreMods.contains(query) && !query.equals("@")) {
                isAutoCompleteMode = false;
                setControlVisible(ruleBtn, true);
                ruleBtn.setText(KineticI18n.translatable(WorldBlockConfig.data.bannedOreGenerations.contains(query) ? "gui.realmcontrol.worldblock.banitem.rule.unban" : "gui.realmcontrol.worldblock.banitem.rule.ban"));
                updateDisplayList(query);
            } else {
                isAutoCompleteMode = true;
                setControlVisible(ruleBtn, false);
                autoCompleteList = ItemSearchCache.searchStrings("banore_mod", oreMods, query);
                totalH = autoCompleteList.size() * SLOT_SIZE;
            }
        } else if (query.startsWith("#")) {
            if (oreTags.contains(query) && !query.equals("#")) {
                isAutoCompleteMode = false;
                setControlVisible(ruleBtn, true);
                ruleBtn.setText(KineticI18n.translatable(WorldBlockConfig.data.bannedOreGenerations.contains(query) ? "gui.realmcontrol.worldblock.banitem.rule.unban" : "gui.realmcontrol.worldblock.banitem.rule.ban"));
                updateDisplayList(query);
            } else {
                isAutoCompleteMode = true;
                setControlVisible(ruleBtn, false);
                autoCompleteList = ItemSearchCache.searchStrings("banore_tag", oreTags, query);
                totalH = autoCompleteList.size() * SLOT_SIZE;
            }
        } else {
            isAutoCompleteMode = false;
            setControlVisible(ruleBtn, false);
            updateDisplayList(query);
        }

        if (!isAutoCompleteMode) {
            int totalRows = (int) Math.ceil((double) displayList.size() / gridCols);
            totalH = totalRows * SLOT_SIZE;
        }

        gridScroll.update(totalH, contentH);

        if (changed) {
            gridScroll.reset();
        } else {
            gridScroll.setOffset(rememberedScrollOffset);
        }

        rememberedScrollOffset = gridScroll.offset();
    }

    private void updateDisplayList(String query) {
        currentSourceList = viewMode == 0 ? allOreItems : buildBannedOreSourceList();
        int sourceHash = ItemSearchCache.hashCachedItems(currentSourceList) + 31 * ItemSearchCache.hashStrings(WorldBlockConfig.data.bannedOreGenerations);
        String searchQuery = query.startsWith("@") || query.startsWith("#") ? "" : query;
        displayList = ItemSearchCache.searchItems("banore_display_" + viewMode, currentSourceList, searchQuery, item -> item != null && item.stack() != null && !item.stack().isEmpty() && matchesRuleFilter(item, query), sourceHash);
    }

    private boolean matchesRuleFilter(KineticItemSearch.CachedItem item, String query) {
        if (query == null || query.isEmpty()) return true;
        String clean = query.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("@")) {
            ResourceLocation id = getId(item.id());
            return id != null && clean.substring(1).equals(id.getNamespace());
        }
        if (clean.startsWith("#")) {
            String tagId = clean.substring(1);
            boolean itemMatches = ItemSearchCache.getRegistryTagIdsForId(item.id()).contains(tagId);
            if (itemMatches) return true;
            if (item.stack().getItem() instanceof BlockItem blockItem) {
                try {
                    return blockItem.getBlock().defaultBlockState().is(BlockTags.create(new ResourceLocation(tagId)));
                } catch (Exception ignored) {
                    return false;
                }
            }
            return false;
        }
        return true;
    }

    private List<KineticItemSearch.CachedItem> buildBannedOreSourceList() {
        List<KineticItemSearch.CachedItem> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String rule : WorldBlockConfig.data.bannedOreGenerations) {
            if (rule == null || rule.isBlank()) continue;
            String clean = rule.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("@")) {
                result.add(KineticItemSearch.customSnapshot(new ItemStack(net.minecraft.world.item.Items.COMMAND_BLOCK), clean));
                added.add(clean);
            } else if (clean.startsWith("#")) {
                result.add(KineticItemSearch.customSnapshot(new ItemStack(net.minecraft.world.item.Items.NAME_TAG), clean));
                added.add(clean);
            } else {
                if (WorldBlockConfig.isWorldgenMergeTargetIdentifier(clean)) continue;
                ItemStack stack = WorldBlockConfig.parseItemStack(clean);
                if (!stack.isEmpty()) {
                    result.add(KineticItemSearch.customSnapshot(stack, clean));
                    added.add(clean);
                }
            }
        }
        for (KineticItemSearch.CachedItem item : allOreItems) {
            if (WorldBlockConfig.isOreGenerationBanned(item.stack()) && added.add(item.id())) {
                result.add(item);
            }
        }
        return result;
    }

    private boolean isOreCandidate(KineticItemSearch.CachedItem item) {
        return item != null && item.id() != null && item.stack() != null && !item.stack().isEmpty() && item.stack().getItem() instanceof BlockItem;
    }

    private ResourceLocation getId(String idStr) {
        if (idStr == null || idStr.isBlank()) return null;
        String clean = idStr.trim().toLowerCase(Locale.ROOT);
        int bracket = clean.indexOf('{');
        if (bracket >= 0) clean = clean.substring(0, bracket);
        try {
            return new ResourceLocation(clean);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    protected void renderBackground(KineticGraphics g, int smx, int smy, float pt) {
        KineticTheme.canvasBackground(g, width(), height());
        KineticTheme.panelAlt(g, gridX - 3, gridY - 3, contentW + 12, contentH + 6);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int smx, int smy, float pt) {
        int countX = searchBox.controlX() + searchBox.controlWidth() + 10;
        if (ruleBtn != null && isControlVisible(ruleBtn)) countX = ruleBtn.controlX() + ruleBtn.controlWidth() + 10;

        if (isAutoCompleteMode) {
            g.scrollingText(KineticTheme.muted(KineticI18n.translatable(
                            "gui.realmcontrol.worldblock.banitem.autocomplete.matches_count",
                            Component.literal(String.valueOf(autoCompleteList.size()))
                    )), countX, 11, Math.max(1, width() - countX - 10), 0xFFFFFF, false);
            g.scissor(gridX,
                    gridY,
                    gridX + contentW,
                    gridY + contentH
            );
            for (int i = 0; i < autoCompleteList.size(); i++) {
                String entry = autoCompleteList.get(i);
                int y = gridY + i * SLOT_SIZE - (int) Math.round(gridScroll.smoothOffset());
                if (y + SLOT_SIZE > gridY && y < gridY + contentH) {
                    boolean hovered = smx >= gridX && smx < gridX + contentW && smy >= y && smy < y + SLOT_SIZE;
                    KineticTheme.stateSurface(
                            g,
                            gridX,
                            y,
                            contentW,
                            SLOT_SIZE,
                            i % 2 == 0 ? KineticTheme.Surface.PANEL : KineticTheme.Surface.PANEL_ALT,
                            false,
                            hovered,
                            false
                    );
                    g.scrollingText(Component.literal(entry), gridX + 5, y + 6, Math.max(1, contentW - 10), 0xFFFFFF, false);
                }
            }
        } else {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.count", displayList.size(), currentSourceList.size()),
                    countX, 11, Math.max(1, width() - countX - 10), KineticTheme.current().mutedText(), false);
            g.scissor(gridX,
                    gridY,
                    gridX + contentW,
                    gridY + contentH
            );
            for (int i = 0; i < displayList.size(); i++) {
                KineticItemSearch.CachedItem item = displayList.get(i);
                int col = i % gridCols;
                int row = i / gridCols;
                int x = gridX + col * SLOT_SIZE;
                int y = gridY + row * SLOT_SIZE - (int) Math.round(gridScroll.smoothOffset());
                if (y + SLOT_SIZE > gridY && y < gridY + contentH) {
                    boolean hovered = smx >= x && smx < x + SLOT_SIZE
                            && smy >= y && smy < y + SLOT_SIZE;
                    KineticTheme.itemSlot(g, x, y, SLOT_SIZE, 4, hovered);
                    ItemBanControl.withSkip(() -> {
                        KineticTheme.item(g, item.stack(), x, y, SLOT_SIZE, 1.0F, true);
                        return null;
                    });
                    if (item.id().startsWith("@") || item.id().startsWith("#") || WorldBlockConfig.isOreGenerationBanned(item.stack())) {
                        KineticTheme.indicatorFill(g, x + 2, y + SLOT_SIZE - 3, SLOT_SIZE - 3, 2, KineticTheme.Indicator.DANGER);
                    }
                }
            }
        }
        g.endScissor();
        renderScrollbar(g, smx, smy);

    }

    private void renderScrollbar(KineticGraphics g, int mouseX, int mouseY) {
        gridScroll.render(
                g,
                mouseX,
                mouseY,
                gridX + contentW + 2,
                gridY,
                4,
                contentH,
                20
        );
    }

    private boolean isHoveringButton(KineticButton button, double mx, double my) {
        return button != null && isControlVisible(button) && button.contains(mx, my);
    }

    @Override
    protected void renderTooltips(int smx, int smy) {
        if (isHoveringButton(saveBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.tooltip.btn.save"));
            return;
        }
        if (isHoveringButton(viewBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.tooltip.btn.view"));
            return;
        }
        if (isHoveringButton(closeBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.tooltip.btn.close"));
            return;
        }
        if (isHoveringButton(ruleBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.tooltip.btn.rule_desc"));
            return;
        }
        renderGridTooltip(smx, smy);
    }

    private void renderGridTooltip(int smx, int smy) {
        if (isAutoCompleteMode) return;
        if (smx < gridX || smx >= gridX + contentW || smy < gridY || smy >= gridY + contentH) return;
        int col = (smx - gridX) / SLOT_SIZE;
        int row = (int) Math.floor((smy - gridY + gridScroll.smoothOffset()) / SLOT_SIZE);
        int idx = row * gridCols + col;
        if (col < 0 || col >= gridCols || idx < 0 || idx >= displayList.size()) return;
        KineticItemSearch.CachedItem item = displayList.get(idx);
        ItemBanControl.withSkip(() -> {
            List<Component> tooltip = new ArrayList<>();
            if (item.id().startsWith("@") || item.id().startsWith("#")) {
                tooltip.add(Component.literal(item.id()));
                tooltip.add(KineticI18n.translatable(item.id().startsWith("@") ? "gui.realmcontrol.worldblock.banblock.tooltip.mod_rule" : "gui.realmcontrol.worldblock.banblock.tooltip.tag_rule"));
                tooltip.add(KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.tooltip.right_unban_rule"));
            } else {
                tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(item.stack()));
                tooltip.add(Component.literal(item.id()));
                tooltip.add(WorldBlockConfig.isOreGenerationBanned(item.stack()) ? KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.tooltip.right_unban") : KineticI18n.translatable("gui.realmcontrol.worldblock.banblock.tooltip.left_ban"));
            }
            showTooltip(tooltip);
            return null;
        });
    }

    @Override
    protected boolean onMouseClickCapture(MouseInput input) {
        // 原 canvasMouseClicked 全部在控件之前处理 / The old canvasMouseClicked handled all of this before controls.
        double smx = input.x();
        double smy = input.y();
        if (gridScroll.beginDrag(
                        smx,
                        smy,
                        input.button(),
                        gridX + contentW + 2,
                        gridY,
                        4,
                        contentH,
                        20,
                        0
                )) {
            rememberedScrollOffset = gridScroll.offset();
            return true;
        }
        if (smx >= gridX && smx < gridX + contentW && smy >= gridY && smy < gridY + contentH) {
            if (isAutoCompleteMode) {
                int idx = (int) ((smy - gridY + gridScroll.smoothOffset()) / SLOT_SIZE);
                if (idx >= 0 && idx < autoCompleteList.size()) {
                    searchBox.setTextValue(autoCompleteList.get(idx));
                    return true;
                }
            } else {
                int col = (int) ((smx - gridX) / SLOT_SIZE);
                int row = (int) ((smy - gridY + gridScroll.smoothOffset()) / SLOT_SIZE);
                int idx = row * gridCols + col;
                if (col >= 0 && col < gridCols && idx >= 0 && idx < displayList.size()) {
                    KineticItemSearch.CachedItem item = displayList.get(idx);
                    if (item.id().startsWith("@") || item.id().startsWith("#")) {
                        if (input.isRight()) toggleOreRule(item.id());
                        return true;
                    }
                    if (WorldBlockConfig.isProtected(item.id())) return true;
                    if (input.isLeft() && !WorldBlockConfig.isOreGenerationBanned(item.stack())) {
                        if (rejectOreBanRule(item.id())) return true;
                        WorldBlockConfig.data.bannedOreGenerations.add(item.id());
                        WorldBlockConfig.rebuildCache();
                        ItemSearchCache.markRulesChanged();
                        updateSearch(searchBox.textValue());
                    } else if (input.isRight() && WorldBlockConfig.data.bannedOreGenerations.contains(item.id())) {
                        WorldBlockConfig.data.bannedOreGenerations.remove(item.id());
                        WorldBlockConfig.rebuildCache();
                        ItemSearchCache.markRulesChanged();
                        updateSearch(searchBox.textValue());
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean onMouseRelease(MouseInput input) {
        if (gridScroll.release(input.button())) {
            rememberedScrollOffset = gridScroll.offset();
            return true;
        }

        return false;
    }

    @Override
    protected boolean onMouseDrag(MouseDragInput input) {
        double smy = input.y();
        if (gridScroll.drag(
                smy,
                gridY,
                contentH,
                20
        )) {
            rememberedScrollOffset = gridScroll.offset();
            return true;
        }

        return false;
    }

    @Override
    protected boolean onMouseScroll(ScrollInput input) {
        double d = input.deltaY();
        if (gridScroll.scroll(d, SLOT_SIZE)) {
            rememberedScrollOffset = gridScroll.offset();
            return true;
        }

        return false;
    }


    private static boolean isControlVisible(KineticControl control) {
        return control != null && control.controlVisible();
    }

    private static boolean isControlEnabled(KineticControl control) {
        return control != null && control.isEnabled();
    }

    private static void setControlVisible(KineticControl control, boolean visible) {
        if (control != null) control.setControlVisible(visible);
    }

    private static void setControlEnabled(KineticControl control, boolean enabled) {
        if (control != null) control.setEnabled(enabled);
    }

}
