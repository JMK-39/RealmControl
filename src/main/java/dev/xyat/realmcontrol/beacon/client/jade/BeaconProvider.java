package dev.xyat.realmcontrol.beacon.client.jade;

import dev.xyat.realmcontrol.beacon.util.ColorText;
import dev.xyat.realmcontrol.beacon.config.BeaconConfig;
import dev.xyat.realmcontrol.beacon.mixin.LevelAccess;
import dev.xyat.realmcontrol.beacon.util.BeaconStateManager;
import dev.xyat.realmcontrol.beacon.util.IBeaconAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public class BeaconProvider {
    public static final ResourceLocation ID = new ResourceLocation("realmcontrol", "beacon_info");

    public enum Server implements IServerDataProvider<BlockAccessor> {
        INSTANCE;
        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (accessor.getBlockEntity() instanceof BeaconBlockEntity beacon && beacon instanceof IBeaconAccess ktAccessor) {
                int levels = ((LevelAccess) beacon).realmcontrol_beacon$getLevels();
                int maxRad = BeaconConfig.getBeaconRadius(levels);
                int maxPreventRad = maxRad >= 0 ? maxRad + 1 : -1;

                data.putInt("BeaconModuleLevel", levels);
                data.putInt("BeaconModuleMaxRadius", maxRad);
                data.putInt("BeaconModuleMaxPreventRadius", maxPreventRad);
                data.putInt("BeaconModuleActualLoad", ktAccessor.realmcontrol_beacon$getActualChunkLoadRadius(levels, maxRad));
                data.putInt("BeaconModuleActualPrevent", ktAccessor.realmcontrol_beacon$getActualSpawnPreventRadius(levels, maxPreventRad));
                data.putInt("BeaconModuleJadeSpawnType", ktAccessor.realmcontrol_beacon$getSpawnPreventType());
                data.putString("BeaconModuleJadeSpawnCodes", ktAccessor.realmcontrol_beacon$getSpawnPreventCodes());

                if (accessor.getLevel().getServer() != null) {
                    BeaconStateManager state = BeaconStateManager.get(accessor.getLevel().getServer());
                    boolean perPlayer = BeaconConfig.perPlayerLimitEnabled;
                    data.putBoolean("BeaconModulePerPlayer", perPlayer);
                    data.putInt("BeaconModuleGlobalMax", BeaconConfig.globalChunkLoadLimit);

                    if (perPlayer) {
                        data.putInt("BeaconModulePersonalUsed", state.getUsedQuota(ktAccessor.realmcontrol_beacon$getOwner()));
                        data.putInt("BeaconModulePersonalMax", BeaconConfig.perPlayerChunkLoadLimit);
                    } else {
                        data.putInt("BeaconModuleGlobalUsed", state.getUsedQuota(null));
                    }
                }
            }
        }
        @Override public ResourceLocation getUid() { return ID; }
    }

    public enum Client implements IBlockComponentProvider {
        INSTANCE;
        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            CompoundTag data = accessor.getServerData();
            if (!data.contains("BeaconModuleLevel")) return;

            int level = data.getInt("BeaconModuleLevel");
            if (level <= 0) return;

            int maxRad = data.getInt("BeaconModuleMaxRadius");
            int maxPreventRad = data.getInt("BeaconModuleMaxPreventRadius");
            int actLoad = data.getInt("BeaconModuleActualLoad");
            int actPrev = data.getInt("BeaconModuleActualPrevent");
            int spType = data.getInt("BeaconModuleJadeSpawnType");
            String codes = data.getString("BeaconModuleJadeSpawnCodes");

            Component loadText = actLoad >= 0
                    ? ColorText.translatable("jade.realmcontrol.beacon.cl.on", actLoad * 2 + 1, actLoad * 2 + 1, maxRad * 2 + 1, maxRad * 2 + 1)
                    : ColorText.translatable("jade.realmcontrol.beacon.cl.off");
            tooltip.add(loadText);

            if (actLoad >= 0 && data.contains("BeaconModuleGlobalMax")) {
                boolean perPlayer = data.getBoolean("BeaconModulePerPlayer");
                int globalMax = data.getInt("BeaconModuleGlobalMax");

                if (perPlayer) {
                    int pUsed = data.getInt("BeaconModulePersonalUsed");
                    int pMax = data.getInt("BeaconModulePersonalMax");
                    int remain = Math.max(0, pMax - pUsed);
                    tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.quota_both", pUsed, remain, globalMax));
                } else {
                    int gUsed = data.getInt("BeaconModuleGlobalUsed");
                    int remain = Math.max(0, globalMax - gUsed);
                    Component typeTx = ColorText.translatable("msg.realmcontrol.beacon.beacon.quota_global");
                    tooltip.add(ColorText.translatable("tip.realmcontrol.beacon.beacon.quota_single", typeTx.getString(), gUsed, remain));
                }
            }

            Component spTypeTx = ColorText.translatable("gui.realmcontrol.beacon.beacon.type." + spType);
            Component spCodeTx = codes.isEmpty() ? ColorText.translatable("gui.realmcontrol.beacon.beacon.type.global") : Component.literal(codes.toUpperCase());

            Component prevText = actPrev >= 0
                    ? ColorText.translatable("jade.realmcontrol.beacon.sp.on", actPrev * 2 + 1, actPrev * 2 + 1, maxPreventRad * 2 + 1, maxPreventRad * 2 + 1, spTypeTx, spCodeTx)
                    : ColorText.translatable("jade.realmcontrol.beacon.sp.off");
            tooltip.add(prevText);
        }

        @Override public ResourceLocation getUid() { return ID; }
    }
}
