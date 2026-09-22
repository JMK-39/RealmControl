package dev.xyat.realmcontrol.worldblock.command;

import dev.xyat.kineticcore.api.command.KineticCommands;
import dev.xyat.kineticcore.api.command.CommandExtension;
import dev.xyat.realmcontrol.worldblock.WorldBlockModule;
import dev.xyat.realmcontrol.worldblock.config.WorldBlockConfig;
import dev.xyat.realmcontrol.worldblock.network.WorldBlockNetwork;
import net.minecraft.commands.CommandSourceStack;

public final class WorldBlockCommandExtension implements CommandExtension {
    private WorldBlockCommandExtension() {
    }

    public static void install() {
        KineticCommands.registerExtension(WorldBlockModule.MODID, new WorldBlockCommandExtension());
    }

    @Override
    public void reload(CommandSourceStack source) {
        WorldBlockConfig.load();
        WorldBlockNetwork.syncServerConfigToAllPlayers();
    }
}
