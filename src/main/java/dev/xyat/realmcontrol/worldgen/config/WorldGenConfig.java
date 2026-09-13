package dev.xyat.realmcontrol.worldgen.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WorldGenConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("kineticcore/worldgen.toml");
    private static CommentedFileConfig configData;

    public static boolean enableStructureBlocking = true;
    public static List<String> disabledStructures = new ArrayList<>();
    public static Map<String, StructureEntryRule> structureEntryRules = new LinkedHashMap<>();
    public static Map<String, StructurePlacementRule> structurePlacementRules = new LinkedHashMap<>();
    public static boolean enableBiomeControl = true;
    public static List<BiomeReplacementRule> biomeReplacementRules = new ArrayList<>();

    public static void load() {
        try {
            if (configData != null) {
                configData.close();
            }
            configData = CommentedFileConfig.builder(CONFIG_PATH)
                    .sync()
                    .preserveInsertionOrder()
                    .writingMode(WritingMode.REPLACE)
                    .build();
            configData.load();
            setupConfig();
            configData.save();
            readValues();
        } catch (Exception e) {
            WorldGenModule.LOGGER.error("WorldGenConfig load failed", e);
            if (configData != null) {
                try {
                    configData.close();
                } catch (Exception closeException) {
                    WorldGenModule.LOGGER.debug("Failed to close broken world generation config", closeException);
                }
                configData = null;
            }
        }
    }

    private static void setupConfig() {
        if (configData.contains("worldgen")) {
            configData.remove("worldgen");
        }

        configData.setComment("structure_control", """
                 结构自然生成管理
                 Natural structure generation management""");

        define("structure_control.enable", true, """
                 是否启用结构自然生成规则覆盖。
                 Whether to enable natural structure generation overrides.""");

        define("structure_control.disabled", new ArrayList<>(), """
                 旧版兼容字段：禁止自然生成的结构 ID 列表。新版本保存后会同步到 entry_rules。
                 Legacy compatibility field for disabled structure IDs. New saves mirror these values into entry_rules.""");

        define("structure_control.entry_rules", new ArrayList<>(), """
                 单结构覆盖规则。格式由界面自动维护，包含禁用状态和结构选择权重。
                 Per-structure entry overrides maintained by the GUI, including disabled state and selection weight.""");

        define("structure_control.placement_rules", new ArrayList<>(), """
                 StructureSet 放置规则覆盖。格式由界面自动维护，包含 frequency、salt、间距和环形分布参数。
                 StructureSet placement overrides maintained by the GUI, including frequency, salt, spacing and concentric-ring parameters.""");

        configData.setComment("biome_control", """
                 群系替换与移除规则。只改变群系选择，不改变地形形状。
                 Biome replacement/removal rules. Changes biome selection only, not terrain shape.""");

        define("biome_control.enable", true, """
                 是否启用群系控制。规则会在世界启动时应用到 MultiNoise 群系源。
                 Whether biome control is enabled. Rules are applied to MultiNoise biome sources on world start.""");

        define("biome_control.rules", new ArrayList<>(), """
                 由界面维护的群系规则：维度|源群系或#标签|目标群系或null。
                 GUI-managed biome rules: dimension|source biome or #tag|target biome or null.""");
    }

    private static void define(String path, Object def, String comment) {
        if (!configData.contains(path)) {
            configData.set(path, def);
        }
        configData.setComment(path, comment);
    }

    private static void readValues() {
        enableStructureBlocking = configData.getOrElse("structure_control.enable", true);
        disabledStructures = configData.getOrElse("structure_control.disabled", new ArrayList<>());

        structureEntryRules = new LinkedHashMap<>();
        List<String> rawEntryRules = configData.getOrElse("structure_control.entry_rules", new ArrayList<>());
        for (String encoded : rawEntryRules) {
            StructureEntryRule rule = StructureRuleCodec.decodeEntry(encoded);
            if (rule != null && !rule.isEmpty()) {
                structureEntryRules.put(rule.structureId(), rule);
            }
        }
        for (String id : disabledStructures) {
            if (id == null || id.isBlank()) {
                continue;
            }
            structureEntryRules.putIfAbsent(id.trim(), new StructureEntryRule(id.trim(), true, null));
        }

        structurePlacementRules = new LinkedHashMap<>();
        List<String> rawPlacementRules = configData.getOrElse("structure_control.placement_rules", new ArrayList<>());
        for (String encoded : rawPlacementRules) {
            StructurePlacementRule rule = StructureRuleCodec.decodePlacement(encoded);
            if (rule != null && !rule.isEmpty()) {
                structurePlacementRules.put(rule.structureSetId(), rule);
            }
        }

        disabledStructures = structureEntryRules.values().stream()
                .filter(StructureEntryRule::disabled)
                .map(StructureEntryRule::structureId)
                .distinct()
                .sorted()
                .toList();

        enableBiomeControl = configData.getOrElse("biome_control.enable", true);
        biomeReplacementRules = new ArrayList<>();
        List<String> rawBiomeRules = configData.getOrElse("biome_control.rules", new ArrayList<>());
        for (String encoded : rawBiomeRules) {
            BiomeReplacementRule rule = BiomeRuleCodec.decode(encoded);
            if (rule != null && !rule.isEmpty()) {
                biomeReplacementRules.add(rule);
            }
        }

        StructureGenerationControl.refresh(structureEntryRules, structurePlacementRules, enableStructureBlocking);
    }

    public static void save() {
        if (configData == null) {
            throw new IllegalStateException("World generation config is not loaded");
        }

        Path backupPath = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".save-backup");
        boolean hadOriginal = Files.exists(CONFIG_PATH);
        try {
            if (hadOriginal) {
                Files.copy(CONFIG_PATH, backupPath, StandardCopyOption.REPLACE_EXISTING);
            }

            if (configData.contains("worldgen")) {
                configData.remove("worldgen");
            }

            configData.set("structure_control.enable", enableStructureBlocking);
            disabledStructures = structureEntryRules.values().stream()
                    .filter(rule -> rule != null && rule.disabled())
                    .map(StructureEntryRule::structureId)
                    .distinct()
                    .sorted()
                    .toList();
            configData.set("structure_control.disabled", disabledStructures);
            configData.set("structure_control.entry_rules", structureEntryRules.values().stream()
                    .filter(rule -> rule != null && !rule.isEmpty())
                    .sorted(Comparator.comparing(StructureEntryRule::structureId))
                    .map(StructureRuleCodec::encodeEntry)
                    .toList());
            configData.set("structure_control.placement_rules", structurePlacementRules.values().stream()
                    .filter(rule -> rule != null && !rule.isEmpty())
                    .sorted(Comparator.comparing(StructurePlacementRule::structureSetId))
                    .map(StructureRuleCodec::encodePlacement)
                    .toList());
            configData.set("biome_control.enable", enableBiomeControl);
            configData.set("biome_control.rules", biomeReplacementRules.stream()
                    .filter(rule -> rule != null && !rule.isEmpty())
                    .map(BiomeRuleCodec::encode)
                    .toList());

            configData.save();
            readValues();
            try {
                Files.deleteIfExists(backupPath);
            } catch (Exception cleanupException) {
                WorldGenModule.LOGGER.debug("Failed to remove world generation config save backup", cleanupException);
            }
        } catch (Exception exception) {
            restoreAfterFailedSave(backupPath, hadOriginal);
            throw new IllegalStateException("Failed to save world generation config", exception);
        }
    }

    private static void restoreAfterFailedSave(Path backupPath, boolean hadOriginal) {
        try {
            if (configData != null) {
                configData.close();
            }
        } catch (Exception closeException) {
            WorldGenModule.LOGGER.debug("Failed to close world generation config before rollback", closeException);
        }
        configData = null;

        try {
            if (hadOriginal && Files.exists(backupPath)) {
                Files.move(backupPath, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(backupPath);
            }
        } catch (Exception restoreFileException) {
            WorldGenModule.LOGGER.error("Failed to restore world generation config file after save failure", restoreFileException);
        }

        try {
            load();
        } catch (Exception reloadException) {
            WorldGenModule.LOGGER.error("Failed to reload world generation config after rollback", reloadException);
        }
    }
}
