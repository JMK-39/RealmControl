package dev.xyat.realmcontrol.worldblock.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.search.KineticSearch;
import dev.xyat.kineticcore.api.client.overlay.GuiOverlay;
import dev.xyat.kineticcore.api.client.search.ItemSearchIndex;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets.GridScrollController;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import dev.xyat.realmcontrol.worldblock.util.ItemBanControl;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

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

public class OreMergeScreen extends KineticScreen {
    private static final int SLOT_SIZE = 18;
    private static final int SLOT_PITCH = 19;
    private static final int SCROLL_TRACK_COLOR = 0xFF171717;
    private static final int SCROLL_THUMB_COLOR = 0xFFFF9800;
    private static final int SCROLL_THUMB_DRAG_COLOR = 0xFFFFD700;
    private static final int RIGHT_SCROLL_THUMB_COLOR = 0xFFFF9800;
    private static final int RIGHT_SCROLL_THUMB_HOVER_COLOR = 0xFFFFD700;
    private static final int RIGHT_SCROLLBAR_INSET = 8;
    private static String rememberedLeftSearch = "";
    private static String rememberedRightSearch = "";

    private final Screen parent;
    private final Map<String, List<String>> tempRules;
    private final Map<String, Integer> replacementChances = new LinkedHashMap<>();
    private final Runnable onChanged;
    private final List<ItemSearchIndex.CachedItem> allItemsCache;
    private final Set<String> expandedTargets = new HashSet<>();
    private final List<LeftEntry> leftEntries = new ArrayList<>();

    private EditBox leftSearchBox;
    private EditBox searchBox;
    private Button addBtn;
    private Button filterBtn;
    private Button doneBtn;
    private Button saveBtn;
    private Button closeBtn;
    private EditBox replacementChanceBox;

