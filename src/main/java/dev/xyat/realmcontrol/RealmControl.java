package dev.xyat.realmcontrol;

import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import dev.xyat.realmcontrol.beacon.BeaconModule;
import dev.xyat.realmcontrol.teleport.TeleportModule;
import net.minecraftforge.fml.common.Mod;
//? if forge {
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
//?}

@Mod(RealmControl.MODID)
public final class RealmControl {
    public static final String MODID = "realmcontrol";

    //? if forge {
    public RealmControl(FMLJavaModLoadingContext context) {
    //?} else {
    /*public RealmControl() {
    *///?}
        new WorldBlockModule();
        new WorldGenModule();
        new BeaconModule();
        new TeleportModule();
    }
}
