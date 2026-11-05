package dev.xyat.realmcontrol.worldgen.mixin;

import dev.xyat.realmcontrol.structure.mega.MegaCoverage;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Other structures do not start inside a mega structure that asks for it (see {@link MegaCoverage}). */
@Mixin(ChunkGenerator.class)
public abstract class MegaStructureSuppressMixin {
    @Inject(method = "createStructures", at = @At("HEAD"))
    private void realmcontrol_worldgen$checkMegaCoverage(RegistryAccess access, ChunkGeneratorStructureState state, StructureManager structureManager,
                                                         ChunkAccess chunk, StructureTemplateManager templates,
                                                         //? if >=26.1 {
                                                         /*net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level,
                                                         *///?}
                                                         CallbackInfo ci) {
        MegaCoverage.begin(access, state, (ChunkGenerator) (Object) this, chunk);
    }

    @Inject(method = "createStructures", at = @At("RETURN"))
    private void realmcontrol_worldgen$endMegaCoverage(CallbackInfo ci) {
        MegaCoverage.end();
    }

    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void realmcontrol_worldgen$skipInsideMega(StructureSet.StructureSelectionEntry entry, StructureManager structureManager, RegistryAccess access,
                                                      RandomState randomState, StructureTemplateManager templates, long seed, ChunkAccess chunk,
                                                      ChunkPos pos, SectionPos section,
                                                      //? if >=26.1 {
                                                      /*net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level,
                                                      *///?}
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (MegaCoverage.skip(entry.structure().value())) cir.setReturnValue(false);
    }
}
