package dev.xyat.realmcontrol.teleport.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.kineticcore.api.command.CommandText;
import dev.xyat.kineticcore.api.command.KineticCommands;
import dev.xyat.kineticcore.api.command.CommandExtension;
import dev.xyat.realmcontrol.teleport.TeleportModule;
import dev.xyat.realmcontrol.teleport.TpdCommand;
import dev.xyat.realmcontrol.teleport.config.TpdConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

public final class TpdCommandExtension implements CommandExtension {
    private TpdCommandExtension() {
    }

    public static void install() {
        KineticCommands.registerExtension(TeleportModule.MODID, new TpdCommandExtension());
    }

    @Override
    public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        TpdCommand.register(root);
    }

    @Override
    public void appendHelpItems(CommandSourceStack source, List<MutableComponent> items) {
        items.add(CommandText.executable(
                "/kt tpd help",
                "cmd.realmcontrol.teleport.tpd.desc"
        ));
    }

    @Override
    public void reload(CommandSourceStack source) {
        TpdConfig.load();
    }
}
