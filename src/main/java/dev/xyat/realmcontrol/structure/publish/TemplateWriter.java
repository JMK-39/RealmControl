package dev.xyat.realmcontrol.structure.publish;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a vanilla structure template ({@code .nbt}) straight to a file, block by block, so a large template is never
 * held in memory as one tag tree. The layout is the one structure blocks save: {@code size}, {@code palette},
 * {@code blocks}, {@code entities} and {@code DataVersion}.
 */
final class TemplateWriter implements AutoCloseable {
    private static final byte END = 0, INT = 3, DOUBLE = 6, STRING = 8, LIST = 9, COMPOUND = 10;
    private final DataOutputStream out;
    private int blocksLeft;
    private int entitiesLeft;

    TemplateWriter(Path file, int sizeX, int sizeY, int sizeZ, List<String> palette, int blocks) throws IOException {
        Files.createDirectories(file.getParent());
        out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file), 65536)));
        out.writeByte(COMPOUND);
        out.writeUTF("");
        intList("size", sizeX, sizeY, sizeZ);
        // Palette entries in the form NbtUtils writes: Name, then Properties when there are any.
        listHeader("palette", COMPOUND, palette.size());
        for (String state : palette) {
            out.writeByte(STRING);
            out.writeUTF("Name");
            out.writeUTF(dev.xyat.realmcontrol.structure.project.BlockStates.blockId(state));
            Map<String, String> properties = dev.xyat.realmcontrol.structure.project.BlockStates.properties(state);
            if (!properties.isEmpty()) {
                out.writeByte(COMPOUND);
                out.writeUTF("Properties");
                for (Map.Entry<String, String> property : properties.entrySet()) {
                    out.writeByte(STRING);
                    out.writeUTF(property.getKey());
                    out.writeUTF(property.getValue());
                }
                out.writeByte(END);
            }
            out.writeByte(END);
        }
        listHeader("blocks", COMPOUND, blocks);
        blocksLeft = blocks;
    }

    /** One block; exactly as many as announced, before the entities. */
    void block(int x, int y, int z, int state, CompoundTag nbt) throws IOException {
        if (blocksLeft-- <= 0) throw new IllegalStateException("More blocks than announced");
        intList("pos", x, y, z);
        out.writeByte(INT);
        out.writeUTF("state");
        out.writeInt(state);
        if (nbt != null) namedTag("nbt", nbt);
        out.writeByte(END);
    }

    void startEntities(int count) throws IOException {
        if (blocksLeft != 0) throw new IllegalStateException(blocksLeft + " announced blocks were not written");
        listHeader("entities", COMPOUND, count);
        entitiesLeft = count;
    }

    void entity(double x, double y, double z, CompoundTag nbt) throws IOException {
        if (entitiesLeft-- <= 0) throw new IllegalStateException("More entities than announced");
        listHeader("pos", DOUBLE, 3);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        intList("blockPos", (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        namedTag("nbt", nbt);
        out.writeByte(END);
    }

    void finish(int dataVersion) throws IOException {
        if (entitiesLeft != 0) throw new IllegalStateException(entitiesLeft + " announced entities were not written");
        out.writeByte(INT);
        out.writeUTF("DataVersion");
        out.writeInt(dataVersion);
        out.writeByte(END);
    }

    private void namedTag(String name, Tag tag) throws IOException {
        out.writeByte(tag.getId());
        out.writeUTF(name);
        tag.write(out);
    }

    private void intList(String name, int... values) throws IOException {
        listHeader(name, INT, values.length);
        for (int value : values) out.writeInt(value);
    }

    private void listHeader(String name, byte type, int length) throws IOException {
        out.writeByte(LIST);
        out.writeUTF(name);
        out.writeByte(length == 0 ? END : type);
        out.writeInt(length);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
