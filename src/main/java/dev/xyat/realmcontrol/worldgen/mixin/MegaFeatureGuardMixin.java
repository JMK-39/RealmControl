package dev.xyat.realmcontrol.worldgen.mixin;

import dev.xyat.realmcontrol.structure.mega.MegaFeatureGuard;
import net.minecraft.world.level.levelgen.feature.BlockBlobFeature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lakes and forest boulders are not placed where they would cut into a mega structure (see {@link MegaFeatureGuard}). */
@Mixin({LakeFeature.class, BlockBlobFeature.class})
public abstract class MegaFeatureGuardMixin {
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void realmcontrol_worldgen$keepOutOfMega(FeaturePlaceContext<?> context, CallbackInfoReturnable<Boolean> cir) {
        // A lake reaches 8 blocks around its origin, a boulder a few.
        if (MegaFeatureGuard.blocked(context.level(), context.origin(), 9)) cir.setReturnValue(false);
    }
}
