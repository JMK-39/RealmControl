package dev.xyat.realmcontrol.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

/**
 * NBT access that reads the same on every Minecraft version. 26.1 getters return Optional, lists lost their element
 * type and UUIDs go through a codec; the stored data is the same.
 */
public final class Nbt {
    private static final int TAG_STRING = 8;
    private static final int TAG_COMPOUND = 10;

    private Nbt() {
    }

    //? if >=26.1 {
    /*public static Set<String> keys(CompoundTag tag) { return tag.keySet(); }
    public static CompoundTag compound(CompoundTag tag, String key) { return tag.getCompoundOrEmpty(key); }
    public static ListTag compoundList(CompoundTag tag, String key) { return tag.getListOrEmpty(key); }
    public static CompoundTag compoundAt(ListTag list, int index) { return list.getCompoundOrEmpty(index); }
    public static long longValue(CompoundTag tag, String key) { return tag.getLongOr(key, 0L); }
    public static int intValue(CompoundTag tag, String key) { return tag.getIntOr(key, 0); }
    public static String string(CompoundTag tag, String key) { return tag.getStringOr(key, ""); }
    public static long[] longArray(CompoundTag tag, String key) { return tag.getLongArray(key).orElse(new long[0]); }
    public static int[] intArray(CompoundTag tag, String key) { return tag.getIntArray(key).orElse(new int[0]); }
    public static boolean hasString(CompoundTag tag, String key) { return tag.get(key) instanceof net.minecraft.nbt.StringTag; }
    public static boolean hasCompound(CompoundTag tag, String key) { return tag.get(key) instanceof CompoundTag; }
    public static @Nullable UUID uuid(CompoundTag tag, String key) { return tag.read(key, net.minecraft.core.UUIDUtil.CODEC).orElse(null); }
    public static void putUuid(CompoundTag tag, String key, UUID value) { tag.store(key, net.minecraft.core.UUIDUtil.CODEC, value); }
    *///?} else {
    public static Set<String> keys(CompoundTag tag) { return tag.getAllKeys(); }
    public static CompoundTag compound(CompoundTag tag, String key) { return tag.getCompound(key); }
    public static ListTag compoundList(CompoundTag tag, String key) { return tag.getList(key, TAG_COMPOUND); }
    public static CompoundTag compoundAt(ListTag list, int index) { return list.getCompound(index); }
    public static long longValue(CompoundTag tag, String key) { return tag.getLong(key); }
    public static int intValue(CompoundTag tag, String key) { return tag.getInt(key); }
    public static String string(CompoundTag tag, String key) { return tag.getString(key); }
    public static long[] longArray(CompoundTag tag, String key) { return tag.getLongArray(key); }
    public static int[] intArray(CompoundTag tag, String key) { return tag.getIntArray(key); }
    public static boolean hasString(CompoundTag tag, String key) { return tag.contains(key, TAG_STRING); }
    public static boolean hasCompound(CompoundTag tag, String key) { return tag.contains(key, TAG_COMPOUND); }
    public static @Nullable UUID uuid(CompoundTag tag, String key) { return tag.hasUUID(key) ? tag.getUUID(key) : null; }
    public static void putUuid(CompoundTag tag, String key, UUID value) { tag.putUUID(key, value); }
    //?}
}
