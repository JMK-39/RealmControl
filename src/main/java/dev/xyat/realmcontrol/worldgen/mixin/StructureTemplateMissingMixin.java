package dev.xyat.realmcontrol.worldgen.mixin;

import dev.xyat.realmcontrol.worldgen.preview.TemplateMissingTracker;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Notes the unknown blocks of each loaded template and which templates a structure preview places. */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateMissingMixin {
    @Inject(method = "load", at = @At("TAIL"))
    private void realmcontrol_worldgen$recordMissingBlocks(HolderGetter<Block> blocks, CompoundTag tag, CallbackInfo ci) {
        TemplateMissingTracker.recordLoaded((StructureTemplate) (Object) this, tag);
    }

    @Inject(method = "placeInWorld", at = @At("HEAD"))
    private void realmcontrol_worldgen$notePlaced(CallbackInfoReturnable<Boolean> cir) {
        TemplateMissingTracker.notePlaced((StructureTemplate) (Object) this);
    }
}
