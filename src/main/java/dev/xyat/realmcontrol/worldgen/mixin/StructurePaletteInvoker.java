package dev.xyat.realmcontrol.worldgen.mixin;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Builds a vanilla palette that reuses an editor tile's already decoded block records. */
@Mixin(StructureTemplate.Palette.class)
public interface StructurePaletteInvoker {
    @Invoker("<init>")
    static StructureTemplate.Palette realmcontrol$fromBlocks(List<StructureTemplate.StructureBlockInfo> blocks) {
        throw new AssertionError();
    }
}
