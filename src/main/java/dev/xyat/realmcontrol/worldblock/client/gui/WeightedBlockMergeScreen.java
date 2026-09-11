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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
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

public final class WeightedBlockMergeScreen extends KineticScreen {
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

    private final Screen parent;
    private final Map<String, List<WorldBlockConfig.WeightedBlockTarget>> rules;
    private final Map<String, Integer> replacementChances = new LinkedHashMap<>();
    private final List<ItemSearchIndex.CachedItem> allItems;
    private final GridScrollController leftScroll = new GridScrollController();
    private final GridScrollController rightScroll = new GridScrollController();
    private final LinkedHashMap<String, Integer> selectedTargets = new LinkedHashMap<>();
    private final List<String> visibleSources = new ArrayList<>();
    private List<ItemSearchIndex.CachedItem> visibleItems = new ArrayList<>();

    private Mode mode = Mode.BROWSE;
    private String selectedSource;
    private String activeTarget;
    private EditBox leftSearch;
    private EditBox rightSearch;
    private EditBox weightBox;
    private EditBox chanceBox;
    private Button newButton;
    private Button doneButton;
    private Button minusButton;
    private Button plusButton;
    private Button saveButton;
    private Button backButton;
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

    private record WeightedSnapshot(
            Map<String, List<WorldBlockConfig.WeightedBlockTarget>> rules,
            Map<String, Integer> replacementChances,
            LinkedHashMap<String, Integer> selectedTargets,
            Mode mode,
            String selectedSource,
            String activeTarget,
            boolean weightInputValid
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
                weightInputValid
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
    }

