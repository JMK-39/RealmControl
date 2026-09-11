package dev.xyat.realmcontrol.teleport.mixin;

import com.chaosthedude.naturescompass.items.NaturesCompassItem;
import com.chaosthedude.naturescompass.network.TeleportPacket;
import com.chaosthedude.naturescompass.util.CompassState;
import com.chaosthedude.naturescompass.util.ItemUtils;
import com.chaosthedude.naturescompass.util.PlayerUtils;
import dev.xyat.realmcontrol.teleport.api.ITeleportAuth;
import dev.xyat.realmcontrol.teleport.config.TpdConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

public final class NaturesCompassMixins {
    private NaturesCompassMixins() {
    }

    @Mixin(value = PlayerUtils.class, remap = false)
    public static class Permission {
        @Inject(method = "canTeleport", at = @At("HEAD"), cancellable = true)
        private static void realmcontrol_tpd$alwaysAllowNaturesTeleport(MinecraftServer server, Player player, CallbackInfoReturnable<Boolean> cir) {
            if (TpdConfig.enableTpModify) {
                cir.setReturnValue(true);
            }
        }
    }

    @Mixin(value = TeleportPacket.class, remap = false)
    public static abstract class Logic {
        @Shadow
        private int findValidTeleportHeight(Level level, int x, int z) {
            return 256;
        }

        @Overwrite
        public void handle(Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                ServerPlayer player = ctx.get().getSender();
                if (player == null || player.getServer() == null) return;

                ItemStack stack = ItemUtils.getHeldNatureCompass(player);
                if (!(stack.getItem() instanceof NaturesCompassItem natureCompass)) return;

                boolean allowed = player.getServer().getPlayerList().isOp(player.getGameProfile());
                if (!allowed) {
                    ITeleportAuth auth = (ITeleportAuth) player;
                    if (auth.hasTpAuth()) {
                        auth.consumeTpAuth();
                        allowed = true;
                        realmcontrol_tpd$sendTpFeedback(player, auth);
                    } else {
                        player.sendSystemMessage(Component.translatable("cmd.realmcontrol.teleport.tpd.no_auth"));
                    }
                }

                if (allowed && natureCompass.getState(stack) == CompassState.FOUND) {
                    int x = natureCompass.getFoundBiomeX(stack);
                    int z = natureCompass.getFoundBiomeZ(stack);
                    int y = findValidTeleportHeight(player.level(), x, z);
                    player.stopRiding();
                    player.connection.teleport(x, y, z, player.getYRot(), player.getXRot());
                    if (!player.isFallFlying()) {
                        player.setDeltaMovement(player.getDeltaMovement().x(), 0, player.getDeltaMovement().z());
                        player.setOnGround(true);
                    }
                }
            });
            ctx.get().setPacketHandled(true);
        }

        @Unique
        private void realmcontrol_tpd$sendTpFeedback(ServerPlayer player, ITeleportAuth auth) {
            long now = System.currentTimeMillis();
            Component message;
            if (auth.realmcontrol_tpd$getTpExpiry() > now) {
                long left = (auth.realmcontrol_tpd$getTpExpiry() - now) / 1000;
                message = Component.translatable("cmd.realmcontrol.teleport.tpd.remaining.time", left);
            } else {
                message = Component.translatable("cmd.realmcontrol.teleport.tpd.remaining.count", auth.realmcontrol_tpd$getTpCount());
            }
            player.displayClientMessage(message, true);
        }
    }
}
