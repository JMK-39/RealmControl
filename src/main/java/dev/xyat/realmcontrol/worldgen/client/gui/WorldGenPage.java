package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.text.KineticText;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.theme.KineticTheme;
import dev.xyat.kineticcore.api.client.gui.ui.KineticUi;
import dev.xyat.kineticcore.api.client.gui.widget.*;
import dev.xyat.kineticcore.api.client.gui.widget.list.*;
import dev.xyat.kineticcore.api.client.search.KineticSuggestion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.client.search.KineticSearch;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfigGui;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class WorldGenPage extends KineticPage {
    private static final int STRUCTURE_ROW_HEIGHT = 30;
    private static final int STRUCTURE_ROW_GAP = 2;
    private static final Map<String, String> ZH_CN_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> ZH_CN_LOADED_NAMESPACES = ConcurrentHashMap.newKeySet();

    private boolean structureBlockingEnable;
    private final List<String> serverDictStructs;
    private final List<StructureRuleDescriptor> structureDescriptors;
    private final Map<String, StructureEntryRule> structureEntryRules = new LinkedHashMap<>();
    private final Map<String, StructurePlacementRule> structurePlacementRules = new LinkedHashMap<>();

    private int structureScrollOffset = 0;
    private String structureSearch = "";
    private KineticAutoCompleteField activeInput;
    private StructureListWidget structureListWidget;
    private KineticButton locateStructureButton;
    private KineticButton teleportDimensionButton;
    private String selectedStructureId = "";

    private record WorldGenSnapshot(
            boolean structureBlockingEnable,
            Map<String, StructureEntryRule> entryRules,
            Map<String, StructurePlacementRule> placementRules
    ) {
    }

    private WorldGenSnapshot captureWorldGenSnapshot() {
        return new WorldGenSnapshot(
                structureBlockingEnable,
                new LinkedHashMap<>(structureEntryRules),
                new LinkedHashMap<>(structurePlacementRules)
        );
    }

    private void restoreWorldGenSnapshot(WorldGenSnapshot snapshot) {
        if (snapshot == null) return;
        structureBlockingEnable = snapshot.structureBlockingEnable();
        structureEntryRules.clear();
        structureEntryRules.putAll(snapshot.entryRules());
        structurePlacementRules.clear();
        structurePlacementRules.putAll(snapshot.placementRules());
    }

    public WorldGenPage(WorldGenNetwork.OpenWorldGenGuiPacket packet) {
        super(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.title"));
        useCanvas(CANVAS_WIDTH, CANVAS_HEIGHT, SAFE_MARGIN);
        this.structureBlockingEnable = packet.structureBlockingEnable();
        this.serverDictStructs = packet.allStructs() != null ? new ArrayList<>(packet.allStructs()) : new ArrayList<>();
        this.structureDescriptors = packet.structureDescriptors() != null ? new ArrayList<>(packet.structureDescriptors()) : new ArrayList<>();

        for (StructureRuleDescriptor descriptor : this.structureDescriptors) {
            if (descriptor.entryRule() != null && !descriptor.entryRule().isEmpty()) {
                this.structureEntryRules.put(descriptor.structureId(), descriptor.entryRule());
            }
            if (descriptor.placementRule() != null && !descriptor.placementRule().isEmpty() && !descriptor.structureSetId().isBlank()) {
                this.structurePlacementRules.put(descriptor.structureSetId(), descriptor.placementRule());
            }
        }
        configureStandaloneDraft(this::captureWorldGenSnapshot, this::restoreWorldGenSnapshot);
    }


    @Override
    protected void build(KineticUi ui) {
        activeInput = null;
        structureListWidget = null;
        locateStructureButton = null;
        teleportDimensionButton = null;

        int panelW = this.width() - 40;
        int startX = 20;
        int topY = 16;

        int gap = 5;
        int backW = 60;
        int saveW = 80;
        int actionW = 88;
        int backX = startX + panelW - backW;
        int saveX = backX - gap - saveW;
        int teleportX = saveX - gap - actionW;
        int locateX = teleportX - gap - actionW;

        locateStructureButton = ui().button(locateX, topY, actionW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.locate_structure")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.locate_structure")).onClick(() -> locateSelectedStructure()).build();

        teleportDimensionButton = ui().button(teleportX, topY, actionW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.teleport_dimension")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.teleport_dimension")).onClick(() -> teleportSelectedStructureDimension()).build();

        ui().button(saveX, topY, saveW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_all")).onClick(() -> WorldGenNetwork.CHANNEL.sendToServer(new WorldGenNetwork.SaveWorldGenPacket(
                structureBlockingEnable,
                new ArrayList<>(structureEntryRules.values()),
                new ArrayList<>(structurePlacementRules.values())
        ))).build();

        ui().button(backX, topY, backW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(() -> this.close()).build();

        updateStructureActionButtons();

        int searchY = 46;
        int inputW = panelW - 180;
        activeInput = ui().autoComplete(startX, searchY, inputW, this::getStructDict).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.hint_structure_rules")).build();
        activeInput.setTextValue(structureSearch);
        activeInput.setDefaultText(structureSearch);
        activeInput.onTextChange(value -> {
            structureSearch = value == null ? "" : value;
            if (structureListWidget != null) {
                structureListWidget.refresh();
            }
        });

        ui().button(startX + inputW + 5, searchY, 85).text(KineticI18n.translatable(
                "gui.realmcontrol.worldgen.worldgen.rules_btn",
                KineticI18n.translatable(structureBlockingEnable ? "gui.realmcontrol.worldgen.worldgen.enable" : "gui.realmcontrol.worldgen.worldgen.disable")
        )).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.rules_btn")).onClick(b -> {
            structureBlockingEnable = !structureBlockingEnable;
            b.setText(KineticI18n.translatable(
                    "gui.realmcontrol.worldgen.worldgen.rules_btn",
                    KineticI18n.translatable(structureBlockingEnable ? "gui.realmcontrol.worldgen.worldgen.enable" : "gui.realmcontrol.worldgen.worldgen.disable")
            ));
        }).build();

        ui().button(startX + inputW + 95, searchY, 85).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.refresh_structures")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.refresh_structures")).onClick(() -> WorldGenNetwork.requestStructureRegistryRefresh()).build();

        int listY = 76;
        int listH = this.height() - listY - 16;
        // 原列表首行位于顶部下方 4px（原版列表内边距）/ Old rows started 4 px below the list top (vanilla list padding).
        structureListWidget = ui.add(new StructureListWidget(startX, listY + 4, panelW, listH - 4));
        structureListWidget.setScrollOffset(structureScrollOffset);
        updateStructureActionButtons();
    }

    public void handleSaveResult(boolean success) {
        if (success) {
            commitDraft();
            KTConfigApi.notifySaved(WorldGenConfigGui.RULES_PAGE_ID);
        } else {
            KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_invalid_toast"));
        }
    }

    public void handleStructureRegistryRefresh(List<String> structures, List<StructureRuleDescriptor> descriptors) {
        this.serverDictStructs.clear();
        if (structures != null) {
            structures.stream().filter(id -> id != null && !id.isBlank()).distinct().sorted().forEach(this.serverDictStructs::add);
        }
        this.structureDescriptors.clear();
        if (descriptors != null) {
            this.structureDescriptors.addAll(descriptors);
        }
        if (!selectedStructureId.isBlank() && this.structureDescriptors.stream().noneMatch(descriptor -> descriptor.structureId().equals(selectedStructureId))) {
            selectedStructureId = "";
        }
        if (structureListWidget != null) {
            structureListWidget.refresh();
        }
        if (activeInput != null) {
            String value = activeInput.textValue();
            activeInput.setTextValue("");
            activeInput.setTextValue(value);
        }
        updateStructureActionButtons();
        KineticOverlays.toast(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_refresh_toast"));
    }

    private StructureRuleDescriptor getSelectedStructureDescriptor() {
        if (selectedStructureId == null || selectedStructureId.isBlank()) {
            return null;
        }
        return structureDescriptors.stream()
                .filter(descriptor -> descriptor.structureId().equals(selectedStructureId))
                .findFirst()
                .orElse(null);
    }

    private void selectStructure(StructureRuleDescriptor descriptor) {
        selectedStructureId = descriptor == null ? "" : descriptor.structureId();
        updateStructureActionButtons();
    }

    private boolean isStructureDisabled(StructureRuleDescriptor descriptor) {
        if (descriptor == null) {
            return false;
        }
        StructureEntryRule entryRule = structureEntryRules.get(descriptor.structureId());
        return entryRule != null && entryRule.disabled();
    }

    private void updateStructureActionButtons() {
        StructureRuleDescriptor descriptor = getSelectedStructureDescriptor();
        boolean active = descriptor != null && !isStructureDisabled(descriptor);
        if (locateStructureButton != null) {
            setControlEnabled(locateStructureButton, active);
        }
        if (teleportDimensionButton != null) {
            setControlEnabled(teleportDimensionButton, active);
        }
    }

    private void locateSelectedStructure() {
        StructureRuleDescriptor descriptor = getSelectedStructureDescriptor();
        if (descriptor == null) {
            KineticOverlays.toast(KineticI18n.translatable("msg.realmcontrol.worldgen.structure_action.no_selection"));
            return;
        }
        if (isStructureDisabled(descriptor)) {
            KineticOverlays.toast(KineticI18n.translatable("msg.realmcontrol.worldgen.structure_action.disabled"));
            return;
        }
        WorldGenNetwork.requestLocateStructure(descriptor.structureId());
    }

    private void teleportSelectedStructureDimension() {
        StructureRuleDescriptor descriptor = getSelectedStructureDescriptor();
        if (descriptor == null) {
            KineticOverlays.toast(KineticI18n.translatable("msg.realmcontrol.worldgen.structure_action.no_selection"));
            return;
        }
        if (isStructureDisabled(descriptor)) {
            KineticOverlays.toast(KineticI18n.translatable("msg.realmcontrol.worldgen.structure_action.disabled"));
            return;
        }
        WorldGenNetwork.requestTeleportStructureDimension(descriptor.structureId());
    }

    private Component getDimensionDisplay(List<String> dimensionIds) {
        if (dimensionIds == null || dimensionIds.isEmpty()) {
            return KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.unknown");
        }
        MutableComponent result = Component.empty();
        for (int i = 0; i < dimensionIds.size(); i++) {
            if (i > 0) {
                result.append(Component.literal(" / "));
            }
            result.append(getDimensionName(dimensionIds.get(i)));
        }
        return result;
    }

    private Component getDimensionName(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.minecraft.overworld");
            case "minecraft:the_nether" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_nether");
            case "minecraft:the_end" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_end");
            case "twilightforest:twilight_forest" -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.twilightforest.twilight_forest");
            default -> KineticI18n.translatable("gui.realmcontrol.worldgen.dimension.modded", dimensionId);
        };
    }

    StructureEntryRule getEntryRule(String structureId) {
        return structureEntryRules.get(structureId);
    }

    StructurePlacementRule getPlacementRule(String structureSetId) {
        return structurePlacementRules.get(structureSetId);
    }

    void saveLocalStructureRules(StructureRuleDescriptor descriptor, StructureEntryRule entryRule, StructurePlacementRule placementRule) {
        if (entryRule == null || entryRule.isEmpty()) {
            structureEntryRules.remove(descriptor.structureId());
        } else {
            structureEntryRules.put(descriptor.structureId(), entryRule);
        }

        if (!descriptor.structureSetId().isBlank()) {
            if (placementRule == null || placementRule.isEmpty()) {
                structurePlacementRules.remove(descriptor.structureSetId());
            } else {
                structurePlacementRules.put(descriptor.structureSetId(), placementRule);
            }
        }
        refreshStructureRows();
    }

    void resetStructureRule(StructureRuleDescriptor descriptor) {
        structureEntryRules.remove(descriptor.structureId());
        if (!descriptor.structureSetId().isBlank()) {
            structurePlacementRules.remove(descriptor.structureSetId());
        }
        refreshStructureRows();
    }

    private void openStructureEditor(StructureRuleDescriptor descriptor) {
        if (structureListWidget != null) {
            structureScrollOffset = structureListWidget.scrollOffset();
        }
        openChild(new StructureRuleEditPage(this, descriptor));
    }

    private boolean isStructureModified(StructureRuleDescriptor descriptor) {
        StructureEntryRule entryRule = structureEntryRules.get(descriptor.structureId());
        StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : structurePlacementRules.get(descriptor.structureSetId());
        return entryRule != null && !entryRule.isEmpty() || placementRule != null && !placementRule.isEmpty();
    }

    private void refreshStructureRows() {
        if (structureListWidget != null) {
            int scroll = structureListWidget.scrollOffset();
            structureListWidget.refresh();
            structureListWidget.setScrollOffset(scroll);
        }
        updateStructureActionButtons();
    }

    String toDisplayEntry(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) {
            return id;
        }
        String translated = getChineseTranslation(loc);
        if (translated.isEmpty()) {
            return id;
        }
        return id + " - " + translated;
    }

    private String getChineseTranslation(ResourceLocation loc) {
        String key = "structure" + "." + loc.getNamespace() + "." + loc.getPath();
        String selectedLanguage = safeClientTranslate(key);
        if (isValidChineseTranslation(key, selectedLanguage)) {
            return selectedLanguage;
        }

        String zhCn = readZhCnTranslation(loc.getNamespace(), key);
        if (isValidChineseTranslation(key, zhCn)) {
            return zhCn;
        }

        if ("structure".equals("structure")) {
            for (String fallbackKey : getStructureFallbackKeys(loc)) {
                selectedLanguage = safeClientTranslate(fallbackKey);
                if (isValidChineseTranslation(fallbackKey, selectedLanguage)) {
                    return selectedLanguage;
                }
                zhCn = readZhCnTranslation(loc.getNamespace(), fallbackKey);
                if (isValidChineseTranslation(fallbackKey, zhCn)) {
                    return zhCn;
                }
            }
        }
        return "";
    }

    private String safeClientTranslate(String key) {
        try {
            return I18n.get(key);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readZhCnTranslation(String namespace, String key) {
        loadZhCnNamespace(namespace);
        return ZH_CN_CACHE.getOrDefault(key, "");
    }

    private void loadZhCnNamespace(String namespace) {
        if (namespace == null || namespace.isBlank() || !ZH_CN_LOADED_NAMESPACES.add(namespace)) {
            return;
        }
        try {
            ResourceLocation langFile = new ResourceLocation(namespace, "lang/zh_cn.json");
            KineticClientRuntime.resourceManager().getResource(langFile).ifPresent(this::loadZhCnResource);
        } catch (Exception ignored) {
        }
    }

    private void loadZhCnResource(Resource resource) {
        try (InputStream input = resource.open(); InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    continue;
                }
                String value = entry.getValue().getAsString();
                if (containsChinese(value)) {
                    ZH_CN_CACHE.put(entry.getKey(), value);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isValidChineseTranslation(String key, String value) {
        return value != null && !value.isBlank() && !value.equals(key) && !value.contains("%") && containsChinese(value);
    }

    private boolean containsChinese(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '一' && c <= '鿿') || (c >= '㐀' && c <= '䶿') || (c >= '豈' && c <= '﫿')) {
                return true;
            }
        }
        return false;
    }

    private List<String> getStructureFallbackKeys(ResourceLocation loc) {
        List<String> keys = new ArrayList<>();
        String namespace = loc.getNamespace();
        String path = loc.getPath();
        if (path.startsWith("village_")) {
            keys.add("structure." + namespace + ".village");
        }
        if (path.startsWith("ruined_portal_")) {
            keys.add("structure." + namespace + ".ruined_portal");
        }
        if (path.startsWith("ocean_ruin_")) {
            keys.add("structure." + namespace + ".ocean_ruin");
        }
        if (path.startsWith("mineshaft_")) {
            keys.add("structure." + namespace + ".mineshaft");
        }
        if (path.startsWith("shipwreck_")) {
            keys.add("structure." + namespace + ".shipwreck");
        }
        return keys;
    }

    private KineticSuggestion toSuggestion(String id, String translationPrefix) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) {
            return new KineticSuggestion(id, Component.empty());
        }

        List<String> translationKeys = new ArrayList<>();
        translationKeys.add(translationPrefix + "." + loc.getNamespace() + "." + loc.getPath());
        if ("structure".equals(translationPrefix)) {
            translationKeys.addAll(getStructureFallbackKeys(loc));
        }
        String translated = KineticSearch.resolveTranslation(translationKeys.toArray(String[]::new));
        return new KineticSuggestion(
                id,
                translated == null ? Component.empty() : Component.literal(translated)
        );
    }

    private List<KineticSuggestion> getStructDict() {
        return this.serverDictStructs.stream()
                .map(id -> toSuggestion(id, "structure"))
                .collect(Collectors.toList());
    }

    @Override
    protected void renderBackground(KineticGraphics g, int mx, int my, float pt) {
        KineticTheme.shadow(g, this.width(), this.height());
        int panelW = this.width() - 40;
        int startX = 20;
        int listY = 76;
        int listH = this.height() - listY - 16;

        KineticTheme.panel(g, 10, 8, this.width() - 20, this.height() - 16);
        KineticTheme.separator(g, 16, 40, this.width() - 32);
        KineticTheme.separator(g, 16, 71, this.width() - 32);
        KineticTheme.panelAlt(g, startX - 2, listY - 2, panelW + 4, listH + 4);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int panelW = this.width() - 40;
        int gap = 5;
        int backW = 60;
        int saveW = 80;
        int actionW = 88;
        int backX = 20 + panelW - backW;
        int saveX = backX - gap - saveW;
        int teleportX = saveX - gap - actionW;
        int locateX = teleportX - gap - actionW;
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.title"), 20, 22, Math.max(1, locateX - 26), 0xFFFFFFFF, false);
    }

    /**
     * 结构列表（原 SmoothSelectionList）：行内“编辑”按钮由 KineticTheme.button 绘制，点击在 onRowClick 中判定。
     * Structure list (formerly SmoothSelectionList): the inline edit button is painted with KineticTheme.button and
     * hit-tested in onRowClick.
     */
    class StructureListWidget extends KineticRowList<StructureRuleDescriptor> {
        private static final int EDIT_W = 54;
        private static final int EDIT_H = CONTROL_HEIGHT;

        StructureListWidget(int x, int y, int width, int height) {
            super(x, y, width, height, STRUCTURE_ROW_HEIGHT);
            refresh();
        }

        void refresh() {
            String query = structureSearch == null ? "" : structureSearch.trim().toLowerCase(Locale.ROOT);
            List<StructureRuleDescriptor> visibleDescriptors = structureDescriptors.stream()
                    .filter(descriptor -> query.isEmpty()
                            || descriptor.structureId().toLowerCase(Locale.ROOT).contains(query)
                            || toDisplayEntry(descriptor.structureId()).toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator.comparing(WorldGenPage.this::isStructureModified).reversed()
                            .thenComparing(StructureRuleDescriptor::structureId)
                            .thenComparing(StructureRuleDescriptor::structureSetId))
                    .toList();
            setItems(visibleDescriptors);
        }

        // 原行布局：左缩进 2，宽度 = 列表宽 - 12 / Former row layout: 2 px left inset, width = list width - 12.
        private int rowLeft() {
            return controlX() + 2;
        }

        private int rowWidth() {
            return controlWidth() - 12;
        }

        private int editX() {
            return rowLeft() + rowWidth() - EDIT_W - 4;
        }

        private int editY(int top) {
            return top + Math.max(0, (STRUCTURE_ROW_HEIGHT - STRUCTURE_ROW_GAP - EDIT_H) / 2);
        }

        private boolean editEnabled(StructureRuleDescriptor descriptor) {
            return !"unassigned".equals(descriptor.placementType());
        }

        @Override
        protected void renderRowBackground(KineticGraphics g, int index, int x, int y, int width, int height,
                                           boolean hovered, boolean selected) {
            // 背景在 renderRow 中按原样绘制 / The background is drawn in renderRow exactly as before.
        }

        @Override
        protected void renderRow(KineticGraphics g, StructureRuleDescriptor descriptor, int index, int x, int t, int rw, int rh,
                                 boolean hovered, boolean rowSelected) {
            int l = rowLeft();
            int w = rowWidth();
            boolean modified = isStructureModified(descriptor);
            boolean disabled = isStructureDisabled(descriptor);
            boolean selected = descriptor.structureId().equals(selectedStructureId);
            int contentH = STRUCTURE_ROW_HEIGHT - STRUCTURE_ROW_GAP;
            KineticTheme.surface(
                    g,
                    l,
                    t,
                    w,
                    contentH,
                    index % 2 == 0 ? KineticTheme.Surface.PANEL : KineticTheme.Surface.PANEL_ALT
            );
            if (selected) {
                KineticTheme.stateOutline(g, l, t, w, contentH, true, false, false);
            } else if (hovered) {
                KineticTheme.stateOutline(g, l, t, w, contentH, false, true, false);
            } else if (disabled) {
                KineticTheme.indicatorOutline(g, l, t, w, contentH, KineticTheme.Indicator.DANGER);
            } else if (modified) {
                KineticTheme.indicatorOutline(g, l, t, w, contentH, KineticTheme.Indicator.SUCCESS);
            } else {
                KineticTheme.indicatorOutline(g, l, t, w, contentH, KineticTheme.Indicator.MUTED);
            }

            int editX = editX();
            int editY = editY(t);
            boolean editActive = editEnabled(descriptor);
            boolean editHovered = editActive && mouseX() >= editX && mouseX() < editX + EDIT_W
                    && mouseY() >= editY && mouseY() < editY + EDIT_H;
            KineticTheme.button(g, editX, editY, EDIT_W, EDIT_H,
                    KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.edit"), editHovered, editActive, false);

            String display = toDisplayEntry(descriptor.structureId());
            int maxW = editX - l - 12;
            int lineHeight = g.lineHeight();
            int textGap = 1;
            int textBlockH = lineHeight * 2 + textGap;
            int firstLineY = t + (contentH - textBlockH) / 2;
            int secondLineY = firstLineY + lineHeight + textGap;
            g.scrollingText(Component.literal(display), l + 6, firstLineY, maxW, 0xFFFFFFFF, false);

            StructureEntryRule entryRule = structureEntryRules.get(descriptor.structureId());
            StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : structurePlacementRules.get(descriptor.structureSetId());
            int weight = entryRule != null && entryRule.weight() != null ? entryRule.weight() : descriptor.originalWeight();
            float frequency = placementRule != null && placementRule.frequency() != null ? placementRule.frequency() : descriptor.originalFrequency();

            MutableComponent state = disabled
                    ? KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_state_disabled")
                    : KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_state_enabled");
            Component freq = Component.literal(String.format(Locale.ROOT, "%.3f", frequency));
            Component weightText = Component.literal(Integer.toString(weight));
            Component type = KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.placement_type." + descriptor.placementType());
            Component dimension = KineticI18n.translatable(
                    "gui.realmcontrol.worldgen.worldgen.structure_dimension",
                    getDimensionDisplay(descriptor.dimensionIds())
            );
            MutableComponent summary = KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_summary", state, type, freq, weightText)
                    .append(Component.literal("  "))
                    .append(dimension);
            g.scrollingText(summary, l + 6, secondLineY, maxW, 0xFFCCCCCC, false);
        }

        @Override
        protected boolean onRowClick(StructureRuleDescriptor descriptor, int index, MouseInput input) {
            // 原行为：左键选中结构；命中可用的编辑按钮时再打开编辑器
            // Former behaviour: left click selects the structure; hitting the enabled edit button also opens the editor.
            if (!input.isLeft()) {
                return false;
            }
            selectStructure(descriptor);
            int top = rowTop(index);
            if (editEnabled(descriptor) && input.inside(editX(), editY(top), EDIT_W, EDIT_H)) {
                playClickSound(); // 与原行内按钮一致 / Same click sound as the former inline button.
                openStructureEditor(descriptor);
            }
            return true;
        }
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