    public WeightedBlockMergeScreen(Screen parent) {
        super(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.title"));
        this.parent = parent;
        this.rules = WorldBlockConfig.copyWeightedBlockReplacements();
        if (WorldBlockConfig.data != null && WorldBlockConfig.data.weightedBlockReplacementChances != null) {
            replacementChances.putAll(WorldBlockConfig.data.weightedBlockReplacementChances);
        }
        this.allItems = ItemSearchCache.getAllItems();
        useCanvas(640f, 360f, 6);
        configureStandaloneDraft(this::captureWeightedSnapshot, this::restoreWeightedSnapshot);
    }

    @Override
    protected void buildUi() {
        int pad = 8;
        int gap = 6;
        leftW = Math.max(150, Math.min(190, canvasWidth / 3));
        leftX = pad;
        rightX = leftX + leftW + gap;
        rightW = Math.max(180, canvasWidth - rightX - pad);
        leftY = 58;
        rightY = 58;
        leftH = Math.max(100, canvasHeight - leftY - 10);
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

        leftSearch = new EditBox(font, leftX, 5, leftW, 20, Component.empty());
        leftSearch.setResponder(ignored -> refreshLeft());
        addRenderableWidget(leftSearch);

        int buttonWidth = 62;
        int closeX = rightX + rightW - buttonWidth;
        int saveX = closeX - gap - buttonWidth;
        int doneX = saveX - gap - buttonWidth;
        int newX = doneX - gap - buttonWidth;
        int searchWidth = Math.max(80, newX - rightX - gap);

        rightSearch = new EditBox(font, rightX, 5, searchWidth, 20, Component.empty());
        rightSearch.setResponder(ignored -> refreshRight());
        addRenderableWidget(rightSearch);

        newButton = Button.builder(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.new"), ignored -> startNewRule())
                .bounds(newX, 5, buttonWidth, 20).build();
        addRenderableWidget(newButton);

        doneButton = Button.builder(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.done"), ignored -> finishTargets())
                .bounds(doneX, 5, buttonWidth, 20).build();
        addRenderableWidget(doneButton);

        saveButton = Button.builder(Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.save"), ignored -> save())
                .bounds(saveX, 5, buttonWidth, 20).build();
        addRenderableWidget(saveButton);

        backButton = Button.builder(Component.translatable("gui.realmcontrol.worldblock.config.back"), ignored -> onClose())
                .bounds(closeX, 5, buttonWidth, 20).build();
        addRenderableWidget(backButton);

        Component weightLabel = Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight");
        int weightBoxX = rightX + font.width(weightLabel) + 6;
        weightBox = new EditBox(font, weightBoxX, 31, 72, 20, Component.empty());
        weightBox.setMaxLength(7);
        weightBox.setFilter(value -> value.isEmpty() || value.matches("\\d{1,7}"));
        weightBox.setResponder(this::updateActiveWeightFromText);
        addRenderableWidget(weightBox);

        int minusX = weightBoxX + weightBox.getWidth() + 4;
        minusButton = Button.builder(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight.minus"), ignored -> adjustWeight(-10))
                .bounds(minusX, 31, 42, 20).build();
        addRenderableWidget(minusButton);
        plusButton = Button.builder(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight.plus"), ignored -> adjustWeight(10))
                .bounds(minusX + 46, 31, 42, 20).build();
        addRenderableWidget(plusButton);

        int chanceBoxX = rightX + rightW - 48;
        chanceBox = new EditBox(font, chanceBoxX, 31, 44, 20, Component.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance"));
        chanceBox.setMaxLength(3);
        chanceBox.setFilter(value -> value.isEmpty() || value.matches("\\d{1,3}"));
        chanceBox.setResponder(this::updateReplacementChance);
        addRenderableWidget(chanceBox);

        refreshLeft();
        refreshRight();
        updateControls();
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
        String query = leftSearch == null ? "" : leftSearch.getValue().trim().toLowerCase(Locale.ROOT);
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

        String query = rightSearch.getValue().trim().toLowerCase(Locale.ROOT);
        Set<String> simpleSources = simpleSourceIds();
        Set<String> weightedTargets = weightedTargetIds();
        int hash = 31 * ItemSearchCache.getAllItemsHash()
                + rules.keySet().hashCode()
                + simpleSources.hashCode()
                + weightedTargets.hashCode()
                + mode.ordinal();
        visibleItems = new ArrayList<>(ItemSearchCache.searchItems("weighted_block_replace", allItems, query, item -> {
            if (item == null || item.stack == null || item.stack.isEmpty()) return false;
            if (!(item.stack.getItem() instanceof BlockItem)) return false;
            String id = item.idStr;
            if (mode == Mode.SELECT_SOURCE) {
                return !rules.containsKey(id)
                        && !weightedTargets.contains(id)
                        && !simpleSources.contains(id)
                        && !WorldBlockConfig.isOreGenerationBanned(item.stack);
            }
            if (selectedSource == null || selectedSource.equals(id)) return false;
            if (rules.containsKey(id) || simpleSources.contains(id)) return false;
            return !WorldBlockConfig.isOreGenerationBanned(item.stack);
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
                GuiOverlay.toast(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.too_many_targets"));
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
        String value = String.valueOf(replacementChances.getOrDefault(selectedSource, 100));
        if (!value.equals(chanceBox.getValue())) chanceBox.setValue(value);
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
        weightBox.setValue(activeTarget == null ? "" : String.valueOf(selectedTargets.getOrDefault(activeTarget, 100)));
    }

    private void updateControls() {
        if (newButton != null) {
            newButton.active = mode == Mode.BROWSE;
        }
        if (doneButton != null) {
            doneButton.visible = mode == Mode.SELECT_TARGETS;
            doneButton.active = doneButton.visible && currentRuleValid();
        }
        if (saveButton != null) {
            saveButton.active = mode == Mode.BROWSE;
        }
        boolean editWeight = mode == Mode.SELECT_TARGETS && activeTarget != null;
        boolean editChance = mode == Mode.SELECT_TARGETS && selectedSource != null;
        if (chanceBox != null) {
            chanceBox.visible = editChance;
            chanceBox.active = editChance;
        }
        if (weightBox != null) {
            weightBox.visible = editWeight;
            weightBox.active = editWeight;
        }
        if (minusButton != null) {
            minusButton.visible = editWeight;
            minusButton.active = editWeight;
        }
        if (plusButton != null) {
            plusButton.visible = editWeight;
            plusButton.active = editWeight;
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
        dev.xyat.realmcontrol.worldblock.client.WorldBlockClientProxy.beginServerSave("gui.realmcontrol.worldblock.banitem.block.weighted.save_success");
        WorldBlockNetwork.CHANNEL.sendToServer(new WorldBlockNetwork.SaveWorldBlockConfigPacket(WorldBlockConfig.GSON.toJson(data)));
        commitDraft();
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    protected void renderCanvasBackground(@NotNull GuiGraphics g, int smx, int smy, float pt) {
        g.fillGradient(0, 0, canvasWidth, canvasHeight, 0xFF222222, 0xFF111111);
        GuiTheme.panel(g, leftX, leftY, leftW, leftH, 0xFF1C1C1C, 0xFF555555);
        GuiTheme.panel(g, rightX, rightY, rightW, rightH, 0xFF1C1C1C, 0xFF555555);
    }

    @Override
    protected void renderCanvasForeground(@NotNull GuiGraphics g, int smx, int smy, float pt) {
        renderLeft(g, smx, smy);
        renderRight(g, smx, smy);
        renderHeader(g);
        renderHints(g);
    }

    private void renderHeader(GuiGraphics g) {
        int statusX = leftX;
        Component status = switch (mode) {
            case BROWSE -> Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.mode.browse");
            case SELECT_SOURCE -> Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.mode.source");
            case SELECT_TARGETS -> Component.translatable(
                    "gui.realmcontrol.worldblock.banitem.block.weighted.mode.targets",
                    Component.literal(String.valueOf(selectedTargets.size()))
            );
        };
        if (mode == Mode.SELECT_TARGETS && selectedSource != null) {
            var sourceStack = WorldBlockConfig.parseItemStack(selectedSource);
            GuiTheme.itemSlot(g, sourceStack, leftX, 32, SLOT_SIZE, 4, false);
            ItemBanControl.withSkip(() -> {
                GuiTheme.item(g, font, sourceStack, leftX, 32, SLOT_SIZE, 1.0F, false);
                return null;
            });
            statusX += SLOT_SIZE + 4;
        }
        int statusRight = canvasWidth - 8;
        int statusWidth = mode == Mode.SELECT_TARGETS && activeTarget != null
                ? Math.max(1, leftW - (statusX - leftX))
                : Math.max(1, statusRight - statusX);
        var statusLines = font.split(status, statusWidth);
        if (!statusLines.isEmpty()) {
            g.drawString(font, statusLines.get(0), statusX, 36, 0xFFFFFFFF, false);
        }
        g.drawString(
                font,
                Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.rule_hint"),
                leftX,
                48,
                0xFFFFFFFF,
                false
        );
        if (mode == Mode.SELECT_TARGETS && activeTarget != null) {
            g.drawString(font, Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight"), rightX, 37, 0xFFFFFFFF, false);
        }
        if (chanceBox != null && chanceBox.visible) {
            Component chanceLabel = Component.translatable("gui.realmcontrol.worldblock.banitem.block.replace_chance");
            g.drawString(font, chanceLabel, chanceBox.getX() - font.width(chanceLabel) - 3, 37, 0xFFFFFFFF, false);
        }
    }

    private void renderLeft(GuiGraphics g, int mx, int my) {
        enableCanvasScissor(
                g,
                leftContentX,
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
                int rowColor = hovered ? 0xFF30303A : 0xFF1A1A22;
                GuiTheme.panel(g, leftContentX, rowY, leftContentW, 24, rowColor, 0xFF555555);
                var stack = WorldBlockConfig.parseItemStack(source);
                GuiTheme.itemSlot(g, stack, leftContentX + 2, rowY + 2, 20, 4, hovered);
                RenderSystem.enableDepthTest();
                ItemBanControl.withSkip(() -> {
                    g.renderItem(stack, leftContentX + 4, rowY + 4);
                    return null;
                });
                RenderSystem.disableDepthTest();
                String name = ItemCacheHudRenderer.getDisplayNameCustom(stack).getString();
                int countWidth = 18;
                int nameWidth = Math.max(8, leftContentW - 30 - countWidth);
                g.drawString(
                        font,
                        font.plainSubstrByWidth(name, nameWidth),
                        leftContentX + 26,
                        rowY + 8,
                        0xFFFFFFFF,
                        false
                );
                int count = rules.getOrDefault(source, List.of()).size();
                Component countText = Component.literal(String.valueOf(count));
                g.drawString(
                        font,
                        countText,
                        leftContentX + leftContentW - 2 - font.width(countText),
                        rowY + 8,
                        0xFFFFFFFF,
                        false
                );
            }
            y += 25;
        }
        g.disableScissor();
        GuiTheme.scrollbar(
                leftScroll,
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

    private void renderRight(GuiGraphics g, int mx, int my) {
        if (mode == Mode.BROWSE) return;
        enableCanvasScissor(
                g,
                rightContentX,
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
            ItemSearchIndex.CachedItem item = visibleItems.get(i);
            boolean hovered = mx >= x && mx < x + SLOT_SIZE && my >= y && my < y + SLOT_SIZE;
            boolean selected = selectedTargets.containsKey(item.idStr);
            GuiTheme.itemSlot(g, item.stack, x, y, SLOT_SIZE, 4, false);
            ItemBanControl.withSkip(() -> {
                GuiTheme.item(g, font, item.stack, x, y, SLOT_SIZE, 1.0F, false);
                return null;
            });
            if (hovered) {
                g.renderOutline(x, y, SLOT_SIZE, SLOT_SIZE, HOVER_OUTLINE_COLOR);
            } else if (selected) {
                g.renderOutline(x, y, SLOT_SIZE, SLOT_SIZE, SELECTED_OUTLINE_COLOR);
            }
            if (selected) {
                String probability = formatProbability(selectedTargets.get(item.idStr), totalWeight);
                Component badge = Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.probability_badge", probability);
                renderProbabilityBadge(g, x, y, badge);
            }
        }
        g.disableScissor();
        GuiTheme.scrollbar(
                rightScroll,
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

    private void renderProbabilityBadge(GuiGraphics g, int x, int y, Component badge) {
        int scaledWidth = Math.max(1, (int) Math.ceil(font.width(badge) * 0.5F));
        int drawX = x + SLOT_SIZE - scaledWidth - 1;
        int drawY = y + SLOT_SIZE - 5;
        RenderSystem.disableDepthTest();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        g.fill(Math.max(x, drawX - 1), drawY - 1, x + SLOT_SIZE, y + SLOT_SIZE, 0xA0000000);
        g.pose().translate(drawX, drawY, 0.0F);
        g.pose().scale(0.5F, 0.5F, 1.0F);
        g.drawString(font, badge, 0, 0, 0xFF55FF55, false);
        g.pose().popPose();
        RenderSystem.enableDepthTest();
    }

    private String formatProbability(int weight, long totalWeight) {
        if (totalWeight <= 0) return "0";
        double value = weight * 100.0D / totalWeight;
        if (Math.abs(value - Math.rint(value)) < 0.05D) return String.format(Locale.ROOT, "%.0f", value);
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void renderHints(GuiGraphics g) {
        if (leftSearch != null && leftSearch.getValue().isEmpty() && !leftSearch.isFocused()) {
            g.drawString(font, Component.translatable("gui.realmcontrol.worldblock.banitem.search.hint"), leftSearch.getX() + 5, leftSearch.getY() + 6, 0xFFAAAAAA, false);
        }
        if (rightSearch != null && rightSearch.getValue().isEmpty() && !rightSearch.isFocused()) {
            g.drawString(font, Component.translatable("gui.realmcontrol.worldblock.banitem.block.merge.search"), rightSearch.getX() + 5, rightSearch.getY() + 6, 0xFFAAAAAA, false);
        }
    }

    private boolean isHoveringButton(Button button, double mx, double my) {
        return button != null
                && button.visible
                && mx >= button.getX()
                && mx < button.getX() + button.getWidth()
                && my >= button.getY()
                && my < button.getY() + button.getHeight();
    }

    @Override
    protected void renderTooltips(@NotNull GuiGraphics g, int smx, int smy, int mx, int my) {
        if (isHoveringButton(newButton, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.new.tooltip"), mx, my);
            return;
        }
        if (isHoveringButton(doneButton, smx, smy)) {
            GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.done.tooltip"), mx, my);
            return;
        }
        if (mode == Mode.SELECT_TARGETS && activeTarget != null) {
            if ((weightBox != null && weightBox.isMouseOver(smx, smy))
                    || isHoveringButton(minusButton, smx, smy)
                    || isHoveringButton(plusButton, smx, smy)) {
                GuiOverlay.requestTooltip(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.weight.tooltip"), mx, my);
                return;
            }
        }
        if (mode == Mode.SELECT_TARGETS && selectedSource != null
                && smx >= leftX && smx < leftX + SLOT_SIZE
                && smy >= 32 && smy < 32 + SLOT_SIZE) {
            var sourceStack = WorldBlockConfig.parseItemStack(selectedSource);
            GuiOverlay.requestTooltip(List.of(ItemCacheHudRenderer.getDisplayNameCustom(sourceStack), Component.literal(selectedSource)), mx, my);
            return;
        }
        int leftRuleIndex = leftRuleAt(smx, smy);
        if (leftRuleIndex >= 0) {
            String source = visibleSources.get(leftRuleIndex);
            var sourceStack = WorldBlockConfig.parseItemStack(source);
            GuiOverlay.requestTooltip(List.of(
                            ItemCacheHudRenderer.getDisplayNameCustom(sourceStack),
                            Component.literal(source),
                            Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.rule.tooltip")
                    ), mx, my);
            return;
        }
        int index = rightItemAt(smx, smy);
        if (index >= 0) {
            ItemSearchIndex.CachedItem item = visibleItems.get(index);
            List<Component> tooltip = new ArrayList<>();
            tooltip.add(ItemCacheHudRenderer.getDisplayNameCustom(item.stack));
            tooltip.add(Component.literal(item.idStr));
            if (mode == Mode.SELECT_SOURCE) {
                tooltip.add(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.source.tooltip"));
            } else if (selectedTargets.containsKey(item.idStr)) {
                long total = selectedTotalWeight();
                tooltip.add(Component.translatable(
                        "gui.realmcontrol.worldblock.banitem.block.weighted.target.selected.tooltip",
                        selectedTargets.get(item.idStr),
                        formatProbability(selectedTargets.get(item.idStr), total)
                ));
                tooltip.add(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.target.remove.tooltip"));
            } else {
                tooltip.add(Component.translatable("gui.realmcontrol.worldblock.banitem.block.weighted.target.add.tooltip"));
            }
            GuiOverlay.requestTooltip(tooltip, mx, my);
        }
    }

    @Override
    protected boolean canvasMouseClicked(double mx, double my, int button) {
        if (button == 0 && leftScroll.beginDrag(
                mx,
                my,
                leftX + leftW - PANEL_INSET - SCROLLBAR_WIDTH,
                leftContentY,
                SCROLLBAR_WIDTH,
                leftContentH,
                SCROLLBAR_MIN_THUMB,
                1
        )) return true;
        if (button == 0 && rightScroll.beginDrag(
                mx,
                my,
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
                if (button == 0) {
                    switchRule(source);
                    return true;
                }
                if (button == 1) {
                    deleteRule(source);
                    return true;
                }
            }
        }

        int index = rightItemAt(mx, my);
        if (index >= 0) {
            String id = visibleItems.get(index).idStr;
            if (mode == Mode.SELECT_SOURCE && button == 0) {
                selectSource(id);
                return true;
            }
            if (mode == Mode.SELECT_TARGETS) {
                if (button == 0) {
                    selectTarget(id);
                    return true;
                }
                if (button == 1 && selectedTargets.containsKey(id)) {
                    removeTarget(id);
                    return true;
                }
            }
        }
        return super.canvasMouseClicked(mx, my, button);
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
    protected boolean canvasMouseReleased(double mx, double my, int button) {
        return leftScroll.release(button) | rightScroll.release(button) || super.canvasMouseReleased(mx, my, button);
    }

    @Override
    protected boolean canvasMouseDragged(double mx, double my, int button, double dx, double dy) {
        if (leftScroll.drag(my, leftContentY, leftContentH, SCROLLBAR_MIN_THUMB)) return true;
        if (rightScroll.drag(my, rightContentY, rightContentH, SCROLLBAR_MIN_THUMB)) return true;
        return super.canvasMouseDragged(mx, my, button, dx, dy);
    }

    @Override
    protected boolean canvasMouseScrolled(double mx, double my, double delta) {
        if (mx >= leftContentX
                && mx <= leftX + leftW - PANEL_INSET
                && my >= leftContentY
                && my <= leftContentY + leftContentH
                && leftScroll.scroll(delta, 25 / 3.0D)) return true;
        if (mx >= rightContentX
                && mx <= rightX + rightW - PANEL_INSET
                && my >= rightContentY
                && my <= rightContentY + rightContentH
                && rightScroll.scroll(delta, SLOT_PITCH / 3.0D)) return true;
        return super.canvasMouseScrolled(mx, my, delta);
    }
}
