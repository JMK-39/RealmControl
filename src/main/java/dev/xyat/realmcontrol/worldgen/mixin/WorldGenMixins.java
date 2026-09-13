package dev.xyat.realmcontrol.worldgen.mixin;

import dev.xyat.realmcontrol.worldgen.config.BiomeGenerationControl;
import dev.xyat.realmcontrol.worldgen.config.StructureGenerationControl;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

public class WorldGenMixins {
    @Mixin(MinecraftServer.class)
    public static abstract class MinecraftServerMixin {
        @Inject(method = "prepareLevels(Lnet/minecraft/server/level/progress/ChunkProgressListener;)V", at = @At("HEAD"))
        private void realmcontrol_worldgen$applyStructureGenerationRules(ChunkProgressListener progressListener, CallbackInfo ci) {
            MinecraftServer server = (MinecraftServer) (Object) this;
            BiomeGenerationControl.applyBeforeWorldPreparation(server);
            StructureGenerationControl.applyBeforeWorldPreparation(server);
        }
    }
}
