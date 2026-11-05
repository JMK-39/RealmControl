package dev.xyat.realmcontrol.structure.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.kineticcore.api.command.CommandExtension;
import dev.xyat.kineticcore.api.command.CommandText;
import dev.xyat.kineticcore.api.command.KineticCommands;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.structure.StructureEditorModule;
import dev.xyat.realmcontrol.structure.network.StructureEditorNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** {@code /kt structure} opens the structure editor; {@code /kt structure wand} hands out the controller. */
public final class StructureCommand implements CommandExtension {
    private StructureCommand() {
    }

    public static void install() {
        KineticCommands.registerExtension(StructureEditorModule.MODID + "_structure", new StructureCommand());
    }

    @Override
    public void registerCommands(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("structure").requires(source -> source.hasPermission(2))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    StructureEditorNetwork.sendProjects(player, null, true);
                    return 1;
                })
                .then(Commands.literal("wand").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ItemStack stack = new ItemStack(StructureEditorModule.CONTROLLER.get());
                    if (!player.getInventory().add(stack)) player.drop(stack, false);
                    context.getSource().sendSuccess(() -> KineticI18n.translatable("msg.realmcontrol.structure.wand_given"), false);
                    return 1;
                })));
    }

    @Override
    public void appendHelpItems(CommandSourceStack source, List<MutableComponent> items) {
        if (source.hasPermission(2)) {
            items.add(CommandText.executable("/kt structure", "cmd.realmcontrol.structure.desc"));
            items.add(CommandText.executable("/kt structure wand", "cmd.realmcontrol.structure.wand.desc"));
        }
    }

    @Override
    public void reload(CommandSourceStack source) {
    }
}
