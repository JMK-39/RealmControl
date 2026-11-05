package dev.xyat.realmcontrol.structure.project;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One tile of a draft: the full content of a column of the scanned region before cut-outs are applied. Blocks are
 * palette indices (the palette holds state strings, entry 0 is air), packed into longs on disk the way chunk sections
 * pack them. Block entity and entity data are kept as vanilla saves them, at positions relative to the tile.
 */
public final class Tile {
    private static final int MAGIC = 0x52435431; // "RCT1"
    /** Largest palette a tile can hold. */
    public static final int MAX_PALETTE = 65535;

    public final int sizeX, sizeY, sizeZ;
    /** Palette index per cell, at {@code x + sizeX * (z + sizeZ * y)}. */
    public final short[] cells;
    public final List<String> palette = new ArrayList<>();
    /** Block entity data by cell index. */
    public final Map<Integer, CompoundTag> blockEntities = new HashMap<>();
    public final List<EntityData> entities = new ArrayList<>();
    private final Map<String, Integer> paletteIndex = new HashMap<>();

    /** An entity at a position relative to the tile's minimum corner. */
    public record EntityData(double x, double y, double z, CompoundTag tag) {
    }

    public Tile(int sizeX, int sizeY, int sizeZ) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.cells = new short[sizeX * sizeY * sizeZ];
        palette.add(BlockStates.AIR);
        paletteIndex.put(BlockStates.AIR, 0);
    }

    public int cell(int x, int y, int z) {
        return x + sizeX * (z + sizeZ * y);
    }

    /** Palette index for a state string, adding it if new; air (0) once the palette is full. */
    public int index(String state) {
        Integer index = paletteIndex.get(state);
        if (index != null) return index;
        if (palette.size() >= MAX_PALETTE) return 0;
        palette.add(state);
        paletteIndex.put(state, palette.size() - 1);
        return palette.size() - 1;
    }

    public String state(int x, int y, int z) {
        return palette.get(cells[cell(x, y, z)] & 0xFFFF);
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temp), 65536)))) {
            out.writeInt(MAGIC);
            out.writeInt(sizeX);
            out.writeInt(sizeY);
            out.writeInt(sizeZ);
            out.writeInt(palette.size());
            for (String state : palette) out.writeUTF(state);
            int bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            int perLong = 64 / bits;
            int longs = (cells.length + perLong - 1) / perLong;
            out.writeByte(bits);
            out.writeInt(longs);
            long mask = (1L << bits) - 1;
            for (int i = 0; i < longs; i++) {
                long packed = 0L;
                int base = i * perLong;
                for (int j = 0; j < perLong && base + j < cells.length; j++) {
                    packed |= ((long) (cells[base + j] & 0xFFFF) & mask) << (j * bits);
                }
                out.writeLong(packed);
            }
            out.writeInt(blockEntities.size());
            for (Map.Entry<Integer, CompoundTag> entry : blockEntities.entrySet()) {
                out.writeInt(entry.getKey());
                NbtIo.write(entry.getValue(), out);
            }
            out.writeInt(entities.size());
            for (EntityData entity : entities) {
                out.writeDouble(entity.x());
                out.writeDouble(entity.y());
                out.writeDouble(entity.z());
                NbtIo.write(entity.tag(), out);
            }
        }
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    public static Tile read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 65536)))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a structure tile: " + file);
            int sizeX = in.readInt(), sizeY = in.readInt(), sizeZ = in.readInt();
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || (long) sizeX * sizeY * sizeZ > 64L * 64 * 4096) {
                throw new IOException("Bad tile size in " + file);
            }
            Tile tile = new Tile(sizeX, sizeY, sizeZ);
            int paletteSize = in.readInt();
            if (paletteSize < 1 || paletteSize > MAX_PALETTE) throw new IOException("Bad palette in " + file);
            tile.palette.clear();
            tile.paletteIndex.clear();
            for (int i = 0; i < paletteSize; i++) {
                String state = in.readUTF();
                tile.palette.add(state);
                tile.paletteIndex.putIfAbsent(state, i);
            }
            int bits = in.readByte();
            int perLong = 64 / bits;
            int longs = in.readInt();
            long mask = (1L << bits) - 1;
            for (int i = 0; i < longs; i++) {
                long packed = in.readLong();
                int base = i * perLong;
                for (int j = 0; j < perLong && base + j < tile.cells.length; j++) {
                    int index = (int) ((packed >>> (j * bits)) & mask);
                    tile.cells[base + j] = (short) (index < paletteSize ? index : 0);
                }
            }
            int blockEntities = in.readInt();
            for (int i = 0; i < blockEntities; i++) {
                int cell = in.readInt();
                tile.blockEntities.put(cell, NbtIo.read(in));
            }
            int entities = in.readInt();
            for (int i = 0; i < entities; i++) {
                tile.entities.add(new EntityData(in.readDouble(), in.readDouble(), in.readDouble(), NbtIo.read(in)));
            }
            return tile;
        }
    }
}
