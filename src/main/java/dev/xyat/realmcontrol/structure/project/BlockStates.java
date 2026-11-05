package dev.xyat.realmcontrol.structure.project;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Block states as strings such as {@code minecraft:oak_stairs[facing=east,half=bottom]}: the form drafts keep, so a
 * block from a mod that is gone keeps its name and properties and can still be reported and written out.
 */
public final class BlockStates {
    public static final String AIR = "minecraft:air";

    private BlockStates() {
    }

    public static String serialize(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** The state a string names, or {@code null} if its block is not registered here. */
    @Nullable
    public static BlockState parse(String name) {
        try {
            return BlockStateParser.parseForBlock(lookup(), name, false).blockState();
        } catch (CommandSyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** The block id of a state string, without its properties. */
    public static String blockId(String name) {
        int bracket = name.indexOf('[');
        return bracket < 0 ? name : name.substring(0, bracket);
    }

    /** The properties of a state string, in their written order. */
    public static Map<String, String> properties(String name) {
        Map<String, String> properties = new LinkedHashMap<>();
        int open = name.indexOf('['), close = name.lastIndexOf(']');
        if (open < 0 || close <= open) return properties;
        for (String pair : name.substring(open + 1, close).split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0) properties.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        return properties;
    }

    /** The block registry as templates and state parsing look blocks up. */
    public static HolderLookup<Block> lookup() {
        //? if >=26.1 {
        /*return BuiltInRegistries.BLOCK;
        *///?} else {
        return BuiltInRegistries.BLOCK.asLookup();
        //?}
    }
}
