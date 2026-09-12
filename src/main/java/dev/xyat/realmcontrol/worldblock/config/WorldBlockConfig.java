package dev.xyat.realmcontrol.worldblock.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class WorldBlockConfig {
    private static final Logger LOGGER = LogManager.getLogger("realmcontrol/WorldBlockConfig");
    public static final String VOID_ID = "realmcontrol:void_placeholder";
    public static final Path PATH = Paths.get("config", "kineticcore", "worldblock.json");
    public static final Path BACKUP_PATH = Paths.get("config", "kineticcore", "worldblock.old.json");
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static volatile Data data = new Data();
    public static volatile Map<ItemRule, String> ruleReplacementMap = new HashMap<>();

    private static final ConcurrentHashMap<Item, String> ITEM_ID_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger INVALID_RULE_LOG_COUNT = new AtomicInteger();

    public static volatile Map<String, String> pureIdReplacements = new HashMap<>();
    public static volatile Map<String, List<Map.Entry<ItemRule, String>>> nbtReplacements = new HashMap<>();
    public static volatile List<Map.Entry<ItemRule, String>> macroReplacements = new ArrayList<>();
    public static volatile Map<String, Set<String>> targetMergedTags = new HashMap<>();
    public static volatile Map<String, String> pureBlockReplacements = new HashMap<>();
    public static volatile Set<Block> worldgenBannedBlocks = Collections.emptySet();
    public static volatile Map<Block, BlockState> worldgenReplacementBlockStates = Collections.emptyMap();
    public static volatile Map<BlockState, BlockState> worldgenFastPlainStateRewrites = Collections.emptyMap();
    public static volatile Map<BlockState, BlockState> worldgenFastStateRewrites = Collections.emptyMap();
    public static volatile Map<Block, Integer> worldgenFixedReplacementChances = Collections.emptyMap();
    public static volatile Map<Block, WeightedBlockReplacement> worldgenWeightedBlockReplacements = Collections.emptyMap();
    public static volatile Set<Block> worldgenMergeTargetBlocks = Collections.emptySet();
    public static volatile Set<String> worldgenMergeTargetIds = Collections.emptySet();

    public static volatile Set<String> pureIdBanned = new HashSet<>();
    public static volatile Map<String, List<ItemRule>> nbtBanned = new HashMap<>();
    public static volatile List<ItemRule> macroBanned = new ArrayList<>();
    public static volatile List<ItemRule> oreGenerationBannedRules = new ArrayList<>();
    public static volatile boolean hasOreGenerationBanRules = false;
    public static volatile boolean hasRawWorldgenRewriteRules = false;
    public static volatile boolean hasBlockReplacementRules = false;

    public static String getItemIdFromCache(Item item) {
        return ITEM_ID_CACHE.computeIfAbsent(item, k -> {
            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(k);
            return rl != null ? rl.toString() : "";
        });
    }

    public static class Data {
        public transient List<String> bannedItems = new ArrayList<>();
        public List<String> bannedOreGenerations = new ArrayList<>();
        public transient Map<String, List<String>> mergedItems = new HashMap<>();
        public Map<String, List<String>> oreMergedItems = new HashMap<>();
        public Map<String, List<WeightedBlockTarget>> weightedBlockReplacements = new LinkedHashMap<>();
        public Map<String, Integer> blockReplacementChances = new LinkedHashMap<>();
        public Map<String, Integer> weightedBlockReplacementChances = new LinkedHashMap<>();
        public boolean applyBlockReplacementToLoadedChunksOnce = false;
        public boolean applyWeightedBlockReplacementToLoadedChunksOnce = false;
    }

    public static class WeightedBlockTarget {
        public String target = "";
        public int weight = 100;

        public WeightedBlockTarget() {
        }

        public WeightedBlockTarget(String target, int weight) {
            this.target = target;
            this.weight = weight;
        }
    }

    public static final class WeightedBlockReplacement {
        private final BlockState[] targetStates;
        private final int[] cumulativeWeights;
        private final int totalWeight;
        private final int replacementChance;
        private final Map<BlockState, BlockState[]> resolvedStatesBySourceState;

        private WeightedBlockReplacement(
                Block sourceBlock,
                BlockState[] targetStates,
                int[] cumulativeWeights,
                int totalWeight,
                int replacementChance
        ) {
            this.targetStates = targetStates;
            this.cumulativeWeights = cumulativeWeights;
            this.totalWeight = totalWeight;
            this.replacementChance = clampChance(replacementChance);
            IdentityHashMap<BlockState, BlockState[]> resolved = new IdentityHashMap<>();
            for (BlockState sourceState : sourceBlock.getStateDefinition().getPossibleStates()) {
                BlockState[] targets = new BlockState[targetStates.length];
                for (int i = 0; i < targetStates.length; i++) {
                    targets[i] = copySharedProperties(sourceState, targetStates[i]);
                }
                resolved.put(sourceState, targets);
            }
            this.resolvedStatesBySourceState = Collections.unmodifiableMap(resolved);
        }

        public BlockState resolveState(BlockState sourceState) {
            if (sourceState == null || targetStates.length == 0 || totalWeight <= 0) return sourceState;
            if (!rollReplacementChance(replacementChance)) return sourceState;
            int roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(totalWeight);
            int index = Arrays.binarySearch(cumulativeWeights, roll + 1);
            if (index < 0) index = -index - 1;
            BlockState[] resolved = resolvedStatesBySourceState.get(sourceState);
            if (resolved != null && index < resolved.length) return resolved[index];
            return targetStates[index];
        }
    }

    public static Map<String, List<WeightedBlockTarget>> copyWeightedBlockReplacements() {
        Map<String, List<WeightedBlockTarget>> result = new LinkedHashMap<>();
        if (data == null || data.weightedBlockReplacements == null) return result;
        for (Map.Entry<String, List<WeightedBlockTarget>> entry : data.weightedBlockReplacements.entrySet()) {
            List<WeightedBlockTarget> values = new ArrayList<>();
            if (entry.getValue() != null) {
                for (WeightedBlockTarget value : entry.getValue()) {
                    if (value != null) values.add(new WeightedBlockTarget(value.target, value.weight));
                }
            }
            if (!values.isEmpty()) result.put(entry.getKey(), values);
        }
        return result;
    }

    public static Set<String> getWeightedBlockRuleIdentifiers() {
        Set<String> result = new HashSet<>();
        if (data == null || data.weightedBlockReplacements == null) return result;
        for (Map.Entry<String, List<WeightedBlockTarget>> entry : data.weightedBlockReplacements.entrySet()) {
            String source = getBaseIdentifier(entry.getKey());
            if (!source.isEmpty()) result.add(source);
            if (entry.getValue() == null) continue;
            for (WeightedBlockTarget target : entry.getValue()) {
                if (target == null) continue;
                String id = getBaseIdentifier(target.target);
                if (!id.isEmpty()) result.add(id);
            }
        }
        return result;
    }

    public static Set<String> getSimpleBlockMergeIdentifiers() {
        Set<String> result = new HashSet<>();
        if (data == null || data.oreMergedItems == null) return result;
        for (Map.Entry<String, List<String>> entry : data.oreMergedItems.entrySet()) {
            String target = getBaseIdentifier(entry.getKey());
            if (!target.isEmpty()) result.add(target);
            if (entry.getValue() == null) continue;
            for (String source : entry.getValue()) {
                String id = getBaseIdentifier(source);
                if (!id.isEmpty()) result.add(id);
            }
        }
        return result;
    }

    public static class ItemRule {
        public final String originalString;
        public final String baseId;
        public final CompoundTag nbt;
        public final boolean hasNbt;

        public ItemRule(String str) {
            String clean = normalizeRuleIdentifier(str);
            this.originalString = clean;
            int bracket = clean.indexOf('{');
            if (bracket == -1) {
                this.baseId = clean;
                this.nbt = null;
                this.hasNbt = false;
            } else {
                this.baseId = clean.substring(0, bracket);
                this.nbt = safeParseTagForRule(clean, clean.substring(bracket));
                this.hasNbt = true;
            }
        }

        public boolean matchesWithId(ItemStack stack, String itemId) {
            if (stack == null || itemId == null || itemId.isEmpty() || this.originalString.isEmpty()) return false;
            if (this.originalString.startsWith("@")) {
                int colonIdx = itemId.indexOf(':');
                String namespace = colonIdx == -1 ? itemId : itemId.substring(0, colonIdx);
                return namespace.equals(this.originalString.substring(1));
            }
            if (this.originalString.startsWith("#")) {
                String targetTag = this.originalString.substring(1);
                try {
                    return stack.getTags().anyMatch(t -> t.location().toString().toLowerCase(Locale.ROOT).equals(targetTag));
                } catch (Throwable e) {
                    logLimitedWarn("匹配物品标签规则时出错，规则=" + this.originalString + ", 物品=" + itemId, e);
                    return false;
                }
            }

            if (!itemId.equals(this.baseId)) return false;
            if (!this.hasNbt) return true;
            if (this.nbt != null) {
                return net.minecraft.nbt.NbtUtils.compareNbt(this.nbt, stack.getTag(), true);
            }
            return false;
        }

        public boolean matches(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            String itemId = getItemIdFromCache(stack.getItem());
            if (itemId.isEmpty()) return false;
            return matchesWithId(stack, itemId);
        }

        @Override
        public int hashCode() { return originalString.hashCode(); }
        @Override
        public boolean equals(Object obj) {
            return obj instanceof ItemRule && ((ItemRule)obj).originalString.equals(this.originalString);
        }
    }

    public static boolean isProtected(String identifier) {
        if (identifier == null) return false;
        String clean = identifier.toLowerCase(Locale.ROOT);
        return clean.contains("realmcontrol");
    }

    public static boolean isBanned(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        String itemId = getItemIdFromCache(stack.getItem());
        if (itemId.isEmpty()) return false;

        if (pureIdBanned.contains(itemId)) return true;

        List<ItemRule> nbtRules = nbtBanned.get(itemId);
        if (nbtRules != null) {
            for (ItemRule rule : nbtRules) {
                if (rule.matchesWithId(stack, itemId)) return true;
            }
        }

        for (ItemRule rule : macroBanned) {
            if (rule.matchesWithId(stack, itemId)) return true;
        }

        return false;
    }

    public static boolean isBannedStr(String idStr) {
        String clean = normalizeRuleIdentifier(idStr);
        if (clean.isEmpty() || clean.startsWith("@") || clean.startsWith("#")) return false;
        if (data != null && data.bannedItems != null && data.bannedItems.contains(clean)) return true;

        int bracket = clean.indexOf('{');
        String baseId = bracket == -1 ? clean : clean.substring(0, bracket);
        if (pureIdBanned.contains(baseId)) return true;

        ItemStack stack = parseItemStack(clean);
        return !stack.isEmpty() && isBanned(stack);
    }


    public static Set<String> getMergedTagIds(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Collections.emptySet();
        String itemId = getItemIdFromCache(stack.getItem());
        Set<String> configured = targetMergedTags.get(itemId);
        if (configured == null || configured.isEmpty()) return Collections.emptySet();
        return configured;
    }

    public static Set<TagKey<Item>> getMergedTagKeys(ItemStack stack) {
        Set<String> ids = getMergedTagIds(stack);
        if (ids.isEmpty()) return Collections.emptySet();
        Set<TagKey<Item>> result = new HashSet<>();
        for (String id : ids) {
            try {
                result.add(ItemTags.create(new ResourceLocation(id)));
            } catch (Exception ignored) {}
        }
        if (result.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(result);
    }

    public static boolean hasMergedTag(ItemStack stack, TagKey<Item> tagKey) {
        if (tagKey == null) return false;
        String tagId = tagKey.location().toString().toLowerCase(Locale.ROOT);
        return getMergedTagIds(stack).contains(tagId);
    }

    private static String normalizeMergedTagId(String tagId) {
        if (tagId == null) return "";
        String clean = tagId.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("#")) clean = clean.substring(1);
        try {
            return new ResourceLocation(clean).toString();
        } catch (Exception ignored) {
            return "";
        }
    }


    private static void normalizeData() {
        if (data == null) data = new Data();
        if (data.bannedItems == null) data.bannedItems = new ArrayList<>();
        if (data.bannedOreGenerations == null) data.bannedOreGenerations = new ArrayList<>();
        if (data.mergedItems == null) data.mergedItems = new LinkedHashMap<>();
        if (data.oreMergedItems == null) data.oreMergedItems = new LinkedHashMap<>();
        if (data.weightedBlockReplacements == null) data.weightedBlockReplacements = new LinkedHashMap<>();
        if (data.blockReplacementChances == null) data.blockReplacementChances = new LinkedHashMap<>();
        if (data.weightedBlockReplacementChances == null) data.weightedBlockReplacementChances = new LinkedHashMap<>();

        data.bannedItems = normalizeRuleList(data.bannedItems, "bannedItems");
        data.bannedOreGenerations = normalizeRuleList(data.bannedOreGenerations, "bannedOreGenerations");
        data.mergedItems = normalizeRuleMap(data.mergedItems);
        data.oreMergedItems = normalizeBlockRuleMap(data.oreMergedItems);
        data.weightedBlockReplacements = normalizeWeightedBlockRules(data.weightedBlockReplacements);
        data.blockReplacementChances = normalizeChanceMap(data.blockReplacementChances);
        data.weightedBlockReplacementChances = normalizeChanceMap(data.weightedBlockReplacementChances);
        removeWeightedBlockRuleConflicts();
        removeDirectOreGenerationBansForMergeTargets();
    }

    private static Map<String, Integer> normalizeChanceMap(Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) return result;
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            String key = getBaseIdentifier(entry.getKey());
            if (key.isEmpty()) continue;
            int chance = clampChance(entry.getValue() == null ? 100 : entry.getValue());
            if (chance < 100) result.put(key, chance);
        }
        return result;
    }

    private static int clampChance(int chance) {
        return Math.max(0, Math.min(100, chance));
    }

    private static boolean rollReplacementChance(int chance) {
        int clamped = clampChance(chance);
        if (clamped >= 100) return true;
        if (clamped <= 0) return false;
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < clamped;
    }

    private static List<String> normalizeRuleList(List<String> source, String name) {
        if (source == null || source.isEmpty()) return new ArrayList<>();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String raw : source) {
            String clean = normalizeRuleIdentifier(raw);
            if (clean.isEmpty()) {
                logLimitedWarn("已跳过无效规则，位置=" + name + ", 原始值=" + raw, null);
                continue;
            }
            result.add(clean);
        }
        return new ArrayList<>(result);
    }

    private static String normalizePrefixedRuleBody(String raw) {
        return raw.substring(1).trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeRuleIdentifier(String identifier) {
        if (identifier == null) return "";
        String raw = identifier.trim();
        if (raw.isEmpty()) return "";
        if (raw.startsWith("@")) {
            String namespace = normalizePrefixedRuleBody(raw);
            if (namespace.isEmpty() || namespace.contains(" ") || namespace.contains("{")) return "";
            return "@" + namespace;
        }
        if (raw.startsWith("#")) {
            String tag = normalizePrefixedRuleBody(raw);
            try {
                return "#" + new ResourceLocation(tag);
            } catch (Exception e) {
                logLimitedWarn("无效标签规则=" + raw, e);
                return "";
            }
        }

        int bracket = raw.indexOf('{');
        String idPart = bracket == -1 ? raw : raw.substring(0, bracket);
        String nbtPart = bracket == -1 ? "" : raw.substring(bracket).trim();
        String cleanId = idPart.trim().toLowerCase(Locale.ROOT);
        if (cleanId.isEmpty()) return "";
        try {
            cleanId = new ResourceLocation(cleanId).toString();
        } catch (Exception e) {
            logLimitedWarn("无效物品或方块 ID=" + raw, e);
            return "";
        }
        if (!nbtPart.isEmpty()) {
            CompoundTag parsed = safeParseTagForRule(raw, nbtPart);
            if (parsed == null) return "";
            String compact = compactKnownNbtIdentifier(cleanId, parsed);
            if (!compact.isEmpty()) return compact;
            return cleanId + nbtPart;
        }
        return cleanId;
    }

    private static String compactKnownNbtIdentifier(String baseId, CompoundTag tag) {
        if (baseId == null || baseId.isBlank() || tag == null || tag.isEmpty()) return "";
        if (tag.contains("GunId", 8)) {
            String gunId = tag.getString("GunId").trim();
            if (!gunId.isEmpty()) {
                CompoundTag stable = new CompoundTag();
                stable.putString("GunId", gunId);
                return baseId + stable;
            }
        }
        return "";
    }

    private static CompoundTag safeParseTagForRule(String rule, String nbtText) {
        if (nbtText == null || nbtText.isBlank()) return null;
        try {
            return TagParser.parseTag(nbtText);
        } catch (Exception e) {
            logLimitedWarn("无效 NBT 规则，已跳过，规则=" + rule + ", NBT=" + nbtText, e);
            return null;
        }
    }

    private static void logLimitedWarn(String message, Throwable throwable) {
        if (INVALID_RULE_LOG_COUNT.incrementAndGet() > 200) return;
        if (throwable == null) LOGGER.warn(message);
        else LOGGER.warn(message, throwable);
    }

    private static void removeDirectOreGenerationBansForMergeTargets() {
        if (data == null || data.bannedOreGenerations == null) return;
        Set<String> targets = new HashSet<>();
        if (data.oreMergedItems != null) {
            data.oreMergedItems.keySet().stream()
                    .map(WorldBlockConfig::getBaseIdentifier)
                    .filter(id -> !id.isEmpty())
                    .forEach(targets::add);
        }
        if (data.weightedBlockReplacements != null) {
            for (List<WeightedBlockTarget> values : data.weightedBlockReplacements.values()) {
                if (values == null) continue;
                for (WeightedBlockTarget value : values) {
                    if (value == null) continue;
                    String id = getBaseIdentifier(value.target);
                    if (!id.isEmpty()) targets.add(id);
                }
            }
        }
        if (targets.isEmpty()) return;
        data.bannedOreGenerations.removeIf(rule -> {
            if (rule == null) return true;
            String clean = rule.trim();
            if (clean.startsWith("@") || clean.startsWith("#")) return false;
            return targets.contains(getBaseIdentifier(clean));
        });
    }

    private static Map<String, List<String>> normalizeRuleMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) return result;
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String target = cleanIdentifier(entry.getKey());
            if (target.isEmpty()) continue;
            List<String> values = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            List<String> cleanSources = values.stream()
                    .map(WorldBlockConfig::cleanIdentifier)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !getBaseIdentifier(s).equals(getBaseIdentifier(target)))
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!cleanSources.isEmpty()) result.put(target, cleanSources);
        }
        return result;
    }

    private static Map<String, List<String>> normalizeBlockRuleMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) return result;

        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String target = normalizeBlockIdentifier(entry.getKey());
            if (target.isEmpty()) continue;

            List<String> values = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            LinkedHashSet<String> cleanSources = new LinkedHashSet<>();
            for (String rawSource : values) {
                String sourceId = normalizeBlockIdentifier(rawSource);
                if (sourceId.isEmpty() || sourceId.equals(target)) continue;
                cleanSources.add(sourceId);
            }

            if (!cleanSources.isEmpty()) result.put(target, new ArrayList<>(cleanSources));
        }
        return result;
    }

    private static Map<String, List<WeightedBlockTarget>> normalizeWeightedBlockRules(
            Map<String, List<WeightedBlockTarget>> source
    ) {
        Map<String, List<WeightedBlockTarget>> result = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) return result;

        for (Map.Entry<String, List<WeightedBlockTarget>> entry : source.entrySet()) {
            String sourceId = normalizeBlockIdentifier(entry.getKey());
            if (sourceId.isEmpty()) continue;

            List<WeightedBlockTarget> values = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            LinkedHashMap<String, Integer> merged = new LinkedHashMap<>();
            for (WeightedBlockTarget value : values) {
                if (value == null) continue;
                String targetId = normalizeBlockIdentifier(value.target);
                int weight = Math.max(1, Math.min(1_000_000, value.weight));
                if (targetId.isEmpty() || targetId.equals(sourceId)) continue;
                merged.merge(targetId, weight, (left, right) -> {
                    long sum = (long) left + right;
                    return (int) Math.min(1_000_000L, sum);
                });
            }

            if (merged.isEmpty()) continue;
            List<WeightedBlockTarget> clean = new ArrayList<>();
            for (Map.Entry<String, Integer> target : merged.entrySet()) {
                clean.add(new WeightedBlockTarget(target.getKey(), Math.min(1_000_000, target.getValue())));
            }
            result.put(sourceId, clean);
        }
        return result;
    }

    private static void removeWeightedBlockRuleConflicts() {
        if (data.weightedBlockReplacements == null || data.weightedBlockReplacements.isEmpty()) return;

        Set<String> simpleIdentifiers = getSimpleBlockMergeIdentifiers();
        Set<String> weightedSources = new HashSet<>(data.weightedBlockReplacements.keySet());
        Iterator<Map.Entry<String, List<WeightedBlockTarget>>> iterator = data.weightedBlockReplacements.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<WeightedBlockTarget>> entry = iterator.next();
            if (simpleIdentifiers.contains(entry.getKey())) {
                iterator.remove();
                continue;
            }
            List<WeightedBlockTarget> targets = entry.getValue();
            if (targets == null) {
                iterator.remove();
                continue;
            }
            targets.removeIf(target -> target == null
                    || simpleIdentifiers.contains(target.target)
                    || weightedSources.contains(target.target));
            if (targets.isEmpty()) iterator.remove();
        }
    }

    private static String normalizeBlockIdentifier(String identifier) {
        if (identifier == null) return "";
        String raw = identifier.trim();
        if (raw.isEmpty() || raw.startsWith("@") || raw.startsWith("#")) return "";

        int bracket = raw.indexOf('{');
        String idPart = bracket == -1 ? raw : raw.substring(0, bracket);
        String cleanId = idPart.trim().toLowerCase(Locale.ROOT);
        if (cleanId.isEmpty()) return "";

        try {
            return new ResourceLocation(cleanId).toString();
        } catch (Exception e) {
            logLimitedWarn("无效方块 ID=" + raw, e);
            return "";
        }
    }

    private static String cleanIdentifier(String identifier) {
        return normalizeRuleIdentifier(identifier);
    }

    public static String getBaseIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) return "";
        String raw = identifier.trim();
        int bracket = raw.indexOf('{');
        String base = bracket == -1 ? raw : raw.substring(0, bracket);
        if (base.startsWith("@") || base.startsWith("#")) return base.toLowerCase(Locale.ROOT);
        try {
            return new ResourceLocation(base.trim().toLowerCase(Locale.ROOT)).toString();
        } catch (Exception ignored) {
            return "";
        }
    }
    public static void load() {
        try {
            if (Files.exists(PATH)) {
                String json = Files.readString(PATH);
                data = readDataFromJson(json, "load");
            } else {
                data = new Data();
            }
            normalizeData();
            if (!Files.exists(PATH)) writeConfigOnly();
        } catch (Throwable e) {
            LOGGER.error("配置加载失败，已备份异常配置并使用空白安全配置", e);
            backupBrokenConfig();
            data = new Data();
            try {
                normalizeData();
                writeConfigOnly();
            } catch (Throwable writeError) {
                LOGGER.error("写入空白安全配置失败", writeError);
            }
        }
        rebuildCache();
    }

    public static void save() {
        try {
            normalizeData();
            writeConfigOnly();
            rebuildCache();
        } catch (Throwable e) {
            LOGGER.error("保存世界方块配置失败", e);
        }
    }

    public static synchronized boolean applyJson(String json, String source, boolean saveFile) {
        Data previous = data;
        try {
            if (json == null || json.isBlank()) {
                LOGGER.warn("收到空白配置，来源={}", source);
                return false;
            }
            Data candidate = readDataFromJson(json, source);
            data = candidate;
            normalizeData();
            if (saveFile) writeConfigOnly();
            rebuildCache();
            return true;
        } catch (Throwable e) {
            data = previous == null ? new Data() : previous;
            try {
                normalizeData();
                rebuildCache();
            } catch (Throwable rollbackError) {
                LOGGER.error("恢复保存前配置失败，来源={}", source, rollbackError);
            }
            LOGGER.error("应用配置失败，已忽略本次错误数据，来源={}", source, e);
            return false;
        }
    }

    public static String getNetworkJson() {
        try {
            normalizeData();
        } catch (Throwable e) {
            LOGGER.error("生成同步配置前清洗失败，将使用当前内存数据", e);
        }
        return GSON.toJson(data == null ? new Data() : data);
    }


    private static Data readDataFromJson(String json, String source) {
        try {
            Data loaded = GSON.fromJson(json, Data.class);
            if (loaded == null) {
                LOGGER.warn("配置内容为空，来源={}", source);
                return new Data();
            }
            return loaded;
        } catch (Throwable e) {
            throw new IllegalArgumentException("无法解析 " + PATH.getFileName() + "，来源=" + source, e);
        }
    }

    private static void writeConfigOnly() {
        Path temp = PATH.resolveSibling(PATH.getFileName() + ".tmp");
        try {
            if (PATH.getParent() != null) Files.createDirectories(PATH.getParent());
            Files.writeString(temp, GSON.toJson(data == null ? new Data() : data));
            try {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable e) {
            try {
                Files.deleteIfExists(temp);
            } catch (Throwable ignored) {
            }
            throw new IllegalStateException("写入 " + PATH.getFileName() + " 失败", e);
        }
    }

    private static void backupBrokenConfig() {
        try {
            if (!Files.exists(PATH)) return;
            if (BACKUP_PATH.getParent() != null) Files.createDirectories(BACKUP_PATH.getParent());
            Files.copy(PATH, BACKUP_PATH, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("已备份异常配置到 {}", BACKUP_PATH);
        } catch (Throwable e) {
            LOGGER.error("备份异常配置失败", e);
        }
    }


    private static void collectMergeRules(Map<String, List<String>> rules, Map<ItemRule, String> replacements, Map<String, Set<String>> mergedTagsByTarget) {
        if (rules == null || rules.isEmpty()) return;
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            String target = cleanIdentifier(entry.getKey());
            if (target.isEmpty()) continue;
            ItemRule targetRule = new ItemRule(target);
            collectVirtualMergedTags(targetRule.baseId, target, mergedTagsByTarget);

            List<String> sources = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            for (String sourceRaw : sources) {
                String source = cleanIdentifier(sourceRaw);
                if (source.isEmpty()) continue;
                replacements.put(new ItemRule(source), target);
                collectVirtualMergedTags(targetRule.baseId, source, mergedTagsByTarget);
            }
        }
    }

    private static void collectVirtualMergedTags(String targetBaseId, String identifier, Map<String, Set<String>> mergedTagsByTarget) {
        if (targetBaseId == null || targetBaseId.isEmpty() || identifier == null || identifier.isEmpty()) return;
        Set<String> tags = getRegistryTagIdsForIdentifier(identifier);
        if (tags.isEmpty()) return;
        mergedTagsByTarget.computeIfAbsent(targetBaseId, key -> new TreeSet<>()).addAll(tags);
    }

    private static Set<String> getRegistryTagIdsForIdentifier(String identifier) {
        ItemStack stack = parseItemStack(identifier);
        if (stack == null || stack.isEmpty()) return Collections.emptySet();
        Set<String> result = new TreeSet<>();
        try {
            stack.getTags().forEach(tag -> {
                String clean = normalizeMergedTagId(tag.location().toString());
                if (!clean.isEmpty()) result.add(clean);
            });
        } catch (Exception ignored) {
        }
        return result;
    }

    public static void rebuildCache() {
        try {
            rebuildCacheUnsafe();
        } catch (Throwable e) {
            clearRuntimeCache();
            LOGGER.error("重建物品封禁运行缓存失败，已清空运行缓存，避免影响进服", e);
        }
    }

    private static void rebuildCacheUnsafe() {
        normalizeData();
        List<ItemRule> newBannedRules = new ArrayList<>();
        List<ItemRule> newOreGenerationBannedRules = new ArrayList<>();
        Map<ItemRule, String> newReplacementMap = new HashMap<>();
        Map<String, Set<String>> newTargetMergedTags = new HashMap<>();

        for (String banned : data.bannedItems) {
            ItemRule rule = new ItemRule(banned);
            newBannedRules.add(rule);
            newReplacementMap.put(rule, VOID_ID);
        }
        for (String bannedOre : data.bannedOreGenerations) {
            newOreGenerationBannedRules.add(new ItemRule(bannedOre));
        }
        collectMergeRules(data.mergedItems, newReplacementMap, newTargetMergedTags);

        ruleReplacementMap = newReplacementMap;
        Map<String, Set<String>> frozenTargetTags = new HashMap<>();
        newTargetMergedTags.forEach((k, v) -> frozenTargetTags.put(k, Collections.unmodifiableSet(new TreeSet<>(v))));
        targetMergedTags = Collections.unmodifiableMap(frozenTargetTags);

        Map<String, String> newPureIdReplacements = new HashMap<>();
        Map<String, List<Map.Entry<ItemRule, String>>> newNbtReplacements = new HashMap<>();
        List<Map.Entry<ItemRule, String>> newMacroReplacements = new ArrayList<>();

        for (Map.Entry<ItemRule, String> entry : newReplacementMap.entrySet()) {
            ItemRule rule = entry.getKey();
            if (rule.originalString.startsWith("@") || rule.originalString.startsWith("#")) {
                newMacroReplacements.add(entry);
            } else if (rule.hasNbt) {
                newNbtReplacements.computeIfAbsent(rule.baseId, k -> new ArrayList<>()).add(entry);
            } else {
                newPureIdReplacements.put(rule.baseId, entry.getValue());
            }
        }

        pureIdReplacements = newPureIdReplacements;
        nbtReplacements = newNbtReplacements;
        macroReplacements = newMacroReplacements;

        Map<String, String> newPureBlockReplacements = buildWorldgenBlockReplacementMap(data.oreMergedItems);
        Map<Block, BlockState> newReplacementBlockStates = buildBlockReplacementStateMap(newPureBlockReplacements);
        Map<Block, WeightedBlockReplacement> newWeightedBlockReplacements = buildWeightedBlockReplacementCache(data.weightedBlockReplacements, data.weightedBlockReplacementChances);
        Set<Block> newWorldgenMergeTargetBlocks = buildWorldgenMergeTargetBlocks(data.oreMergedItems);
        newWorldgenMergeTargetBlocks.addAll(buildWeightedTargetBlocks(data.weightedBlockReplacements));
        Set<String> newWorldgenMergeTargetIds = buildWorldgenMergeTargetIds(data.oreMergedItems);
        newWorldgenMergeTargetIds.addAll(buildWeightedTargetIds(data.weightedBlockReplacements));
        Set<Block> newWorldgenBannedBlocks = buildWorldgenBannedBlocks(newOreGenerationBannedRules, newWorldgenMergeTargetBlocks);
        Map<BlockState, BlockState> newFastPlainStateRewrites = buildFastPlainReplacementStateCache(newReplacementBlockStates);
        Map<BlockState, BlockState> newFastStateRewrites = buildFastWorldgenStateRewriteCache(newWorldgenBannedBlocks, newFastPlainStateRewrites);

        Map<Block, Integer> newFixedReplacementChances = buildFixedReplacementChanceCache(
                data.oreMergedItems,
                data.blockReplacementChances
        );

        pureBlockReplacements = Collections.unmodifiableMap(newPureBlockReplacements);
        worldgenFixedReplacementChances = Collections.unmodifiableMap(newFixedReplacementChances);
        worldgenReplacementBlockStates = Collections.unmodifiableMap(newReplacementBlockStates);
        worldgenFastPlainStateRewrites = Collections.unmodifiableMap(newFastPlainStateRewrites);
        worldgenFastStateRewrites = Collections.unmodifiableMap(newFastStateRewrites);
        worldgenWeightedBlockReplacements = Collections.unmodifiableMap(newWeightedBlockReplacements);
        worldgenMergeTargetBlocks = Collections.unmodifiableSet(newWorldgenMergeTargetBlocks);
        worldgenMergeTargetIds = Collections.unmodifiableSet(newWorldgenMergeTargetIds);
        worldgenBannedBlocks = Collections.unmodifiableSet(newWorldgenBannedBlocks);

        Set<String> newPureIdBanned = new HashSet<>();
        Map<String, List<ItemRule>> newNbtBanned = new HashMap<>();
        List<ItemRule> newMacroBanned = new ArrayList<>();

        for (ItemRule rule : newBannedRules) {
            if (rule.originalString.startsWith("@") || rule.originalString.startsWith("#")) {
                newMacroBanned.add(rule);
            } else if (rule.hasNbt) {
                newNbtBanned.computeIfAbsent(rule.baseId, k -> new ArrayList<>()).add(rule);
            } else {
                newPureIdBanned.add(rule.baseId);
            }
        }

        pureIdBanned = newPureIdBanned;
        nbtBanned = newNbtBanned;
        macroBanned = newMacroBanned;
        oreGenerationBannedRules = Collections.unmodifiableList(newOreGenerationBannedRules);
        hasOreGenerationBanRules = !worldgenBannedBlocks.isEmpty();
        hasRawWorldgenRewriteRules = !worldgenFastStateRewrites.isEmpty() || !worldgenWeightedBlockReplacements.isEmpty();
        hasBlockReplacementRules = !worldgenFastPlainStateRewrites.isEmpty() || !worldgenWeightedBlockReplacements.isEmpty();

    }

    private static void clearRuntimeCache() {
        ruleReplacementMap = Collections.emptyMap();
        pureIdReplacements = Collections.emptyMap();
        nbtReplacements = Collections.emptyMap();
        macroReplacements = Collections.emptyList();
        targetMergedTags = Collections.emptyMap();
        pureBlockReplacements = Collections.emptyMap();
        worldgenBannedBlocks = Collections.emptySet();
        worldgenReplacementBlockStates = Collections.emptyMap();
        worldgenFastPlainStateRewrites = Collections.emptyMap();
        worldgenFastStateRewrites = Collections.emptyMap();
        worldgenFixedReplacementChances = Collections.emptyMap();
        worldgenWeightedBlockReplacements = Collections.emptyMap();
        worldgenMergeTargetBlocks = Collections.emptySet();
        worldgenMergeTargetIds = Collections.emptySet();
        pureIdBanned = Collections.emptySet();
        nbtBanned = Collections.emptyMap();
        macroBanned = Collections.emptyList();
        oreGenerationBannedRules = Collections.emptyList();
        hasOreGenerationBanRules = false;
        hasRawWorldgenRewriteRules = false;
        hasBlockReplacementRules = false;
    }


    private static Map<String, String> buildWorldgenBlockReplacementMap(Map<String, List<String>> rules) {
        Map<String, String> result = new HashMap<>();
        if (rules == null || rules.isEmpty()) return result;
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            String target = getBaseIdentifier(entry.getKey());
            if (target.isEmpty()) continue;
            Block targetBlock = getBlockById(target);
            if (targetBlock == null || targetBlock == Blocks.AIR) continue;
            List<String> sources = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            for (String sourceIdentifier : sources) {
                String source = getBaseIdentifier(sourceIdentifier);
                if (source.isEmpty() || source.equals(target)) continue;
                Block sourceBlock = getBlockById(source);
                if (sourceBlock == null || sourceBlock == Blocks.AIR || sourceBlock == targetBlock) continue;
                result.put(source, target);
            }
        }
        return result;
    }

    private static Set<Block> buildWorldgenMergeTargetBlocks(Map<String, List<String>> rules) {
        Set<Block> result = newIdentityBlockSet();
        if (rules == null || rules.isEmpty()) return result;
        for (String targetIdentifier : rules.keySet()) {
            String target = getBaseIdentifier(targetIdentifier);
            if (target.isEmpty()) continue;
            Block block = getBlockById(target);
            if (block != null && block != Blocks.AIR) result.add(block);
        }
        return result;
    }

    private static Set<String> buildWorldgenMergeTargetIds(Map<String, List<String>> rules) {
        Set<String> result = new HashSet<>();
        if (rules == null || rules.isEmpty()) return result;
        for (String targetIdentifier : rules.keySet()) {
            String target = getBaseIdentifier(targetIdentifier);
            if (!target.isEmpty() && getBlockById(target) != null) result.add(target);
        }
        return result;
    }

    private static Map<Block, WeightedBlockReplacement> buildWeightedBlockReplacementCache(
            Map<String, List<WeightedBlockTarget>> rules,
            Map<String, Integer> chances
    ) {
        Map<Block, WeightedBlockReplacement> result = new IdentityHashMap<>();
        if (rules == null || rules.isEmpty()) return result;

        for (Map.Entry<String, List<WeightedBlockTarget>> entry : rules.entrySet()) {
            String sourceId = normalizeBlockIdentifier(entry.getKey());
            if (sourceId.isEmpty()) continue;
            Block sourceBlock = getBlockById(sourceId);
            if (sourceBlock == null || sourceBlock == Blocks.AIR) continue;

            List<BlockState> states = new ArrayList<>();
            List<Integer> cumulative = new ArrayList<>();
            int totalWeight = 0;
            List<WeightedBlockTarget> targets = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            for (WeightedBlockTarget target : targets) {
                if (target == null) continue;
                String targetId = normalizeBlockIdentifier(target.target);
                Block targetBlock = getBlockById(targetId);
                if (targetBlock == null || targetBlock == Blocks.AIR || targetBlock == sourceBlock) continue;
                int weight = Math.max(1, Math.min(1_000_000, target.weight));
                if ((long) totalWeight + weight > Integer.MAX_VALUE) break;
                totalWeight += weight;
                states.add(targetBlock.defaultBlockState());
                cumulative.add(totalWeight);
            }

            if (states.isEmpty() || totalWeight <= 0) continue;
            BlockState[] stateArray = states.toArray(BlockState[]::new);
            int[] cumulativeArray = new int[cumulative.size()];
            for (int i = 0; i < cumulative.size(); i++) cumulativeArray[i] = cumulative.get(i);
            int replacementChance = chances == null ? 100 : chances.getOrDefault(sourceId, 100);
            result.put(sourceBlock, new WeightedBlockReplacement(sourceBlock, stateArray, cumulativeArray, totalWeight, replacementChance));
        }

        return result;
    }

    private static Map<Block, Integer> buildFixedReplacementChanceCache(
            Map<String, List<String>> rules,
            Map<String, Integer> chances
    ) {
        Map<Block, Integer> result = new IdentityHashMap<>();
        if (rules == null || rules.isEmpty()) return result;
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            String targetId = getBaseIdentifier(entry.getKey());
            int chance = chances == null ? 100 : chances.getOrDefault(targetId, 100);
            List<String> sources = entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            for (String sourceIdentifier : sources) {
                Block sourceBlock = getBlockById(getBaseIdentifier(sourceIdentifier));
                if (sourceBlock != null && sourceBlock != Blocks.AIR) result.put(sourceBlock, clampChance(chance));
            }
        }
        return result;
    }

    private static Set<Block> buildWeightedTargetBlocks(Map<String, List<WeightedBlockTarget>> rules) {
        Set<Block> result = newIdentityBlockSet();
        if (rules == null || rules.isEmpty()) return result;
        for (List<WeightedBlockTarget> values : rules.values()) {
            if (values == null) continue;
            for (WeightedBlockTarget value : values) {
                if (value == null) continue;
                Block block = getBlockById(getBaseIdentifier(value.target));
                if (block != null && block != Blocks.AIR) result.add(block);
            }
        }
        return result;
    }

    private static Set<String> buildWeightedTargetIds(Map<String, List<WeightedBlockTarget>> rules) {
        Set<String> result = new HashSet<>();
        if (rules == null || rules.isEmpty()) return result;
        for (List<WeightedBlockTarget> values : rules.values()) {
            if (values == null) continue;
            for (WeightedBlockTarget value : values) {
                if (value == null) continue;
                String id = getBaseIdentifier(value.target);
                if (!id.isEmpty() && getBlockById(id) != null) result.add(id);
            }
        }
        return result;
    }

    private static Block getBlockById(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<Block, BlockState> buildBlockReplacementStateMap(Map<String, String> blockReplacements) {
        Map<Block, BlockState> result = new IdentityHashMap<>();
        if (blockReplacements == null || blockReplacements.isEmpty()) return result;
        for (Map.Entry<String, String> entry : blockReplacements.entrySet()) {
            try {
                Block sourceBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(entry.getKey()));
                Block targetBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(entry.getValue()));
                if (sourceBlock == null || targetBlock == null) continue;
                if (sourceBlock == Blocks.AIR || targetBlock == Blocks.AIR || sourceBlock == targetBlock) continue;
                result.put(sourceBlock, targetBlock.defaultBlockState());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static Map<BlockState, BlockState> buildFastPlainReplacementStateCache(Map<Block, BlockState> replacementStates) {
        IdentityHashMap<BlockState, BlockState> result = new IdentityHashMap<>();
        if (replacementStates == null || replacementStates.isEmpty()) return result;

        for (Map.Entry<Block, BlockState> entry : replacementStates.entrySet()) {
            Block sourceBlock = entry.getKey();
            BlockState targetDefaultState = entry.getValue();
            for (BlockState sourceState : sourceBlock.getStateDefinition().getPossibleStates()) {
                result.put(sourceState, copySharedProperties(sourceState, targetDefaultState));
            }
        }
        return result;
    }

    private static Map<BlockState, BlockState> buildFastWorldgenStateRewriteCache(
            Set<Block> bannedBlocks,
            Map<BlockState, BlockState> plainStateRewrites
    ) {
        IdentityHashMap<BlockState, BlockState> result = new IdentityHashMap<>();
        if (plainStateRewrites != null && !plainStateRewrites.isEmpty()) result.putAll(plainStateRewrites);

        if (bannedBlocks != null && !bannedBlocks.isEmpty()) {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (Block block : bannedBlocks) {
                for (BlockState sourceState : block.getStateDefinition().getPossibleStates()) {
                    result.put(sourceState, air);
                }
            }
        }
        return result;
    }

    private static Set<Block> newIdentityBlockSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<Block> buildWorldgenBannedBlocks(List<ItemRule> rules, Set<Block> protectedTargets) {
        if (rules == null || rules.isEmpty()) return Collections.emptySet();
        Set<Block> result = newIdentityBlockSet();
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            if (block == null || block == Blocks.AIR) continue;
            if (protectedTargets != null && protectedTargets.contains(block)) continue;
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            if (blockId == null) continue;
            BlockState defaultState = block.defaultBlockState();
            Item item = block.asItem();
            ItemStack stack = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
            String blockIdStr = blockId.toString();
            for (ItemRule rule : rules) {
                if (matchesOreGenerationRule(rule, defaultState, stack, blockIdStr)) {
                    result.add(block);
                    break;
                }
            }
        }
        return result;
    }

    public static BlockState getWeightedBlockReplacementState(BlockState state) {
        if (state == null || state.isAir()) return state;
        WeightedBlockReplacement weighted = worldgenWeightedBlockReplacements.get(state.getBlock());
        return weighted == null ? state : weighted.resolveState(state);
    }

    public static BlockState getReplacementBlockState(BlockState state) {
        if (state == null || state.isAir()) return state;
        BlockState weighted = getWeightedBlockReplacementState(state);
        if (weighted != state) return weighted;
        BlockState target = worldgenFastPlainStateRewrites.get(state);
        if (target == null) return state;
        int chance = worldgenFixedReplacementChances.getOrDefault(state.getBlock(), 100);
        return rollReplacementChance(chance) ? target : state;
    }

    public static BlockState getRawWorldgenReplacementBlockState(BlockState state) {
        return getReplacementBlockState(state);
    }

    public static BlockState getFastWorldgenRewriteState(BlockState state) {
        if (state == null) return null;
        WeightedBlockReplacement weighted = worldgenWeightedBlockReplacements.get(state.getBlock());
        if (weighted != null) return weighted.resolveState(state);
        BlockState fixed = worldgenFastPlainStateRewrites.get(state);
        if (fixed != null) {
            int chance = worldgenFixedReplacementChances.getOrDefault(state.getBlock(), 100);
            return rollReplacementChance(chance) ? fixed : state;
        }
        return worldgenBannedBlocks.contains(state.getBlock()) ? Blocks.AIR.defaultBlockState() : null;
    }

    private static BlockState copySharedProperties(BlockState from, BlockState to) {
        BlockState result = to;
        for (Property<?> sourceProperty : from.getProperties()) {
            Property<?> targetProperty = findCompatibleProperty(result, sourceProperty);
            if (targetProperty != null) {
                result = copyPropertyByName(from, result, sourceProperty, targetProperty);
            }
        }
        return result;
    }

    private static Property<?> findCompatibleProperty(BlockState targetState, Property<?> sourceProperty) {
        for (Property<?> targetProperty : targetState.getProperties()) {
            if (targetProperty.getName().equals(sourceProperty.getName())) return targetProperty;
        }
        return null;
    }

    private static <S extends Comparable<S>, T extends Comparable<T>> BlockState copyPropertyByName(BlockState from, BlockState to, Property<S> sourceProperty, Property<T> targetProperty) {
        S value = from.getValue(sourceProperty);
        for (T candidate : targetProperty.getPossibleValues()) {
            if (candidate.toString().equals(value.toString())) {
                return to.setValue(targetProperty, candidate);
            }
        }
        return to;
    }


    public static boolean isWorldgenMergeTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) return false;
        return worldgenMergeTargetBlocks.contains(blockItem.getBlock());
    }

    public static boolean isWorldgenMergeTargetIdentifier(String identifier) {
        String baseId = getBaseIdentifier(identifier);
        return !baseId.isEmpty() && worldgenMergeTargetIds.contains(baseId);
    }

    public static boolean wouldOreGenerationRuleBanWorldgenMergeTarget(String ruleString) {
        if (ruleString == null || ruleString.isBlank() || worldgenMergeTargetBlocks.isEmpty()) return false;
        ItemRule rule = new ItemRule(ruleString.trim().toLowerCase(Locale.ROOT));
        for (Block block : worldgenMergeTargetBlocks) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
            if (blockId == null) continue;
            Item item = block.asItem();
            ItemStack stack = item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
            if (matchesOreGenerationRule(rule, block.defaultBlockState(), stack, blockId.toString())) return true;
        }
        return false;
    }

    public static boolean isOreGenerationBanned(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (isWorldgenMergeTarget(stack)) return false;
        String itemId = getItemIdFromCache(stack.getItem());
        if (itemId.isEmpty()) return false;
        BlockState blockState = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().defaultBlockState() : null;
        for (ItemRule rule : oreGenerationBannedRules) {
            if (rule.matchesWithId(stack, itemId)) return true;
            if (blockState != null && matchesOreGenerationRule(rule, blockState, stack, itemId)) return true;
        }
        return false;
    }

    public static boolean isOreGenerationBannedBlockState(BlockState state) {
        return state != null && !state.isAir() && worldgenBannedBlocks.contains(state.getBlock());
    }

    private static boolean matchesOreGenerationRule(ItemRule rule, BlockState state, ItemStack stack, String blockIdStr) {
        if (rule == null || blockIdStr == null || blockIdStr.isEmpty()) return false;
        if (rule.originalString.startsWith("@")) {
            int colonIdx = blockIdStr.indexOf(':');
            String namespace = colonIdx == -1 ? blockIdStr : blockIdStr.substring(0, colonIdx);
            return namespace.equals(rule.originalString.substring(1));
        }
        if (rule.originalString.startsWith("#")) {
            String targetTag = rule.originalString.substring(1);
            boolean itemMatches = stack != null && !stack.isEmpty() && stack.getTags().anyMatch(t -> t.location().toString().toLowerCase(Locale.ROOT).equals(targetTag));
            if (itemMatches) return true;
            try {
                return state.is(BlockTags.create(new ResourceLocation(targetTag)));
            } catch (Exception ignored) {
                return false;
            }
        }
        if (!blockIdStr.equals(rule.baseId)) return false;
        if (!rule.hasNbt) return true;
        return stack != null && !stack.isEmpty() && rule.matchesWithId(stack, blockIdStr);
    }


    public static boolean hasWorldgenBlockRules() {
        return hasRawWorldgenRewriteRules;
    }

    public static String getReplacement(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String itemId = getItemIdFromCache(stack.getItem());
        if (itemId.isEmpty()) return null;

        List<Map.Entry<ItemRule, String>> nbtRules = nbtReplacements.get(itemId);
        if (nbtRules != null) {
            for (Map.Entry<ItemRule, String> entry : nbtRules) {
                if (entry.getKey().matchesWithId(stack, itemId)) return entry.getValue();
            }
        }

        String pureTarget = pureIdReplacements.get(itemId);
        if (pureTarget != null) {
            return pureTarget;
        }

        for (Map.Entry<ItemRule, String> entry : macroReplacements) {
            if (entry.getKey().matchesWithId(stack, itemId)) return entry.getValue();
        }

        return null;
    }

    public static String getReplacement(String id) {
        return pureIdReplacements.get(id);
    }

    public static String getItemIdentifier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return "";
        String base = id.toString();
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) return base;
        String compact = compactKnownNbtIdentifier(base, tag);
        if (!compact.isEmpty()) return compact;
        return base + tag;
    }

    public static ItemStack parseItemStack(String identifier) {
        String clean = normalizeRuleIdentifier(identifier);
        if (clean.isEmpty() || clean.startsWith("@") || clean.startsWith("#")) return ItemStack.EMPTY;
        final ItemStack[] result = {ItemStack.EMPTY};

        dev.xyat.realmcontrol.worldblock.util.ItemBanControl.withSkip(() -> {
            try {
                int bracket = clean.indexOf('{');
                if (bracket == -1) {
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(clean));
                    if (item != null) result[0] = new ItemStack(item);
                } else {
                    String id = clean.substring(0, bracket);
                    String nbt = clean.substring(bracket);
                    Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                    if (item != null) {
                        CompoundTag tag = safeParseTagForRule(clean, nbt);
                        if (tag != null) {
                            ItemStack stack = new ItemStack(item);
                            stack.setTag(tag);
                            result[0] = stack;
                        }
                    }
                }
            } catch (Throwable e) {
                logLimitedWarn("解析物品失败，identifier=" + identifier + ", clean=" + clean, e);
            }
            return null;
        });

        return result[0];
    }
}
