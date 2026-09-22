package dev.xyat.realmcontrol.worldblock.client.gui;

import dev.xyat.kineticcore.api.client.search.KineticSearch;
import dev.xyat.kineticcore.api.client.search.KineticItemSearch;
import dev.xyat.kineticcore.api.runtime.KineticClientRuntime;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.util.ItemBanControl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.xyat.kineticcore.api.registry.KineticRegistries;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

public final class ItemSearchCache {
    private static final long TTL_MS = 15L * 60L * 1000L;
    private static final int MAX_ITEM_SEARCH_CACHE = 256;
    private static final int MAX_STRING_SEARCH_CACHE = 128;
    private static final int MAX_ID_SEARCH_DATA_CACHE = 512;
    private static final Object SNAPSHOT_LOCK = new Object();
    private static volatile Snapshot snapshot = Snapshot.empty();
    private static volatile long bannedSourceVersion = -1L;
    private static volatile List<KineticItemSearch.CachedItem> bannedSourceCache = Collections.emptyList();

    private static final AtomicLong RULES_VERSION = new AtomicLong(0L);
    private static final ConcurrentHashMap<SearchKey, CacheEntry<List<KineticItemSearch.CachedItem>>> ITEM_SEARCH_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<SearchKey, CacheEntry<List<String>>> STRING_SEARCH_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ID_SEARCH_DATA_CACHE = new ConcurrentHashMap<>();

    private ItemSearchCache() {
    }

    public static void prepareCache(Runnable afterReady) {
        KineticItemSearch.prepare(() -> {
            buildIfNeeded();
            if (afterReady != null) afterReady.run();
        });
    }

    public static List<KineticItemSearch.CachedItem> getAllItems() {
        return buildIfNeeded().allItems();
    }

    public static int getAllItemsHash() {
        return buildIfNeeded().allItemsHash();
    }

    public static List<String> getAllMods() {
        return buildIfNeeded().allMods();
    }

    public static List<String> getAllTags() {
        return buildIfNeeded().allTags();
    }

    public static Set<String> getRegistryTagIdsForId(String idStr) {
        if (idStr == null || idStr.isEmpty()) return Collections.emptySet();
        Snapshot current = buildIfNeeded();
        Set<String> cached = current.tagsById().get(idStr);
        if (cached != null) return cached;
        String baseId = getBaseIdentifier(idStr);
        cached = current.tagsById().get(baseId);
        if (cached != null) return cached;

        ItemStack stack = WorldBlockConfig.parseItemStack(idStr);
        if (stack == null || stack.isEmpty()) return Collections.emptySet();
        return getRegistryTagIdsForStack(stack);
    }

    public static Set<String> getRegistryTagIdsForStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Collections.emptySet();
        Set<String> result = new TreeSet<>();
        stack.getTags().forEach(tagKey -> {
            ResourceLocation location = tagKey.location();
            if (!location.getNamespace().equals("realmcontrol")) {
                result.add(location.toString().toLowerCase(Locale.ROOT));
            }
        });
        if (result.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(result);
    }

    private static String getBaseIdentifier(String idStr) {
        if (idStr == null) return "";
        String clean = idStr.trim().toLowerCase(Locale.ROOT);
        int bracket = clean.indexOf('{');
        return bracket < 0 ? clean : clean.substring(0, bracket);
    }

    public static Set<String> getUnificationTagIdsForId(String idStr) {
        return ItemUnificationHelper.getTagIdsForIdentifier(idStr);
    }

    public static Set<ItemUnificationHelper.MergeGroup> getUnificationGroupsForId(String idStr) {
        return ItemUnificationHelper.getGroupsForIdentifier(idStr);
    }

    public static boolean hasAnyUnificationGroup(ItemStack stack, Set<ItemUnificationHelper.MergeGroup> targetGroups) {
        return ItemUnificationHelper.matchesAnyGroup(stack, targetGroups);
    }