    private List<ItemSearchIndex.CachedItem> rightDisplayList = new ArrayList<>();
    private String selectedTarget;
    private boolean isCreatingRule;
    private boolean selectingNewTarget;
    private boolean groupFilterEnabled;
    private final LinkedHashSet<String> pendingSources = new LinkedHashSet<>();
    private final GridScrollController leftScroll = new GridScrollController();
    private final GridScrollController rightScroll = new GridScrollController();
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
            List<String> pendingSources
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
                new ArrayList<>(pendingSources)
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
        if (selectedTarget != null && !tempRules.containsKey(selectedTarget) && !isCreatingRule) {
            selectedTarget = null;
        }
        if (onChanged != null) onChanged.run();
    }

    public OreMergeScreen(Screen parent, Map<String, List<String>> tempRules, Runnable onChanged) {
        super(Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.title"));
        this.parent = parent;
        this.tempRules = tempRules == null ? new LinkedHashMap<>() : tempRules;
        this.onChanged = onChanged;
        if (WorldBlockConfig.data != null && WorldBlockConfig.data.blockReplacementChances != null) {
            replacementChances.putAll(WorldBlockConfig.data.blockReplacementChances);
        }
        useFluidCanvas(
                640f,
                360f,
                4
        );
        this.allItemsCache = ItemSearchCache.getAllItems();
        configureStandaloneDraft(this::captureMergeSnapshot, this::restoreMergeSnapshot);
    }

    @Override
    protected void buildUi() {
        int sidePadding =
                switch (layoutLevel()) {
                    case LARGE -> 14;
                    case NORMAL -> 10;
                    case SMALL -> 8;
                    case COMPACT -> 6;
                };

        int gap = 5;

        compactLayout =
                isPortraitLayout()
                        || isCompactLayout()
                        || canvasWidth < 560;

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

        replacementChanceBox = createReplacementChanceBox();
        updateLeftEntries();
        updateRightPanel();
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
                                canvasWidth / 4
                        )
                );

        leftX = sidePadding;
        leftY = panelY;

        leftH =
                Math.max(
                        80,
                        canvasHeight
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
                        canvasWidth
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
                        canvasWidth
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
                canvasHeight
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
                        canvasHeight
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

    private EditBox createLeftSearchBox(
            int x,
            int y,
            int width
    ) {
        EditBox box =
                new EditBox(
                        font,
                        x,
                        y,
                        width,
                        20,
                        Component.empty()
                );

        box.setResponder(
                query -> {
                    rememberedLeftSearch =
                            query == null
                                    ? ""
                                    : query;

                    updateLeftEntries();
                }
        );

        box.setValue(
                rememberedLeftSearch
        );

        addRenderableWidget(box);
        return box;
    }

    private EditBox createRightSearchBox(
            int x,
            int y,
            int width
    ) {
        EditBox box =
                new EditBox(
                        font,
                        x,
                        y,
                        width,
                        20,
                        Component.empty()
                );

        box.setResponder(
                query -> {
                    rememberedRightSearch =
                            query == null
                                    ? ""
                                    : query;

                    updateRightPanel();
                }
        );

        box.setValue(
                rememberedRightSearch
        );

        addRenderableWidget(box);
        return box;
    }

    private EditBox createReplacementChanceBox() {
        EditBox box = new EditBox(
                font,
                rightX + rightW - 48,
                rightY + 2,
                44,
                20,
                Component.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance")
        );
        box.setMaxLength(3);
        box.setFilter(value -> value.isEmpty() || value.matches("\\d{1,3}"));
        box.setResponder(this::updateReplacementChance);
        addRenderableWidget(box);
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
        replacementChanceBox.visible = visible;
        replacementChanceBox.active = visible;
        if (!visible) return;
        String value = String.valueOf(replacementChances.getOrDefault(getBaseIdentifier(selectedTarget), 100));
        if (!value.equals(replacementChanceBox.getValue())) replacementChanceBox.setValue(value);
    }

    private Button createAddButton(
            int x,
            int y,
            int width
    ) {
        Button button =
                Button.builder(
                                Component.translatable(
                                        "gui.realmcontrol.worldblock.banitem.merge_add"
                                ),
                                ignored -> {
                                    isCreatingRule = true;
                                    selectingNewTarget = false;
                                    selectedTarget = null;
                                    pendingSources.clear();
                                    groupFilterEnabled = false;

                                    updateLeftEntries();
                                    updateRightPanel();
                                }
                        )
                        .bounds(
                                x,
                                y,
                                width,
                                20
                        )
                        .build();

        addRenderableWidget(button);
        return button;
    }

    private Button createDoneButton(
            int x,
            int y,
            int width
    ) {
        Button button = Button.builder(
                Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.create.done"),
                ignored -> {
                    if (!isCreatingRule || selectingNewTarget || pendingSources.isEmpty()) return;
                    selectingNewTarget = true;
                    groupFilterEnabled = false;
                    updateRightPanel();
                }
        ).bounds(x, y, width, 20).build();
        button.visible = false;
        addRenderableWidget(button);
        return button;
    }

    private Button createFilterButton(
            int x,
            int y,
            int width
    ) {
        Button button =
                Button.builder(
                                Component.translatable(
                                        "gui.realmcontrol.worldblock.banitem.block.merge.filter_group"
                                ),
                                ignored -> {
                                    groupFilterEnabled =
                                            !groupFilterEnabled;

                                    updateRightPanel();
                                }
                        )
                        .bounds(
                                x,
                                y,
                                width,
                                20
                        )
                        .build();

        addRenderableWidget(button);
        return button;
    }

    private Button createSaveButton(
            int x,
            int y,
            int width
    ) {
        Button button =
                Button.builder(
                                Component.translatable(
                                        "gui.realmcontrol.worldblock.banitem.block.merge.save"
                                ),
                                ignored -> saveAndApply()
                        )
                        .bounds(
                                x,
                                y,
                                width,
                                20
                        )
                        .build();

        addRenderableWidget(button);
        return button;
    }

    private Button createCloseButton(
            int x,
            int y,
            int width
    ) {
        Button button =
                Button.builder(
                                Component.translatable(
                                        parent == null
                                                ? "gui.realmcontrol.worldblock.banitem.btn.close"
                                                : "gui.realmcontrol.worldblock.config.back"
                                ),
                                ignored -> closeScreen()
                        )
                        .bounds(
                                x,
                                y,
                                width,
                                20
                        )
                        .build();

        addRenderableWidget(button);
        return button;
    }

    private void updateLeftEntries() {
        leftEntries.clear();
        String query = leftSearchBox == null ? "" : leftSearchBox.getValue().toLowerCase(Locale.ROOT).trim();
        for (Map.Entry<String, List<String>> entry : tempRules.entrySet()) {
            String target = entry.getKey();
            List<String> sources = entry.getValue();
            if (!isOreRule(target, sources)) continue;

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
        searchBox.visible = isCreatingRule || selectedTarget != null;
        searchBox.active = searchBox.visible;
        updateFilterButtonState();
        updateDoneButtonState();
        if (!searchBox.visible) {
            rightDisplayList = new ArrayList<>();
            totalRightH = 0;
            rightScroll.reset();
            rightScroll.update(0, gridAreaH);
            return;
        }

        String query = searchBox.getValue().toLowerCase(Locale.ROOT).trim();
        Set<String> excluded = buildExcludedIdentifiers();
        Set<ItemUnificationHelper.MergeGroup> targetGroups = selectedTarget == null ? Collections.emptySet() : getOreGroupsForId(selectedTarget);
        int creationHash = 31 * pendingSources.hashCode() + (selectingNewTarget ? 1 : 0);
        int sourceHash = 31 * ItemSearchCache.getAllItemsHash()
                + ItemSearchCache.hashStrings(excluded)
                + hashGroups(targetGroups)
                + (groupFilterEnabled ? 1 : 0)
                + creationHash;

        rightDisplayList = new ArrayList<>(ItemSearchCache.searchItems("ore_merge_right_block", allItemsCache, query, item -> {
            if (item == null || item.stack == null || item.stack.isEmpty()) return false;
            if (!isBlockMergeCandidate(item.stack)) return false;
            String itemId = item.idStr;
            String baseId = getBaseIdentifier(itemId);
            if (isCreatingRule && !selectingNewTarget && pendingSources.contains(itemId)) return true;
            if (excluded.contains(itemId) || excluded.contains(baseId)) return false;
            if (isCreatingRule && selectingNewTarget) {
                for (String source : pendingSources) {
                    if (getBaseIdentifier(source).equals(baseId)) return false;
                }
                if (WorldBlockConfig.isOreGenerationBanned(item.stack)) return false;
                return true;
            }
            Set<ItemUnificationHelper.MergeGroup> groups = getOreGroupsForStack(item.stack);
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
        filterBtn.visible = visible;
        filterBtn.active = visible && !groups.isEmpty();
        if (!filterBtn.active) groupFilterEnabled = false;
        filterBtn.setMessage(Component.translatable(groupFilterEnabled ? "gui.realmcontrol.worldblock.banitem.block.merge.filter_group_on" : "gui.realmcontrol.worldblock.banitem.block.merge.filter_group"));
    }

    private void updateDoneButtonState() {
        if (doneBtn == null) return;
        doneBtn.visible = isCreatingRule && !selectingNewTarget;
        doneBtn.active = doneBtn.visible && !pendingSources.isEmpty();
    }

    private boolean isOreRule(String target, List<String> sources) {
        if (isBlockIdentifier(target) || !getOreGroupsForId(target).isEmpty()) return true;
        if (sources == null) return false;
        for (String source : sources) {
            if (isBlockIdentifier(source) || !getOreGroupsForId(source).isEmpty()) return true;
        }
        return false;
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
        if (!validateMergeTargets()) return;
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
        dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.beginServerSave("gui.realmcontrol.worldblock.banitem.block.merge.save_success");
        WorldBlockNetwork.CHANNEL.sendToServer(new WorldBlockNetwork.SaveWorldBlockConfigPacket(WorldBlockConfig.GSON.toJson(ds)));
        commitDraft();
    }

    private boolean validateMergeTargets() {
        for (String target : tempRules.keySet()) {
            ItemStack stack = WorldBlockConfig.parseItemStack(target);
            if (WorldBlockConfig.isOreGenerationBanned(stack)) {
                GuiOverlay.toast("oremerge_target_banned", Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.conflict.target_banned"));
                return false;
            }
        }
        return true;
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
    public void onClose() {
        closeScreen();
    }

    private void closeScreen() {
        if (onChanged != null) onChanged.run();
        if (this.minecraft != null) this.minecraft.setScreen(parent);
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
    protected void renderCanvasBackground(@NotNull GuiGraphics g, int smx, int smy, float pt) {
        g.fillGradient(0, 0, canvasWidth, canvasHeight, 0xFF222222, 0xFF111111);
        GuiTheme.panel(g, leftX, leftY, leftW, leftH, 0xFF1C1C1C, 0xFF555555);
        GuiTheme.panel(g, rightX, rightY, rightW, rightH, 0xFF1C1C1C, 0xFF555555);
    }

    @Override
    protected void renderCanvasForeground(@NotNull GuiGraphics g, int smx, int smy, float pt) {
        renderLeftPanel(g, smx, smy);
        renderRightPanel(g, smx, smy);
        renderSearchHints(g);
    }

    private void renderLeftPanel(GuiGraphics g, int smx, int smy) {
        enableCanvasScissor(
                g,
                leftX,
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
        g.disableScissor();

        leftScroll.render(
                g,
                smx,
                smy,
                leftX + leftW + 2,
                leftY,
                4,
                leftH,
                20,
                SCROLL_TRACK_COLOR,
                SCROLL_THUMB_COLOR,
                SCROLL_THUMB_DRAG_COLOR
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

    private void renderRightStatus(GuiGraphics g) {
        Component info;
        if (isCreatingRule && !selectingNewTarget) {
            info = Component.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.merge.mode.select_replaced",
                    Component.literal(String.valueOf(pendingSources.size())).withStyle(ChatFormatting.YELLOW),
                    Component.literal(String.valueOf(rightDisplayList.size())).withStyle(ChatFormatting.AQUA)
            );
        } else if (isCreatingRule) {
            info = Component.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.merge.mode.select_final_target",
                    Component.literal(String.valueOf(pendingSources.size())).withStyle(ChatFormatting.YELLOW),
                    Component.literal(String.valueOf(rightDisplayList.size())).withStyle(ChatFormatting.AQUA)
            );
        } else {
            ItemStack targetStack = WorldBlockConfig.parseItemStack(selectedTarget);
            Component targetName = ItemCacheHudRenderer.getDisplayNameCustom(targetStack).copy().withStyle(ChatFormatting.GOLD);
            Component filterText = Component.translatable(
                    groupFilterEnabled
                            ? "gui.realmcontrol.worldblock.banitem.block.merge.filter_state.group"
                            : "gui.realmcontrol.worldblock.banitem.block.merge.filter_state.all"
            ).withStyle(ChatFormatting.AQUA);
            info = Component.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.merge.mode.select_source",
                    targetName,
                    Component.literal(String.valueOf(rightDisplayList.size())).withStyle(ChatFormatting.YELLOW),
                    filterText
            );
        }
        int infoRight = replacementChanceBox != null && replacementChanceBox.visible
                ? replacementChanceBox.getX() - font.width(Component.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance")) - 6
                : rightX + rightW - 4;
        var lines = font.split(info, Math.max(1, infoRight - (rightX + 6)));
        if (!lines.isEmpty()) g.drawString(font, lines.get(0), rightX + 6, rightY + 8, 0xFFFFFFFF, false);
        if (replacementChanceBox != null && replacementChanceBox.visible) {
            Component chanceLabel = Component.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance");
            g.drawString(font, chanceLabel, replacementChanceBox.getX() - font.width(chanceLabel) - 3, rightY + 8, 0xFFFFFFFF, false);
        }
    }

    private void renderRightPanel(GuiGraphics g, int smx, int smy) {
        if (!isCreatingRule && selectedTarget == null) return;

        renderRightStatus(g);

        int gridX = rightX + 2;
        int gridY = getRightGridY();
        enableCanvasScissor(
                g,
                rightX,
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
            ItemSearchIndex.CachedItem item = rightDisplayList.get(i);
            boolean hovered = smx >= x && smx < x + SLOT_SIZE
                    && smy >= y && smy < y + SLOT_SIZE;
            GuiTheme.itemSlot(g, item.stack, x, y, SLOT_SIZE, 4, hovered);
            RenderSystem.enableDepthTest();
            ItemBanControl.withSkip(() -> {
                g.renderItem(item.stack, x + 1, y + 1);
                return null;
            });
            RenderSystem.disableDepthTest();
            if (isCreatingRule && !selectingNewTarget && pendingSources.contains(item.idStr)) {
                g.renderOutline(x, y, SLOT_SIZE, SLOT_SIZE, 0xFFFFFFFF);
            }
        }
        g.disableScissor();

        rightScroll.render(
                g,
                smx,
                smy,
                rightX + rightW - RIGHT_SCROLLBAR_INSET,
                gridY,
                4,
                gridAreaH,
                20,
                SCROLL_TRACK_COLOR,
                RIGHT_SCROLL_THUMB_COLOR,
                RIGHT_SCROLL_THUMB_HOVER_COLOR
        );
    }

    private void renderSearchHints(GuiGraphics g) {
        if (leftSearchBox != null && leftSearchBox.getValue().isEmpty() && !leftSearchBox.isFocused()) {
            g.drawString(font, Component.translatable("gui.realmcontrol.worldblock.banitem.search.hint"), leftSearchBox.getX() + 6, leftSearchBox.getY() + 6, 0x888888, false);
        }
        if (searchBox != null && searchBox.visible && searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            g.drawString(font, Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.search"), searchBox.getX() + 6, searchBox.getY() + 6, 0x888888, false);
        }
    }

    private boolean isHoveringButton(Button button, double mx, double my) {
        return button != null && button.visible && mx >= button.getX() && mx < button.getX() + button.getWidth() && my >= button.getY() && my < button.getY() + button.getHeight();
    }

    @Override
    protected void renderTooltips(@NotNull GuiGraphics g, int smx, int smy, int mx, int my) {
        int tooltipY = smy < leftY ? my + 15 : my;
        if (isHoveringButton(addBtn, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.tooltip.btn.add"), mx, tooltipY);
            return;
        }
        if (isHoveringButton(doneBtn, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.create.done.tooltip"), mx, tooltipY);
            return;
        }
        if (isHoveringButton(filterBtn, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.tooltip.btn.filter"), mx, tooltipY);
            return;
        }
        if (isHoveringButton(saveBtn, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.tooltip.btn.save"), mx, tooltipY);
            return;
        }
        if (isHoveringButton(closeBtn, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable(parent == null ? "gui.realmcontrol.worldblock.banitem.tooltip.btn.close" : "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.btn.back"), mx, tooltipY);
            return;
        }

        if (smx >= leftX && smx < leftX + leftW && smy >= leftY && smy < leftY + leftH) {
            int currentY = leftY - (int) Math.round(leftScroll.smoothOffset());
            for (LeftEntry entry : leftEntries) {
                if (currentY + entry.h >= leftY && currentY <= leftY + leftH && smx >= entry.x && smx < entry.x + entry.w && smy >= currentY && smy < currentY + entry.h) {
                    entry.requestTooltip(g, mx, my);
                    return;
                }
                currentY += entry.h + 1;
            }
        }

        int idx = getRightItemIndexAt(smx, smy);
        if (idx >= 0) {
            ItemSearchIndex.CachedItem item = rightDisplayList.get(idx);
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(item.stack));
            tooltip.add(Component.literal(item.idStr));
            String actionKey = !isCreatingRule
                    ? "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.add_source"
                    : selectingNewTarget
                    ? "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.set_final_target"
                    : pendingSources.contains(item.idStr)
                    ? "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.unselect_replaced"
                    : "gui.realmcontrol.worldblock.banitem.block.merge.tooltip.select_replaced";
            tooltip.add(Component.translatable(actionKey));
            GuiOverlay.requestTooltip(tooltip, mx, my);
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
    protected boolean canvasMouseClicked(double smx, double smy, int btn) {
        if (btn == 0
                && leftScroll.beginDrag(
                        smx,
                        smy,
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

        if (btn == 0
                && rightScroll.beginDrag(
                        smx,
                        smy,
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
                    if (entry.mouseClicked(smx, smy, btn)) return true;
                }
                currentY += entry.h + 1;
            }
        } else {
            int idx = getRightItemIndexAt(smx, smy);
            if (btn == 0 && idx >= 0) {
            ItemSearchIndex.CachedItem clicked = rightDisplayList.get(idx);
            String id = clicked.idStr;
            if (isCreatingRule && !selectingNewTarget) {
                if (!pendingSources.add(id)) pendingSources.remove(id);
            } else if (isCreatingRule) {
                if (pendingSources.isEmpty()) {
                    selectingNewTarget = false;
                    updateRightPanel();
                    return true;
                }
                if (WorldBlockConfig.isOreGenerationBanned(clicked.stack)) {
                    GuiOverlay.toast("oremerge_target_banned", Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.conflict.target_banned"));
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
        return super.canvasMouseClicked(smx, smy, btn);
    }

    @Override
    protected boolean canvasMouseReleased(double smx, double smy, int btn) {
        boolean released =
                leftScroll.release(btn)
                        | rightScroll.release(btn);

        return released
                || super.canvasMouseReleased(smx, smy, btn);
    }

    @Override
    protected boolean canvasMouseDragged(double smx, double smy, int btn, double dx, double dy) {
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

        return super.canvasMouseDragged(smx, smy, btn, dx, dy);
    }

    @Override
    protected boolean canvasMouseScrolled(double smx, double smy, double delta) {
        if (smx >= leftX
                && smx <= leftX + leftW
                && smy >= leftY
                && smy <= leftY + leftH
                && leftScroll.scroll(delta, 10 / 3.0D)) {
            return true;
        }

        int rightGridY = getRightGridY();

        if (smx >= rightX
                && smx <= rightX + rightW
                && smy >= rightGridY
                && smy <= rightGridY + gridAreaH
                && rightScroll.scroll(delta, SLOT_PITCH / 3.0D)) {
            return true;
        }

        return super.canvasMouseScrolled(smx, smy, delta);
    }

    abstract static class LeftEntry {
        int x;
        int y;
        int w;
        int h;

        abstract void render(GuiGraphics g, int mx, int my);

        abstract boolean mouseClicked(double mx, double my, int btn);

        abstract void requestTooltip(GuiGraphics g, int mx, int my);
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
        void render(GuiGraphics g, int mx, int my) {
            boolean selected = id.equals(selectedTarget);
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
            g.fill(x, y, x + w, y + h, selected ? 0xFF555555 : hover ? 0xFF333333 : 0xFF222222);
            GuiTheme.itemSlot(g, stack, x + 1, y + 1, SLOT_SIZE, 4, hover);
            RenderSystem.enableDepthTest();
            ItemBanControl.withSkip(() -> {
                g.renderItem(stack, x + 2, y + 2);
                return null;
            });
            RenderSystem.disableDepthTest();
            String name = ItemCacheHudRenderer.getDisplayNameCustom(stack).getString();
            g.drawString(font, font.plainSubstrByWidth(name, w - 58), x + 24, y + 6, 0xFFFFFF, false);
            g.drawString(font, Component.translatable("gui.realmcontrol.worldblock.common.count_parentheses", Component.literal(String.valueOf(count)).withStyle(ChatFormatting.YELLOW)), x + w - 24, y + 6, 0xFFFFFF, false);
            g.drawString(font, Component.translatable(expandedTargets.contains(id) ? "gui.realmcontrol.worldblock.common.collapse" : "gui.realmcontrol.worldblock.common.expand"), x + w - 36, y + 6, 0xFFFFFF, false);
        }

        @Override
        boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 0) {
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
            if (btn == 1) {
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
        void requestTooltip(GuiGraphics g, int mx, int my) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(stack));
            tooltip.add(Component.literal(id));
            tooltip.add(Component.translatable("gui.realmcontrol.worldblock.banitem.tooltip.target_del"));
            GuiOverlay.requestTooltip(tooltip, mx, my);
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
        void render(GuiGraphics g, int mx, int my) {
            boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
            g.fill(x, y, x + w, y + h, hover ? 0xFF2A2A2A : 0xFF141414);
            GuiTheme.itemSlot(g, stack, x + 1, y + 1, SLOT_SIZE, 4, hover);
            RenderSystem.enableDepthTest();
            ItemBanControl.withSkip(() -> {
                g.renderItem(stack, x + 2, y + 2);
                return null;
            });
            RenderSystem.disableDepthTest();
        }

        @Override
        boolean mouseClicked(double mx, double my, int btn) {
            if (btn == 1) {
                List<String> sources = tempRules.get(targetId);
                if (sources != null) sources.remove(sourceId);
                updateLeftEntries();
                updateRightPanel();
                return true;
            }
            return false;
        }

        @Override
        void requestTooltip(GuiGraphics g, int mx, int my) {
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(stack));
            tooltip.add(Component.literal(sourceId));
            tooltip.add(Component.translatable("gui.realmcontrol.worldblock.banitem.tooltip.source_del"));
            GuiOverlay.requestTooltip(tooltip, mx, my);
        }
    }
}
