package dev.xyat.realmcontrol.worldblock.util;

import net.minecraft.world.item.ItemStack;

/** Version-specific item data behind the existing world-block rule syntax. */
public final class ItemRuleData {
    private ItemRuleData() {}

    public static int dataStart(String text) {
        int nbt = text.indexOf('{');
        //? if >=1.21 {
        /*int components = text.indexOf('[');
        return nbt < 0 ? components : components < 0 ? nbt : Math.min(nbt, components);
        *///?} else {
        return nbt;
        //?}
    }

    public static boolean hasData(ItemStack stack) {
        //? if >=1.21 {
        /*return !stack.getComponentsPatch().isEmpty();
        *///?} else {
        return stack.hasTag();
        //?}
    }

    //? if >=1.21 {
    /*public static boolean matches(String rule, ItemStack actual) {
        int start = dataStart(rule);
        if (start >= 0 && rule.charAt(start) == '{') return matchesLegacy(rule, start, actual);
        var reader = new com.mojang.brigadier.StringReader(rule);
        net.minecraft.commands.arguments.item.ItemParser.ItemResult expected;
        try {
            expected = new net.minecraft.commands.arguments.item.ItemParser(registries()).parse(reader);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        if (reader.canRead()) throw new IllegalArgumentException("Trailing item rule text");
        if (expected.item().value() != actual.getItem()) return false;
        // ItemStack normalizes its patch against the prototype; the parser retains explicit defaults/removals.
        for (var entry : expected.components().entrySet()) {
            Object value = actual.get(entry.getKey());
            if (entry.getValue().isEmpty()) {
                if (value != null) return false;
            } else if (entry.getValue().get() instanceof net.minecraft.world.item.component.CustomData data) {
                if (!(value instanceof net.minecraft.world.item.component.CustomData found)
                        || !net.minecraft.nbt.NbtUtils.compareNbt(data.copyTag(), found.copyTag(), true)) return false;
            } else if (!entry.getValue().get().equals(value)) return false;
        }
        return true;
    }

    private static boolean matchesLegacy(String rule, int start, ItemStack actual) {
        if (!rule.substring(0, start).equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(actual.getItem()).toString())) return false;
        net.minecraft.nbt.CompoundTag legacy;
        try {
            legacy = net.minecraft.nbt.TagParser.parseTag(rule.substring(start));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) {
            throw new IllegalArgumentException(invalid.getMessage(), invalid);
        }
        // The game's item upgrader drops zero damage/repair cost, false Unbreakable and partial enchantments.
        // These are filter constraints, so compare them before upgrading the remaining item data.
        if (legacy.contains("Damage") && !net.minecraft.nbt.NbtUtils.compareNbt(legacy.get("Damage"),
                net.minecraft.nbt.IntTag.valueOf(actual.getDamageValue()), true)) return false;
        if (legacy.contains("RepairCost") && !net.minecraft.nbt.NbtUtils.compareNbt(legacy.get("RepairCost"),
                net.minecraft.nbt.IntTag.valueOf(actual.getOrDefault(net.minecraft.core.component.DataComponents.REPAIR_COST, 0)), true)) return false;
        if (legacy.contains("Unbreakable") && !net.minecraft.nbt.NbtUtils.compareNbt(legacy.get("Unbreakable"),
                net.minecraft.nbt.ByteTag.valueOf(actual.has(net.minecraft.core.component.DataComponents.UNBREAKABLE)), true)) return false;
        if (!matchesEnchantments(legacy, "Enchantments", actual.get(net.minecraft.core.component.DataComponents.ENCHANTMENTS))
                || !matchesEnchantments(legacy, "StoredEnchantments", actual.get(
                net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS))) return false;
        for (String key : java.util.List.of("Damage", "RepairCost", "Unbreakable", "Enchantments", "StoredEnchantments")) {
            legacy.remove(key);
        }
        var stack = new net.minecraft.nbt.CompoundTag();
        stack.putString("id", rule.substring(0, start));
        stack.putByte("Count", (byte) 1);
        stack.put("tag", legacy);
        var upgraded = net.minecraft.util.datafix.DataFixers.getDataFixer().update(
                net.minecraft.util.datafix.fixes.References.ITEM_STACK,
                new com.mojang.serialization.Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, stack), 3465,
                net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
        if (!(upgraded instanceof net.minecraft.nbt.CompoundTag result)) return false;
        var components = dev.xyat.realmcontrol.util.Nbt.compound(result, "components");
        var lookup = registries();
        for (String key : dev.xyat.realmcontrol.util.Nbt.keys(components)) {
            var type = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(
                    dev.xyat.kineticcore.api.resource.KineticResourceIds.parse(key));
            if (type == null || !matchesEncoded(type, components.get(key), actual.get(type), lookup)) return false;
        }
        return true;
    }

    private static boolean matchesEnchantments(net.minecraft.nbt.CompoundTag rule, String key,
            net.minecraft.world.item.enchantment.ItemEnchantments actual) {
        if (!rule.contains(key)) return true;
        if (actual == null) return false;
        var legacy = new net.minecraft.nbt.ListTag();
        for (var entry : actual.entrySet()) {
            var id = entry.getKey().unwrapKey();
            if (id.isEmpty()) continue;
            var enchantment = new net.minecraft.nbt.CompoundTag();
            enchantment.putString("id", id.get().location().toString());
            enchantment.putShort("lvl", (short) entry.getIntValue());
            legacy.add(enchantment);
        }
        return net.minecraft.nbt.NbtUtils.compareNbt(rule.get(key), legacy, true);
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesEncoded(net.minecraft.core.component.DataComponentType<?> type,
            net.minecraft.nbt.Tag expected, Object actual, net.minecraft.core.HolderLookup.Provider lookup) {
        if (actual == null || type.codec() == null) return false;
        var codec = (com.mojang.serialization.Codec<Object>) type.codec();
        var ops = lookup.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        // Text codecs normalize JSON (including collapsing plain text). Normalize both sides identically.
        // Other component patterns remain raw so decoding does not insert defaults into partial filters.
        if (type == net.minecraft.core.component.DataComponents.CUSTOM_NAME
                || type == net.minecraft.core.component.DataComponents.ITEM_NAME
                || type == net.minecraft.core.component.DataComponents.LORE) {
            var decoded = codec.parse(ops, expected).result();
            if (decoded.isEmpty()) return false;
            var normalized = codec.encodeStart(ops, decoded.get()).result();
            if (normalized.isEmpty()) return false;
            expected = normalized.get();
        }
        var found = codec.encodeStart(ops, actual).result();
        return found.isPresent() && net.minecraft.nbt.NbtUtils.compareNbt(expected, found.get(), true);
    }

    private static net.minecraft.core.HolderLookup.Provider registries() {
        var server = dev.xyat.kineticcore.api.runtime.KineticServerRuntime.currentServer();
        if (server != null) return server.registryAccess();
        var levelRegistries = dev.xyat.kineticcore.api.runtime.KineticPlatform.callOnClient(() -> () -> {
            var level = dev.xyat.kineticcore.api.runtime.KineticClientRuntime.currentLevel();
            return level == null ? null : level.registryAccess();
        }, null);
        return levelRegistries != null ? levelRegistries : net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
                net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
    }

    public static String format(ItemStack stack) {
        var custom = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (custom != null && dev.xyat.realmcontrol.util.Nbt.hasString(custom.copyTag(), "GunId")) {
            String gunId = dev.xyat.realmcontrol.util.Nbt.string(custom.copyTag(), "GunId").trim();
            if (!gunId.isEmpty()) {
                var stable = new net.minecraft.nbt.CompoundTag();
                stable.putString("GunId", gunId);
                ItemStack identity = new ItemStack(stack.getItem());
                identity.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                        net.minecraft.world.item.component.CustomData.of(stable));
                return dev.xyat.kineticcore.api.inventory.KineticItemText.format(identity);
            }
        }
        return dev.xyat.kineticcore.api.inventory.KineticItemText.format(stack);
    }
    *///?}
}
