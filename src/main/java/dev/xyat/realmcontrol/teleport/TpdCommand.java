package dev.xyat.realmcontrol.teleport;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.kineticcore.api.command.CommandText;
import dev.xyat.realmcontrol.teleport.api.ITeleportAuth;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;

public final class TpdCommand {
    private TpdCommand() {
    }

    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        LiteralArgumentBuilder<CommandSourceStack> tpd = Commands.literal("tpd");

        tpd.then(Commands.literal("help").executes(ctx -> sendHelp(ctx.getSource())));
        tpd.executes(ctx -> checkSelf(ctx.getSource()));

        tpd.then(Commands.literal("allow")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("count")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                        .executes(ctx -> grantCount(
                                                ctx.getSource(),
                                                EntityArgument.getPlayers(ctx, "targets"),
                                                IntegerArgumentType.getInteger(ctx, "amount")
                                        )))))
                .then(Commands.literal("time")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                        .executes(ctx -> grantTime(
                                                ctx.getSource(),
                                                EntityArgument.getPlayers(ctx, "targets"),
                                                IntegerArgumentType.getInteger(ctx, "seconds")
                                        )))))
        );

        tpd.then(Commands.literal("clear")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("targets", EntityArgument.players())
                        .executes(ctx -> clearAuth(ctx.getSource(), EntityArgument.getPlayers(ctx, "targets")))));

        tpd.then(Commands.literal("check")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> checkOther(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))));

        root.then(tpd);
    }

    private static int sendHelp(CommandSourceStack source) {
        MutableComponent message = CommandText.header("cmd.realmcontrol.teleport.tpd.desc").append("\n");
        message.append(CommandText.executable("/kt tpd", "cmd.realmcontrol.teleport.tpd.help.self"));
        if (source.hasPermission(2)) {
            message.append("\n").append(CommandText.createSuggestCommand(
                    "/kt tpd check <player>", "/kt tpd check ", "cmd.realmcontrol.teleport.tpd.help.check"));
            message.append("\n").append(CommandText.createSuggestCommand(
                    "/kt tpd allow count <player> <num>", "/kt tpd allow count ", "cmd.realmcontrol.teleport.tpd.help.allow_count"));
            message.append("\n").append(CommandText.createSuggestCommand(
                    "/kt tpd allow time <player> <sec>", "/kt tpd allow time ", "cmd.realmcontrol.teleport.tpd.help.allow_time"));
            message.append("\n").append(CommandText.createSuggestCommand(
                    "/kt tpd clear <player>", "/kt tpd clear ", "cmd.realmcontrol.teleport.tpd.help.clear"));
        }
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int checkSelf(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        ITeleportAuth auth = (ITeleportAuth) player;
        MutableComponent message = Component.translatable("cmd.realmcontrol.teleport.tpd.status.prefix");
        appendStatus(message, auth);
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int checkOther(CommandSourceStack source, ServerPlayer target) {
        ITeleportAuth auth = (ITeleportAuth) target;
        MutableComponent message = Component.translatable(
                "cmd.realmcontrol.teleport.tpd.status.other_prefix",
                target.getName()
        );
        appendStatus(message, auth);
        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static void appendStatus(MutableComponent message, ITeleportAuth auth) {
        boolean hasAny = false;
        long now = System.currentTimeMillis();

        if (auth.realmcontrol_tpd$getTpExpiry() > now) {
            long secondsLeft = (auth.realmcontrol_tpd$getTpExpiry() - now) / 1000L;
            message.append(Component.translatable("cmd.realmcontrol.teleport.tpd.status.time", secondsLeft));
            hasAny = true;
        }

        if (auth.realmcontrol_tpd$getTpCount() > 0) {
            if (hasAny) message.append(Component.translatable("cmd.realmcontrol.teleport.tpd.status.separator"));
            message.append(Component.translatable("cmd.realmcontrol.teleport.tpd.status.count", auth.realmcontrol_tpd$getTpCount()));
            hasAny = true;
        }

        if (!hasAny) {
            message.append(Component.translatable("cmd.realmcontrol.teleport.tpd.status.none"));
        }
    }

    private static int grantCount(CommandSourceStack source, Collection<ServerPlayer> targets, int amount) {
        for (ServerPlayer player : targets) {
            ITeleportAuth auth = (ITeleportAuth) player;
            int total = auth.realmcontrol_tpd$getTpCount() + amount;
            auth.realmcontrol_tpd$setTpCount(total);
            source.sendSuccess(
                    () -> Component.translatable("cmd.realmcontrol.teleport.tpd.grant.count.admin", player.getName(), total),
                    true
            );
            player.sendSystemMessage(Component.translatable("cmd.realmcontrol.teleport.tpd.grant.count.player", amount, total));
        }
        return targets.size();
    }

    private static int grantTime(CommandSourceStack source, Collection<ServerPlayer> targets, int seconds) {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : targets) {
            ITeleportAuth auth = (ITeleportAuth) player;
            long current = auth.realmcontrol_tpd$getTpExpiry();
            long newExpiry = Math.max(current, now) + seconds * 1000L;
            auth.realmcontrol_tpd$setTpExpiry(newExpiry);
            long secondsLeft = (newExpiry - now) / 1000L;
            source.sendSuccess(
                    () -> Component.translatable(
                            "cmd.realmcontrol.teleport.tpd.grant.time.admin",
                            player.getName(), seconds, secondsLeft
                    ),
                    true
            );
            player.sendSystemMessage(Component.translatable(
                    "cmd.realmcontrol.teleport.tpd.grant.time.player",
                    seconds, secondsLeft
            ));
        }
        return targets.size();
    }

    private static int clearAuth(CommandSourceStack source, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            ITeleportAuth auth = (ITeleportAuth) player;
            auth.realmcontrol_tpd$setTpCount(0);
            auth.realmcontrol_tpd$setTpExpiry(0L);
            source.sendSuccess(
                    () -> Component.translatable("cmd.realmcontrol.teleport.tpd.clear.success", player.getName()),
                    true
            );
        }
        return targets.size();
    }
}
