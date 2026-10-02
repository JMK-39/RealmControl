package dev.xyat.realmcontrol.worldblock.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.KineticGui;
import dev.xyat.kineticcore.api.client.gui.input.ScrollInput;
import dev.xyat.kineticcore.api.client.gui.input.MouseDragInput;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.text.KineticText;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.scroll.KineticScrollController;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.*;
import dev.xyat.kineticcore.api.client.gui.widget.list.*;

import dev.xyat.kineticcore.api.client.search.KineticSearch;
import dev.xyat.kineticcore.api.client.search.KineticItemSearch;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import dev.xyat.realmcontrol.worldblock.util.ItemBanControl;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.TreeSet;

public class OreMergePage extends KineticPage {
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_PITCH = 19;
    private static final int RIGHT_SCROLLBAR_INSET = 7;
    private static String rememberedLeftSearch = "";
    private static String rememberedRightSearch = "";

    // 原 parent != null：打开时是否存在父界面（决定“返回/关闭”文本）
    // Former parent != null: whether a parent screen existed when opened (chooses the Back/Close text).
    private final boolean hasParentScreen;
    private final Map<String, List<String>> tempRules;
    private final Map<String, Integer> replacementChances = new LinkedHashMap<>();
    private final Runnable onChanged;
    private final List<KineticItemSearch.CachedItem> allItemsCache;
    private final Set<String> expandedTargets = new HashSet<>();
    private final List<LeftEntry> leftEntries = new ArrayList<>();

    private KineticTextField leftSearchBox;
    private KineticTextField searchBox;
    private KineticButton addBtn;
    private KineticButton filterBtn;
    private KineticButton doneBtn;
    private KineticButton saveBtn;
    private KineticButton closeBtn;
    private KineticButton loadedChunksToggleBtn;
    private KineticTextField replacementChanceBox;

    private List<KineticItemSearch.CachedItem> rightDisplayList = new ArrayList<>();
    private String selectedTarget;
    private boolean isCreatingRule;
    private boolean selectingNewTarget;
    private boolean groupFilterEnabled;
    private boolean applyToLoadedChunksOnce;
    private final LinkedHashSet<String> pendingSources = new LinkedHashSet<>();
    private final KineticScrollController leftScroll = new KineticScrollController();
    private final KineticScrollController rightScroll = new KineticScrollController();
    private int totalLeftH;
    private int totalRightH;

    private int leftX;
    private int leftY;
    private int leftW;
    private int leftH;
    private int rightX;
    private int rightY;
    private int rightW;
    private int rightH;
    private int gridCols;
    private int gridAreaH;
    private boolean compactLayout;

    private record MergeSnapshot(
            Map<String, List<String>> rules,
            Map<String, Integer> replacementChances,
            Set<String> expandedTargets,
            String selectedTarget,
            boolean creatingRule,
            boolean selectingNewTarget,
            boolean groupFilterEnabled,
            List<String> pendingSources,
            boolean applyToLoadedChunksOnce
    ) {
    }

    private MergeSnapshot captureMergeSnapshot() {
        Map<String, List<String>> rulesCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : tempRules.entrySet()) {
            rulesCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return new MergeSnapshot(
                rulesCopy,
                new LinkedHashMap<>(replacementChances),
                new HashSet<>(expandedTargets),
                selectedTarget,
                isCreatingRule,
                selectingNewTarget,
                groupFilterEnabled,
                new ArrayList<>(pendingSources),
                applyToLoadedChunksOnce
        );
    }

