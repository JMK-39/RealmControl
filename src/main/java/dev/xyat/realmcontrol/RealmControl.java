package dev.xyat.realmcontrol;

import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import dev.xyat.realmcontrol.beacon.BeaconModule;
import dev.xyat.realmcontrol.teleport.TeleportModule;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(RealmControl.MODID)
public final class RealmControl {
    public static final String MODID = "realmcontrol";

    public RealmControl(FMLJavaModLoadingContext context) {
        new WorldBlockModule(context);
        new WorldGenModule(context);
        new BeaconModule(context);
        new TeleportModule(context);
    }
}
