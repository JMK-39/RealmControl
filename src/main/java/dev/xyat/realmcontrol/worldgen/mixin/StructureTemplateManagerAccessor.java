package dev.xyat.realmcontrol.worldgen.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Optional;

/** The templates vanilla keeps loaded, from which mega tiles are dropped again. */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerAccessor {
    @Accessor("structureRepository")
    Map<ResourceLocation, Optional<StructureTemplate>> realmcontrol_worldgen$templates();
}
