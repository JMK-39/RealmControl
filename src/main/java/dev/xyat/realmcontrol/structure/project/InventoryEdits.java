package dev.xyat.realmcontrol.structure.project;

import dev.xyat.realmcontrol.util.Nbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.BitSet;
import java.util.Set;

/** Only the fixed item list is exposed to the inventory editor, never a loot table or other block entity data. */
public final class InventoryEdits {
    private InventoryEdits() { }

    @Nullable
    public static CompoundTag fixedItems(@Nullable CompoundTag blockEntity) {
        if (blockEntity == null || blockEntity.get("LootTable") != null || blockEntity.get("loot_table") != null
                || !(blockEntity.get("Items") instanceof ListTag items)) return null;
        CompoundTag result = new CompoundTag();
        result.put("Items", items.copy());
        return result;
    }

    @Nullable
    public static CompoundTag parse(String text) {
        CompoundTag inventory = EntityEdits.parse(text);
        return valid(inventory) ? inventory : null;
    }

    public static boolean valid(@Nullable CompoundTag inventory) {
        if (inventory == null || !Nbt.keys(inventory).equals(Set.of("Items"))
                || !(inventory.get("Items") instanceof ListTag items)) return false;
        BitSet used = new BitSet();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof CompoundTag entry) || !(entry.get("Slot") instanceof NumericTag)) return false;
            String id = Nbt.string(entry, "id");
            int rawSlot = Nbt.intValue(entry, "Slot"), slot = rawSlot & 255;
            if (rawSlot < -128 || rawSlot > 255 || used.get(slot) || id.isBlank() || ResourceLocation.tryParse(id) == null) return false;
            used.set(slot);
        }
        return true;
    }

    static boolean fits(CompoundTag inventory, CompoundTag original) {
        int slots = switch (Nbt.string(original, "id")) {
            case "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box" -> 27;
            case "minecraft:dispenser", "minecraft:dropper" -> 9;
            case "minecraft:hopper", "minecraft:brewing_stand" -> 5;
            case "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> 3;
            default -> 256;
        };
        ListTag items = Nbt.compoundList(inventory, "Items");
        for (int i = 0; i < items.size(); i++) {
            if ((Nbt.intValue(Nbt.compoundAt(items, i), "Slot") & 255) >= slots) return false;
        }
        return true;
    }
}
