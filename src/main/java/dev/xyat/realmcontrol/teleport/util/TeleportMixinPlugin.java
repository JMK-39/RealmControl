package dev.xyat.realmcontrol.teleport.util;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.util.List;
import java.util.Set;

public final class TeleportMixinPlugin implements IMixinConfigPlugin {
    private static final String EXPLORERS_TELEPORT_PACKET = "com.chaosthedude.explorerscompass.network.TeleportPacket";
    private static final String LEGACY_HEIGHT_METHOD = "findValidTeleportHeight";
    private static final String LEGACY_HEIGHT_DESC = "(Lnet/minecraft/world/level/Level;II)I";

    private Boolean legacyExplorersCompass;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("ExplorersCompassMixins$Logic")) {
            return isLegacyExplorersCompass();
        }
        if (mixinClassName.endsWith("ExplorersCompassMixins$EnhancedLogic")) {
            return !isLegacyExplorersCompass();
        }
        return true;
    }

    private boolean isLegacyExplorersCompass() {
        if (legacyExplorersCompass == null) {
            legacyExplorersCompass = detectLegacyExplorersCompass();
        }
        return legacyExplorersCompass;
    }

    private static boolean detectLegacyExplorersCompass() {
        try {
            ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(EXPLORERS_TELEPORT_PACKET);
            for (MethodNode method : node.methods) {
                if (LEGACY_HEIGHT_METHOD.equals(method.name) && LEGACY_HEIGHT_DESC.equals(method.desc)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
