package dev.xyat.realmcontrol.worldgen.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.kineticcore.command.CommandUtils;
import dev.xyat.kineticcore.command.KTCommandApi;
import dev.xyat.kineticcore.command.KTCommandExtension;
import dev.xyat.realmcontrol.worldgen.WorldGenModule;
import dev.xyat.realmcontrol.worldgen.config.WorldGenConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public final class WorldGenCommandExtension implements KTCommandExtension {
    private WorldGenCommandExtension() {
    }

    public static void install() {
        KTCommandApi.register(WorldGenModule.MODID, new WorldGenCommandExtension());
    }

    @Override
    public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        WorldGenCommand.register(root);
    }

    @Override
    public void appendHelpItems(CommandSourceStack source, List<MutableComponent> items) {
        items.add(CommandUtils.createExecutableCommand("/kt world help", "cmd.realmcontrol.worldgen.world.desc"));
    }

    @Override
    public void reload(CommandSourceStack source) {
        WorldGenConfig.load();
    }
}
