package dev.xyat.realmcontrol.teleport.mixin;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xyat.realmcontrol.teleport.api.ITeleportAuth;
import dev.xyat.realmcontrol.teleport.config.TpdConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.TeleportCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.function.Predicate;

@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {
    @Redirect(
            method = "register",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/brigadier/builder/LiteralArgumentBuilder;requires(Ljava/util/function/Predicate;)Lcom/mojang/brigadier/builder/ArgumentBuilder;",
                    remap = false
            )
    )
    private static ArgumentBuilder<CommandSourceStack, ?> realmcontrol_tpd$makeTpVisible(
            LiteralArgumentBuilder<CommandSourceStack> instance,
            Predicate<CommandSourceStack> original
    ) {
        if (TpdConfig.enableTpModify && "AUTHORIZED".equals(TpdConfig.tpMode)) {
            return instance.requires(source -> true);
        }
        return instance.requires(original);
    }

    @Inject(method = "teleportToPos", at = @At("HEAD"), cancellable = true)
    private static void realmcontrol_tpd$interceptPos(
            CommandSourceStack source,
            Collection<?> targets,
            ServerLevel level,
            Coordinates position,
            @Nullable Coordinates rotation,
            @Coerce @Nullable Object lookAt,
            CallbackInfoReturnable<Integer> callback
    ) {
        if (realmcontrol_tpd$checkAndConsumeAuth(source, targets)) callback.setReturnValue(0);
    }

    @Inject(method = "teleportToEntity", at = @At("HEAD"), cancellable = true)
    private static void realmcontrol_tpd$interceptEntity(
            CommandSourceStack source,
            Collection<?> targets,
            Entity destination,
            CallbackInfoReturnable<Integer> callback
    ) {
        if (realmcontrol_tpd$checkAndConsumeAuth(source, targets)) callback.setReturnValue(0);
    }

    @Unique
    private static boolean realmcontrol_tpd$checkAndConsumeAuth(CommandSourceStack source, Collection<?> targets) {
        if (!TpdConfig.enableTpModify || source.hasPermission(3)) return false;
        if (source.hasPermission(2) && TpdConfig.adminTpBypass) return false;
        if ("FREE".equals(TpdConfig.tpMode)) return false;

        if (!(source.getEntity() instanceof ServerPlayer player)) return true;

        for (Object target : targets) {
            if (target instanceof Entity entity && entity != player) {
                player.sendSystemMessage(Component.translatable("cmd.realmcontrol.teleport.tpd.self_only"));
                return true;
            }
        }

        ITeleportAuth auth = (ITeleportAuth) player;
        if (auth.hasTpAuth()) {
            auth.consumeTpAuth();
            realmcontrol_tpd$sendFeedback(player, auth);
            return false;
        }

        source.sendFailure(realmcontrol_tpd$getDeniedMessage(TpdConfig.tpDenyCustomMessage, player.getScoreboardName()));
        return true;
    }

    @Unique
    private static void realmcontrol_tpd$sendFeedback(ServerPlayer player, ITeleportAuth auth) {
        long now = System.currentTimeMillis();
        Component message = auth.realmcontrol_tpd$getTpExpiry() > now
                ? Component.translatable(
                        "cmd.realmcontrol.teleport.tpd.remaining.time",
                        (auth.realmcontrol_tpd$getTpExpiry() - now) / 1000L
                )
                : Component.translatable("cmd.realmcontrol.teleport.tpd.remaining.count", auth.realmcontrol_tpd$getTpCount());
        player.displayClientMessage(message, true);
    }

    @Unique
    private static Component realmcontrol_tpd$getDeniedMessage(String configuredMessage, String playerName) {
        if (configuredMessage == null || configuredMessage.isEmpty()) {
            return Component.translatable("cmd.realmcontrol.teleport.tpd.no_auth");
        }
        String formatted = configuredMessage.replace("{player}", playerName);
        return Component.literal(formatted.replace('&', '\u00A7'));
    }
}
