package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.client.text.KineticText;
import dev.xyat.kineticcore.api.client.input.KineticMouseButtons;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.xyat.kineticcore.api.client.theme.GuiTheme;
import dev.xyat.kineticcore.api.client.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.screen.KineticScreen;
import dev.xyat.kineticcore.api.client.search.KineticSearch;
import dev.xyat.kineticcore.api.client.widget.KineticWidgets;
import dev.xyat.kineticcore.api.client.widget.button.KineticButtons.StateButton;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.kineticcore.api.config.client.KTConfigApi;
import dev.xyat.kineticcore.api.client.widget.scroll.KineticScroll.SmoothEntry;
import dev.xyat.kineticcore.api.client.widget.scroll.KineticScroll.SmoothSelectionList;
import dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete;
import dev.xyat.kineticcore.api.client.widget.input.KineticAutoComplete.AutoCompleteBox;
import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfigGui;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.jetbrains.annotations.NotNull;
import dev.xyat.kineticcore.api.client.widget.KineticControl;

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

public class WorldGenScreen extends KineticScreen {
    private static final int STRUCTURE_ROW_HEIGHT = 30;
    private static final int STRUCTURE_ROW_GAP = 2;
    private static final Map<String, String> ZH_CN_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> ZH_CN_LOADED_NAMESPACES = ConcurrentHashMap.newKeySet();

    private final Screen parent;
    private boolean structureBlockingEnable;
    private final List<String> serverDictStructs;
    private final List<StructureRuleDescriptor> structureDescriptors;
    private final Map<String, StructureEntryRule> structureEntryRules = new LinkedHashMap<>();
    private final Map<String, StructurePlacementRule> structurePlacementRules = new LinkedHashMap<>();

    private double structureScrollAmount = 0.0D;
    private String structureSearch = "";
    private AutoCompleteBox activeInput;
    private StructureListWidget structureListWidget;
    private StateButton locateStructureButton;
    private StateButton teleportDimensionButton;
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

    public WorldGenScreen(WorldGenNetwork.OpenWorldGenGuiPacket packet) {
        this(packet, null);
    }

