package dev.xyat.realmcontrol.worldgen.client.gui;

import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.kineticcore.api.client.gui.overlay.KineticOverlays;
import dev.xyat.kineticcore.api.client.gui.input.MouseInput;
import dev.xyat.kineticcore.api.client.gui.page.KineticPage;
import dev.xyat.kineticcore.api.client.gui.render.KineticGraphics;
import dev.xyat.kineticcore.api.client.gui.render.KineticTexture;
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
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import dev.xyat.realmcontrol.worldgen.config.StructureEntryRule;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfigGui;
import dev.xyat.realmcontrol.worldgen.config.StructurePlacementRule;
import dev.xyat.realmcontrol.worldgen.data.StructureRuleDescriptor;
import dev.xyat.realmcontrol.worldgen.network.WorldGenNetwork;
import dev.xyat.realmcontrol.worldgen.preview.client.StructurePreviewClient;
import dev.xyat.realmcontrol.worldgen.preview.client.StructureThumbnailClient;
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
    private static final int STRUCTURE_ROW_HEIGHT = 34;
    private static final int STRUCTURE_ROW_GAP = 2;
    private static final int THUMBNAIL_SIZE = 28;
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
    private KineticButton editRulesButton;
    private KineticButton variantButton;
    private KineticStructureView previewView;
    private String selectedStructureId = "";
    private int previewVariant;
    private StructurePreviewClient.Result previewResult;
    private long lastRowClick;
    private int lastRowIndex = -1;

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


    private static final int ACTION_W = 72, LIST_Y = 46;
    // The list takes the left part; the preview of the selected structure and its facts fill the right column.
    private static final int LIST_W = 380, INFO_LINES = 3, INFO_LINE_H = 12;

    @Override
    protected void build(KineticUi ui) {
        activeInput = null;
        structureListWidget = null;
        locateStructureButton = null;
        teleportDimensionButton = null;
        editRulesButton = null;
        variantButton = null;
        previewView = null;

        int panelW = this.width() - 8;
        int startX = 4;
        int topY = 4;

        // Header: Back, the title, Locate and Teleport, and Save at the top right; each button only as wide as its label.
        int gap = 4;
        int saveW = 40;
        int actionW = ACTION_W;
        int backX = startX;
        int saveX = startX + panelW - saveW;
        int teleportX = saveX - gap - actionW;
        int locateX = teleportX - gap - actionW;
        int editX = locateX - gap - actionW;

        editRulesButton = ui().button(editX, topY, actionW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.edit_rules")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.edit_rules")).onClick(() -> {
            StructureRuleDescriptor descriptor = getSelectedStructureDescriptor();
            if (descriptor != null && !"unassigned".equals(descriptor.placementType())) openStructureEditor(descriptor);
        }).build();

        locateStructureButton = ui().button(locateX, topY, actionW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.locate_structure")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.locate_structure")).onClick(() -> locateSelectedStructure()).build();

        teleportDimensionButton = ui().button(teleportX, topY, actionW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.teleport_dimension")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.teleport_dimension")).onClick(() -> teleportSelectedStructureDimension()).build();

        ui().button(saveX, topY, saveW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.save_all")).onClick(() -> WorldGenNetwork.CHANNEL.sendToServer(new WorldGenNetwork.SaveWorldGenPacket(
                structureBlockingEnable,
                new ArrayList<>(structureEntryRules.values()),
                new ArrayList<>(structurePlacementRules.values())
        ))).build();

        ui().button(backX, topY, 40).text(KineticI18n.translatable("gui.realmcontrol.worldgen.config.back")).onClick(() -> this.close()).build();

        updateStructureActionButtons();

        // The search row sits right under the header; the rule switch and Refresh fill its right end.
        int searchY = 28;
        int inputW = panelW - 81 - gap - 64 - gap;
        activeInput = ui().autoComplete(startX, searchY, inputW, this::getStructDict).placeholder(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.hint_structure_rules")).build();
        activeInput.setTextValue(structureSearch);
        activeInput.setDefaultText(structureSearch);
        activeInput.onTextChange(value -> {
            structureSearch = value == null ? "" : value;
            if (structureListWidget != null) {
                structureListWidget.refresh();
            }
        });

        ui().button(startX + inputW + gap, searchY, 81).text(KineticI18n.translatable(
                "gui.realmcontrol.worldgen.worldgen.rules_btn",
                KineticI18n.translatable(structureBlockingEnable ? "gui.realmcontrol.worldgen.worldgen.enable" : "gui.realmcontrol.worldgen.worldgen.disable")
        )).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.rules_btn")).onClick(b -> {
            structureBlockingEnable = !structureBlockingEnable;
            b.setText(KineticI18n.translatable(
                    "gui.realmcontrol.worldgen.worldgen.rules_btn",
                    KineticI18n.translatable(structureBlockingEnable ? "gui.realmcontrol.worldgen.worldgen.enable" : "gui.realmcontrol.worldgen.worldgen.disable")
            ));
        }).build();

        ui().button(startX + panelW - 64, searchY, 64).text(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.refresh_structures")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.tooltip.refresh_structures")).onClick(() -> WorldGenNetwork.requestStructureRegistryRefresh()).build();

        int listY = LIST_Y;
        int listH = this.height() - listY - 4;
        structureListWidget = ui.add(new StructureListWidget(startX, listY + 2, LIST_W, listH - 2));
        structureListWidget.setScrollOffset(structureScrollOffset);

        int previewX = previewX(), previewW = previewWidth();
        previewView = ui.add(new KineticStructureView(previewX, listY, previewW, previewHeight()));
        int variantW = 64;
        variantButton = ui().button(previewX + previewW - variantW, infoY(), variantW).text(KineticI18n.translatable("gui.realmcontrol.worldgen.preview.variant")).tooltip(KineticI18n.translatable("gui.realmcontrol.worldgen.preview.variant.tooltip")).onClick(() -> {
            if (!selectedStructureId.isBlank()) requestPreview(selectedStructureId, previewVariant + 1);
        }).build();
        if (!selectedStructureId.isBlank()) requestPreview(selectedStructureId, previewVariant);
        else previewView.setMessage(KineticI18n.translatable("gui.realmcontrol.worldgen.preview.select"));
        updateStructureActionButtons();
    }

    private int previewX() {
        return 4 + LIST_W + 4;
    }

    private int previewWidth() {
        return this.width() - 4 - previewX();
    }

    private int infoY() {
        return this.height() - 4 - INFO_LINES * INFO_LINE_H;
    }

    private int previewHeight() {
        return infoY() - 4 - LIST_Y;
    }

    /** The sample shown for a structure here, so its rules page shows the same one. */
    int previewVariantOf(String structureId) {
        return structureId.equals(selectedStructureId) ? previewVariant : 0;
    }

    private void requestPreview(String structureId, int variant) {
        previewVariant = variant;
        previewResult = null;
        if (previewView == null) return;
        previewView.setSnapshot(null);
        previewView.setMessage(null);
        previewView.setLoading(0F);
        StructurePreviewClient.request(structureId, variant, new StructurePreviewClient.Listener() {
            @Override
            public void progress(float share) {
                if (previewView != null && previewView.snapshot() == null) previewView.setLoading(share);
            }

            @Override
            public void finished(StructurePreviewClient.Result result) {
                previewResult = result;
                if (previewView != null) previewView.setSnapshot(result.snapshot());
                refreshStructureRows();
            }

            @Override
            public void failed(Component reason) {
                if (previewView != null) previewView.setMessage(reason);
            }
        });
    }

    @Override
    protected void onRemoved() {
        StructurePreviewClient.cancel();
        StructureThumbnailClient.stop();
        if (previewView != null) previewView.release();
        super.onRemoved();
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
        String previous = selectedStructureId;
        selectedStructureId = descriptor == null ? "" : descriptor.structureId();
        if (!selectedStructureId.isBlank() && !selectedStructureId.equals(previous)) requestPreview(selectedStructureId, 0);
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
        if (editRulesButton != null) {
            setControlEnabled(editRulesButton, descriptor != null && !"unassigned".equals(descriptor.placementType()));
        }
        if (variantButton != null) {
            setControlEnabled(variantButton, descriptor != null);
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
        // A custom structure's rules are its draft's: the server sends the draft and its generation page opens.
        if (descriptor.custom()) {
            StructureEditorNetwork.request(StructureEditorNetwork.ProjectRequestPacket.Kind.OPEN_GENERATION, descriptor.structureId(), "");
            return;
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
            ResourceLocation langFile = dev.xyat.kineticcore.api.resource.KineticResourceIds.of(namespace, "lang/zh_cn.json");
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
        int panelW = this.width() - 8;
        int startX = 4;
        int listY = LIST_Y;
        int listH = this.height() - listY - 4;

        KineticTheme.panel(g, 0, 0, this.width(), this.height());
        KineticTheme.separator(g, 4, 24, this.width() - 8);
        KineticTheme.surface(g, startX, listY, LIST_W, listH, KineticTheme.Surface.PANEL_ALT);
    }

    @Override
    protected void renderForeground(KineticGraphics g, int mx, int my, float pt) {
        int panelW = this.width() - 8;
        int gap = 4;
        int titleX = 4 + 40 + gap;
        int saveX = 4 + panelW - 40;
        int editX = saveX - 3 * (gap + ACTION_W);
        g.scrollingTextCentered(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.title"), width() / 2, 8, Math.max(1, 2 * Math.min(width() / 2 - titleX, editX - gap - width() / 2)), 0xFFFFAA00, false);
        renderPreviewFacts(g);
        // The rows drawn this frame have noted which thumbnails they lack.
        StructureThumbnailClient.flush();
    }

    /** Size, pieces, counts and missing materials of the previewed sample, under the preview. */
    private void renderPreviewFacts(KineticGraphics g) {
        if (previewResult == null) return;
        // The first line leaves room for the Another sample button at its right.
        drawPreviewFacts(g, previewResult, previewX(), infoY() + 2, previewWidth() - 68, previewWidth());
    }

    /**
     * Draws three lines about a preview sample at {@code x, y}, {@link #INFO_LINE_H} apart: size and pieces, block,
     * container and entity counts, then missing materials. {@code firstWidth} limits the first line.
     */
    static void drawPreviewFacts(KineticGraphics g, StructurePreviewClient.Result result, int x, int y, int firstWidth, int w) {
        var info = result.info();
        var snapshot = result.snapshot();
        // The extent of the blocks themselves; the pieces' bounding box also covers terrain they only adapt.
        int scale = snapshot.scale();
        MutableComponent size = KineticI18n.translatable("gui.realmcontrol.worldgen.preview.size",
                snapshot.sizeX() * scale, snapshot.sizeY() * scale, snapshot.sizeZ() * scale, info.pieces());
        if (snapshot.scale() > 1) size.append(KineticI18n.translatable("gui.realmcontrol.worldgen.preview.reduced", snapshot.scale()));
        g.scrollingText(size, x, y, firstWidth, 0xFFFFFFFF, false);
        g.scrollingText(KineticI18n.translatable("gui.realmcontrol.worldgen.preview.counts", snapshot.nonAirCount(),
                snapshot.containerCount(), snapshot.entityCount()), x, y + INFO_LINE_H, w, 0xFFCCCCCC, false);
        Component third;
        int color;
        if (!info.missing().isEmpty()) {
            String names = info.missing().entrySet().stream().map(e -> e.getKey() + " \u00d7" + e.getValue()).collect(Collectors.joining("\u3001"));
            third = KineticI18n.translatable("gui.realmcontrol.worldgen.preview.missing", names);
            color = 0xFFFF5555;
        } else if (info.failedChunks() > 0) {
            third = KineticI18n.translatable("gui.realmcontrol.worldgen.preview.partial", info.failedChunks());
            color = 0xFFFFAA00;
        } else {
            third = KineticI18n.translatable("gui.realmcontrol.worldgen.preview.no_missing");
            color = 0xFF55FF55;
        }
        g.scrollingText(third, x, y + 2 * INFO_LINE_H, w, color, false);
    }

    /**
     * 结构列表（原 SmoothSelectionList）：行内“编辑”按钮由 KineticTheme.button 绘制，点击在 onRowClick 中判定。
     * Structure list (formerly SmoothSelectionList): the inline edit button is painted with KineticTheme.button and
     * hit-tested in onRowClick.
     */
    class StructureListWidget extends KineticRowList<StructureRuleDescriptor> {

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
                            .thenComparing(StructureRuleDescriptor::custom, Comparator.reverseOrder())
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
            } else if (disabled || hasMissingMaterials(descriptor)) {
                KineticTheme.indicatorOutline(g, l, t, w, contentH, KineticTheme.Indicator.DANGER);
            } else if (modified) {
                KineticTheme.indicatorOutline(g, l, t, w, contentH, KineticTheme.Indicator.SUCCESS);
            } else {
                KineticTheme.indicatorOutline(g, l, t, w, contentH, KineticTheme.Indicator.MUTED);
            }

            int thumbX = l + 2, thumbY = t + (contentH - THUMBNAIL_SIZE) / 2;
            KineticTexture thumbnail = StructureThumbnailClient.get(descriptor.structureId());
            if (thumbnail != null) {
                g.texture(thumbnail, thumbX, thumbY, THUMBNAIL_SIZE, THUMBNAIL_SIZE, 0, 0,
                        thumbnail.textureWidth(), thumbnail.textureHeight());
            } else {
                g.fill(thumbX, thumbY, thumbX + THUMBNAIL_SIZE, thumbY + THUMBNAIL_SIZE, 0x22FFFFFF);
                if (!StructureThumbnailClient.none(descriptor.structureId())) {
                    g.scrollingTextCentered(Component.literal("…"), thumbX + THUMBNAIL_SIZE / 2,
                            thumbY + (THUMBNAIL_SIZE - g.lineHeight()) / 2, THUMBNAIL_SIZE, 0xFF888888, false);
                }
            }

            String display = toDisplayEntry(descriptor.structureId());
            int textX = thumbX + THUMBNAIL_SIZE + 4;
            int maxW = l + w - 6 - textX;
            int lineHeight = g.lineHeight();
            int textGap = 1;
            int textBlockH = lineHeight * 2 + textGap;
            int firstLineY = t + (contentH - textBlockH) / 2;
            int secondLineY = firstLineY + lineHeight + textGap;
            g.scrollingText(Component.literal(display), textX, firstLineY, maxW, 0xFFFFFFFF, false);

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
            MutableComponent summary = Component.empty();
            if (descriptor.custom()) summary.append(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.custom_tag")).append(Component.literal("  "));
            summary.append(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_summary", state, type, freq, weightText))
                    .append(Component.literal("  "))
                    .append(dimension);
            if (hasMissingMaterials(descriptor)) {
                summary.append(Component.literal("  ")).append(KineticI18n.translatable("gui.realmcontrol.worldgen.worldgen.structure_missing_tag"));
            }
            g.scrollingText(summary, textX, secondLineY, maxW, 0xFFCCCCCC, false);
        }

        @Override
        protected boolean onRowClick(StructureRuleDescriptor descriptor, int index, MouseInput input) {
            // 原行为：左键选中结构；命中可用的编辑按钮时再打开编辑器
            // Former behaviour: left click selects the structure; hitting the enabled edit button also opens the editor.
            if (!input.isLeft()) {
                return false;
            }
            selectStructure(descriptor);
            // A second click on the same row soon after opens its rules, as the Edit rules button does.
            long now = System.nanoTime();
            boolean doubleClick = index == lastRowIndex && now - lastRowClick < 350_000_000L;
            lastRowIndex = index;
            lastRowClick = doubleClick ? 0L : now;
            if (doubleClick && editEnabled(descriptor)) {
                playClickSound();
                openStructureEditor(descriptor);
            }
            return true;
        }
    }

    private static boolean hasMissingMaterials(StructureRuleDescriptor descriptor) {
        var known = StructurePreviewClient.known(descriptor.structureId());
        return known != null && !known.missing().isEmpty();
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
