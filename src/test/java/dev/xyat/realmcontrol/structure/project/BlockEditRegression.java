package dev.xyat.realmcontrol.structure.project;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks persisted block states and fixed inventories through the same draft readers as export and placement. */
public final class BlockEditRegression {
    private static final BlockPos CORNER = new BlockPos(32, 3, 32);
    private static final BlockPos CHEST = new BlockPos(33, 3, 32);
    private static final BlockPos RANDOM = new BlockPos(34, 3, 32);
    private static final String LOG_X = "minecraft:oak_log[axis=x]", LOG_Z = "minecraft:oak_log[axis=z]";

    public static void main(String[] args) throws IOException {
        Path folder = Files.createTempDirectory("realmcontrol-block-edit-");
        try {
            writeDraft(folder);
            checkEdits(folder);
            checkFocus();
            System.out.println("BlockEditRegression passed");
        } finally {
            ProjectStore.deleteTree(folder);
        }
    }

    private static void writeDraft(Path folder) throws IOException {
        StructureProject project = new StructureProject();
        project.id = "test:structure";
        project.size = new int[]{65, 5, 33};
        project.origin = new int[]{-1024, 65, 700};
        project.tileSize = 32;
        project.status = "published";
        project.blocks = 4;
        ProjectStore.writeJson(folder.resolve("project.json"), project.toJson());
        var markers = new Markers();
        markers.containers.add(container(CHEST));
        markers.containers.add(container(RANDOM));
        ProjectStore.writeJson(folder.resolve("markers.json"), markers.toJson());
        Tile west = new Tile(32, 5, 1), east = new Tile(32, 5, 1);
        west.cells[west.cell(31, 3, 0)] = (short) west.index(LOG_X);
        east.cells[east.cell(0, 3, 0)] = (short) east.index(LOG_X);
        east.cells[east.cell(1, 3, 0)] = (short) east.index("minecraft:chest");
        east.cells[east.cell(2, 3, 0)] = (short) east.index("minecraft:chest");
        CompoundTag fixed = inventory("minecraft:stick", 0);
        fixed.putString("id", "minecraft:chest");
        fixed.putString("CustomName", "original name");
        east.blockEntities.put(east.cell(1, 3, 0), fixed);
        CompoundTag random = fixed.copy();
        random.remove("Items");
        random.putString("LootTable", "minecraft:chests/simple_dungeon");
        east.blockEntities.put(east.cell(2, 3, 0), random);
        west.write(ProjectStore.tile(folder.resolve("tiles"), 0, 1));
        east.write(ProjectStore.tile(folder.resolve("tiles"), 1, 1));
    }

    private static void checkEdits(Path folder) throws IOException {
        try (ProjectStore.Draft old = ProjectStore.openDraft(folder)) {
            require(ProjectStore.replaceBlock(old, CORNER, LOG_X, LOG_Z) == ProjectStore.BlockEditStatus.SAVED,
                    "persist block-state property at exact tile corner");
            require(LOG_X.equals(old.readTile(1, 1).state(0, 3, 0)), "reader pinned before edit still sees original");
            require(ProjectStore.replaceBlock(old, CORNER, LOG_X, BlockStates.AIR) == ProjectStore.BlockEditStatus.CONFLICT,
                    "stale cells may not overwrite a changed tile");
            try (ProjectStore.Draft updated = ProjectStore.openDraft(folder)) {
                require(updated.project().blockRevision == 1 && updated.project().status.equals("modified"), "edit marks published draft modified");
                require(LOG_Z.equals(updated.readTile(1, 1).state(0, 3, 0)) && LOG_X.equals(updated.readTile(0, 1).state(31, 3, 0)),
                        "only the selected cell changes across the tile boundary");
                require(ProjectStore.replaceBlock(updated, CHEST, "minecraft:chest", BlockStates.AIR) == ProjectStore.BlockEditStatus.PROTECTED,
                        "ordinary edit cannot remove a block entity");
                require(ProjectStore.replaceInventory(updated, RANDOM, inventory("minecraft:stick", 0), inventory("minecraft:diamond", 0))
                        == ProjectStore.BlockEditStatus.PROTECTED, "an untouched loot table never turns into fixed inventory");
                require(ProjectStore.replaceInventory(updated, CHEST, inventory("minecraft:stick", 0), inventory("minecraft:diamond", 27))
                        == ProjectStore.BlockEditStatus.PROTECTED, "reject a chest slot that would not be loaded");
                require(ProjectStore.replaceInventory(updated, CHEST, inventory("minecraft:stick", 0), inventory("minecraft:diamond", 4))
                        == ProjectStore.BlockEditStatus.SAVED, "save a fixed item in its chosen slot");
            }
        }
        try (ProjectStore.Draft updated = ProjectStore.openDraft(folder)) {
            Tile tile = updated.readTile(1, 1);
            CompoundTag data = tile.blockEntities.get(tile.cell(1, 3, 0));
            require(InventoryEdits.fixedItems(data).equals(inventory("minecraft:diamond", 4)), "reload the edited fixed inventory");
            require(data.get("CustomName") != null && data.get("LootTable") == null && data.get("LootTableSeed") == null,
                    "preserve unrelated NBT without creating a loot table");
            require(ProjectStore.replaceBlock(updated, CORNER, LOG_Z, BlockStates.AIR) == ProjectStore.BlockEditStatus.SAVED, "remove an exact block");
        }
        try (ProjectStore.Draft removed = ProjectStore.openDraft(folder)) {
            require(removed.project().blocks == 3 && removed.project().blockRevision == 3, "persist removal and updated block count");
            require(BlockStates.AIR.equals(removed.readTile(1, 1).state(0, 3, 0)), "preview/export will read a removed cell as air");
        }
    }

    private static Markers.Container container(BlockPos pos) {
        Markers.Container marker = new Markers.Container();
        marker.x = pos.getX();
        marker.y = pos.getY();
        marker.z = pos.getZ();
        marker.block = "minecraft:chest";
        return marker;
    }

    private static CompoundTag inventory(String id, int slot) {
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) slot);
        item.putString("id", id);
        item.putByte("Count", (byte) 1);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag contents = new CompoundTag();
        contents.put("Items", items);
        return contents;
    }

    private static void checkFocus() {
        StructureProject huge = new StructureProject();
        huge.size = new int[]{4096, 384, 4096};
        require(new BlockPos(4064, 352, 4064).equals(ProjectPreview.windowOrigin(huge, new BlockPos(4095, 383, 4095))),
                "window includes the last block of a 4096 draft");
        require(new BlockPos(32, 4, 33).equals(ProjectPreview.position("32,4,33", huge)), "exact focus keeps offsets");
        require(ProjectPreview.position("4096,0,0", huge) == null && ProjectPreview.position("-1,0,0", huge) == null,
                "reject out-of-bounds focus coordinates");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