    private void restoreMergeSnapshot(MergeSnapshot snapshot) {
        if (snapshot == null) return;
        tempRules.clear();
        for (Map.Entry<String, List<String>> entry : snapshot.rules().entrySet()) {
            tempRules.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        replacementChances.clear();
        replacementChances.putAll(snapshot.replacementChances());
        expandedTargets.clear();
        expandedTargets.addAll(snapshot.expandedTargets());
        selectedTarget = snapshot.selectedTarget();
        isCreatingRule = snapshot.creatingRule();
        selectingNewTarget = snapshot.selectingNewTarget();
        groupFilterEnabled = snapshot.groupFilterEnabled();
        pendingSources.clear();
        pendingSources.addAll(snapshot.pendingSources());
        applyToLoadedChunksOnce = snapshot.applyToLoadedChunksOnce();
        if (loadedChunksToggleBtn != null) loadedChunksToggleBtn.setText(getLoadedChunksToggleText());
        if (selectedTarget != null && !tempRules.containsKey(selectedTarget) && !isCreatingRule) {
            selectedTarget = null;
        }
        if (onChanged != null) onChanged.run();
    }

    public OreMergePage(Map<String, List<String>> tempRules, Runnable onChanged) {
        super(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.title"));
        // 构造紧接在 KineticGui.openChild 之前，当前界面即父界面 / Constructed right before KineticGui.openChild, so the current screen becomes the parent.
        this.hasParentScreen = KineticGui.isScreenOpen();
        this.tempRules = tempRules == null ? new LinkedHashMap<>() : tempRules;
        this.onChanged = onChanged;
        if (WorldBlockConfig.data != null && WorldBlockConfig.data.blockReplacementChances != null) {
            replacementChances.putAll(WorldBlockConfig.data.blockReplacementChances);
        }
        this.applyToLoadedChunksOnce = WorldBlockConfig.data != null && WorldBlockConfig.data.applyBlockReplacementToLoadedChunksOnce;
        useCanvas(640, 360, 4);
        this.allItemsCache = new ArrayList<>(ItemSearchCache.getAllItems());
        configureStandaloneDraft(this::captureMergeSnapshot, this::restoreMergeSnapshot);
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

        int gap = 5;

        compactLayout =
                isPortraitLayout()
                        || isCompactLayout()
                        || width() < 560;

        if (compactLayout) {
            initCompactLayout(
                    sidePadding,
                    gap
            );
        } else {
            initWideLayout(
                    sidePadding,
                    gap
            );
        }

        loadedChunksToggleBtn = createLoadedChunksToggleButton();
        replacementChanceBox = createReplacementChanceBox();
        updateLeftEntries();
        updateRightPanel();
        ItemSearchCache.prepareCache(() -> {
            allItemsCache.clear();
            allItemsCache.addAll(ItemSearchCache.getAllItems());
            updateLeftEntries();
            updateRightPanel();
        });
    }

    private void initWideLayout(
            int sidePadding,
            int gap
    ) {
        int searchY = 5;
        int panelY = 30;

        leftW =
                Math.max(
                        120,
                        Math.min(
                                170,
                                width() / 4
                        )
                );

        leftX = sidePadding;
        leftY = panelY;

        leftH =
                Math.max(
                        80,
                        height()
                                - panelY
                                - 8
                );

        rightX =
                leftX
                        + leftW
                        + gap;

        rightY = panelY;

        rightW =
                Math.max(
                        SLOT_PITCH * 4,
                        width()
                                - sidePadding
                                - rightX
                );

        rightH = leftH;

        gridCols =
                Math.max(
                        1,
                        (
                                rightW - 10
                        ) / SLOT_PITCH
                );

        fitRightPanelToWholeRows();

        leftSearchBox =
                createLeftSearchBox(
                        leftX,
                        searchY,
                        leftW
                );

        int buttonWidth = 60;

        int closeX =
                rightX
                        + rightW
                        - buttonWidth;

        int saveX =
                closeX
                        - gap
                        - buttonWidth;

        int filterX =
                saveX
                        - gap
                        - buttonWidth;

        int addX =
                filterX
                        - gap
                        - buttonWidth;

        int rightSearchWidth =
                Math.max(
                        80,
                        Math.min(
                                140,
                                addX
                                        - gap
                                        - rightX
                        )
                );

        searchBox =
                createRightSearchBox(
                        rightX,
                        searchY,
                        rightSearchWidth
                );

        addBtn =
                createAddButton(
                        addX,
                        searchY,
                        buttonWidth
                );

        filterBtn =
                createFilterButton(
                        filterX,
                        searchY,
                        buttonWidth
                );

        doneBtn =
                createDoneButton(
                        filterX,
                        searchY,
                        buttonWidth
                );

        saveBtn =
                createSaveButton(
                        saveX,
                        searchY,
                        buttonWidth
                );

        closeBtn =
                createCloseButton(
                        closeX,
                        searchY,
                        buttonWidth
                );
    }

    private void initCompactLayout(
            int sidePadding,
            int gap
    ) {
        int contentW =
                Math.max(
                        140,
                        width()
                                - sidePadding * 2
                );

        leftX = sidePadding;
        leftW = contentW;

        leftSearchBox =
                createLeftSearchBox(
                        leftX,
                        5,
                        leftW
                );

        int buttonWidth =
                Math.max(
                        60,
                        (
                                contentW - gap
                        ) / 2
                );

        addBtn =
                createAddButton(
                        leftX,
                        30,
                        buttonWidth
                );

        filterBtn =
                createFilterButton(
                        leftX
                                + buttonWidth
                                + gap,
                        30,
                        buttonWidth
                );

        doneBtn =
                createDoneButton(
                        leftX
                                + buttonWidth
                                + gap,
                        30,
                        buttonWidth
                );

        saveBtn =
                createSaveButton(
                        leftX,
                        55,
                        buttonWidth
                );

        closeBtn =
                createCloseButton(
                        leftX
                                + buttonWidth
                                + gap,
                        55,
                        buttonWidth
                );

        leftY = 80;

        int minimumRightHeight =
                SLOT_PITCH * 3
                        + getRightHeaderHeight();

        int reservedForRight =
                20
                        + 4
                        + minimumRightHeight
                        + 8;

        int availableForLeft =
                height()
                        - leftY
                        - reservedForRight;

        leftH =
                Math.max(
                        48,
                        Math.min(
                                110,
                                availableForLeft
                        )
                );

        int rightSearchY =
                leftY
                        + leftH
                        + 4;

        rightX = sidePadding;
        rightW = contentW;

        searchBox =
                createRightSearchBox(
                        rightX,
                        rightSearchY,
                        rightW
                );

        rightY =
                rightSearchY
                        + 24;

        rightH =
                Math.max(
                        getRightHeaderHeight()
                                + SLOT_PITCH * 2,
                        height()
                                - rightY
                                - 8
                );

        gridCols =
                Math.max(
                        1,
                        (
                                rightW - 10
                        ) / SLOT_PITCH
                );

        fitRightPanelToWholeRows();
    }

    private KineticTextField createLeftSearchBox(
            int x,
            int y,
            int width
    ) {
        KineticTextField box =
                ui().textField(x, y, width).placeholder(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.search.hint")).build();

        box.onTextChange(
                query -> {
                    rememberedLeftSearch =
                            query == null
                                    ? ""
                                    : query;

                    updateLeftEntries();
                }
        );

        box.setTextValue(
                rememberedLeftSearch
        );
        box.setDefaultText(rememberedLeftSearch);
return box;
    }

    private KineticTextField createRightSearchBox(
            int x,
            int y,
            int width
    ) {
        KineticTextField box =
                ui().textField(x, y, width).placeholder(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.search")).build();

        box.onTextChange(
                query -> {
                    rememberedRightSearch =
                            query == null
                                    ? ""
                                    : query;

                    updateRightPanel();
                }
        );

        box.setTextValue(
                rememberedRightSearch
        );
        box.setDefaultText(rememberedRightSearch);
return box;
    }

    private KineticButton createLoadedChunksToggleButton() {
        int width = 80;
        int chanceBoxX = rightX + rightW - 48;
        int chanceLabelWidth = KineticText.width(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance"));
        int x = chanceBoxX - chanceLabelWidth - width - 10;
        int y = rightY + 2;
        KineticButton button = ui().button(x, y, width).text(getLoadedChunksToggleText()).onClick(() -> {
                    applyToLoadedChunksOnce = !applyToLoadedChunksOnce;
                    loadedChunksToggleBtn.setText(getLoadedChunksToggleText());
                }).build();
return button;
    }

    private Component getLoadedChunksToggleText() {
        return KineticI18n.translatable(
                applyToLoadedChunksOnce
                        ? "gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.on"
                        : "gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.off"
        );
    }

    private KineticTextField createReplacementChanceBox() {
        KineticTextField box = ui().textField(rightX + rightW - 48, rightY + 2, 44).label(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance")).build();
        box.limitTextLength(3);
        box.setValidator(value -> value.isEmpty() || value.matches("\\d{1,3}"));
        box.onTextChange(this::updateReplacementChance);
return box;
    }

    private void updateReplacementChance(String text) {
        if (selectedTarget == null || isCreatingRule || text == null || text.isBlank()) return;
        try {
            int chance = Math.max(0, Math.min(100, Integer.parseInt(text)));
            String key = getBaseIdentifier(selectedTarget);
            if (chance >= 100) replacementChances.remove(key);
            else replacementChances.put(key, chance);
        } catch (NumberFormatException ignored) {
        }
    }

    private void syncReplacementChanceBox() {
        if (replacementChanceBox == null) return;
        boolean visible = selectedTarget != null && !isCreatingRule;
        setControlVisible(replacementChanceBox, visible);
        setControlEnabled(replacementChanceBox, visible);
        if (!visible) return;
        String targetId = getBaseIdentifier(selectedTarget);
        String original = String.valueOf(WorldBlockConfig.data == null || WorldBlockConfig.data.blockReplacementChances == null
                ? 100 : WorldBlockConfig.data.blockReplacementChances.getOrDefault(targetId, 100));
        replacementChanceBox.setDefaultText(original);
        String value = String.valueOf(replacementChances.getOrDefault(targetId, 100));
        if (!value.equals(replacementChanceBox.textValue())) replacementChanceBox.setTextValue(value);
    }

    private KineticButton createAddButton(
            int x,
            int y,
            int width
    ) {
        KineticButton button =
                ui().button(x, y, width).text(KineticI18n.translatable(
                                        "gui.realmcontrol.worldblock.banitem.merge_add"
                                )).onClick(() -> {
                                    isCreatingRule = true;
                                    selectingNewTarget = false;
                                    selectedTarget = null;
                                    pendingSources.clear();
                                    groupFilterEnabled = false;

                                    updateLeftEntries();
                                    updateRightPanel();
                                }).build();
return button;
    }

    private KineticButton createDoneButton(
            int x,
            int y,
            int width
    ) {
        KineticButton button = ui().button(x, y, width).text(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.create.done")).onClick(() -> {
                    if (!isCreatingRule || selectingNewTarget || pendingSources.isEmpty()) return;
                    selectingNewTarget = true;
                    groupFilterEnabled = false;
                    updateRightPanel();
                }).build();
        setControlVisible(button, false);
return button;
    }

    private KineticButton createFilterButton(
            int x,
            int y,
            int width
    ) {
        KineticButton button =
                ui().button(x, y, width).text(KineticI18n.translatable(
                                        "gui.realmcontrol.worldblock.banitem.block.merge.filter_group"
                                )).onClick(() -> {
                                    groupFilterEnabled =
                                            !groupFilterEnabled;

                                    updateRightPanel();
                                }).build();
return button;
    }

    private KineticButton createSaveButton(
            int x,
            int y,
            int width
    ) {
        KineticButton button =
                ui().button(x, y, width).text(KineticI18n.translatable(
                                        "gui.realmcontrol.worldblock.banitem.block.merge.save"
                                )).onClick(() -> saveAndApply()).build();
return button;
    }

    private KineticButton createCloseButton(
            int x,
            int y,
            int width
    ) {
        KineticButton button =
                ui().button(x, y, width).text(KineticI18n.translatable(
                                        !hasParentScreen
                                                ? "gui.realmcontrol.worldblock.banitem.btn.close"
                                                : "gui.realmcontrol.worldblock.config.back"
                                )).onClick(() -> closeScreen()).build();
return button;
    }

    private void updateLeftEntries() {
        leftEntries.clear();
        String query = leftSearchBox == null ? "" : leftSearchBox.textValue().toLowerCase(Locale.ROOT).trim();
        for (Map.Entry<String, List<String>> entry : tempRules.entrySet()) {
            String target = entry.getKey();
            List<String> sources = entry.getValue();
            if (isNotOreRule(target, sources)) continue;

            boolean targetMatches = query.isEmpty() || KineticSearch.match(ItemSearchCache.getSearchDataForId(target), query) || KineticSearch.match(getOreGroupSearchText(target), query);
            boolean sourceMatches = false;
            if (!query.isEmpty()) {
                for (String source : sources) {
                    if (KineticSearch.match(ItemSearchCache.getSearchDataForId(source), query) || KineticSearch.match(getOreGroupSearchText(source), query)) {
                        sourceMatches = true;
                        break;
                    }
                }
            }

            if (targetMatches || sourceMatches) {
                leftEntries.add(new TargetEntry(target, sources.size()));
                if (expandedTargets.contains(target) || !query.isEmpty()) {
                    for (String source : sources) {
                        if (query.isEmpty() || targetMatches || KineticSearch.match(ItemSearchCache.getSearchDataForId(source), query) || KineticSearch.match(getOreGroupSearchText(source), query)) {
                            leftEntries.add(new SourceEntry(target, source));
                        }
                    }
                }
            }
        }

        totalLeftH = 0;
        for (LeftEntry entry : leftEntries) {
            entry.h = 20;
            totalLeftH += entry.h + 1;
        }
        leftScroll.update(totalLeftH, leftH);
    }

    private void updateRightPanel() {
        syncReplacementChanceBox();
        if (searchBox == null) return;
        setControlVisible(searchBox, isCreatingRule || selectedTarget != null);
        setControlEnabled(searchBox, isControlVisible(searchBox));
        updateFilterButtonState();
        updateDoneButtonState();
        if (!isControlVisible(searchBox)) {
            rightDisplayList = new ArrayList<>();
            totalRightH = 0;
            rightScroll.reset();
            rightScroll.update(0, gridAreaH);
            return;
        }

        String query = searchBox.textValue().toLowerCase(Locale.ROOT).trim();
        Set<String> excluded = buildExcludedIdentifiers();
        Set<ItemUnificationHelper.MergeGroup> targetGroups = selectedTarget == null ? Collections.emptySet() : getOreGroupsForId(selectedTarget);
        int creationHash = 31 * pendingSources.hashCode() + (selectingNewTarget ? 1 : 0);
        int sourceHash = 31 * ItemSearchCache.getAllItemsHash()
                + ItemSearchCache.hashStrings(excluded)
                + hashGroups(targetGroups)
                + (groupFilterEnabled ? 1 : 0)
                + creationHash;

        rightDisplayList = new ArrayList<>(ItemSearchCache.searchItems("ore_merge_right_block", allItemsCache, query, item -> {
            if (item == null || item.stack() == null || item.stack().isEmpty()) return false;
            if (!isBlockMergeCandidate(item.stack())) return false;
            String itemId = item.id();
            String baseId = getBaseIdentifier(itemId);
            if (isCreatingRule && !selectingNewTarget && pendingSources.contains(itemId)) return true;
            if (excluded.contains(itemId) || excluded.contains(baseId)) return false;
            if (isCreatingRule && selectingNewTarget) {
                for (String source : pendingSources) {
                    if (getBaseIdentifier(source).equals(baseId)) return false;
                }
                if (WorldBlockConfig.isOreGenerationBanned(item.stack())) return false;
                return true;
            }
            Set<ItemUnificationHelper.MergeGroup> groups = getOreGroupsForStack(item.stack());
            return selectedTarget == null || !groupFilterEnabled || targetGroups.isEmpty() || matchesAnyGroup(groups, targetGroups);
        }, sourceHash));

        int totalRows = (int) Math.ceil((double) rightDisplayList.size() / gridCols);
        totalRightH = totalRows * SLOT_PITCH;
        rightScroll.update(totalRightH, gridAreaH);
    }

    private void updateFilterButtonState() {
        if (filterBtn == null) return;
        boolean visible = selectedTarget != null && !isCreatingRule;
        Set<ItemUnificationHelper.MergeGroup> groups = visible ? getOreGroupsForId(selectedTarget) : Collections.emptySet();
        setControlVisible(filterBtn, visible);
        setControlEnabled(filterBtn, visible && !groups.isEmpty());
        if (isControlDisabled(filterBtn)) groupFilterEnabled = false;
        filterBtn.setText(KineticI18n.translatable(groupFilterEnabled ? "gui.realmcontrol.worldblock.banitem.block.merge.filter_group_on" : "gui.realmcontrol.worldblock.banitem.block.merge.filter_group"));
    }

    private void updateDoneButtonState() {
        if (doneBtn == null) return;
        setControlVisible(doneBtn, isCreatingRule && !selectingNewTarget);
        setControlEnabled(doneBtn, isControlVisible(doneBtn) && !pendingSources.isEmpty());
    }

    private boolean isNotOreRule(String target, List<String> sources) {
        if (isBlockIdentifier(target) || !getOreGroupsForId(target).isEmpty()) return false;
        if (sources == null) return true;
        for (String source : sources) {
            if (isBlockIdentifier(source) || !getOreGroupsForId(source).isEmpty()) return false;
        }
        return true;
    }

    private boolean isBlockIdentifier(String idStr) {
        ItemStack stack = WorldBlockConfig.parseItemStack(idStr);
        return isBlockMergeCandidate(stack);
    }

    private boolean isBlockMergeCandidate(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    private Set<String> buildExcludedIdentifiers() {
        Set<String> excluded = new HashSet<>(WorldBlockConfig.data.bannedItems);
        for (String banned : WorldBlockConfig.data.bannedItems) addIdentifierExclusion(excluded, banned);
        if (WorldBlockConfig.data != null && WorldBlockConfig.data.mergedItems != null) {
            addRuleMapExclusions(excluded, WorldBlockConfig.data.mergedItems);
        }
        addRuleMapExclusions(excluded, tempRules);
        excluded.addAll(WorldBlockConfig.getWeightedBlockRuleIdentifiers());
        return excluded;
    }

    private void addRuleMapExclusions(Set<String> excluded, Map<String, List<String>> rules) {
        if (rules == null || rules.isEmpty()) return;
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            addIdentifierExclusion(excluded, entry.getKey());
            if (entry.getValue() != null) {
                for (String source : entry.getValue()) addIdentifierExclusion(excluded, source);
            }
        }
    }

    private void addIdentifierExclusion(Set<String> excluded, String idStr) {
        if (idStr == null || idStr.isEmpty()) return;
        excluded.add(idStr);
        excluded.add(getBaseIdentifier(idStr));
    }

    private String getBaseIdentifier(String idStr) {
        if (idStr == null) return "";
        int bracket = idStr.indexOf('{');
        return bracket == -1 ? idStr : idStr.substring(0, bracket);
    }

    private void saveAndApply() {
        if (hasInvalidMergeTargets()) return;
        WorldBlockConfig.Data ds = new WorldBlockConfig.Data();
        ds.bannedItems = new ArrayList<>(WorldBlockConfig.data.bannedItems);
        if (WorldBlockConfig.data.bannedOreGenerations != null) {
            ds.bannedOreGenerations = new ArrayList<>(WorldBlockConfig.data.bannedOreGenerations);
        }
        if (WorldBlockConfig.data.mergedItems != null) {
            for (Map.Entry<String, List<String>> entry : WorldBlockConfig.data.mergedItems.entrySet()) {
                ds.mergedItems.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        ds.oreMergedItems.putAll(buildMergedRulesForSave());
        ds.weightedBlockReplacements.putAll(WorldBlockConfig.copyWeightedBlockReplacements());
        ds.blockReplacementChances.putAll(replacementChances);
        ds.weightedBlockReplacementChances.putAll(WorldBlockConfig.data.weightedBlockReplacementChances);
        ds.applyBlockReplacementToLoadedChunksOnce = applyToLoadedChunksOnce;
        ds.applyWeightedBlockReplacementToLoadedChunksOnce = WorldBlockConfig.data.applyWeightedBlockReplacementToLoadedChunksOnce;
        dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.beginServerSave("gui.realmcontrol.worldblock.banitem.block.merge.save_success");
        WorldBlockNetwork.CHANNEL.sendToServer(new WorldBlockNetwork.SaveWorldBlockConfigPacket(WorldBlockConfig.GSON.toJson(ds)));
        commitDraft();
    }

    private boolean hasInvalidMergeTargets() {
        for (String target : tempRules.keySet()) {
            ItemStack stack = WorldBlockConfig.parseItemStack(target);
            if (WorldBlockConfig.isOreGenerationBanned(stack)) {
                KineticOverlays.toast("oremerge_target_banned", KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.conflict.target_banned"));
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> buildMergedRulesForSave() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : tempRules.entrySet()) {
            List<String> sources = new ArrayList<>(new LinkedHashSet<>(entry.getValue()));
            sources.removeIf(source -> source == null || source.isEmpty() || getBaseIdentifier(source).equals(getBaseIdentifier(entry.getKey())));
            if (sources.isEmpty()) continue;
            String target = entry.getKey();
            List<String> targetSources = result.computeIfAbsent(target, key -> new ArrayList<>());
            for (String source : sources) {
                if (!targetSources.contains(source)) targetSources.add(source);
            }
        }
        return result;
    }

    @Override
    protected boolean onCloseRequested() {
        closeScreen();
        return true;
    }

    private void closeScreen() {
        if (onChanged != null) onChanged.run();
        this.navigateBack();
    }

    private Set<ItemUnificationHelper.MergeGroup> getOreGroupsForId(String idStr) {
        Set<ItemUnificationHelper.MergeGroup> result = new TreeSet<>(Comparator.comparing(ItemUnificationHelper.MergeGroup::key));
        result.addAll(ItemSearchCache.getUnificationGroupsForId(idStr));
        return result.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(result);
    }

    private Set<ItemUnificationHelper.MergeGroup> getOreGroupsForStack(ItemStack stack) {
        Set<ItemUnificationHelper.MergeGroup> result = new TreeSet<>(Comparator.comparing(ItemUnificationHelper.MergeGroup::key));
        result.addAll(ItemUnificationHelper.getGroupsForStack(stack));
        return result.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(result);
    }

    private boolean matchesAnyGroup(Set<ItemUnificationHelper.MergeGroup> itemGroups, Set<ItemUnificationHelper.MergeGroup> targetGroups) {
        if (itemGroups.isEmpty() || targetGroups.isEmpty()) return false;
        for (ItemUnificationHelper.MergeGroup itemGroup : itemGroups) {
            for (ItemUnificationHelper.MergeGroup targetGroup : targetGroups) {
                if (itemGroup.sameGroup(targetGroup)) return true;
            }
        }
        return false;
    }

    private int hashGroups(Set<ItemUnificationHelper.MergeGroup> groups) {
        if (groups == null || groups.isEmpty()) return 0;
        int hash = 1;
        for (ItemUnificationHelper.MergeGroup group : groups) hash = 31 * hash + group.key().hashCode();
        return hash;
    }

    private String getOreGroupSearchText(String idStr) {
        Set<ItemUnificationHelper.MergeGroup> groups = getOreGroupsForId(idStr);
        StringBuilder builder = new StringBuilder();
        for (ItemUnificationHelper.MergeGroup group : groups) {
            builder.append(' ').append(group.material()).append(' ').append(group.strata()).append(' ').append(group.tagId());
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    @Override
    protected void renderBackground(KineticGraphics g, int smx, int smy, float pt) {
        g.fillGradient(0, 0, width(), height(), 0xFF222222, 0xFF111111);
        KineticTheme.panel(g, leftX, leftY, leftW, leftH);
        KineticTheme.panel(g, rightX, rightY, rightW, rightH);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int smx, int smy, float pt) {
        renderLeftPanel(g, smx, smy);
        renderRightPanel(g, smx, smy);
    }

    private void renderLeftPanel(KineticGraphics g, int smx, int smy) {
        g.scissor(leftX,
                leftY,
                leftX + leftW,
                leftY + leftH
        );
        int currentY = leftY - (int) Math.round(leftScroll.smoothOffset());
        for (LeftEntry entry : leftEntries) {
            if (currentY + entry.h >= leftY && currentY <= leftY + leftH) {
                entry.x = leftX + 2;
                entry.y = currentY;
                entry.w = leftW - 4;
                entry.render(g, smx, smy);
            }
            currentY += entry.h + 1;
        }
        g.endScissor();
        leftScroll.render(
                g,
                smx,
                smy,
                leftX + leftW - 7,
                leftY,
                4,
                leftH,
                20
        );
    }

    private void fitRightPanelToWholeRows() {
        int availableGridHeight = Math.max(SLOT_PITCH, rightH - getRightHeaderHeight());
        int fullRows = Math.max(1, availableGridHeight / SLOT_PITCH);
        gridAreaH = fullRows * SLOT_PITCH;
        rightH = getRightHeaderHeight() + gridAreaH;
    }

    private int getRightHeaderHeight() {
        return 24;
    }

    private int getRightGridY() {
        return rightY + getRightHeaderHeight();
    }

    private void renderRightStatus(KineticGraphics g) {
        Component info;
        if (isCreatingRule && !selectingNewTarget) {
            info = KineticI18n.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.merge.mode.select_replaced",
                    Component.literal(String.valueOf(pendingSources.size())),
                    Component.literal(String.valueOf(rightDisplayList.size()))
            );
        } else if (isCreatingRule) {
            info = KineticI18n.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.merge.mode.select_final_target",
                    Component.literal(String.valueOf(pendingSources.size())),
                    Component.literal(String.valueOf(rightDisplayList.size()))
            );
        } else {
            ItemStack targetStack = WorldBlockConfig.parseItemStack(selectedTarget);
            Component targetName = ItemCacheHudRenderer.getDisplayNameCustom(targetStack);
            Component filterText = KineticI18n.translatable(
                    groupFilterEnabled
                            ? "gui.realmcontrol.worldblock.banitem.block.merge.filter_state.group"
                            : "gui.realmcontrol.worldblock.banitem.block.merge.filter_state.all"
            );
            info = KineticI18n.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.merge.mode.select_source",
                    targetName,
                    Component.literal(String.valueOf(rightDisplayList.size())),
                    filterText
            );
        }
        int infoRight = replacementChanceBox != null && isControlVisible(replacementChanceBox)
                ? replacementChanceBox.controlX() - KineticText.width(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance")) - 6
                : rightX + rightW - 4;
        if (loadedChunksToggleBtn != null && isControlVisible(loadedChunksToggleBtn)) {
            infoRight = Math.min(infoRight, loadedChunksToggleBtn.controlX() - 6);
        }
        g.scrollingText(info, rightX + 6, rightY + 8, Math.max(1, infoRight - (rightX + 6)), 0xFFFFFFFF, false);
        if (replacementChanceBox != null && isControlVisible(replacementChanceBox)) {
            Component chanceLabel = KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance");
            g.scrollingTextRight(chanceLabel, replacementChanceBox.controlX() - 3, rightY + 8, Math.max(1, replacementChanceBox.controlX() - rightX - 9), 0xFFFFFFFF, false);
        }
    }

    private void renderRightPanel(KineticGraphics g, int smx, int smy) {
        if (!isCreatingRule && selectedTarget == null) return;

        renderRightStatus(g);

        int gridX = rightX + 2;
        int gridY = getRightGridY();
        g.scissor(rightX,
                gridY,
                rightX + rightW,
                gridY + gridAreaH
        );
        for (int i = 0; i < rightDisplayList.size(); i++) {
            int col = i % gridCols;
            int row = i / gridCols;
            int x = gridX + col * SLOT_PITCH;
            int y = gridY + row * SLOT_PITCH - (int) Math.round(rightScroll.smoothOffset());
            if (y + SLOT_SIZE <= gridY || y >= gridY + gridAreaH) continue;
            KineticItemSearch.CachedItem item = rightDisplayList.get(i);
            boolean hovered = smx >= x && smx < x + SLOT_SIZE
                    && smy >= y && smy < y + SLOT_SIZE;
            boolean selected = isCreatingRule && !selectingNewTarget && pendingSources.contains(item.id());
            KineticTheme.itemSlot(g, x, y, SLOT_SIZE, SLOT_SIZE, 4, selected, hovered, false);
            ItemBanControl.withSkip(() -> {
                KineticTheme.item(g, item.stack(), x, y, SLOT_SIZE, 1.0F, false);
                return null;
            });
        }
        g.endScissor();
        rightScroll.render(
                g,
                smx,
                smy,
                rightX + rightW - RIGHT_SCROLLBAR_INSET,
                gridY,
                4,
                gridAreaH,
                20
        );
    }

    private boolean isHoveringButton(KineticButton button, double mx, double my) {
        return button != null && isControlVisible(button) && button.contains(mx, my);
    }

    @Override
    protected void renderTooltips(int smx, int smy) {
        if (isHoveringButton(addBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.tooltip.btn.add"));
            return;
        }
        if (isHoveringButton(doneBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.create.done.tooltip"));
            return;
        }
        if (isHoveringButton(filterBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.tooltip.btn.filter"));
            return;
        }
        if (isHoveringButton(loadedChunksToggleBtn, smx, smy)) {
            showTooltip(List.of(
                    KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.tooltip.title"),
                    KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.tooltip.restart"),
                    KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.tooltip.off"),
                    KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.tooltip.on"),
                    KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.tooltip.throttle"),
                    KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.tooltip.scale")
            ));
            return;
        }
        if (isHoveringButton(saveBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.tooltip.btn.save"));
            return;
        }
        if (isHoveringButton(closeBtn, smx, smy)) {
            showTooltip(KineticI18n.translatable(!hasParentScreen ? "gui.realmcontrol.worldblock.banitem.tooltip.btn.close" : "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.btn.back"));
            return;
        }

        if (smx >= leftX && smx < leftX + leftW && smy >= leftY && smy < leftY + leftH) {
            int currentY = leftY - (int) Math.round(leftScroll.smoothOffset());
            for (LeftEntry entry : leftEntries) {
                if (currentY + entry.h >= leftY && currentY <= leftY + leftH && smx >= entry.x && smx < entry.x + entry.w && smy >= currentY && smy < currentY + entry.h) {
                    entry.requestTooltip();
                    return;
                }
                currentY += entry.h + 1;
            }
        }

        int idx = getRightItemIndexAt(smx, smy);
        if (idx >= 0) {
            KineticItemSearch.CachedItem item = rightDisplayList.get(idx);
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(item.stack()));
            tooltip.add(Component.literal(item.id()));
            String actionKey = !isCreatingRule
                    ? "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.add_source"
                    : selectingNewTarget
                    ? "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.set_final_target"
                    : pendingSources.contains(item.id())
                    ? "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.unselect_replaced"
                    : "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.select_replaced";
            tooltip.add(KineticI18n.translatable(actionKey));
            showTooltip(tooltip);
        }
    }

    private int getRightItemIndexAt(double smx, double smy) {
        if (!isCreatingRule && selectedTarget == null) return -1;

        int gridX = rightX + 2;
        int gridY = getRightGridY();
        if (smx < gridX || smx >= rightX + rightW || smy < gridY || smy >= gridY + gridAreaH) return -1;

        int localX = (int) Math.floor(smx - gridX);
        int localY = (int) Math.floor(smy - gridY + (int) Math.round(rightScroll.smoothOffset()));
        if (localX < 0 || localY < 0) return -1;

        int col = localX / SLOT_PITCH;
        int row = localY / SLOT_PITCH;
        if (col < 0 || col >= gridCols) return -1;
        if (localX % SLOT_PITCH >= SLOT_SIZE || localY % SLOT_PITCH >= SLOT_SIZE) return -1;

        int idx = row * gridCols + col;
        return idx >= 0 && idx < rightDisplayList.size() ? idx : -1;
    }

    @Override
    protected boolean onMouseClickCapture(MouseInput input) {
        // 原 canvasMouseClicked 全部在控件之前处理 / The old canvasMouseClicked handled all of this before controls.
        double smx = input.x();
        double smy = input.y();
        if (leftScroll.beginDrag(
                        smx,
                        smy,
                        input.button(),
                        leftX + leftW + 2,
                        leftY,
                        4,
                        leftH,
                        20,
                        0
                )) {
            return true;
        }

        int rightGridYForScroll = getRightGridY();

        if (rightScroll.beginDrag(
                        smx,
                        smy,
                        input.button(),
                        rightX + rightW - RIGHT_SCROLLBAR_INSET,
                        rightGridYForScroll,
                        4,
                        gridAreaH,
                        20,
                        0
                )) {
            return true;
        }

        if (smx >= leftX && smx < leftX + leftW && smy >= leftY && smy < leftY + leftH) {
            int currentY = leftY - (int) Math.round(leftScroll.smoothOffset());
            for (LeftEntry entry : leftEntries) {
                if (currentY + entry.h >= leftY && currentY <= leftY + leftH && smx >= entry.x && smx < entry.x + entry.w && smy >= currentY && smy < currentY + entry.h) {
                    if (entry.mouseClicked(input)) return true;
                }
                currentY += entry.h + 1;
            }
        } else {
            int idx = getRightItemIndexAt(smx, smy);
            if (input.isLeft() && idx >= 0) {
            KineticItemSearch.CachedItem clicked = rightDisplayList.get(idx);
            String id = clicked.id();
            if (isCreatingRule && !selectingNewTarget) {
                if (!pendingSources.add(id)) pendingSources.remove(id);
            } else if (isCreatingRule) {
                if (pendingSources.isEmpty()) {
                    selectingNewTarget = false;
                    updateRightPanel();
                    return true;
                }
                if (WorldBlockConfig.isOreGenerationBanned(clicked.stack())) {
                    KineticOverlays.toast("oremerge_target_banned", KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.conflict.target_banned"));
                    return true;
                }
                List<String> sources = tempRules.computeIfAbsent(id, key -> new ArrayList<>());
                for (String source : pendingSources) {
                    if (!sources.contains(source)) sources.add(source);
                }
                selectedTarget = id;
                isCreatingRule = false;
                selectingNewTarget = false;
                pendingSources.clear();
                groupFilterEnabled = false;
                expandedTargets.add(id);
            } else {
                List<String> sources = tempRules.computeIfAbsent(selectedTarget, key -> new ArrayList<>());
                if (!sources.contains(id)) sources.add(id);
            }
            updateLeftEntries();
            updateRightPanel();
            return true;
            }
        }
        return false;
    }

    @Override
    protected boolean onMouseRelease(MouseInput input) {
        boolean released =
                leftScroll.release(input.button())
                        | rightScroll.release(input.button());

        return released
                || false;
    }

    @Override
    protected boolean onMouseDrag(MouseDragInput input) {
        double smy = input.y();
        if (leftScroll.drag(
                smy,
                leftY,
                leftH,
                20
        )) {
            return true;
        }

        if (rightScroll.drag(
                smy,
                getRightGridY(),
                gridAreaH,
                20
        )) {
            return true;
        }

        return false;
    }

    @Override
    protected boolean onMouseScroll(ScrollInput input) {
        double smx = input.x();
        double smy = input.y();
        double delta = input.deltaY();
        if (smx >= leftX
                && smx <= leftX + leftW
                && smy >= leftY
                && smy <= leftY + leftH
                && leftScroll.scroll(delta, 10D)) {
            return true;
        }

        int rightGridY = getRightGridY();

        if (smx >= rightX
                && smx <= rightX + rightW
                && smy >= rightGridY
                && smy <= rightGridY + gridAreaH
                && rightScroll.scroll(delta, SLOT_PITCH)) {
            return true;
        }

        return false;
    }

    abstract static class LeftEntry {
        int x;
        int y;
        int w;
        int h;

        abstract void render(KineticGraphics g, int mx, int my);

        abstract boolean mouseClicked(MouseInput input);

        abstract void requestTooltip();
    }

    class TargetEntry extends LeftEntry {
        private final String id;
        private final ItemStack stack;
        private final int count;

        TargetEntry(String id, int count) {
            this.id = id;
            this.stack = WorldBlockConfig.parseItemStack(id);
            this.count = count;
        }

        @Override
        void render(KineticGraphics g, int mx, int my) {
            boolean selected = id.equals(selectedTarget);
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
            KineticTheme.stateSurface(g, x, y, w, h, KineticTheme.Surface.PANEL_ALT, selected, hover, false);
            KineticTheme.itemSlot(g, x + 1, y + 1, SLOT_SIZE, SLOT_SIZE, 4, selected, hover, false);
            ItemBanControl.withSkip(() -> {
                KineticTheme.item(g, stack, x + 1, y + 1, SLOT_SIZE, 1.0F, false);
                return null;
            });
            String name = ItemCacheHudRenderer.getDisplayNameCustom(stack).getString();
            g.scrollingText(Component.literal(name), x + 24, y + 6, w - 58, 0xFFFFFF, false);
            g.scrollingTextRight(KineticI18n.translatable("gui.realmcontrol.worldblock.common.count_parentheses", Component.literal(String.valueOf(count))), x + w - 4, y + 6, 22, 0xFFFFFF, false);
            g.scrollingText(KineticI18n.translatable(expandedTargets.contains(id) ? "gui.realmcontrol.worldblock.common.collapse" : "gui.realmcontrol.worldblock.common.expand"), x + w - 36, y + 6, 10, 0xFFFFFF, false);
        }

        @Override
        boolean mouseClicked(MouseInput input) {
            if (input.isLeft()) {
                if (expandedTargets.contains(id)) expandedTargets.remove(id);
                else expandedTargets.add(id);
                selectedTarget = id;
                isCreatingRule = false;
                selectingNewTarget = false;
                pendingSources.clear();
                groupFilterEnabled = false;
                updateLeftEntries();
                updateRightPanel();
                return true;
            }
            if (input.isRight()) {
                tempRules.remove(id);
                replacementChances.remove(getBaseIdentifier(id));
                if (id.equals(selectedTarget)) selectedTarget = null;
                expandedTargets.remove(id);
                updateLeftEntries();
                updateRightPanel();
                return true;
            }
            return false;
        }

        @Override
        void requestTooltip() {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(stack));
            tooltip.add(Component.literal(id));
            tooltip.add(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.tooltip.target_del"));
            showTooltip(tooltip);
        }
    }

    class SourceEntry extends LeftEntry {
        private final String targetId;
        private final String sourceId;
        private final ItemStack stack;

        SourceEntry(String targetId, String sourceId) {
            this.targetId = targetId;
            this.sourceId = sourceId;
            this.stack = WorldBlockConfig.parseItemStack(sourceId);
        }

        @Override
        void render(KineticGraphics g, int mx, int my) {
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
            KineticTheme.stateSurface(g, x, y, w, h, KineticTheme.Surface.PANEL_ALT, false, hover, false);
            KineticTheme.itemSlot(g, x + 1, y + 1, SLOT_SIZE, SLOT_SIZE, 4, false, hover, false);
            ItemBanControl.withSkip(() -> {
                KineticTheme.item(g, stack, x + 1, y + 1, SLOT_SIZE, 1.0F, false);
                return null;
            });
        }

        @Override
        boolean mouseClicked(MouseInput input) {
            if (input.isRight()) {
                List<String> sources = tempRules.get(targetId);
                if (sources != null) sources.remove(sourceId);
                updateLeftEntries();
                updateRightPanel();
                return true;
            }
            return false;
        }

        @Override
        void requestTooltip() {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(stack));
            tooltip.add(Component.literal(sourceId));
            tooltip.add(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.tooltip.source_del"));
            showTooltip(tooltip);
        }
    }
    private static boolean isControlVisible(KineticControl control) {
        return control != null && control.controlVisible();
    }

    private static boolean isControlDisabled(KineticControl control) {
        return control == null || !control.isEnabled();
    }

    private static void setControlVisible(KineticControl control, boolean visible) {
        if (control != null) control.setControlVisible(visible);
    }

    private static void setControlEnabled(KineticControl control, boolean enabled) {
        if (control != null) control.setEnabled(enabled);
    }

}
