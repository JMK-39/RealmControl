package dev.xyat.realmcontrol.teleport.mixin;

import com.chaosthedude.explorerscompass.ExplorersCompass;
import com.chaosthedude.explorerscompass.items.ExplorersCompassItem;
import com.chaosthedude.explorerscompass.network.TeleportPacket;
import com.chaosthedude.explorerscompass.util.CompassState;
import com.chaosthedude.explorerscompass.util.ItemUtils;
import com.chaosthedude.explorerscompass.util.PlayerUtils;
import dev.xyat.kineticcore.api.text.KineticI18n;
import dev.xyat.realmcontrol.teleport.api.ITeleportAuth;
import dev.xyat.realmcontrol.teleport.config.TpdConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
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
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.function.Supplier;

public final class ExplorersCompassMixins {
    private ExplorersCompassMixins() {
    }

    @Mixin(value = PlayerUtils.class, remap = false)
    public static class Permission {
        @Inject(method = "canTeleport", at = @At("HEAD"), cancellable = true)
        private static void realmcontrol_tpd$alwaysAllowExplorersTeleport(MinecraftServer server, Player player, CallbackInfoReturnable<Boolean> cir) {
            if (TpdConfig.enableTpModify) {
                cir.setReturnValue(player.hasPermissions(2) || player instanceof ITeleportAuth auth && auth.hasTpAuth());
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

                ItemStack stack = ItemUtils.getHeldItem(player, ExplorersCompass.explorersCompass);
                if (!(stack.getItem() instanceof ExplorersCompassItem explorersCompass)) return;

                boolean canExecute = player.getServer().getPlayerList().isOp(player.getGameProfile());
                if (!canExecute) {
                    ITeleportAuth auth = (ITeleportAuth) player;
                    if (auth.hasTpAuth()) {
                        auth.consumeTpAuth();
                        canExecute = true;
                        realmcontrol_tpd$sendTpFeedback(player, auth);
                    } else {
                        player.sendSystemMessage(KineticI18n.translatable("cmd.realmcontrol.teleport.tpd.no_auth"));
                    }
                }

                if (canExecute && explorersCompass.getState(stack) == CompassState.FOUND) {
                    int x = explorersCompass.getFoundStructureX(stack);
                    int z = explorersCompass.getFoundStructureZ(stack);
                    int y = findValidTeleportHeight(player.level(), x, z);
                    player.stopRiding();
                    player.teleportTo(x, y, z);
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
                message = KineticI18n.translatable("cmd.realmcontrol.teleport.tpd.remaining.time", left);
            } else {
                message = KineticI18n.translatable("cmd.realmcontrol.teleport.tpd.remaining.count", auth.realmcontrol_tpd$getTpCount());
            }
            player.displayClientMessage(message, true);
        }
    }

    @Mixin(value = TeleportPacket.class, remap = false)
    public static abstract class EnhancedLogic {
        @Redirect(
                method = "*",
                require = 1,
                at = @At(
                        value = "INVOKE",
                        target = "Lnet/minecraft/server/level/ServerPlayer;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",
                        remap = true
                )
        )
        private boolean realmcontrol_tpd$authorizeEnhancedTeleport(ServerPlayer player, ServerLevel level, double x, double y, double z, Set<RelativeMovement> relative, float yRot, float xRot) {
            if (!TpdConfig.enableTpModify || player.hasPermissions(2)) {
                return player.teleportTo(level, x, y, z, relative, yRot, xRot);
            }

            ITeleportAuth auth = (ITeleportAuth) player;
            if (!auth.hasTpAuth()) {
                player.sendSystemMessage(KineticI18n.translatable("cmd.realmcontrol.teleport.tpd.no_auth"));
                return false;
            }

            boolean teleported = player.teleportTo(level, x, y, z, relative, yRot, xRot);
            if (teleported) {
                auth.consumeTpAuth();
                realmcontrol_tpd$sendTpFeedback(player, auth);
            }
            return teleported;
        }

        @Unique
        private void realmcontrol_tpd$sendTpFeedback(ServerPlayer player, ITeleportAuth auth) {
            long now = System.currentTimeMillis();
            Component message;
            if (auth.realmcontrol_tpd$getTpExpiry() > now) {
                long left = (auth.realmcontrol_tpd$getTpExpiry() - now) / 1000;
                message = KineticI18n.translatable("cmd.realmcontrol.teleport.tpd.remaining.time", left);
            } else {
                message = KineticI18n.translatable("cmd.realmcontrol.teleport.tpd.remaining.count", auth.realmcontrol_tpd$getTpCount());
            }
            player.displayClientMessage(message, true);
        }
    }

}