    public static Set<String> getMergedUnificationTagIds(String targetId, Iterable<String> sources) {
        Set<String> tagIds = new TreeSet<>(getUnificationTagIdsForId(targetId));
        if (sources != null) {
            for (String source : sources) tagIds.addAll(getUnificationTagIdsForId(source));
        }
        if (tagIds.isEmpty()) return Collections.emptySet();
        return Collections.unmodifiableSet(tagIds);
    }

    public static String getSearchDataForId(String idStr) {
        if (idStr == null || idStr.isEmpty()) return "";
        Snapshot current = buildIfNeeded();
        String cached = current.searchDataById().get(idStr);
        if (cached != null) return cached;
        if (ID_SEARCH_DATA_CACHE.size() > MAX_ID_SEARCH_DATA_CACHE) ID_SEARCH_DATA_CACHE.clear();
        return ID_SEARCH_DATA_CACHE.computeIfAbsent(idStr, ItemSearchCache::buildSearchDataForUnknownId);
    }

    public static List<KineticItemSearch.CachedItem> getInventoryItems() {
        List<KineticItemSearch.CachedItem> list = new ArrayList<>();
        Player player = KineticClientRuntime.localPlayer();
        if (player != null) {
            ItemBanControl.withSkip(() -> {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                    ItemStack stack = player.getInventory().getItem(i);
                    if (!stack.isEmpty()) {
                        String id = WorldBlockConfig.getItemIdentifier(stack);
                        if (seen.add(id)) list.add(KineticItemSearch.customSnapshot(stack.copy(), id));
                    }
                }
                return null;
            });
        }
        return list;
    }

    public static List<KineticItemSearch.CachedItem> getBannedSourceList() {
        long version = RULES_VERSION.get();
        List<KineticItemSearch.CachedItem> current = bannedSourceCache;
        if (bannedSourceVersion == version) return current;

        synchronized (SNAPSHOT_LOCK) {
            if (bannedSourceVersion == version) return bannedSourceCache;

            List<KineticItemSearch.CachedItem> result = new ArrayList<>();
            Set<String> addedRules = new HashSet<>();

            for (String rule : WorldBlockConfig.data.bannedItems) {
                if (rule == null || rule.isBlank()) continue;
                if (rule.startsWith("@")) {
                    result.add(KineticItemSearch.customSnapshot(new ItemStack(Items.COMMAND_BLOCK), rule));
                    addedRules.add(rule);
                } else if (rule.startsWith("#")) {
                    result.add(KineticItemSearch.customSnapshot(new ItemStack(Items.NAME_TAG), rule));
                    addedRules.add(rule);
                } else if (addedRules.add(rule)) {
                    KineticItemSearch.CachedItem cached = buildBannedRuleCachedItem(rule);
                    result.add(cached);
                }
            }

            for (KineticItemSearch.CachedItem c : buildIfNeeded().allItems()) {
                String identifier = cachedIdentifier(c);
                if (!identifier.isBlank() && WorldBlockConfig.isBanned(c.stack()) && addedRules.add(identifier)) {
                    result.add(c);
                }
            }

            bannedSourceCache = Collections.unmodifiableList(result);
            bannedSourceVersion = version;
            return bannedSourceCache;
        }
    }

    public static List<KineticItemSearch.CachedItem> searchItems(String scope, List<KineticItemSearch.CachedItem> source, String query, int sourceHash) {
        return searchItems(scope, source, query, c -> true, sourceHash);
    }

    public static List<KineticItemSearch.CachedItem> searchItems(String scope, List<KineticItemSearch.CachedItem> source, String query, Predicate<KineticItemSearch.CachedItem> filter, int sourceHash) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        String q = normalize(query);
        long version = RULES_VERSION.get();
        SearchKey key = new SearchKey(scope, q, sourceHash, version);
        long now = System.currentTimeMillis();
        CacheEntry<List<KineticItemSearch.CachedItem>> cached = ITEM_SEARCH_CACHE.get(key);
        if (cached != null && cached.isAlive(now)) {
            cached.touch(now);
            return cached.value;
        }

        List<KineticItemSearch.CachedItem> result = new ArrayList<>();
        if (q.isEmpty()) {
            for (KineticItemSearch.CachedItem item : source) {
                if (filter.test(item)) result.add(item);
            }
        } else {
            for (KineticItemSearch.CachedItem item : source) {
                if (filter.test(item) && KineticSearch.match(item.searchText(), q)) result.add(item);
            }
        }
        List<KineticItemSearch.CachedItem> safe = Collections.unmodifiableList(result);
        ITEM_SEARCH_CACHE.put(key, new CacheEntry<>(safe, now));
        trimItemSearchCache(now);
        return safe;
    }

    public static List<String> searchStrings(String scope, List<String> source, String query) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        String q = normalize(query);
        if (q.isEmpty()) return source;

        SearchKey key = new SearchKey(scope, q, hashStrings(source), RULES_VERSION.get());
        long now = System.currentTimeMillis();
        CacheEntry<List<String>> cached = STRING_SEARCH_CACHE.get(key);
        if (cached != null && cached.isAlive(now)) {
            cached.touch(now);
            return cached.value;
        }

        List<String> result = new ArrayList<>();
        for (String value : source) {
            if (KineticSearch.match(value, q)) result.add(value);
        }
        List<String> safe = Collections.unmodifiableList(result);
        STRING_SEARCH_CACHE.put(key, new CacheEntry<>(safe, now));
        trimStringSearchCache(now);
        return safe;
    }

    public static int hashCachedItems(List<KineticItemSearch.CachedItem> items) {
        if (items == null || items.isEmpty()) return 0;
        Snapshot current = snapshot;
        if (items == current.allItems()) return current.allItemsHash();
        int hash = 1;
        for (KineticItemSearch.CachedItem item : items) {
            hash = 31 * hash + cachedIdentifier(item).hashCode();
        }
        return hash;
    }

    public static int hashStrings(Iterable<String> strings) {
        if (strings == null) return 0;
        int hash = 0;
        int count = 0;
        for (String value : strings) {
            if (value != null) {
                hash += value.hashCode();
                count++;
            }
        }
        return 31 * hash + count;
    }

    public static void markRulesChanged() {
        RULES_VERSION.incrementAndGet();
        bannedSourceVersion = -1L;
        bannedSourceCache = Collections.emptyList();
        ITEM_SEARCH_CACHE.clear();
        STRING_SEARCH_CACHE.clear();
        ID_SEARCH_DATA_CACHE.clear();
    }

    public static void clear() {
        synchronized (SNAPSHOT_LOCK) {
            snapshot = Snapshot.empty();
            bannedSourceVersion = -1L;
            bannedSourceCache = Collections.emptyList();
            ITEM_SEARCH_CACHE.clear();
            STRING_SEARCH_CACHE.clear();
            ID_SEARCH_DATA_CACHE.clear();
            RULES_VERSION.incrementAndGet();
        }
    }

    private static Snapshot buildIfNeeded() {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot;
        if (current.isAlive(now)) return current;

        synchronized (SNAPSHOT_LOCK) {
            current = snapshot;
            if (current.isAlive(now)) return current;

            List<KineticItemSearch.CachedItem> items = buildRawItemSnapshot();
            Map<String, String> searchDataById = new HashMap<>();
            Map<String, Set<String>> tagsById = new HashMap<>();
            for (KineticItemSearch.CachedItem item : items) {
                searchDataById.putIfAbsent(cachedIdentifier(item), item.searchText());
                searchDataById.putIfAbsent(item.id(), item.searchText());
                tagsById.putIfAbsent(cachedIdentifier(item), getRegistryTagIdsForStack(item.stack()));
                tagsById.putIfAbsent(item.id(), getRegistryTagIdsForStack(item.stack()));
            }

            List<String> mods = new ArrayList<>();
            Set<String> modSeen = new HashSet<>();
            for (Item item : KineticRegistries.items().values()) {
                ResourceLocation rl = KineticRegistries.items().id(item);
                if (rl != null && !rl.getNamespace().equals("realmcontrol")) {
                    String mod = "@" + rl.getNamespace();
                    if (modSeen.add(mod)) mods.add(mod);
                }
            }
            mods.sort(String::compareTo);

            List<String> tags = new ArrayList<>();
            for (ResourceLocation loc : KineticRegistries.items().tagIds()) {
                if (!loc.getNamespace().equals("realmcontrol")) {
                    tags.add("#" + loc.toString().toLowerCase(Locale.ROOT));
                }
            }
            tags.sort(String::compareTo);

            Snapshot fresh = new Snapshot(
                    Collections.unmodifiableList(items),
                    Collections.unmodifiableMap(searchDataById),
                    Collections.unmodifiableMap(tagsById),
                    Collections.unmodifiableList(mods),
                    Collections.unmodifiableList(tags),
                    hashCachedItemIds(items),
                    now
            );
            snapshot = fresh;
            trimExpired(now);
            return fresh;
        }
    }

    private static List<KineticItemSearch.CachedItem> buildRawItemSnapshot() {
        List<KineticItemSearch.CachedItem> cached = KineticItemSearch.items();
        if (cached != null && !cached.isEmpty()) {
            List<KineticItemSearch.CachedItem> result = new ArrayList<>(cached.size());
            for (KineticItemSearch.CachedItem item : cached) {
                if (item == null || WorldBlockConfig.VOID_ID.equals(item.id())) continue;
                result.add(item);
            }
            return result;
        }

        List<KineticItemSearch.CachedItem> result = new ArrayList<>();
        ItemBanControl.withSkip(() -> {
            for (Item item : KineticRegistries.items().values()) {
                ResourceLocation rl = KineticRegistries.items().id(item);
                if (rl != null && !rl.getNamespace().equals("realmcontrol")) {
                    ItemStack stack = new ItemStack(item);
                    result.add(KineticItemSearch.snapshot(stack));
                }
            }
            return null;
        });
        return result;
    }

    private static String buildSearchDataForUnknownId(String idStr) {
        KineticItemSearch.CachedItem cached = buildBannedRuleCachedItem(idStr);
        if (cached.stack().isEmpty()) return idStr.toLowerCase(Locale.ROOT);
        return cached.searchText();
    }

    private static KineticItemSearch.CachedItem buildBannedRuleCachedItem(String idStr) {
        KineticItemSearch.CachedItem cached = buildOriginalCachedItem(idStr);
        if (cached != null && !cached.stack().isEmpty()) return cached;

        ItemStack fallback = buildBaseStackForIdentifier(idStr);
        if (!fallback.isEmpty()) return KineticItemSearch.customSnapshot(fallback, idStr);
        return KineticItemSearch.customSnapshot(new ItemStack(Items.BARRIER), idStr);
    }

    private static KineticItemSearch.CachedItem buildOriginalCachedItem(String idStr) {
        if (idStr == null || idStr.isEmpty()) return null;
        final KineticItemSearch.CachedItem[] result = new KineticItemSearch.CachedItem[1];
        ItemBanControl.withSkip(() -> {
            ItemStack stack = WorldBlockConfig.parseItemStack(idStr);
            if (stack != null && !stack.isEmpty()) {
                result[0] = KineticItemSearch.customSnapshot(stack, idStr);
            }
            return null;
        });
        return result[0];
    }

    private static ItemStack buildBaseStackForIdentifier(String idStr) {
        if (idStr == null || idStr.isBlank() || idStr.startsWith("@") || idStr.startsWith("#")) return ItemStack.EMPTY;
        try {
            String baseId = getBaseIdentifier(idStr);
            if (baseId.isBlank()) return ItemStack.EMPTY;
            Item item = KineticRegistries.items().get(new ResourceLocation(baseId));
            if (item == null || item == Items.AIR) return ItemStack.EMPTY;
            return new ItemStack(item);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    private static int hashCachedItemIds(List<KineticItemSearch.CachedItem> items) {
        int hash = 1;
        for (KineticItemSearch.CachedItem item : items) {
            hash = 31 * hash + cachedIdentifier(item).hashCode();
        }
        return hash;
    }

    private static String cachedIdentifier(KineticItemSearch.CachedItem item) {
        if (item == null) return "";
        if (item.id() != null && (item.id().startsWith("@") || item.id().startsWith("#") || item.id().contains("{"))) {
            return item.id();
        }
        ItemStack stack = item.stack();
        if (stack != null && !stack.isEmpty() && stack.hasTag()) {
            try {
                return WorldBlockConfig.getItemIdentifier(stack);
            } catch (Throwable ignored) {
            }
        }
        return item.id() == null ? "" : item.id();
    }

    private static void trimExpired(long now) {
        ITEM_SEARCH_CACHE.entrySet().removeIf(e -> !e.getValue().isAlive(now));
        STRING_SEARCH_CACHE.entrySet().removeIf(e -> !e.getValue().isAlive(now));
    }

    private static void trimItemSearchCache(long now) {
        trimMap(ITEM_SEARCH_CACHE, MAX_ITEM_SEARCH_CACHE, now);
    }

    private static void trimStringSearchCache(long now) {
        trimMap(STRING_SEARCH_CACHE, MAX_STRING_SEARCH_CACHE, now);
    }

    private static <T> void trimMap(ConcurrentHashMap<SearchKey, CacheEntry<T>> map, int maxSize, long now) {
        map.entrySet().removeIf(e -> !e.getValue().isAlive(now));
        if (map.size() <= maxSize) return;

        List<Map.Entry<SearchKey, CacheEntry<T>>> entries = new ArrayList<>(map.entrySet());
        entries.sort(Comparator.comparingLong(e -> e.getValue().lastAccess));
        int removeCount = Math.max(1, entries.size() - maxSize + maxSize / 4);
        for (int i = 0; i < removeCount && i < entries.size(); i++) {
            Map.Entry<SearchKey, CacheEntry<T>> entry = entries.get(i);
            map.remove(entry.getKey(), entry.getValue());
        }
    }

    private record Snapshot(List<KineticItemSearch.CachedItem> allItems, Map<String, String> searchDataById, Map<String, Set<String>> tagsById, List<String> allMods, List<String> allTags, int allItemsHash, long createdAt) {
        boolean isAlive(long now) {
            return !allItems.isEmpty() && now - createdAt <= TTL_MS;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), 0, 0L);
        }
    }

    private static final class SearchKey {
        final String scope;
        final String query;
        final int sourceHash;
        final long version;
        final int hash;

        SearchKey(String scope, String query, int sourceHash, long version) {
            this.scope = scope == null ? "" : scope;
            this.query = query == null ? "" : query;
            this.sourceHash = sourceHash;
            this.version = version;
            this.hash = Objects.hash(this.scope, this.query, this.sourceHash, this.version);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof SearchKey other)) return false;
            return sourceHash == other.sourceHash && version == other.version && scope.equals(other.scope) && query.equals(other.query);
        }
    }

    private static final class CacheEntry<T> {
        final T value;
        volatile long lastAccess;
        final long createdAt;

        CacheEntry(T value, long now) {
            this.value = value;
            this.lastAccess = now;
            this.createdAt = now;
        }

        void touch(long now) {
            this.lastAccess = now;
        }

        boolean isAlive(long now) {
            return now - lastAccess <= TTL_MS && now - createdAt <= TTL_MS;
        }
    }
}
