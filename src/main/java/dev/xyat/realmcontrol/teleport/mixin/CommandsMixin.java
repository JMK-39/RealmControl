package dev.xyat.realmcontrol.teleport.mixin;

import com.mojang.brigadier.ParseResults;
import dev.xyat.realmcontrol.teleport.util.CommandFlag;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "performCommand", at = @At("HEAD"))
    private void realmcontrol_tpd$onCommandStart(
            ParseResults<CommandSourceStack> results,
            String command,
            CallbackInfoReturnable<Integer> callback
    ) {
        String clean = command.startsWith("/") ? command.substring(1) : command;
        String trimmed = clean.stripLeading();
        int separator = trimmed.indexOf(' ');
        String root = separator < 0 ? trimmed : trimmed.substring(0, separator);
        CommandFlag.set("tp".equals(root) || "teleport".equals(root));
    }

    @Inject(method = "performCommand", at = @At("RETURN"))
    private void realmcontrol_tpd$onCommandEnd(
            ParseResults<CommandSourceStack> results,
            String command,
            CallbackInfoReturnable<Integer> callback
    ) {
        CommandFlag.set(false);
    }
}
