package dev.xyat.realmcontrol.teleport.mixin;

import dev.xyat.realmcontrol.teleport.util.CommandFlag;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntitySelector.class)
public abstract class EntitySelectorMixin {
    @Inject(method = "checkPermissions", at = @At("HEAD"), cancellable = true)
    private void realmcontrol_tpd$bypassSelfSelectorAuth(CommandSourceStack source, CallbackInfo callback) {
        EntitySelector selector = (EntitySelector) (Object) this;
        if (CommandFlag.get() && selector.isSelfSelector()) {
            callback.cancel();
        }
    }
}
