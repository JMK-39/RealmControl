package dev.xyat.realmcontrol.worldblock.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class WeightedBlockMergePage extends KineticPage {
    private enum Mode {
        BROWSE,
        SELECT_SOURCE,
        SELECT_TARGETS
    }

    private static final int SLOT_SIZE = 18;
    private static final int SLOT_PITCH = 19;
    private static final int PANEL_INSET = 2;
    private static final int SCROLLBAR_WIDTH = 4;
    private static final int SCROLLBAR_MIN_THUMB = 20;
    private static final int SELECTED_OUTLINE_COLOR = 0xFF55FF55;
    private static final int HOVER_OUTLINE_COLOR = 0xFFAAAAAA;
    private static final int MAX_WEIGHT = 1_000_000;
    private static final int MAX_TARGETS = 1024;

    private final Map<String, List<WorldBlockConfig.WeightedBlockTarget>> rules;
    private final Map<String, Integer> replacementChances = new LinkedHashMap<>();
    private final List<KineticItemSearch.CachedItem> allItems;
    private final KineticScrollController leftScroll = new KineticScrollController();
    private final KineticScrollController rightScroll = new KineticScrollController();
    private final LinkedHashMap<String, Integer> selectedTargets = new LinkedHashMap<>();
    private final List<String> visibleSources = new ArrayList<>();
    private List<KineticItemSearch.CachedItem> visibleItems = new ArrayList<>();

    private Mode mode = Mode.BROWSE;
    private String selectedSource;
    private String activeTarget;
    private KineticTextField leftSearch;
    private KineticTextField rightSearch;
    private KineticTextField weightBox;
    private KineticTextField chanceBox;
    private KineticButton newButton;
    private KineticButton doneButton;
    private KineticButton minusButton;
    private KineticButton plusButton;
    private KineticButton saveButton;
    private KineticButton backButton;
    private KineticButton loadedChunksToggleBtn;
    private int leftX;
    private int leftY;
    private int leftW;
    private int leftH;
    private int rightX;
    private int rightY;
    private int rightW;
    private int rightH;
    private int leftContentX;
    private int leftContentY;
    private int leftContentW;
    private int leftContentH;
    private int rightContentX;
    private int rightContentY;
    private int rightContentW;
    private int rightContentH;
    private int cols;
    private int totalLeftHeight;
    private int totalRightHeight;
    private boolean weightInputValid = true;
    private boolean applyWeightedToLoadedChunksOnce;

    private record WeightedSnapshot(
            Map<String, List<WorldBlockConfig.WeightedBlockTarget>> rules,
            Map<String, Integer> replacementChances,
            LinkedHashMap<String, Integer> selectedTargets,
            Mode mode,
            String selectedSource,
            String activeTarget,
            boolean weightInputValid,
            boolean applyWeightedToLoadedChunksOnce
    ) {
    }

    private WeightedSnapshot captureWeightedSnapshot() {
        Map<String, List<WorldBlockConfig.WeightedBlockTarget>> rulesCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorldBlockConfig.WeightedBlockTarget>> entry : rules.entrySet()) {
            List<WorldBlockConfig.WeightedBlockTarget> targets = new ArrayList<>();
            for (WorldBlockConfig.WeightedBlockTarget target : entry.getValue()) {
                targets.add(new WorldBlockConfig.WeightedBlockTarget(target.target, target.weight));
            }
            rulesCopy.put(entry.getKey(), targets);
        }
        return new WeightedSnapshot(
                rulesCopy,
                new LinkedHashMap<>(replacementChances),
                new LinkedHashMap<>(selectedTargets),
                mode,
                selectedSource,
                activeTarget,
                weightInputValid,
                applyWeightedToLoadedChunksOnce
        );
    }

    private void restoreWeightedSnapshot(WeightedSnapshot snapshot) {
        if (snapshot == null) return;
        rules.clear();
        for (Map.Entry<String, List<WorldBlockConfig.WeightedBlockTarget>> entry : snapshot.rules().entrySet()) {
            List<WorldBlockConfig.WeightedBlockTarget> targets = new ArrayList<>();
            for (WorldBlockConfig.WeightedBlockTarget target : entry.getValue()) {
                targets.add(new WorldBlockConfig.WeightedBlockTarget(target.target, target.weight));
            }
            rules.put(entry.getKey(), targets);
        }
        replacementChances.clear();
        replacementChances.putAll(snapshot.replacementChances());
        selectedTargets.clear();
        selectedTargets.putAll(snapshot.selectedTargets());
        mode = snapshot.mode();
        selectedSource = snapshot.selectedSource();
        activeTarget = snapshot.activeTarget();
        weightInputValid = snapshot.weightInputValid();
        applyWeightedToLoadedChunksOnce = snapshot.applyWeightedToLoadedChunksOnce();
        if (loadedChunksToggleBtn != null) loadedChunksToggleBtn.setText(getLoadedChunksToggleText());
    }

    public WeightedBlockMergePage() {
        super(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.title"));
        this.rules = WorldBlockConfig.copyWeightedBlockReplacements();
        if (WorldBlockConfig.data != null && WorldBlockConfig.data.weightedBlockReplacementChances != null) {
            replacementChances.putAll(WorldBlockConfig.data.weightedBlockReplacementChances);
        }
        this.applyWeightedToLoadedChunksOnce = WorldBlockConfig.data != null && WorldBlockConfig.data.applyWeightedBlockReplacementToLoadedChunksOnce;
        this.allItems = new ArrayList<>(ItemSearchCache.getAllItems());
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        configureStandaloneDraft(this::captureWeightedSnapshot, this::restoreWeightedSnapshot);
    }

    @Override
    protected void build(KineticUi ui) {
        int pad = 8;
        int gap = 6;
        leftW = Math.max(150, Math.min(190, width() / 3));
        leftX = pad;
        rightX = leftX + leftW + gap;
        rightW = Math.max(180, width() - rightX - pad);
        leftY = 58;
        rightY = 58;
        leftH = Math.max(100, height() - leftY - 10);
        rightH = leftH;

        leftContentX = leftX + PANEL_INSET;
        leftContentY = leftY + PANEL_INSET;
        leftContentW = Math.max(1, leftW - PANEL_INSET * 3 - SCROLLBAR_WIDTH);
        leftContentH = Math.max(1, leftH - PANEL_INSET * 2);
        rightContentX = rightX + PANEL_INSET;
        rightContentY = rightY + PANEL_INSET;
        rightContentW = Math.max(1, rightW - PANEL_INSET * 2 - SCROLLBAR_WIDTH);
        rightContentH = Math.max(1, rightH - PANEL_INSET * 2);
        cols = Math.max(1, rightContentW / SLOT_PITCH);

        leftSearch = ui().textField(leftX, 5, leftW).placeholder(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.search.hint")).build();
        leftSearch.onTextChange(ignored -> refreshLeft());
int buttonWidth = 62;
        int closeX = rightX + rightW - buttonWidth;
        int saveX = closeX - gap - buttonWidth;
        int doneX = saveX - gap - buttonWidth;
        int newX = doneX - gap - buttonWidth;
        int searchWidth = Math.max(80, newX - rightX - gap);

        rightSearch = ui().textField(rightX, 5, searchWidth).placeholder(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.search")).build();
        rightSearch.onTextChange(ignored -> refreshRight());
newButton = ui().button(newX, 5, buttonWidth).text(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.new")).onClick(() -> startNewRule()).build();
doneButton = ui().button(doneX, 5, buttonWidth).text(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.done")).onClick(() -> finishTargets()).build();
saveButton = ui().button(saveX, 5, buttonWidth).text(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.merge.save")).onClick(() -> save()).build();
backButton = ui().button(closeX, 5, buttonWidth).text(KineticI18n.translatable("gui.realmcontrol.worldblock.config.back")).onClick(() -> close()).build();
Component weightLabel = KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight");
        int weightBoxX = rightX + KineticText.width(weightLabel) + 6;
        weightBox = ui().textField(weightBoxX, 31, 72).build();
        weightBox.limitTextLength(7);
        weightBox.setValidator(value -> value.isEmpty() || value.matches("\\d{1,7}"));
        weightBox.onTextChange(this::updateActiveWeightFromText);
int minusX = weightBoxX + weightBox.controlWidth() + 4;
        minusButton = ui().button(minusX, 31, 42).text(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight.minus")).onClick(() -> adjustWeight(-10)).build();
plusButton = ui().button(minusX + 46, 31, 42).text(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight.plus")).onClick(() -> adjustWeight(10)).build();
int chanceBoxX = rightX + rightW - 48;
        chanceBox = ui().textField(chanceBoxX, 31, 44).label(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance")).build();
        chanceBox.limitTextLength(3);
        chanceBox.setValidator(value -> value.isEmpty() || value.matches("\\d{1,3}"));
        chanceBox.onTextChange(this::updateReplacementChance);
int loadedToggleWidth = 80;
        int chanceLabelWidth = KineticText.width(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance"));
        int loadedToggleX = chanceBoxX - chanceLabelWidth - loadedToggleWidth - 10;
        loadedChunksToggleBtn = ui().button(loadedToggleX, 31, loadedToggleWidth).text(getLoadedChunksToggleText()).onClick(() -> {
                    applyWeightedToLoadedChunksOnce = !applyWeightedToLoadedChunksOnce;
                    loadedChunksToggleBtn.setText(getLoadedChunksToggleText());
                }).build();
refreshLeft();
        refreshRight();
        updateControls();
        ItemSearchCache.prepareCache(() -> {
            allItems.clear();
            allItems.addAll(ItemSearchCache.getAllItems());
            refreshLeft();
            refreshRight();
            updateControls();
        });
    }

    private Component getLoadedChunksToggleText() {
        return KineticI18n.translatable(
                applyWeightedToLoadedChunksOnce
                        ? "gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.on"
                        : "gui.realmcontrol.worldblock.banitem.block.merge.loaded_chunks.off"
        );
    }

    private void startNewRule() {
        mode = Mode.SELECT_SOURCE;
        selectedSource = null;
        activeTarget = null;
        selectedTargets.clear();
        weightInputValid = true;
        refreshRight();
        updateControls();
    }

    private void editRule(String source) {
        selectedSource = source;
        selectedTargets.clear();
        List<WorldBlockConfig.WeightedBlockTarget> targets = rules.get(source);
        if (targets != null) {
            for (WorldBlockConfig.WeightedBlockTarget target : targets) {
                if (target != null && target.target != null && !target.target.isBlank()) {
                    selectedTargets.put(target.target, Math.max(1, target.weight));
                }
            }
        }
        activeTarget = selectedTargets.isEmpty() ? null : selectedTargets.keySet().iterator().next();
        weightInputValid = true;
        mode = Mode.SELECT_TARGETS;
        refreshRight();
        rightScroll.setOffset(0);
        updateControls();
        syncChanceBox();
        syncWeightBox();
    }

    private void storeCurrentRuleForSwitch() {
        if (mode != Mode.SELECT_TARGETS || selectedSource == null || selectedTargets.isEmpty()) return;

        List<WorldBlockConfig.WeightedBlockTarget> targets = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : selectedTargets.entrySet()) {
            targets.add(new WorldBlockConfig.WeightedBlockTarget(entry.getKey(), clampWeight(entry.getValue())));
        }
        rules.put(selectedSource, targets);
    }

    private void switchRule(String source) {
        if (source == null || source.isBlank()) return;
        if (mode == Mode.SELECT_TARGETS && source.equals(selectedSource)) return;
        storeCurrentRuleForSwitch();
        editRule(source);
    }

    private void deleteRule(String source) {
        if (source == null || source.isBlank() || !rules.containsKey(source)) return;

        boolean deletingActiveRule = mode == Mode.SELECT_TARGETS && source.equals(selectedSource);
        if (mode == Mode.SELECT_TARGETS && !deletingActiveRule) {
            storeCurrentRuleForSwitch();
        }

        rules.remove(source);
        replacementChances.remove(source);

        if (deletingActiveRule) {
            mode = Mode.BROWSE;
            selectedSource = null;
            activeTarget = null;
            selectedTargets.clear();
            weightInputValid = true;
            rightScroll.setOffset(0);
            syncWeightBox();
        }

        refreshLeft();
        refreshRight();
        updateControls();
    }

    private void finishTargets() {
        if (mode != Mode.SELECT_TARGETS || selectedSource == null || selectedTargets.isEmpty()) return;
        List<WorldBlockConfig.WeightedBlockTarget> targets = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : selectedTargets.entrySet()) {
            targets.add(new WorldBlockConfig.WeightedBlockTarget(entry.getKey(), clampWeight(entry.getValue())));
        }
        rules.put(selectedSource, targets);
        mode = Mode.BROWSE;
        selectedSource = null;
        activeTarget = null;
        selectedTargets.clear();
        refreshLeft();
        refreshRight();
        updateControls();
    }

    private void refreshLeft() {
        visibleSources.clear();
        String query = leftSearch == null ? "" : leftSearch.textValue().trim().toLowerCase(Locale.ROOT);
        for (String source : rules.keySet()) {
            if (query.isEmpty() || KineticSearch.match(ItemSearchCache.getSearchDataForId(source), query)) {
                visibleSources.add(source);
            }
        }
        visibleSources.sort(Comparator.naturalOrder());
        totalLeftHeight = visibleSources.size() * 25;
        leftScroll.update(totalLeftHeight, leftContentH);
    }

    private void refreshRight() {
        if (rightSearch == null) return;
        if (mode == Mode.BROWSE) {
            visibleItems = new ArrayList<>();
            totalRightHeight = 0;
            rightScroll.update(0, rightContentH);
            return;
        }

        String query = rightSearch.textValue().trim().toLowerCase(Locale.ROOT);
        Set<String> simpleSources = simpleSourceIds();
        Set<String> weightedTargets = weightedTargetIds();
        int hash = 31 * ItemSearchCache.getAllItemsHash()
                + rules.keySet().hashCode()
                + simpleSources.hashCode()
                + weightedTargets.hashCode()
                + mode.ordinal();
        visibleItems = new ArrayList<>(ItemSearchCache.searchItems("weighted_block_replace", allItems, query, item -> {
            if (item == null || item.stack() == null || item.stack().isEmpty()) return false;
            if (!(item.stack().getItem() instanceof BlockItem)) return false;
            String id = item.id();
            if (mode == Mode.SELECT_SOURCE) {
                return !rules.containsKey(id)
                        && !weightedTargets.contains(id)
                        && !simpleSources.contains(id)
                        && !WorldBlockConfig.isOreGenerationBanned(item.stack());
            }
            if (selectedSource == null || selectedSource.equals(id)) return false;
            if (rules.containsKey(id) || simpleSources.contains(id)) return false;
            return !WorldBlockConfig.isOreGenerationBanned(item.stack());
        }, hash));
        int rows = (int) Math.ceil((double) visibleItems.size() / cols);
        totalRightHeight = rows * SLOT_PITCH;
        rightScroll.update(totalRightHeight, rightContentH);
    }

    private Set<String> simpleSourceIds() {
        return WorldBlockConfig.getSimpleBlockMergeIdentifiers();
    }

    private Set<String> weightedTargetIds() {
        Set<String> result = new HashSet<>();
        for (List<WorldBlockConfig.WeightedBlockTarget> targets : rules.values()) {
            if (targets == null) continue;
            for (WorldBlockConfig.WeightedBlockTarget target : targets) {
                if (target != null && target.target != null && !target.target.isBlank()) {
                    result.add(target.target);
                }
            }
        }
        return result;
    }

    private void selectSource(String id) {
        selectedSource = id;
        selectedTargets.clear();
        activeTarget = null;
        weightInputValid = true;
        mode = Mode.SELECT_TARGETS;
        refreshRight();
        updateControls();
        syncChanceBox();
    }

    private void selectTarget(String id) {
        boolean added = false;
        if (!selectedTargets.containsKey(id)) {
            if (selectedTargets.size() >= MAX_TARGETS) {
                KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.too_many_targets"));
                return;
            }
            selectedTargets.put(id, 100);
            added = true;
        }
        activeTarget = id;
        weightInputValid = true;
        if (added) {
            refreshRight();
        }
        syncWeightBox();
        updateControls();
    }

    private void removeTarget(String id) {
        selectedTargets.remove(id);
        if (id.equals(activeTarget)) activeTarget = selectedTargets.isEmpty() ? null : selectedTargets.keySet().iterator().next();
        weightInputValid = true;
        refreshRight();
        syncWeightBox();
        updateControls();
    }

    private void updateReplacementChance(String text) {
        if (selectedSource == null || text == null || text.isBlank()) return;
        try {
            int chance = Math.max(0, Math.min(100, Integer.parseInt(text)));
            if (chance >= 100) replacementChances.remove(selectedSource);
            else replacementChances.put(selectedSource, chance);
        } catch (NumberFormatException ignored) {
        }
    }

    private void syncChanceBox() {
        if (chanceBox == null || selectedSource == null) return;
        String original = String.valueOf(WorldBlockConfig.data == null || WorldBlockConfig.data.weightedBlockReplacementChances == null
                ? 100 : WorldBlockConfig.data.weightedBlockReplacementChances.getOrDefault(selectedSource, 100));
        chanceBox.setDefaultText(original);
        String value = String.valueOf(replacementChances.getOrDefault(selectedSource, 100));
        if (!value.equals(chanceBox.textValue())) chanceBox.setTextValue(value);
    }

    private void updateActiveWeightFromText(String text) {
        if (activeTarget == null) {
            weightInputValid = true;
            updateControls();
            return;
        }
        if (text == null || text.isBlank()) {
            weightInputValid = false;
            updateControls();
            return;
        }
        try {
            int value = Integer.parseInt(text);
            weightInputValid = value >= 1 && value <= MAX_WEIGHT;
            if (weightInputValid) selectedTargets.put(activeTarget, value);
        } catch (NumberFormatException ignored) {
            weightInputValid = false;
        }
        updateControls();
    }

    private void adjustWeight(int delta) {
        if (activeTarget == null) return;
        int value = clampWeight(selectedTargets.getOrDefault(activeTarget, 100) + delta);
        selectedTargets.put(activeTarget, value);
        weightInputValid = true;
        syncWeightBox();
        updateControls();
    }

    private int clampWeight(int value) {
        return Math.max(1, Math.min(MAX_WEIGHT, value));
    }

    private void syncWeightBox() {
        if (weightBox == null) return;
        int originalWeight = 100;
        if (activeTarget != null && selectedSource != null && WorldBlockConfig.data != null
                && WorldBlockConfig.data.weightedBlockReplacements != null) {
            List<WorldBlockConfig.WeightedBlockTarget> originals = WorldBlockConfig.data.weightedBlockReplacements.get(selectedSource);
            if (originals != null) {
                for (WorldBlockConfig.WeightedBlockTarget target : originals) {
                    if (target != null && activeTarget.equals(target.target)) {
                        originalWeight = target.weight;
                        break;
                    }
                }
            }
        }
        weightBox.setDefaultText(activeTarget == null ? "" : String.valueOf(originalWeight));
        weightBox.setTextValue(activeTarget == null ? "" : String.valueOf(selectedTargets.getOrDefault(activeTarget, 100)));
    }

    private void updateControls() {
        if (newButton != null) {
            setControlEnabled(newButton, mode == Mode.BROWSE);
        }
        if (doneButton != null) {
            setControlVisible(doneButton, mode == Mode.SELECT_TARGETS);
            setControlEnabled(doneButton, isControlVisible(doneButton) && currentRuleValid());
        }
        if (saveButton != null) {
            setControlEnabled(saveButton, mode == Mode.BROWSE);
        }
        if (loadedChunksToggleBtn != null) {
            setControlEnabled(loadedChunksToggleBtn, mode == Mode.BROWSE);
        }
        boolean editWeight = mode == Mode.SELECT_TARGETS && activeTarget != null;
        boolean editChance = mode == Mode.SELECT_TARGETS && selectedSource != null;
        if (chanceBox != null) {
            setControlVisible(chanceBox, editChance);
            setControlEnabled(chanceBox, editChance);
        }
        if (weightBox != null) {
            setControlVisible(weightBox, editWeight);
            setControlEnabled(weightBox, editWeight);
        }
        if (minusButton != null) {
            setControlVisible(minusButton, editWeight);
            setControlEnabled(minusButton, editWeight);
        }
        if (plusButton != null) {
            setControlVisible(plusButton, editWeight);
            setControlEnabled(plusButton, editWeight);
        }
    }

    private boolean currentRuleValid() {
        if (selectedSource == null || selectedTargets.isEmpty() || selectedTargets.size() > MAX_TARGETS || !weightInputValid) {
            return false;
        }
        long total = 0L;
        for (int weight : selectedTargets.values()) {
            if (weight < 1 || weight > MAX_WEIGHT) return false;
            total += weight;
            if (total > Integer.MAX_VALUE) return false;
        }
        return true;
    }

    private long selectedTotalWeight() {
        long total = 0L;
        for (int weight : selectedTargets.values()) total += weight;
        return total;
    }

    private void save() {
        WorldBlockConfig.Data data = new WorldBlockConfig.Data();
        data.bannedItems = new ArrayList<>(WorldBlockConfig.data.bannedItems);
        data.bannedOreGenerations = new ArrayList<>(WorldBlockConfig.data.bannedOreGenerations);
        if (WorldBlockConfig.data.mergedItems != null) {
            for (Map.Entry<String, List<String>> entry : WorldBlockConfig.data.mergedItems.entrySet()) {
                data.mergedItems.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        if (WorldBlockConfig.data.oreMergedItems != null) {
            for (Map.Entry<String, List<String>> entry : WorldBlockConfig.data.oreMergedItems.entrySet()) {
                data.oreMergedItems.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        for (Map.Entry<String, List<WorldBlockConfig.WeightedBlockTarget>> entry : rules.entrySet()) {
            List<WorldBlockConfig.WeightedBlockTarget> targets = new ArrayList<>();
            for (WorldBlockConfig.WeightedBlockTarget target : entry.getValue()) {
                targets.add(new WorldBlockConfig.WeightedBlockTarget(target.target, target.weight));
            }
            if (!targets.isEmpty()) data.weightedBlockReplacements.put(entry.getKey(), targets);
        }
        data.blockReplacementChances.putAll(WorldBlockConfig.data.blockReplacementChances);
        data.weightedBlockReplacementChances.putAll(replacementChances);
        data.applyBlockReplacementToLoadedChunksOnce = WorldBlockConfig.data.applyBlockReplacementToLoadedChunksOnce;
        data.applyWeightedBlockReplacementToLoadedChunksOnce = applyWeightedToLoadedChunksOnce;
        dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.beginServerSave("gui.realmcontrol.worldblock.banitem.block.weighted.save_success");
        WorldBlockNetwork.CHANNEL.sendToServer(new WorldBlockNetwork.SaveWorldBlockConfigPacket(WorldBlockConfig.GSON.toJson(data)));
        commitDraft();
    }


    @Override
    protected void renderBackground(KineticGraphics g, int smx, int smy, float pt) {
        g.fillGradient(0, 0, width(), height(), 0xFF222222, 0xFF111111);
        KineticTheme.panel(g, leftX, leftY, leftW, leftH);
        KineticTheme.panel(g, rightX, rightY, rightW, rightH);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int smx, int smy, float pt) {
        renderLeft(g, smx, smy);
        renderRight(g, smx, smy);
        renderHeader(g);
    }

    private void renderHeader(KineticGraphics g) {
        int statusX = leftX;
        Component status = switch (mode) {
            case BROWSE -> KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.mode.browse");
            case SELECT_SOURCE -> KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.mode.source");
            case SELECT_TARGETS -> KineticI18n.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.weighted.mode.targets",
                    Component.literal(String.valueOf(selectedTargets.size()))
            );
        };
        if (mode == Mode.SELECT_TARGETS && selectedSource != null) {
            var sourceStack = WorldBlockConfig.parseItemStack(selectedSource);
            KineticTheme.itemSlot(g, leftX, 32, SLOT_SIZE, 4, false);
            ItemBanControl.withSkip(() -> {
                KineticTheme.item(g, sourceStack, leftX, 32, SLOT_SIZE, 1.0F, false);
                return null;
            });
            statusX += SLOT_SIZE + 4;
        }
        int statusRight = width() - 8;
        int statusWidth = mode == Mode.SELECT_TARGETS && activeTarget != null
                ? Math.max(1, leftW - (statusX - leftX))
                : Math.max(1, statusRight - statusX);
        g.scrollingText(status, statusX, 36, statusWidth, 0xFFFFFFFF, false);
        if (mode == Mode.SELECT_TARGETS && activeTarget != null) {
            g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight"), rightX, 37, Math.max(1, weightBox.controlX() - rightX - 3), 0xFFFFFFFF, false);
        }
        if (chanceBox != null && isControlVisible(chanceBox)) {
            Component chanceLabel = KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance");
            g.scrollingTextRight(chanceLabel, chanceBox.controlX() - 3, 37, Math.max(1, chanceBox.controlX() - rightX - 6), 0xFFFFFFFF, false);
        }
    }

    private void renderLeft(KineticGraphics g, int mx, int my) {
        g.scissor(leftContentX,
                leftContentY,
                leftContentX + leftContentW,
                leftContentY + leftContentH
        );
        int y = leftContentY - (int) Math.round(leftScroll.smoothOffset());
        for (String source : visibleSources) {
            int rowY = y;
            if (rowY + 24 >= leftContentY && rowY < leftContentY + leftContentH) {
                boolean hovered = mx >= leftContentX
                        && mx < leftContentX + leftContentW
                        && my >= rowY
                        && my < rowY + 24;
                boolean selected = source.equals(selectedSource);
                KineticTheme.stateSurface(
                        g,
                        leftContentX,
                        rowY,
                        leftContentW,
                        24,
                        KineticTheme.Surface.PANEL_ALT,
                        selected,
                        hovered,
                        false
                );
                var stack = WorldBlockConfig.parseItemStack(source);
                KineticTheme.itemSlot(g, leftContentX + 2, rowY + 2, 20, 20, 4, selected, hovered, false);
                ItemBanControl.withSkip(() -> {
                    KineticTheme.item(g, stack, leftContentX + 2, rowY + 2, 20, 1.0F, false);
                    return null;
                });
                String name = ItemCacheHudRenderer.getDisplayNameCustom(stack).getString();
                int countWidth = 18;
                int nameWidth = Math.max(8, leftContentW - 30 - countWidth);
                g.scrollingText(Component.literal(name), leftContentX + 26, rowY + 8, nameWidth, 0xFFFFFFFF, false);
                int count = rules.getOrDefault(source, List.of()).size();
                Component countText = Component.literal(String.valueOf(count));
                g.scrollingTextRight(countText, leftContentX + leftContentW - 2, rowY + 8, 24, 0xFFFFFFFF, false);
            }
            y += 25;
        }
        g.endScissor();
        leftScroll.render(
                g,
                mx,
                my,
                leftX + leftW - PANEL_INSET - SCROLLBAR_WIDTH,
                leftContentY,
                SCROLLBAR_WIDTH,
                leftContentH,
                SCROLLBAR_MIN_THUMB
        );
    }

    private void renderRight(KineticGraphics g, int mx, int my) {
        if (mode == Mode.BROWSE) return;
        g.scissor(rightContentX,
                rightContentY,
                rightContentX + rightContentW,
                rightContentY + rightContentH
        );
        int gridX = rightContentX;
        int gridY = rightContentY;
        long totalWeight = selectedTotalWeight();
        for (int i = 0; i < visibleItems.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int x = gridX + col * SLOT_PITCH;
            int y = gridY + row * SLOT_PITCH - (int) Math.round(rightScroll.smoothOffset());
            if (y + SLOT_SIZE <= rightContentY || y >= rightContentY + rightContentH) continue;
            KineticItemSearch.CachedItem item = visibleItems.get(i);
            boolean hovered = mx >= x && mx < x + SLOT_SIZE && my >= y && my < y + SLOT_SIZE;
            boolean selected = selectedTargets.containsKey(item.id());
            KineticTheme.itemSlot(g, x, y, SLOT_SIZE, SLOT_SIZE, 4, selected, hovered, false);
            ItemBanControl.withSkip(() -> {
                KineticTheme.item(g, item.stack(), x, y, SLOT_SIZE, 1.0F, false);
                return null;
            });
            if (selected) {
                String probability = formatProbability(selectedTargets.get(item.id()), totalWeight);
                Component badge = KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.probability_badge", probability);
                renderProbabilityBadge(g, x, y, badge);
            }
        }
        g.endScissor();
        rightScroll.render(
                g,
                mx,
                my,
                rightX + rightW - PANEL_INSET - SCROLLBAR_WIDTH,
                rightContentY,
                SCROLLBAR_WIDTH,
                rightContentH,
                SCROLLBAR_MIN_THUMB
        );
    }

    private void renderProbabilityBadge(KineticGraphics g, int x, int y, Component badge) {
        int scaledWidth = Math.max(1, (int) Math.ceil(KineticText.width(badge) * 0.5F));
        int drawX = x + SLOT_SIZE - scaledWidth - 1;
        int drawY = y + SLOT_SIZE - 5;
        // 原 runWithoutDepthTest + translate(z=300)：隔离绘制并抬高一层盖过物品
        // Former runWithoutDepthTest + translate(z=300): isolated drawing raised one layer above items.
        g.isolated(() -> {
            g.push();
            g.raise(1);
            int badgeX = Math.max(x, drawX - 1);
            KineticTheme.surface(
                    g,
                    badgeX,
                    drawY - 1,
                    x + SLOT_SIZE - badgeX,
                    y + SLOT_SIZE - drawY + 1,
                    KineticTheme.Surface.PANEL_ALT
            );
            g.translate(drawX, drawY);
            g.scale(0.5F, 0.5F);
            g.scrollingTextRight(badge, scaledWidth * 2, 0, scaledWidth * 2, 0xFF55FF55, false);
            g.pop();
        });
    }

    private String formatProbability(int weight, long totalWeight) {
        if (totalWeight <= 0) return "0";
        double value = weight * 100.0D / totalWeight;
        if (Math.abs(value - Math.rint(value)) < 0.05D) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private boolean isHoveringButton(KineticButton button, double mx, double my) {
        return button != null
                && isControlVisible(button)
                && button.contains(mx, my);
    }

    @Override
    protected void renderTooltips(int smx, int smy) {
        if (isHoveringButton(newButton, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.new.tooltip"));
            return;
        }
        if (isHoveringButton(doneButton, smx, smy)) {
            showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.done.tooltip"));
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
        if (mode == Mode.SELECT_TARGETS && activeTarget != null) {
            if ((weightBox != null && weightBox.contains(smx, smy))
                    || isHoveringButton(minusButton, smx, smy)
                    || isHoveringButton(plusButton, smx, smy)) {
                showTooltip(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight.tooltip"));
                return;
            }
        }
        if (mode == Mode.SELECT_TARGETS && selectedSource != null
                && smx >= leftX && smx < leftX + SLOT_SIZE
                && smy >= 32 && smy < 32 + SLOT_SIZE) {
            var sourceStack = WorldBlockConfig.parseItemStack(selectedSource);
            showTooltip(List.of(ItemCacheHudRenderer.getDisplayNameCustom(sourceStack), Component.literal(selectedSource)));
            return;
        }
        int leftRuleIndex = leftRuleAt(smx, smy);
        if (leftRuleIndex >= 0) {
            String source = visibleSources.get(leftRuleIndex);
            var sourceStack = WorldBlockConfig.parseItemStack(source);
            showTooltip(List.of(
                            ItemCacheHudRenderer.getDisplayNameCustom(sourceStack),
                            Component.literal(source),
                            KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.rule.tooltip")
                    ));
            return;
        }
        int index = rightItemAt(smx, smy);
        if (index >= 0) {
            KineticItemSearch.CachedItem item = visibleItems.get(index);
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(item.stack()));
            tooltip.add(Component.literal(item.id()));
            if (mode == Mode.SELECT_SOURCE) {
                tooltip.add(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.source.tooltip"));
            } else if (selectedTargets.containsKey(item.id())) {
                long total = selectedTotalWeight();
                tooltip.add(KineticI18n.translatable(
                        "gui.realmcontrol.worldblock.banitem.block.weighted.target.selected.tooltip",
                        selectedTargets.get(item.id()),
                        formatProbability(selectedTargets.get(item.id()), total)
                ));
                tooltip.add(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.target.remove.tooltip"));
            } else {
                tooltip.add(KineticI18n.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.target.add.tooltip"));
            }
            showTooltip(tooltip);
        }
    }

    @Override
    protected boolean onMouseClickCapture(MouseInput input) {
        // 原 canvasMouseClicked 全部在控件之前处理 / The old canvasMouseClicked handled all of this before controls.
        double mx = input.x();
        double my = input.y();
        if (leftScroll.beginDrag(
                mx,
                my,
                input.button(),
                leftX + leftW - PANEL_INSET - SCROLLBAR_WIDTH,
                leftContentY,
                SCROLLBAR_WIDTH,
                leftContentH,
                SCROLLBAR_MIN_THUMB,
                1
        )) return true;
        if (rightScroll.beginDrag(
                mx,
                my,
                input.button(),
                rightX + rightW - PANEL_INSET - SCROLLBAR_WIDTH,
                rightContentY,
                SCROLLBAR_WIDTH,
                rightContentH,
                SCROLLBAR_MIN_THUMB,
                1
        )) return true;

        if (mode == Mode.BROWSE || mode == Mode.SELECT_TARGETS) {
            int row = leftRuleAt(mx, my);
            if (row >= 0) {
                String source = visibleSources.get(row);
                if (input.isLeft()) {
                    switchRule(source);
                    return true;
                }
                if (input.isRight()) {
                    deleteRule(source);
                    return true;
                }
            }
        }

        int index = rightItemAt(mx, my);
        if (index >= 0) {
            String id = visibleItems.get(index).id();
            if (mode == Mode.SELECT_SOURCE && input.isLeft()) {
                selectSource(id);
                return true;
            }
            if (mode == Mode.SELECT_TARGETS) {
                if (input.isLeft()) {
                    selectTarget(id);
                    return true;
                }
                if (input.isRight() && selectedTargets.containsKey(id)) {
                    removeTarget(id);
                    return true;
                }
            }
        }
        return false;
    }

    private int leftRuleAt(double mx, double my) {
        if (mx < leftContentX
                || mx >= leftContentX + leftContentW
                || my < leftContentY
                || my >= leftContentY + leftContentH) {
            return -1;
        }
        int row = (int) Math.floor((my - leftContentY + (int) Math.round(leftScroll.smoothOffset())) / 25.0D);
        return row >= 0 && row < visibleSources.size() ? row : -1;
    }

    private int rightItemAt(double mx, double my) {
        if (mode == Mode.BROWSE
                || mx < rightContentX
                || mx >= rightContentX + rightContentW
                || my < rightContentY
                || my >= rightContentY + rightContentH) return -1;
        int localX = (int) Math.floor(mx - rightContentX);
        int localY = (int) Math.floor(my - rightContentY + (int) Math.round(rightScroll.smoothOffset()));
        if (localX < 0 || localY < 0) return -1;
        int col = localX / SLOT_PITCH;
        int row = localY / SLOT_PITCH;
        if (col < 0 || col >= cols || localX % SLOT_PITCH >= SLOT_SIZE || localY % SLOT_PITCH >= SLOT_SIZE) return -1;
        int index = row * cols + col;
        return index >= 0 && index < visibleItems.size() ? index : -1;
    }

    @Override
    protected boolean onMouseRelease(MouseInput input) {
        return leftScroll.release(input.button()) | rightScroll.release(input.button());
    }

    @Override
    protected boolean onMouseDrag(MouseDragInput input) {
        double my = input.y();
        if (leftScroll.drag(my, leftContentY, leftContentH, SCROLLBAR_MIN_THUMB)) return true;
        if (rightScroll.drag(my, rightContentY, rightContentH, SCROLLBAR_MIN_THUMB)) return true;
        return false;
    }

    @Override
    protected boolean onMouseScroll(ScrollInput input) {
        double mx = input.x();
        double my = input.y();
        double delta = input.deltaY();
        if (mx >= leftContentX
                && mx <= leftX + leftW - PANEL_INSET
                && my >= leftContentY
                && my <= leftContentY + leftContentH
                && leftScroll.scroll(delta, 25D)) return true;
        if (mx >= rightContentX
                && mx <= rightX + rightW - PANEL_INSET
                && my >= rightContentY
                && my <= rightContentY + rightContentH
                && rightScroll.scroll(delta, SLOT_PITCH)) return true;
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