    public WorldGenScreen(WorldGenNetwork.OpenWorldGenGuiPacket packet, Screen parent) {
        super(Component.translatable("gui.realmcontrol.worldgen.worldgen.title"));
        this.parent = parent;
        setParentScreen(parent);
        useCanvas(STANDARD_CANVAS_WIDTH, STANDARD_CANVAS_HEIGHT, STANDARD_SAFE_MARGIN);
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
    protected void buildUi() {
        activeInput = null;
        structureListWidget = null;
        locateStructureButton = null;
        teleportDimensionButton = null;

        int panelW = this.canvasWidth() - 40;
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

        locateStructureButton = addButton(locateX, topY, actionW, Component.translatable("gui.realmcontrol.worldgen.worldgen.locate_structure"), Component.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.locate_structure"), () -> locateSelectedStructure());

        teleportDimensionButton = addButton(teleportX, topY, actionW, Component.translatable("gui.realmcontrol.worldgen.worldgen.teleport_dimension"), Component.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.teleport_dimension"), () -> teleportSelectedStructureDimension());

        addButton(saveX, topY, saveW, Component.translatable("gui.realmcontrol.worldgen.worldgen.save_all"), null, () -> WorldGenNetwork.CHANNEL.sendToServer(new WorldGenNetwork.SaveWorldGenPacket(
                structureBlockingEnable,
                new ArrayList<>(structureEntryRules.values()),
                new ArrayList<>(structurePlacementRules.values())
        )));

        addButton(backX, topY, backW, Component.translatable("gui.realmcontrol.worldgen.config.back"), null, () -> this.onClose());

        updateStructureActionButtons();

        int searchY = 46;
        int inputW = panelW - 180;
        activeInput = addAutoCompleteField(
                startX, searchY, inputW, Component.empty(), Component.translatable("gui.realmcontrol.worldgen.worldgen.hint_structure_rules"), this::getStructDict, null
        );
        activeInput.setValue(structureSearch);
        activeInput.setResponder(value -> {
            structureSearch = value == null ? "" : value;
            if (structureListWidget != null) {
                structureListWidget.refresh();
            }
        });

        addButtonWithHandler(startX + inputW + 5, searchY, 85, Component.translatable(
                "gui.realmcontrol.worldgen.worldgen.rules_btn",
                Component.translatable(structureBlockingEnable ? "gui.realmcontrol.worldgen.worldgen.enable" : "gui.realmcontrol.worldgen.worldgen.disable")
                        .withStyle(structureBlockingEnable ? ChatFormatting.GREEN : ChatFormatting.RED)
        ), Component.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.rules_btn"), b -> {
            structureBlockingEnable = !structureBlockingEnable;
            b.setText(Component.translatable(
                    "gui.realmcontrol.worldgen.worldgen.rules_btn",
                    Component.translatable(structureBlockingEnable ? "gui.realmcontrol.worldgen.worldgen.enable" : "gui.realmcontrol.worldgen.worldgen.disable")
                            .withStyle(structureBlockingEnable ? ChatFormatting.GREEN : ChatFormatting.RED)
            ));
        });

        addButton(startX + inputW + 95, searchY, 85, Component.translatable("gui.realmcontrol.worldgen.worldgen.refresh_structures"), Component.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.refresh_structures"), () -> WorldGenNetwork.requestStructureRegistryRefresh());

        int listY = 76;
        int listH = this.canvasHeight() - listY - 16;
        structureListWidget = new StructureListWidget(panelW, listH, listY, listY + listH, STRUCTURE_ROW_HEIGHT);
        structureListWidget.setLeftPos(startX);
        structureListWidget.setScrollAmount(structureScrollAmount);
        addSmoothSelectionList(structureListWidget);
        updateStructureActionButtons();
    }

    public void handleSaveResult(boolean success) {
        if (success) {
            commitDraft();
            KTConfigApi.notifySaved(WorldGenConfigGui.RULES_PAGE_ID);
        } else {
            KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.worldgen.save_invalid_toast"));
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
            String value = activeInput.getValue();
            activeInput.setValue("");
            activeInput.setValue(value);
        }
        updateStructureActionButtons();
        KineticOverlays.toast(Component.translatable("gui.realmcontrol.worldgen.worldgen.structure_refresh_toast"));
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
            KineticOverlays.toast(Component.translatable("msg.realmcontrol.worldgen.structure_action.no_selection"));
            return;
        }
        if (isStructureDisabled(descriptor)) {
            KineticOverlays.toast(Component.translatable("msg.realmcontrol.worldgen.structure_action.disabled"));
            return;
        }
        WorldGenNetwork.requestLocateStructure(descriptor.structureId());
    }

    private void teleportSelectedStructureDimension() {
        StructureRuleDescriptor descriptor = getSelectedStructureDescriptor();
        if (descriptor == null) {
            KineticOverlays.toast(Component.translatable("msg.realmcontrol.worldgen.structure_action.no_selection"));
            return;
        }
        if (isStructureDisabled(descriptor)) {
            KineticOverlays.toast(Component.translatable("msg.realmcontrol.worldgen.structure_action.disabled"));
            return;
        }
        WorldGenNetwork.requestTeleportStructureDimension(descriptor.structureId());
    }

    private Component getDimensionDisplay(List<String> dimensionIds) {
        if (dimensionIds == null || dimensionIds.isEmpty()) {
            return Component.translatable("gui.realmcontrol.worldgen.dimension.unknown");
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
            case "minecraft:overworld" -> Component.translatable("gui.realmcontrol.worldgen.dimension.minecraft.overworld");
            case "minecraft:the_nether" -> Component.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_nether");
            case "minecraft:the_end" -> Component.translatable("gui.realmcontrol.worldgen.dimension.minecraft.the_end");
            case "twilightforest:twilight_forest" -> Component.translatable("gui.realmcontrol.worldgen.dimension.twilightforest.twilight_forest");
            default -> Component.translatable("gui.realmcontrol.worldgen.dimension.modded", dimensionId);
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
            structureScrollAmount = structureListWidget.getScrollAmount();
        }
        KineticClientRuntime.openScreen(new StructureRuleEditScreen(this, descriptor));
    }

    private boolean isStructureModified(StructureRuleDescriptor descriptor) {
        StructureEntryRule entryRule = structureEntryRules.get(descriptor.structureId());
        StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : structurePlacementRules.get(descriptor.structureSetId());
        return entryRule != null && !entryRule.isEmpty() || placementRule != null && !placementRule.isEmpty();
    }

    private void refreshStructureRows() {
        if (structureListWidget != null) {
            double scroll = structureListWidget.getScrollAmount();
            structureListWidget.refresh();
            structureListWidget.setScrollAmount(scroll);
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

    private KineticAutoComplete.Suggestion toSuggestion(String id, String translationPrefix) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) {
            return new KineticAutoComplete.Suggestion(id, Component.empty());
        }

        List<String> translationKeys = new ArrayList<>();
        translationKeys.add(translationPrefix + "." + loc.getNamespace() + "." + loc.getPath());
        if ("structure".equals(translationPrefix)) {
            translationKeys.addAll(getStructureFallbackKeys(loc));
        }
        String translated = KineticSearch.resolveTranslation(translationKeys.toArray(String[]::new));
        return new KineticAutoComplete.Suggestion(
                id,
                translated == null ? Component.empty() : Component.literal(translated)
        );
    }

    private List<KineticAutoComplete.Suggestion> getStructDict() {
        return this.serverDictStructs.stream()
                .map(id -> toSuggestion(id, "structure"))
                .collect(Collectors.toList());
    }

    @Override
    protected void renderCanvasBackground(@NotNull GuiGraphics g, int mx, int my, float pt) {
        GuiTheme.shadow(g, this.canvasWidth(), this.canvasHeight());
        int panelW = this.canvasWidth() - 40;
        int startX = 20;
        int listY = 76;
        int listH = this.canvasHeight() - listY - 16;

        GuiTheme.panel(g, 10, 8, this.canvasWidth() - 20, this.canvasHeight() - 16);
        GuiTheme.separator(g, 16, 40, this.canvasWidth() - 32);
        GuiTheme.separator(g, 16, 71, this.canvasWidth() - 32);
        GuiTheme.panelAlt(g, startX - 2, listY - 2, panelW + 4, listH + 4);
        renderSmoothSelectionList(structureListWidget, g, mx, my, pt);
    }

    @Override
    protected void renderCanvasForeground(@NotNull GuiGraphics g, int mx, int my, float pt) {
        int panelW = this.canvasWidth() - 40;
        int gap = 5;
        int backW = 60;
        int saveW = 80;
        int actionW = 88;
        int backX = 20 + panelW - backW;
        int saveX = backX - gap - saveW;
        int teleportX = saveX - gap - actionW;
        int locateX = teleportX - gap - actionW;
        KineticText.drawScrollingLeft(
                g,
                this.font,
                Component.translatable("gui.realmcontrol.worldgen.worldgen.title"),
                20,
                22,
                Math.max(1, locateX - 26),
                0xFFFFFFFF,
                false
        );
    }

    class StructureListWidget extends SmoothSelectionList<StructureListWidget.Entry> {
        StructureListWidget(int w, int h, int t, int b, int ih) {
            super(w, h, t, b, ih);
            setRenderBackground(false);
            setRenderTopAndBottom(false);
            refresh();
        }

        void refresh() {
            clearEntries();
            String query = structureSearch == null ? "" : structureSearch.trim().toLowerCase(Locale.ROOT);
            List<StructureRuleDescriptor> visibleDescriptors = structureDescriptors.stream()
                    .filter(descriptor -> query.isEmpty()
                            || descriptor.structureId().toLowerCase(Locale.ROOT).contains(query)
                            || toDisplayEntry(descriptor.structureId()).toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator.comparing(WorldGenScreen.this::isStructureModified).reversed()
                            .thenComparing(StructureRuleDescriptor::structureId)
                            .thenComparing(StructureRuleDescriptor::structureSetId))
                    .toList();

            for (StructureRuleDescriptor descriptor : visibleDescriptors) {
                addEntry(new Entry(descriptor));
            }
            setSelected(null);
        }

        @Override
        public int getRowLeft() {
            return this.getLeft() + 2;
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        class Entry extends SmoothEntry<Entry> {
            private final StructureRuleDescriptor descriptor;
            private final StateButton editButton;

            Entry(StructureRuleDescriptor descriptor) {
                this.descriptor = descriptor;
                this.editButton = KineticWidgets.createCompactButton(0, 0, 54, Component.translatable("gui.realmcontrol.worldgen.worldgen.edit"), null, () -> openStructureEditor(descriptor));
                setControlEnabled(editButton, !"unassigned".equals(descriptor.placementType()));
            }

            @Override
            public void render(@NotNull GuiGraphics g, int index, int t, int l, int w, int h, int mx, int my, boolean hovered, float pt) {
                boolean modified = isStructureModified(descriptor);
                boolean disabled = isStructureDisabled(descriptor);
                boolean selected = descriptor.structureId().equals(selectedStructureId);
                int contentH = STRUCTURE_ROW_HEIGHT - STRUCTURE_ROW_GAP;
                GuiTheme.surface(
                        g,
                        l,
                        t,
                        w,
                        contentH,
                        index % 2 == 0 ? GuiTheme.Surface.PANEL : GuiTheme.Surface.PANEL_ALT
                );
                if (selected) {
                    GuiTheme.stateOutline(g, l, t, w, contentH, true, false, false);
                } else if (hovered) {
                    GuiTheme.stateOutline(g, l, t, w, contentH, false, true, false);
                } else if (disabled) {
                    GuiTheme.indicatorOutline(g, l, t, w, contentH, GuiTheme.Indicator.DANGER);
                } else if (modified) {
                    GuiTheme.indicatorOutline(g, l, t, w, contentH, GuiTheme.Indicator.SUCCESS);
                } else {
                    GuiTheme.indicatorOutline(g, l, t, w, contentH, GuiTheme.Indicator.MUTED);
                }

                int editX = l + w - editButton.getWidth() - 4;
                int editY = t + Math.max(0, (contentH - editButton.getHeight()) / 2);
                editButton.setX(editX);
                editButton.setY(editY);
                editButton.render(g, mx, my, pt);

                String display = toDisplayEntry(descriptor.structureId());
                int maxW = editX - l - 12;
                int lineHeight = font.lineHeight;
                int textGap = 1;
                int textBlockH = lineHeight * 2 + textGap;
                int firstLineY = t + (contentH - textBlockH) / 2;
                int secondLineY = firstLineY + lineHeight + textGap;
                KineticText.drawScrollingLeft(g, font, display, l + 6, firstLineY, maxW, 0xFFFFFFFF, false);

                StructureEntryRule entryRule = structureEntryRules.get(descriptor.structureId());
                StructurePlacementRule placementRule = descriptor.structureSetId().isBlank() ? null : structurePlacementRules.get(descriptor.structureSetId());
                int weight = entryRule != null && entryRule.weight() != null ? entryRule.weight() : descriptor.originalWeight();
                float frequency = placementRule != null && placementRule.frequency() != null ? placementRule.frequency() : descriptor.originalFrequency();

                MutableComponent state = disabled
                        ? Component.translatable("gui.realmcontrol.worldgen.worldgen.structure_state_disabled").withStyle(ChatFormatting.RED)
                        : Component.translatable("gui.realmcontrol.worldgen.worldgen.structure_state_enabled").withStyle(ChatFormatting.GREEN);
                Component freq = Component.literal(String.format(Locale.ROOT, "%.3f", frequency)).withStyle(ChatFormatting.AQUA);
                Component weightText = Component.literal(Integer.toString(weight)).withStyle(ChatFormatting.GOLD);
                Component type = Component.translatable("gui.realmcontrol.worldgen.worldgen.placement_type." + descriptor.placementType()).withStyle(ChatFormatting.WHITE);
                Component dimension = Component.translatable(
                        "gui.realmcontrol.worldgen.worldgen.structure_dimension",
                        getDimensionDisplay(descriptor.dimensionIds())
                );
                MutableComponent summary = Component.translatable("gui.realmcontrol.worldgen.worldgen.structure_summary", state, type, freq, weightText)
                        .append(Component.literal("  "))
                        .append(dimension);
                KineticText.drawScrollingLeft(
                        g,
                        font,
                        summary,
                        l + 6,
                        secondLineY,
                        maxW,
                        0xFFCCCCCC,
                        false
                );
            }

            @Override
            public boolean mouseClicked(double mx, double my, int btn) {
                if (!KineticMouseButtons.isPrimary(btn)) {
                    return false;
                }
                selectStructure(descriptor);
                StructureListWidget.this.setSelected(null);
                if (editButton.mouseClicked(mx, my, btn)) {
                    return true;
                }
                return true;
            }

            @Override
            public @NotNull Component getNarration() {
                return Component.translatable("gui.realmcontrol.worldgen.worldgen.edit");
            }
        }
    }

    private static boolean isControlVisible(KineticControl control) {
        return control != null && control.isVisible();
    }

    private static boolean isControlEnabled(KineticControl control) {
        return control != null && control.isEnabled();
    }

    private static void setControlVisible(KineticControl control, boolean visible) {
        if (control != null) control.setVisible(visible);
    }

    private static void setControlEnabled(KineticControl control, boolean enabled) {
        if (control != null) control.setEnabled(enabled);
    }

}
