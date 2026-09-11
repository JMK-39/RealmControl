package dev.xyat.realmcontrol.worldblock.command;

import dev.xyat.kineticcore.command.KTCommandApi;
import dev.xyat.kineticcore.command.KTCommandExtension;
import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import net.minecraft.commands.CommandSourceStack;

public final class WorldBlockCommandExtension implements KTCommandExtension {
    private WorldBlockCommandExtension() {
    }

    public static void install() {
        KTCommandApi.register(WorldBlockModule.MODID, new WorldBlockCommandExtension());
    }

    @Override
    public void reload(CommandSourceStack source) {
        WorldBlockConfig.load();
        WorldBlockNetwork.syncServerConfigToAllPlayers();
    }
}
