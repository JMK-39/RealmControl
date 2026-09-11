package dev.xyat.realmcontrol.worldblock.mixin;

import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.util.WorldgenBlockRewriteHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

public final class WorldBlockMixins {
    private WorldBlockMixins() {
    }

    @Mixin(LevelChunkSection.class)
    public static class LevelChunkSectionMixin {
        @ModifyVariable(
                method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
                at = @At("HEAD"),
                argsOnly = true,
                ordinal = 0,
                require = 0
        )
        private BlockState realmcontrol_worldblock$rewriteRawGeneratedBlockState(BlockState state) {
            if (!WorldBlockConfig.hasRawWorldgenRewriteRules) return state;
            return WorldgenBlockRewriteHelper.rewriteFromRawChunkSection(state);
        }
    }
}
