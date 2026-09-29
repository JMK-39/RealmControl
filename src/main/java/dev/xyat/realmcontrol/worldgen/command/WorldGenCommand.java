package dev.xyat.realmcontrol.worldgen.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.kineticcore.api.command.CommandText;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.worldgen.util.StructureUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.stream.Collectors;

public class WorldGenCommand {
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> world = Commands.literal("world");

        world.then(Commands.literal("structure").executes(ctx -> checkCurrentPosStructures(ctx.getSource())));
        world.then(Commands.literal("list-structures").requires(source -> source.hasPermission(2)).executes(ctx -> listAllStructures(ctx.getSource())));
        world.then(Commands.literal("help").executes(ctx -> sendHelp(ctx.getSource())));
        world.executes(ctx -> sendHelp(ctx.getSource()));

        root.then(world);
    }

    private static int sendHelp(CommandSourceStack source) {
        MutableComponent msg = CommandText.header("cmd.realmcontrol.worldgen.world.desc").append("\n");
        msg.append(CommandText.executable("/kt world structure", "cmd.realmcontrol.worldgen.world.structure.desc"));
        if (source.hasPermission(2)) {
            msg.append("\n").append(CommandText.executable("/kt world list-structures", "cmd.realmcontrol.worldgen.world.list_structures.desc"));
        }
        source.sendSuccess(() -> msg, false);
        return 1;
    }

    private static int checkCurrentPosStructures(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            List<String> structures = StructureUtils.getStructuresAt(player.serverLevel(), player.blockPosition());

            if (structures.isEmpty()) {
                source.sendSuccess(() -> KineticI18n.translatable("msg.realmcontrol.worldgen.structure.not_found"), false);
                return 1;
            }

            MutableComponent msg = KineticI18n.translatable("msg.realmcontrol.worldgen.structure.found_simple");
            for (String id : structures) {
                msg.append(CommandText.clickToCopy(KineticI18n.translatable("msg.realmcontrol.worldgen.structure.entry", id),
                        id, KineticI18n.translatable("msg.realmcontrol.worldgen.click_to_copy")));
            }
            source.sendSuccess(() -> msg, false);
        } catch (Exception e) {
            source.sendFailure(KineticI18n.translatable("msg.realmcontrol.worldgen.structure.check_failed", String.valueOf(e.getMessage())));
        }
        return 1;
    }

    private static int listAllStructures(CommandSourceStack source) {
        try {
            List<String> ids = source.getServer().registryAccess().registryOrThrow(Registries.STRUCTURE).keySet().stream()
                    .map(ResourceLocation::toString)
                    .sorted()
                    .collect(Collectors.toList());

            String allIdsStr = String.join("\n", ids);
            MutableComponent msg = KineticI18n.translatable("msg.realmcontrol.worldgen.list.structures", ids.size());
            if (!ids.isEmpty()) {
                msg.append(Component.literal("  "));
                msg.append(CommandText.clickToCopy(KineticI18n.translatable("msg.realmcontrol.worldgen.click_to_copy_all")
                                .withStyle(style -> style.withBold(true).withUnderlined(true)),
                        allIdsStr, KineticI18n.translatable("cmd.realmcontrol.worldgen.copy.too_long")));
            }
            source.sendSuccess(() -> msg, false);
        } catch (Exception e) {
            source.sendFailure(KineticI18n.translatable("msg.realmcontrol.worldgen.structure.list_failed"));
        }
        return 1;
    }
}
