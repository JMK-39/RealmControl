package dev.xyat.realmcontrol.beacon.mixin;

import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BeaconBlockEntity.class)
public interface LevelAccess {
    @Accessor("levels")
    int realmcontrol_beacon$getLevels();

    @Accessor("levels")
    void realmcontrol_beacon$setLevels(int levels);
}
