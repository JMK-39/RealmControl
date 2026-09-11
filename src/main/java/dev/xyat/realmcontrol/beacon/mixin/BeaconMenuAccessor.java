package dev.xyat.realmcontrol.beacon.mixin;

import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BeaconMenu.class)
public interface BeaconMenuAccessor {
    @Accessor("access")
    ContainerLevelAccess realmcontrol_beacon$getAccess();
}
