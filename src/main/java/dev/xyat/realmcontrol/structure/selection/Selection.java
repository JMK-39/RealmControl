package dev.xyat.realmcontrol.structure.selection;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.xyat.kineticcore.api.network.NetworkBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * One player's selection in one dimension: the two corners of the region and the cut-out boxes drawn in the world,
 * in world coordinates. Cut-out boxes become the project's cut-outs when the region is scanned.
 */
public final class Selection {
    /** Largest footprint along X and Z. */
    public static final int MAX_FOOTPRINT = 1024;
    /** Largest number of blocks one scan reads. */
    public static final long MAX_VOLUME = 200_000_000L;
    public static final int MAX_CUTOUTS = 256;

    @Nullable public BlockPos a;
    @Nullable public BlockPos b;
    /** First corner of the cut-out box being drawn. */
    @Nullable public BlockPos cutA;
    public final List<Box> cutouts = new ArrayList<>();

    /** An axis-aligned box of blocks, both corners included. */
    public record Box(BlockPos min, BlockPos max) {
        public static Box of(BlockPos first, BlockPos second) {
            return new Box(new BlockPos(Math.min(first.getX(), second.getX()), Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ())),
                    new BlockPos(Math.max(first.getX(), second.getX()), Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ())));
        }

        public int sizeX() {
            return max.getX() - min.getX() + 1;
        }

        public int sizeY() {
            return max.getY() - min.getY() + 1;
        }

        public int sizeZ() {
            return max.getZ() - min.getZ() + 1;
        }

        public long volume() {
            return (long) sizeX() * sizeY() * sizeZ();
        }

        public boolean contains(int x, int y, int z) {
            return x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY() && z >= min.getZ() && z <= max.getZ();
        }

        /** This box with one face moved outwards by {@code amount} blocks (inwards when negative), never thinner than a block. */
        public Box push(Direction face, int amount) {
            int minX = min.getX(), minY = min.getY(), minZ = min.getZ(), maxX = max.getX(), maxY = max.getY(), maxZ = max.getZ();
            switch (face) {
                case EAST -> maxX = Math.max(minX, maxX + amount);
                case WEST -> minX = Math.min(maxX, minX - amount);
                case UP -> maxY = Math.max(minY, maxY + amount);
                case DOWN -> minY = Math.min(maxY, minY - amount);
                case SOUTH -> maxZ = Math.max(minZ, maxZ + amount);
                case NORTH -> minZ = Math.min(maxZ, minZ - amount);
            }
            return new Box(new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
        }
    }

    /** The selected region, or {@code null} until both corners are set. */
    @Nullable
    public Box region() {
        return a == null || b == null ? null : Box.of(a, b);
    }

    /** Why the region cannot be scanned, as a translation key, or {@code null} if it can. */
    @Nullable
    public static String problem(@Nullable Box region) {
        if (region == null) return "msg.realmcontrol.structure.selection.incomplete";
        if (region.sizeX() > MAX_FOOTPRINT || region.sizeZ() > MAX_FOOTPRINT) return "msg.realmcontrol.structure.selection.too_wide";
        if (region.volume() > MAX_VOLUME) return "msg.realmcontrol.structure.selection.too_big";
        return null;
    }

    public boolean isEmpty() {
        return a == null && b == null && cutA == null && cutouts.isEmpty();
    }

    public void write(NetworkBuffer buf) {
        writePos(buf, a);
        writePos(buf, b);
        writePos(buf, cutA);
        buf.writeVarInt(cutouts.size());
        for (Box box : cutouts) {
            writePos(buf, box.min());
            writePos(buf, box.max());
        }
    }

    public static Selection read(NetworkBuffer buf) {
        Selection selection = new Selection();
        selection.a = readPos(buf);
        selection.b = readPos(buf);
        selection.cutA = readPos(buf);
        int count = Math.min(buf.readVarInt(), MAX_CUTOUTS);
        for (int i = 0; i < count; i++) {
            BlockPos min = readPos(buf), max = readPos(buf);
            if (min != null && max != null) selection.cutouts.add(Box.of(min, max));
        }
        return selection;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        if (a != null) json.add("a", posJson(a));
        if (b != null) json.add("b", posJson(b));
        if (cutA != null) json.add("cut_a", posJson(cutA));
        JsonArray boxes = new JsonArray();
        for (Box box : cutouts) {
            JsonArray entry = new JsonArray();
            entry.add(posJson(box.min()));
            entry.add(posJson(box.max()));
            boxes.add(entry);
        }
        json.add("cutouts", boxes);
        return json;
    }

    static Selection fromJson(JsonObject json) {
        Selection selection = new Selection();
        selection.a = json.has("a") ? posOf(json.getAsJsonArray("a")) : null;
        selection.b = json.has("b") ? posOf(json.getAsJsonArray("b")) : null;
        selection.cutA = json.has("cut_a") ? posOf(json.getAsJsonArray("cut_a")) : null;
        if (json.has("cutouts")) {
            for (var element : json.getAsJsonArray("cutouts")) {
                JsonArray entry = element.getAsJsonArray();
                if (selection.cutouts.size() < MAX_CUTOUTS) {
                    selection.cutouts.add(Box.of(posOf(entry.get(0).getAsJsonArray()), posOf(entry.get(1).getAsJsonArray())));
                }
            }
        }
        return selection;
    }

    public static JsonArray posJson(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    public static BlockPos posOf(JsonArray array) {
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    public static void writePos(NetworkBuffer buf, @Nullable BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeInt(pos.getX());
            buf.writeInt(pos.getY());
            buf.writeInt(pos.getZ());
        }
    }

    @Nullable
    public static BlockPos readPos(NetworkBuffer buf) {
        return buf.readBoolean() ? new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()) : null;
    }
}
