package dev.xyat.realmcontrol.teleport.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.kineticcore.command.CommandUtils;
import dev.xyat.kineticcore.command.KTCommandApi;
import dev.xyat.kineticcore.command.KTCommandExtension;
import dev.xyat.realmcontrol.teleport.TeleportModule;
import dev.xyat.realmcontrol.teleport.TpdCommand;
import dev.xyat.realmcontrol.teleport.config.TpdConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public final class TpdCommandExtension implements KTCommandExtension {
    private TpdCommandExtension() {
    }

    public static void install() {
        KTCommandApi.register(TeleportModule.MODID, new TpdCommandExtension());
    }

    @Override
    public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        TpdCommand.register(root);
    }

    @Override
    public void appendHelpItems(CommandSourceStack source, List<MutableComponent> items) {
        items.add(CommandUtils.createExecutableCommand(
                "/kt tpd help",
                "cmd.realmcontrol.teleport.tpd.desc"
        ));
    }

    @Override
    public void reload(CommandSourceStack source) {
        TpdConfig.load();
    }
}
